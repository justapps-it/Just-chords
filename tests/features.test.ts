import { describe, expect, it } from 'vitest';
import { aesArtist, relevant, parseAesApi, parseAesSearch, parseAesSong, parseSpotify, parseUgSearch, parseUgTab, splitVideoTitle, youtubeId, linkKind, sourceOf } from '../src/lib/sources';
import { chordsDifficulty, songDifficulty, estimateLevel } from '../src/lib/difficulty';
import { emptyProfile, learn, plan, rank, songKey, type Candidate } from '../src/lib/recs';
import { mergeLibrary } from '../src/lib/sync';
import { importText } from '../src/lib/chordpro';
import { parseChord } from '../src/lib/music';

const ugPage = (data: unknown) => `<html><div class="js-store" data-content="${JSON.stringify({ store: { page: { data } } }).replace(/&/g, '&amp;').replace(/"/g, '&quot;')}"></div></html>`;

describe('sources', () => {
  it('parses Ultimate Guitar search results, chords only, best first', () => {
    const html = ugPage({
      results: [
        { song_name: 'Wonderwall', artist_name: 'Oasis', type: 'Chords', rating: 4.6, votes: 100, tab_url: 'https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1', difficulty: 'novice' },
        { song_name: 'Wonderwall', artist_name: 'Oasis', type: 'Tabs', rating: 4.9, votes: 900, tab_url: 'https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-tabs-2' },
        { song_name: 'Wonderwall', artist_name: 'Oasis', type: 'Chords', rating: 4.9, votes: 5000, tab_url: 'https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-3', version: 2 },
        { song_name: 'Wonderwall', artist_name: 'Oasis', type: 'Official', marketing_type: 'TabProAccess' },
      ],
    });
    const r = parseUgSearch(html);
    expect(r.map((x) => x.url.slice(-1))).toEqual(['3', '1']);
    expect(r[1].level).toBe(1);
    expect(r[0].kind).toBe('Chords v2');
  });

  it('parses an Ultimate Guitar tab page into importable text', () => {
    const html = ugPage({
      tab: { song_name: 'Wonderwall', artist_name: 'Oasis', tonality_name: 'F#m' },
      tab_view: { meta: { capo: 2, difficulty: 'intermediate' }, wiki_tab: { content: '[Verse]\r\n[ch]Em7[/ch]   [ch]G[/ch]\r\nToday is gonna be the day' } },
    });
    const s = parseUgTab(html, 'https://tabs.ultimate-guitar.com/tab/x')!;
    expect(s).toMatchObject({ title: 'Wonderwall', artist: 'Oasis', capo: 2, key: 'F#m', level: 2 });
    const cp = importText(s.text, { title: s.title, artist: s.artist });
    expect(cp).toContain('[Em7]Today');
  });

  it('parses Accordi e Spartiti search (REST and HTML) and song pages', () => {
    const api = parseAesApi([
      { title: 'Albachiara &#8211; Vasco Rossi', url: 'https://www.accordiespartiti.it/accordi/italiani/vasco-rossi/albachiara/' },
      { title: 'Chi siamo', url: 'https://www.accordiespartiti.it/chi-siamo/' },
    ]);
    expect(api).toEqual([{ source: 'aes', url: 'https://www.accordiespartiti.it/accordi/italiani/vasco-rossi/albachiara/', kind: 'Accordi', title: 'Albachiara', artist: 'Vasco Rossi' }]);
    const html = parseAesSearch('<a href="https://www.accordiespartiti.it/accordi/italiani/vasco-rossi/albachiara/">Albachiara</a><a href="https://www.accordiespartiti.it/accordi/italiani/vasco-rossi/albachiara/">Leggi</a>');
    expect(html).toHaveLength(1);
    expect(html[0].artist).toBe('Vasco Rossi');
    const song = parseAesSong(
      '<h1>Albachiara – Vasco Rossi</h1><div class="entry-content"><pre>DO          SOL\nRespiri piano per non far rumore\n</pre></div>',
      'https://www.accordiespartiti.it/accordi/italiani/vasco-rossi/albachiara/',
    )!;
    expect(song.title).toBe('Albachiara');
    expect(importText(song.text, song)).toContain('[C]Respiri');
  });

  it('fixes Accordi e Spartiti artist names and filters results', () => {
    expect(aesArtist('rossi-vasco')).toBe('Vasco Rossi');
    expect(aesArtist('de-andre-fabrizio')).toBe('Fabrizio De Andre');
    expect(aesArtist('modena-city-ramblers')).toBe('Modena City Ramblers');
    expect(relevant([{ title: 'Albachiara', artist: 'Vasco Rossi' }, { title: '5 Minuti', artist: 'Alfa' }], 'albachiara')).toHaveLength(1);
  });

  it('understands YouTube, YouTube Music and Spotify links', () => {
    expect(youtubeId('https://youtu.be/dQw4w9WgXcQ?t=3')).toBe('dQw4w9WgXcQ');
    expect(youtubeId('https://music.youtube.com/watch?v=abcdefghijk&list=x')).toBe('abcdefghijk');
    expect(youtubeId('https://www.youtube.com/shorts/abcdefghijk')).toBe('abcdefghijk');
    expect(linkKind('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=1')).toBe('spotify');
    expect(linkKind('https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1')).toBe('chords');
    expect(sourceOf('http://www.accordiespartiti.it/x')).toBeNull();
    expect(splitVideoTitle('Oasis - Wonderwall (Official Video)', 'OasisVEVO')).toEqual({ artist: 'Oasis', title: 'Wonderwall' });
    expect(splitVideoTitle('Albachiara', 'Vasco Rossi - Topic')).toEqual({ title: 'Albachiara', artist: 'Vasco Rossi' });
    expect(parseSpotify('<meta property="og:title" content="Wonderwall - Remastered"/><meta property="og:description" content="Oasis · Morning Glory · Song · 1995"/>')).toEqual({ title: 'Wonderwall - Remastered', artist: 'Oasis' });
  });
});

describe('difficulty', () => {
  const ch = (s: string) => s.split(' ').map((x) => parseChord(x)!);
  it('rates open chords easy and barre-heavy songs hard', () => {
    expect(chordsDifficulty(ch('G D Em C')).level).toBe(1);
    expect(chordsDifficulty(ch('F#m7b5 Bbmaj7 Ebm C#7#9 G#m Abdim7 Fm'), 4).level).toBe(3);
    const mid = chordsDifficulty(ch('C G Am F Dm E7 Bm'));
    expect(mid.level).toBe(2);
    expect(mid.hard).toContain('F');
  });
  it('rates a ChordPro song and estimates the player level', () => {
    expect(songDifficulty('{title: x}\n[G]one [D]two [Em]three [C]four').level).toBe(1);
    expect(estimateLevel([{ level: 1, weight: 1 }])).toBe(1);
    expect(estimateLevel([{ level: 1, weight: 3 }, { level: 2, weight: 3 }, { level: 3, weight: 0.5 }])).toBe(2);
  });
});

describe('recommendations', () => {
  it('learns artists and genres from signals, and forgets dislikes', () => {
    let p = emptyProfile();
    p = learn(p, 'import', { title: 'Wonderwall', artist: 'Oasis', genres: ['rock'] });
    p = learn(p, 'favorite', { title: 'Wonderwall', artist: 'Oasis' });
    expect(p.artists.oasis).toBeGreaterThan(4);
    expect(p.genreBoost.rock).toBeGreaterThan(0);
    p = learn(p, 'dislike', { title: 'Song 2', artist: 'Blur' });
    expect(p.disliked).toContain(songKey('Song 2', 'Blur'));
    p = learn(p, 'like', { title: 'Song 2', artist: 'Blur' });
    expect(p.disliked).not.toContain(songKey('Song 2', 'Blur'));
  });

  it('ranks by taste and level, skips known songs, keeps variety', () => {
    const p = { ...emptyProfile(), genres: ['cantautori'], artists: { 'lucio battisti': 10 } };
    const c = (title: string, artist: string, extra: Partial<Candidate> = {}): Candidate => ({ title, artist, reason: { type: 'genre', name: 'cantautori' }, genres: ['cantautori'], ...extra });
    const cands = [
      c('Emozioni', 'Lucio Battisti', { reason: { type: 'more', name: 'Lucio Battisti' }, level: 2 }),
      c('La canzone del sole', 'Lucio Battisti', { level: 1 }),
      c('Il mio canto libero', 'Lucio Battisti'),
      c('Acqua azzurra', 'Lucio Battisti'),
      c('Hotel California', 'Eagles', { genres: ['classicrock'], level: 3, reason: { type: 'level' } }),
      c('Rimmel', 'Francesco De Gregori', { level: 2 }),
    ];
    const r = rank(cands, p, { level: 2, known: new Set([songKey('Acqua azzurra', 'Lucio Battisti')]), seed: 1 });
    expect(r.filter((x) => x.artist === 'Lucio Battisti')).toHaveLength(2);
    expect(r.some((x) => x.title === 'Acqua azzurra')).toBe(false);
    expect(r[r.length - 1].title).toBe('Hotel California');
    expect(plan(p, 3).top).toEqual(['lucio battisti']);
    expect(plan(p, 3).seeds.every((s) => s.genre === 'cantautori')).toBe(true);
  });
});

describe('sync', () => {
  it('merges libraries by last write, with tombstones', () => {
    const now = Date.now();
    const a = { songs: [{ id: 'x', updated: now - 10, v: 'old' }, { id: 'y', updated: now - 5 }], setlists: [], deleted: {} };
    const b = { songs: [{ id: 'x', updated: now, v: 'new' }], setlists: [{ id: 's', updated: now }], deleted: { y: now - 1 } };
    const m = mergeLibrary(a, b);
    expect(m.songs).toEqual([{ id: 'x', updated: now, v: 'new' }]);
    expect(m.setlists).toHaveLength(1);
    // Re-created after the deletion: comes back.
    const m2 = mergeLibrary(m, { songs: [{ id: 'y', updated: now + 5 }], setlists: [], deleted: {} });
    expect(m2.songs.map((s) => s.id).sort()).toEqual(['x', 'y']);
  });
});
