// Account and taste profile. The profile lives on the device and works
// without an account; signing in with Google keeps profile and library in
// sync across devices.
import { useSyncExternalStore } from 'react';
import { ApiError, callNative, getToken, inAndroidApp, loginWithGoogle, me, native, putProfile, setToken, syncLibrary, type User } from './api';
import { emptyProfile, genresOfArtist, learn, type Profile, type Signal } from './lib/recs';
import { estimateLevel, songDifficulty, type Level } from './lib/difficulty';
import { exportLibrary, importLibrary, onLibraryChange, type Song } from './store';
import type { Library } from './lib/sync';

export const GOOGLE_CLIENT_ID = '600886543133-tmdg4a42m6vk0iimh705i0gpennvt4li.apps.googleusercontent.com';

const PKEY = 'justchords-profile';
const UKEY = 'justchords-user';
const read = <T,>(k: string, fallback: T): T => {
  try {
    const v = localStorage.getItem(k);
    return v ? { ...fallback, ...JSON.parse(v) } : fallback;
  } catch {
    return fallback;
  }
};
const write = (k: string, v: unknown) => {
  try {
    if (v === null) localStorage.removeItem(k);
    else localStorage.setItem(k, JSON.stringify(v));
  } catch {
    /* blocked */
  }
};

interface State {
  user: User | null;
  profile: Profile;
  sync: 'idle' | 'syncing' | 'error';
  error?: string;
}

let state: State = { user: getToken() ? read<User | null>(UKEY, null as never) : null, profile: read(PKEY, emptyProfile()), sync: 'idle' };
const listeners = new Set<() => void>();
const set = (patch: Partial<State>) => {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
};

export const useAccount = () =>
  useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
export const getProfile = () => state.profile;

let profileTimer: ReturnType<typeof setTimeout> | undefined;
export function setProfile(next: Profile) {
  next = { ...next, updated: Date.now() };
  write(PKEY, next);
  set({ profile: next });
  if (state.user) {
    clearTimeout(profileTimer);
    profileTimer = setTimeout(() => void putProfile(state.profile).catch(onAuthError), 1500);
  }
}

export function signal(kind: Signal, song: { title: string; artist: string; genres?: string[] }) {
  if (!song.artist && !song.title) return;
  setProfile(learn(state.profile, kind, { ...song, genres: song.genres ?? genresOfArtist(song.artist) }));
}

/** The level to recommend for: chosen, or estimated from the library. */
export function effectiveLevel(songs: Song[]): Level {
  if (state.profile.level) return state.profile.level;
  return estimateLevel(songs.map((s) => ({ level: songDifficulty(s.chordpro).level, weight: 1 + (s.favorite ? 2 : 0) + (s.opened ? 1 : 0) })));
}

// ------------------------------------------------------------------ login

function onAuthError(e: unknown) {
  if (e instanceof ApiError && e.status === 401) logout();
}

export async function completeLogin(credential: string) {
  const r = await loginWithGoogle(credential);
  setToken(r.token);
  write(UKEY, r.user);
  // The newer profile wins; a brand-new account takes the local one.
  const local = state.profile;
  const profile = r.profile && r.profile.updated > local.updated ? r.profile : local;
  write(PKEY, profile);
  set({ user: r.user, profile });
  if (profile !== r.profile) await putProfile(profile).catch(() => {});
  await syncNow();
}

export function logout() {
  setToken(null);
  write(UKEY, null);
  set({ user: null, sync: 'idle' });
  (window as unknown as { google?: { accounts: { id: { disableAutoSelect(): void } } } }).google?.accounts.id.disableAutoSelect();
}

let syncing: Promise<void> | null = null;
let again = false;
export function syncNow(): Promise<void> {
  if (!state.user) return Promise.resolve();
  if (syncing) {
    again = true;
    return syncing;
  }
  set({ sync: 'syncing' });
  syncing = (async () => {
    try {
      const merged = await syncLibrary(exportLibrary() as unknown as Library);
      await importLibrary(merged);
      set({ sync: 'idle', error: undefined });
    } catch (e) {
      onAuthError(e);
      set({ sync: 'error', error: (e as Error).message });
    }
    syncing = null;
    if (again) {
      again = false;
      void syncNow();
    }
  })();
  return syncing;
}

let libTimer: ReturnType<typeof setTimeout> | undefined;
export async function initAccount() {
  onLibraryChange(() => {
    if (!state.user) return;
    clearTimeout(libTimer);
    libTimer = setTimeout(() => void syncNow(), 4000);
  });
  if (!getToken()) return;
  try {
    const r = await me();
    write(UKEY, r.user);
    if (r.profile && r.profile.updated > state.profile.updated) {
      write(PKEY, r.profile);
      set({ user: r.user, profile: r.profile });
    } else set({ user: r.user });
    await syncNow();
  } catch (e) {
    onAuthError(e);
  }
}

// ------------------------------------------------------------------ Google button

interface Gis {
  accounts: {
    id: {
      initialize(o: Record<string, unknown>): void;
      renderButton(el: HTMLElement, o: Record<string, unknown>): void;
      prompt(): void;
      disableAutoSelect(): void;
    };
  };
}

let gis: Promise<Gis> | null = null;
function loadGis(): Promise<Gis> {
  gis ??= new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = 'https://accounts.google.com/gsi/client';
    s.async = true;
    s.onload = () => resolve((window as unknown as { google: Gis }).google);
    s.onerror = () => {
      gis = null;
      reject(new Error('Google non raggiungibile'));
    };
    document.head.appendChild(s);
  });
  return gis;
}

/** Renders the "Sign in with Google" button (web) into el. */
export async function renderGoogleButton(el: HTMLElement, lang: string, onError: (e: Error) => void) {
  const g = await loadGis();
  g.accounts.id.initialize({
    client_id: GOOGLE_CLIENT_ID,
    callback: (r: { credential: string }) => void completeLogin(r.credential).catch(onError),
    ux_mode: 'popup',
    use_fedcm_for_prompt: true,
  });
  g.accounts.id.renderButton(el, { theme: 'filled_black', size: 'large', shape: 'pill', text: 'continue_with', locale: lang });
}

/** Android app: Google's account sheet through Credential Manager. */
export const canNativeSignIn = () => inAndroidApp() && !!native()?.googleSignIn;
export async function nativeSignIn() {
  const r = await callNative('googleSignIn', undefined, 120000);
  if (!r.ok) throw new Error(r.body || 'login cancelled');
  await completeLogin(r.body);
}
