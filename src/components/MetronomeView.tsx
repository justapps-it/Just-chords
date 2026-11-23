import { useEffect, useRef, useState } from 'react';
import { Metronome, tapTempo } from '../audio/metronome';
import { useT } from '../i18n';

export function MetronomeView({ initialBpm }: { initialBpm?: number }) {
  const t = useT();
  const [bpm, setBpm] = useState(initialBpm ?? 100);
  const [beats, setBeats] = useState(4);
  const [sub, setSub] = useState(1);
  const [running, setRunning] = useState(false);
  const [beat, setBeat] = useState(-1);
  const m = useRef<Metronome | null>(null);
  const taps = useRef<number[]>([]);

  m.current ??= new Metronome({ bpm, beats, sub, accent: true });
  useEffect(() => {
    m.current!.s = { bpm, beats, sub, accent: true };
  }, [bpm, beats, sub]);
  useEffect(() => () => m.current?.stop(), []);

  useEffect(() => {
    if (!running) return setBeat(-1);
    let raf = 0;
    const draw = () => {
      const now = m.current!.now();
      const past = m.current!.queue.filter((q) => q.time <= now && q.sub === 0);
      if (past.length) setBeat(past[past.length - 1].beat);
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [running]);

  const toggle = async () => {
    if (running) {
      m.current!.stop();
      setRunning(false);
    } else {
      await m.current!.start();
      setRunning(true);
    }
  };

  const tap = () => {
    taps.current = [...taps.current, performance.now()].slice(-8);
    const v = tapTempo(taps.current);
    if (v && v >= 30 && v <= 300) setBpm(v);
  };

  return (
    <div className="page metronome">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('metronome')}</h1>
      <div className="beats">
        {Array.from({ length: beats }, (_, i) => (
          <span key={i} className={`beat${i === beat ? ' on' : ''}${i === 0 ? ' first' : ''}`} />
        ))}
      </div>
      <div className="bpm">
        <button onClick={() => setBpm((b) => Math.max(30, b - 1))}>−</button>
        <div>
          <b>{bpm}</b>
          <span>{t('bpm')}</span>
        </div>
        <button onClick={() => setBpm((b) => Math.min(300, b + 1))}>+</button>
      </div>
      <input className="bpm-range" type="range" min={30} max={300} value={bpm} onChange={(e) => setBpm(Number(e.target.value))} aria-label={t('bpm')} />
      <div className="tuner-controls">
        <label>
          <span className="tool-label">{t('beats')}</span>
          <select value={beats} onChange={(e) => setBeats(Number(e.target.value))}>
            {[1, 2, 3, 4, 5, 6, 7, 9, 12].map((b) => (
              <option key={b}>{b}</option>
            ))}
          </select>
        </label>
        <label>
          <span className="tool-label">{t('sub')}</span>
          <select value={sub} onChange={(e) => setSub(Number(e.target.value))}>
            <option value={1}>♩</option>
            <option value={2}>♪♪</option>
            <option value={3}>♪♪♪ (3)</option>
            <option value={4}>♬♬</option>
          </select>
        </label>
      </div>
      <div className="import-actions center">
        <button className="btn primary big" onClick={() => void toggle()}>
          {running ? `■ ${t('stop')}` : `▶ ${t('start')}`}
        </button>
        <button className="btn big" onClick={tap}>
          {t('tap')}
        </button>
      </div>
    </div>
  );
}
