package it.justapps.justchords.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Audio DSP, the same algorithms as the web app's WebAssembly module
// (assembly/index.ts + src/audio/dsp.ts): chroma for chord recognition,
// YIN for the tuner, chord templates, live smoothing and Viterbi for files.

class Dsp(maxSize: Int = 16384) {
    private val re = FloatArray(maxSize)
    private val im = FloatArray(maxSize)
    private val spec = FloatArray(maxSize)
    private val yinBuf = FloatArray(maxSize)
    var confidence = 0f
        private set

    private fun fft(n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k; k = k shr 1
            }
            j += k
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Chroma of a power-of-two frame, normalised to max 1, and the frame RMS. */
    fun chroma(frame: FloatArray, sampleRate: Float, n: Int = frame.size): Pair<FloatArray, Float> {
        var rms = 0f
        for (i in 0 until n) {
            val x = frame[i]
            rms += x * x
            val w = (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat()
            re[i] = x * w; im[i] = 0f
        }
        rms = sqrt(rms / n)
        fft(n)
        val half = n shr 1
        var maxMag = 0f
        for (k in 0 until half) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            spec[k] = m
            if (m > maxMag) maxMag = m
        }
        val c = FloatArray(12)
        if (maxMag <= 0) return c to rms
        val binHz = sampleRate / n
        val lo = (55f / binHz).toInt() + 1
        val hi = min((4000f / binHz).toInt(), half - 2)
        val floor = maxMag * 0.02f
        for (k in lo..hi) {
            val m = spec[k]
            if (m < floor || m < spec[k - 1] || m < spec[k + 1]) continue
            val a = spec[k - 1]
            val cc = spec[k + 1]
            val den = a - 2 * m + cc
            val p = if (den != 0f) 0.5f * (a - cc) / den else 0f
            val f = (k + p) * binHz
            val midi = (69.0 + 12.0 * log2(f / 440.0)).toFloat()
            val nearest = midi.roundToInt()
            val dev = abs(midi - nearest)
            val weight = cos(dev * PI).toFloat()
            val pc = ((nearest % 12) + 12) % 12
            val octave = if (f > 1000) 0.5f else if (f > 500) 0.8f else 1f
            c[pc] += sqrt(m / maxMag) * weight * weight * octave
        }
        val mx = c.max()
        if (mx > 0) for (i in 0 until 12) c[i] /= mx
        return c to rms
    }

    /** YIN pitch in Hz, or -1 with no clear pitch; [confidence] is set. */
    fun yin(frame: FloatArray, sampleRate: Float, threshold: Float = 0.12f, n: Int = frame.size): Float {
        val w = n shr 1
        val maxTau = w
        val minTau = max(2, (sampleRate / 1500).toInt())
        yinBuf[0] = 1f
        var running = 0f
        for (tau in 1 until maxTau) {
            var sum = 0f
            for (i in 0 until w) {
                val d = frame[i] - frame[i + tau]
                sum += d * d
            }
            running += sum
            yinBuf[tau] = if (running > 0) sum * tau / running else 1f
        }
        var tau = minTau
        var found = -1
        while (tau < maxTau) {
            if (yinBuf[tau] < threshold) {
                while (tau + 1 < maxTau && yinBuf[tau + 1] < yinBuf[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) {
            confidence = 0f
            return -1f
        }
        confidence = 1 - yinBuf[found]
        var better = found.toFloat()
        if (found > 1 && found < maxTau - 1) {
            val s0 = yinBuf[found - 1]
            val s1 = yinBuf[found]
            val s2 = yinBuf[found + 1]
            val den = 2 * (2 * s1 - s2 - s0)
            if (den != 0f) better = found + (s2 - s0) / den
        }
        return sampleRate / better
    }

    companion object {
        fun noteFromHz(hz: Double, a4: Double = 440.0): Pair<Int, Double> {
            val m = 69 + 12 * log2(hz / a4)
            val midi = m.roundToInt()
            return midi to (m - midi) * 100
        }
    }
}

// ---------------------------------------------------------------- templates

data class Template(val chord: Chord, val name: String, val vec: FloatArray, val prior: Float)

object ChordTemplates {
    private data class Q(val suffix: String, val intervals: List<Int>, val prior: Float)

    private val QUALITIES = listOf(
        Q("", listOf(0, 4, 7), 1f), Q("m", listOf(0, 3, 7), 1f), Q("7", listOf(0, 4, 7, 10), 0.93f), Q("m7", listOf(0, 3, 7, 10), 0.92f),
        Q("maj7", listOf(0, 4, 7, 11), 0.9f), Q("sus4", listOf(0, 5, 7), 0.88f), Q("sus2", listOf(0, 2, 7), 0.86f), Q("dim", listOf(0, 3, 6), 0.85f),
    )

    fun all(simple: Boolean = false): List<Template> = (if (simple) QUALITIES.take(2) else QUALITIES).flatMap { q ->
        (0 until 12).map { r ->
            val vec = FloatArray(12)
            q.intervals.forEachIndexed { i, iv -> vec[(r + iv) % 12] = if (i == 0) 1.2f else 1f }
            val n = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
            for (i in 0 until 12) vec[i] /= n
            val chord = Chord(r, q.suffix)
            Template(chord, Music.format(chord), vec, q.prior)
        }
    }

    fun score(chroma: FloatArray, t: Template): Float {
        val n = sqrt(chroma.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        var s = 0f
        for (i in 0 until 12) s += chroma[i] / n * t.vec[i]
        return s * t.prior
    }
}

/** Live recognition: smooths chroma over time and only switches on a clear winner. */
class LiveChords(private val tpl: List<Template> = ChordTemplates.all(), private val smoothing: Float = 0.6f) {
    private val avg = FloatArray(12)
    var current: Template? = null
        private set
    private var candidate: Template? = null
    private var streak = 0

    data class Result(val chord: Template?, val confidence: Float, val ranking: List<Pair<String, Float>>)

    fun push(chroma: FloatArray, rms: Float): Result {
        if (rms < 0.004f) {
            for (i in 0 until 12) avg[i] *= 0.8f
            return Result(current, 0f, emptyList())
        }
        for (i in 0 until 12) avg[i] = smoothing * avg[i] + (1 - smoothing) * chroma[i]
        val ranked = tpl.map { it to ChordTemplates.score(avg, it) }.sortedByDescending { it.second }
        val best = ranked[0]
        if (best.first === candidate) streak++ else {
            candidate = best.first; streak = 1
        }
        if (streak >= 2 && best.second > 0.55f) current = best.first
        return Result(current, best.second, ranked.take(4).map { it.first.name to it.second })
    }
}

data class Segment(val start: Double, var end: Double, val chord: String?)

object Recognition {
    /** Whole recording → chord segments (frame chroma + Viterbi, like the web app). */
    fun recognise(dsp: Dsp, samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit = {}): List<Segment> {
        val frame = if (sampleRate > 30000) 8192 else 4096
        val hop = frame / 2
        val tpl = ChordTemplates.all()
        val s = tpl.size + 1
        val frames = mutableListOf<FloatArray>()
        val buf = FloatArray(frame)
        var peak = 0f
        var i = 0
        while (i < samples.size) {
            peak = max(peak, abs(samples[i])); i += 997
        }
        val silence = max(0.003f, peak * 0.03f)
        val total = max(1, (samples.size - frame) / hop)
        var pos = 0
        var f = 0
        while (pos + frame <= samples.size) {
            System.arraycopy(samples, pos, buf, 0, frame)
            val (chroma, rms) = dsp.chroma(buf, sampleRate.toFloat())
            val obs = FloatArray(s)
            if (rms < silence) {
                obs.fill(-6f); obs[s - 1] = 0f
            } else {
                for (k in tpl.indices) obs[k] = 9 * (ChordTemplates.score(chroma, tpl[k]) - 1)
                obs[s - 1] = -4.5f
            }
            frames.add(obs)
            if (f % 50 == 0) onProgress(f.toFloat() / total)
            pos += hop; f++
        }
        if (frames.isEmpty()) return emptyList()
        val stay = ln(0.92)
        val move = ln(0.08 / (s - 1))
        var prev = DoubleArray(s) { frames[0][it].toDouble() }
        val back = mutableListOf<IntArray>()
        for (t in 1 until frames.size) {
            var bestPrev = 0
            for (k in 1 until s) if (prev[k] > prev[bestPrev]) bestPrev = k
            val cur = DoubleArray(s)
            val bp = IntArray(s)
            for (k in 0 until s) {
                val a = prev[k] + stay
                val b = prev[bestPrev] + move
                if (a >= b || bestPrev == k) {
                    cur[k] = a + frames[t][k]; bp[k] = k
                } else {
                    cur[k] = b + frames[t][k]; bp[k] = bestPrev
                }
            }
            back.add(bp)
            prev = cur
        }
        var st = 0
        for (k in 1 until s) if (prev[k] > prev[st]) st = k
        val path = IntArray(frames.size)
        path[frames.size - 1] = st
        for (t in frames.size - 1 downTo 1) path[t - 1] = back[t - 1][path[t]]
        val segs = mutableListOf<Segment>()
        val secPerHop = hop.toDouble() / sampleRate
        path.forEachIndexed { t, k ->
            val name = if (k == s - 1) null else tpl[k].name
            val time = t * secPerHop
            val last = segs.lastOrNull()
            if (last != null && last.chord == name) last.end = time + frame.toDouble() / sampleRate
            else segs.add(Segment(time, time + frame.toDouble() / sampleRate, name))
        }
        val merged = mutableListOf<Segment>()
        for (g in segs) {
            val last = merged.lastOrNull()
            if (last != null && (g.end - g.start < 0.4 || last.chord == g.chord)) last.end = g.end else merged.add(g.copy())
        }
        onProgress(1f)
        return merged
    }

    fun toChordPro(segs: List<Segment>, title: String): String {
        val chords = segs.mapNotNull { it.chord }
        val lines = chords.chunked(4).map { l -> l.joinToString(" ") { "[$it]" } }
        return "{title: $title}\n{comment: Accordi riconosciuti dall’audio}\n\n${lines.joinToString("\n")}\n"
    }
}
