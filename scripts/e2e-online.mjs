// End-to-end check of the online features on the live site:
// node scripts/e2e-online.mjs [url]  (SHOTS=dir for screenshots)
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const URL_ = process.argv[2] ?? 'https://chords.justapps.it';
const SHOTS = process.env.SHOTS ?? '.';
const ok = (c, m) => {
  if (!c) throw new Error('FAIL ' + m);
  console.log('✓', m);
};

const b = await chromium.launch();
const ctx = await b.newContext({ viewport: { width: 1280, height: 900 }, locale: 'it-IT' });
const p = await ctx.newPage();
const errors = [];
p.on('pageerror', (e) => errors.push(String(e)));

// 1. Search → Ultimate Guitar + Accordi e Spartiti results → open → saved song.
await p.goto(URL_ + '/#/', { waitUntil: 'domcontentloaded' });
await p.fill('.searchbox input', 'albachiara');
await p.click('.search-sites .btn.primary');
await p.waitForSelector('.result', { timeout: 30000 });
const titles = await p.$$eval('.result', (els) => els.map((e) => e.querySelector('.song-title').textContent + ' / ' + e.querySelector('.song-sub').textContent));
ok(titles.filter((x) => x === 'Albachiara / Vasco Rossi').length === 1, `one result per song (${titles.join(', ')})`);
await p.screenshot({ path: `${SHOTS}/find.png` });
// Accordi e Spartiti, from a pasted link.
await p.goto(URL_ + '/#/find?url=' + encodeURIComponent('https://www.accordiespartiti.it/accordi/italiani/rossi-vasco/albachiara/'), { waitUntil: 'domcontentloaded' });
await p.waitForSelector('.sheet', { timeout: 30000 });
ok((await p.textContent('h1')).includes('Albachiara'), 'Accordi e Spartiti song opened');
ok((await p.textContent('.song-artist')).includes('Vasco Rossi'), 'artist name in the right order');
ok(await p.isVisible('.song-meta .level'), 'difficulty badge shown');
ok((await p.$$('.sheet .ch')).length > 10, 'Italian chords converted and placed');

// 2. Player: no API key → paste a YouTube link → embedded video.
await p.click('text=▶ Ascolta');
await p.waitForSelector('.player input', { timeout: 15000 });
await p.fill('.player input', 'https://youtu.be/6hzrDeceEKc');
await p.click('.player button:has-text("OK")');
await p.waitForSelector('.player iframe');
ok((await p.getAttribute('.player iframe', 'src')).includes('youtube-nocookie.com/embed/6hzrDeceEKc'), 'YouTube player embedded');
await p.screenshot({ path: `${SHOTS}/player.png` });

// 3. Paste a YouTube Music link → title/artist → chords results.
await p.goto(URL_ + '/#/', { waitUntil: 'domcontentloaded' });
await p.fill('.searchbox input', 'https://music.youtube.com/watch?v=bx1Bh8ZvH84');
await p.click('.search-sites .btn.primary');
await p.waitForSelector('.link-info', { timeout: 30000 });
ok((await p.textContent('.link-info')).includes('Wonderwall'), 'YouTube Music link resolved');
await p.waitForSelector('.result', { timeout: 30000 });
await p.click('.result:has(.src-ug)');
await p.waitForSelector('.sheet', { timeout: 30000 });
ok((await p.textContent('h1')).includes('Wonderwall'), 'Ultimate Guitar song opened from the link');
await p.click('text=▶ Ascolta');
await p.waitForSelector('.player iframe', { timeout: 10000 });
ok((await p.getAttribute('.player iframe', 'src')).includes('bx1Bh8ZvH84'), 'video from the pasted link kept for the song');

// 4. Spotify link.
await p.goto(URL_ + '/#/find?url=' + encodeURIComponent('https://open.spotify.com/track/1qPbGZqppFwLwcBC1JQ6Vr'), { waitUntil: 'domcontentloaded' });
await p.waitForSelector('.link-info', { timeout: 30000 });
ok((await p.textContent('.link-info')).includes('Oasis'), 'Spotify link resolved');

// 5. Profile: genres + level → recommendations on the home page.
await p.goto(URL_ + '/#/profile', { waitUntil: 'domcontentloaded' });
await p.click('.chips button:has-text("Cantautori")');
await p.click('.chips button:has-text("Rock")');
await p.click('.seg.wide button:has-text("Facile")');
ok(!(await p.isVisible('.gbtn')), 'Google sign-in hidden until the 14+ box is ticked');
await p.click('.consent input');
await p.waitForSelector('.gbtn');
ok(await p.isVisible('.gbtn'), 'Google sign-in offered after consent');
await p.screenshot({ path: `${SHOTS}/profile.png` });
await p.goto(URL_ + '/#/', { waitUntil: 'domcontentloaded' });
await p.waitForSelector('.rec-card .rec-title', { timeout: 30000 });
const recs = await p.$$eval('.rec-card', (els) => els.map((e) => e.querySelector('.rec-title')?.textContent + ' · ' + e.querySelector('.rec-reason')?.textContent));
ok(recs.length >= 8, `recommendations shown (${recs.slice(0, 5).join(' | ')})`);
const first = recs[0].split(' · ')[0];
await p.locator('.rec-card').first().locator('.rec-fb button').nth(1).click();
const after = await p.$$eval('.rec-card .rec-title', (els) => els.map((e) => e.textContent));
ok(!after.includes(first), '👎 removes a recommendation');
const prof = await p.evaluate(() => JSON.parse(localStorage.getItem('justchords-profile')));
ok(prof.genres.includes('cantautori') && prof.level === 1 && prof.artists['oasis'] > 0 && prof.disliked.length === 1, 'profile learnt genres, level, artists, dislikes ' + JSON.stringify(prof));
await p.screenshot({ path: `${SHOTS}/recs.png` });

// 6. Mobile layout.
await p.setViewportSize({ width: 390, height: 844 });
await p.reload();
await p.waitForSelector('.rec-card .rec-title', { timeout: 30000 });
ok(!(await p.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)), 'no horizontal scroll on mobile');
await p.screenshot({ path: `${SHOTS}/mobile.png` });

// 7. API rejects what it should.
const r = await p.evaluate(async () => [
  (await fetch('/api/song?url=https://example.com/')).status,
  (await fetch('/api/me')).status,
  (await fetch('/api/sync', { method: 'POST', body: '{}', headers: { authorization: 'Bearer abc.def' } })).status,
  (await fetch('/api/auth/google', { method: 'POST', body: JSON.stringify({ credential: 'x.y.z' }) })).status,
  (await fetch('/api/account', { method: 'DE' + 'LETE' })).status,
]);
ok(r.join() === '400,401,401,400,401', `API guards: bad site, login, consent (${r})`);
await p.goto(URL_ + '/privacy', { waitUntil: 'domcontentloaded' });
await p.waitForSelector('.legal h2');
ok((await p.$$('.legal h2')).length >= 8, 'privacy policy at /privacy');

ok(!errors.length, 'no page errors ' + errors.join('; '));
await b.close();
console.log('all passed');
