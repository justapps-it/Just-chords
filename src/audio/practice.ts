// The clock of a lesson (android audio/Practice.kt): a count-in, then the
// lesson's beats with a click on each (accent on the bar), and optionally
// plucked notes for riffs. Everything is scheduled on the audio clock.

export interface PracticeOptions {
  bpm: number;
  totalBeats: number;
  bar?: number;
  countIn?: number;
  /** [beat, MIDI notes, beats long] to play as plucked strings. */
  notes?: [number, number[], number][];
  clicks?: boolean;
  onEnd?: () => void;
}

let shared: AudioContext | null = null;
const context = () => (shared ??= new AudioContext());

export class PracticeClock {
  private ctx = context();
  private start0 = 0;
  private spb: number;
  private nodes: AudioScheduledSourceNode[] = [];
  private timer = 0;
  stopped = false;

  constructor(private o: PracticeOptions) {
    this.spb = 60 / o.bpm;
  }

  async start() {
    await this.ctx.resume();
    const bar = this.o.bar ?? 4;
    const countIn = this.o.countIn ?? bar;
    const t0 = this.ctx.currentTime + 0.1;
    this.start0 = t0 + countIn * this.spb;
    const beats = countIn + Math.ceil(this.o.totalBeats);
    for (let b = 0; b < beats; b++) if (this.o.clicks !== false || b < countIn) this.click(t0 + b * this.spb, b % bar === 0);
    for (const [at, midi, len] of this.o.notes ?? []) for (const m of midi) this.pluck(this.start0 + at * this.spb, m, Math.min(1.6, len * this.spb + 0.25));
    const end = (this.start0 + this.o.totalBeats * this.spb - this.ctx.currentTime) * 1000;
    this.timer = window.setTimeout(() => {
      if (this.stopped) return;
      this.stopped = true;
      this.o.onEnd?.();
    }, end);
  }

  /** The beat being heard now (negative during the count-in). */
  position() {
    return (this.ctx.currentTime - this.start0) / this.spb;
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    for (const n of this.nodes) {
      try {
        n.stop();
      } catch {
        /* not started */
      }
    }
    this.nodes = [];
  }

  private click(t: number, accent: boolean) {
    const o = this.ctx.createOscillator();
    const g = this.ctx.createGain();
    o.frequency.value = accent ? 1760 : 1100;
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(accent ? 0.5 : 0.35, t + 0.002);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.04);
    o.connect(g).connect(this.ctx.destination);
    o.start(t);
    o.stop(t + 0.05);
    this.nodes.push(o);
  }

  /** Karplus-Strong plucked string, rendered into a buffer. */
  private pluck(t: number, midi: number, seconds: number) {
    const sr = this.ctx.sampleRate;
    const hz = 440 * 2 ** ((midi - 69) / 12);
    const period = Math.max(2, Math.floor(sr / hz));
    const buf = this.ctx.createBuffer(1, Math.floor(sr * seconds), sr);
    const out = buf.getChannelData(0);
    const ring = new Float32Array(period);
    let seed = midi * 9301 + 49297;
    for (let i = 0; i < period; i++) {
      seed = (seed * 9301 + 49297) % 233280;
      ring[i] = (seed / 233280) * 2 - 1;
    }
    let p = 0;
    const fadeFrom = out.length - sr * 0.03;
    for (let i = 0; i < out.length; i++) {
      const a = ring[p];
      ring[p] = 0.996 * 0.5 * (a + ring[(p + 1) % period]);
      out[i] = a * 0.45 * (i > fadeFrom ? (out.length - i) / (sr * 0.03) : 1);
      p = (p + 1) % period;
    }
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(this.ctx.destination);
    src.start(t);
    this.nodes.push(src);
  }
}
