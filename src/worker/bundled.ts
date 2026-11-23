// Entry for `npm run deploy:bundle`: the same Worker, with the app files
// packed into the script instead of Workers Assets.
import worker from './index';
import { FILES } from './assets.gen';

const decoded = new Map<string, Uint8Array<ArrayBuffer>>();
const bytes = (path: string) => {
  let b = decoded.get(path);
  if (!b) {
    b = Uint8Array.from(atob(FILES[path].b64), (c) => c.charCodeAt(0));
    decoded.set(path, b);
  }
  return b;
};

const BASE: Record<string, string> = {
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'permissions-policy': 'camera=(), microphone=(self), geolocation=()',
};

function serve(req: Request): Response {
  const url = new URL(req.url);
  let path = decodeURIComponent(url.pathname);
  if (path.endsWith('/')) path += 'index.html';
  if (!FILES[path]) {
    // Files that don't exist are 404; app routes get the SPA shell.
    if (/\.[a-z0-9]+$/i.test(path)) return new Response('Not found', { status: 404, headers: BASE });
    path = '/index.html';
  }
  const f = FILES[path];
  const cache = path.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : path === '/sw.js' || path.endsWith('.html') ? 'no-cache' : 'public, max-age=3600';
  const body = req.method === 'HEAD' ? null : bytes(path);
  return new Response(body, { headers: { ...BASE, 'content-type': f.type, 'cache-control': cache } });
}

export default {
  fetch(req: Request, env: Record<string, unknown>, ctx: { waitUntil(p: Promise<unknown>): void }) {
    return worker.fetch(req, { ...env, ASSETS: { fetch: async (r: Request) => serve(r) } } as never, ctx);
  },
};
