// "Impara": courses for each level and a practice plan for any song.
// Course content lives in courses.json (the Android app reads the same file).
// A lesson plays at a few tempos, slow first, up to the target.
import data from './courses.json';
import { parseChordPro, songChords } from './chordpro';
import { formatChord, parseChord, shapeDifficulty, simplifyChord, transposeChord, type Chord } from './music';
import type { Level } from './difficulty';

export type L10n = { it: string; en: string };
export type LessonType = 'chord' | 'switch' | 'strum' | 'riff' | 'progression';

export interface Lesson {
  id: string;
  type: LessonType;
  title: L10n;
  tip?: L10n;
  /** chord */
  chord?: string;
  /** switch, strum, progression */
  chords?: string[];
  /** Beats per chord (switch, progression). */
  beats?: number;
  /** strum: 8 or 16 slots per bar, D = down, U = up, X = muted, - = miss. */
  pattern?: string;
  /** progression: strumming pattern. */
  strum?: string;
  /** riff: "string.fret:beats", "+" for notes together, "r:beats" for a rest. String 1 = high E. */
  notes?: string;
  /** Beats per bar for riffs (3 or 4). */
  bar?: number;
  /** BPM from → to. */
  tempo?: [number, number];
}

export interface Course {
  id: string;
  level: Level;
  title: L10n;
  desc: L10n;
  lessons: Lesson[];
}

type RawLesson = Omit<Lesson, 'tempo' | 'bar'> & { tempo?: number[]; beats?: number };
const raw = data as unknown as { courses: (Omit<Course, 'lessons'> & { lessons: RawLesson[] })[] };

export const COURSES: Course[] = raw.courses.map((c) => ({
  ...c,
  lessons: c.lessons.map((l) => ({
    ...l,
    // In the JSON, a riff's "beats" is the time signature; elsewhere beats per chord.
    bar: l.type === 'riff' ? (l.beats ?? 4) : 4,
    beats: l.type === 'riff' ? undefined : l.beats,
    tempo: l.tempo ? [l.tempo[0], l.tempo[1]] : undefined,
  })),
}));

export const lessonById = (id: string): { course: Course; lesson: Lesson; index: number } | null => {
  for (const course of COURSES) {
    const index = course.lessons.findIndex((l) => l.id === id);
    if (index >= 0) return { course, lesson: course.lessons[index], index };
  }
  return null;
};

/** Courses for a level first, then the others (easiest first). */
export function coursesFor(level: Level): { mine: Course[]; others: Course[] } {
  return { mine: COURSES.filter((c) => c.level === level), others: COURSES.filter((c) => c.level !== level).sort((a, b) => a.level - b.level) };
}

/** The next lesson to do: the first not done in my courses, else in any. */
export function nextLesson(level: Level, done: Set<string>): { course: Course; lesson: Lesson } | null {
  const { mine, others } = coursesFor(level);
  for (const course of [...mine, ...others]) {
    const lesson = course.lessons.find((l) => !done.has(l.id));
    if (lesson) return { course, lesson };
  }
  return null;
}

export const courseProgress = (c: Course, done: Set<string>) => c.lessons.filter((l) => done.has(l.id)).length / c.lessons.length;

/** Slow, middle, target. */
export function tempoSteps(t: [number, number] | undefined): number[] {
  if (!t) return [];
  const [a, b] = t;
  const mid = Math.round((a + b) / 2 / 5) * 5;
  return [...new Set([a, mid, b])].filter((x) => x > 0);
}

// ------------------------------------------------------------- riffs

export interface Note {
  /** Empty for a rest. */
  frets: { s: number; f: number }[];
  beats: number;
}

export function parseNotes(src: string): Note[] {
  return src
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map((tok) => {
      const [what, dur] = tok.split(':');
      const beats = dur ? Number(dur) : 1;
      if (what === 'r') return { frets: [], beats };
      const frets = what.split('+').map((p) => {
        const [s, f] = p.split('.').map(Number);
        return { s, f };
      });
      return { frets: frets.filter((x) => x.s >= 1 && x.s <= 6 && x.f >= 0 && x.f <= 24), beats: Number.isFinite(beats) && beats > 0 ? beats : 1 };
    });
}

/** MIDI note of a string/fret in standard tuning (string 1 = high E). */
export const STANDARD = [64, 59, 55, 50, 45, 40];
export const midiOf = (s: number, f: number) => STANDARD[s - 1] + f;

/** Six tab lines (high E first) with one column per note, for display. */
export function tabLines(notes: Note[]): { lines: string[]; columns: number[] } {
  const names = ['e', 'B', 'G', 'D', 'A', 'E'];
  const lines = names.map((n) => `${n}|-`);
  const columns: number[] = [];
  for (const n of notes) {
    const width = Math.max(1, ...n.frets.map((x) => String(x.f).length));
    columns.push(lines[0].length);
    for (let s = 1; s <= 6; s++) {
      const hit = n.frets.find((x) => x.s === s);
      const cell = hit ? String(hit.f).padEnd(width, '-') : '-'.repeat(width);
      // Longer notes get more room.
      lines[s - 1] += cell + '-'.repeat(Math.max(1, Math.round(n.beats * 2)));
    }
  }
  return { lines: lines.map((l) => l + '|'), columns };
}

/** ASCII tab from a song (6 lines like "e|--0--3--|") → notes, half a beat per column with a note. */
export function parseAsciiTab(lines: string[]): Note[] {
  const rows = lines.map((l) => l.trim()).filter((l) => /^[eEBGDAb]?\s*[|:]?[-0-9hpbr/\\~x|() ]{6,}\|?$/.test(l) && /-/.test(l));
  if (rows.length < 4) return [];
  const six = rows.slice(0, 6);
  // Strip the string name and leading bar.
  const body = six.map((r) => r.replace(/^[a-zA-Z]?\s*[|:]?/, ''));
  const width = Math.max(...body.map((b) => b.length));
  const notes: Note[] = [];
  let i = 0;
  let gap = 0;
  while (i < width) {
    const frets: { s: number; f: number }[] = [];
    let used = 1;
    body.forEach((b, s) => {
      const m = b.slice(i).match(/^\d{1,2}/);
      if (m) {
        frets.push({ s: s + 1, f: Number(m[0]) });
        used = Math.max(used, m[0].length);
      }
    });
    if (frets.length) {
      if (notes.length && gap > 4) notes[notes.length - 1].beats = 1;
      notes.push({ frets, beats: 0.5 });
      gap = 0;
    } else gap++;
    i += used;
  }
  return notes.length >= 3 ? notes : [];
}

// --------------------------------------------------------- strumming

export type Stroke = 'D' | 'U' | 'X' | '-';
export const parsePattern = (p: string): Stroke[] => [...p.toUpperCase()].filter((c) => 'DUX-'.includes(c)) as Stroke[];

export const PATTERNS = {
  basic: 'D-D-D-D-',
  ballad: 'D-D-D-DU',
  pop: 'D-DU-UDU',
  drive: 'DDDDDDDD',
} as const;

// ---------------------------------------------------------- timeline

/** One thing to play, at a beat (from the start, after the count-in). */
export interface Step {
  at: number;
  beats: number;
  chord?: string;
  stroke?: Stroke;
  note?: number;
}

/** What a lesson plays at one tempo: a few bars, the same at every tempo. */
export function timeline(l: Lesson): { steps: Step[]; total: number; bar: number } {
  const steps: Step[] = [];
  let at = 0;
  if (l.type === 'riff' && l.notes) {
    parseNotes(l.notes).forEach((n, i) => {
      steps.push({ at, beats: n.beats, note: i });
      at += n.beats;
    });
    const bar = l.bar ?? 4;
    return { steps, total: Math.ceil(at / bar) * bar, bar };
  }
  const chords = l.chords ?? (l.chord ? [l.chord] : []);
  if (!chords.length) return { steps, total: 0, bar: 4 };
  const pattern = parsePattern(l.type === 'strum' ? (l.pattern ?? PATTERNS.basic) : (l.strum ?? ''));
  // Switches: 8 changes. Strumming: two bars per chord. Progressions: twice round.
  const per = l.type === 'strum' ? 8 : (l.beats ?? 4);
  const seq = l.type === 'switch' ? Array.from({ length: 8 }, (_, i) => chords[i % chords.length]) : l.type === 'strum' ? chords : [...chords, ...chords];
  for (const chord of seq) {
    steps.push({ at, beats: per, chord });
    if (pattern.length) {
      const slot = 4 / pattern.length;
      for (let b = 0; b < per; b += slot) {
        const stroke = pattern[Math.round(b / slot) % pattern.length];
        if (stroke !== '-') steps.push({ at: at + b, beats: slot, stroke });
      }
    }
    at += per;
  }
  return { steps, total: at, bar: 4 };
}

/** The chord and stroke sounding at a beat. */
export function at(steps: Step[], beat: number): { chord?: Step; next?: Step; stroke?: Step; note?: Step } {
  let chord: Step | undefined, next: Step | undefined, stroke: Step | undefined, note: Step | undefined;
  for (const s of steps) {
    if (s.chord !== undefined) {
      if (s.at <= beat + 1e-6) chord = s;
      else if (!next) next = s;
    }
    if (s.stroke && s.at <= beat + 1e-6 && beat < s.at + s.beats - 1e-6) stroke = s;
    if (s.note !== undefined && s.at <= beat + 1e-6) note = s;
  }
  return { chord, next, stroke, note };
}

// --------------------------------------------------------- level test

export const TEST_CHORDS = ['Em', 'Am', 'C', 'G', 'D', 'E', 'A', 'Dm', 'F', 'Bm', 'B7', 'Cmaj7', 'F#m', 'Bb'];
/** Chords the practical test asks for, easy to hard. */
export const PLAY_TEST = ['Em', 'C', 'G', 'D', 'F', 'Bm'];

export interface TestAnswers {
  /** 0 = never played, 1 = under a year, 2 = 1–3 years, 3 = more. */
  years?: 0 | 1 | 2 | 3;
  known: string[];
  /** Heard on the microphone in the practical test. */
  played?: string[];
}

export function levelFromTest(a: TestAnswers): Level {
  const known = new Set([...a.known, ...(a.played ?? [])]);
  const barre = ['F', 'Bm', 'F#m', 'Bb'].filter((c) => known.has(c)).length;
  const years = a.years ?? 0;
  if ((barre >= 3 && known.size >= 10) || (years >= 3 && barre >= 2)) return 3;
  if (barre >= 1 || known.size >= 7 || (years >= 2 && known.size >= 4)) return 2;
  return 1;
}

// --------------------------------------------------------- song plan

/** Easier shapes for beginners: no barre. */
const EASIER: Record<string, string> = { F: 'Fmaj7', Bm: 'Bm7', B: 'B7', Fm: 'Fm7', 'F#m': 'F#m7', Cm: 'Cm7', 'C#m': 'C#m7', Gm: 'Gm7', Bb: 'Bbmaj7', 'F#': 'F#7', Ab: 'Ab7', Eb: 'Ebmaj7', 'G#m': 'G#m7' };

export function simplerShape(c: Chord, level: Level): Chord {
  if (level >= 3) return c;
  const plain = level === 1 ? simplifyChord(c) : { ...c, bass: undefined };
  if (level === 1) {
    const alt = EASIER[formatChord(plain)];
    if (alt) return parseChord(alt) ?? plain;
  }
  return plain;
}

/** The capo that makes the shapes easiest once simplified for the level (a capo costs a little). */
export function bestCapo(chords: Chord[], level: Level): number {
  const unique = [...new Map(chords.map((c) => [formatChord(c), c])).values()];
  let best = { capo: 0, score: Infinity };
  for (let capo = 0; capo <= 7; capo++) {
    const score = unique.reduce((s, c) => s + shapeDifficulty(simplerShape(transposeChord(c, -capo), level)), 0) + capo * 0.3;
    if (score < best.score - 1e-9) best = { capo, score };
  }
  return best.capo;
}

export interface SongPlan {
  /** Capo to put on (shapes are relative to it). */
  capo: number;
  /** Shapes to learn, in order of appearance, with the original name when simplified. */
  chords: { shape: string; from?: string; hard: boolean }[];
  /** Hardest switches first. */
  switches: [string, string][];
  pattern: string;
  bpm: number;
  tempo: [number, number];
  /** The song's chords in order (shapes), for the progression drill. */
  progression: string[];
  /** Riffs and melodies from the song's tabs. */
  riffs: Note[][];
  lessons: Lesson[];
}

export function songPlan(chordpro: string, level: Level, labels: { chords: L10n; switch: L10n; rhythm: L10n; riff: L10n; play: L10n }): SongPlan {
  const song = parseChordPro(chordpro);
  const all = songChords(song);
  const sequence: Chord[] = [];
  const tabBlocks: string[][] = [];
  for (const s of song.sections) {
    let tab: string[] = [];
    for (const l of s.lines) {
      if (l.type === 'tab') tab.push(l.text);
      else {
        if (tab.length) tabBlocks.push(tab);
        tab = [];
        if (l.type === 'lyric') l.parts.forEach((p) => p.chord && parseChord(p.chord) && sequence.push(parseChord(p.chord)!));
        if (l.type === 'chords') l.chords.forEach((c) => parseChord(c) && sequence.push(parseChord(c)!));
      }
    }
    if (tab.length) tabBlocks.push(tab);
  }
  // Beginners and intermediates: the capo that gives the easiest shapes (on top of the song's own capo).
  const capo = level < 3 && all.length ? bestCapo(all, level) : 0;
  const shape = (c: Chord) => formatChord(simplerShape(transposeChord(c, -capo), level));
  const seen = new Map<string, { shape: string; from?: string; hard: boolean }>();
  for (const c of sequence.length ? sequence : all) {
    const sh = shape(c);
    if (seen.has(sh)) continue;
    const original = formatChord(transposeChord(c, -capo));
    const pc = parseChord(sh)!;
    seen.set(sh, { shape: sh, from: original !== sh ? original : undefined, hard: shapeDifficulty(pc) >= (level === 1 ? 1 : 2) });
  }
  const chords = [...seen.values()];
  const shapes = (sequence.length ? sequence : all).map(shape);
  const pairs = new Map<string, { a: string; b: string; n: number; d: number }>();
  for (let i = 1; i < shapes.length; i++) {
    const [a, b] = [shapes[i - 1], shapes[i]];
    if (a === b) continue;
    const k = [a, b].sort().join('|');
    const d = shapeDifficulty(parseChord(a)!) + shapeDifficulty(parseChord(b)!);
    const p = pairs.get(k) ?? { a, b, n: 0, d };
    p.n++;
    pairs.set(k, p);
  }
  const switches = [...pairs.values()]
    .sort((x, y) => y.d * 2 + y.n - (x.d * 2 + x.n))
    .slice(0, level === 1 ? 3 : 4)
    .map((p) => [p.a, p.b] as [string, string]);
  const bpm = song.tempo && song.tempo >= 40 && song.tempo <= 240 ? Math.round(song.tempo) : 90;
  const pattern = level === 1 ? PATTERNS.basic : level === 2 ? PATTERNS.pop : bpm < 80 ? PATTERNS.ballad : bpm > 140 ? PATTERNS.drive : PATTERNS.pop;
  const slow = Math.max(40, Math.round((bpm * (level === 1 ? 0.5 : level === 2 ? 0.6 : 0.7)) / 5) * 5);
  const tempo: [number, number] = [Math.min(slow, bpm), bpm];
  // The song's progression, without immediate repeats, first 8 changes.
  const progression: string[] = [];
  for (const s of shapes) if (progression[progression.length - 1] !== s) progression.push(s);
  const riffs = level === 1 ? [] : tabBlocks.map(parseAsciiTab).filter((n) => n.length).slice(0, 3);

  const lessons: Lesson[] = [];
  chords.forEach((c) => lessons.push({ id: `chord:${c.shape}`, type: 'chord', chord: c.shape, title: { it: `${labels.chords.it}: ${c.shape}`, en: `${labels.chords.en}: ${c.shape}` } }));
  switches.forEach(([a, b]) => lessons.push({ id: `switch:${a}|${b}`, type: 'switch', chords: [a, b], beats: level === 1 ? 4 : 2, tempo: [Math.max(40, tempo[0] - 10), bpm], title: { it: `${labels.switch.it}: ${a} ↔ ${b}`, en: `${labels.switch.en}: ${a} ↔ ${b}` } }));
  lessons.push({ id: 'rhythm', type: 'strum', chords: chords.slice(0, 2).map((c) => c.shape), pattern, tempo, title: labels.rhythm });
  riffs.forEach((n, i) =>
    lessons.push({
      id: `riff:${i}`,
      type: 'riff',
      notes: n.map((x) => (x.frets.length ? x.frets.map((f) => `${f.s}.${f.f}`).join('+') : 'r') + `:${x.beats}`).join(' '),
      bar: 4,
      tempo,
      title: { it: `${labels.riff.it} ${riffs.length > 1 ? i + 1 : ''}`.trim(), en: `${labels.riff.en} ${riffs.length > 1 ? i + 1 : ''}`.trim() },
    }),
  );
  if (progression.length > 1) lessons.push({ id: 'play', type: 'progression', chords: progression.slice(0, 8), beats: 4, strum: pattern, tempo, title: labels.play });
  return { capo, chords, switches, pattern, bpm, tempo, progression, riffs, lessons };
}

/** Playback speeds for playing along with the original: slow → original. */
export const ALONG_SPEEDS = (level: Level) => (level === 1 ? [0.5, 0.75, 1] : level === 2 ? [0.75, 0.9, 1] : [0.85, 1]);
