import { useEffect, useRef, useState } from 'react';
import { afterLogin, canNativeSignIn, nativeSignIn, renderGoogleButton, setProfile, setWelcomed, signInEmail, signUpEmail, useAccount, getProfile } from '../account';
import { ApiError, fetchPlaylist } from '../api';
import { PLAY_TEST, TEST_CHORDS, levelFromTest } from '../lib/learn';
import { GENRES, applyPlaylist, type PlaylistInfo } from '../lib/recs';
import { formatChord, parseChord } from '../lib/music';
import type { Level } from '../lib/difficulty';
import { useT } from '../i18n';
import { go } from '../router';
import { useSettings } from '../settings';
import { ChordCard } from './Diagram';
import { chordMatches, useMic, useMicChord } from './Learn';

/** Consent (14+, terms, privacy): needed to create or use an account. */
export function Consent({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  const t = useT();
  return (
    <label className="consent">
      <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
      <span>
        {t('consentAge')} <a href="#/terms">{t('terms')}</a> {t('consentAnd')} <a href="#/privacy">{t('privacy2')}</a>.
      </span>
    </label>
  );
}

/** Email + password: sign in, or create an account. */
export function EmailLogin({ consent }: { consent: boolean }) {
  const t = useT();
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const ok = email.includes('@') && password.length >= (register ? 8 : 1) && (!register || consent);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr('');
    try {
      setWelcomed();
      afterLogin(register ? await signUpEmail(email, password, name) : await signInEmail(email, password));
    } catch (x) {
      const s = x instanceof ApiError ? x.status : 0;
      if (s === 409) setRegister(false);
      setErr(s === 401 ? t('loginFailed') : s === 409 ? t('emailTaken') : s === 429 ? t('tooMany') : (x as Error).message);
    }
    setBusy(false);
  };
  return (
    <form className="email-login" onSubmit={(e) => void submit(e)}>
      {register && <input value={name} onChange={(e) => setName(e.target.value)} placeholder={t('yourName')} autoComplete="name" />}
      <input type="email" value={email} onChange={(e) => setEmail(e.target.value.trim())} placeholder={t('email')} autoComplete="email" required />
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t('password')} autoComplete={register ? 'new-password' : 'current-password'} required />
      {register && <span className="muted small">{t('passwordHint')}</span>}
      <button className="btn primary big" disabled={!ok || busy}>
        {register ? t('createAccount') : t('signIn')}
      </button>
      {register && !consent && <span className="muted small">{t('consentNeeded')}</span>}
      {err && <p className="error small">{err}</p>}
      <button type="button" className="link-btn" onClick={() => (setRegister(!register), setErr(''))}>
        {register ? t('haveAccount') : t('noAccount')}
      </button>
    </form>
  );
}

/** First visit: sign in (Google or email) or later. */
export function Welcome() {
  const t = useT();
  const st = useSettings();
  const acc = useAccount();
  const [consent, setConsent] = useState(false);
  const [email, setEmail] = useState(false);
  const [err, setErr] = useState('');
  const btn = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (acc.user || canNativeSignIn() || !btn.current || !consent) return;
    btn.current.innerHTML = '';
    renderGoogleButton(btn.current, st.lang, (e) => setErr(e.message)).catch((e: Error) => setErr(e.message));
  }, [acc.user, st.lang, consent]);
  useEffect(() => {
    if (acc.user) setWelcomed();
  }, [acc.user]);
  return (
    <div className="page welcome">
      <img src="/favicon.svg?v=2" alt="" width="88" height="88" />
      <h1>{t('welcomeTitle')}</h1>
      <p className="muted">{t('welcomeText')}</p>
      <Consent value={consent} onChange={setConsent} />
      {!consent ? (
        <p className="muted small">{t('consentNeeded')}</p>
      ) : canNativeSignIn() ? (
        <button className="btn primary big" onClick={() => nativeSignIn().catch((e: Error) => setErr(e.message))}>
          {t('loginGoogle')}
        </button>
      ) : (
        <div ref={btn} className="gbtn" />
      )}
      {email ? (
        <EmailLogin consent={consent} />
      ) : (
        <button className="btn big" onClick={() => setEmail(true)}>
          ✉ {t('loginEmail')}
        </button>
      )}
      {err && <p className="error small">{err}</p>}
      <button
        className="link-btn muted"
        onClick={() => {
          setWelcomed();
          go('/', true);
        }}
      >
        {t('later')}
      </button>
    </div>
  );
}

/** First-run questions: level (questions, then the guitar), then a playlist. All skippable. */
export function Onboarding() {
  const t = useT();
  const st = useSettings();
  const [step, setStep] = useState(0);
  const [years, setYears] = useState<0 | 1 | 2 | 3 | undefined>();
  const [known, setKnown] = useState<string[]>([]);
  const [played, setPlayed] = useState<string[]>([]);
  const [level, setLevel] = useState<Level>(1);
  const [link, setLink] = useState('');
  const [pl, setPl] = useState<PlaylistInfo | null>(null);
  const [plErr, setPlErr] = useState('');
  const [busy, setBusy] = useState(false);
  const label = (s: string) => formatChord(parseChord(s)!, st.notation);
  const levelName = (n: Level) => t(n === 1 ? 'learnBeginner' : n === 2 ? 'level2' : 'level3');

  const finish = () => {
    let p = getProfile();
    if (step >= 3 || known.length || years !== undefined) p = { ...p, level };
    if (pl) p = applyPlaylist(p, pl);
    setProfile({ ...p, onboarded: Date.now() });
    go('/learn', true);
  };
  const next = () => {
    const none = step === 1 && years === 0 && !known.length;
    if (step === 2 || none) setLevel(levelFromTest({ years, known, played }));
    setStep(none ? 3 : step + 1);
  };

  return (
    <div className="page onboarding">
      <div className="onb-top">
        <div className="bar">
          <span style={{ width: `${((step + 1) / 5) * 100}%` }} />
        </div>
        <button className="link-btn muted" onClick={finish}>
          {t('skipAll')}
        </button>
      </div>
      {step === 0 && (
        <>
          <h1>{t('testYears')}</h1>
          {(['years0', 'years1', 'years2', 'years3'] as const).map((k, i) => (
            <button
              key={k}
              className={`card choice${years === i ? ' on' : ''}`}
              onClick={() => {
                setYears(i as 0 | 1 | 2 | 3);
                setStep(1);
              }}
            >
              {t(k)}
            </button>
          ))}
        </>
      )}
      {step === 1 && (
        <>
          <h1>{t('testChords')}</h1>
          <p className="muted">{t('testChordsHint')}</p>
          <div className="chips">
            {TEST_CHORDS.map((c) => (
              <button key={c} className={`chip mono${known.includes(c) ? ' on' : ''}`} onClick={() => setKnown(known.includes(c) ? known.filter((x) => x !== c) : [...known, c])}>
                {label(c)}
              </button>
            ))}
          </div>
        </>
      )}
      {step === 2 && <PlayTest played={played} onPlayed={(c) => setPlayed((p) => [...p, c])} />}
      {step === 3 && (
        <>
          <h1>{t('testResult')}</h1>
          <b className="big-level accent">{levelName(level)}</b>
          <div className="seg">
            {([1, 2, 3] as Level[]).map((n) => (
              <button key={n} className={n === level ? 'on' : ''} onClick={() => setLevel(n)}>
                {levelName(n)}
              </button>
            ))}
          </div>
          <p className="muted">{t('testResultHint')}</p>
        </>
      )}
      {step === 4 && (
        <>
          <h1>{t('playlistTitle')}</h1>
          <p className="muted">{t('playlistText')}</p>
          <form
            className="paste-row"
            onSubmit={(e) => {
              e.preventDefault();
              setBusy(true);
              setPlErr('');
              fetchPlaylist(link)
                .then(setPl)
                .catch(() => setPlErr(t('playlistFailed')))
                .finally(() => setBusy(false));
            }}
          >
            <input value={link} onChange={(e) => (setLink(e.target.value.trim()), setPl(null))} placeholder={t('playlistLink')} inputMode="url" />
            <button className="btn" disabled={!link.startsWith('http') || busy}>
              {busy ? '…' : 'OK'}
            </button>
          </form>
          {pl && (
            <section className="card">
              <b>{pl.name || pl.source}</b>
              <p className="muted small">
                {pl.artists.length} {t('playlistFound')}: {pl.artists.slice(0, 6).map((a) => a.name).join(', ')}
              </p>
              {pl.genres.length > 0 && <p className="accent">{pl.genres.map((g) => GENRES.find((x) => x.id === g)).filter(Boolean).map((g) => (st.lang === 'it' ? g!.it : g!.en)).join(' · ')}</p>}
            </section>
          )}
          {plErr && <p className="error">{plErr}</p>}
        </>
      )}
      <div className="row-btns onb-bottom">
        {step < 4 ? (
          <>
            <button className="btn big" onClick={() => (step === 0 && setYears(undefined), next())}>
              {t('skip')}
            </button>
            <button className="btn primary big" onClick={next}>
              {t('continue')}
            </button>
          </>
        ) : (
          <button className="btn primary big" onClick={finish}>
            {t('letsGo')}
          </button>
        )}
      </div>
    </div>
  );
}

function PlayTest({ played, onPlayed }: { played: string[]; onPlayed: (c: string) => void }) {
  const t = useT();
  const st = useSettings();
  const { mic, err, start } = useMic();
  const [i, setI] = useState(0);
  const target = PLAY_TEST[i] ?? null;
  const label = (s: string) => formatChord(parseChord(s)!, st.notation);
  const heard = useMicChord(mic, target, () => {
    if (!target) return;
    onPlayed(target);
    setTimeout(() => setI((x) => x + 1), 600);
  });
  return (
    <>
      <h1>{t('testPlay')}</h1>
      <p className="muted">{t('testPlayHint')}</p>
      <div className="chips">
        {PLAY_TEST.map((c) => (
          <span key={c} className={`mono ${played.includes(c) ? 'accent' : c === target ? '' : 'muted'}`}>
            {label(c)}
            {played.includes(c) ? ' ✓' : ''}
          </span>
        ))}
      </div>
      {target ? (
        <>
          <div className="center">
            <ChordCard symbol={target} display={`${t('playThis')}: ${label(target)}`} big />
          </div>
          {!mic ? (
            <button className="btn primary big" onClick={() => void start(t('micDenied'))}>
              🎤 {t('micStart')}
            </button>
          ) : (
            <>
              <b className={`heard center${chordMatches(target, heard) ? ' accent' : ' muted'}`}>{heard ? label(heard) : '…'}</b>
              <button className="btn" onClick={() => setI(i + 1)}>
                {t('dontKnow')}
              </button>
            </>
          )}
        </>
      ) : (
        <b className="big-level accent">
          ✓ {played.length}/{PLAY_TEST.length}
        </b>
      )}
      {err && <p className="error">{err}</p>}
    </>
  );
}
