import { useEffect, useMemo, useState } from 'react';
import { recommendations } from '../api';
import { effectiveLevel, signal, useAccount } from '../account';
import { songKey, type Rec } from '../lib/recs';
import { useT } from '../i18n';
import { go } from '../router';
import { useSettings } from '../settings';
import { useLibrary } from '../store';
import { LevelBadge } from './LevelBadge';

const CACHE = 'justchords-recs';

export function Recs() {
  const t = useT();
  const st = useSettings();
  const lib = useLibrary();
  const { profile } = useAccount();
  const level = effectiveLevel(lib.songs);
  const [recs, setRecs] = useState<Rec[] | null>(null);
  const [genreNames, setGenreNames] = useState<Record<string, string>>({});
  const [err, setErr] = useState(false);
  const known = useMemo(() => lib.songs.map((s) => songKey(s.title, s.artist)), [lib.songs]);
  const empty = !profile.genres.length && !Object.keys(profile.artists).length && !lib.songs.length;
  // Refetch when the taste changes meaningfully, not on every signal.
  const sig = JSON.stringify([profile.genres, profile.level, level, Object.keys(profile.artists).sort().slice(0, 12), profile.disliked.length, known.length]);

  useEffect(() => {
    if (!lib.loaded || empty) return;
    let live = true;
    try {
      const c = JSON.parse(sessionStorage.getItem(CACHE) ?? 'null');
      if (c?.sig === sig && Date.now() - c.at < 3600_000) {
        setRecs(c.recs);
        setGenreNames(c.names);
        return;
      }
    } catch {
      /* no cache */
    }
    const timer = setTimeout(() => {
      recommendations(profile, level, known)
        .then((r) => {
          if (!live) return;
          const names = Object.fromEntries(r.genres.map((g) => [g.id, st.lang === 'it' ? g.it : g.en]));
          setRecs(r.recs);
          setGenreNames(names);
          setErr(false);
          try {
            sessionStorage.setItem(CACHE, JSON.stringify({ sig, at: Date.now(), recs: r.recs, names }));
          } catch {
            /* full */
          }
        })
        .catch(() => live && setErr(true));
    }, 300);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [sig, lib.loaded]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!lib.loaded) return null;
  if (empty)
    return (
      <section className="recs">
        <div className="recs-cta">
          <div>
            <h2>{t('forYou')}</h2>
            <p className="muted">{t('recsCta')}</p>
          </div>
          <button className="btn primary" onClick={() => go('/profile')}>
            {t('pickGenres')}
          </button>
        </div>
      </section>
    );

  const reason = (r: Rec) =>
    r.reason.type === 'artist'
      ? `${t('becauseYouPlay')} ${r.reason.name}`
      : r.reason.type === 'more'
        ? `${t('moreFrom')} ${r.artist}`
        : r.reason.type === 'genre'
          ? genreNames[r.reason.name ?? ''] ?? r.reason.name
          : t('forYourLevel');

  const feedback = (r: Rec, kind: 'like' | 'dislike') => {
    signal(kind, { title: r.title, artist: r.artist, genres: r.genres });
    if (kind === 'dislike') setRecs((list) => list?.filter((x) => x.key !== r.key) ?? null);
  };

  return (
    <section className="recs">
      <div className="lib-head">
        <h2>{t('forYou')}</h2>
        <button className="link-btn" onClick={() => go('/profile')}>
          {t('tuneRecs')}
        </button>
      </div>
      {err && !recs && <p className="muted">{t('recsOffline')}</p>}
      {!recs && !err && <div className="rec-row skeleton" aria-hidden="true">{Array.from({ length: 4 }, (_, i) => <div key={i} className="rec-card" />)}</div>}
      {recs && (
        <div className="rec-row">
          {recs.map((r) => (
            <div key={r.key} className="rec-card">
              <button
                className="rec-open"
                onClick={() => {
                  signal('open', r);
                  go(`/find?q=${encodeURIComponent(`${r.title} ${r.artist}`)}&t=${encodeURIComponent(r.title)}&a=${encodeURIComponent(r.artist)}`);
                }}
              >
                {r.cover ? (
                  <img
                    src={r.cover}
                    alt=""
                    loading="lazy"
                    width="250"
                    height="250"
                    onError={(e) => {
                      // Cover unavailable: show the note placeholder instead.
                      const ph = document.createElement('span');
                      ph.className = 'rec-cover-ph';
                      ph.textContent = '♪';
                      e.currentTarget.replaceWith(ph);
                    }}
                  />
                ) : (
                  <span className="rec-cover-ph">♪</span>
                )}
                <span className="rec-title">{r.title}</span>
                <span className="rec-artist">{r.artist}</span>
                <span className="rec-reason">{reason(r)}</span>
              </button>
              <div className="rec-foot">
                {r.level ? <LevelBadge level={r.level} /> : <span />}
                <span className="rec-fb">
                  <button onClick={() => feedback(r, 'like')} aria-label={t('like')} title={t('like')} className={profile.liked.includes(r.key) ? 'on' : ''}>
                    👍
                  </button>
                  <button onClick={() => feedback(r, 'dislike')} aria-label={t('dislike')} title={t('dislike')}>
                    👎
                  </button>
                </span>
              </div>
            </div>
          ))}
        </div>
      )}
      {!profile.genres.length && (
        <button className="link-btn small" onClick={() => go('/profile')}>
          {t('pickGenres')} →
        </button>
      )}
    </section>
  );
}
