import { describe, expect, it } from 'vitest';
import { alignLyrics, analysisChordPro, clipRanges, estimateTempo, howToPlay, keyOfSegments, lyricLines, tidySegments, wav16k, type Segment, type Word } from '../src/lib/analysis';
import { formatKey } from '../src/lib/music';
import { linkKind, socialVideo, embedUrl, videoRef } from '../src/lib/sources';

/** Clicks (short decaying noise bursts) at a tempo, over a quiet hum. */
function clickTrack(bpm: number, seconds: number, sr = 22050) {
  const x = new Float32Array(seconds * sr);
  let seed = 1;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
  for (let i = 0; i < x.length; i++) x[i] = 0.05 * Math.sin((2 * Math.PI * 110 * i) / sr);
  const per = (60 / bpm) * sr;
  for (let b = 0; b * per < x.length; b++) {
    const o = Math.round(b * per);
    for (let k = 0; k < 600 && o + k < x.length; k++) x[o + k] += rnd() * Math.exp(-k / 120) * (b % 4 === 0 ? 0.9 : 0.6);
  }
  return x;
}

const seg = (start: number, end: number, chord: string | null): Segment => ({ start, end, chord });
const words = (list: [string, number, number][]): Word[] => list.map(([w, s, e]) => ({ w, s, e }));

describe('analysis', () => {
  it('finds the tempo', () => {
    for (const bpm of [92, 100, 128]) {
      const t = estimateTempo(clickTrack(bpm, 14), 22050);
      expect(Math.abs(t.bpm - bpm), `${bpm} → ${t.bpm}`).toBeLessThanOrEqual(2);
      expect(t.confidence).toBeGreaterThan(0.1);
    }
  });

  it('tidies blips and finds the key, capo and strumming', () => {
    const t = tidySegments([seg(0, 2, 'G'), seg(2, 2.3, 'Bm'), seg(2.3, 4, 'G'), seg(4, 6, 'D'), seg(6, 6.4, null), seg(6.4, 8, 'Em'), seg(8, 10, 'C')], 100);
    expect(t.map((s) => s.chord)).toEqual(['G', 'D', 'Em', 'C']);
    expect(formatKey(keyOfSegments(t)!)).toBe('G');
    const how = howToPlay([seg(0, 4, 'Eb'), seg(4, 8, 'Ab'), seg(8, 12, 'Bb'), seg(12, 16, 'Cm')], 96);
    expect([1, 3]).toContain(how.capo);
    expect(how.shapes.length).toBe(4);
    expect(how.strum).toBe('pop');
    expect(howToPlay([seg(0, 4, 'G')], 70).strum).toBe('ballad');
  });

  it('puts the chords on the sung words', () => {
    const segs = [seg(0, 2, 'G'), seg(2, 4, 'D'), seg(4, 7, 'Em')];
    const w = words([['hello', 0.1, 0.5], ['my', 0.6, 1.8], ['friend,', 2.05, 3.6], ['how', 4.1, 4.4], ['are', 4.5, 4.7], ['you?', 4.8, 5.3]]);
    expect(alignLyrics(segs, w)).toBe('[G]hello my [D]friend, [Em]how are you?');
  });

  it('labels intro and outro and breaks lines at pauses', () => {
    const segs = [seg(0, 2, 'C'), seg(2, 4, 'G'), seg(4, 8, 'Am'), seg(8, 12, 'F'), seg(12, 14, 'C'), seg(14, 16, 'G')];
    const w = words([['Respiri', 4.2, 4.8], ['piano', 4.9, 5.4], ['ti', 8.1, 8.3], ['addormenti', 8.4, 9.2]]);
    const out = alignLyrics(segs, w);
    expect(out).toBe('{comment: Intro}\n[C] [G]\n\n[Am]Respiri piano\n[F]ti addormenti\n\n{comment: Outro}\n[C] [G]');
    expect(lyricLines(words([['a', 0, 0.2], ['b', 2, 2.2]])).length).toBe(2);
  });

  it('writes a ChordPro song', () => {
    const segs = [seg(0, 4, 'Eb'), seg(4, 8, 'Ab'), seg(8, 12, 'Bb'), seg(12, 16, 'Cm')];
    const how = howToPlay(segs, 96);
    const cp = analysisChordPro({ title: 'Prova', segs, how, strumLabel: 'Ritmo' });
    expect(cp).toMatch(/\{title: Prova\}/);
    expect(cp).toMatch(/\{capo: [13]\}/);
    expect(cp).toMatch(/\{tempo: 96\}/);
    // With a capo the sheet holds the shapes to play.
    expect(cp).toContain(how.shapes.map((c) => `[${c}]`).join(' '));
    expect(how.shapes).toEqual(how.capo === 3 ? ['C', 'F', 'G', 'Am'] : ['D', 'G', 'A', 'Bm']);
    const noCapo = howToPlay([seg(0, 4, 'G'), seg(4, 8, 'D')], 100);
    expect(analysisChordPro({ title: 'x', segs: [seg(0, 4, 'G'), seg(4, 8, 'D')], how: noCapo, strumLabel: 'R' })).toContain('[G] [D]');
  });

  it('prepares audio for Whisper', () => {
    const x = new Float32Array(44100).fill(0.5);
    const wav = wav16k(x, 44100);
    expect(wav.length).toBe(44 + 16000 * 2);
    expect(String.fromCharCode(...wav.slice(0, 4))).toBe('RIFF');
    expect(new DataView(wav.buffer).getInt16(44 + 100, true)).toBe(Math.round(0.5 * 32767));
    const long = new Float32Array(70 * 8000);
    const r = clipRanges(long, 8000);
    expect(r.length).toBe(3);
    for (const [a, b] of r) expect((b - a) / 8000).toBeLessThanOrEqual(28);
    expect(r[r.length - 1][1]).toBe(long.length);
  });

  it('recognises TikTok and Instagram links', () => {
    expect(socialVideo('https://www.tiktok.com/@someone/video/7312345678901234567?is_from_webapp=1')).toEqual({ platform: 'tiktok', id: '7312345678901234567' });
    expect(socialVideo('https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=x')).toEqual({ platform: 'instagram', id: 'C1a2B3c4D5e' });
    expect(socialVideo('https://www.instagram.com/p/C1a2B3c4D5e/')).toEqual({ platform: 'instagram', id: 'C1a2B3c4D5e' });
    expect(linkKind('https://vm.tiktok.com/ZMabc123/')).toBe('tiktok');
    expect(linkKind('https://www.instagram.com/reel/C1a2B3c4D5e/')).toBe('instagram');
    expect(embedUrl(videoRef('https://youtu.be/dQw4w9WgXcQ')!)).toContain('youtube-nocookie.com/embed/dQw4w9WgXcQ');
    expect(embedUrl({ platform: 'tiktok', id: '1' })).toContain('tiktok.com/player/v1/1');
  });
});
