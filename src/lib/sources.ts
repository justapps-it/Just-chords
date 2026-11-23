// Ultimate Guitar and Accordi e Spartiti: search result pages and song pages
// → plain data. Shared by the Cloudflare Worker (/api/*) and the Android app,
// which fetches from the phone itself. Only these hosts are ever fetched.

export type SourceId = 'ug' | 'aes';

export interface SearchResult {
  source: SourceId;
  title: string;
  artist: string;
  url: string;
  kind?: string;
  rating?: number;
  votes?: number;
  /** 1 easy · 2 intermediate · 3 advanced, when the site says so. */
  level?: 1 | 2 | 3;
  key?: string;
}

export interface FetchedSong {
  source: SourceId;
  url: string;
  title: string;
  artist: string;
  /** Text to give importText(): chords over lyrics or UG [ch] markup. */
  text: string;
  key?: string;
  capo?: number;
  level?: 1 | 2 | 3;
}

const HOSTS: Record<string, SourceId> = {
  'www.ultimate-guitar.com': 'ug',
  'tabs.ultimate-guitar.com': 'ug',
  'ultimate-guitar.com': 'ug',
  'www.accordiespartiti.it': 'aes',
  'accordiespartiti.it': 'aes',
};

export function sourceOf(url: string): SourceId | null {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' ? HOSTS[u.hostname] ?? null : null;
  } catch {
    return null;
  }
}

export const searchUrl = (source: SourceId, q: string) =>
  source === 'ug'
    ? `https://www.ultimate-guitar.com/search.php?search_type=title&value=${encodeURIComponent(q)}`
    : `https://www.accordiespartiti.it/?s=${encodeURIComponent(q)}`;

export const aesApiSearchUrl = (q: string) => `https://www.accordiespartiti.it/wp-json/wp/v2/search?search=${encodeURIComponent(q)}&per_page=12&subtype=post`;

// ------------------------------------------------------------------ helpers

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', egrave: 'è', eacute: 'é', agrave: 'à', ograve: 'ò', ugrave: 'ù', igrave: 'ì', rsquo: '’', lsquo: '‘', ldquo: '“', rdquo: '”', hellip: '…', ndash: '–', mdash: '—' };

export function decodeEntities(s: string): string {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e: string) => {
    if (e[0] === '#') {
      const n = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(n) ? String.fromCodePoint(n) : m;
    }
    return ENTITIES[e.toLowerCase()] ?? m;
  });
}

/** HTML fragment → text, keeping line breaks and the spacing of <pre>. */
export function htmlToText(html: string): string {
  return decodeEntities(
    html
      .replace(/<(script|style|noscript)[\s\S]*?<\/\1>/gi, '')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/(p|div|h[1-6]|li|tr)>/gi, '\n')
      .replace(/<[^>]+>/g, ''),
  )
    .replace(/\r\n?/g, '\n')
    .replace(/ /g, ' ')
    .replace(/[ \t]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

const UG_LEVEL: Record<string, 1 | 2 | 3> = { novice: 1, beginner: 1, intermediate: 2, advanced: 3 };

/** The JSON state Ultimate Guitar embeds in its pages. */
export function ugStore(html: string): any {
  const m = html.match(/class="js-store"\s+data-content="([^"]*)"/) ?? html.match(/data-content="([^"]*)"\s+class="js-store"/);
  if (m) {
    try {
      return JSON.parse(decodeEntities(m[1]));
    } catch {
      /* fall through */
    }
  }
  const w = html.match(/window\.UGAPP\.store\.page\s*=\s*(\{[\s\S]*?\});\s*<\/script>/);
  if (w) {
    try {
      return { store: { page: JSON.parse(w[1]) } };
    } catch {
      /* nothing */
    }
  }
  return null;
}

const titleCase = (s: string) => s.replace(/(^|[\s-])(\p{L})/gu, (_, a, b: string) => a + b.toUpperCase());

// ------------------------------------------------------------------ search

export function parseUgSearch(html: string): SearchResult[] {
  const store = ugStore(html);
  const data = store?.store?.page?.data;
  const list: any[] = [...(data?.results ?? []), ...(data?.other_tabs ?? [])];
  const out: SearchResult[] = [];
  for (const r of list) {
    const kind = String(r.type ?? '');
    if (!r.tab_url || r.marketing_type || !/chords/i.test(kind)) continue;
    out.push({
      source: 'ug',
      title: String(r.song_name ?? ''),
      artist: String(r.artist_name ?? ''),
      url: String(r.tab_url),
      kind: kind + (r.version > 1 ? ` v${r.version}` : ''),
      rating: typeof r.rating === 'number' && r.votes > 0 ? Math.round(r.rating * 10) / 10 : undefined,
      votes: typeof r.votes === 'number' ? r.votes : undefined,
      level: UG_LEVEL[String(r.difficulty ?? '').toLowerCase()],
      key: r.tonality_name || undefined,
    });
  }
  // Guitar before ukulele, then the best versions: rating weighted by votes.
  const uke = (r: SearchResult) => (/ukulele/i.test(r.kind ?? '') ? 1 : 0);
  return out.sort((a, b) => uke(a) - uke(b) || (b.rating ?? 0) * Math.log10(10 + (b.votes ?? 0)) - (a.rating ?? 0) * Math.log10(10 + (a.votes ?? 0)));
}

// Accordi e Spartiti files artists as "surname-name" (rossi-vasco): put
// known first names back in front.
const FIRST_NAMES = new Set(
  'vasco luciano lucio francesco fabrizio franco gianni gino giorgio giorgia laura elisa tiziano cesare marco max eros renato rino adriano ornella loredana fiorella edoardo antonello claudio enrico ivano umberto biagio paolo pino riccardo roberto sergio carmen gigi alessandra annalisa arisa emma irene giusy noemi alex carl ed john paul george bob david michael neil bruce eric elton james jimmy johnny tom taylor ben billie lewis luca daniele davide diodato giuliano filippo samuele levante gianna mia patty anna nada niccolo francesca alessandro angelo nino vinicio carmen jovanotti'.split(' '),
);
export function aesArtist(slug: string): string {
  const w = slug.split('-').filter(Boolean);
  if (w.length >= 2 && FIRST_NAMES.has(w[w.length - 1])) w.unshift(w.pop()!);
  return titleCase(w.join(' '));
}

/** Drops results that don't contain the words searched for. */
export function relevant<T extends { title: string; artist: string }>(list: T[], q: string): T[] {
  const norm = (x: string) => x.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const words = norm(q).split(/[^a-z0-9]+/).filter((w) => w.length > 2);
  return list.filter((r) => {
    const hay = norm(`${r.title} ${r.artist}`);
    return words.every((w) => hay.includes(w));
  });
}

/** "Albachiara – Vasco Rossi", "Accordi Albachiara Vasco Rossi", or the URL slug. */
function aesTitle(raw: string, url: string): { title: string; artist: string } {
  const t = decodeEntities(raw.replace(/<[^>]+>/g, ' '))
    .replace(/\s+/g, ' ')
    .replace(/^\s*accordi\s+(di\s+|per\s+)?/i, '')
    .replace(/\s*[-–|]\s*accordi\s*e\s*spartiti.*$/i, '')
    .trim();
  const parts = t.split(/\s+[-–—]\s+/);
  if (parts.length >= 2) return { title: parts[0], artist: parts.slice(1).join(' - ') };
  const slug = new URL(url).pathname.split('/').filter(Boolean);
  if (slug.length >= 2) {
    const artist = aesArtist(slug[slug.length - 2]);
    return { title: t || titleCase(slug[slug.length - 1].replace(/-/g, ' ')), artist };
  }
  return { title: t, artist: '' };
}

const isAesSong = (url: string) => {
  try {
    const u = new URL(url);
    return /accordiespartiti\.it$/.test(u.hostname) && /^\/accordi\/.+\/.+\/.+/.test(u.pathname);
  } catch {
    return false;
  }
};

/** WordPress REST search JSON. */
export function parseAesApi(json: unknown): SearchResult[] {
  if (!Array.isArray(json)) return [];
  return json
    .filter((r: any) => typeof r?.url === 'string' && isAesSong(r.url))
    .map((r: any) => ({ source: 'aes' as const, url: r.url, kind: 'Accordi', ...aesTitle(String(r.title ?? ''), r.url) }));
}

/** Search result page (fallback when the REST API is off). */
export function parseAesSearch(html: string): SearchResult[] {
  const seen = new Set<string>();
  const out: SearchResult[] = [];
  for (const m of html.matchAll(/<a\s[^>]*href="(https:\/\/www\.accordiespartiti\.it\/accordi\/[^"#?]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    const url = m[1].endsWith('/') ? m[1] : m[1] + '/';
    const label = m[2].replace(/<[^>]+>/g, '').trim();
    if (!isAesSong(url) || seen.has(url) || !label || /^(leggi|continua|read)/i.test(label)) continue;
    seen.add(url);
    out.push({ source: 'aes', url, kind: 'Accordi', ...aesTitle(label, url) });
  }
  return out.slice(0, 15);
}

// ------------------------------------------------------------------ songs

export function parseUgTab(html: string, url: string): FetchedSong | null {
  const data = ugStore(html)?.store?.page?.data;
  const content: string | undefined = data?.tab_view?.wiki_tab?.content;
  if (!content) return null;
  const tab = data.tab ?? {};
  const meta = data.tab_view?.meta ?? {};
  const capo = Number(meta.capo ?? 0) || undefined;
  return {
    source: 'ug',
    url,
    title: String(tab.song_name ?? ''),
    artist: String(tab.artist_name ?? ''),
    text: content.replace(/\r\n?/g, '\n'),
    key: tab.tonality_name || meta.tonality || undefined,
    capo,
    level: UG_LEVEL[String(meta.difficulty ?? tab.difficulty ?? '').toLowerCase()],
  };
}

export function parseAesSong(html: string, url: string): FetchedSong | null {
  // The sheet is in <pre> blocks; otherwise the article body.
  const pres = [...html.matchAll(/<pre[^>]*>([\s\S]*?)<\/pre>/gi)].map((m) => htmlToText(m[1]));
  let text = pres.sort((a, b) => b.length - a.length)[0] ?? '';
  if (text.length < 40) {
    const body = html.match(/<div[^>]+class="[^"]*(entry-content|post-content|td-post-content|the-content)[^"]*"[^>]*>([\s\S]*?)<\/(article|section)>/i);
    text = body ? htmlToText(body[2]) : '';
  }
  if (text.length < 20) return null;
  const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i)?.[1] ?? html.match(/<title>([\s\S]*?)<\/title>/i)?.[1] ?? '';
  const { title, artist } = aesTitle(h1, url);
  // Cut site furniture after the sheet.
  text = text.split(/\n(?:Condividi|Ti potrebbe interessare|Articoli correlati|Commenti)\b/i)[0];
  return { source: 'aes', url, title, artist, text };
}

export function parseSong(html: string, url: string): FetchedSong | null {
  const s = sourceOf(url);
  return s === 'ug' ? parseUgTab(html, url) : s === 'aes' ? parseAesSong(html, url) : null;
}

// ------------------------------------------------------------------ links

export interface LinkInfo {
  kind: 'youtube' | 'spotify' | 'chords';
  title?: string;
  artist?: string;
  youtubeId?: string;
  url: string;
}

export function youtubeId(url: string): string | null {
  try {
    const u = new URL(url);
    if (/(^|\.)youtu\.be$/.test(u.hostname)) return u.pathname.slice(1).split('/')[0] || null;
    if (/(^|\.)youtube(-nocookie)?\.com$/.test(u.hostname)) {
      if (u.searchParams.get('v')) return u.searchParams.get('v');
      const m = u.pathname.match(/^\/(?:embed|shorts|live|v)\/([\w-]{6,})/);
      return m?.[1] ?? null;
    }
  } catch {
    /* not a URL */
  }
  return null;
}

export function linkKind(url: string): LinkInfo['kind'] | null {
  if (sourceOf(url)) return 'chords';
  if (youtubeId(url)) return 'youtube';
  try {
    const u = new URL(url);
    if (u.hostname === 'open.spotify.com' && /\/track\//.test(u.pathname)) return 'spotify';
  } catch {
    /* not a URL */
  }
  return null;
}

/** "Oasis - Wonderwall (Official Video)" / channel "Oasis - Topic" → title and artist. */
export function splitVideoTitle(title: string, channel = ''): { title: string; artist: string } {
  const clean = title
    .replace(/\s*[([](official|ufficiale|lyrics?|testo|audio|video|videoclip|hd|4k|remaster(ed)?|live)[^)\]]*[)\]]/gi, '')
    .replace(/\s*\|.*$/, '')
    .replace(/\s+(ft\.|feat\.).*$/i, '')
    .trim();
  const ch = channel.replace(/\s*-\s*Topic$/i, '').replace(/VEVO$/i, '').trim();
  const parts = clean.split(/\s+[-–—]\s+/);
  if (parts.length >= 2) return { artist: parts[0].trim(), title: parts.slice(1).join(' - ').replace(/^["“]|["”]$/g, '').trim() };
  return { title: clean.replace(/^["“]|["”]$/g, ''), artist: ch };
}

/** Spotify track page → title and artist from its meta tags. */
export function parseSpotify(html: string): { title: string; artist: string } | null {
  const og = (p: string) => decodeEntities(html.match(new RegExp(`<meta[^>]+property="og:${p}"[^>]+content="([^"]*)"`, 'i'))?.[1] ?? '');
  const title = og('title');
  if (!title) return null;
  // og:description: "Oasis · (What's The Story) Morning Glory? · Song · 1995"
  let artist = og('description').split(' · ')[0] ?? '';
  const t = decodeEntities(html.match(/<title>([^<]*)<\/title>/i)?.[1] ?? '');
  const by = t.match(/ - (?:song(?: and lyrics)?|canzone(?: e testo)?) (?:by|di) (.+?) \| Spotify/i);
  if (by) artist = by[1];
  return { title, artist };
}

// ------------------------------------------------------------------ one per song

const keyOf = (s: string) =>
  s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\s*[([].*?[)\]]/g, '')
    .replace(/\b(the|feat|ft)\b.*$/g, (m) => (m.startsWith('the') ? m : ''))
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();

const quality = (r: SearchResult) => (r.rating ?? 3.5) * Math.log10(10 + (r.votes ?? 0));

/**
 * One result per song: the best guitar version by the original artist.
 * Ukulele and lower-rated versions are dropped, the same song on Accordi e
 * Spartiti is dropped when Ultimate Guitar has it, and covers (same title,
 * another artist with far fewer votes) are dropped too.
 */
export function onePerSong(list: SearchResult[]): SearchResult[] {
  const guitar = list.filter((r) => !/ukulele|bass|tab/i.test(r.kind ?? ''));
  const groups = new Map<string, { best: SearchResult; votes: number; order: number }>();
  guitar.forEach((r, order) => {
    const k = `${keyOf(r.title)}|${keyOf(r.artist).split(' ').sort().join(' ')}`;
    const g = groups.get(k);
    if (!g) groups.set(k, { best: r, votes: r.votes ?? 0, order });
    else {
      g.votes += r.votes ?? 0;
      // Ultimate Guitar (rated) wins over Accordi e Spartiti, then the best rated.
      if ((r.source === 'ug' && g.best.source !== 'ug') || (r.source === g.best.source && quality(r) > quality(g.best))) g.best = r;
    }
  });
  const byTitle = new Map<string, number>();
  for (const [k, g] of groups) byTitle.set(k.split('|')[0], Math.max(byTitle.get(k.split('|')[0]) ?? 0, g.votes));
  const topAll = Math.max(0, ...[...groups.values()].map((g) => g.votes));
  return [...groups.entries()]
    .filter(([k, g]) => {
      if (/^misc\b|mashup/i.test(g.best.artist)) return false;
      const top = byTitle.get(k.split('|')[0]) ?? 0;
      // A cover: same title, a tenth of the votes.
      if (top >= 50 && g.votes * 10 < top) return false;
      // Obscure tabs riding on a famous song's words ("Oasis By Wonderwall").
      if (topAll >= 1000 && g.best.source === 'ug' && g.votes * 100 < topAll) return false;
      return true;
    })
    .sort((a, b) => a[1].order - b[1].order)
    .map(([, g]) => ({ ...g.best, votes: g.votes || g.best.votes }));
}
