package it.justapps.justchords

import it.justapps.justchords.core.L10n
import it.justapps.justchords.core.Learn
import it.justapps.justchords.core.Lesson
import it.justapps.justchords.core.LessonType
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.PlanLabels
import it.justapps.justchords.core.TestAnswers
import it.justapps.justchords.core.Voicings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

// Mirrors tests/learn.test.ts.
class LearnTest {
    private val labels = PlanLabels(L10n("Accordo", "Chord"), L10n("Cambio", "Switch"), L10n("Ritmo", "Rhythm"), L10n("Riff", "Riff"), L10n("Suona", "Play"))

    @Before fun load() = Learn.load(File("src/main/assets/courses.json").readText())

    @Test fun coursesAreWellFormed() {
        val ids = mutableSetOf<String>()
        assertTrue(Learn.courses.size >= 9)
        for (c in Learn.courses) for (l in c.lessons) {
            assertTrue(ids.add(l.id))
            for (ch in listOfNotNull(l.chord) + l.chords) {
                val p = Music.parseChord(ch)
                assertNotNull(ch, p)
                assertTrue(ch, Voicings.voicings(p!!).isNotEmpty())
            }
            if (l.type != LessonType.CHORD) assertTrue(Learn.timeline(l).total > 0)
        }
        assertEquals(32.0, Learn.timeline(Learn.lessonById("m-frere")!!.second).total, 0.0)
        val grace = Learn.lessonById("m-grace")!!.second
        assertEquals(3, grace.bar)
        assertEquals(0.0, Learn.timeline(grace).total % 3, 0.0)
    }

    @Test fun nextLessonAndTempo() {
        assertEquals("c-em", Learn.nextLesson(1, emptySet())!!.second.id)
        assertEquals("c-am", Learn.nextLesson(1, setOf("c-em"))!!.second.id)
        assertEquals(3, Learn.nextLesson(3, emptySet())!!.first.level)
        assertEquals(listOf(50, 65, 80), Learn.tempoSteps(50 to 80))
    }

    @Test fun timelines() {
        val t = Learn.timeline(Lesson("x", LessonType.SWITCH, L10n("", ""), chords = listOf("C", "G"), beats = 4))
        assertEquals(32.0, t.total, 0.0)
        assertEquals("G", Learn.at(t.steps, 3.5).next!!.chord)
        val s = Learn.timeline(Lesson("x", LessonType.STRUM, L10n("", ""), chords = listOf("G"), pattern = "D-DU-UDU"))
        assertEquals(listOf("0.0D", "1.0D", "1.5U", "2.5U", "3.0D", "3.5U"), s.steps.filter { it.stroke != null }.take(6).map { "${it.at}${it.stroke}" })
    }

    @Test fun tabs() {
        val n = Learn.parseNotes("5.0+4.2:0.5 1.3 r:2")
        assertEquals(listOf(5 to 0, 4 to 2), n[0].frets)
        assertEquals(0.5, n[0].beats, 0.0)
        assertTrue(n[2].frets.isEmpty())
        val (lines, cols) = Learn.tabLines(n)
        assertEquals('0', lines[4][cols[0]])
        assertEquals('3', lines[0][cols[1]])
        val a = Learn.parseAsciiTab(listOf("e|-------0-----|", "B|-----1---1---|", "G|---0-------0-|", "D|-2-----------|", "A|-------------|", "E|-------------|"))
        assertEquals(listOf(listOf(4 to 2), listOf(3 to 0), listOf(2 to 1), listOf(1 to 0), listOf(2 to 1), listOf(3 to 0)), a.map { it.frets })
    }

    @Test fun levelTest() {
        assertEquals(1, Learn.levelFromTest(TestAnswers()))
        assertEquals(2, Learn.levelFromTest(TestAnswers(known = setOf("Em", "Am", "C", "G", "D"), played = setOf("F"))))
        assertEquals(3, Learn.levelFromTest(TestAnswers(3, setOf("Em", "Am", "C", "G", "D", "E", "A", "Dm", "F", "Bm", "B7"))))
    }

    private val song = "{title: Test}\n{tempo: 120}\n[C]Hello [G]world [Am]again [F]now\n[C]Hello [G]world [F]now\n{sot}\ne|-------0-----|\nB|-----1---1---|\nG|---0-------0-|\nD|-2-----------|\nA|-------------|\nE|-------------|\n{eot}\n"

    @Test fun songPlanForLevels() {
        val p1 = Learn.songPlan(song, 1, labels)
        assertEquals(0, p1.capo)
        assertEquals(listOf("C", "G", "Am", "Fmaj7"), p1.chords.map { it.shape })
        assertEquals("F", p1.chords.first { it.shape == "Fmaj7" }.from)
        assertEquals(60 to 120, p1.tempo)
        assertEquals("C" to "G", p1.switches[0])
        assertTrue(p1.riffs.isEmpty())
        assertEquals(LessonType.PROGRESSION, p1.lessons.last().type)
        val p3 = Learn.songPlan(song, 3, labels)
        assertTrue("F" in p3.chords.map { it.shape })
        assertEquals(1, p3.riffs.size)
        assertEquals(85 to 120, p3.tempo)
        assertTrue(Learn.songPlan("[Bb]one [Eb]two [F]three [Gm]four", 2, labels).capo > 0)
        assertFalse(p3.lessons.none { it.type == LessonType.RIFF })
    }
}
