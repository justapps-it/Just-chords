// Audio DSP compiled to WebAssembly (AssemblyScript): FFT, chroma (pitch
// class profile) for chord recognition and YIN pitch detection for the tuner.
// No allocations: everything lives in static buffers.

const MAX: i32 = 16384;
const INPUT = memory.data(MAX * 4);
const RE = memory.data(MAX * 4);
const IM = memory.data(MAX * 4);
const YINBUF = memory.data(MAX * 4);
const CHROMA = memory.data(12 * 4);
const SPEC = memory.data(MAX * 2);

let confidence: f32 = 0;

export function inputPtr(): usize {
  return INPUT;
}
export function chromaPtr(): usize {
  return CHROMA;
}
export function pitchConfidence(): f32 {
  return confidence;
}

@inline function ld(base: usize, i: i32): f32 {
  return load<f32>(base + (<usize>i << 2));
}
@inline function st(base: usize, i: i32, v: f32): void {
  store<f32>(base + (<usize>i << 2), v);
}

function fft(n: i32): void {
  // Bit reversal.
  let j = 0;
  for (let i = 0; i < n - 1; i++) {
    if (i < j) {
      const tr = ld(RE, i);
      st(RE, i, ld(RE, j));
      st(RE, j, tr);
      const ti = ld(IM, i);
      st(IM, i, ld(IM, j));
      st(IM, j, ti);
    }
    let k = n >> 1;
    while (k <= j) {
      j -= k;
      k >>= 1;
    }
    j += k;
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2.0 * Math.PI) / <f64>len;
    const wr = <f32>Math.cos(ang);
    const wi = <f32>Math.sin(ang);
    const half = len >> 1;
    for (let i = 0; i < n; i += len) {
      let cr: f32 = 1;
      let ci: f32 = 0;
      for (let k = 0; k < half; k++) {
        const a = i + k;
        const b = a + half;
        const xr = ld(RE, b) * cr - ld(IM, b) * ci;
        const xi = ld(RE, b) * ci + ld(IM, b) * cr;
        st(RE, b, ld(RE, a) - xr);
        st(IM, b, ld(IM, a) - xi);
        st(RE, a, ld(RE, a) + xr);
        st(IM, a, ld(IM, a) + xi);
        const nr = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = nr;
      }
    }
  }
}

/**
 * 12-bin chroma of the first `n` input samples (n power of two). Uses
 * spectral peaks between 55 Hz and 4 kHz, each spread to its nearest pitch
 * class with a weight that drops off with the distance in cents, and
 * attenuates the harmonics so the fundamental dominates.
 * Returns the frame energy (RMS).
 */
export function chroma(n: i32, sampleRate: f32): f32 {
  let rms: f32 = 0;
  for (let i = 0; i < n; i++) {
    const x = ld(INPUT, i);
    rms += x * x;
    const w = <f32>(0.5 - 0.5 * Math.cos((2.0 * Math.PI * <f64>i) / <f64>(n - 1)));
    st(RE, i, x * w);
    st(IM, i, 0);
  }
  rms = <f32>Math.sqrt(rms / <f32>n);
  fft(n);
  const half = n >> 1;
  let maxMag: f32 = 0;
  for (let k = 0; k < half; k++) {
    const m = <f32>Math.sqrt(ld(RE, k) * ld(RE, k) + ld(IM, k) * ld(IM, k));
    st(SPEC, k, m);
    if (m > maxMag) maxMag = m;
  }
  for (let c = 0; c < 12; c++) st(CHROMA, c, 0);
  if (maxMag <= 0) return rms;
  const binHz = sampleRate / <f32>n;
  const lo = <i32>(<f32>55 / binHz) + 1;
  const hi = min(<i32>(<f32>4000 / binHz), half - 2);
  const floor = maxMag * 0.02;
  for (let k = lo; k <= hi; k++) {
    const m = ld(SPEC, k);
    if (m < floor || m < ld(SPEC, k - 1) || m < ld(SPEC, k + 1)) continue;
    // Parabolic interpolation of the peak.
    const a: f32 = ld(SPEC, k - 1);
    const b = m;
    const c = ld(SPEC, k + 1);
    const den: f32 = a - <f32>2 * b + c;
    const p: f32 = den != 0 ? <f32>0.5 * (a - c) / den : 0;
    const f = (<f32>k + p) * binHz;
    const midi = <f32>(69.0 + 12.0 * Math.log2(<f64>f / 440.0));
    const nearest = Mathf.round(midi);
    const dev = Mathf.abs(midi - nearest);
    const weight = Mathf.cos(dev * <f32>Math.PI) ;
    const pc = ((<i32>nearest % 12) + 12) % 12;
    // Higher notes are usually harmonics: weigh them less.
    const octave: f32 = f > 1000 ? 0.5 : f > 500 ? 0.8 : 1;
    const v = Mathf.sqrt(m / maxMag) * weight * weight * octave;
    st(CHROMA, pc, ld(CHROMA, pc) + v);
  }
  return rms;
}

/**
 * YIN pitch detection on the first `n` input samples. Returns the frequency
 * in Hz, or -1 when there is no clear pitch.
 */
export function yin(n: i32, sampleRate: f32, threshold: f32): f32 {
  const w = n >> 1;
  const maxTau = w;
  const minTau = max(2, <i32>(sampleRate / 1500));
  st(YINBUF, 0, 1);
  let running: f32 = 0;
  for (let tau = 1; tau < maxTau; tau++) {
    let sum: f32 = 0;
    for (let i = 0; i < w; i++) {
      const d = ld(INPUT, i) - ld(INPUT, i + tau);
      sum += d * d;
    }
    running += sum;
    st(YINBUF, tau, running > <f32>0 ? sum * <f32>tau / running : 1);
  }
  let tau = minTau;
  let found = -1;
  while (tau < maxTau) {
    if (ld(YINBUF, tau) < threshold) {
      while (tau + 1 < maxTau && ld(YINBUF, tau + 1) < ld(YINBUF, tau)) tau++;
      found = tau;
      break;
    }
    tau++;
  }
  if (found < 0) {
    confidence = 0;
    return -1;
  }
  confidence = 1 - ld(YINBUF, found);
  let better = <f32>found;
  if (found > 1 && found < maxTau - 1) {
    const s0 = ld(YINBUF, found - 1);
    const s1 = ld(YINBUF, found);
    const s2 = ld(YINBUF, found + 1);
    const den: f32 = <f32>2 * (<f32>2 * s1 - s2 - s0);
    if (den != 0) better = <f32>found + (s2 - s0) / den;
  }
  return sampleRate / better;
}
