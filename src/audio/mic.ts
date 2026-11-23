// Microphone input shared by the tuner and the chord listener.
import { Dsp } from './dsp';

let dspPromise: Promise<Dsp> | null = null;
export const getDsp = () => (dspPromise ??= Dsp.load('/dsp.wasm'));

export interface Mic {
  ctx: AudioContext;
  analyser: AnalyserNode;
  read(n: number): Float32Array;
  stop(): void;
}

export async function openMic(fftSize = 8192): Promise<Mic> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
  });
  const ctx = new AudioContext();
  await ctx.resume();
  const src = ctx.createMediaStreamSource(stream);
  const analyser = ctx.createAnalyser();
  analyser.fftSize = fftSize;
  analyser.smoothingTimeConstant = 0;
  src.connect(analyser);
  const buf = new Float32Array(fftSize);
  return {
    ctx,
    analyser,
    read(n: number) {
      analyser.getFloatTimeDomainData(buf);
      return buf.slice(fftSize - n);
    },
    stop() {
      stream.getTracks().forEach((t) => t.stop());
      void ctx.close();
    },
  };
}

/** Keeps the screen on (Wake Lock API, or the Android app bridge). */
export function keepAwake(on: boolean) {
  const bridge = (window as unknown as { JustChordsAndroid?: { keepScreenOn(b: boolean): void } }).JustChordsAndroid;
  if (bridge) return bridge.keepScreenOn(on);
  const w = window as unknown as { __wake?: WakeLockSentinel | null };
  if (on && 'wakeLock' in navigator && !w.__wake) {
    navigator.wakeLock
      .request('screen')
      .then((l) => (w.__wake = l))
      .catch(() => {});
  } else if (!on && w.__wake) {
    void w.__wake.release().catch(() => {});
    w.__wake = null;
  }
}
