import { useState } from 'react';
import { useT } from '../i18n';
import { go } from '../router';
import { saveSetlists, useLibrary, type Setlist as SL } from '../store';

export function Setlist({ list }: { list: SL }) {
  const t = useT();
  const lib = useLibrary();
  const [adding, setAdding] = useState(false);
  const byId = new Map(lib.songs.map((s) => [s.id, s]));
  const update = (patch: Partial<SL>) => void saveSetlists(lib.setlists.map((l) => (l.id === list.id ? { ...l, ...patch, updated: Date.now() } : l)));
  const move = (i: number, d: number) => {
    const songs = [...list.songs];
    const j = i + d;
    if (j < 0 || j >= songs.length) return;
    [songs[i], songs[j]] = [songs[j], songs[i]];
    update({ songs });
  };
  const play = () => {
    void document.documentElement.requestFullscreen?.().catch(() => {});
    go(`/stage/${list.id}/0`);
  };
  return (
    <div className="page setlist">
      <button className="link-btn" onClick={() => go('/')}>
        ← {t('library')}
      </button>
      <h1
        contentEditable
        suppressContentEditableWarning
        onBlur={(e) => e.currentTarget.textContent?.trim() && update({ name: e.currentTarget.textContent.trim() })}
      >
        {list.name}
      </h1>
      <div className="import-actions">
        <button className="btn primary big" disabled={!list.songs.length} onClick={play}>
          ▶ {t('play')}
        </button>
        <button className="btn" onClick={() => setAdding((a) => !a)}>
          + {t('addSongs')}
        </button>
        <button
          className="btn danger"
          onClick={() => {
            if (confirm(`${t('delete')}?`)) void saveSetlists(lib.setlists.filter((l) => l.id !== list.id)).then(() => go('/'));
          }}
        >
          {t('delete')}
        </button>
      </div>
      <ol className="song-list numbered">
        {list.songs.map((id, i) => {
          const s = byId.get(id);
          if (!s) return null;
          return (
            <li key={id} className="song-row">
              <a href={`#/song/${id}`}>
                <span className="song-title">{s.title}</span>
                <span className="song-sub">{s.artist}</span>
              </a>
              <span className="row-actions">
                <button onClick={() => move(i, -1)} aria-label="Up">
                  ↑
                </button>
                <button onClick={() => move(i, 1)} aria-label="Down">
                  ↓
                </button>
                <button onClick={() => update({ songs: list.songs.filter((x) => x !== id) })} aria-label={t('remove')}>
                  ×
                </button>
              </span>
            </li>
          );
        })}
      </ol>
      {adding && (
        <ul className="song-list picker">
          {lib.songs
            .filter((s) => !list.songs.includes(s.id))
            .sort((a, b) => a.title.localeCompare(b.title))
            .map((s) => (
              <li key={s.id}>
                <button className="song-row" onClick={() => update({ songs: [...list.songs, s.id] })}>
                  <span className="song-title">+ {s.title}</span>
                  <span className="song-sub">{s.artist}</span>
                </button>
              </li>
            ))}
        </ul>
      )}
    </div>
  );
}
