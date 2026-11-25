import { useEffect, useRef, useState } from 'react';
import { resolveLink, transcribe } from '../api';
import { signal } from '../account';
import { LiveChords, type Segment } from '../audio/dsp';
import { getDsp, keepAwake } from '../audio/mic';
import { canRecordTab, decodeFile, recordMic, recordTab, type Recorder } from '../audio/recorder';
import { analysisChordPro, clipRanges, estimateTempo, howToPlay, tidySegments, wav16k, STRUMS, type HowToPlay, type Word } from '../lib/analysis';
import { formatChord, formatKey, parseChord } from '../lib/music';
import { embedUrl, videoRef, type VideoRef } from '../lib/sources';
import { useT } from '../i18n';
import { go, routeQuery } from '../router';
import { useSettings } from '../settings';
import { saveSong } from '../store';
import { ChordCard } from './Diagram';
import { LevelBadge } from './LevelBadge';
import { Sheet } from './SongView';

interface Result {
  segs: Segment[];
  how: HowToPlay;
  words: Word[] | null;
  lyricsFailed: boolean;
}

const mmss = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

/** Whole-recording chord recognition off the main thread. */
function recogniseInWorker(samples: Float32Array, sampleRate: number, onProgress: (p: number) => void): Promise<Segment[]> {
  return new Promise((resolve, reject) => {
    const w = new Worker(new URL('../workers/recognise.worker.ts', import.meta.url), { type: 'module' });
    w.onmessage = (e) => {
      if (e.data.progress !== undefined) onProgress(e.data.progress);
      if (e.data.segments) {
        resolve(e.data.segments);
        w.terminate();
      }
    };
    w.onerror = (e) => reject(e);
    // A copy: the worker takes ownership of the buffer.
    const copy = samples.slice();
    w.postMessage({ samples: copy, sampleRate }, [copy.buffer]);
  });
}

export function Analyze() {
  const t = useT();
  const st = useSettings();
  const q = routeQuery();
  const [input, setInput] = useState(q.get('url') ?? '');
  const [url, setUrl] = useState(q.get('url') ?? '');
  const [video, setVideo] = useState<VideoRef | null>(url ? videoRef(url) : null);
  const [title, setTitle] = useState('');
  const [artist, setArtist] = useState('');
  const [lyrics, setLyrics] = useState(true);
  const [rec, setRec] = useState<Recorder | null>(null);
  const [live, setLive] = useState<{ chord: string | null; heard: string[]; secs: number; level: number; loudest: number }>({ chord: null, heard: [], secs: 0, level: 0, loudest: 0 });
  const [stage, setStage] = useState<{ label: string; p: number } | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [err, setErr] = useState('');
  const recRef = useRef<Recorder | null>(null);
  const label = (s: string) => (parseChord(s) ? formatChord(parseChord(s)!, st.notation) : s);

  useEffect(() => {
    if (!url) return;
    setErr('');
    const v = videoRef(url);
    if (v) setVideo(v);
    resolveLink(url)
      .then((li) => {
        if (!v) {
          if (li.youtubeId) setVideo({ platform: 'youtube', id: li.youtubeId });
          else if (li.videoId && (li.kind === 'tiktok' || li.kind === 'instagram')) setVideo({ platform: li.kind, id: li.videoId });
          else setErr(t('linkUnsupported'));
        }
        setTitle((x) => x || li.title || '');
        setArtist((x) => x || li.artist || '');
      })
      .catch(() => !v && setErr(t('linkUnsupported')));
  }, [url]); // eslint-disable-line react-hooks/exhaustive-deps

  async function analyse(samples: Float32Array, rate: number) {
    if (samples.length < rate * 3) return setErr(t('analyzeNoChords'));
    setResult(null);
    setStage({ label: t('analyzeWorking'), p: 0 });
    try {
      const tempo = estimateTempo(samples, rate);
      const raw = await recogniseInWorker(samples, rate, (p) => setStage({ label: t('analyzeWorking'), p }));
      const segs = tidySegments(raw, tempo.bpm);
      const how = howToPlay(segs, tempo.bpm);
      let words: Word[] | null = null;
      let lyricsFailed = false;
      if (lyrics && segs.some((s) => s.chord)) {
        const clips = clipRanges(samples, rate);
        const all: Word[] = [];
        try {
          for (let i = 0; i < clips.length; i++) {
            setStage({ label: t('transcribing'), p: i / clips.length });
            const [a, b] = clips[i];
            all.push(...(await transcribe(wav16k(samples.subarray(a, b), rate), a / rate)));
          }
        } catch {
          lyricsFailed = true;
        }
        words = all.length ? all : null;
        if (!all.length) lyricsFailed = true;
      }
      setResult({ segs, how, words, lyricsFailed });
      if (!segs.some((s) => s.chord)) setErr(t('analyzeNoChords'));
    } catch (e) {
      setErr(String((e as Error).message ?? e));
    }
    setStage(null);
  }

  const stop = () => {
    const r = recRef.current;
    if (!r) return;
    recRef.current = null;
    setRec(null);
    r.stop();
    void analyse(r.samples(), r.rate);
  };

  async function start(kind: 'tab' | 'mic') {
    setErr('');
    setResult(null);
    try {
      const r = kind === 'tab' ? await recordTab() : await recordMic();
      r.onEnded = stop;
      recRef.current = r;
      setRec(r);
    } catch (e) {
      setErr(kind === 'tab' ? t('analyzeCaptureFailed') : t('micDenied'));
      void e;
    }
  }

  // Live chord while listening.
  useEffect(() => {
    if (!rec) return;
    keepAwake(true);
    let alive = true;
    let timer = 0;
    const lc = new LiveChords();
    void getDsp().then((dsp) => {
      const loop = () => {
        if (!alive) return;
        const { chroma, rms } = dsp.chroma(rec.tail(4096), rec.rate);
        const name = lc.push(chroma, rms).chord?.name ?? null;
        setLive((l) => ({
          chord: name,
          heard: name && l.heard[l.heard.length - 1] !== name ? [...l.heard.slice(-23), name] : l.heard,
          secs: rec.seconds(),
          level: rec.level(),
          loudest: Math.max(l.loudest, rec.level()),
        }));
        if (rec.full()) return stop();
        timer = window.setTimeout(loop, 120);
      };
      loop();
    });
    return () => {
      alive = false;
      clearTimeout(timer);
      keepAwake(false);
    };
  }, [rec]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => recRef.current?.stop(), []);

  const openFile = async (f: File) => {
    setErr('');
    setStage({ label: t('analyzeWorking'), p: 0 });
    try {
      const { samples, rate } = await decodeFile(f);
      if (!title) setTitle(f.name.replace(/\.[^.]+$/, ''));
      await analyse(samples, rate);
    } catch (e) {
      setStage(null);
      setErr(String((e as Error).message ?? e));
    }
  };

  const cp = result
    ? analysisChordPro({ title: title || t('untitled'), artist, segs: result.segs, words: result.words ?? undefined, how: result.how, strumLabel: t('strumming'), labels: { intro: t('intro'), solo: t('instrumental'), outro: t('outro') } })
    : '';

  const save = async () => {
    const s = await saveSong({ chordpro: cp, source: url || undefined, youtube: video?.platform === 'youtube' ? video.id : undefined });
    signal('import', { title: s.title, artist: s.artist });
    go(`/song/${s.id}`, true);
  };

  return (
    <div className="page analyze">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('analyze')}</h1>
      <p className="lead">{t('analyzeLead')}</p>

      {!video && (
        <form
          className="paste-row"
          onSubmit={(e) => {
            e.preventDefault();
            setUrl(input.trim().match(/https?:\/\/\S+/)?.[0] ?? input.trim());
          }}
        >
          <input value={input} onChange={(e) => setInput(e.target.value)} placeholder={t('analyzeLink')} inputMode="url" />
          <button className="btn" disabled={!input.trim()}>
            OK
          </button>
        </form>
      )}

      {video && (
        <div className={`analyze-video ${video.platform}`}>
          <iframe src={embedUrl(video)} title={title || 'video'} allow="autoplay; encrypted-media; picture-in-picture; fullscreen" referrerPolicy="strict-origin-when-cross-origin" allowFullScreen />
        </div>
      )}

      {!rec && !stage && !result && (
        <div className="analyze-start">
          {video && canRecordTab() && (
            <>
              <button className="btn primary big" onClick={() => void start('tab')}>
                ✨ {t('analyzeTab')}
              </button>
              <p className="muted small">{t('analyzeTabHint')}</p>
            </>
          )}
          <div className="import-actions">
            <button className="btn" onClick={() => void start('mic')}>
              🎤 {t('analyzeMic')}
            </button>
            <label className="btn">
              📄 {t('analyzeFile')}
              <input type="file" hidden accept="audio/*,video/*" onChange={(e) => e.target.files?.[0] && void openFile(e.target.files[0])} />
            </label>
          </div>
          <label className="card consent lyrics-toggle">
            <input type="checkbox" checked={lyrics} onChange={(e) => setLyrics(e.target.checked)} />
            <span>
              <b>{t('lyricsSync')}</b>
              <br />
              <span className="muted small">{t('lyricsNote')}</span>
            </span>
          </label>
          <p className="muted small">{t('analyzeMax')}</p>
        </div>
      )}

      {rec && (
        <section className="card analyze-live">
          <div className="analyze-live-head">
            <span className="rec-dot" />
            <span className="tool-label">{t('analyzeListening')}</span>
            <span className="muted small mono">{mmss(live.secs)} / 6:00</span>
          </div>
          <div className="meter">
            <div style={{ width: `${Math.min(100, live.level * 400)}%` }} />
          </div>
          <div className="listen-chord">{live.chord ? label(live.chord) : '…'}</div>
          {live.heard.length > 0 && <p className="seq">{live.heard.map(label).join('  ')}</p>}
          {live.secs > 6 && live.loudest < 0.01 && <p className="error">{t('analyzeSilent')}</p>}
          <button className="btn primary big" onClick={stop}>
            ■ {t('analyzeStop')}
          </button>
        </section>
      )}

      {stage && (
        <div className="progress" role="progressbar" aria-valuenow={Math.round(stage.p * 100)}>
          <div style={{ width: `${stage.p * 100}%` }} />
          <span>
            {stage.label} {Math.round(stage.p * 100)}%
          </span>
        </div>
      )}
      {err && <p className="error">{err}</p>}

      {result && result.segs.some((s) => s.chord) && (
        <>
          <HowToPlayCard how={result.how} />
          {result.lyricsFailed && <p className="muted small">{t('lyricsFailed')}</p>}
          <div className="import-grid">
            <div>
              <input className="field" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={t('title')} />
              <input className="field" value={artist} onChange={(e) => setArtist(e.target.value)} placeholder={t('artist')} />
            </div>
            <Sheet chordpro={cp} />
          </div>
          <div className="import-foot">
            <button className="btn primary big" onClick={() => void save()}>
              {t('saveToLibrary')}
            </button>
            <button className="btn" onClick={() => setResult(null)}>
              ↻ {t('analyzeAgain')}
            </button>
            {title && (
              <button className="btn" onClick={() => go(`/find?q=${encodeURIComponent(`${title} ${artist}`.trim())}${video?.platform === 'youtube' ? `&yt=${video.id}` : ''}`)}>
                {t('officialChords')}
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
}

export function HowToPlayCard({ how }: { how: HowToPlay }) {
  const t = useT();
  const st = useSettings();
  return (
    <section className="card how">
      <div className="how-head">
        <h2>{t('howToPlay')}</h2>
        <LevelBadge level={how.level} />
      </div>
      <div className="facts">
        <div className="fact">
          <span className="tool-label">{t('key')}</span>
          <b>{how.key ? formatKey(how.key, st.notation) : '—'}</b>
        </div>
        <div className={`fact${how.capo ? ' accent' : ''}`}>
          <span className="tool-label">{t('capo')}</span>
          <b>{how.capo || '—'}</b>
        </div>
        <div className="fact">
          <span className="tool-label">{t('tempoLabel')}</span>
          <b>
            {how.bpm || '—'} <small>BPM</small>
          </b>
        </div>
      </div>
      <p>
        <b>{how.capo ? `${t('capoAt')} ${how.capo}` : t('noCapo')}</b>
      </p>
      <span className="tool-label">{t('shapesLabel')}</span>
      <div className="strip-cards">
        {how.shapes.map((s) => (
          <ChordCard key={s} symbol={s} display={label(s, st.notation)} />
        ))}
      </div>
      <span className="tool-label">{t('strumming')}</span>
      <p className="strum">{STRUMS[how.strum]}</p>
      <p className="muted small">{t('strumHint')}</p>
      {how.hard.length > 0 && (
        <>
          <span className="tool-label">{t('practice')}</span>
          <div className="chips">
            {how.hard.map((h) => (
              <span key={h} className="chip tag mono">
                {h}
              </span>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

const label = (s: string, n: 'it' | 'en') => (parseChord(s) ? formatChord(parseChord(s)!, n) : s);
