import { useEffect, useState } from 'react';
import { ApiError, findVideos } from '../api';
import { youtubeId } from '../lib/sources';
import { useT } from '../i18n';
import { patchSong, type Song } from '../store';

/** YouTube player docked under the toolbar, to play along with the sheet. */
export function Player({ song, onClose }: { song: Song; onClose: () => void }) {
  const t = useT();
  const [id, setId] = useState(song.youtube ?? '');
  const [alts, setAlts] = useState<{ id: string; title: string }[]>([]);
  const [state, setState] = useState<'idle' | 'search' | 'nokey' | 'error'>(song.youtube ? 'idle' : 'search');
  const [paste, setPaste] = useState('');
  const query = `${song.artist} ${song.title}`.trim();

  useEffect(() => {
    if (song.youtube) return;
    let live = true;
    findVideos(query)
      .then((r) => {
        if (!live) return;
        if (!r.items.length) return setState('error');
        setAlts(r.items);
        choose(r.items[0].id);
      })
      .catch((e) => live && setState(e instanceof ApiError && e.status === 501 ? 'nokey' : 'error'));
    return () => {
      live = false;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function choose(v: string) {
    setId(v);
    setState('idle');
    patchSong(song.id, { youtube: v, updated: Date.now() });
  }

  const usePasted = () => {
    const v = youtubeId(paste.trim());
    if (v) choose(v);
  };

  return (
    <section className="player" aria-label={t('listenSong')}>
      <div className="player-head">
        <span className="tool-label">▶ {t('listenSong')}</span>
        <button className="icon-btn" onClick={onClose} aria-label={t('close')}>
          ×
        </button>
      </div>
      {id && state === 'idle' ? (
        <div className="player-frame">
          <iframe
            src={`https://www.youtube-nocookie.com/embed/${id}?rel=0&playsinline=1&modestbranding=1`}
            title={query}
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            referrerPolicy="strict-origin-when-cross-origin"
            allowFullScreen
          />
        </div>
      ) : state === 'search' ? (
        <p className="muted">{t('searching')}</p>
      ) : null}
      {(state === 'nokey' || state === 'error' || id) && (
        <div className="player-pick">
          {alts.length > 1 && (
            <select value={id} onChange={(e) => choose(e.target.value)} aria-label={t('otherVideo')}>
              {alts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.title.replace(/&#39;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, '&')}
                </option>
              ))}
            </select>
          )}
          <form
            className="paste-row"
            onSubmit={(e) => {
              e.preventDefault();
              usePasted();
            }}
          >
            <input value={paste} onChange={(e) => setPaste(e.target.value)} placeholder={t('pasteYoutube')} inputMode="url" />
            <button className="btn" disabled={!youtubeId(paste.trim())}>
              OK
            </button>
          </form>
          <a className="small" href={`https://www.youtube.com/results?search_query=${encodeURIComponent(query)}`} target="_blank" rel="noopener noreferrer">
            {t('searchYoutube')} ↗
          </a>
        </div>
      )}
    </section>
  );
}
