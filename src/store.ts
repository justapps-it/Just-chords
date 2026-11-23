// Songs and setlists live on the device (IndexedDB). With a Google account
// they are also synced (see account.ts); deletions are kept as tombstones.
import { createStore, del, entries, get, set } from 'idb-keyval';
import { useSyncExternalStore } from 'react';
import { songMetaFromChordPro } from './lib/chordpro';

export interface Song {
  id: string;
  chordpro: string;
  title: string;
  artist: string;
  created: number;
  updated: number;
  opened?: number;
  /** Per-song view settings. */
  transpose: number;
  capo?: number;
  scroll: number;
  font: number;
  favorite?: boolean;
  /** Page the chords came from. */
  source?: string;
  /** YouTube video to play along. */
  youtube?: string;
}

export interface Setlist {
  id: string;
  name: string;
  songs: string[];
  updated: number;
}

const db = (() => {
  try {
    return createStore('justchords', 'data');
  } catch {
    return undefined;
  }
})();

let songs: Song[] = [];
let setlists: Setlist[] = [];
let loaded = false;
const listeners = new Set<() => void>();
let deleted: Record<string, number> = {};
let snapshot = { songs, setlists, loaded };
const changeListeners = new Set<() => void>();
const emit = (changed = true) => {
  snapshot = { songs, setlists, loaded };
  listeners.forEach((l) => l());
  if (changed && loaded) changeListeners.forEach((l) => l());
};
/** Called after any change worth syncing (not on view settings). */
export const onLibraryChange = (fn: () => void) => changeListeners.add(fn);

export async function load() {
  try {
    const all = await entries<string, Song | Setlist[]>(db);
    songs = all.filter(([k]) => k.startsWith('song:')).map(([, v]) => v as Song);
    setlists = ((await get<Setlist[]>('setlists', db)) ?? []).filter(Boolean);
    deleted = (await get<Record<string, number>>('deleted', db)) ?? {};
  } catch {
    /* private mode: memory only */
  }
  loaded = true;
  emit(false);
}

export const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);

export async function saveSong(s: Partial<Song> & { chordpro: string; id?: string }): Promise<Song> {
  const meta = songMetaFromChordPro(s.chordpro);
  const old = s.id ? songs.find((x) => x.id === s.id) : undefined;
  const song: Song = {
    transpose: 0,
    scroll: 3,
    font: 1,
    created: Date.now(),
    ...old,
    ...s,
    id: s.id ?? uid(),
    title: meta.title,
    artist: meta.artist,
    updated: Date.now(),
  } as Song;
  songs = [...songs.filter((x) => x.id !== song.id), song];
  emit();
  try {
    await set(`song:${song.id}`, song, db);
  } catch {
    /* memory only */
  }
  return song;
}

/** View settings change often: no "updated" bump. */
export function patchSong(id: string, patch: Partial<Song>) {
  const old = songs.find((x) => x.id === id);
  if (!old) return;
  const song = { ...old, ...patch };
  songs = songs.map((x) => (x.id === id ? song : x));
  // Changes that carry a new "updated" (favourite, video…) are synced.
  emit('updated' in patch);
  void set(`song:${id}`, song, db).catch(() => {});
}

export async function deleteSong(id: string) {
  songs = songs.filter((x) => x.id !== id);
  setlists = setlists.map((l) => (l.songs.includes(id) ? { ...l, songs: l.songs.filter((s) => s !== id), updated: Date.now() } : l));
  deleted = { ...deleted, [id]: Date.now() };
  emit();
  await del(`song:${id}`, db).catch(() => {});
  await set('setlists', setlists, db).catch(() => {});
  await set('deleted', deleted, db).catch(() => {});
}

export async function saveSetlists(next: Setlist[]) {
  const now = Date.now();
  for (const old of setlists) if (!next.some((l) => l.id === old.id)) deleted = { ...deleted, ['sl:' + old.id]: now };
  setlists = next;
  emit();
  await set('setlists', next, db).catch(() => {});
  await set('deleted', deleted, db).catch(() => {});
}

export const findBySource = (url: string) => songs.find((s) => s.source === url);

/** Everything the account sync needs. */
export const exportLibrary = () => ({ songs, setlists, deleted });

/** Replaces the library with the merged copy from the account. */
export async function importLibrary(lib: { songs: unknown[]; setlists: unknown[]; deleted: Record<string, number> }) {
  const incoming = lib.songs as Song[];
  const gone = songs.filter((s) => !incoming.some((x) => x.id === s.id));
  // Keep local view settings (transpose, scroll…) of songs that did not change.
  songs = incoming.map((x) => {
    const mine = songs.find((s) => s.id === x.id);
    return mine && mine.updated === x.updated ? mine : x;
  });
  setlists = lib.setlists as Setlist[];
  deleted = lib.deleted ?? {};
  emit(false);
  try {
    for (const g of gone) await del(`song:${g.id}`, db);
    for (const x of songs) await set(`song:${x.id}`, x, db);
    await set('setlists', setlists, db);
    await set('deleted', deleted, db);
  } catch {
    /* memory only */
  }
}

export function useLibrary() {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => snapshot,
  );
}
