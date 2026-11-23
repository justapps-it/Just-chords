// Chord diagrams generated from theory (not a fixed table): any chord, any
// tuning, guitar or ukulele. Finds playable shapes and ranks them.
import { chordIntervals, type Chord } from './music';

export type Instrument = 'guitar' | 'ukulele' | 'piano';

export interface Tuning {
  name: string;
  /** MIDI notes from the lowest string. */
  strings: number[];
}

export const TUNINGS: Record<string, Tuning> = {
  standard: { name: 'E A D G B E', strings: [40, 45, 50, 55, 59, 64] },
  dropD: { name: 'Drop D', strings: [38, 45, 50, 55, 59, 64] },
  halfDown: { name: 'Eb Ab Db Gb Bb Eb', strings: [39, 44, 49, 54, 58, 63] },
  openG: { name: 'Open G', strings: [38, 43, 50, 55, 59, 62] },
  openD: { name: 'Open D', strings: [38, 45, 50, 54, 57, 62] },
  dadgad: { name: 'DADGAD', strings: [38, 45, 50, 55, 57, 62] },
  ukulele: { name: 'G C E A', strings: [67, 60, 64, 69] },
};

/** -1 = muted string. */
export interface Voicing {
  frets: number[];
  /** First fret shown in the diagram. */
  base: number;
  barre?: { fret: number; from: number; to: number };
  score: number;
}

const cache = new Map<string, Voicing[]>();

export function voicings(c: Chord, tuning: Tuning = TUNINGS.standard, limit = 4): Voicing[] {
  const key = `${c.root}|${c.suffix}|${c.bass}|${tuning.strings.join(',')}`;
  const hit = cache.get(key);
  if (hit) return hit.slice(0, limit);

  const intervals = chordIntervals(c);
  const pcs = new Set(intervals.map((i) => (c.root + i) % 12));
  const bass = c.bass ?? c.root;
  if (c.bass !== undefined) pcs.add(c.bass);
  // Notes that must be there: root, third (if any) and the 7th/6th/extensions.
  // The fifth can be dropped.
  const required = new Set(intervals.filter((i) => i !== 7).map((i) => (c.root + i) % 12));
  required.add(bass);
  const n = tuning.strings.length;
  const isUke = n === 4;
  const out: Voicing[] = [];
  const seen = new Set<string>();

  for (let base = 0; base <= 12; base++) {
    const options: number[][] = tuning.strings.map((open) => {
      const o: number[] = isUke ? [] : [-1];
      if (pcs.has(open % 12)) o.push(0);
      for (let f = Math.max(1, base); f <= base + 3; f++) if (pcs.has((open + f) % 12)) o.push(f);
      return o;
    });
    const frets = new Array<number>(n);
    const walk = (s: number) => {
      if (s === n) return consider(frets.slice());
      for (const f of options[s]) {
        frets[s] = f;
        walk(s + 1);
      }
    };
    const consider = (fr: number[]) => {
      const played = fr.map((f, i) => (f < 0 ? -1 : tuning.strings[i] + f));
      const first = played.findIndex((p) => p >= 0);
      if (first < 0) return;
      // No muted strings in the middle (guitar).
      for (let i = first; i < n; i++) if (played[i] < 0) return;
      const count = played.filter((p) => p >= 0).length;
      if (count < (isUke ? 4 : Math.min(4, pcs.size + 1))) return;
      // Lowest sounding note is the bass (ukulele is re-entrant: skip).
      if (!isUke) {
        const lowest = Math.min(...played.filter((p) => p >= 0));
        if (lowest % 12 !== bass) return;
      }
      const have = new Set(played.filter((p) => p >= 0).map((p) => p % 12));
      for (const r of required) if (!have.has(r)) return;
      const fretted = fr.filter((f) => f > 0);
      const minF = fretted.length ? Math.min(...fretted) : 0;
      const maxF = fretted.length ? Math.max(...fretted) : 0;
      if (maxF - minF > 3) return;
      // Fingers: a barre on the lowest fret counts as one finger.
      const atMin = fretted.filter((f) => f === minF).length;
      const barre = minF > 0 && atMin >= 2 && fretted.length > 4 - (isUke ? 1 : 0);
      const fingers = barre ? fretted.length - atMin + 1 : fretted.length;
      if (fingers > 4) return;
      // A barre must cover every string from where it starts.
      let barreInfo: Voicing['barre'];
      if (barre) {
        const from = fr.findIndex((f) => f === minF);
        for (let i = from; i < n; i++) if (fr[i] >= 0 && fr[i] < minF) return;
        barreInfo = { fret: minF, from, to: n - 1 };
      }
      const k = fr.join(',');
      if (seen.has(k)) return;
      seen.add(k);
      const opens = fr.filter((f) => f === 0).length;
      const score = fingers * 1.0 + (maxF - minF) * 0.6 + minF * 0.35 + (barre ? 1.2 : 0) - opens * 0.3 - count * 0.4 + (fr[0] < 0 && !isUke ? 0.1 : 0);
      out.push({ frets: fr, base: maxF <= 4 ? 1 : minF, barre: barreInfo, score });
    };
    walk(0);
  }
  out.sort((a, b) => a.score - b.score);
  cache.set(key, out);
  return out.slice(0, limit);
}

/** Piano: chord tones as MIDI notes around middle C (bass an octave lower). */
export function pianoNotes(c: Chord): { notes: number[]; bass: number } {
  const root = 60 + c.root;
  const notes = chordIntervals(c).map((i) => root + (i > 12 ? i - 12 : i));
  return { notes, bass: 48 + (c.bass ?? c.root) };
}
