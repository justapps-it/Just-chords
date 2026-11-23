// Library sync: last write wins per song and setlist, deletions travel as
// tombstones so a song deleted on one device disappears on the others.

export interface SyncSong {
  id: string;
  updated: number;
  [k: string]: unknown;
}
export interface Library {
  songs: SyncSong[];
  setlists: { id: string; updated: number; [k: string]: unknown }[];
  deleted: Record<string, number>;
}

export function mergeLibrary(a: Library, b: Library): Library {
  const deleted: Record<string, number> = { ...a.deleted };
  for (const [k, t] of Object.entries(b.deleted ?? {})) if (typeof t === 'number') deleted[k] = Math.max(deleted[k] ?? 0, t);
  const pick = <T extends { id: string; updated: number }>(xs: T[], ys: T[], prefix: string) => {
    const m = new Map<string, T>();
    for (const x of [...(xs ?? []), ...(ys ?? [])]) {
      if (!x || typeof x.id !== 'string') continue;
      const old = m.get(x.id);
      if (!old || (x.updated ?? 0) > (old.updated ?? 0)) m.set(x.id, x);
    }
    return [...m.values()].filter((x) => (deleted[prefix + x.id] ?? 0) < (x.updated ?? 0));
  };
  // Tombstones older than a year are forgotten.
  const yearAgo = Date.now() - 365 * 864e5;
  for (const k of Object.keys(deleted)) if (deleted[k] < yearAgo) delete deleted[k];
  return { songs: pick(a.songs, b.songs, ''), setlists: pick(a.setlists, b.setlists, 'sl:'), deleted };
}

