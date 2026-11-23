// Sample-accurate metronome: beats are scheduled ahead on the audio clock.
export interface MetronomeState {
  bpm: number;
  beats: number;
  sub: number;
  accent: boolean;
}

export class Metronome {
  private ctx: AudioContext | null = null;
  private timer = 0;
  private next = 0;
  private tick = 0;
  /** Audio time + beat index of scheduled beats, for the visual indicator. */
  queue: { time: number; beat: number; sub: number }[] = [];
  running = false;

  constructor(public s: MetronomeState) {}

  async start() {
    this.ctx ??= new AudioContext();
    await this.ctx.resume();
    this.running = true;
    this.tick = 0;
    this.next = this.ctx.currentTime + 0.06;
    this.timer = window.setInterval(() => this.schedule(), 25);
    this.schedule();
  }

  stop() {
    this.running = false;
    clearInterval(this.timer);
    this.queue = [];
  }

  now() {
    return this.ctx?.currentTime ?? 0;
  }

  private schedule() {
    const ctx = this.ctx!;
    const per = 60 / this.s.bpm / this.s.sub;
    while (this.next < ctx.currentTime + 0.12) {
      const total = this.s.beats * this.s.sub;
      const i = this.tick % total;
      const beat = Math.floor(i / this.s.sub);
      const sub = i % this.s.sub;
      this.click(this.next, sub === 0 ? (beat === 0 && this.s.accent ? 2 : 1) : 0);
      this.queue.push({ time: this.next, beat, sub });
      if (this.queue.length > 32) this.queue.shift();
      this.next += per;
      this.tick++;
    }
  }

  private click(t: number, level: 0 | 1 | 2) {
    const ctx = this.ctx!;
    const o = ctx.createOscillator();
    const g = ctx.createGain();
    o.frequency.value = level === 2 ? 1760 : level === 1 ? 1100 : 760;
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(level === 0 ? 0.25 : 0.6, t + 0.002);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.05);
    o.connect(g).connect(ctx.destination);
    o.start(t);
    o.stop(t + 0.06);
  }
}

/** Tap tempo: average of the last taps. */
export function tapTempo(taps: number[]): number | null {
  const recent = taps.filter((t, i) => i === 0 || t - taps[i - 1] < 2000).slice(-6);
  if (recent.length < 2) return null;
  const gaps = recent.slice(1).map((t, i) => t - recent[i]);
  return Math.round(60000 / (gaps.reduce((a, b) => a + b, 0) / gaps.length));
}
