# Just-Chords

Chords, tuner, metronome and stage mode in one open-source app, by [JustApps](https://www.justapps.it).
Live at **https://chords.justapps.it**, Android APK in the [releases](https://github.com/justapps-it/Just-Chords/releases).

- **Find chords**: search a song and get the versions on Ultimate Guitar and Accordi e Spartiti (rating, votes, level); the one you pick is opened and saved with a link to its source. The Android app downloads the page from the phone itself; the web app goes through the Worker. Pasting text or a ChordPro file still works: the importer understands chords-over-lyrics, Ultimate Guitar `[ch]` markup, Italian notation (DO, RE, LAm, SOL7…) and ChordPro.
- **Links**: paste or share a Spotify, YouTube or YouTube Music link to get the chords of that song.
- **Listen while you play**: a YouTube player next to the sheet (found automatically with a YouTube Data API key, or from a pasted link).
- **Levels**: every song gets a difficulty (easy / intermediate / advanced) from its chords with the best capo, barre chords, how many chords and how often they change.
- **For you**: recommendations from a taste profile (genres, level, artists learnt from what you import, open, star, 👍/👎), related artists and genre seeds from Deezer, and a curated list of songs by level.
- **Google account (optional)**: library, setlists and profile synced across devices.
- **Interactive sheet**: transpose, capo (with a suggested capo for easier shapes), simplify chords, C D E or Do Re Mi notation, font size, autoscroll.
- **Chord diagrams for any chord**: guitar (any tuning), ukulele and piano, generated from music theory, several voicings each, left-handed mode.
- **Stage mode**: setlists, full screen, autoscroll, Bluetooth page-turner pedals (↓/Space/PageDown scroll, →/← next/previous song), screen kept on.
- **Audio, all on your device (WebAssembly)**: live chord recognition from the microphone, chord recognition from an audio file (chroma + Viterbi), chromatic tuner (YIN) with alternate tunings, sample-accurate metronome with tap tempo.
- Songs are stored on your device (IndexedDB), and in your account when signed in. Offline PWA. No ads.

## Backend

`src/worker/index.ts` is a Cloudflare Worker in front of the static app: `/api/search`, `/api/song`, `/api/link`, `/api/yt`, `/api/recs`, `/api/auth/google`, `/api/me`, `/api/profile`, `/api/sync`. Accounts and libraries are in the `USERS` KV namespace. Configuration:

- `GOOGLE_CLIENT_ID` (var, in `wrangler.jsonc`): OAuth web client. Authorized JavaScript origin: `https://chords.justapps.it`.
- `SESSION_SECRET` (secret): signs session tokens. `openssl rand -hex 32 | npx wrangler secret put SESSION_SECRET`
- `YOUTUBE_API_KEY` (secret, optional): YouTube Data API v3 key to find the video of a song automatically.
- Android: an Android OAuth client for `it.justapps.justchords` with the signing certificate SHA-1, in the same Google Cloud project.

`npm run deploy` uses Workers Assets; `npm run deploy:bundle` packs the app into the Worker script instead (for environments that can't use the assets upload).

## Development

```bash
npm install
npm run dev       # dev server
npm test          # unit tests (music theory, parser, voicings, WASM DSP)
npm run build     # AssemblyScript → dsp.wasm, type check, Vite build
npm run deploy    # Cloudflare Workers static assets (chords.justapps.it)
node scripts/e2e.mjs http://localhost:4173   # browser end-to-end test
```

The DSP is written in AssemblyScript (`assembly/index.ts`) and compiled to `public/dsp.wasm`.

## License

GPL-3.0-or-later.
