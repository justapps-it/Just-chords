// Music theory: chord symbols, transposition, notation (C D E / Do Re Mi),
// key detection, simplification and capo suggestions.

export type Notation = 'en' | 'it';

const SHARP = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
const FLAT = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B'];
const IT_SHARP = ['Do', 'Do#', 'Re', 'Re#', 'Mi', 'Fa', 'Fa#', 'Sol', 'Sol#', 'La', 'La#', 'Si'];
const IT_FLAT = ['Do', 'Reb', 'Re', 'Mib', 'Mi', 'Fa', 'Solb', 'Sol', 'Lab', 'La', 'Sib', 'Si'];
const LETTER: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11, H: 11 };
const SOLFEGE: Record<string, number> = { do: 0, re: 2, mi: 4, fa: 5, sol: 7, la: 9, si: 11 };

export interface Chord {
  root: number;
  /** Normalised quality, e.g. "", "m", "7", "maj7", "m7b5", "sus4", "add9". */
  suffix: string;
  bass?: number;
}

const mod12 = (n: number) => ((n % 12) + 12) % 12;

// Root: English letter (C, Bb, F#) or Italian solfège (Do, SOL, Sib, Fa#).
const ROOT_RE = /^(?:(Do|DO|Re|RE|Mi|MI|Fa|FA|Sol|SOL|La|LA|Si|SI)|([A-H]))([#♯b♭]?)/;
// Everything a chord suffix may contain.
const SUFFIX_RE =
  /^(?:maj|Maj|MAJ|min|mi|m|M|Δ|-|dim|°|o|aug|\+|ø|sus|add|no|alt|[#♯b♭]|\d{1,2}|\(|\)|,|\/(?=\d)|\*)*$/;

function parseRoot(s: string): { pc: number; rest: string } | null {
  const m = ROOT_RE.exec(s);
  if (!m) return null;
  let pc = m[1] ? SOLFEGE[m[1].toLowerCase()] : LETTER[m[2]];
  if (m[3] === '#' || m[3] === '♯') pc++;
  else if (m[3] === 'b' || m[3] === '♭') pc--;
  return { pc: mod12(pc), rest: s.slice(m[0].length) };
}

/** Normalises the quality text: Italian "Lam"/"LA-" → "m", "Δ" → "maj7", etc. */
function normaliseSuffix(raw: string): string {
  let s = raw.replace(/[♯]/g, '#').replace(/[♭]/g, 'b').replace(/[()]/g, '').replace(/\*$/, '');
  s = s.replace(/^(min|mi|-)(?!\d*b5)/, 'm').replace(/^-(?=\d)/, 'm');
  s = s.replace(/^Δ7?/, 'maj7').replace(/^(Maj|MAJ)/, 'maj').replace(/^M(?=\d|$)/, 'maj');
  if (s === 'maj') s = '';
  s = s.replace(/^(°|o)7/, 'dim7').replace(/^(°|o)$/, 'dim').replace(/^ø7?/, 'm7b5').replace(/^\+/, 'aug');
  s = s.replace(/^mmaj/, 'mmaj');
  return s;
}

export function parseChord(symbol: string): Chord | null {
  const sym = symbol.trim();
  if (!sym || sym.length > 16) return null;
  const r = parseRoot(sym);
  if (!r) return null;
  let rest = r.rest;
  let bass: number | undefined;
  const slash = rest.match(/\/([^/\d].*)$/);
  if (slash) {
    const b = parseRoot(slash[1]);
    if (!b || b.rest) return null;
    bass = b.pc;
    rest = rest.slice(0, slash.index);
  }
  // Solfège roots are often written all-caps or followed by "m"/"-" directly.
  if (!SUFFIX_RE.test(rest)) return null;
  return { root: r.pc, suffix: normaliseSuffix(rest), bass };
}

export const isChord = (s: string) => parseChord(s) !== null;

export function noteName(pc: number, notation: Notation = 'en', flats = false): string {
  const table = notation === 'it' ? (flats ? IT_FLAT : IT_SHARP) : flats ? FLAT : SHARP;
  return table[mod12(pc)];
}

export function formatChord(c: Chord, notation: Notation = 'en', flats = false): string {
  const bass = c.bass === undefined ? '' : `/${noteName(c.bass, notation, flats)}`;
  return noteName(c.root, notation, flats) + c.suffix + bass;
}

export function transposeChord(c: Chord, semitones: number): Chord {
  return { root: mod12(c.root + semitones), suffix: c.suffix, bass: c.bass === undefined ? undefined : mod12(c.bass + semitones) };
}

/** Intervals (semitones above the root) of the chord, root first. */
export function chordIntervals(c: Chord): number[] {
  const s = c.suffix;
  const set = new Set<number>([0]);
  const minor = /^m(?!aj)/.test(s);
  const dim = /^dim/.test(s) || /m7b5/.test(s);
  const aug = /^aug/.test(s) || /#5/.test(s);
  const sus2 = /sus2/.test(s);
  const sus4 = /sus(4)?(?!2)/.test(s);
  if (sus2) set.add(2);
  else if (sus4) set.add(5);
  else set.add(minor || dim ? 3 : 4);
  if (/^5$/.test(s)) return [0, 7];
  if (!/no5/.test(s)) set.add(dim || /b5/.test(s) ? 6 : aug ? 8 : 7);
  if (/no3/.test(s)) {
    set.delete(3);
    set.delete(4);
  }
  const ext = /(?:maj|^m|^dim|^aug|^)(\d{1,2})/.exec(s.replace(/^mmaj/, 'maj'))?.[1];
  const n = ext ? Number(ext) : 0;
  const major7 = /maj(7|9|11|13)/.test(s);
  if (/^dim7/.test(s)) set.add(9);
  else if (n === 6) set.add(9);
  else if (n >= 7 && n !== 69) set.add(major7 ? 11 : 10);
  if (/6\/?9|69/.test(s)) {
    set.add(9);
    set.add(2);
  }
  if (n >= 9 && n !== 69) set.add(2);
  if (n >= 11) set.add(5);
  if (n >= 13) set.add(9);
  if (/add9|add2/.test(s)) set.add(2);
  if (/add11|add4/.test(s)) set.add(5);
  if (/b9/.test(s)) (set.delete(2), set.add(1));
  if (/#9/.test(s)) set.add(3);
  if (/#11/.test(s)) set.add(6);
  if (/b13/.test(s)) set.add(8);
  return [...set].sort((a, b) => a - b);
}

/** Pitch classes of the chord, root first. */
export function chordNotes(c: Chord): number[] {
  return chordIntervals(c).map((i) => mod12(c.root + i));
}

/** Keeps only the basic triad (or minor/dim): "Cmaj7/E" → "C", "Am9" → "Am". */
export function simplifyChord(c: Chord): Chord {
  const s = c.suffix;
  let suffix = '';
  if (/^dim|m7b5/.test(s)) suffix = 'dim';
  else if (/^m(?!aj)/.test(s)) suffix = 'm';
  else if (/^aug/.test(s)) suffix = 'aug';
  else if (/^7|^9|^13|^11/.test(s)) suffix = '7';
  return { root: c.root, suffix };
}

// ---------------------------------------------------------------- keys

export interface Key {
  tonic: number;
  minor: boolean;
}

const MAJOR_SCALE = [0, 2, 4, 5, 7, 9, 11];
// Diatonic triads of a major key: I ii iii IV V vi vii°.
const DIATONIC: [number, 'M' | 'm' | 'd'][] = [
  [0, 'M'],
  [2, 'm'],
  [4, 'm'],
  [5, 'M'],
  [7, 'M'],
  [9, 'm'],
  [11, 'd'],
];

function chordKind(c: Chord): 'M' | 'm' | 'd' {
  const s = simplifyChord(c).suffix;
  return s === 'm' ? 'm' : s === 'dim' ? 'd' : 'M';
}

/** Guesses the key from the chords (in order of appearance). */
export function detectKey(chords: Chord[]): Key | null {
  if (!chords.length) return null;
  let best: { key: Key; score: number } | null = null;
  for (let t = 0; t < 12; t++) {
    let score = 0;
    chords.forEach((c, i) => {
      const deg = DIATONIC.find(([iv, k]) => mod12(t + iv) === c.root && k === chordKind(c));
      const w = i === 0 || i === chords.length - 1 ? 2 : 1;
      if (deg) score += w * (deg[0] === 0 || deg[0] === 7 || deg[0] === 5 ? 1.2 : 1);
      else if (MAJOR_SCALE.some((iv) => mod12(t + iv) === c.root)) score += 0.2 * w;
      else score -= 0.5 * w;
    });
    // Relative minor shares the chords: decide by the first/last chord.
    const first = chords[0];
    const last = chords[chords.length - 1];
    const relMinor = mod12(t + 9);
    const minorish = (c: Chord) => c.root === relMinor && chordKind(c) === 'm';
    const tonicish = (c: Chord) => c.root === t && chordKind(c) === 'M';
    const minor = (minorish(first) ? 1 : 0) + (minorish(last) ? 1 : 0) > (tonicish(first) ? 1 : 0) + (tonicish(last) ? 1 : 0);
    if (tonicish(first)) score += 1;
    if (minorish(first)) score += 1;
    const key = minor ? { tonic: relMinor, minor: true } : { tonic: t, minor: false };
    if (!best || score > best.score) best = { key, score };
  }
  return best!.key;
}

/** Whether a key is conventionally written with flats. */
export function prefersFlats(key: Key | null): boolean {
  if (!key) return false;
  const major = key.minor ? mod12(key.tonic + 3) : key.tonic;
  return [5, 10, 3, 8, 1, 6].includes(major); // F Bb Eb Ab Db Gb
}

export function formatKey(key: Key, notation: Notation = 'en'): string {
  return noteName(key.tonic, notation, prefersFlats(key)) + (key.minor ? 'm' : '');
}

export function parseKey(s: string): Key | null {
  const c = parseChord(s.replace(/\s*(major|maggiore)$/i, '').replace(/\s*(minor|minore)$/i, 'm'));
  return c ? { tonic: c.root, minor: /^m(?!aj)/.test(c.suffix) } : null;
}

// ---------------------------------------------------------------- capo

/** Open-position shapes everyone can play, and the cheap barre ones. */
const EASY = new Set(['C', 'A', 'G', 'E', 'D', 'Am', 'Em', 'Dm', 'A7', 'E7', 'D7', 'G7', 'C7', 'B7', 'Am7', 'Em7', 'Dm7', 'Cmaj7', 'Fmaj7', 'Amaj7', 'Dmaj7', 'Gmaj7', 'Asus2', 'Asus4', 'Dsus2', 'Dsus4', 'Esus4', 'Cadd9', 'A7sus4', 'E5', 'A5', 'D5', 'G5']);
const MEDIUM = new Set(['F', 'Bm', 'Fm', 'Bm7', 'F7', 'Em9', 'Gsus4', 'Csus2', 'Csus4', 'Fsus2', 'Bb', 'Bdim', 'Cm']);

export function shapeDifficulty(c: Chord): number {
  const name = formatChord({ ...c, bass: undefined });
  if (EASY.has(name)) return 0;
  if (MEDIUM.has(name)) return 2;
  const simple = formatChord(simplifyChord(c));
  if (EASY.has(simple)) return 1;
  if (MEDIUM.has(simple)) return 2.5;
  return 3.5;
}

/** Best capo position (0–7) so the shapes are as easy as possible. */
export function suggestCapo(chords: Chord[]): { capo: number; score: number; base: number }[] {
  const unique = [...new Map(chords.map((c) => [formatChord(c), c])).values()];
  const results = [];
  for (let capo = 0; capo <= 7; capo++) {
    const score = unique.reduce((s, c) => s + shapeDifficulty(transposeChord(c, -capo)), 0) + capo * 0.15;
    results.push({ capo, score, base: unique.reduce((s, c) => s + shapeDifficulty(c), 0) });
  }
  return results.sort((a, b) => a.score - b.score);
}
