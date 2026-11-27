import { useEffect, useMemo, useRef, useState } from 'react';
import { effectiveLevel, markDone, setProfile, useAccount } from '../account';
import { findVideos } from '../api';
import { getDsp, keepAwake, openMic, type Mic } from '../audio/mic';
import { LiveChords } from '../audio/dsp';
import { PracticeClock } from '../audio/practice';
import { ALONG_SPEEDS, COURSES, at, courseProgress, coursesFor, midiOf, nextLesson, parseNotes, parsePattern, songPlan, tabLines, tempoSteps, timeline, type Course, type L10n, type Lesson } from '../lib/learn';
import { chordNotes, formatChord, parseChord, simplifyChord } from '../lib/music';
import type { Level } from '../lib/difficulty';
import { useT, type Key } from '../i18n';
import { go, routeQuery } from '../router';
import { useSettings } from '../settings';
import { patchSong, useLibrary, type Song } from '../store';
import { ChordCard } from './Diagram';
import { Sheet } from './SongView';

const LEVEL_KEY: Record<Level, Key> = { 1: 'learnBeginner', 2: 'level2', 3: 'level3' };
const ICON: Record<Lesson['type'], string> = { chord: '✋', switch: '⇄', strum: '↕', riff: '♪', progression: '▶' };

function useLabels() {
  const st = useSettings();
  const l = (x: L10n) => (st.lang === 'it' ? x.it : x.en);
  const chord = (s: string) => {
    const c = parseChord(s);
    return c ? formatChord(c, st.notation) : s;
  };
  return { l, chord };
}

/** What the microphone hears fits the chord to learn (an Fmaj7 heard as F or Am is fine). */
export function chordMatches(target: string, heard: string | null | undefined): boolean {
  const t = parseChord(target);
  const h = heard ? parseChord(heard) : null;
  if (!t || !h) return false;
  if (t.root === h.root && (t.suffix === '5' || simplifyChord(t).suffix === simplifyChord(h).suffix)) return true;
  const tn = new Set(chordNotes(t));
  const hn = chordNotes(h);
  return hn.length >= 3 && hn.every((n) => tn.has(n));
}

/** Live chord from the microphone; calls onMatch when [target] is heard steadily. */
export function useMicChord(mic: Mic | null, target: string | null, onMatch: () => void) {
  const [heard, setHeard] = useState<string | null>(null);
  const cb = useRef(onMatch);
  cb.current = onMatch;
  useEffect(() => {
    if (!mic) return;
    keepAwake(true);
    let alive = true;
    let timer = 0;
    let streak = 0;
    const live = new LiveChords();
    void getDsp().then((dsp) => {
      const sr = mic.ctx.sampleRate;
      const n = sr > 30000 ? 8192 : 4096;
      const loop = () => {
        if (!alive) return;
        const { chroma, rms } = dsp.chroma(mic.read(n), sr);
        const name = live.push(chroma, rms).chord?.name ?? null;
        setHeard(name);
        streak = target && chordMatches(target, name) ? streak + 1 : 0;
        if (streak >= 4) {
          streak = 0;
          cb.current();
        }
        timer = window.setTimeout(loop, 100);
      };
      loop();
    });
    return () => {
      alive = false;
      clearTimeout(timer);
      keepAwake(false);
    };
  }, [mic, target]);
  return heard;
}

export function useMic() {
  const [mic, setMic] = useState<Mic | null>(null);
  const [err, setErr] = useState('');
  useEffect(() => () => mic?.stop(), [mic]);
  const start = async (deniedText: string) => {
    setErr('');
    try {
      setMic(await openMic(8192));
    } catch {
      setErr(deniedText);
    }
  };
  return { mic, err, start, stop: () => setMic(null) };
}

// ------------------------------------------------------------- Learn page

export function LearnPage() {
  const t = useT();
  const lib = useLibrary();
  const acc = useAccount();
  const { l } = useLabels();
  const level = effectiveLevel(lib.songs);
  const done = new Set(acc.profile.done ?? []);
  const { mine, others } = coursesFor(level);
  const next = nextLesson(level, done);
  return (
    <div className="page learn">
      <h1>{t('learn')}</h1>
      <p className="muted">{t('learnLead')}</p>
      <section className="card">
        <span className="eyebrow">{t('yourLevel')}</span>
        <div className="seg">
          {([1, 2, 3] as Level[]).map((n) => (
            <button key={n} className={n === level ? 'on' : ''} onClick={() => setProfile({ ...acc.profile, level: n })}>
              {t(LEVEL_KEY[n])}
            </button>
          ))}
        </div>
      </section>
      {!acc.profile.onboarded && (
        <button className="card learn-test" onClick={() => go('/onboarding')}>
          <b>❓ {t('takeTest')}</b>
          <span className="muted small">{t('takeTestText')}</span>
        </button>
      )}
      <section className="card learn-next">
        {next ? (
          <>
            <span className="eyebrow accent">{t('continueLesson')}</span>
            <h2>{l(next.lesson.title)}</h2>
            <p className="muted small">{l(next.course.title)}</p>
            <button className="btn primary" onClick={() => go(`/lesson/${encodeURIComponent(next.lesson.id)}`)}>
              ▶ {t('learnStart')}
            </button>
          </>
        ) : (
          <p>{t('allDone')}</p>
        )}
      </section>
      <h2>{t('coursesForYou')}</h2>
      <div className="course-list">{mine.map((c) => <CourseCard key={c.id} c={c} done={done} />)}</div>
      <h2>{t('otherCourses')}</h2>
      <div className="course-list">{others.map((c) => <CourseCard key={c.id} c={c} done={done} />)}</div>
    </div>
  );
}

function CourseCard({ c, done }: { c: Course; done: Set<string> }) {
  const t = useT();
  const { l } = useLabels();
  const p = courseProgress(c, done);
  return (
    <button className="card course" onClick={() => go(`/course/${c.id}`)}>
      <b>
        {l(c.title)} {p >= 1 && <span className="accent">✓</span>}
      </b>
      <span className="muted small">{l(c.desc)}</span>
      <span className="progress-row">
        <span className="bar">
          <span style={{ width: `${p * 100}%` }} />
        </span>
        <span className="mono small muted">
          {c.lessons.filter((x) => done.has(x.id)).length}/{c.lessons.length} {t('lessons')}
        </span>
      </span>
    </button>
  );
}

export function CoursePage({ id }: { id: string }) {
  const t = useT();
  const acc = useAccount();
  const { l } = useLabels();
  const c = COURSES.find((x) => x.id === id);
  if (!c) return null;
  const done = new Set(acc.profile.done ?? []);
  return (
    <div className="page learn">
      <button className="link-btn" onClick={() => go('/learn')}>
        ← {t('learn')}
      </button>
      <h1>{l(c.title)}</h1>
      <p className="muted">
        {t(LEVEL_KEY[c.level])} · {l(c.desc)}
      </p>
      <div className="lesson-list">
        {c.lessons.map((x) => (
          <LessonRow key={x.id} title={l(x.title)} icon={ICON[x.type]} done={done.has(x.id)} onClick={() => go(`/lesson/${encodeURIComponent(x.id)}`)} />
        ))}
      </div>
    </div>
  );
}

function LessonRow({ title, icon, done, sub, onClick }: { title: string; icon: string; done: boolean; sub?: string; onClick: () => void }) {
  return (
    <button className={`card lesson-row${done ? ' done' : ''}`} onClick={onClick}>
      <span className="lesson-icon">{done ? '✓' : icon}</span>
      <span className="lesson-text">
        <b>{title}</b>
        {sub && <span className="muted small">{sub}</span>}
      </span>
      <span className="muted">→</span>
    </button>
  );
}

const planLabels = (it: Record<string, string>, en: Record<string, string>) => ({
  chords: { it: it.stepChords, en: en.stepChords },
  switch: { it: it.stepSwitch, en: en.stepSwitch },
  rhythm: { it: it.stepRhythm, en: en.stepRhythm },
  riff: { it: it.stepRiff, en: en.stepRiff },
  play: { it: it.stepPlay, en: en.stepPlay },
});
const LABELS = { it: { stepChords: 'Gli accordi', stepSwitch: 'I cambi', stepRhythm: 'Il ritmo', stepRiff: 'Riff e melodia', stepPlay: 'Tutta la canzone' }, en: { stepChords: 'The chords', stepSwitch: 'The switches', stepRhythm: 'The rhythm', stepRiff: 'Riff and melody', stepPlay: 'The whole song' } };

/** A course lesson, or (with ?song=) a lesson of a song's plan. */
export function LessonPage({ id }: { id: string }) {
  const t = useT();
  const lib = useLibrary();
  const acc = useAccount();
  const { l } = useLabels();
  const songId = routeQuery().get('song');
  const level = effectiveLevel(lib.songs);
  const song = songId ? lib.songs.find((s) => s.id === songId) : undefined;
  const lesson = song ? songPlan(song.chordpro, level, planLabels(LABELS.it, LABELS.en)).lessons.find((x) => x.id === id) : COURSES.flatMap((c) => c.lessons).find((x) => x.id === id);
  if (!lesson) return null;
  const doneId = song ? `song:${song.id}:${id}` : id;
  return (
    <div className="page learn">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{l(lesson.title)}</h1>
      <LessonPlayer key={doneId} lesson={lesson} done={(acc.profile.done ?? []).includes(doneId)} onDone={() => markDone(doneId)} />
    </div>
  );
}

// ------------------------------------------------------------- the player

export function LessonPlayer({ lesson, done, onDone }: { lesson: Lesson; done: boolean; onDone: () => void }) {
  const t = useT();
  const { l, chord } = useLabels();
  const steps = useMemo(() => tempoSteps(lesson.tempo), [lesson]);
  const tl = useMemo(() => timeline(lesson), [lesson]);
  const notes = useMemo(() => (lesson.notes ? parseNotes(lesson.notes) : []), [lesson]);
  const [stage, setStage] = useState(0);
  const [clock, setClock] = useState<PracticeClock | null>(null);
  const [pos, setPos] = useState(-4);
  const [finished, setFinished] = useState(false);
  const [listening, setListening] = useState(false);
  const [complete, setComplete] = useState(false);

  useEffect(() => () => clock?.stop(), [clock]);
  useEffect(() => {
    if (!clock) return;
    keepAwake(true);
    let raf = 0;
    const loop = () => {
      setPos(clock.position());
      raf = requestAnimationFrame(loop);
    };
    loop();
    return () => {
      cancelAnimationFrame(raf);
      keepAwake(false);
    };
  }, [clock]);

  const stop = () => {
    clock?.stop();
    setClock(null);
  };
  const play = (listenOnly = false, s = stage) => {
    clock?.stop();
    setFinished(false);
    setListening(listenOnly);
    const sounds: [number, number[], number][] = listenOnly ? tl.steps.filter((x) => x.note !== undefined).map((x) => [x.at, notes[x.note!].frets.map((f) => midiOf(f.s, f.f)), x.beats]) : [];
    const c = new PracticeClock({
      bpm: steps[s] ?? 70,
      totalBeats: tl.total,
      bar: tl.bar,
      countIn: listenOnly ? 0 : tl.bar,
      notes: sounds,
      clicks: !listenOnly,
      onEnd: () => {
        setClock(null);
        setFinished(true);
        if (!listenOnly && s === steps.length - 1) {
          setComplete(true);
          onDone();
        }
      },
    });
    void c.start();
    setClock(c);
  };

  if (lesson.type === 'chord') return <ChordCheck target={lesson.chord!} tip={lesson.tip ? l(lesson.tip) : undefined} done={done} onDone={onDone} />;

  const now = at(tl.steps, Math.max(0, pos));
  const cur = now.chord?.chord ?? tl.steps.find((x) => x.chord)?.chord ?? '';
  const pattern = parsePattern(lesson.type === 'strum' ? (lesson.pattern ?? '') : (lesson.strum ?? ''));
  const slot = clock && pos >= 0 ? Math.floor((pos % 4) / (4 / Math.max(1, pattern.length))) : -1;
  const { lines, columns } = tabLines(notes);
  const noteCol = clock && now.note?.note !== undefined ? columns[now.note.note] : -1;

  return (
    <div className="player-lesson">
      {lesson.tip && <p className="card tip">{l(lesson.tip)}</p>}
      <div className="tempo-steps">
        <span className="muted">⏱</span>
        {steps.map((b, i) => (
          <span key={i} className={`tempo-chip${i === stage ? ' on' : i < stage ? ' past' : ''}`}>
            {b}
          </span>
        ))}
        <span className="muted small">BPM</span>
      </div>
      <section className="card stage-card">
        {clock && pos < 0 ? (
          <div className="count-in">
            <span className="muted">{t('getReady')}</span>
            <b>{Math.floor(-pos) + 1}</b>
          </div>
        ) : lesson.type === 'riff' ? (
          <>
            <pre className="tab-view">
              {lines.map((line, i) => (
                <div key={i}>
                  {(() => {
                    const m = noteCol >= 0 ? line.slice(noteCol).match(/^\d+/) : null;
                    return m ? (
                      <>
                        {line.slice(0, noteCol)}
                        <mark>{m[0]}</mark>
                        {line.slice(noteCol + m[0].length)}
                      </>
                    ) : (
                      line
                    );
                  })()}
                </div>
              ))}
            </pre>
            <p className="muted small">{t('tabLegend')}</p>
          </>
        ) : (
          <>
            <div className="now-next">
              <div>
                <span className="eyebrow">{t('nowLabel')}</span>
                <b className="now-chord">{chord(cur)}</b>
                {now.next && (
                  <span className="muted mono">
                    {t('nextLabel')}: {chord(now.next.chord!)}
                  </span>
                )}
              </div>
              <ChordCard symbol={cur} display={chord(cur)} />
            </div>
            {pattern.length > 0 && (
              <>
                <div className="strum-row">
                  {pattern.map((s, i) => (
                    <span key={i} className={`stroke${i === slot ? ' on' : ''}${s === '-' ? ' miss' : ''}`}>
                      {s === 'D' ? '↓' : s === 'U' ? '↑' : s === 'X' ? '✕' : '·'}
                    </span>
                  ))}
                </div>
                <p className="muted small">{t('strumLegend')}</p>
              </>
            )}
          </>
        )}
        {clock && pos >= 0 && (
          <>
            <div className="beat-dots">
              {Array.from({ length: tl.bar }, (_, i) => (
                <span key={i} className={i === Math.floor(pos % tl.bar) ? 'on' : ''} />
              ))}
            </div>
            <div className="bar thin">
              <span style={{ width: `${Math.min(100, (pos / tl.total) * 100)}%` }} />
            </div>
          </>
        )}
      </section>
      {complete && <p className="card complete">✓ {t('learnComplete')}</p>}
      <div className="row-btns">
        {clock ? (
          <button className="btn primary big" onClick={stop}>
            ❚❚ {t('learnPause')}
          </button>
        ) : finished && !listening && stage < steps.length - 1 ? (
          <>
            <button
              className="btn primary big"
              onClick={() => {
                setStage(stage + 1);
                play(false, stage + 1);
              }}
            >
              ⏩ {t('learnFaster')} · {steps[stage + 1]} BPM
            </button>
            <button className="btn big" onClick={() => play()}>
              ↻ {t('learnAgain')}
            </button>
          </>
        ) : (
          <button
            className="btn primary big"
            onClick={() => {
              if (complete) {
                setComplete(false);
                setStage(0);
                play(false, 0);
              } else play();
            }}
          >
            ▶ {complete ? t('learnAgain') : `${t('learnStart')} · ${steps[stage] ?? ''} BPM`}
          </button>
        )}
        {lesson.type === 'riff' && !clock && (
          <button className="btn big" onClick={() => play(true)}>
            👂 {t('listenRiff')}
          </button>
        )}
        {!done && !complete && !clock && (
          <button className="btn big" onClick={onDone}>
            {t('markDone')}
          </button>
        )}
      </div>
    </div>
  );
}

function ChordCheck({ target, tip, done, onDone }: { target: string; tip?: string; done: boolean; onDone: () => void }) {
  const t = useT();
  const { chord } = useLabels();
  const { mic, err, start } = useMic();
  const [ok, setOk] = useState(false);
  const heard = useMicChord(ok ? null : mic, target, () => {
    setOk(true);
    onDone();
  });
  return (
    <div className="player-lesson">
      {tip && <p className="card tip">{tip}</p>}
      <div className="center">
        <ChordCard symbol={target} display={chord(target)} big />
      </div>
      <section className="card">
        {ok || (done && !mic) ? (
          <h2 className="accent">✓ {ok ? t('heard') : t('lessonDone')}</h2>
        ) : !mic ? (
          <>
            <p className="muted">{t('playChordCheck')}</p>
            <button className="btn primary big" onClick={() => void start(t('micDenied'))}>
              🎤 {t('micStart')}
            </button>
          </>
        ) : (
          <>
            <span className="eyebrow accent">{t('micOn')}</span>
            <b className={`heard${chordMatches(target, heard) ? ' accent' : ' muted'}`}>{heard ? chord(heard) : '…'}</b>
            <p className="muted small">{t('playChordCheck')}</p>
          </>
        )}
        {err && <p className="error">{err}</p>}
      </section>
      {!done && !ok && (
        <button className="link-btn" onClick={onDone}>
          {t('markDone')}
        </button>
      )}
    </div>
  );
}

// ---------------------------------------------------------- learn a song

export function SongLearn({ song }: { song: Song }) {
  const t = useT();
  const lib = useLibrary();
  const acc = useAccount();
  const { chord, l } = useLabels();
  const level = effectiveLevel(lib.songs);
  const plan = useMemo(() => songPlan(song.chordpro, level, planLabels(LABELS.it, LABELS.en)), [song.chordpro, level]);
  const done = new Set(acc.profile.done ?? []);
  const isDone = (x: Lesson) => done.has(`song:${song.id}:${x.id}`);
  const open = (x: Lesson) => go(`/lesson/${encodeURIComponent(x.id)}?song=${encodeURIComponent(song.id)}`);
  const switches = plan.lessons.filter((x) => x.type === 'switch');
  const rhythm = plan.lessons.find((x) => x.type === 'strum');
  const riffs = plan.lessons.filter((x) => x.type === 'riff');
  const whole = plan.lessons.find((x) => x.type === 'progression');
  const arrows = (p: string) => [...p].map((c) => (c === 'D' ? '↓' : c === 'U' ? '↑' : c === 'X' ? '✕' : '·')).join(' ');
  let n = 0;
  return (
    <div className="page learn">
      <button className="link-btn" onClick={() => go(`/song/${song.id}`)}>
        ← {song.title}
      </button>
      <h1>
        {t('learn')}: {song.title}
      </h1>
      <p className="muted">
        {t(LEVEL_KEY[level])} · {t('learnSongLead')}
      </p>
      {plan.capo > 0 && (
        <p className="card accent">
          <b>
            {t('putCapo')} {plan.capo}
          </b>
        </p>
      )}
      <h2>
        {++n} · {t('stepChords')}
      </h2>
      <div className="chord-strip">
        {plan.chords.map((c) => {
          const x = plan.lessons.find((y) => y.id === `chord:${c.shape}`)!;
          return (
            <div key={c.shape} className="chord-step">
              <ChordCard symbol={c.shape} display={chord(c.shape)} onClick={() => open(x)} />
              <span className={`small ${isDone(x) ? 'accent' : 'muted'}`}>{isDone(x) ? `✓ ${t('lessonDone')}` : c.from ? `${t('easierShape')} ${chord(c.from)}` : ' '}</span>
            </div>
          );
        })}
      </div>
      {switches.length > 0 && (
        <>
          <h2>
            {++n} · {t('stepSwitch')}
          </h2>
          <div className="lesson-list">
            {switches.map((x) => (
              <LessonRow key={x.id} title={`${chord(x.chords![0])} ↔ ${chord(x.chords![1])}`} icon="⇄" done={isDone(x)} onClick={() => open(x)} />
            ))}
          </div>
        </>
      )}
      {rhythm && (
        <>
          <h2>
            {++n} · {t('stepRhythm')}
          </h2>
          <LessonRow title={arrows(rhythm.pattern!)} icon="↕" done={isDone(rhythm)} sub={`${plan.tempo[0]} → ${plan.tempo[1]} BPM`} onClick={() => open(rhythm)} />
        </>
      )}
      {riffs.length > 0 && (
        <>
          <h2>
            {++n} · {t('stepRiff')}
          </h2>
          <div className="lesson-list">
            {riffs.map((x) => (
              <LessonRow key={x.id} title={l(x.title)} icon="♪" done={isDone(x)} onClick={() => open(x)} />
            ))}
          </div>
        </>
      )}
      {whole && (
        <>
          <h2>
            {++n} · {t('stepPlay')}
          </h2>
          <LessonRow title={whole.chords!.map(chord).join('  ')} icon="▶" done={isDone(whole)} sub={`${plan.tempo[0]} → ${plan.tempo[1]} BPM`} onClick={() => open(whole)} />
        </>
      )}
      <AlongPlayer song={song} level={level} />
      <section className="card">
        <Sheet chordpro={song.chordpro} />
      </section>
    </div>
  );
}

// YouTube IFrame API, for the playback speed.
interface YTPlayer {
  setPlaybackRate(r: number): void;
  playVideo(): void;
  seekTo(s: number, allow: boolean): void;
  destroy(): void;
}
type YTNamespace = { Player: new (el: HTMLElement, o: Record<string, unknown>) => YTPlayer; PlayerState: { ENDED: number } };
let ytApi: Promise<YTNamespace> | null = null;
function loadYT(): Promise<YTNamespace> {
  ytApi ??= new Promise((resolve, reject) => {
    const w = window as unknown as { YT?: YTNamespace; onYouTubeIframeAPIReady?: () => void };
    if (w.YT?.Player) return resolve(w.YT);
    w.onYouTubeIframeAPIReady = () => resolve(w.YT!);
    const s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    s.onerror = () => {
      ytApi = null;
      reject(new Error('youtube'));
    };
    document.head.appendChild(s);
  });
  return ytApi;
}

/** The original video, slowed down first, then at full speed. */
function AlongPlayer({ song, level }: { song: Song; level: Level }) {
  const t = useT();
  const speeds = ALONG_SPEEDS(level);
  const [speed, setSpeed] = useState(0);
  const [id, setId] = useState(song.youtube ?? '');
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const host = useRef<HTMLDivElement>(null);
  const player = useRef<YTPlayer | null>(null);
  const speedRef = useRef(0);
  speedRef.current = speed;

  useEffect(() => () => player.current?.destroy(), []);
  useEffect(() => player.current?.setPlaybackRate(speeds[speed]), [speed]); // eslint-disable-line react-hooks/exhaustive-deps

  const start = async () => {
    setState('loading');
    try {
      let v = id;
      if (!v) {
        v = (await findVideos(`${song.artist} ${song.title}`.trim())).items[0]?.id ?? '';
        if (!v) throw new Error('no video');
        setId(v);
        patchSong(song.id, { youtube: v, updated: Date.now() });
      }
      const YT = await loadYT();
      const el = document.createElement('div');
      host.current!.innerHTML = '';
      host.current!.appendChild(el);
      player.current = new YT.Player(el, {
        videoId: v,
        host: 'https://www.youtube-nocookie.com',
        playerVars: { playsinline: 1, rel: 0, modestbranding: 1 },
        events: {
          onReady: (e: { target: YTPlayer }) => {
            e.target.setPlaybackRate(speeds[speedRef.current]);
            e.target.playVideo();
            setState('ready');
          },
          onStateChange: (e: { data: number; target: YTPlayer }) => {
            // At the end: again, a bit faster.
            if (e.data === YT.PlayerState.ENDED && speedRef.current < speeds.length - 1) {
              setSpeed(speedRef.current + 1);
              e.target.seekTo(0, true);
              e.target.playVideo();
            }
          },
        },
      });
    } catch {
      setState('error');
    }
  };

  return (
    <section className="card along">
      <h2>{t('playAlong')}</h2>
      <p className="muted small">{t('playAlongHint')}</p>
      <div className="chips">
        <span className="muted small">{t('speed')}</span>
        {speeds.map((s, i) => (
          <button key={s} className={`chip${i === speed ? ' on' : ''}`} onClick={() => setSpeed(i)}>
            {s}×
          </button>
        ))}
        {state === 'ready' && speed < speeds.length - 1 && (
          <button className="btn small" onClick={() => setSpeed(speed + 1)}>
            ⏩ {t('learnFaster')}
          </button>
        )}
      </div>
      <div ref={host} className={state === 'ready' || state === 'loading' ? 'player-frame' : ''} />
      {(state === 'idle' || state === 'error') && (
        <button className="btn primary" onClick={() => void start()}>
          ▶ {t('learnStart')}
        </button>
      )}
      {state === 'error' && <p className="error small">{t('noVideo')}</p>}
    </section>
  );
}
