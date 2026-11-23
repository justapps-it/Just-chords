import { useEffect, useMemo, useState } from 'react';
import { displayChord, parseChordPro, songChords, type Line, type RenderOptions } from '../lib/chordpro';
import { detectKey, formatChord, formatKey, parseChord, parseKey, prefersFlats, simplifyChord, suggestCapo, transposeChord } from '../lib/music';
import { useT } from '../i18n';
import { keepAwake } from '../audio/mic';
import { setSettings, useSettings } from '../settings';
import { deleteSong, patchSong, saveSetlists, useLibrary, type Song } from '../store';
import { ChordCard } from './Diagram';
import { go } from '../router';
import { signal } from '../account';
import { changesPerLine, chordsDifficulty } from '../lib/difficulty';
import { LevelBadge } from './LevelBadge';
import { Player } from './Player';

interface Props {
  song: Song;
  stage?: boolean;
  setlist?: { id: string; index: number; ids: string[] };
}

export function SongView({ song, stage = false, setlist }: Props) {
  const t = useT();
  const st = useSettings();
  const lib = useLibrary();
  const parsed = useMemo(() => parseChordPro(song.chordpro), [song.chordpro]);
  const chords = useMemo(() => songChords(parsed), [parsed]);
  const sheetCapo = parsed.capo;
  const capo = song.capo ?? sheetCapo;
  // Shapes move opposite to the capo so the song sounds the same.
  const shift = song.transpose + (sheetCapo - capo);
  const [simplify, setSimplify] = useState(false);
  const [zoom, setZoom] = useState<string | null>(null);
  const [scrolling, setScrolling] = useState(false);
  const [menu, setMenu] = useState(false);
  const [listen, setListen] = useState(false);

  const originalKey = useMemo(() => (parsed.key ? parseKey(parsed.key) : null) ?? detectKey(chords), [parsed.key, chords]);
  // Sounding key (for the label) and the key the shapes are written in (for sharps/flats).
  const soundingKey = originalKey && { ...originalKey, tonic: (originalKey.tonic + sheetCapo + song.transpose + 120) % 12 };
  const shapeKey = originalKey && { ...originalKey, tonic: (originalKey.tonic + shift + 120) % 12 };
  const opts: RenderOptions = { transpose: shift, notation: st.notation, flats: prefersFlats(shapeKey), simplify };

  const unique = useMemo(() => {
    const seen = new Map<string, string>();
    for (const c of chords) {
      let x = transposeChord(c, shift);
      if (simplify) x = simplifyChord(x);
      const sym = formatChord(x);
      if (!seen.has(sym)) seen.set(sym, formatChord(x, st.notation, opts.flats));
    }
    return [...seen.entries()];
  }, [chords, shift, simplify, st.notation, opts.flats]);

  const capoTips = useMemo(() => {
    const sounding = chords.map((c) => transposeChord(c, sheetCapo + song.transpose));
    return suggestCapo(sounding).slice(0, 3);
  }, [chords, sheetCapo, song.transpose]);

  useEffect(() => {
    patchSong(song.id, { opened: Date.now() });
    signal('open', { title: song.title, artist: song.artist });
  }, [song.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Difficulty of what is shown (follows transpose/capo/simplify).
  const difficulty = useMemo(() => {
    const shown = chords.map((c) => (simplify ? simplifyChord(transposeChord(c, shift)) : transposeChord(c, shift)));
    return chordsDifficulty(shown, changesPerLine(parsed));
  }, [chords, shift, simplify, parsed]);
  const toggleFavorite = () => {
    if (!song.favorite) signal('favorite', { title: song.title, artist: song.artist });
    patchSong(song.id, { favorite: !song.favorite, updated: Date.now() });
  };

  // Autoscroll.
  useEffect(() => {
    if (!scrolling) return;
    keepAwake(true);
    let last = performance.now();
    let acc = 0;
    let raf = 0;
    const step = (now: number) => {
      const dt = (now - last) / 1000;
      last = now;
      acc += dt * (6 + song.scroll * 7);
      const px = Math.floor(acc);
      if (px) {
        acc -= px;
        window.scrollBy(0, px);
        if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2) setScrolling(false);
      }
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => {
      cancelAnimationFrame(raf);
      if (!stage) keepAwake(false);
    };
  }, [scrolling, song.scroll, stage]);

  // Stage: keep awake, pedal / keyboard.
  useEffect(() => {
    if (!stage) return;
    keepAwake(true);
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement)?.closest('input,textarea,select')) return;
      const page = window.innerHeight * 0.75;
      if (['ArrowDown', 'PageDown', ' ', 'Enter'].includes(e.key)) {
        e.preventDefault();
        if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4 && setlist) nextSong(1);
        else window.scrollBy({ top: page, behavior: 'smooth' });
      } else if (['ArrowUp', 'PageUp'].includes(e.key)) {
        e.preventDefault();
        window.scrollBy({ top: -page, behavior: 'smooth' });
      } else if (e.key === 'ArrowRight') nextSong(1);
      else if (e.key === 'ArrowLeft') nextSong(-1);
      else if (e.key === 'Escape') exit();
      else if (e.key === 's') setScrolling((s) => !s);
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      keepAwake(false);
    };
  }); // eslint-disable-line react-hooks/exhaustive-deps

  const nextSong = (d: number) => {
    if (!setlist) return;
    const i = setlist.index + d;
    if (i < 0 || i >= setlist.ids.length) return;
    window.scrollTo(0, 0);
    go(`/stage/${setlist.id}/${i}`);
  };
  const exit = () => {
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => {});
    go(setlist ? `/setlist/${setlist.id}` : `/song/${song.id}`);
  };
  const enterStage = () => {
    void document.documentElement.requestFullscreen?.().catch(() => {});
    go(`/stage/song/${song.id}`);
  };

  const exportCp = () => {
    const blob = new Blob([song.chordpro], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${song.title || 'song'}${song.artist ? ' - ' + song.artist : ''}.cho`.replace(/[\\/:*?"<>|]/g, '_');
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };

  const fontScale = song.font * (stage ? 1.25 : 1);

  return (
    <div className={`song${stage ? ' stage' : ''}`} style={{ ['--fs' as string]: String(fontScale) }}>
      <header className="song-head">
        {!stage && (
          <button className="link-btn" onClick={() => history.back()}>
            ← {t('library')}
          </button>
        )}
        <h1>{parsed.title || t('untitled')}</h1>
        {parsed.artist && <p className="song-artist">{parsed.artist}</p>}
        <p className="song-meta">
          {soundingKey && (
            <span>
              {t('key')}: <b>{formatKey(soundingKey, st.notation)}</b>
              {song.transpose !== 0 && originalKey && (
                <em>
                  {' '}
                  ({t('original')} {formatKey({ ...originalKey, tonic: (originalKey.tonic + sheetCapo) % 12 }, st.notation)})
                </em>
              )}
            </span>
          )}
          {capo > 0 && (
            <span className="capo-badge">
              {t('capo')} {capo}
            </span>
          )}
          {parsed.tempo && <span>♩ {parsed.tempo}</span>}
          {chords.length > 0 && <LevelBadge level={difficulty.level} title={difficulty.hard.length ? `${t('hardChords')}: ${difficulty.hard.join(' ')}` : undefined} />}
        </p>
      </header>

      <div className={`toolbar${stage ? ' mini' : ''}`} role="toolbar">
        <div className="tool-group" aria-label={t('transpose')}>
          <button onClick={() => patchSong(song.id, { transpose: song.transpose - 1 })} aria-label="−1">
            −
          </button>
          <span className="tool-value">
            {song.transpose > 0 ? `+${song.transpose}` : song.transpose}
          </span>
          <button onClick={() => patchSong(song.id, { transpose: song.transpose + 1 })} aria-label="+1">
            +
          </button>
        </div>
        <label className="tool-group">
          <span className="tool-label">{t('capo')}</span>
          <select value={capo} onChange={(e) => patchSong(song.id, { capo: Number(e.target.value) })}>
            {Array.from({ length: 10 }, (_, i) => (
              <option key={i} value={i}>
                {i === 0 ? '—' : i}
                {capoTips[0]?.capo === i ? ' ★' : ''}
              </option>
            ))}
          </select>
        </label>
        <button className={`chip${simplify ? ' on' : ''}`} onClick={() => setSimplify((s) => !s)}>
          {t('simplify')}
        </button>
        <button className="chip" onClick={() => setSettings({ notation: st.notation === 'it' ? 'en' : 'it' })} title={t('notation')}>
          {st.notation === 'it' ? 'Do Re Mi' : 'C D E'}
        </button>
        <div className="tool-group">
          <button onClick={() => patchSong(song.id, { font: Math.max(0.7, +(song.font - 0.1).toFixed(2)) })} aria-label="A−">
            A−
          </button>
          <button onClick={() => patchSong(song.id, { font: Math.min(2.2, +(song.font + 0.1).toFixed(2)) })} aria-label="A+">
            A+
          </button>
        </div>
        <div className="tool-group">
          <button className={scrolling ? 'on' : ''} onClick={() => setScrolling((s) => !s)} aria-label={t('autoscroll')}>
            {scrolling ? '❚❚' : '▶'}
          </button>
          <input type="range" min={0} max={10} step={0.5} value={song.scroll} onChange={(e) => patchSong(song.id, { scroll: Number(e.target.value) })} aria-label={t('speed')} />
        </div>
        <button className={`chip${listen ? ' on' : ''}`} onClick={() => setListen((l) => !l)}>
          ▶ {t('listenSong')}
        </button>
        {stage ? (
          <>
            {setlist && (
              <div className="tool-group">
                <button disabled={setlist.index === 0} onClick={() => nextSong(-1)}>
                  ‹
                </button>
                <span className="tool-value">
                  {setlist.index + 1}/{setlist.ids.length}
                </span>
                <button disabled={setlist.index >= setlist.ids.length - 1} onClick={() => nextSong(1)}>
                  ›
                </button>
              </div>
            )}
            <button className="chip" onClick={exit}>
              {t('exitStage')}
            </button>
          </>
        ) : (
          <>
            <button className="chip primary" onClick={enterStage}>
              {t('stage')}
            </button>
            <button className="chip" onClick={() => go(`/edit/${song.id}`)}>
              {t('edit')}
            </button>
            <div className="menu-wrap">
              <button className="chip" onClick={() => setMenu((m) => !m)} aria-expanded={menu}>
                ⋯
              </button>
              {menu && (
                <div className="menu" onClick={() => setMenu(false)}>
                  <button onClick={toggleFavorite}>{song.favorite ? '★' : '☆'} {t('favorites')}</button>
                  {lib.setlists.map((l) => (
                    <button key={l.id} onClick={() => void saveSetlists(lib.setlists.map((x) => (x.id === l.id && !x.songs.includes(song.id) ? { ...x, songs: [...x.songs, song.id], updated: Date.now() } : x)))}>
                      + {l.name}
                    </button>
                  ))}
                  <button onClick={exportCp}>{t('export')}</button>
                  <button onClick={() => window.print()}>{t('print')}</button>
                  <button
                    className="danger"
                    onClick={() => {
                      if (confirm(t('deleteConfirm'))) void deleteSong(song.id).then(() => go('/'));
                    }}
                  >
                    {t('delete')}
                  </button>
                </div>
              )}
            </div>
          </>
        )}
      </div>

      {listen && <Player song={song} onClose={() => setListen(false)} />}

      {!stage && capoTips[0] && capoTips[0].capo !== capo && capoTips[0].score + 0.5 < (capoTips.find((c) => c.capo === capo)?.score ?? 99) && (
        <button className="capo-tip" onClick={() => patchSong(song.id, { capo: capoTips[0].capo })}>
          💡 {t('capoSuggest')}: <b>{capoTips[0].capo === 0 ? '—' : capoTips[0].capo}</b>
          <span className="muted"> · {chordsPreview(chords, sheetCapo + song.transpose - capoTips[0].capo, st.notation)}</span>
        </button>
      )}

      {unique.length > 0 && (
        <section className="strip" aria-label={t('chordsInSong')}>
          <div className="strip-head">
            <span className="tool-label">{t('chordsInSong')}</span>
            <div className="seg">
              {(['guitar', 'ukulele', 'piano'] as const).map((i) => (
                <button key={i} className={st.instrument === i ? 'on' : ''} onClick={() => setSettings({ instrument: i })}>
                  {t(i)}
                </button>
              ))}
            </div>
          </div>
          <div className="strip-cards">
            {unique.map(([sym, label]) => (
              <ChordCard key={sym} symbol={sym} display={label} onClick={() => setZoom(sym)} />
            ))}
          </div>
        </section>
      )}

      <article className="sheet">
        {parsed.sections.map((s, i) => (
          <section key={i} className={`sec sec-${s.kind}`}>
            {s.label && <h3 className="sec-label">{s.label}</h3>}
            {s.lines.map((l, j) => (
              <RenderLine key={j} line={l} opts={opts} onChord={(c) => setZoom(displayChord(c, { ...opts, notation: 'en', flats: false }))} />
            ))}
          </section>
        ))}
      </article>

      {stage && <p className="stage-hint">{t('pedalHint')}</p>}

      {zoom && (
        <div className="modal" onClick={() => setZoom(null)} role="dialog">
          <div className="modal-card" onClick={(e) => e.stopPropagation()}>
            <ChordCard symbol={zoom} display={formatChord(parseChord(zoom)!, st.notation, opts.flats)} big />
            <button className="btn" onClick={() => setZoom(null)}>
              {t('close')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function chordsPreview(chords: ReturnType<typeof songChords>, shift: number, n: 'en' | 'it') {
  const seen = new Set<string>();
  for (const c of chords) {
    seen.add(formatChord(transposeChord({ ...c, bass: undefined }, shift), n));
    if (seen.size >= 5) break;
  }
  return [...seen].join(' ');
}

function RenderLine({ line, opts, onChord }: { line: Line; opts: RenderOptions; onChord: (sym: string) => void }) {
  if (line.type === 'empty') return <div className="ln-empty" />;
  if (line.type === 'comment') return <p className="ln-comment">{line.text}</p>;
  if (line.type === 'tab') return <pre className="ln-tab">{line.text}</pre>;
  if (line.type === 'chords')
    return (
      <p className="ln ln-chords">
        {line.chords.map((c, i) => (
          <button key={i} className="ch" onClick={() => onChord(c)}>
            {displayChord(c, opts)}
          </button>
        ))}
      </p>
    );
  const hasChords = line.parts.some((p) => p.chord);
  if (!hasChords) return <p className="ln">{line.parts.map((p) => p.text).join('')}</p>;
  // Word by word: each word carries the chord above it (or an empty slot),
  // so lines wrap anywhere and chords always stay on top of their syllable.
  const units: { chord?: string; text: string; join: boolean }[] = [];
  for (const p of line.parts) {
    const words = p.text.match(/\S+\s*|\s+/g) ?? [''];
    words.forEach((w, i) => units.push({ chord: i === 0 ? p.chord : undefined, text: w, join: i === 0 && !!units.length && !/\s$/.test(units[units.length - 1].text) }));
  }
  // Pieces of the same word stay together.
  const groups: (typeof units)[] = [];
  for (const u of units) {
    if (u.join && groups.length) groups[groups.length - 1].push(u);
    else groups.push([u]);
  }
  return (
    <p className="ln with-chords">
      {groups.map((g, i) => (
        <span key={i} className="wg">
          {g.map((u, j) => (
            <span key={j} className="u">
              {u.chord ? (
                <button className="ch" onClick={() => onChord(u.chord!)}>
                  {displayChord(u.chord, opts)}
                </button>
              ) : (
                <span className="ch-empty" />
              )}
              <span className="w">{u.text || '\u00a0'}</span>
            </span>
          ))}
        </span>
      ))}
    </p>
  );
}

/** Read-only rendering of ChordPro (import preview, editor). */
export function Sheet({ chordpro }: { chordpro: string }) {
  const st = useSettings();
  const parsed = useMemo(() => parseChordPro(chordpro), [chordpro]);
  const opts: RenderOptions = { transpose: 0, notation: st.notation, flats: false, simplify: false };
  return (
    <article className="sheet preview">
      <h2>{parsed.title}</h2>
      {parsed.artist && <p className="song-artist">{parsed.artist}</p>}
      {parsed.sections.map((s, i) => (
        <section key={i} className={`sec sec-${s.kind}`}>
          {s.label && <h3 className="sec-label">{s.label}</h3>}
          {s.lines.map((l, j) => (
            <RenderLine key={j} line={l} opts={opts} onChord={() => {}} />
          ))}
        </section>
      ))}
    </article>
  );
}
