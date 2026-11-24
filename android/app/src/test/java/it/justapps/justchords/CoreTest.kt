package it.justapps.justchords

import it.justapps.justchords.core.ChordPro
import it.justapps.justchords.core.Dsp
import it.justapps.justchords.core.Key
import it.justapps.justchords.core.Levels
import it.justapps.justchords.core.Line
import it.justapps.justchords.core.LinkKind
import it.justapps.justchords.core.LiveChords
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.Notation
import it.justapps.justchords.core.Part
import it.justapps.justchords.core.Profile
import it.justapps.justchords.core.Recognition
import it.justapps.justchords.core.RenderOptions
import it.justapps.justchords.core.SectionKind
import it.justapps.justchords.core.Signal
import it.justapps.justchords.core.Sources
import it.justapps.justchords.core.Taste
import it.justapps.justchords.core.Voicings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The same cases as the web app's tests (tests in the web app): both apps must agree. */
class CoreTest {
    private fun p(s: String) = Music.parseChord(s)!!
    private fun fmt(s: String, n: Notation = Notation.EN, flats: Boolean = false) = Music.format(p(s), n, flats)

    @Test fun parsesEnglishAndItalian() {
        assertEquals("Am7", fmt("Am7"))
        assertEquals("Bb/D", fmt("Bb/D", flats = true))
        assertEquals("Am", fmt("LAm"))
        assertEquals("Am", fmt("Lam"))
        assertEquals("Dm", fmt("RE-"))
        assertEquals("G7", fmt("SOL7"))
        assertEquals("A#", fmt("Sib"))
        assertEquals("F#m", fmt("Fa#m"))
        assertEquals("C/E", fmt("DO/MI"))
        assertEquals("Cmaj7", fmt("CΔ7"))
        assertEquals("Bm7b5", fmt("Bø"))
        assertEquals("Do", fmt("C", Notation.IT))
        assertEquals("Sibm", fmt("Bbm", Notation.IT, true))
    }

    @Test fun rejectsWords() {
        for (w in listOf("Hello", "And", "Amore", "Be", "Sole", "Dove", "Fame", "Cosa", "Every", "Ad")) assertNull(w, Music.parseChord(w))
    }

    @Test fun knowsTheNotes() {
        assertEquals(listOf(0, 4, 7), Music.notes(p("C")))
        assertEquals(listOf(9, 0, 4, 7), Music.notes(p("Am7")))
        assertEquals(listOf(7, 11, 2, 5), Music.notes(p("G7")))
        assertEquals(listOf(0, 4, 7, 11), Music.notes(p("Cmaj7")))
        assertEquals(listOf(2, 7, 9), Music.notes(p("Dsus4")))
        assertEquals(listOf(11, 2, 5), Music.notes(p("Bdim")))
        assertEquals(listOf(4, 11), Music.notes(p("E5")))
        assertEquals(listOf(0, 2, 4, 7), Music.notes(p("Cadd9")).sorted())
        assertEquals(listOf(0, 4, 7, 9), Music.notes(p("C6")))
        assertEquals(listOf(11, 2, 5, 9), Music.notes(p("Bm7b5")))
    }

    @Test fun transposesSimplifiesKeysCapo() {
        assertEquals("D/F#", Music.format(Music.transpose(p("C/E"), 2)))
        assertEquals("Gm", Music.format(Music.transpose(p("Am"), -2), flats = true))
        assertEquals("C", Music.format(Music.simplify(p("Cmaj7/E"))))
        assertEquals("Am", Music.format(Music.simplify(p("Am9"))))
        assertEquals("G", Music.formatKey(Music.detectKey(listOf("G", "D", "Em", "C", "G", "D", "C", "G").map(::p))!!))
        assertEquals("Am", Music.formatKey(Music.detectKey(listOf("Am", "F", "C", "G", "Am").map(::p))!!))
        assertEquals("F", Music.formatKey(Music.detectKey(listOf("F", "Bb", "C", "F").map(::p))!!))
        assertTrue(Music.prefersFlats(Key(5, false)))
        assertTrue(Music.suggestCapo(listOf("Eb", "Ab", "Bb", "Cm").map(::p))[0].capo in listOf(1, 3))
        assertEquals(0, Music.suggestCapo(listOf("G", "C", "D", "Em").map(::p))[0].capo)
    }

    private fun top(s: String, t: it.justapps.justchords.core.Tuning = Voicings.TUNINGS[0]) =
        Voicings.voicings(p(s), t, 3).map { v -> v.frets.joinToString("") { if (it < 0) "x" else it.toString() } }

    @Test fun voicings() {
        assertTrue(top("C").contains("x32010"))
        assertTrue(Regex("^3200(0|3)3$").matches(top("G")[0]))
        assertTrue(top("D").contains("xx0232"))
        assertTrue(top("Am").contains("x02210"))
        assertTrue(top("E").contains("022100"))
        assertTrue(top("Em").contains("022000"))
        assertTrue(top("A7").any { Regex("^x0(2020|2223)$").matches(it) })
        assertTrue(top("F").any { Regex("^(133211|xx3211)$").matches(it) })
        assertTrue(top("C/E").contains("032010"))
        assertTrue(top("C", Voicings.UKULELE).contains("0003"))
        assertTrue(top("Am", Voicings.UKULELE).contains("2000"))
        assertTrue(top("G", Voicings.UKULELE).contains("0232"))
        for (s in listOf("C#m7b5", "Ebmaj9", "F#7#9", "Gbdim7", "Bbsus2", "Ab6", "B13")) assertTrue(s, Voicings.voicings(p(s)).isNotEmpty())
    }

    @Test fun chordPro() {
        val s = ChordPro.parse("{title: Song}\n{artist: Me}\n{key: G}\n{capo: 2}\n{soc}\n[G]Hello [D]world\n[Em] [C]\n{eoc}\n")
        assertEquals("Song", s.title)
        assertEquals(2, s.capo)
        assertEquals(SectionKind.CHORUS, s.sections[0].kind)
        assertEquals(Line.Lyric(listOf(Part("G", "Hello "), Part("D", "world"))), s.sections[0].lines[0])
        assertEquals(Line.Chords(listOf("Em", "C")), s.sections[0].lines[1])
        assertEquals(listOf("G", "D", "Em", "C"), ChordPro.songChords(s).map { Music.format(it) })
        assertEquals("Sim", ChordPro.displayChord("Am", RenderOptions(2, Notation.IT, false, false)))
    }

    @Test fun importsPastedText() {
        assertTrue(ChordPro.isChordLine("G    D/F#   Em   C"))
        assertTrue(ChordPro.isChordLine("DO   SOL   LAm   FA"))
        assertFalse(ChordPro.isChordLine("A man walks down the street"))
        assertFalse(ChordPro.isChordLine("la la la"))
        assertFalse(ChordPro.isChordLine("Mi sono innamorato"))
        assertTrue(ChordPro.isChordLine("| G | D | Em | C | (x2)"))
        assertEquals("[G]Hello my [D]friend", ChordPro.merge("G        D", "Hello my friend"))
        assertEquals("Shor[C]t         [G]", ChordPro.merge("    C         G", "Short"))
        val ug = """Wonderwall Chords by Oasis
Difficulty: intermediate
Tuning: E A D G B E
Capo: 2nd fret

[Intro]
Em7  G  Dsus4  A7sus4

[Verse 1]
Em7          G
Today is gonna be the day
         Dsus4                A7sus4
That they're gonna throw it back to you

[Chorus]
C        D         Em
Because maybe
"""
        val cp = ChordPro.importText(ug)
        for (x in listOf("{title: Wonderwall}", "{artist: Oasis}", "{capo: 2}", "{comment: Intro}", "[Em7] [G] [Dsus4] [A7sus4]", "{start_of_verse: Verse 1}", "[Em7]Today is gonn[G]a be the day", "{start_of_chorus: Chorus}")) {
            assertTrue(x, cp.contains(x))
        }
        val it = ChordPro.importText("Albachiara - Vasco Rossi\n\nDO          SOL\nRespiri piano per non far rumore\nLAm\nti addormenti di sera")
        assertTrue(it, it.contains("{artist: Vasco Rossi}"))
        assertTrue(it, it.contains("[C]Respiri pian[G]o per non far rumore"))
        // UG markup.
        assertTrue(ChordPro.importText("[Verse]\n[ch]Em7[/ch]   [ch]G[/ch]\nToday is gonna be the day", ChordPro.Hint("Wonderwall", "Oasis")).contains("[Em7]Today"))
    }

    @Test fun difficulty() {
        fun ch(s: String) = s.split(" ").map(::p)
        assertEquals(1, Levels.chords(ch("G D Em C")).level)
        assertEquals(3, Levels.chords(ch("F#m7b5 Bbmaj7 Ebm C#7#9 G#m Abdim7 Fm"), 4).level)
        val mid = Levels.chords(ch("C G Am F Dm E7 Bm"))
        assertEquals(2, mid.level)
        assertTrue(mid.hard.contains("F"))
        assertEquals(1, Levels.song("{title: x}\n[G]one [D]two [Em]three [C]four").level)
        assertEquals(2, Levels.estimate(listOf(1 to 3.0, 2 to 3.0, 3 to 0.5)))
    }

    @Test fun taste() {
        var pr = Profile()
        pr = Taste.learn(pr, Signal.IMPORT, "Wonderwall", "Oasis", listOf("rock"))
        pr = Taste.learn(pr, Signal.FAVORITE, "Wonderwall", "Oasis")
        assertTrue(pr.artists.getValue("oasis") > 4)
        assertTrue(pr.genreBoost.getValue("rock") > 0)
        pr = Taste.learn(pr, Signal.DISLIKE, "Song 2", "Blur")
        assertTrue(pr.disliked.contains(Taste.songKey("Song 2", "Blur")))
        pr = Taste.learn(pr, Signal.LIKE, "Song 2", "Blur")
        assertFalse(pr.disliked.contains(Taste.songKey("Song 2", "Blur")))
        // Same JSON as the web app.
        val back = Profile.fromJson(JSONObject(pr.toJson().toString()))
        assertEquals(pr, back)
        assertEquals("vasco rossi|albachiara", Taste.songKey("Albachiara (Live)", "Vasco Rossi"))
    }

    private fun ugPage(data: JSONObject) =
        "<html><div class=\"js-store\" data-content=\"${JSONObject().put("store", JSONObject().put("page", JSONObject().put("data", data))).toString().replace("&", "&amp;").replace("\"", "&quot;")}\"></div></html>"

    @Test fun sources() {
        val html = ugPage(
            JSONObject()
                .put("tab", JSONObject().put("song_name", "Wonderwall").put("artist_name", "Oasis").put("tonality_name", "F#m"))
                .put("tab_view", JSONObject().put("meta", JSONObject().put("capo", 2).put("difficulty", "intermediate")).put("wiki_tab", JSONObject().put("content", "[Verse]\r\n[ch]Em7[/ch]   [ch]G[/ch]\r\nToday is gonna be the day"))),
        )
        val s = Sources.parseUgTab(html, "https://tabs.ultimate-guitar.com/tab/x")!!
        assertEquals("Wonderwall", s.title)
        assertEquals(2, s.capo)
        assertEquals("F#m", s.key)
        assertEquals(2, s.level)
        val aes = Sources.parseAesSong(
            "<h1>Albachiara – Vasco Rossi</h1><div class=\"entry-content\"><pre>DO          SOL\nRespiri piano per non far rumore\n</pre></div>",
            "https://www.accordiespartiti.it/accordi/italiani/rossi-vasco/albachiara/",
        )!!
        assertEquals("Albachiara", aes.title)
        assertTrue(ChordPro.importText(aes.text, ChordPro.Hint(aes.title, aes.artist)).contains("[C]Respiri"))
        assertEquals("Vasco Rossi", Sources.aesArtist("rossi-vasco"))
        assertEquals("Modena City Ramblers", Sources.aesArtist("modena-city-ramblers"))
        assertEquals("dQw4w9WgXcQ", Sources.youtubeId("https://youtu.be/dQw4w9WgXcQ?t=3"))
        assertEquals("abcdefghijk", Sources.youtubeId("https://music.youtube.com/watch?v=abcdefghijk&list=x"))
        assertEquals("abcdefghijk", Sources.youtubeId("https://www.youtube.com/shorts/abcdefghijk"))
        assertEquals(LinkKind.SPOTIFY, Sources.linkKind("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=1"))
        assertEquals(LinkKind.CHORDS, Sources.linkKind("https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-1"))
        assertNull(Sources.sourceOf("http://www.accordiespartiti.it/x"))
        assertNotNull(Sources.sourceOf("https://www.accordiespartiti.it/x"))
    }

    private fun tone(freqs: List<Double>, sr: Int, n: Int) = FloatArray(n) { i -> freqs.sumOf { f -> sin(2 * PI * f * i / sr) / freqs.size }.toFloat() * 0.5f }

    @Test fun dsp() {
        val sr = 44100
        val dsp = Dsp()
        val hz = dsp.yin(tone(listOf(110.0), sr, 4096), sr.toFloat())
        assertTrue("$hz", abs(hz - 110) < 1)
        assertEquals(45 to 0.0, Dsp.noteFromHz(110.0).let { it.first to Math.round(it.second).toDouble() })
        // C major triad (C4 E4 G4 with harmonics) → C.
        val chord = FloatArray(8192) { i ->
            listOf(261.63, 329.63, 392.0).sumOf { f -> sin(2 * PI * f * i / sr) + 0.4 * sin(4 * PI * f * i / sr) }.toFloat() * 0.2f
        }
        val (chroma, rms) = dsp.chroma(chord, sr.toFloat())
        assertTrue(rms > 0.01f)
        val live = LiveChords()
        repeat(4) { live.push(chroma, rms) }
        assertEquals("C", live.current?.name)
        // Whole file: G for 2 s then Am for 2 s.
        fun block(fs: List<Double>, sec: Double) = FloatArray((sr * sec).toInt()) { i -> fs.sumOf { f -> sin(2 * PI * f * i / sr) }.toFloat() * 0.2f }
        val song = block(listOf(196.0, 246.94, 293.66), 2.0) + block(listOf(220.0, 261.63, 329.63), 2.0)
        val segs = Recognition.recognise(dsp, song, sr)
        assertEquals(listOf("G", "Am"), segs.mapNotNull { it.chord })
        assertTrue(Recognition.toChordPro(segs, "Test").contains("[G] [Am]"))
    }
}
