import { useEffect, useState } from 'react';
import { getDsp, keepAwake, openMic, type Mic } from '../audio/mic';
import { LiveChords } from '../audio/dsp';
import { formatChord, parseChord } from '../lib/music';
import { useT } from '../i18n';
import { go } from '../router';
import { saveSong } from '../store';
import { useSettings } from '../settings';
import { ChordCard } from './Diagram';

export function Listen() {
  const t = useT();
  const st = useSettings();
  const [mic, setMic] = useState<Mic | null>(null);
  const [err, setErr] = useState('');
  const [chord, setChord] = useState<string | null>(null);
  const [ranking, setRanking] = useState<{ name: string; score: number }[]>([]);
  const [seq, setSeq] = useState<string[]>([]);

  useEffect(() => {
    if (!mic) return;
    keepAwake(true);
    let alive = true;
    let timer = 0;
    const live = new LiveChords();
    void getDsp().then((dsp) => {
      const sr = mic.ctx.sampleRate;
      const n = sr > 30000 ? 8192 : 4096;
      const loop = () => {
        if (!alive) return;
        const { chroma, rms } = dsp.chroma(mic.read(n), sr);
        const r = live.push(chroma, rms);
        const name = r.chord?.name ?? null;
        setChord(name);
        setRanking(r.ranking);
        if (name) setSeq((s) => (s[s.length - 1] === name ? s : [...s.slice(-63), name]));
        timer = window.setTimeout(loop, 100);
      };
      loop();
    });
    return () => {
      alive = false;
      clearTimeout(timer);
      keepAwake(false);
    };
  }, [mic]);
  useEffect(() => () => mic?.stop(), [mic]);

  const start = async () => {
    setErr('');
    try {
      setMic(await openMic(8192));
    } catch {
      setErr(t('micDenied'));
    }
  };
  const label = (s: string) => formatChord(parseChord(s)!, st.notation);

  return (
    <div className="page listen">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('listen')}</h1>
      <p className="lead">{t('listenHint')}</p>
      {!mic ? (
        <button className="btn primary big" onClick={() => void start()}>
          🎤 {t('micStart')}
        </button>
      ) : (
        <div className="listen-now">
          <div className="listen-chord">{chord ? label(chord) : '…'}</div>
          <div className="listen-alt">
            {ranking.slice(1, 4).map((r) => (
              <span key={r.name}>{label(r.name)}</span>
            ))}
          </div>
          {chord && <ChordCard symbol={chord} display={label(chord)} big />}
          <p className="muted small">{t('listening')}</p>
        </div>
      )}
      {err && <p className="error">{err}</p>}
      {seq.length > 0 && (
        <section>
          <h2>{t('history')}</h2>
          <p className="seq">{seq.map(label).join('  ')}</p>
          <div className="import-actions">
            <button
              className="btn"
              onClick={() => {
                const lines: string[] = [];
                for (let i = 0; i < seq.length; i += 4) lines.push(seq.slice(i, i + 4).map((c) => `[${c}]`).join(' '));
                void saveSong({ chordpro: `{title: ${new Date().toLocaleString()}}\n\n${lines.join('\n')}\n` }).then((s) => go(`/song/${s.id}`));
              }}
            >
              {t('saveAsSong')}
            </button>
            <button className="btn" onClick={() => setSeq([])}>
              ×
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
