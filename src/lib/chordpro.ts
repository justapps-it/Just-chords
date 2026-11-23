// Songs are stored as ChordPro text. This module parses ChordPro into lines
// to render, and turns pasted text (Ultimate Guitar, Accordi e Spartiti,
// chords-over-lyrics, ChordPro) into ChordPro.
import { formatChord, isChord, parseChord, parseKey, transposeChord, simplifyChord, type Chord, type Notation } from './music';

export interface Part {
  chord?: string;
  text: string;
}

export type Line =
  | { type: 'lyric'; parts: Part[] }
  | { type: 'chords'; chords: string[] }
  | { type: 'tab'; text: string }
  | { type: 'comment'; text: string }
  | { type: 'empty' };

export interface Section {
  kind: 'verse' | 'chorus' | 'bridge' | 'tab' | 'other';
  label?: string;
  lines: Line[];
}

export interface ParsedSong {
  title: string;
  artist: string;
  key?: string;
  capo: number;
  tempo?: number;
  time?: string;
  sections: Section[];
  meta: Record<string, string>;
}

const DIRECTIVE = /^\{\s*([a-z_]+)\s*(?::\s*(.*?))?\s*\}$/i;
const ALIASES: Record<string, string> = { t: 'title', st: 'subtitle', a: 'artist', c: 'comment', ci: 'comment', cb: 'comment', soc: 'start_of_chorus', eoc: 'end_of_chorus', sov: 'start_of_verse', eov: 'end_of_verse', sob: 'start_of_bridge', eob: 'end_of_bridge', sot: 'start_of_tab', eot: 'end_of_tab', comment_italic: 'comment', comment_box: 'comment', highlight: 'comment' };

export function parseChordPro(src: string): ParsedSong {
  const song: ParsedSong = { title: '', artist: '', capo: 0, sections: [], meta: {} };
  let current: Section = { kind: 'other', lines: [] };
  const push = () => {
    while (current.lines.length && current.lines[current.lines.length - 1].type === 'empty') current.lines.pop();
    if (current.lines.length || current.label) song.sections.push(current);
  };
  const start = (kind: Section['kind'], label?: string) => {
    push();
    current = { kind, label: label || undefined, lines: [] };
  };
  for (const raw of src.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.replace(/\s+$/, '');
    const d = DIRECTIVE.exec(line.trim());
    if (d) {
      const name = ALIASES[d[1].toLowerCase()] ?? d[1].toLowerCase();
      const value = d[2] ?? '';
      if (name === 'title') song.title = value;
      else if (name === 'artist' || name === 'subtitle') song.artist ||= value;
      else if (name === 'key') song.key = value;
      else if (name === 'capo') song.capo = Number.parseInt(value) || 0;
      else if (name === 'tempo') song.tempo = Number.parseFloat(value) || undefined;
      else if (name === 'time') song.time = value;
      else if (name === 'comment') {
        // A comment on its own between sections usually names the next one.
        if (!current.lines.some((l) => l.type !== 'empty')) start('other', value);
        else current.lines.push({ type: 'comment', text: value });
      } else if (name.startsWith('start_of_')) {
        const k = name.slice(9);
        start(k === 'chorus' || k === 'verse' || k === 'bridge' || k === 'tab' ? k : 'other', value || undefined);
      } else if (name.startsWith('end_of_')) start('other');
      else song.meta[name] = value;
      continue;
    }
    if (line.startsWith('#')) continue;
    if (current.kind === 'tab') {
      current.lines.push(line.trim() ? { type: 'tab', text: line } : { type: 'empty' });
      continue;
    }
    if (!line.trim()) {
      if (current.lines.length && current.lines[current.lines.length - 1].type !== 'empty') current.lines.push({ type: 'empty' });
      continue;
    }
    current.lines.push(parseLyricLine(line));
  }
  push();
  return song;
}

function parseLyricLine(line: string): Line {
  const parts: Part[] = [];
  const re = /\[([^\]]*)\]/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let pending: string | undefined;
  while ((m = re.exec(line))) {
    const text = line.slice(last, m.index);
    if (text || pending !== undefined) parts.push({ chord: pending, text });
    pending = m[1].trim();
    last = m.index + m[0].length;
  }
  const tail = line.slice(last);
  if (tail || pending !== undefined) parts.push({ chord: pending, text: tail });
  if (parts.length && parts.every((p) => !p.text.trim()) && parts.some((p) => p.chord)) {
    return { type: 'chords', chords: parts.filter((p) => p.chord).map((p) => p.chord!) };
  }
  return { type: 'lyric', parts };
}

/** All chords in order of appearance. */
export function songChords(song: ParsedSong): Chord[] {
  const out: Chord[] = [];
  for (const s of song.sections)
    for (const l of s.lines) {
      const syms = l.type === 'lyric' ? l.parts.map((p) => p.chord).filter(Boolean) : l.type === 'chords' ? l.chords : [];
      for (const sym of syms) {
        const c = parseChord(sym!);
        if (c) out.push(c);
      }
    }
  return out;
}

export interface RenderOptions {
  transpose: number;
  notation: Notation;
  flats: boolean;
  simplify: boolean;
}

/** Chord symbol as shown: transposed, simplified, in the chosen notation. */
export function displayChord(sym: string, o: RenderOptions): string {
  const c = parseChord(sym);
  if (!c) return sym;
  let x = transposeChord(c, o.transpose);
  if (o.simplify) x = simplifyChord(x);
  return formatChord(x, o.notation, o.flats);
}

// ---------------------------------------------------------------- import

const SECTION_WORDS =
  /^(intro|verse|strofa|chorus|ritornello|rit|pre[- ]?chorus|pre[- ]?rit(ornello)?|bridge|ponte|outro|finale|coda|solo|interlude|interludio|instrumental|strumentale|hook|refrain|special|tag)\b\.?\s*\d*\s*:?\s*/i;

const NOISE =
  /^(chords|accordi|tabs?|tab by|ver(sion)?\s*\d|author|autore|difficulty|difficolt[àa]|strumming|last update|rating|\d+(\.\d+)?\s*\/\s*5|views?|transpose|autoscroll|font|print|stampa|report|save|favorite|preferit|edit|playlist|.*ultimate-guitar\.com|.*accordiespartiti\.it|x$)/i;

/** True when every token of the line is a chord (or bar/repeat marks). */
export function isChordLine(line: string): boolean {
  const tokens = line
    .replace(/[|]/g, ' ')
    .replace(/\(\s*x?\d+\s*x?\s*\)/gi, ' ')
    .split(/\s+/)
    .map((t) => t.replace(/^[(\[]+|[)\],.]+$/g, ''))
    .filter((t) => t && !/^(x\d+|\d+x|%|-+|\/|n\.?c\.?|\.\.\.?|→|>)$/i.test(t));
  if (!tokens.length) return false;
  // Italian roots written in lower case ("la", "mi") are lyrics, not chords.
  return tokens.every((t) => isChord(t) && !/^(do|re|mi|fa|sol|la|si)/.test(t));
}

/** Converts pasted text into ChordPro. */
export function importText(input: string, hint: { title?: string; artist?: string; url?: string } = {}): string {
  let text = input.replace(/\r\n?/g, '\n').replace(/ /g, ' ').replace(/\t/g, '    ');
  // Already ChordPro?
  const inline = [...text.matchAll(/\[([^\]\s]{1,10})\][a-zà-ù]/gi)].filter((m) => isChord(m[1])).length;
  if (/^\s*\{\s*(title|t|soc|start_of_\w+|artist)\s*:?/im.test(text) || inline >= 2) {
    return text.trim() + '\n';
  }
  // Ultimate Guitar markup: [ch]G[/ch], [tab]…[/tab].
  text = text.replace(/\[\/?tab\]/g, '').replace(/\[ch\]([^[]*?)\[\/ch\]/g, '$1');

  const out: string[] = [];
  const meta: Record<string, string> = {};
  const lines = text.split('\n');
  let title = hint.title ?? '';
  let artist = hint.artist ?? '';
  let started = false;
  let open: string | null = null;

  const close = () => {
    if (open) out.push(`{end_of_${open}}`);
    open = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(/\s+$/, '');
    const t = line.trim();
    if (!t) {
      if (out.length && out[out.length - 1] !== '') out.push('');
      continue;
    }
    // Header info.
    const kv = /^(capo|key|tonalit[àa]|chiave|tuning|accordatura|tempo|bpm)\s*[:=]\s*(.+)$/i.exec(t);
    if (kv) {
      const k = kv[1].toLowerCase();
      if (k === 'capo') meta.capo = String(Number.parseInt(kv[2]) || 0);
      else if (k.startsWith('key') || k.startsWith('tonal') || k === 'chiave') {
        const key = parseKey(kv[2].trim().split(/\s/)[0]);
        if (key) meta.key = kv[2].trim().split(/\s/)[0];
      } else if (k === 'tempo' || k === 'bpm') meta.tempo = String(Number.parseFloat(kv[2]) || '');
      else meta.tuning = kv[2];
      continue;
    }
    if (/^capo\s+(\d+)/i.test(t) || /^capotasto\s+(al\s+)?(\d+)/i.test(t)) {
      meta.capo = t.match(/\d+/)![0];
      continue;
    }
    if (!started && NOISE.test(t)) continue;
    // Title lines before any music: "Song - Artist", "Song by Artist", "Song Chords by Artist".
    if (!started && !isChordLine(t) && !SECTION_WORDS.test(t.replace(/^\[/, ''))) {
      const m = /^(.+?)\s+(?:chords|accordi|tab)?\s*(?:by|di|-|–|—)\s+(.+)$/i.exec(t);
      if (!title && m) {
        title = m[1].trim();
        artist ||= m[2].trim();
        continue;
      }
      if (!title && t.length < 70 && !isChordLine(lines[i + 1] ?? '')) {
        title = t;
        continue;
      }
      if (title && !artist && t.length < 50 && !isChordLine(lines[i + 1] ?? '') && !(lines[i + 1] ?? '').trim()) {
        artist = t;
        continue;
      }
    }
    // Section header: "[Chorus]", "Rit.:", "Intro: G D Em C".
    const bracket = /^\[([^\]]+)\]\s*(.*)$/.exec(t);
    const sec = bracket && !isChord(bracket[1]) ? [bracket[1], bracket[2]] : SECTION_WORDS.test(t) ? [t.match(SECTION_WORDS)![0], t.slice(t.match(SECTION_WORDS)![0].length)] : null;
    if (sec && (!sec[1].trim() || isChordLine(sec[1]))) {
      started = true;
      close();
      const label = sec[0].replace(/[:.\s]+$/, '').trim();
      const kind = /chorus|rit/i.test(label) && !/pre/i.test(label) ? 'chorus' : /verse|strofa/i.test(label) ? 'verse' : /bridge|ponte/i.test(label) ? 'bridge' : null;
      if (kind) {
        out.push(`{start_of_${kind}: ${capitalize(label)}}`);
        open = kind;
      } else out.push(`{comment: ${capitalize(label)}}`);
      if (sec[1].trim()) out.push(chordsOnly(sec[1]));
      continue;
    }
    // Tablature.
    if (/^[eBGDAEbgdaH]?\s*[|:][-0-9hpbrx/\\~|. ]{6,}/.test(t)) {
      started = true;
      const block = [line];
      while (i + 1 < lines.length && /^[eBGDAEbgdaH]?\s*[|:][-0-9hpbrx/\\~|. ]{3,}/.test(lines[i + 1].trim())) block.push(lines[++i].replace(/\s+$/, ''));
      close();
      out.push('{start_of_tab}', ...block, '{end_of_tab}');
      continue;
    }
    if (isChordLine(t)) {
      started = true;
      const next = lines[i + 1]?.replace(/\s+$/, '') ?? '';
      if (next.trim() && !isChordLine(next) && !SECTION_WORDS.test(next.trim()) && !/^\[/.test(next.trim())) {
        out.push(merge(line, next));
        i++;
      } else out.push(chordsOnly(line));
      continue;
    }
    started = true;
    out.push(t);
  }
  close();
  while (out.length && out[out.length - 1] === '') out.pop();
  const head = [`{title: ${title || 'Senza titolo'}}`];
  if (artist) head.push(`{artist: ${artist}}`);
  if (meta.key) head.push(`{key: ${meta.key}}`);
  if (meta.capo && meta.capo !== '0') head.push(`{capo: ${meta.capo}}`);
  if (meta.tempo) head.push(`{tempo: ${meta.tempo}}`);
  if (meta.tuning) head.push(`{tuning: ${meta.tuning}}`);
  if (hint.url) head.push(`{source: ${hint.url}}`);
  return head.join('\n') + '\n\n' + out.join('\n').replace(/\n{3,}/g, '\n\n') + '\n';
}

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

function chordTokens(line: string): { col: number; sym: string }[] {
  const out: { col: number; sym: string }[] = [];
  const re = /\S+/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(line))) {
    const sym = m[0].replace(/^[(\[]+|[)\],.]+$/g, '');
    if (isChord(sym)) out.push({ col: m.index + (m[0].length - m[0].replace(/^[(\[]+/, '').length), sym: normaliseImported(sym) });
  }
  return out;
}

/** Italian sheets write "LAm", "DO7", "RE-": keep the symbol but tidy it. */
function normaliseImported(sym: string): string {
  const c = parseChord(sym)!;
  const it = /^(do|re|mi|fa|sol|la|si)/i.test(sym);
  return it ? formatChord(c, 'en') : sym;
}

function chordsOnly(line: string): string {
  return chordTokens(line)
    .map((c) => `[${c.sym}]`)
    .join(' ');
}

/** Chords above, lyrics below → inline ChordPro. */
export function merge(chordLine: string, lyric: string): string {
  const chords = chordTokens(chordLine);
  let text = lyric;
  const maxCol = Math.max(...chords.map((c) => c.col));
  if (text.length < maxCol) text = text.padEnd(maxCol, ' ');
  let out = '';
  let pos = 0;
  for (const c of chords) {
    out += text.slice(pos, c.col) + `[${c.sym}]`;
    pos = c.col;
  }
  out += text.slice(pos);
  return out.replace(/\s+$/, '').replace(/^ +(?=\[)/, '');
}

export function songMetaFromChordPro(src: string) {
  const p = parseChordPro(src);
  return { title: p.title || 'Senza titolo', artist: p.artist, key: p.key, capo: p.capo, tempo: p.tempo };
}

export type { Chord };
