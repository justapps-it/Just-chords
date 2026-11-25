// A whole recording in memory for "Analizza": from the microphone or from a
// browser tab's audio (screen sharing with "share audio", Chrome desktop).
// Kept at ≤ 24 kHz mono, up to maxSeconds; never uploaded except the clips
// sent for lyrics when the user turns that on.

export interface Recorder {
  rate: number;
  /** Last n samples, for the live chord. */
  tail(n: number): Float32Array;
  samples(): Float32Array;
  seconds(): number;
  level(): number;
  full(): boolean;
  stop(): void;
  /** Called when the source ends by itself (tab sharing stopped from the browser bar). */
  onEnded?: () => void;
}

function record(stream: MediaStream, maxSeconds: number): Recorder {
  const ctx = new AudioContext();
  void ctx.resume();
  const src = ctx.createMediaStreamSource(stream);
  const factor = Math.max(1, Math.floor(ctx.sampleRate / 22050));
  const rate = ctx.sampleRate / factor;
  const max = Math.floor(maxSeconds * rate);
  const chunks: Float32Array[] = [];
  let length = 0;
  let level = 0;
  // ScriptProcessor is old but works everywhere, without a separate worklet file.
  const proc = ctx.createScriptProcessor(4096, 1, 1);
  proc.onaudioprocess = (e) => {
    if (length >= max) return;
    const x = e.inputBuffer.getChannelData(0);
    const out = new Float32Array(Math.floor(x.length / factor));
    let peak = 0;
    for (let i = 0; i < out.length; i++) {
      let s = 0;
      for (let k = 0; k < factor; k++) s += x[i * factor + k];
      out[i] = s / factor;
      peak = Math.max(peak, Math.abs(out[i]));
    }
    level = peak;
    const keep = out.subarray(0, Math.min(out.length, max - length));
    chunks.push(keep);
    length += keep.length;
  };
  const mute = ctx.createGain();
  mute.gain.value = 0;
  src.connect(proc);
  proc.connect(mute).connect(ctx.destination);
  const rec: Recorder = {
    rate,
    tail(n) {
      const out = new Float32Array(n);
      let filled = 0;
      for (let c = chunks.length - 1; c >= 0 && filled < n; c--) {
        const ch = chunks[c];
        const take = Math.min(ch.length, n - filled);
        out.set(ch.subarray(ch.length - take), n - filled - take);
        filled += take;
      }
      return out;
    },
    samples() {
      const out = new Float32Array(length);
      let o = 0;
      for (const c of chunks) {
        out.set(c, o);
        o += c.length;
      }
      return out;
    },
    seconds: () => length / rate,
    level: () => level,
    full: () => length >= max,
    stop() {
      proc.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      void ctx.close();
    },
  };
  stream.getAudioTracks()[0]?.addEventListener('ended', () => rec.onEnded?.());
  return rec;
}

export async function recordMic(maxSeconds = 360): Promise<Recorder> {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } });
  return record(stream, maxSeconds);
}

export const canRecordTab = () => typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getDisplayMedia && !/Android|iPhone|iPad/i.test(navigator.userAgent);

/** The audio of a browser tab (this one with the video, by default). */
export async function recordTab(maxSeconds = 360): Promise<Recorder> {
  const stream = await navigator.mediaDevices.getDisplayMedia({
    video: true,
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    preferCurrentTab: true,
    selfBrowserSurface: 'include',
    systemAudio: 'include',
  } as DisplayMediaStreamOptions);
  if (!stream.getAudioTracks().length) {
    stream.getTracks().forEach((t) => t.stop());
    throw new Error('no-audio');
  }
  // The video track stays open (stopping it can end the sharing); only the sound is read.
  return record(stream, maxSeconds);
}

/** An audio or video file → mono samples. */
export async function decodeFile(f: File): Promise<{ samples: Float32Array; rate: number }> {
  const ctx = new AudioContext();
  try {
    const audio = await ctx.decodeAudioData(await f.arrayBuffer());
    const n = Math.min(audio.length, Math.floor(audio.sampleRate * 360));
    const mono = new Float32Array(n);
    for (let c = 0; c < audio.numberOfChannels; c++) {
      const d = audio.getChannelData(c);
      for (let i = 0; i < n; i++) mono[i] += d[i] / audio.numberOfChannels;
    }
    return { samples: mono, rate: audio.sampleRate };
  } finally {
    void ctx.close();
  }
}
