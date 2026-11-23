import { useSyncExternalStore } from 'react';
import type { Notation } from './lib/music';
import type { Instrument } from './lib/voicings';

export interface Settings {
  lang: 'it' | 'en';
  notation: Notation;
  instrument: Instrument;
  tuning: string;
  theme: 'auto' | 'light' | 'dark';
  a4: number;
  lefty: boolean;
}

const KEY = 'justchords-settings';
const defaults: Settings = {
  lang: typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('it') ? 'it' : 'en',
  notation: typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('it') ? 'it' : 'en',
  instrument: 'guitar',
  tuning: 'standard',
  theme: 'auto',
  a4: 440,
  lefty: false,
};

let current: Settings = (() => {
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem(KEY) ?? '{}') };
  } catch {
    return defaults;
  }
})();
const listeners = new Set<() => void>();

export function setSettings(patch: Partial<Settings>) {
  current = { ...current, ...patch };
  try {
    localStorage.setItem(KEY, JSON.stringify(current));
  } catch {
    /* blocked */
  }
  listeners.forEach((l) => l());
}

export const getSettings = () => current;

export function useSettings(): Settings {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => current,
  );
}
