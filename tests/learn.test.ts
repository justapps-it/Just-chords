import { describe, expect, it } from 'vitest';
import { COURSES, at, coursesFor, levelFromTest, lessonById, nextLesson, parseAsciiTab, parseNotes, parsePattern, songPlan, tabLines, tempoSteps, timeline } from '../src/lib/learn';
import { parseChord } from '../src/lib/music';
import { voicings } from '../src/lib/voicings';

const labels = { chords: { it: 'Accordo', en: 'Chord' }, switch: { it: 'Cambio', en: 'Switch' }, rhythm: { it: 'Ritmo', en: 'Rhythm' }, riff: { it: 'Riff', en: 'Riff' }, play: { it: 'Suona', en: 'Play' } };

describe('courses', () => {
  it('are well formed', () => {
    const ids = new Set<string>();
    for (const c of COURSES) {
      expect([1, 2, 3]).toContain(c.level);
      expect(c.title.it && c.title.en && c.desc.it && c.desc.en).toBeTruthy();
      for (const l of c.lessons) {
        expect(ids.has(l.id)).toBe(false);
        ids.add(l.id);
        expect(l.title.it && l.title.en).toBeTruthy();
        for (const ch of [l.chord, ...(l.chords ?? [])].filter(Boolean) as string[]) {
          const p = parseChord(ch);
          expect(p, ch).not.toBeNull();
          expect(voicings(p!).length, ch).toBeGreaterThan(0);
        }
        if (l.type === 'riff') {
          const n = parseNotes(l.notes!);
          expect(n.length).toBeGreaterThan(5);
          expect(n.every((x) => x.frets.length > 0)).toBe(true);
        }
        if (l.type !== 'chord') {
          expect(l.tempo![0]).toBeLessThan(l.tempo![1]);
          expect(timeline(l).total).toBeGreaterThan(0);
        }
      }
    }
    expect(COURSES.filter((c) => c.level === 1).length).toBeGreaterThanOrEqual(3);
    expect(COURSES.filter((c) => c.level === 3).length).toBeGreaterThanOrEqual(3);
  });

  it('melodies fit their bars', () => {
    const frere = lessonById('m-frere')!.lesson;
    expect(timeline(frere).total).toBe(32);
    const grace = lessonById('m-grace')!.lesson;
    expect(grace.bar).toBe(3);
    expect(timeline(grace).total % 3).toBe(0);
  });

  it('suggests the next lesson for my level', () => {
    expect(coursesFor(2).mine.every((c) => c.level === 2)).toBe(true);
    expect(nextLesson(1, new Set())!.lesson.id).toBe('c-em');
    expect(nextLesson(1, new Set(['c-em']))!.lesson.id).toBe('c-am');
    expect(nextLesson(3, new Set())!.course.level).toBe(3);
  });

  it('tempo steps go slow → target', () => {
    expect(tempoSteps([50, 80])).toEqual([50, 65, 80]);
    expect(tempoSteps([60, 60])).toEqual([60]);
  });
});

describe('timeline', () => {
  it('switch alternates chords with strokes', () => {
    const t = timeline({ id: 'x', type: 'switch', chords: ['C', 'G'], beats: 4, title: { it: '', en: '' } });
    expect(t.total).toBe(32);
    expect(at(t.steps, 0).chord!.chord).toBe('C');
    expect(at(t.steps, 3.5).next!.chord).toBe('G');
    expect(at(t.steps, 4).chord!.chord).toBe('G');
  });
  it('strum pattern lands on the slots', () => {
    const t = timeline({ id: 'x', type: 'strum', chords: ['G'], pattern: 'D-DU-UDU', title: { it: '', en: '' } });
    const strokes = t.steps.filter((s) => s.stroke).slice(0, 6).map((s) => `${s.at}${s.stroke}`);
    expect(strokes).toEqual(['0D', '1D', '1.5U', '2.5U', '3D', '3.5U']);
    expect(parsePattern('dx-u')).toEqual(['D', 'X', '-', 'U']);
  });
});

describe('tabs', () => {
  it('parses lesson notes and draws a tab', () => {
    const n = parseNotes('5.0+4.2:0.5 1.3 r:2');
    expect(n).toEqual([
      { frets: [{ s: 5, f: 0 }, { s: 4, f: 2 }], beats: 0.5 },
      { frets: [{ s: 1, f: 3 }], beats: 1 },
      { frets: [], beats: 2 },
    ]);
    const { lines, columns } = tabLines(n);
    expect(lines).toHaveLength(6);
    expect(lines[4][columns[0]]).toBe('0');
    expect(lines[3][columns[0]]).toBe('2');
    expect(lines[0][columns[1]]).toBe('3');
  });
  it('reads an ASCII tab from a song', () => {
    const n = parseAsciiTab(['e|-------0-----|', 'B|-----1---1---|', 'G|---0-------0-|', 'D|-2-----------|', 'A|-------------|', 'E|-------------|']);
    expect(n.map((x) => x.frets)).toEqual([[{ s: 4, f: 2 }], [{ s: 3, f: 0 }], [{ s: 2, f: 1 }], [{ s: 1, f: 0 }], [{ s: 2, f: 1 }], [{ s: 3, f: 0 }]]);
    expect(parseAsciiTab(['just words here', 'and more'])).toEqual([]);
  });
});

describe('level test', () => {
  it('scores answers', () => {
    expect(levelFromTest({ known: [] })).toBe(1);
    expect(levelFromTest({ years: 1, known: ['Em', 'Am', 'C', 'G', 'D'] })).toBe(1);
    expect(levelFromTest({ known: ['Em', 'Am', 'C', 'G', 'D'], played: ['F'] })).toBe(2);
    expect(levelFromTest({ years: 3, known: ['Em', 'Am', 'C', 'G', 'D', 'E', 'A', 'Dm', 'F', 'Bm', 'B7'] })).toBe(3);
  });
});

describe('song plan', () => {
  const song = `{title: Test}\n{tempo: 120}\n[C]Hello [G]world [Am]again [F]now\n[C]Hello [G]world [F]now\n{sot}\ne|-------0-----|\nB|-----1---1---|\nG|---0-------0-|\nD|-2-----------|\nA|-------------|\nE|-------------|\n{eot}\n`;
  it('simplifies for beginners and ramps up the tempo', () => {
    const p = songPlan(song, 1, labels);
    expect(p.capo).toBe(0);
    expect(p.chords.map((c) => c.shape)).toEqual(['C', 'G', 'Am', 'Fmaj7']);
    expect(p.chords.find((c) => c.shape === 'Fmaj7')!.from).toBe('F');
    expect(p.tempo).toEqual([60, 120]);
    expect(p.pattern).toBe('D-D-D-D-');
    expect(p.switches[0]).toEqual(['C', 'G']);
    expect(p.riffs).toEqual([]);
    expect(p.lessons.at(-1)!.type).toBe('progression');
  });
  it('keeps the real chords and riffs for advanced players', () => {
    const p = songPlan(song, 3, labels);
    expect(p.chords.map((c) => c.shape)).toContain('F');
    expect(p.riffs).toHaveLength(1);
    expect(p.lessons.some((l) => l.type === 'riff')).toBe(true);
    expect(p.tempo).toEqual([85, 120]);
  });
  it('uses a capo when it makes shapes easier', () => {
    const p = songPlan('[Bb]one [Eb]two [F]three [Gm]four', 2, labels);
    expect(p.capo).toBeGreaterThan(0);
  });
});
