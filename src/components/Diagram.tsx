import { useMemo, useState } from 'react';
import { formatChord, noteName, parseChord, type Chord, type Notation } from '../lib/music';
import { pianoNotes, TUNINGS, voicings, type Instrument, type Voicing } from '../lib/voicings';
import { useSettings } from '../settings';

export function FretDiagram({ v, strings, lefty, size = 1 }: { v: Voicing; strings: number; lefty?: boolean; size?: number }) {
  const frets = 4;
  const w = 16 * (strings - 1);
  const h = 18 * frets;
  const x0 = 14;
  const y0 = 16;
  const col = (i: number) => x0 + (lefty ? strings - 1 - i : i) * 16;
  const shown = v.base;
  return (
    <svg className="fret" viewBox={`0 0 ${w + 28} ${h + 24}`} width={(w + 28) * size} height={(h + 24) * size} aria-hidden="true">
      {shown === 1 ? <rect x={x0} y={y0 - 3} width={w} height={3} fill="currentColor" /> : <text x={x0 - 4} y={y0 + 12} className="fret-base" textAnchor="end">{shown}</text>}
      {Array.from({ length: frets + 1 }, (_, i) => (
        <line key={`f${i}`} x1={x0} x2={x0 + w} y1={y0 + i * 18} y2={y0 + i * 18} stroke="currentColor" strokeOpacity={0.35} />
      ))}
      {Array.from({ length: strings }, (_, i) => (
        <line key={`s${i}`} x1={x0 + i * 16} x2={x0 + i * 16} y1={y0} y2={y0 + h} stroke="currentColor" strokeOpacity={0.6} />
      ))}
      {v.barre && (
        <rect
          x={Math.min(col(v.barre.from), col(v.barre.to)) - 6}
          y={y0 + (v.barre.fret - shown) * 18 + 3}
          width={Math.abs(col(v.barre.to) - col(v.barre.from)) + 12}
          height={12}
          rx={6}
          className="dot"
        />
      )}
      {v.frets.map((f, i) =>
        f < 0 ? (
          <text key={i} x={col(i)} y={y0 - 6} textAnchor="middle" className="fret-mark">×</text>
        ) : f === 0 ? (
          <circle key={i} cx={col(i)} cy={y0 - 9} r={4} fill="none" stroke="currentColor" strokeWidth={1.4} />
        ) : (
          <circle key={i} cx={col(i)} cy={y0 + (f - shown) * 18 + 9} r={6} className="dot" />
        ),
      )}
    </svg>
  );
}

export function PianoDiagram({ chord, size = 1 }: { chord: Chord; size?: number }) {
  const { notes, bass } = pianoNotes(chord);
  const on = new Set(notes);
  const low = 48;
  const whites: number[] = [];
  for (let m = low; m < low + 36; m++) if (![1, 3, 6, 8, 10].includes(m % 12)) whites.push(m);
  const W = 11;
  const blackX = (m: number) => whites.indexOf(m - 1) * W + W * 0.68;
  return (
    <svg className="piano" viewBox={`0 0 ${whites.length * W} 56`} width={whites.length * W * size} height={56 * size} aria-hidden="true">
      {whites.map((m, i) => (
        <rect key={m} x={i * W} y={0} width={W} height={56} className={on.has(m) ? 'key on' : m === bass ? 'key bass' : 'key'} />
      ))}
      {Array.from({ length: 36 }, (_, k) => low + k)
        .filter((m) => [1, 3, 6, 8, 10].includes(m % 12))
        .map((m) => (
          <rect key={m} x={blackX(m)} y={0} width={W * 0.64} height={34} className={on.has(m) ? 'bkey on' : m === bass ? 'bkey bass' : 'bkey'} />
        ))}
    </svg>
  );
}

/** Diagram card for a chord symbol, with the voicings to flip through. */
export function ChordCard({ symbol, display, instrument, onClick, big }: { symbol: string; display?: string; instrument?: Instrument; onClick?: () => void; big?: boolean }) {
  const st = useSettings();
  const inst = instrument ?? st.instrument;
  const chord = parseChord(symbol);
  const tuning = inst === 'ukulele' ? TUNINGS.ukulele : TUNINGS[st.tuning] ?? TUNINGS.standard;
  const list = useMemo(() => (chord && inst !== 'piano' ? voicings(chord, tuning, 6) : []), [symbol, inst, tuning]); // eslint-disable-line react-hooks/exhaustive-deps
  const [i, setI] = useState(0);
  if (!chord) return null;
  const v = list[i % Math.max(1, list.length)];
  return (
    <figure className={`chord-card${big ? ' big' : ''}`} onClick={onClick}>
      <figcaption>{display ?? symbol}</figcaption>
      {inst === 'piano' ? (
        <PianoDiagram chord={chord} size={big ? 1.4 : 0.8} />
      ) : v ? (
        <FretDiagram v={v} strings={tuning.strings.length} lefty={st.lefty} size={big ? 1.6 : 1} />
      ) : (
        <div className="no-shape">—</div>
      )}
      {list.length > 1 && inst !== 'piano' && (
        <button
          className="voicing-btn"
          onClick={(e) => {
            e.stopPropagation();
            setI((x) => (x + 1) % list.length);
          }}
          aria-label="Next voicing"
        >
          {(i % list.length) + 1}/{list.length} ›
        </button>
      )}
    </figure>
  );
}

export function chordLabel(c: Chord, n: Notation, flats: boolean) {
  return formatChord(c, n, flats);
}

export const noteLabel = noteName;
