// JavaScript side of the WebAssembly DSP: chroma, chord templates, live
// recognition with smoothing, whole-file recognition (Viterbi), tuner.
import { formatChord, type Chord } from '../lib/music';

interface Exports {
  memory: WebAssembly.Memory;
  inputPtr(): number;
  chromaPtr(): number;
  pitchConfidence(): number;
  chroma(n: number, sr: number): number;
  yin(n: number, sr: number, threshold: number): number;
}

export class Dsp {
  private constructor(private x: Exports) {}

  static async load(source: string | ArrayBuffer | Uint8Array = '/dsp.wasm'): Promise<Dsp> {
    let bytes: ArrayBuffer | Uint8Array;
    if (typeof source === 'string') bytes = await (await fetch(source)).arrayBuffer();
    else bytes = source;
    const { instance } = await WebAssembly.instantiate(bytes as BufferSource, { env: { abort: () => {}, seed: () => Date.now() } });
    return new Dsp(instance.exports as unknown as Exports);
  }

  private write(samples: Float32Array) {
    new Float32Array(this.x.memory.buffer, this.x.inputPtr(), samples.length).set(samples);
  }

  /** Chroma of a power-of-two frame (normalised to max 1) and its RMS. */
  chroma(frame: Float32Array, sampleRate: number): { chroma: Float32Array; rms: number } {
    this.write(frame);
    const rms = this.x.chroma(frame.length, sampleRate);
    const c = new Float32Array(this.x.memory.buffer, this.x.chromaPtr(), 12).slice();
    const m = Math.max(...c);
    if (m > 0) for (let i = 0; i < 12; i++) c[i] /= m;
    return { chroma: c, rms };
  }

  /** Pitch in Hz (or -1) and confidence 0–1. */
  pitch(frame: Float32Array, sampleRate: number, threshold = 0.12): { hz: number; confidence: number } {
    this.write(frame);
    const hz = this.x.yin(frame.length, sampleRate, threshold);
    return { hz, confidence: this.x.pitchConfidence() };
  }
}

// ---------------------------------------------------------------- templates

const QUALITIES: { suffix: string; intervals: number[]; prior: number }[] = [
  { suffix: '', intervals: [0, 4, 7], prior: 1 },
  { suffix: 'm', intervals: [0, 3, 7], prior: 1 },
  { suffix: '7', intervals: [0, 4, 7, 10], prior: 0.93 },
  { suffix: 'm7', intervals: [0, 3, 7, 10], prior: 0.92 },
  { suffix: 'maj7', intervals: [0, 4, 7, 11], prior: 0.9 },
  { suffix: 'sus4', intervals: [0, 5, 7], prior: 0.88 },
  { suffix: 'sus2', intervals: [0, 2, 7], prior: 0.86 },
  { suffix: 'dim', intervals: [0, 3, 6], prior: 0.85 },
];

export interface Template {
  chord: Chord;
  name: string;
  vec: Float32Array;
  prior: number;
}

export function templates(simple = false): Template[] {
  const out: Template[] = [];
  for (const q of simple ? QUALITIES.slice(0, 2) : QUALITIES)
    for (let r = 0; r < 12; r++) {
      const vec = new Float32Array(12);
      q.intervals.forEach((iv, i) => (vec[(r + iv) % 12] = i === 0 ? 1.2 : 1));
      const n = Math.hypot(...vec);
      for (let i = 0; i < 12; i++) vec[i] /= n;
      const chord = { root: r, suffix: q.suffix };
      out.push({ chord, name: formatChord(chord), vec, prior: q.prior });
    }
  return out;
}

export function score(chroma: Float32Array, t: Template): number {
  const n = Math.hypot(...chroma) || 1;
  let s = 0;
  for (let i = 0; i < 12; i++) s += (chroma[i] / n) * t.vec[i];
  return s * t.prior;
}

/** Live recognition: smooths chroma over time and only switches on a clear winner. */
export class LiveChords {
  private avg = new Float32Array(12);
  private current: Template | null = null;
  private candidate: Template | null = null;
  private streak = 0;
  constructor(
    private tpl = templates(),
    private smoothing = 0.6,
  ) {}

  push(chroma: Float32Array, rms: number): { chord: Template | null; confidence: number; ranking: { name: string; score: number }[] } {
    if (rms < 0.004) {
      for (let i = 0; i < 12; i++) this.avg[i] *= 0.8;
      return { chord: this.current, confidence: 0, ranking: [] };
    }
    for (let i = 0; i < 12; i++) this.avg[i] = this.smoothing * this.avg[i] + (1 - this.smoothing) * chroma[i];
    const ranked = this.tpl.map((t) => ({ t, s: score(this.avg, t) })).sort((a, b) => b.s - a.s);
    const best = ranked[0];
    if (best.t === this.candidate) this.streak++;
    else {
      this.candidate = best.t;
      this.streak = 1;
    }
    if (this.streak >= 2 && best.s > 0.55) this.current = best.t;
    return { chord: this.current, confidence: best.s, ranking: ranked.slice(0, 4).map((r) => ({ name: r.t.name, score: r.s })) };
  }
}

export interface Segment {
  start: number;
  end: number;
  chord: string | null;
}

/**
 * Whole recording → chord segments. Frame chroma, then a Viterbi pass over
 * the chord states (plus "no chord") with a strong preference to stay on the
 * same chord, so the result is stable instead of flickering.
 */
export function recognise(dsp: Dsp, samples: Float32Array, sampleRate: number, opts: { frame?: number; hop?: number; simple?: boolean; onProgress?: (p: number) => void } = {}): Segment[] {
  const frame = opts.frame ?? (sampleRate > 30000 ? 8192 : 4096);
  const hop = opts.hop ?? frame / 2;
  const tpl = templates(opts.simple);
  const S = tpl.length + 1; // last = no chord
  const frames: { obs: Float32Array }[] = [];
  const buf = new Float32Array(frame);
  let peak = 0;
  for (let i = 0; i < samples.length; i += 997) peak = Math.max(peak, Math.abs(samples[i]));
  const silence = Math.max(0.003, peak * 0.03);
  const total = Math.max(1, Math.floor((samples.length - frame) / hop));
  for (let f = 0, pos = 0; pos + frame <= samples.length; f++, pos += hop) {
    buf.set(samples.subarray(pos, pos + frame));
    const { chroma, rms } = dsp.chroma(buf, sampleRate);
    const obs = new Float32Array(S);
    if (rms < silence) {
      obs.fill(-6);
      obs[S - 1] = 0;
    } else {
      for (let s = 0; s < tpl.length; s++) obs[s] = 9 * (score(chroma, tpl[s]) - 1);
      obs[S - 1] = -4.5;
    }
    frames.push({ obs });
    if (f % 50 === 0) opts.onProgress?.(f / total);
  }
  if (!frames.length) return [];
  const stay = Math.log(0.92);
  const move = Math.log(0.08 / (S - 1));
  let prev = Float64Array.from(frames[0].obs);
  const back: Uint8Array[] = [];
  for (let f = 1; f < frames.length; f++) {
    let bestPrev = 0;
    for (let s = 1; s < S; s++) if (prev[s] > prev[bestPrev]) bestPrev = s;
    const cur = new Float64Array(S);
    const bp = new Uint8Array(S);
    for (let s = 0; s < S; s++) {
      const a = prev[s] + stay;
      const b = prev[bestPrev] + move;
      if (a >= b || bestPrev === s) {
        cur[s] = a + frames[f].obs[s];
        bp[s] = s;
      } else {
        cur[s] = b + frames[f].obs[s];
        bp[s] = bestPrev;
      }
    }
    back.push(bp);
    prev = cur;
  }
  let s = 0;
  for (let i = 1; i < S; i++) if (prev[i] > prev[s]) s = i;
  const path = new Array<number>(frames.length);
  path[frames.length - 1] = s;
  for (let f = frames.length - 1; f > 0; f--) path[f - 1] = back[f - 1][path[f]];
  const segs: Segment[] = [];
  const secPerHop = hop / sampleRate;
  path.forEach((st, f) => {
    const name = st === S - 1 ? null : tpl[st].name;
    const t = f * secPerHop;
    const last = segs[segs.length - 1];
    if (last && last.chord === name) last.end = t + frame / sampleRate;
    else segs.push({ start: t, end: t + frame / sampleRate, chord: name });
  });
  // Drop blips shorter than ~0.4 s.
  const merged: Segment[] = [];
  for (const g of segs) {
    const last = merged[merged.length - 1];
    if (last && (g.end - g.start < 0.4 || last.chord === g.chord)) last.end = g.end;
    else merged.push({ ...g });
  }
  opts.onProgress?.(1);
  return merged;
}

/** Segments → ChordPro, four chords per line. */
export function segmentsToChordPro(segs: Segment[], title: string): string {
  const chords = segs.filter((s) => s.chord).map((s) => s.chord!);
  const lines: string[] = [];
  for (let i = 0; i < chords.length; i += 4) lines.push(chords.slice(i, i + 4).map((c) => `[${c}]`).join(' '));
  return `{title: ${title}}\n{comment: Accordi riconosciuti dall’audio}\n\n${lines.join('\n')}\n`;
}

// ---------------------------------------------------------------- tuner

export const A4 = 440;
export function noteFromHz(hz: number, a4 = A4): { midi: number; cents: number } {
  const m = 69 + 12 * Math.log2(hz / a4);
  const midi = Math.round(m);
  return { midi, cents: (m - midi) * 100 };
}
