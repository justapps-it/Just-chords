import { describe, expect, it } from 'vitest';
import { chordNotes, detectKey, formatChord, formatKey, parseChord, prefersFlats, simplifyChord, suggestCapo, transposeChord } from '../src/lib/music';
import { voicings, TUNINGS } from '../src/lib/voicings';
import { importText, isChordLine, merge, parseChordPro, songChords, displayChord } from '../src/lib/chordpro';

const p = (s: string) => parseChord(s)!;
const fmt = (s: string, n: 'en' | 'it' = 'en', flats = false) => formatChord(p(s), n, flats);

describe('chords', () => {
  it('parses English and Italian symbols', () => {
    expect(fmt('Am7')).toBe('Am7');
    expect(fmt('Bb/D', 'en', true)).toBe('Bb/D');
    expect(fmt('LAm')).toBe('Am');
    expect(fmt('Lam')).toBe('Am');
    expect(fmt('RE-')).toBe('Dm');
    expect(fmt('SOL7')).toBe('G7');
    expect(fmt('Sib')).toBe('A#');
    expect(fmt('Fa#m')).toBe('F#m');
    expect(fmt('DO/MI')).toBe('C/E');
    expect(fmt('CΔ7')).toBe('Cmaj7');
    expect(fmt('Bø')).toBe('Bm7b5');
    expect(fmt('C', 'it')).toBe('Do');
    expect(fmt('Bbm', 'it', true)).toBe('Sibm');
  });
  it('rejects words', () => {
    for (const w of ['Hello', 'And', 'Amore', 'Be', 'Sole', 'Dove', 'Fame', 'Cosa', 'Every', 'Ad']) expect(parseChord(w), w).toBeNull();
  });
  it('knows the notes', () => {
    expect(chordNotes(p('C'))).toEqual([0, 4, 7]);
    expect(chordNotes(p('Am7'))).toEqual([9, 0, 4, 7]);
    expect(chordNotes(p('G7'))).toEqual([7, 11, 2, 5]);
    expect(chordNotes(p('Cmaj7'))).toEqual([0, 4, 7, 11]);
    expect(chordNotes(p('Dsus4'))).toEqual([2, 7, 9]);
    expect(chordNotes(p('Bdim'))).toEqual([11, 2, 5]);
    expect(chordNotes(p('E5'))).toEqual([4, 11]);
    expect(chordNotes(p('Cadd9')).sort()).toEqual([0, 2, 4, 7].sort());
    expect(chordNotes(p('C6'))).toEqual([0, 4, 7, 9]);
    expect(chordNotes(p('Bm7b5'))).toEqual([11, 2, 5, 9]);
  });
  it('transposes and simplifies', () => {
    expect(formatChord(transposeChord(p('C/E'), 2))).toBe('D/F#');
    expect(formatChord(transposeChord(p('Am'), -2), 'en', true)).toBe('Gm');
    expect(formatChord(simplifyChord(p('Cmaj7/E')))).toBe('C');
    expect(formatChord(simplifyChord(p('Am9')))).toBe('Am');
  });
  it('detects keys', () => {
    const k = detectKey(['G', 'D', 'Em', 'C', 'G', 'D', 'C', 'G'].map(p))!;
    expect(formatKey(k)).toBe('G');
    expect(formatKey(detectKey(['Am', 'F', 'C', 'G', 'Am'].map(p))!)).toBe('Am');
    expect(formatKey(detectKey(['F', 'Bb', 'C', 'F'].map(p))!)).toBe('F');
    expect(prefersFlats({ tonic: 5, minor: false })).toBe(true);
  });
  it('suggests a capo', () => {
    // Eb Ab Bb Cm → capo 3 with C F G Am shapes.
    // Eb Ab Bb Cm: capo 1 (D G A Bm) or 3 (C F G Am).
    const best = suggestCapo(['Eb', 'Ab', 'Bb', 'Cm'].map(p))[0];
    expect([1, 3]).toContain(best.capo);
    expect(suggestCapo(['G', 'C', 'D', 'Em'].map(p))[0].capo).toBe(0);
  });
});

describe('voicings', () => {
  const top = (s: string, t = TUNINGS.standard) => voicings(p(s), t, 3).map((v) => v.frets.map((f) => (f < 0 ? 'x' : f)).join(''));
  it('finds the classic open shapes', () => {
    expect(top('C')).toContain('x32010');
    expect(top('G')[0]).toMatch(/^3200(0|3)3$/);
    expect(top('D')).toContain('xx0232');
    expect(top('Am')).toContain('x02210');
    expect(top('E')).toContain('022100');
    expect(top('Em')).toContain('022000');
    expect(top('A7')).toEqual(expect.arrayContaining([expect.stringMatching(/^x0(2020|2223)$/)]));
    expect(top('F')).toEqual(expect.arrayContaining([expect.stringMatching(/^(133211|xx3211)$/)]));
  });
  it('slash chords put the bass at the bottom', () => {
    const lowest = (s: string) => {
      const v = voicings(p(s))[0];
      const i = v.frets.findIndex((f) => f >= 0);
      return (TUNINGS.standard.strings[i] + v.frets[i]) % 12;
    };
    expect(lowest('C/E')).toBe(4);
    expect(lowest('D/F#')).toBe(6);
    expect(top('C/E')).toContain('032010');
  });
  it('ukulele', () => {
    expect(top('C', TUNINGS.ukulele)).toContain('0003');
    expect(top('Am', TUNINGS.ukulele)).toContain('2000');
    expect(top('G', TUNINGS.ukulele)).toContain('0232');
  });
  it('always finds something', () => {
    for (const s of ['C#m7b5', 'Ebmaj9', 'F#7#9', 'Gbdim7', 'Bbsus2', 'Ab6', 'B13']) expect(voicings(p(s)).length, s).toBeGreaterThan(0);
  });
});

describe('chordpro', () => {
  it('parses inline chords and sections', () => {
    const s = parseChordPro('{title: Song}\n{artist: Me}\n{key: G}\n{capo: 2}\n{soc}\n[G]Hello [D]world\n[Em] [C]\n{eoc}\n');
    expect(s.title).toBe('Song');
    expect(s.capo).toBe(2);
    expect(s.sections[0].kind).toBe('chorus');
    expect(s.sections[0].lines[0]).toEqual({ type: 'lyric', parts: [{ chord: 'G', text: 'Hello ' }, { chord: 'D', text: 'world' }] });
    expect(s.sections[0].lines[1]).toEqual({ type: 'chords', chords: ['Em', 'C'] });
    expect(songChords(s).map((c) => formatChord(c))).toEqual(['G', 'D', 'Em', 'C']);
  });
  it('renders in Italian and transposed', () => {
    expect(displayChord('Am', { transpose: 2, notation: 'it', flats: false, simplify: false })).toBe('Sim');
  });
});

describe('import', () => {
  it('chord lines', () => {
    expect(isChordLine('G    D/F#   Em   C')).toBe(true);
    expect(isChordLine('DO   SOL   LAm   FA')).toBe(true);
    expect(isChordLine('A man walks down the street')).toBe(false);
    expect(isChordLine('la la la')).toBe(false);
    expect(isChordLine('Mi sono innamorato')).toBe(false);
    expect(isChordLine('| G | D | Em | C | (x2)')).toBe(true);
  });
  it('merges chords over lyrics', () => {
    expect(merge('G        D', 'Hello my friend')).toBe('[G]Hello my [D]friend');
    expect(merge('    C         G', 'Short')).toBe('Shor[C]t         [G]');
  });
  it('imports an Ultimate Guitar paste', () => {
    const ug = `Wonderwall Chords by Oasis
Difficulty: intermediate
Tuning: E A D G B E
Capo: 2nd fret

[Intro]
Em7  G  Dsus4  A7sus4

[Verse 1]
Em7          G
Today is gonna be the day
         Dsus4                A7sus4
That they're gonna throw it back to you

[Chorus]
C        D         Em
Because maybe
`;
    const cp = importText(ug);
    expect(cp).toContain('{title: Wonderwall}');
    expect(cp).toContain('{artist: Oasis}');
    expect(cp).toContain('{capo: 2}');
    expect(cp).toContain('{comment: Intro}');
    expect(cp).toContain('[Em7] [G] [Dsus4] [A7sus4]');
    expect(cp).toContain('{start_of_verse: Verse 1}');
    expect(cp).toContain('[Em7]Today is gonn[G]a be the day');
    expect(cp).toContain('{start_of_chorus: Chorus}');
    const song = parseChordPro(cp);
    expect(song.sections.map((s) => s.kind)).toEqual(['other', 'verse', 'chorus']);
  });
  it('imports Ultimate Guitar [ch] markup', () => {
    const cp = importText('[Verse]\n[ch]C[/ch]       [ch]G[/ch]\nHello there friend\n');
    expect(cp).toContain('[C]Hello th[G]ere friend');
  });
  it('imports an Accordi e Spartiti paste (Italian notation)', () => {
    const it_ = `La canzone del sole - Lucio Battisti

DO              SOL
Le bionde trecce gli occhi azzurri e poi
LAm               MI
le tue calzette rosse

RIT.
FA          DO
Oh mare nero
`;
    const cp = importText(it_);
    expect(cp).toContain('{title: La canzone del sole}');
    expect(cp).toContain('{artist: Lucio Battisti}');
    expect(cp).toContain('[C]Le bionde trecce[G] gli occhi azzurri e poi');
    expect(cp).toContain('[Am]le tue calzette ro[E]sse');
    expect(cp).toContain('{start_of_chorus: RIT}');
  });
  it('keeps ChordPro as is', () => {
    const src = '{title: X}\n[C]Hi\n';
    expect(importText(src)).toBe(src);
  });
});
