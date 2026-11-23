# Just-Chords

Chords, tuner, metronome and stage mode in one open-source app, by [JustApps](https://www.justapps.it).
Live at **https://chords.justapps.it**, Android APK in the [releases](https://github.com/justapps-it/Just-Chords/releases).

- **Search + import**: search a song, open it on Ultimate Guitar or Accordi e Spartiti, copy the text with chords and paste it (or share the page to the app). The importer understands chords-over-lyrics, Ultimate Guitar `[ch]` markup, Italian notation (DO, RE, LAm, SOL7…) and ChordPro. Just-Chords never scrapes or copies content from those sites: it only imports what you paste.
- **Interactive sheet**: transpose, capo (with a suggested capo for easier shapes), simplify chords, C D E or Do Re Mi notation, font size, autoscroll.
- **Chord diagrams for any chord**: guitar (any tuning), ukulele and piano, generated from music theory, several voicings each, left-handed mode.
- **Stage mode**: setlists, full screen, autoscroll, Bluetooth page-turner pedals (↓/Space/PageDown scroll, →/← next/previous song), screen kept on.
- **Audio, all on your device (WebAssembly)**: live chord recognition from the microphone, chord recognition from an audio file (chroma + Viterbi), chromatic tuner (YIN) with alternate tunings, sample-accurate metronome with tap tempo.
- Songs are stored on your device (IndexedDB). Offline PWA. No account, no ads.

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
