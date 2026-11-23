import { useEffect, useMemo, useState } from 'react';
import { importText } from '../lib/chordpro';
import { segmentsToChordPro, type Segment } from '../audio/dsp';
import { useT } from '../i18n';
import { go, routeQuery } from '../router';
import { saveSong } from '../store';
import { Sheet } from './SongView';

export function Import() {
  const t = useT();
  const q = routeQuery();
  const sharedUrl = q.get('url') || (q.get('text')?.match(/https?:\/\/\S+/)?.[0] ?? '');
  const sharedText = (q.get('text') ?? '').replace(sharedUrl, '').trim();
  const [raw, setRaw] = useState(sharedText.split('\n').length > 2 ? sharedText : '');
  const [title, setTitle] = useState(q.get('title') ?? '');
  const [progress, setProgress] = useState<number | null>(null);
  const [err, setErr] = useState('');

  const chordpro = useMemo(() => (raw.trim() ? importText(raw, { url: sharedUrl || undefined }) : ''), [raw, sharedUrl]);
  const finalCp = useMemo(() => {
    if (!chordpro) return '';
    // A title typed by the user wins over the guessed one.
    return title.trim() && /\{title: (Senza titolo|Untitled)\}/.test(chordpro) ? chordpro.replace(/\{title: [^}]*\}/, `{title: ${title.trim()}}`) : chordpro;
  }, [chordpro, title]);

  useEffect(() => {
    if (q.get('clip') === '1') void pasteClipboard();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function pasteClipboard() {
    try {
      const txt = await navigator.clipboard.readText();
      if (txt) setRaw(txt);
    } catch {
      /* not allowed: user pastes by hand */
    }
  }

  async function openFile(f: File) {
    setErr('');
    if (f.type.startsWith('audio/') || f.type.startsWith('video/') || /\.(mp3|wav|m4a|aac|ogg|oga|flac|opus|webm|mp4)$/i.test(f.name)) {
      return analyseAudio(f);
    }
    setRaw(await f.text());
    if (!title) setTitle(f.name.replace(/\.[^.]+$/, ''));
  }

  async function analyseAudio(f: File) {
    setProgress(0);
    try {
      const ctx = new AudioContext();
      const audio = await ctx.decodeAudioData(await f.arrayBuffer());
      void ctx.close();
      // Mono mix.
      const mono = new Float32Array(audio.length);
      for (let c = 0; c < audio.numberOfChannels; c++) {
        const d = audio.getChannelData(c);
        for (let i = 0; i < d.length; i++) mono[i] += d[i] / audio.numberOfChannels;
      }
      const segs = await new Promise<Segment[]>((resolve, reject) => {
        const w = new Worker(new URL('../workers/recognise.worker.ts', import.meta.url), { type: 'module' });
        w.onmessage = (e) => {
          if (e.data.progress !== undefined) setProgress(e.data.progress);
          if (e.data.segments) {
            resolve(e.data.segments);
            w.terminate();
          }
        };
        w.onerror = (e) => reject(e);
        w.postMessage({ samples: mono, sampleRate: audio.sampleRate }, [mono.buffer]);
      });
      const name = title || f.name.replace(/\.[^.]+$/, '');
      setRaw(segmentsToChordPro(segs, name));
      setTitle(name);
    } catch (e) {
      setErr(String((e as Error).message ?? e));
    }
    setProgress(null);
  }

  const save = async () => {
    const s = await saveSong({ chordpro: finalCp });
    go(`/song/${s.id}`, true);
  };

  return (
    <div className="page import">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('importTitle')}</h1>
      <p className="lead">{t('importLead')}</p>
      {sharedUrl && !raw && (
        <p className="notice">
          {t('sharedUrl')}{' '}
          <a href={sharedUrl} target="_blank" rel="noopener noreferrer">
            {t('openPage')} ↗
          </a>
        </p>
      )}
      <div className="import-actions">
        <button className="btn" onClick={() => void pasteClipboard()}>
          📋 {t('pasteFromClipboard')}
        </button>
        <label className="btn">
          📄 {t('openFile')}
          <input type="file" hidden accept=".cho,.chopro,.chordpro,.crd,.pro,.txt,text/plain,audio/*" onChange={(e) => e.target.files?.[0] && void openFile(e.target.files[0])} />
        </label>
        <label className="btn" title={t('fromAudioHint')}>
          🎧 {t('fromAudio')}
          <input type="file" hidden accept="audio/*,video/*" onChange={(e) => e.target.files?.[0] && void analyseAudio(e.target.files[0])} />
        </label>
      </div>
      {progress !== null && (
        <div className="progress" role="progressbar" aria-valuenow={Math.round(progress * 100)}>
          <div style={{ width: `${progress * 100}%` }} />
          <span>
            {t('analysing')} {Math.round(progress * 100)}%
          </span>
        </div>
      )}
      {err && <p className="error">{err}</p>}
      <div className="import-grid">
        <div>
          <input className="field" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={t('title')} />
          <textarea className="field code" value={raw} onChange={(e) => setRaw(e.target.value)} placeholder={t('pasteHere')} spellCheck={false} rows={18} />
        </div>
        <div>
          {finalCp ? <Sheet chordpro={finalCp} /> : <p className="muted">{t('preview')}</p>}
        </div>
      </div>
      <div className="import-foot">
        <button className="btn primary big" disabled={!finalCp} onClick={() => void save()}>
          {t('save')}
        </button>
        <p className="muted small">{t('sourceNote')}</p>
      </div>
    </div>
  );
}
