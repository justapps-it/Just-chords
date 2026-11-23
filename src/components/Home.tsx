import { useMemo, useState } from 'react';
import { useT } from '../i18n';
import { go } from '../router';
import { saveSetlists, uid, useLibrary } from '../store';
import { useAndroidRelease } from '../app-release';

const SITES = [
  { name: 'Ultimate Guitar', url: (q: string) => `https://www.ultimate-guitar.com/search.php?search_type=title&value=${encodeURIComponent(q)}` },
  { name: 'Accordi e Spartiti', url: (q: string) => `https://www.accordiespartiti.it/?s=${encodeURIComponent(q)}` },
];

const norm = (s: string) => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');

export function Home() {
  const t = useT();
  const lib = useLibrary();
  const android = useAndroidRelease();
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<'recent' | 'all' | 'fav'>('recent');

  const songs = useMemo(() => {
    let list = [...lib.songs];
    if (q.trim()) {
      const words = norm(q).split(/\s+/).filter(Boolean);
      list = list.filter((s) => words.every((w) => norm(`${s.title} ${s.artist}`).includes(w)));
    } else if (filter === 'fav') list = list.filter((s) => s.favorite);
    return filter === 'all' && !q.trim()
      ? list.sort((a, b) => a.title.localeCompare(b.title))
      : list.sort((a, b) => (b.opened ?? b.updated) - (a.opened ?? a.updated));
  }, [lib.songs, q, filter]);

  const newSetlist = () => {
    const name = prompt(t('setlistName'));
    if (!name?.trim()) return;
    const id = uid();
    void saveSetlists([...lib.setlists, { id, name: name.trim(), songs: [], updated: Date.now() }]).then(() => go(`/setlist/${id}`));
  };

  return (
    <div className="home">
      <section className="hero">
        <h1>{t('tagline')}</h1>
        <p className="lead">{t('lead')}</p>
      </section>

      <section className="searchbox">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (q.trim() && !songs.length) window.open(SITES[0].url(q), '_blank', 'noopener');
          }}
        >
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('search')} aria-label={t('search')} autoComplete="off" />
        </form>
        {q.trim() && (
          <div className="search-out">
            <div className="search-sites">
              <span className="muted">{t('searchOn')}</span>
              {SITES.map((s) => (
                <a key={s.name} className="chip" href={s.url(q)} target="_blank" rel="noopener noreferrer">
                  {s.name} ↗
                </a>
              ))}
              <button className="chip primary" onClick={() => go(`/import?title=${encodeURIComponent(q)}`)}>
                {t('paste')}
              </button>
            </div>
            <p className="muted small">{t('importHint')}</p>
          </div>
        )}
      </section>

      <section className="tools">
        {(
          [
            ['tuner', '🎸', '/tuner'],
            ['metronome', '⏱', '/metronome'],
            ['listen', '🎧', '/listen'],
            ['dictionary', '📖', '/chords'],
          ] as const
        ).map(([k, icon, path]) => (
          <button key={k} className="tool-card" onClick={() => go(path)}>
            <span className="tool-icon" aria-hidden="true">
              {icon}
            </span>
            <span>{t(k)}</span>
          </button>
        ))}
      </section>

      <section className="lib">
        <div className="lib-head">
          <h2>{q.trim() ? t('inLibrary') : t('library')}</h2>
          <div className="lib-actions">
            {!q.trim() && (
              <div className="seg">
                <button className={filter === 'recent' ? 'on' : ''} onClick={() => setFilter('recent')}>
                  {t('recent')}
                </button>
                <button className={filter === 'all' ? 'on' : ''} onClick={() => setFilter('all')}>
                  {t('all')}
                </button>
                <button className={filter === 'fav' ? 'on' : ''} onClick={() => setFilter('fav')}>
                  ★
                </button>
              </div>
            )}
            <button className="btn primary" onClick={() => go('/import')}>
              + {t('importBtn')}
            </button>
          </div>
        </div>
        {lib.loaded && !lib.songs.length && <p className="empty">{t('empty')}</p>}
        {lib.loaded && lib.songs.length > 0 && !songs.length && <p className="muted">{t('noResults')}</p>}
        <ul className="song-list">
          {songs.map((s) => (
            <li key={s.id}>
              <a href={`#/song/${s.id}`} className="song-row">
                <span className="song-title">
                  {s.favorite && <span className="star">★ </span>}
                  {s.title}
                </span>
                <span className="song-sub">{s.artist}</span>
              </a>
            </li>
          ))}
        </ul>
      </section>

      <section className="lib">
        <div className="lib-head">
          <h2>{t('setlists')}</h2>
          <button className="btn" onClick={newSetlist}>
            + {t('newSetlist')}
          </button>
        </div>
        <ul className="song-list">
          {lib.setlists.map((l) => (
            <li key={l.id}>
              <a href={`#/setlist/${l.id}`} className="song-row">
                <span className="song-title">{l.name}</span>
                <span className="song-sub">
                  {l.songs.length} {t('songs')}
                </span>
              </a>
            </li>
          ))}
        </ul>
      </section>

      {android && (
        <section className="android" id="android">
          <div className="android-card">
            <img className="android-icon" src="/icons/icon-192.png?v=1" alt="" width="80" height="80" />
            <div className="android-body">
              <span className="android-badge">
                {t('appBadge')} · v{android.version}
              </span>
              <h2>{t('appTitle')}</h2>
              <p>{t('appText')}</p>
            </div>
            <div className="android-cta">
              <a className="btn primary big" href={android.url} rel="noopener">
                {t('appDownload')} <small>{android.sizeMB} MB</small>
              </a>
              <p className="muted small">{t('appInstall')}</p>
            </div>
          </div>
        </section>
      )}

      <p className="muted small note">{t('privacy')}</p>
    </div>
  );
}
