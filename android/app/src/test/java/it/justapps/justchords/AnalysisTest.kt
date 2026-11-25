package it.justapps.justchords

import it.justapps.justchords.core.Analysis
import it.justapps.justchords.core.LinkKind
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.Segment
import it.justapps.justchords.core.Sources
import it.justapps.justchords.core.Strum
import it.justapps.justchords.core.VideoRef
import it.justapps.justchords.core.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** The same cases as the web app's tests/analysis.test.ts. */
class AnalysisTest {
    private fun clickTrack(bpm: Int, seconds: Int, sr: Int = 22050): FloatArray {
        val x = FloatArray(seconds * sr) { i -> (0.05 * sin(2 * PI * 110 * i / sr)).toFloat() }
        var seed = 1L
        fun rnd(): Double { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return seed.toDouble() / 0x7fffffff * 2 - 1 }
        val per = 60.0 / bpm * sr
        var b = 0
        while (b * per < x.size) {
            val o = (b * per).roundToInt()
            for (k in 0 until 600) if (o + k < x.size) x[o + k] += (rnd() * exp(-k / 120.0) * (if (b % 4 == 0) 0.9 else 0.6)).toFloat()
            b++
        }
        return x
    }

    private fun seg(a: Double, b: Double, c: String?) = Segment(a, b, c)
    private fun words(vararg l: Triple<String, Double, Double>) = l.map { Word(it.first, it.second, it.third) }

    @Test fun tempo() {
        for (bpm in listOf(92, 100, 128)) {
            val t = Analysis.estimateTempo(clickTrack(bpm, 14), 22050)
            assertTrue("$bpm → ${t.bpm}", abs(t.bpm - bpm) <= 2)
            assertTrue(t.confidence > 0.1)
        }
    }

    @Test fun tidyKeyCapoStrum() {
        val t = Analysis.tidy(listOf(seg(0.0, 2.0, "G"), seg(2.0, 2.3, "Bm"), seg(2.3, 4.0, "G"), seg(4.0, 6.0, "D"), seg(6.0, 6.4, null), seg(6.4, 8.0, "Em"), seg(8.0, 10.0, "C")), 100)
        assertEquals(listOf("G", "D", "Em", "C"), t.map { it.chord })
        assertEquals("G", Music.formatKey(Analysis.keyOf(t)!!))
        val how = Analysis.howToPlay(listOf(seg(0.0, 4.0, "Eb"), seg(4.0, 8.0, "Ab"), seg(8.0, 12.0, "Bb"), seg(12.0, 16.0, "Cm")), 96)
        assertTrue(how.capo in listOf(1, 3))
        assertEquals(4, how.shapes.size)
        assertEquals(Strum.POP, how.strum)
        assertEquals(Strum.BALLAD, Analysis.howToPlay(listOf(seg(0.0, 4.0, "G")), 70).strum)
    }

    @Test fun chordsOnWords() {
        val segs = listOf(seg(0.0, 2.0, "G"), seg(2.0, 4.0, "D"), seg(4.0, 7.0, "Em"))
        val w = words(Triple("hello", 0.1, 0.5), Triple("my", 0.6, 1.8), Triple("friend,", 2.05, 3.6), Triple("how", 4.1, 4.4), Triple("are", 4.5, 4.7), Triple("you?", 4.8, 5.3))
        assertEquals("[G]hello my [D]friend, [Em]how are you?", Analysis.alignLyrics(segs, w))
    }

    @Test fun introOutro() {
        val segs = listOf(seg(0.0, 2.0, "C"), seg(2.0, 4.0, "G"), seg(4.0, 8.0, "Am"), seg(8.0, 12.0, "F"), seg(12.0, 14.0, "C"), seg(14.0, 16.0, "G"))
        val w = words(Triple("Respiri", 4.2, 4.8), Triple("piano", 4.9, 5.4), Triple("ti", 8.1, 8.3), Triple("addormenti", 8.4, 9.2))
        assertEquals("{comment: Intro}\n[C] [G]\n\n[Am]Respiri piano\n[F]ti addormenti\n\n{comment: Outro}\n[C] [G]", Analysis.alignLyrics(segs, w))
        assertEquals(2, Analysis.lyricLines(words(Triple("a", 0.0, 0.2), Triple("b", 2.0, 2.2))).size)
    }

    @Test fun chordPro() {
        val segs = listOf(seg(0.0, 4.0, "Eb"), seg(4.0, 8.0, "Ab"), seg(8.0, 12.0, "Bb"), seg(12.0, 16.0, "Cm"))
        val how = Analysis.howToPlay(segs, 96)
        val cp = Analysis.chordPro("Prova", null, segs, null, how, "Ritmo")
        assertTrue(cp.contains("{title: Prova}"))
        assertTrue(Regex("\\{capo: [13]\\}").containsMatchIn(cp))
        assertTrue(cp.contains("{tempo: 96}"))
        // With a capo the sheet holds the shapes to play.
        assertTrue(cp, cp.contains(how.shapes.joinToString(" ") { "[$it]" }))
        assertEquals(if (how.capo == 3) listOf("C", "F", "G", "Am") else listOf("D", "G", "A", "Bm"), how.shapes)
        val two = listOf(seg(0.0, 4.0, "G"), seg(4.0, 8.0, "D"))
        assertTrue(Analysis.chordPro("x", null, two, null, Analysis.howToPlay(two, 100), "R").contains("[G] [D]"))
    }

    @Test fun whisperAudio() {
        val wav = Analysis.wav16k(FloatArray(44100) { 0.5f }, 44100)
        assertEquals(44 + 16000 * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals((0.5 * 32767).roundToInt().toShort(), ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(44 + 100))
        val long = FloatArray(70 * 8000)
        val r = Analysis.clipRanges(long, 8000)
        assertEquals(3, r.size)
        for ((a, b) in r) assertTrue((b - a) / 8000.0 <= 28)
        assertEquals(long.size, r.last().second)
    }

    /** A strummed song: each chord a bar of 4 strums, harmonics, decay. */
    private fun strummed(chords: List<List<Double>>, bpm: Int, bars: Int, sr: Int = 22050): FloatArray {
        val beat = 60.0 / bpm
        val out = FloatArray((chords.size * bars * 4 * beat * sr).toInt())
        var t = 0
        repeat(bars) {
            for (freqs in chords) repeat(4) {
                val len = (beat * sr).toInt()
                for (i in 0 until len) {
                    if (t + i >= out.size) break
                    val env = exp(-i / (sr * 0.35))
                    var v = 0.0
                    for (f in freqs) v += sin(2 * PI * f * i / sr) + 0.4 * sin(4 * PI * f * i / sr) + 0.2 * sin(6 * PI * f * i / sr)
                    out[t + i] += (v * env * 0.12).toFloat()
                }
                t += len
            }
        }
        return out
    }

    @Test fun wholeSong() {
        val g = listOf(98.0, 196.0, 246.94, 293.66)
        val d = listOf(146.83, 220.0, 293.66, 369.99)
        val em = listOf(82.41, 164.81, 196.0, 246.94)
        val c = listOf(130.81, 196.0, 261.63, 329.63)
        val sr = 22050
        val x = strummed(listOf(g, d, em, c), 100, 3, sr)
        val tempo = Analysis.estimateTempo(x, sr)
        assertTrue("tempo ${tempo.bpm}", abs(tempo.bpm - 100) <= 3)
        val segs = Analysis.tidy(it.justapps.justchords.core.Recognition.recognise(it.justapps.justchords.core.Dsp(), x, sr), tempo.bpm)
        val seq = segs.mapNotNull { it.chord }
        assertEquals(listOf("G", "D", "Em", "C"), seq.take(4))
        val how = Analysis.howToPlay(segs, tempo.bpm)
        assertEquals("G", how.keyName)
        assertEquals(0, how.capo)
        assertEquals(Strum.POP, how.strum)
    }

    @Test fun socialLinks() {
        assertEquals(VideoRef("tiktok", "7312345678901234567"), Sources.socialVideo("https://www.tiktok.com/@someone/video/7312345678901234567?is_from_webapp=1"))
        assertEquals(VideoRef("instagram", "C1a2B3c4D5e"), Sources.socialVideo("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=x"))
        assertEquals(VideoRef("instagram", "C1a2B3c4D5e"), Sources.socialVideo("https://www.instagram.com/p/C1a2B3c4D5e/"))
        assertEquals(LinkKind.TIKTOK, Sources.linkKind("https://vm.tiktok.com/ZMabc123/"))
        assertEquals(LinkKind.INSTAGRAM, Sources.linkKind("https://www.instagram.com/reel/C1a2B3c4D5e/"))
        assertTrue(Sources.embedUrl(Sources.videoRef("https://youtu.be/dQw4w9WgXcQ")!!).contains("youtube-nocookie.com/embed/dQw4w9WgXcQ"))
    }
}
