import { useEffect, useState } from 'react';
import { fetchSong, resolveLink, searchChords } from '../api';
import { signal } from '../account';
import { importText } from '../lib/chordpro';
import { songDifficulty } from '../lib/difficulty';
import { linkKind, searchUrl, type LinkInfo, type SearchResult } from '../lib/sources';
import { useT } from '../i18n';
import { go, routeQuery } from '../router';
import { findBySource, patchSong, saveSong } from '../store';
import { LevelBadge } from './LevelBadge';

const SOURCE_NAME = { ug: 'Ultimate Guitar', aes: 'Accordi e Spartiti' } as const;

/** Imports a song page and opens it. Shared by search results and pasted links. */
export async function openChords(url: string, hint: { title?: string; artist?: string; youtube?: string } = {}) {
  const existing = findBySource(url);
  if (existing) {
    if (hint.youtube && !existing.youtube) patchSong(existing.id, { youtube: hint.youtube, updated: Date.now() });
    go(`/song/${existing.id}`);
    return;
  }
  const s = await fetchSong(url);
  let cp = importText(s.text, { title: s.title || hint.title, artist: s.artist || hint.artist, url });
  if (s.capo && !/\{capo:/.test(cp)) cp = cp.replace(/(\{artist:[^}]*\}\n?)/, `$1{capo: ${s.capo}}\n`);
  if (s.key && !/\{key:/.test(cp)) cp = cp.replace(/(\{artist:[^}]*\}\n?)/, `$1{key: ${s.key}}\n`);
  const song = await saveSong({ chordpro: cp, source: url, youtube: hint.youtube });
  signal('import', { title: song.title, artist: song.artist });
  go(`/song/${song.id}`, true);
}

export function Find() {
  const t = useT();
  const q = routeQuery();
  const [query, setQuery] = useState(q.get('q') ?? '');
  const [info, setInfo] = useState<LinkInfo | null>(null);
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState('');
  const url = q.get('url') ?? '';
  const hint = { title: q.get('t') ?? undefined, artist: q.get('a') ?? undefined, youtube: q.get('yt') ?? undefined };

  useEffect(() => {
    let live = true;
    setResults(null);
    setErr('');
    (async () => {
      let text = query;
      if (url) {
        const kind = linkKind(url);
        if (kind === 'chords') {
          setBusy(url);
          try {
            await openChords(url, hint);
          } catch (e) {
            if (live) {
              setErr(t('fetchFailed'));
              setBusy(null);
              setFailed(true);
            }
          }
          return;
        }
        if (!kind) return setErr(t('linkUnsupported'));
        if (kind === 'tiktok' || kind === 'instagram') return go(`/analyze?url=${encodeURIComponent(url)}`, true);
        try {
          const li = await resolveLink(url);
          if (!live) return;
          setInfo(li);
          text = [li.title, li.artist].filter(Boolean).join(' ');
          setQuery(text);
        } catch {
          if (live) setErr(t('linkFailed'));
          return;
        }
      }
      if (text.trim().length < 2) return;
      const r = await searchChords(text);
      if (!live) return;
      // One result per song, in the order of relevance the server gives.
      setResults(r.results);
      setFailed(!r.results.length);
    })();
    return () => {
      live = false;
    };
  }, [url, q.get('q')]); // eslint-disable-line react-hooks/exhaustive-deps

  const yt = info?.youtubeId ?? hint.youtube;
  const open = async (r: SearchResult) => {
    setBusy(r.url);
    setErr('');
    try {
      await openChords(r.url, { title: r.title, artist: r.artist, youtube: yt });
    } catch {
      setErr(t('fetchFailed'));
      setBusy(null);
    }
  };

  return (
    <div className="find">
      <button className="link-btn" onClick={() => go('/')}>
        ← {t('library')}
      </button>
      <form
        className="searchbox"
        onSubmit={(e) => {
          e.preventDefault();
          const v = query.trim();
          if (/^https?:\/\//.test(v)) go(`/find?url=${encodeURIComponent(v)}`);
          else if (v) go(`/find?q=${encodeURIComponent(v)}${yt ? `&yt=${yt}` : ''}`);
        }}
      >
        <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('searchOrLink')} aria-label={t('search')} />
      </form>

      {info && (
        <div className="link-info">
          <span className="chip">{info.kind === 'spotify' ? 'Spotify' : 'YouTube'}</span>
          <b>{info.title}</b>
          {info.artist && <span className="muted"> · {info.artist}</span>}
          {info.youtubeId && (
            <button className="chip primary" onClick={() => go(`/analyze?url=${encodeURIComponent(info.url)}`)}>
              ✨ {t('analyze')}
            </button>
          )}
        </div>
      )}
      {err && <p className="error">{err}</p>}
      {busy && url && <p className="muted">{t('loadingChords')}</p>}

      {results === null && !err && !url && <p className="muted">{t('searching')}</p>}
      {results === null && !err && url && !busy && <p className="muted">{t('searching')}</p>}

      {results && results.length > 0 && (
        <ul className="results">
          {results.map((r) => (
            <li key={r.url}>
              <button className="result" onClick={() => void open(r)} disabled={!!busy}>
                <span className="result-main">
                  <span className="song-title">{r.title}</span>
                  <span className="song-sub">{r.artist}</span>
                </span>
                <span className="result-meta">
                  <span className={`src src-${r.source}`}>{SOURCE_NAME[r.source]}</span>
                  {r.kind && <span className="muted small">{r.kind}</span>}
                  {r.rating !== undefined && (
                    <span className="small">
                      ★ {r.rating}
                      {r.votes ? <span className="muted"> ({r.votes})</span> : null}
                    </span>
                  )}
                  {r.level && <LevelBadge level={r.level} />}
                  {busy === r.url && <span className="small">…</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {(failed || (results && !results.length)) && query.trim() && (
        <div className="fallback">
          <p className="muted">{t('fallbackHint')}</p>
          <div className="search-sites">
            {(['ug', 'aes'] as const).map((s) => (
              <a key={s} className="chip" href={searchUrl(s, query)} target="_blank" rel="noopener noreferrer">
                {SOURCE_NAME[s]} ↗
              </a>
            ))}
            <button className="chip primary" onClick={() => go(`/import?title=${encodeURIComponent(hint.title ?? query)}`)}>
              {t('paste')}
            </button>
          </div>
        </div>
      )}
      <p className="muted small note">{t('sourceNote')}</p>
    </div>
  );
}

/** Difficulty of a saved song, for badges. */
export const levelOf = (chordpro: string) => songDifficulty(chordpro).level;
