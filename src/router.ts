import { useSyncExternalStore } from 'react';

// Hash routing: works offline, inside the Android app and on any static host.
const listeners = new Set<() => void>();
window.addEventListener('hashchange', () => listeners.forEach((l) => l()));

export function go(path: string, replace = false) {
  const url = `#${path}`;
  if (replace) history.replaceState(null, '', url);
  else history.pushState(null, '', url);
  listeners.forEach((l) => l());
}

export function useRoute(): string[] {
  const hash = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      window.addEventListener('popstate', l);
      return () => {
        listeners.delete(l);
        window.removeEventListener('popstate', l);
      };
    },
    () => location.hash,
  );
  return hash.replace(/^#\/?/, '').split('?')[0].split('/').filter(Boolean).map(decodeURIComponent);
}

export function routeQuery(): URLSearchParams {
  return new URLSearchParams(location.hash.split('?')[1] ?? location.search);
}
