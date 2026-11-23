import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { Dsp, LiveChords, noteFromHz, recognise } from '../src/audio/dsp';
import { chordNotes, parseChord } from '../src/lib/music';

let dsp: Dsp;
beforeAll(async () => {
  dsp = await Dsp.load(readFileSync(new URL('../public/dsp.wasm', import.meta.url)));
});

const SR = 44100;
const midiHz = (m: number) => 440 * 2 ** ((m - 69) / 12);

/** A strummed-ish chord: each note with harmonics, decaying, plus noise. */
function chordAudio(sym: string, seconds: number, seed = 1): Float32Array {
  const c = parseChord(sym)!;
  const notes = chordNotes(c);
  const midis = [36 + notes[0], ...notes.map((pc) => 48 + pc), ...notes.slice(0, 2).map((pc) => 60 + pc)];
  const out = new Float32Array(Math.floor(seconds * SR));
  let r = seed;
  const rand = () => ((r = (r * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
  for (const m of midis) {
    const f = midiHz(m) * (1 + rand() * 0.002);
    for (let h = 1; h <= 5; h++)
      for (let i = 0; i < out.length; i++) out[i] += (0.08 / h) * Math.sin((2 * Math.PI * f * h * i) / SR) * Math.exp(-i / SR / 1.8);
  }
  for (let i = 0; i < out.length; i++) out[i] += rand() * 0.003;
  return out;
}

describe('dsp', () => {
  it('chroma peaks on the chord notes', () => {
    const { chroma } = dsp.chroma(chordAudio('C', 0.5).subarray(0, 8192), SR);
    const top3 = [...chroma].map((v, i) => [v, i]).sort((a, b) => b[0] - a[0]).slice(0, 3).map((x) => x[1]).sort((a, b) => a - b);
    expect(top3).toEqual([0, 4, 7]);
  });

  it('recognises a progression from audio', () => {
    const prog = ['C', 'G', 'Am', 'F', 'Dm', 'E7'];
    const parts = prog.map((c, i) => chordAudio(c, 2, i + 3));
    const all = new Float32Array(parts.reduce((s, p) => s + p.length, 0));
    let o = 0;
    for (const p of parts) (all.set(p, o), (o += p.length));
    const segs = recognise(dsp, all, SR).filter((s) => s.chord);
    // The 7th is quiet in this synthetic mix: E or E7 are both fine.
    expect(segs.map((s) => (s.chord === 'E' ? 'E7' : s.chord))).toEqual(prog);
  });

  it('live recognition settles on the chord', () => {
    const live = new LiveChords();
    const a = chordAudio('Em', 1.5);
    let last = null;
    for (let p = 0; p + 4096 <= a.length; p += 2048) {
      const { chroma, rms } = dsp.chroma(a.slice(p, p + 4096), SR);
      last = live.push(chroma, rms).chord;
    }
    expect(last?.name).toBe('Em');
  });

  it('tuner finds the pitch of each guitar string', () => {
    for (const m of [40, 45, 50, 55, 59, 64]) {
      const f = midiHz(m) * 2 ** (7 / 1200); // 7 cents sharp
      const x = new Float32Array(4096);
      for (let i = 0; i < x.length; i++) for (let h = 1; h <= 4; h++) x[i] += Math.sin((2 * Math.PI * f * h * i) / SR) / h;
      const { hz } = dsp.pitch(x, SR);
      const n = noteFromHz(hz);
      expect(n.midi).toBe(m);
      expect(Math.abs(n.cents - 7)).toBeLessThan(2);
    }
  });
});
