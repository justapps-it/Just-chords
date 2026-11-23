import { useEffect, useRef, useState } from 'react';
import { getDsp, openMic, type Mic } from '../audio/mic';
import { noteFromHz } from '../audio/dsp';
import { noteName } from '../lib/music';
import { TUNINGS } from '../lib/voicings';
import { useT } from '../i18n';
import { setSettings, useSettings } from '../settings';

const midiName = (m: number, n: 'en' | 'it') => noteName(m % 12, n) + (Math.floor(m / 12) - 1);

export function Tuner() {
  const t = useT();
  const st = useSettings();
  const [mic, setMic] = useState<Mic | null>(null);
  const [err, setErr] = useState('');
  const [reading, setReading] = useState<{ midi: number; cents: number; hz: number } | null>(null);
  const [tuningKey, setTuningKey] = useState(st.instrument === 'ukulele' ? 'ukulele' : st.tuning);
  const hist = useRef<number[]>([]);
  const tuning = tuningKey === 'chromatic' ? null : TUNINGS[tuningKey];

  useEffect(() => {
    if (!mic) return;
    let alive = true;
    let timer = 0;
    void getDsp().then((dsp) => {
      const sr = mic.ctx.sampleRate;
      const n = sr > 30000 ? 4096 : 2048;
      const loop = () => {
        if (!alive) return;
        const frame = mic.read(n);
        let rms = 0;
        for (let i = 0; i < frame.length; i++) rms += frame[i] * frame[i];
        rms = Math.sqrt(rms / frame.length);
        const p = rms > 0.008 ? dsp.pitch(frame, sr) : { hz: -1, confidence: 0 };
        if (p.hz > 25 && p.hz < 2000 && p.confidence > 0.8) {
          hist.current = [...hist.current.slice(-4), p.hz];
          const sorted = [...hist.current].sort((a, b) => a - b);
          const hz = sorted[Math.floor(sorted.length / 2)];
          setReading({ ...noteFromHz(hz, st.a4), hz });
        }
        timer = window.setTimeout(loop, 50);
      };
      loop();
    });
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [mic, st.a4]);

  useEffect(() => () => mic?.stop(), [mic]);

  const start = async () => {
    setErr('');
    try {
      setMic(await openMic(8192));
    } catch {
      setErr(t('micDenied'));
    }
  };

  // Closest string of the chosen tuning.
  let target = reading?.midi ?? null;
  let cents = reading?.cents ?? 0;
  if (reading && tuning) {
    const exact = reading.midi + reading.cents / 100;
    target = tuning.strings.reduce((a, b) => (Math.abs(b - exact) < Math.abs(a - exact) ? b : a));
    cents = (exact - target) * 100;
  }
  const inTune = reading && Math.abs(cents) < 4;
  const angle = Math.max(-50, Math.min(50, cents)) * 0.9;

  return (
    <div className="page tuner">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('tuner')}</h1>
      <div className="tuner-controls">
        <label>
          <span className="tool-label">{t('tuning')}</span>
          <select value={tuningKey} onChange={(e) => setTuningKey(e.target.value)}>
            {Object.entries(TUNINGS).map(([k, v]) => (
              <option key={k} value={k}>
                {k === 'ukulele' ? `${t('ukulele')} (G C E A)` : v.name}
              </option>
            ))}
            <option value="chromatic">{t('chromatic')}</option>
          </select>
        </label>
        <label>
          <span className="tool-label">A4</span>
          <input type="number" min={415} max={466} value={st.a4} onChange={(e) => setSettings({ a4: Number(e.target.value) || 440 })} />
        </label>
      </div>
      <div className={`tuner-dial${inTune ? ' ok' : ''}`}>
        <svg viewBox="0 0 200 120" aria-hidden="true">
          {Array.from({ length: 21 }, (_, i) => {
            const a = ((i - 10) * 4.5 * Math.PI) / 180;
            const long = i % 5 === 0;
            return <line key={i} x1={100 + Math.sin(a) * 80} y1={105 - Math.cos(a) * 80} x2={100 + Math.sin(a) * (long ? 68 : 73)} y2={105 - Math.cos(a) * (long ? 68 : 73)} stroke="currentColor" strokeOpacity={long ? 0.7 : 0.35} strokeWidth={long ? 2 : 1} />;
          })}
          <line className="needle" x1={100} y1={105} x2={100} y2={30} style={{ transform: `rotate(${reading ? angle : 0}deg)`, transformOrigin: '100px 105px' }} />
          <circle cx={100} cy={105} r={5} className="needle-hub" />
        </svg>
        <div className="tuner-note">{target !== null ? midiName(target, st.notation) : '—'}</div>
        <div className="tuner-cents">
          {reading ? `${cents > 0 ? '+' : ''}${cents.toFixed(0)} cent · ${reading.hz.toFixed(1)} Hz` : t('tunerHint')}
        </div>
      </div>
      {tuning && (
        <div className="strings">
          {tuning.strings.map((m, i) => (
            <span key={i} className={target === m ? 'on' : ''}>
              {midiName(m, st.notation)}
            </span>
          ))}
        </div>
      )}
      {!mic && (
        <button className="btn primary big" onClick={() => void start()}>
          🎤 {t('micStart')}
        </button>
      )}
      {err && <p className="error">{err}</p>}
    </div>
  );
}
