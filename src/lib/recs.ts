// Recommendations: a taste profile learnt from what you do (import, open,
// star, 👍/👎) plus the genres and level you pick, and a ranking over
// candidates gathered from Deezer (related artists, genre seeds) and a curated
// list of songs by playing level. Pure logic, shared by the app and the Worker.
import type { Level } from './difficulty';

export interface Profile {
  genres: string[];
  /** 0 = estimate it from the songs I play. */
  level: 0 | Level;
  /** Artist (lowercase) → affinity. */
  artists: Record<string, number>;
  /** Genre → learnt weight on top of the chosen genres. */
  genreBoost: Record<string, number>;
  liked: string[];
  disliked: string[];
  updated: number;
}

export const emptyProfile = (): Profile => ({ genres: [], level: 0, artists: {}, genreBoost: {}, liked: [], disliked: [], updated: 0 });

export const songKey = (title: string, artist: string) =>
  `${artist}|${title}`
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/\s*[([].*?[)\]]/g, '')
    .replace(/[^a-z0-9|]+/g, ' ')
    .trim();

export const GENRES: { id: string; it: string; en: string; seeds: string[] }[] = [
  { id: 'pop', it: 'Pop', en: 'Pop', seeds: ['Ed Sheeran', 'Coldplay', 'Adele', 'Taylor Swift', 'Elisa', 'Tiziano Ferro'] },
  { id: 'rock', it: 'Rock', en: 'Rock', seeds: ['Oasis', 'Queen', 'Nirvana', 'The Beatles', 'Foo Fighters', 'Radiohead'] },
  { id: 'classicrock', it: 'Classic rock', en: 'Classic rock', seeds: ['Led Zeppelin', 'Pink Floyd', 'The Rolling Stones', 'Eagles', 'Dire Straits', 'AC/DC'] },
  { id: 'cantautori', it: 'Cantautori', en: 'Italian songwriters', seeds: ['Lucio Battisti', 'Fabrizio De André', 'Francesco De Gregori', 'Lucio Dalla', 'Francesco Guccini', 'Rino Gaetano'] },
  { id: 'itpop', it: 'Pop rock italiano', en: 'Italian pop rock', seeds: ['Vasco Rossi', 'Ligabue', 'Negramaro', 'Måneskin', 'Cesare Cremonini', 'Jovanotti'] },
  { id: 'indie', it: 'Indie', en: 'Indie', seeds: ['Arctic Monkeys', 'The Killers', 'The Strokes', 'Calcutta', 'Phoebe Bridgers', 'Tame Impala'] },
  { id: 'folk', it: 'Folk e acustico', en: 'Folk & acoustic', seeds: ['Bob Dylan', 'Simon & Garfunkel', 'Mumford & Sons', 'Vance Joy', 'Passenger', 'Neil Young'] },
  { id: 'blues', it: 'Blues', en: 'Blues', seeds: ['Eric Clapton', 'B.B. King', 'Stevie Ray Vaughan', 'John Mayer', 'Gary Moore', 'The Black Keys'] },
  { id: 'metal', it: 'Metal', en: 'Metal', seeds: ['Metallica', 'Iron Maiden', 'Black Sabbath', 'System of a Down', 'Avenged Sevenfold', 'Pantera'] },
  { id: 'punk', it: 'Punk', en: 'Punk', seeds: ['Green Day', 'Blink-182', 'Ramones', 'The Offspring', 'Sum 41', 'The Clash'] },
  { id: 'country', it: 'Country', en: 'Country', seeds: ['Johnny Cash', 'Dolly Parton', 'Chris Stapleton', 'Zach Bryan', 'John Denver', 'Kacey Musgraves'] },
  { id: 'soul', it: 'Soul e funk', en: 'Soul & funk', seeds: ['Bill Withers', 'Stevie Wonder', 'Marvin Gaye', 'Amy Winehouse', 'Bruno Mars', 'Otis Redding'] },
  { id: 'reggae', it: 'Reggae', en: 'Reggae', seeds: ['Bob Marley', 'UB40', 'Sublime', 'Toots and the Maytals', 'Alborosie', 'Jimmy Cliff'] },
  { id: 'jazz', it: 'Jazz e bossa nova', en: 'Jazz & bossa nova', seeds: ['Antônio Carlos Jobim', 'João Gilberto', 'Norah Jones', 'Frank Sinatra', 'Chet Baker', 'Ella Fitzgerald'] },
];

/** Songs known for being playable at each level, tagged by genre. */
export const CURATED: { title: string; artist: string; level: Level; genres: string[] }[] = [
  { title: "Knockin' on Heaven's Door", artist: 'Bob Dylan', level: 1, genres: ['folk', 'rock'] },
  { title: 'A Horse with No Name', artist: 'America', level: 1, genres: ['folk', 'classicrock'] },
  { title: 'Let It Be', artist: 'The Beatles', level: 1, genres: ['rock', 'pop'] },
  { title: 'Stand by Me', artist: 'Ben E. King', level: 1, genres: ['soul'] },
  { title: 'Zombie', artist: 'The Cranberries', level: 1, genres: ['rock'] },
  { title: 'Riptide', artist: 'Vance Joy', level: 1, genres: ['folk', 'indie'] },
  { title: 'Bad Moon Rising', artist: 'Creedence Clearwater Revival', level: 1, genres: ['classicrock', 'country'] },
  { title: 'Three Little Birds', artist: 'Bob Marley', level: 1, genres: ['reggae'] },
  { title: 'Redemption Song', artist: 'Bob Marley', level: 1, genres: ['reggae', 'folk'] },
  { title: "Ain't No Sunshine", artist: 'Bill Withers', level: 1, genres: ['soul'] },
  { title: 'Take Me Home, Country Roads', artist: 'John Denver', level: 1, genres: ['country', 'folk'] },
  { title: 'Folsom Prison Blues', artist: 'Johnny Cash', level: 1, genres: ['country'] },
  { title: 'Jolene', artist: 'Dolly Parton', level: 1, genres: ['country'] },
  { title: 'Sweet Home Alabama', artist: 'Lynyrd Skynyrd', level: 1, genres: ['classicrock'] },
  { title: 'Smells Like Teen Spirit', artist: 'Nirvana', level: 1, genres: ['rock', 'punk'] },
  { title: 'Perfect', artist: 'Ed Sheeran', level: 1, genres: ['pop'] },
  { title: 'La canzone del sole', artist: 'Lucio Battisti', level: 1, genres: ['cantautori'] },
  { title: 'Azzurro', artist: 'Adriano Celentano', level: 1, genres: ['cantautori', 'itpop'] },
  { title: 'La guerra di Piero', artist: 'Fabrizio De André', level: 1, genres: ['cantautori', 'folk'] },
  { title: 'Albachiara', artist: 'Vasco Rossi', level: 1, genres: ['itpop'] },
  { title: 'Certe notti', artist: 'Ligabue', level: 1, genres: ['itpop'] },
  { title: 'Paracetamolo', artist: 'Calcutta', level: 1, genres: ['indie', 'itpop'] },
  { title: 'Wonderwall', artist: 'Oasis', level: 2, genres: ['rock'] },
  { title: 'Wish You Were Here', artist: 'Pink Floyd', level: 2, genres: ['classicrock'] },
  { title: 'Hallelujah', artist: 'Jeff Buckley', level: 2, genres: ['folk', 'pop'] },
  { title: 'Creep', artist: 'Radiohead', level: 2, genres: ['rock', 'indie'] },
  { title: 'The House of the Rising Sun', artist: 'The Animals', level: 2, genres: ['classicrock', 'folk'] },
  { title: 'Hey There Delilah', artist: "Plain White T's", level: 2, genres: ['pop'] },
  { title: 'Fast Car', artist: 'Tracy Chapman', level: 2, genres: ['folk', 'pop'] },
  { title: 'Nothing Else Matters', artist: 'Metallica', level: 2, genres: ['metal'] },
  { title: 'Paranoid', artist: 'Black Sabbath', level: 2, genres: ['metal'] },
  { title: 'Basket Case', artist: 'Green Day', level: 2, genres: ['punk'] },
  { title: 'Wonderful Tonight', artist: 'Eric Clapton', level: 2, genres: ['blues'] },
  { title: 'Viva la Vida', artist: 'Coldplay', level: 2, genres: ['pop'] },
  { title: 'Someone Like You', artist: 'Adele', level: 2, genres: ['pop'] },
  { title: 'Let Her Go', artist: 'Passenger', level: 2, genres: ['folk', 'pop'] },
  { title: 'I Will Wait', artist: 'Mumford & Sons', level: 2, genres: ['folk'] },
  { title: 'Mr. Brightside', artist: 'The Killers', level: 2, genres: ['indie', 'rock'] },
  { title: 'Do I Wanna Know?', artist: 'Arctic Monkeys', level: 2, genres: ['indie'] },
  { title: 'Highway to Hell', artist: 'AC/DC', level: 2, genres: ['classicrock'] },
  { title: 'Is This Love', artist: 'Bob Marley', level: 2, genres: ['reggae'] },
  { title: 'Tennessee Whiskey', artist: 'Chris Stapleton', level: 2, genres: ['country', 'soul'] },
  { title: 'Rimmel', artist: 'Francesco De Gregori', level: 2, genres: ['cantautori'] },
  { title: 'Generale', artist: 'Francesco De Gregori', level: 2, genres: ['cantautori'] },
  { title: 'Piazza Grande', artist: 'Lucio Dalla', level: 2, genres: ['cantautori'] },
  { title: 'Emozioni', artist: 'Lucio Battisti', level: 2, genres: ['cantautori'] },
  { title: 'Bocca di rosa', artist: 'Fabrizio De André', level: 2, genres: ['cantautori'] },
  { title: 'Vita spericolata', artist: 'Vasco Rossi', level: 2, genres: ['itpop'] },
  { title: 'Estate', artist: 'Negramaro', level: 2, genres: ['itpop'] },
  { title: 'Marmellata #25', artist: 'Cesare Cremonini', level: 2, genres: ['itpop'] },
  { title: 'Hotel California', artist: 'Eagles', level: 3, genres: ['classicrock'] },
  { title: 'Under the Bridge', artist: 'Red Hot Chili Peppers', level: 3, genres: ['rock', 'soul'] },
  { title: 'Tears in Heaven', artist: 'Eric Clapton', level: 3, genres: ['blues', 'pop'] },
  { title: 'Love of My Life', artist: 'Queen', level: 3, genres: ['rock'] },
  { title: 'More Than Words', artist: 'Extreme', level: 3, genres: ['rock', 'pop'] },
  { title: 'Blackbird', artist: 'The Beatles', level: 3, genres: ['rock', 'folk'] },
  { title: 'Sultans of Swing', artist: 'Dire Straits', level: 3, genres: ['classicrock'] },
  { title: 'Enter Sandman', artist: 'Metallica', level: 3, genres: ['metal'] },
  { title: 'Pride and Joy', artist: 'Stevie Ray Vaughan', level: 3, genres: ['blues'] },
  { title: 'Gravity', artist: 'John Mayer', level: 3, genres: ['blues', 'pop'] },
  { title: 'The Girl from Ipanema', artist: 'Antônio Carlos Jobim', level: 3, genres: ['jazz'] },
  { title: 'Fly Me to the Moon', artist: 'Frank Sinatra', level: 3, genres: ['jazz'] },
  { title: 'Coraline', artist: 'Måneskin', level: 3, genres: ['itpop', 'rock'] },
  { title: 'Il cielo in una stanza', artist: 'Gino Paoli', level: 3, genres: ['cantautori'] },
];

// ------------------------------------------------------------------ learning

export type Signal = 'import' | 'open' | 'favorite' | 'like' | 'dislike';
// A 👎 hides that song; on the artist it weighs less than an import.
const SIGNAL: Record<Signal, number> = { import: 2, open: 0.4, favorite: 3, like: 2, dislike: -1.2 };

/** Updates the profile after something the user did with a song. */
export function learn(p: Profile, signal: Signal, song: { title: string; artist: string; genres?: string[] }): Profile {
  const a = song.artist.trim().toLowerCase();
  const artists = { ...p.artists };
  // Old affinities fade a little at every step, so taste can change.
  for (const k of Object.keys(artists)) {
    artists[k] *= 0.985;
    if (Math.abs(artists[k]) < 0.05) delete artists[k];
  }
  if (a) artists[a] = Math.max(-5, Math.min(25, (artists[a] ?? 0) + SIGNAL[signal]));
  const genreBoost = { ...p.genreBoost };
  for (const g of song.genres ?? []) genreBoost[g] = Math.max(-2, Math.min(3, (genreBoost[g] ?? 0) + SIGNAL[signal] * 0.15));
  const key = songKey(song.title, song.artist);
  const liked = signal === 'like' ? [...new Set([...p.liked, key])].slice(-300) : p.liked.filter((k) => signal !== 'dislike' || k !== key);
  const disliked = signal === 'dislike' ? [...new Set([...p.disliked, key])].slice(-300) : p.disliked.filter((k) => signal !== 'like' || k !== key);
  return { ...p, artists, genreBoost, liked, disliked, updated: Date.now() };
}

// ------------------------------------------------------------------ ranking

export interface Candidate {
  title: string;
  artist: string;
  cover?: string;
  preview?: string;
  /** Deezer rank (popularity), 0–1e6. */
  rank?: number;
  level?: Level;
  genres?: string[];
  reason: { type: 'artist' | 'genre' | 'level' | 'more'; name?: string };
}

export interface Rec extends Candidate {
  key: string;
  score: number;
}

/** Small deterministic noise so the list changes day by day, not on every reload. */
function noise(s: string, seed: number) {
  let h = seed | 0;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 2654435761);
  return ((h >>> 0) % 1000) / 1000;
}

export function rank(cands: Candidate[], p: Profile, opts: { level: Level; known: Set<string>; seed: number; limit?: number }): Rec[] {
  const maxArtist = Math.max(1, ...Object.values(p.artists));
  const disliked = new Set(p.disliked);
  const genreW = (g: string) => (p.genres.includes(g) ? 1 : 0) + (p.genreBoost[g] ?? 0) * 0.5;
  const best = new Map<string, Rec>();
  for (const c of cands) {
    const key = songKey(c.title, c.artist);
    if (!c.title || opts.known.has(key) || disliked.has(key)) continue;
    const aff = (p.artists[c.artist.toLowerCase()] ?? 0) / maxArtist;
    if (aff < -0.3) continue;
    let s = 1;
    if (c.reason.type === 'artist' && c.reason.name) s += 1.6 * Math.max(0, (p.artists[c.reason.name.toLowerCase()] ?? 0) / maxArtist);
    if (c.reason.type === 'more') s += 1.2 * Math.max(0, aff);
    s += Math.max(0, ...(c.genres ?? []).map(genreW), 0) * 1.1;
    s += aff * 0.8;
    if (c.rank) s += Math.min(0.6, Math.log10(1 + c.rank) / 10);
    if (c.level) s += c.level === opts.level ? 1 : Math.abs(c.level - opts.level) === 1 ? (c.level > opts.level ? 0.35 : 0.15) : -0.6;
    if (p.liked.includes(key)) s -= 0.5; // already liked: keep some space for new songs
    s += noise(key, opts.seed) * 0.6;
    const prev = best.get(key);
    if (!prev || prev.score < s) best.set(key, { ...c, level: c.level ?? prev?.level, cover: c.cover ?? prev?.cover, key, score: s });
  }
  // Diversity: at most two songs per artist, and alternate the reasons.
  const sorted = [...best.values()].sort((a, b) => b.score - a.score);
  const perArtist = new Map<string, number>();
  const out: Rec[] = [];
  for (const r of sorted) {
    const a = r.artist.toLowerCase();
    if ((perArtist.get(a) ?? 0) >= 2) continue;
    perArtist.set(a, (perArtist.get(a) ?? 0) + 1);
    out.push(r);
    if (out.length >= (opts.limit ?? 18)) break;
  }
  return out;
}

/** Artists and genres to explore for this profile (what the Worker fetches). */
export function plan(p: Profile, seed: number) {
  const top = Object.entries(p.artists)
    .filter(([, w]) => w > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4)
    .map(([a]) => a);
  const genres = [...new Set([...p.genres, ...Object.entries(p.genreBoost).filter(([, w]) => w > 0.4).map(([g]) => g)])].slice(0, 5);
  const seeds = genres.flatMap((g) => {
    const list = GENRES.find((x) => x.id === g)?.seeds ?? [];
    // Two seed artists per genre, rotating day by day.
    return [0, 1].map((i) => ({ genre: g, artist: list[(seed + i * 3 + g.length) % list.length] }));
  });
  return { top, seeds: seeds.filter((s) => s.artist) };
}

/** Genres of a known artist, from the seed lists. */
export function genresOfArtist(artist: string): string[] {
  const a = artist.toLowerCase();
  return GENRES.filter((g) => g.seeds.some((s) => s.toLowerCase() === a)).map((g) => g.id);
}
