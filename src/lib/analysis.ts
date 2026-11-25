// "Analizza": from a recording (a video playing, a file, the microphone) to
// how to play it: chords over time, key, tempo, capo, strumming, and the
// lyrics (Whisper word timestamps) with the chords on the right syllables.
// The same algorithms are in the Android app (core/Analysis.kt).
import { detectKey, formatChord, formatKey, parseChord, prefersFlats, suggestCapo, transposeChord, type Chord, type Key } from './music';
import { chordsDifficulty, type Level } from './difficulty';

export interface Segment {
  start: number;
  end: number;
  chord: string | null;
}

export interface Word {
  w: string;
  s: number;
  e: number;
}

// ---------------------------------------------------------------- tempo

/** Tempo from the onset envelope (energy flux) and its autocorrelation, with a prior around 110 BPM. */
export function estimateTempo(samples: Float32Array, sr: number): { bpm: number; confidence: number } {
  const hop = Math.round(sr * 0.0116);
  const n = Math.floor(samples.length / hop);
  if (n < 200) return { bpm: 0, confidence: 0 };
  const env = new Float32Array(n);
  let prevE = 0;
  for (let i = 0; i < n; i++) {
    let e = 0;
    const o = i * hop;
    for (let k = 1; k < hop; k++) {
      // First difference: brings out attacks (strums, drums) over sustained notes.
      const d = samples[o + k] - samples[o + k - 1];
      e += d * d;
    }
    const le = Math.log1p(1000 * e);
    env[i] = Math.max(0, le - prevE);
    prevE = le;
  }
  let mean = 0;
  for (let i = 0; i < n; i++) mean += env[i];
  mean /= n;
  for (let i = 0; i < n; i++) env[i] -= mean;
  const fps = sr / hop;
  const lagOf = (bpm: number) => (60 * fps) / bpm;
  const ac = (lag: number) => {
    const l0 = Math.floor(lag);
    const f = lag - l0;
    let s0 = 0;
    let s1 = 0;
    for (let i = 0; i + l0 + 1 < n; i++) {
      s0 += env[i] * env[i + l0];
      s1 += env[i] * env[i + l0 + 1];
    }
    return (s0 * (1 - f) + s1 * f) / (n - l0);
  };
  let zero = 0;
  for (let i = 0; i < n; i++) zero += env[i] * env[i];
  zero /= n;
  let best = 0;
  let bestScore = -Infinity;
  let bestAc = 0;
  for (let bpm = 60; bpm <= 200; bpm += 0.5) {
    // A beat also lines up with its double: reward both.
    const a = ac(lagOf(bpm)) + 0.5 * ac(lagOf(bpm / 2));
    const prior = Math.exp(-0.5 * (Math.log2(bpm / 110) / 0.9) ** 2);
    const score = a * prior;
    if (score > bestScore) {
      bestScore = score;
      best = bpm;
      bestAc = ac(lagOf(bpm));
    }
  }
  return { bpm: Math.round(best), confidence: zero > 0 ? Math.max(0, Math.min(1, bestAc / zero)) : 0 };
}

// ---------------------------------------------------------------- chords

/** Fewer, steadier chords to play: drops blips shorter than about a beat. */
export function tidySegments(segs: Segment[], bpm = 0): Segment[] {
  const min = Math.max(0.5, bpm ? (60 / bpm) * 0.9 : 0.7);
  const out: Segment[] = [];
  for (const s of segs) {
    const last = out[out.length - 1];
    const short = s.end - s.start < (s.chord ? min : 1);
    if (last && (last.chord === s.chord || short)) last.end = s.end;
    else out.push({ ...s });
  }
  // A blip at the very start goes into the next chord.
  if (out.length > 1 && out[0].end - out[0].start < min) {
    out[1].start = out[0].start;
    out.shift();
  }
  // Merging can leave neighbours with the same chord.
  return out.reduce<Segment[]>((acc, s) => {
    const last = acc[acc.length - 1];
    if (last && last.chord === s.chord) last.end = s.end;
    else acc.push({ ...s });
    return acc;
  }, []);
}

/** Key from the chords, the longer ones counting more. */
export function keyOfSegments(segs: Segment[]): Key | null {
  const list: Chord[] = [];
  for (const s of segs) {
    const c = s.chord ? parseChord(s.chord) : null;
    if (!c) continue;
    const times = Math.max(1, Math.min(6, Math.round((s.end - s.start) / 2)));
    for (let i = 0; i < times; i++) list.push(c);
  }
  return list.length ? detectKey(list) : null;
}

export type Strum = 'ballad' | 'pop' | 'drive';
export const STRUMS: Record<Strum, string> = {
  ballad: '↓ · ↓ · ↓ · ↓↑',
  pop: '↓ · ↓↑ · ↑↓↑',
  drive: '↓↓↓↓ ↓↓↓↓',
};

export interface HowToPlay {
  key: Key | null;
  /** Key name as heard (no capo). */
  keyName: string;
  bpm: number;
  capo: number;
  /** Chords to finger with that capo, in order of appearance. */
  shapes: string[];
  /** The chords as heard. */
  chords: string[];
  level: Level;
  hard: string[];
  strum: Strum;
}

export function howToPlay(segs: Segment[], bpm: number): HowToPlay {
  const chords = [...new Set(segs.filter((s) => s.chord).map((s) => s.chord!))];
  const parsed = chords.map((c) => parseChord(c)).filter((c): c is Chord => !!c);
  const key = keyOfSegments(segs);
  const capo = suggestCapo(parsed)[0]?.capo ?? 0;
  const flats = prefersFlats(key ? { ...key, tonic: (key.tonic - capo + 12) % 12 } : null);
  const shapes = parsed.map((c) => formatChord(transposeChord(c, -capo), 'en', flats));
  const changes = segs.filter((s) => s.chord).length;
  const minutes = Math.max(0.25, (segs.at(-1)?.end ?? 0) / 60);
  // Changes per "line" (about four bars) from changes per minute and tempo.
  const perLine = bpm ? Math.round((changes / minutes) * (16 / bpm)) : 2;
  const d = chordsDifficulty(parsed, perLine);
  const strum: Strum = !bpm || bpm < 80 ? 'ballad' : bpm > 140 ? 'drive' : 'pop';
  return { key, keyName: key ? formatKey(key) : '', bpm, capo, shapes, chords, level: d.level, hard: d.hard, strum };
}

/**
 * The chords as written in a sheet with a capo: shapes (sounding chord minus
 * the capo), spelled for the key, as the song view expects them.
 */
export function asShapes(segs: Segment[], how: HowToPlay): Segment[] {
  const flats = prefersFlats(how.key ? { ...how.key, tonic: (how.key.tonic - how.capo + 12) % 12 } : null);
  return segs.map((s) => {
    const c = s.chord ? parseChord(s.chord) : null;
    return c ? { ...s, chord: formatChord(transposeChord(c, -how.capo), 'en', flats) } : s;
  });
}

// ---------------------------------------------------------------- lyrics

/** Lines of sung words: a new line after a pause, a sentence end, or too many words. */
export function lyricLines(words: Word[]): Word[][] {
  const lines: Word[][] = [];
  let cur: Word[] = [];
  for (const w of words) {
    const prev = cur[cur.length - 1];
    if (prev && (w.s - prev.e > 0.9 || /[.?!]$/.test(prev.w) || (/[,;:]$/.test(prev.w) && cur.length >= 4) || cur.length >= 9)) {
      lines.push(cur);
      cur = [];
    }
    cur.push(w);
  }
  if (cur.length) lines.push(cur);
  return lines;
}

const chordAt = (segs: Segment[], t: number) => segs.find((s) => s.start <= t + 1e-6 && t < s.end)?.chord ?? null;

/**
 * Chords and words on one timeline → ChordPro body. Each chord change goes
 * before the word being sung at that moment; every line starts with the chord
 * playing; changes between sung lines become chord-only lines (Intro,
 * Strumentale, Outro).
 */
export function alignLyrics(segs: Segment[], words: Word[], labels = { intro: 'Intro', solo: 'Strumentale', outro: 'Outro' }): string {
  const changes = segs.filter((s, i) => s.chord && (i === 0 || segs[i - 1].chord !== s.chord || segs[i - 1].end < s.start - 0.05));
  const lines = lyricLines(words);
  const out: string[] = [];
  let ci = 0;
  const chordRow = (until: number, label: string) => {
    const row: string[] = [];
    let start = -1;
    while (ci < changes.length && changes[ci].start < until - 0.3) {
      if (start < 0) start = changes[ci].start;
      row.push(changes[ci].chord!);
      ci++;
    }
    if (!row.length) return;
    // Only a real instrumental part gets a label; a chord just before singing joins the line.
    if (until - start >= 4 || label === 'outro') {
      if (out.length) out.push('');
      out.push(`{comment: ${labels[label as keyof typeof labels]}}`);
      for (let i = 0; i < row.length; i += 4) out.push(row.slice(i, i + 4).map((c) => `[${c}]`).join(' '));
      out.push('');
    } else ci -= row.length;
  };
  lines.forEach((line, li) => {
    const lineStart = line[0].s;
    chordRow(lineStart, li === 0 ? 'intro' : 'solo');
    let text = '';
    line.forEach((w, wi) => {
      const next = line[wi + 1];
      const chords: string[] = [];
      // Changes up to the start of the next word belong to this word.
      // A change just before a word (anticipation) belongs to that word.
      const limit = next ? next.s - 0.3 : w.e + 0.3;
      while (ci < changes.length && changes[ci].start < limit) chords.push(changes[ci++].chord!);
      if (wi === 0 && !chords.length) {
        const playing = chordAt(segs, w.s);
        if (playing) chords.push(playing);
      }
      text += chords.map((c) => `[${c}]`).join('') + w.w + (next ? ' ' : '');
    });
    // A long pause after the line: a blank line between verses.
    const nextLine = lines[li + 1];
    out.push(text);
    if (nextLine && nextLine[0].s - line[line.length - 1].e > 3) out.push('');
  });
  chordRow(Infinity, 'outro');
  while (out[out.length - 1] === '') out.pop();
  return out.join('\n');
}

/** Chords only, four per line, with the bar feel from the tempo. */
export function chordsOnly(segs: Segment[]): string {
  const list = segs.filter((s, i) => s.chord && (i === 0 || segs[i - 1].chord !== s.chord)).map((s) => s.chord!);
  const lines: string[] = [];
  for (let i = 0; i < list.length; i += 4) lines.push(list.slice(i, i + 4).map((c) => `[${c}]`).join(' '));
  return lines.join('\n');
}

export function analysisChordPro(o: { title: string; artist?: string; segs: Segment[]; words?: Word[]; how: HowToPlay; strumLabel: string; source?: string; labels?: { intro: string; solo: string; outro: string } }): string {
  const head = [`{title: ${o.title || 'Senza titolo'}}`];
  if (o.artist) head.push(`{artist: ${o.artist}}`);
  // Key and chords are written as played with the capo (the sheet convention).
  if (o.how.key) head.push(`{key: ${formatKey({ ...o.how.key, tonic: (o.how.key.tonic - o.how.capo + 12) % 12 })}}`);
  if (o.how.capo) head.push(`{capo: ${o.how.capo}}`);
  if (o.how.bpm) head.push(`{tempo: ${o.how.bpm}}`);
  head.push(`{comment: ${o.strumLabel}: ${STRUMS[o.how.strum]}}`);
  const segs = asShapes(o.segs, o.how);
  const body = o.words?.length ? alignLyrics(segs, o.words, o.labels) : chordsOnly(segs);
  return `${head.join('\n')}\n\n${body}\n`;
}

// ---------------------------------------------------------------- audio for Whisper

/** Mono float samples → 16 kHz 16-bit WAV (what Whisper wants). */
export function wav16k(samples: Float32Array, sr: number): Uint8Array {
  const rate = 16000;
  const n = Math.floor((samples.length * rate) / sr);
  const buf = new Uint8Array(44 + n * 2);
  const v = new DataView(buf.buffer);
  const str = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  str(0, 'RIFF');
  v.setUint32(4, 36 + n * 2, true);
  str(8, 'WAVE');
  str(12, 'fmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, 1, true);
  v.setUint32(24, rate, true);
  v.setUint32(28, rate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  str(36, 'data');
  v.setUint32(40, n * 2, true);
  const step = sr / rate;
  for (let i = 0; i < n; i++) {
    // Average over the source samples of this output sample (simple low-pass).
    const a = Math.floor(i * step);
    const b = Math.max(a + 1, Math.floor((i + 1) * step));
    let s = 0;
    for (let k = a; k < b && k < samples.length; k++) s += samples[k];
    const x = Math.max(-1, Math.min(1, s / (b - a)));
    v.setInt16(44 + i * 2, Math.round(x * 32767), true);
  }
  return buf;
}

/** Cut points for ≤ max-second clips, each at the quietest moment of its last 5 seconds. */
export function clipRanges(samples: Float32Array, sr: number, max = 28): [number, number][] {
  const out: [number, number][] = [];
  const win = Math.round(sr * 0.2);
  let start = 0;
  while (start < samples.length) {
    if (samples.length - start <= max * sr) {
      out.push([start, samples.length]);
      break;
    }
    let cut = start + max * sr;
    let quiet = Infinity;
    for (let p = start + (max - 5) * sr; p + win <= start + max * sr; p += win / 2) {
      let e = 0;
      for (let k = p; k < p + win; k++) e += samples[k] * samples[k];
      if (e < quiet) {
        quiet = e;
        cut = p + win / 2;
      }
    }
    out.push([start, Math.round(cut)]);
    start = Math.round(cut);
  }
  return out;
}
