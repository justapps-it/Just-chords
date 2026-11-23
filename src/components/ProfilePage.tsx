import { useEffect, useRef, useState } from 'react';
import { canNativeSignIn, deleteAccount, downloadMyData, effectiveLevel, logout, nativeSignIn, renderGoogleButton, setProfile, syncNow, useAccount } from '../account';
import { GENRES } from '../lib/recs';
import type { Level } from '../lib/difficulty';
import { useT } from '../i18n';
import { go } from '../router';
import { useSettings } from '../settings';
import { useLibrary } from '../store';
import { LevelBadge } from './LevelBadge';

export function ProfilePage() {
  const t = useT();
  const st = useSettings();
  const lib = useLibrary();
  const acc = useAccount();
  const p = acc.profile;
  const btn = useRef<HTMLDivElement>(null);
  const [err, setErr] = useState('');
  // Accounts need the 14+ confirmation and acceptance of terms and privacy policy.
  const [consent, setConsent] = useState(false);
  const estimated = effectiveLevel(lib.songs);

  useEffect(() => {
    if (acc.user || canNativeSignIn() || !btn.current || !consent) return;
    btn.current.innerHTML = '';
    renderGoogleButton(btn.current, st.lang, (e) => setErr(e.message)).catch((e: Error) => setErr(e.message));
  }, [acc.user, st.lang, consent]);

  const toggleGenre = (id: string) => setProfile({ ...p, genres: p.genres.includes(id) ? p.genres.filter((g) => g !== id) : [...p.genres, id] });
  const artists = Object.entries(p.artists)
    .filter(([, w]) => w > 0.3)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 15);

  return (
    <div className="profile">
      <button className="link-btn" onClick={() => go('/')}>
        ← {t('library')}
      </button>
      <h1>{t('profile')}</h1>

      <section className="card account">
        {acc.user ? (
          <div className="account-row">
            {acc.user.picture && <img src={acc.user.picture} alt="" width="48" height="48" referrerPolicy="no-referrer" />}
            <div className="account-who">
              <b>{acc.user.name}</b>
              <span className="muted small">{acc.user.email}</span>
              <span className="muted small">
                {acc.sync === 'syncing' ? t('syncing') : acc.sync === 'error' ? `${t('syncError')}: ${acc.error}` : `${t('synced')} · ${lib.songs.length} ${t('songs')}`}
              </span>
            </div>
            <div className="account-actions">
              <button className="btn" onClick={() => void syncNow()}>
                {t('syncNow')}
              </button>
              <button className="btn" onClick={logout}>
                {t('logout')}
              </button>
            </div>
            <div className="account-legal">
              <button className="link-btn small" onClick={downloadMyData}>
                {t('downloadData')}
              </button>
              <button
                className="link-btn small danger"
                onClick={() => {
                  if (confirm(t('deleteAccountConfirm'))) deleteAccount().catch((e: Error) => setErr(e.message));
                }}
              >
                {t('deleteAccount')}
              </button>
            </div>
            {err && <p className="error small">{err}</p>}
          </div>
        ) : (
          <div>
            <h2>{t('loginTitle')}</h2>
            <p className="muted">{t('loginText')}</p>
            <label className="consent">
              <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              <span>
                {t('consentAge')} <a href="#/terms">{t('terms')}</a> {t('consentAnd')} <a href="#/privacy">{t('privacy2')}</a>.
              </span>
            </label>
            {!consent ? (
              <p className="muted small">{t('consentNeeded')}</p>
            ) : canNativeSignIn() ? (
              <button className="btn primary big" onClick={() => nativeSignIn().catch((e: Error) => setErr(e.message))}>
                <GoogleG /> {t('loginGoogle')}
              </button>
            ) : (
              <div ref={btn} className="gbtn" />
            )}
            {err && <p className="error small">{err}</p>}
          </div>
        )}
      </section>

      <section className="card">
        <h2>{t('yourGenres')}</h2>
        <p className="muted small">{t('genresHint')}</p>
        <div className="chips">
          {GENRES.map((g) => (
            <button key={g.id} className={`chip${p.genres.includes(g.id) ? ' on' : ''}`} onClick={() => toggleGenre(g.id)} aria-pressed={p.genres.includes(g.id)}>
              {st.lang === 'it' ? g.it : g.en}
            </button>
          ))}
        </div>
      </section>

      <section className="card">
        <h2>{t('yourLevel')}</h2>
        <div className="seg wide">
          <button className={p.level === 0 ? 'on' : ''} onClick={() => setProfile({ ...p, level: 0 })}>
            {t('levelAuto')}
          </button>
          {([1, 2, 3] as Level[]).map((l) => (
            <button key={l} className={p.level === l ? 'on' : ''} onClick={() => setProfile({ ...p, level: l })}>
              {t(`level${l}` as 'level1')}
            </button>
          ))}
        </div>
        <p className="muted small">
          {p.level === 0 ? (
            <>
              {t('levelEstimated')} <LevelBadge level={estimated} />
            </>
          ) : (
            t(`level${p.level}Hint` as 'level1Hint')
          )}
        </p>
      </section>

      {artists.length > 0 && (
        <section className="card">
          <h2>{t('learntArtists')}</h2>
          <p className="muted small">{t('learntHint')}</p>
          <div className="chips">
            {artists.map(([a]) => (
              <span key={a} className="chip tag">
                {a.replace(/(^|\s)\S/g, (c) => c.toUpperCase())}
                <button
                  aria-label={t('remove')}
                  onClick={() => {
                    const next = { ...p.artists };
                    delete next[a];
                    setProfile({ ...p, artists: next });
                  }}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        </section>
      )}

      <p className="muted small note">{t('profilePrivacy')}</p>
    </div>
  );
}

const GoogleG = () => (
  <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
    <path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z" />
    <path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z" />
    <path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-7.9l-6.5 5C9.5 39.6 16.2 44 24 44z" />
    <path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z" />
  </svg>
);
