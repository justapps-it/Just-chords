// How hard a song is to play on guitar, from its chords: shapes (with the best
// capo), barre chords, how many different chords and how often they change.
import { formatChord, shapeDifficulty, suggestCapo, transposeChord, type Chord } from './music';
import { parseChordPro, songChords, type ParsedSong } from './chordpro';

export type Level = 1 | 2 | 3;

export interface Difficulty {
  /** 0 (trivial) – 100 (very hard). */
  score: number;
  level: Level;
  capo: number;
  /** Chords hard enough to deserve practice, with the capo applied. */
  hard: string[];
}

export function chordsDifficulty(chords: Chord[], changesPerLine = 2): Difficulty {
  const unique = [...new Map(chords.map((c) => [formatChord(c), c])).values()];
  if (!unique.length) return { score: 0, level: 1, capo: 0, hard: [] };
  const capo = suggestCapo(unique)[0]?.capo ?? 0;
  const shapes = unique.map((c) => transposeChord(c, -capo));
  const d = shapes.map(shapeDifficulty);
  const hard = shapes.filter((_, i) => d[i] >= 2).map((c) => formatChord(c));
  const worst = Math.max(...d);
  const mean = d.reduce((a, b) => a + b, 0) / d.length;
  const score = Math.round(
    Math.min(100, worst * 9 + mean * 12 + Math.max(0, unique.length - 4) * 3.5 + hard.length * 4 + Math.max(0, changesPerLine - 2) * 6 + (capo ? 2 : 0)),
  );
  return { score, level: score < 25 ? 1 : score < 55 ? 2 : 3, capo, hard };
}

/** Typical number of chords per line (median, so one riff line doesn't count much). */
export function changesPerLine(song: ParsedSong): number {
  const counts: number[] = [];
  for (const s of song.sections)
    for (const l of s.lines) {
      const n = l.type === 'lyric' ? l.parts.filter((p) => p.chord).length : l.type === 'chords' ? l.chords.length : 0;
      if (n) counts.push(n);
    }
  if (!counts.length) return 2;
  counts.sort((a, b) => a - b);
  return counts[Math.floor(counts.length / 2)];
}

export function songDifficulty(chordpro: string): Difficulty {
  const song = parseChordPro(chordpro);
  return chordsDifficulty(songChords(song), changesPerLine(song));
}

/** The level a player is at, from the songs they open and keep. */
export function estimateLevel(played: { level: Level; weight: number }[]): Level {
  const total = played.reduce((s, p) => s + p.weight, 0);
  if (total < 3) return 1;
  // The level of the hardest songs played often enough (top 30% by weight).
  const sorted = [...played].sort((a, b) => b.level - a.level);
  let acc = 0;
  for (const p of sorted) {
    acc += p.weight;
    if (acc >= total * 0.3) return p.level;
  }
  return 1;
}
