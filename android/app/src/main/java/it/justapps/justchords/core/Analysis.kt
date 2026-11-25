package it.justapps.justchords.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

// "Analizza": from a recording (a video playing, a file, the microphone) to
// how to play it: chords over time, key, tempo, capo, strumming, and the
// lyrics (Whisper word timestamps) with the chords on the right syllables.
// The same algorithms as the web app (src/lib/analysis.ts).

data class Word(val w: String, val s: Double, val e: Double)

data class Tempo(val bpm: Int, val confidence: Double)

enum class Strum(val pattern: String) { BALLAD("↓ · ↓ · ↓ · ↓↑"), POP("↓ · ↓↑ · ↑↓↑"), DRIVE("↓↓↓↓ ↓↓↓↓") }

data class HowToPlay(
    val key: Key?,
    /** Key as heard (no capo). */
    val keyName: String,
    val bpm: Int,
    val capo: Int,
    /** Chords to finger with that capo, in order of appearance. */
    val shapes: List<String>,
    /** The chords as heard. */
    val chords: List<String>,
    val level: Int,
    val hard: List<String>,
    val strum: Strum,
)

object Analysis {
    // ------------------------------------------------------------ tempo

    /** Tempo from the onset envelope (energy flux) and its autocorrelation, with a prior around 110 BPM. */
    fun estimateTempo(samples: FloatArray, sr: Int): Tempo {
        val hop = (sr * 0.0116).roundToInt()
        val n = samples.size / hop
        if (n < 200) return Tempo(0, 0.0)
        val env = DoubleArray(n)
        var prevE = 0.0
        for (i in 0 until n) {
            var e = 0.0
            val o = i * hop
            for (k in 1 until hop) {
                // First difference: brings out attacks (strums, drums) over sustained notes.
                val d = (samples[o + k] - samples[o + k - 1]).toDouble()
                e += d * d
            }
            val le = ln1p(1000 * e)
            env[i] = max(0.0, le - prevE)
            prevE = le
        }
        val mean = env.average()
        for (i in 0 until n) env[i] -= mean
        val fps = sr.toDouble() / hop
        fun lagOf(bpm: Double) = 60 * fps / bpm
        fun ac(lag: Double): Double {
            val l0 = lag.toInt()
            val f = lag - l0
            var s0 = 0.0
            var s1 = 0.0
            var i = 0
            while (i + l0 + 1 < n) {
                s0 += env[i] * env[i + l0]
                s1 += env[i] * env[i + l0 + 1]
                i++
            }
            return (s0 * (1 - f) + s1 * f) / (n - l0)
        }
        val zero = env.sumOf { it * it } / n
        var best = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestAc = 0.0
        var bpm = 60.0
        while (bpm <= 200) {
            // A beat also lines up with its double: reward both.
            val a = ac(lagOf(bpm)) + 0.5 * ac(lagOf(bpm / 2))
            val prior = exp(-0.5 * (ln(bpm / 110) / ln(2.0) / 0.9).pow(2))
            val score = a * prior
            if (score > bestScore) {
                bestScore = score
                best = bpm
                bestAc = ac(lagOf(bpm))
            }
            bpm += 0.5
        }
        return Tempo(best.roundToInt(), if (zero > 0) (bestAc / zero).coerceIn(0.0, 1.0) else 0.0)
    }

    // ------------------------------------------------------------ chords

    /** Fewer, steadier chords to play: drops blips shorter than about a beat. */
    fun tidy(segs: List<Segment>, bpm: Int = 0): List<Segment> {
        val min = max(0.5, if (bpm > 0) 60.0 / bpm * 0.9 else 0.7)
        val out = mutableListOf<Segment>()
        for (s in segs) {
            val last = out.lastOrNull()
            val short = s.end - s.start < (if (s.chord != null) min else 1.0)
            if (last != null && (last.chord == s.chord || short)) last.end = s.end else out += s.copy()
        }
        if (out.size > 1 && out[0].end - out[0].start < min) {
            out[1] = out[1].copy(start = out[0].start)
            out.removeAt(0)
        }
        val merged = mutableListOf<Segment>()
        for (s in out) {
            val last = merged.lastOrNull()
            if (last != null && last.chord == s.chord) last.end = s.end else merged += s.copy()
        }
        return merged
    }

    /** Key from the chords, the longer ones counting more. */
    fun keyOf(segs: List<Segment>): Key? {
        val list = mutableListOf<Chord>()
        for (s in segs) {
            val c = s.chord?.let(Music::parseChord) ?: continue
            val times = ((s.end - s.start) / 2).roundToInt().coerceIn(1, 6)
            repeat(times) { list += c }
        }
        return if (list.isEmpty()) null else Music.detectKey(list)
    }

    fun howToPlay(segs: List<Segment>, bpm: Int): HowToPlay {
        val chords = segs.mapNotNull { it.chord }.distinct()
        val parsed = chords.mapNotNull(Music::parseChord)
        val key = keyOf(segs)
        val capo = Music.suggestCapo(parsed).firstOrNull()?.capo ?: 0
        val flats = Music.prefersFlats(key?.let { it.copy(tonic = Music.mod12(it.tonic - capo)) })
        val shapes = parsed.map { Music.format(Music.transpose(it, -capo), Notation.EN, flats) }
        val changes = segs.count { it.chord != null }
        val minutes = max(0.25, (segs.lastOrNull()?.end ?: 0.0) / 60)
        val perLine = if (bpm > 0) (changes / minutes * (16.0 / bpm)).roundToInt() else 2
        val d = Levels.chords(parsed, perLine)
        val strum = if (bpm == 0 || bpm < 80) Strum.BALLAD else if (bpm > 140) Strum.DRIVE else Strum.POP
        return HowToPlay(key, key?.let { Music.formatKey(it) } ?: "", bpm, capo, shapes, chords, d.level, d.hard, strum)
    }

    /**
     * The chords as written in a sheet with a capo: shapes (sounding chord minus
     * the capo), spelled for the key, as the song view expects them.
     */
    fun asShapes(segs: List<Segment>, how: HowToPlay): List<Segment> {
        val flats = Music.prefersFlats(how.key?.let { it.copy(tonic = Music.mod12(it.tonic - how.capo)) })
        return segs.map { s -> s.chord?.let(Music::parseChord)?.let { s.copy(chord = Music.format(Music.transpose(it, -how.capo), Notation.EN, flats)) } ?: s.copy() }
    }

    // ------------------------------------------------------------ lyrics

    /** Lines of sung words: a new line after a pause, a sentence end, or too many words. */
    fun lyricLines(words: List<Word>): List<List<Word>> {
        val lines = mutableListOf<List<Word>>()
        var cur = mutableListOf<Word>()
        for (w in words) {
            val prev = cur.lastOrNull()
            if (prev != null && (w.s - prev.e > 0.9 || Regex("[.?!]$").containsMatchIn(prev.w) || (Regex("[,;:]$").containsMatchIn(prev.w) && cur.size >= 4) || cur.size >= 9)) {
                lines += cur
                cur = mutableListOf()
            }
            cur += w
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    data class Labels(val intro: String = "Intro", val solo: String = "Strumentale", val outro: String = "Outro")

    private fun chordAt(segs: List<Segment>, t: Double) = segs.find { it.start <= t + 1e-6 && t < it.end }?.chord

    /**
     * Chords and words on one timeline → ChordPro body. Each chord change goes
     * before the word being sung at that moment; every line starts with the
     * chord playing; changes between sung lines become chord-only lines.
     */
    fun alignLyrics(segs: List<Segment>, words: List<Word>, labels: Labels = Labels()): String {
        val changes = segs.filterIndexed { i, s -> s.chord != null && (i == 0 || segs[i - 1].chord != s.chord || segs[i - 1].end < s.start - 0.05) }
        val lines = lyricLines(words)
        val out = mutableListOf<String>()
        var ci = 0
        fun chordRow(until: Double, label: String) {
            val row = mutableListOf<String>()
            var start = -1.0
            while (ci < changes.size && changes[ci].start < until - 0.3) {
                if (start < 0) start = changes[ci].start
                row += changes[ci].chord!!
                ci++
            }
            if (row.isEmpty()) return
            // Only a real instrumental part gets a label; a chord just before singing joins the line.
            if (until - start >= 4 || label == "outro") {
                if (out.isNotEmpty()) out += ""
                out += "{comment: ${when (label) { "intro" -> labels.intro; "outro" -> labels.outro; else -> labels.solo }}}"
                row.chunked(4).forEach { r -> out += r.joinToString(" ") { "[$it]" } }
                out += ""
            } else ci -= row.size
        }
        lines.forEachIndexed { li, line ->
            chordRow(line[0].s, if (li == 0) "intro" else "solo")
            val text = StringBuilder()
            line.forEachIndexed { wi, w ->
                val next = line.getOrNull(wi + 1)
                val chords = mutableListOf<String>()
                // A change just before a word (anticipation) belongs to that word.
                val limit = if (next != null) next.s - 0.3 else w.e + 0.3
                while (ci < changes.size && changes[ci].start < limit) chords += changes[ci++].chord!!
                if (wi == 0 && chords.isEmpty()) chordAt(segs, w.s)?.let { chords += it }
                text.append(chords.joinToString("") { "[$it]" }).append(w.w).append(if (next != null) " " else "")
            }
            out += text.toString()
            val nextLine = lines.getOrNull(li + 1)
            if (nextLine != null && nextLine[0].s - line.last().e > 3) out += ""
        }
        chordRow(Double.POSITIVE_INFINITY, "outro")
        while (out.lastOrNull() == "") out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    fun chordsOnly(segs: List<Segment>): String =
        segs.filterIndexed { i, s -> s.chord != null && (i == 0 || segs[i - 1].chord != s.chord) }.map { it.chord!! }
            .chunked(4).joinToString("\n") { l -> l.joinToString(" ") { "[$it]" } }

    fun chordPro(title: String, artist: String?, segs: List<Segment>, words: List<Word>?, how: HowToPlay, strumLabel: String, labels: Labels = Labels()): String {
        val head = mutableListOf("{title: ${title.ifEmpty { "Senza titolo" }}}")
        if (!artist.isNullOrEmpty()) head += "{artist: $artist}"
        // Key and chords are written as played with the capo (the sheet convention).
        how.key?.let { head += "{key: ${Music.formatKey(it.copy(tonic = Music.mod12(it.tonic - how.capo)))}}" }
        if (how.capo != 0) head += "{capo: ${how.capo}}"
        if (how.bpm != 0) head += "{tempo: ${how.bpm}}"
        head += "{comment: $strumLabel: ${how.strum.pattern}}"
        val shapes = asShapes(segs, how)
        val body = if (!words.isNullOrEmpty()) alignLyrics(shapes, words, labels) else chordsOnly(shapes)
        return "${head.joinToString("\n")}\n\n$body\n"
    }

    // ------------------------------------------------------------ audio for Whisper

    /** Mono float samples → 16 kHz 16-bit WAV (what Whisper wants). */
    fun wav16k(samples: FloatArray, sr: Int, from: Int = 0, to: Int = samples.size): ByteArray {
        val rate = 16000
        val len = to - from
        val n = (len.toLong() * rate / sr).toInt()
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        val step = sr.toDouble() / rate
        for (i in 0 until n) {
            val a = (i * step).toInt()
            val e = max(a + 1, ((i + 1) * step).toInt())
            var s = 0.0
            var k = a
            while (k < e && from + k < to) { s += samples[from + k]; k++ }
            val x = (s / (e - a)).coerceIn(-1.0, 1.0)
            b.putShort((x * 32767).roundToInt().toShort())
        }
        return b.array()
    }

    /** Cut points for ≤ max-second clips, each at the quietest moment of its last 5 seconds. */
    fun clipRanges(samples: FloatArray, sr: Int, max: Int = 28): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val win = (sr * 0.2).roundToInt()
        var start = 0
        while (start < samples.size) {
            if (samples.size - start <= max * sr) {
                out += start to samples.size
                break
            }
            var cut = start + max * sr
            var quiet = Double.POSITIVE_INFINITY
            var p = start + (max - 5) * sr
            while (p + win <= start + max * sr) {
                var e = 0.0
                for (k in p until p + win) e += samples[k] * samples[k]
                if (e < quiet) { quiet = e; cut = p + win / 2 }
                p += win / 2
            }
            out += start to cut
            start = cut
        }
        return out
    }
}
