// Songs and setlists live on the device (IndexedDB). Nothing is uploaded.
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
let snapshot = { songs, setlists, loaded };
const emit = () => {
  snapshot = { songs, setlists, loaded };
  listeners.forEach((l) => l());
};

export async function load() {
  try {
    const all = await entries<string, Song | Setlist[]>(db);
    songs = all.filter(([k]) => k.startsWith('song:')).map(([, v]) => v as Song);
    setlists = ((await get<Setlist[]>('setlists', db)) ?? []).filter(Boolean);
  } catch {
    /* private mode: memory only */
  }
  loaded = true;
  emit();
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
  emit();
  void set(`song:${id}`, song, db).catch(() => {});
}

export async function deleteSong(id: string) {
  songs = songs.filter((x) => x.id !== id);
  setlists = setlists.map((l) => ({ ...l, songs: l.songs.filter((s) => s !== id) }));
  emit();
  await del(`song:${id}`, db).catch(() => {});
  await set('setlists', setlists, db).catch(() => {});
}

export async function saveSetlists(next: Setlist[]) {
  setlists = next;
  emit();
  await set('setlists', next, db).catch(() => {});
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
