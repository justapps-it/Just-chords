// End-to-end check in a real browser: node scripts/e2e.mjs [url]
// Fake microphone files: FAKE_DIR with chordG.wav and toneA.wav.
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const URL_ = process.argv[2] ?? 'http://localhost:4180';
const FAKE = process.env.FAKE_DIR;
const SHOTS = process.env.SHOTS ?? '.';
const ok = (c, m) => {
  if (!c) throw new Error('FAIL ' + m);
  console.log('✓', m);
};

const UG = `Wonderwall Chords by Oasis
Difficulty: intermediate
Capo: 2nd fret

[Intro]
Em7  G  Dsus4  A7sus4

[Verse 1]
Em7          G
Today is gonna be the day
         Dsus4                A7sus4
That they're gonna throw it back to you

[Chorus]
C               D              Em
Because maybe, you're gonna be the one that saves me
`;

async function browser(audio) {
  const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'];
  if (audio) args.push(`--use-file-for-fake-audio-capture=${audio}`);
  return chromium.launch({ args });
}

{
  const b = await browser();
  const ctx = await b.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', (e) => errors.push(e.message));
  await p.goto(URL_);
  await p.evaluate(() => localStorage.setItem('justchords-settings', JSON.stringify({ lang: 'it', notation: 'en' })));
  await p.reload();
  ok(await p.getByRole('heading', { name: /Accordi, accordatore/ }).isVisible(), 'home');
  await p.screenshot({ path: `${SHOTS}/chords-home.png` });
  await p.getByRole('button', { name: /Importa/ }).first().click();
  await p.locator('textarea').fill(UG);
  ok(await p.locator('.sheet.preview .ch').first().isVisible(), 'import preview');
  await p.getByRole('button', { name: 'Salva' }).click();
  await p.waitForSelector('.song-head h1');
  ok((await p.textContent('.song-head h1')) === 'Wonderwall', 'saved song title');
  ok((await p.textContent('.song-artist')) === 'Oasis', 'artist');
  ok(await p.locator('.capo-badge').isVisible(), 'capo badge');
  const chords = await p.locator('.sheet .ch').allTextContents();
  ok(chords.join(' ').startsWith('Em7 G Dsus4 A7sus4 Em7 G'), 'chords rendered: ' + chords.slice(0, 6).join(' '));
  await p.screenshot({ path: `${SHOTS}/chords-song.png`, fullPage: true });
  await p.getByRole('button', { name: '+1' }).click();
  ok((await p.locator('.sheet .ch').first().textContent()) === 'Fm7', 'transpose +1');
  await p.getByRole('button', { name: 'C D E' }).click();
  ok((await p.locator('.sheet .ch').first().textContent()) === 'Fam7', 'Italian notation');
  await p.getByRole('button', { name: 'Do Re Mi' }).click();
  await p.getByRole('button', { name: '−1' }).click();
  // Capo 0: shapes become the sounding chords (Em7 → F#m7).
  await p.locator('.toolbar select').selectOption('0');
  ok((await p.locator('.sheet .ch').first().textContent()) === 'F#m7', 'capo change keeps the pitch');
  ok((await p.locator('.strip .chord-card').count()) >= 5, 'chord diagrams strip');
  await p.locator('.sheet .ch').first().click();
  ok(await p.locator('.modal .chord-card.big svg').isVisible(), 'chord zoom');
  await p.getByRole('button', { name: 'Chiudi' }).click();
  await p.getByRole('button', { name: 'Palco' }).click();
  await p.waitForSelector('.song.stage');
  await p.keyboard.press('ArrowDown');
  ok(true, 'stage mode + pedal');
  await p.screenshot({ path: `${SHOTS}/chords-stage.png` });
  await p.keyboard.press('Escape');
  // Persistence.
  await p.goto(URL_ + '/#/');
  await p.reload();
  ok(await p.getByText('Wonderwall').isVisible(), 'library persists');
  // Dictionary + metronome.
  await p.getByRole('button', { name: /Dizionario/ }).click();
  ok((await p.locator('.dict-grid .chord-card').count()) > 3, 'dictionary voicings');
  await p.goto(URL_ + '/#/metronome');
  await p.getByRole('button', { name: /Avvia/ }).click();
  await p.waitForTimeout(1200);
  ok(await p.locator('.beat.on').count() === 1, 'metronome ticking');
  ok(!errors.length, 'no page errors ' + errors.join('; '));
  // Mobile.
  const m = await ctx.newPage();
  await m.setViewportSize({ width: 390, height: 844 });
  await m.goto(URL_);
  await m.getByText('Wonderwall').click();
  await m.waitForSelector('.song-head');
  const overflow = await m.evaluate(() => document.documentElement.scrollWidth - innerWidth);
  ok(overflow <= 0, 'mobile no horizontal scroll (' + overflow + ')');
  await m.screenshot({ path: `${SHOTS}/chords-mobile.png` });
  await m.locator('.sec-chorus').scrollIntoViewIfNeeded();
  await m.screenshot({ path: `${SHOTS}/chords-mobile2.png` });
  await b.close();
}

if (FAKE) {
  for (const [file, path, check] of [
    ['toneA.wav', '/#/tuner', async (p) => {
      await p.waitForFunction(() => /A2/.test(document.querySelector('.tuner-note')?.textContent ?? ''), null, { timeout: 15000 });
      const cents = await p.textContent('.tuner-cents');
      ok(/-1[0-4] cent/.test(cents), 'tuner: A2 ' + cents);
    }],
    ['chordG.wav', '/#/listen', async (p) => {
      await p.waitForFunction(() => document.querySelector('.listen-chord')?.textContent === 'G', null, { timeout: 15000 });
      ok(true, 'live chord recognition: G');
    }],
  ]) {
    const b = await browser(`${FAKE}/${file}`);
    const p = await b.newPage();
    await p.goto(URL_);
    await p.evaluate(() => localStorage.setItem('justchords-settings', JSON.stringify({ lang: 'it', notation: 'en' })));
    await p.goto(URL_ + path);
    await p.reload();
    await p.getByRole('button', { name: /microfono/ }).click();
    await check(p);
    await p.screenshot({ path: `${SHOTS}/chords-${file}.png` });
    await b.close();
  }
}
console.log('all passed');
