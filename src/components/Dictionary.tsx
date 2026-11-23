import { useMemo, useState } from 'react';
import { chordNotes, formatChord, noteName, type Chord } from '../lib/music';
import { TUNINGS, voicings } from '../lib/voicings';
import { useT } from '../i18n';
import { setSettings, useSettings } from '../settings';
import { FretDiagram, PianoDiagram } from './Diagram';

const QUALITIES = ['', 'm', '7', 'm7', 'maj7', 'sus2', 'sus4', 'add9', '6', 'm6', '9', 'm9', 'dim', 'dim7', 'm7b5', 'aug', '5', '7sus4', '13'];

export function Dictionary() {
  const t = useT();
  const st = useSettings();
  const [root, setRoot] = useState(0);
  const [q, setQ] = useState('');
  const chord: Chord = { root, suffix: q };
  const tuning = st.instrument === 'ukulele' ? TUNINGS.ukulele : TUNINGS[st.tuning] ?? TUNINGS.standard;
  const list = useMemo(() => (st.instrument === 'piano' ? [] : voicings(chord, tuning, 8)), [root, q, st.instrument, tuning]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="page dictionary">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('dictionary')}</h1>
      <div className="seg wrap" aria-label={t('root')}>
        {Array.from({ length: 12 }, (_, i) => (
          <button key={i} className={i === root ? 'on' : ''} onClick={() => setRoot(i)}>
            {noteName(i, st.notation, [1, 3, 8, 10].includes(i))}
          </button>
        ))}
      </div>
      <div className="seg wrap" aria-label={t('type')}>
        {QUALITIES.map((x) => (
          <button key={x} className={x === q ? 'on' : ''} onClick={() => setQ(x)}>
            {x || 'maj'}
          </button>
        ))}
      </div>
      <div className="seg">
        {(['guitar', 'ukulele', 'piano'] as const).map((i) => (
          <button key={i} className={st.instrument === i ? 'on' : ''} onClick={() => setSettings({ instrument: i })}>
            {t(i)}
          </button>
        ))}
      </div>
      <h2 className="dict-name">{formatChord(chord, st.notation, [1, 3, 8, 10].includes(root))}</h2>
      <p className="muted">
        {t('notes')}: {chordNotes(chord).map((n) => noteName(n, st.notation, [1, 3, 8, 10].includes(root))).join(' – ')}
      </p>
      {st.instrument === 'piano' ? (
        <PianoDiagram chord={chord} size={1.6} />
      ) : (
        <div className="dict-grid">
          {list.map((v, i) => (
            <figure key={i} className="chord-card">
              <FretDiagram v={v} strings={tuning.strings.length} lefty={st.lefty} size={1.3} />
              <figcaption className="muted small">{v.frets.map((f) => (f < 0 ? 'x' : f)).join(' ')}</figcaption>
            </figure>
          ))}
        </div>
      )}
    </div>
  );
}
