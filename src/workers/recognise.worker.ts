// Whole-file chord recognition off the main thread.
import { Dsp, recognise } from '../audio/dsp';
import wasmUrl from '../../public/dsp.wasm?url';

self.onmessage = async (e: MessageEvent<{ samples: Float32Array; sampleRate: number }>) => {
  const dsp = await Dsp.load(wasmUrl);
  const segs = recognise(dsp, e.data.samples, e.data.sampleRate, { onProgress: (p) => self.postMessage({ progress: p }) });
  self.postMessage({ segments: segs });
};
