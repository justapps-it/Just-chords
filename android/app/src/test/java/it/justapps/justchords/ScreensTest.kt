package it.justapps.justchords

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import it.justapps.justchords.core.Profile
import it.justapps.justchords.ui.JustChordsRoot
import it.justapps.justchords.ui.JustTheme
import it.justapps.justchords.ui.HowToPlayCard
import it.justapps.justchords.ui.JustCard
import it.justapps.justchords.ui.SheetPreview
import it.justapps.justchords.core.Analysis
import it.justapps.justchords.core.Segment
import it.justapps.justchords.core.Word
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen to build/outputs/roborazzi (./gradlew recordRoborazziDebug). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<ChordsApp>()

    private fun seed(): String {
        val lib = app.library
        if (lib.state.value.songs.isNotEmpty()) return lib.state.value.songs.first().id
        app.settings.update { it.copy(lang = "it", welcomed = true) }
        app.account.setProfile(Profile(genres = listOf("rock", "itpop")))
        lib.saveSong("{title: Albachiara}\n{artist: Vasco Rossi}\n\n[C]Respiri pian[G]o per non far rumore\n[Am]ti addormenti di [F]sera\n")
        lib.saveSong("{title: Let It Be}\n{artist: The Beatles}\n\n[C]When I find my[G]self in times of [Am]trouble\n")
        val s = lib.saveSong(
            """
            {title: Wonderwall}
            {artist: Oasis}
            {capo: 2}
            {key: Em}

            {start_of_verse: Verse 1}
            [Em7]Today is [G]gonna be the day that they're [Dsus4]gonna throw it back to [A7sus4]you
            [Em7]By now you [G]should've somehow real[Dsus4]ised what you gotta [A7sus4]do
            {end_of_verse}

            {start_of_chorus: Chorus}
            And [C]all the roads we [D]have to walk are [Em]winding
            And [C]all the lights that [D]lead us there are [Em]blinding
            {end_of_chorus}
            """.trimIndent(),
        ) { it.copy(favorite = true) }
        return s.id
    }

    private fun shot(name: String, route: String?, theme: String = "light") {
        rule.setContent { JustTheme(theme) { JustChordsRoot(route) {} } }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun home() { seed(); shot("home", null) }
    @Test fun homeDark() { seed(); shot("home-dark", null, "dark") }
    @Test fun song() { shot("song", "song/${seed()}") }
    @Test fun songDark() { shot("song-dark", "song/${seed()}", "dark") }
    @Test fun stage() { shot("stage", "stage/song/${seed()}", "dark") }
    @Test fun find() { seed(); shot("find", "find") }
    @Test fun tools() { seed(); shot("tools", "tools") }
    @Test fun tuner() { seed(); shot("tuner", "tuner") }
    @Test fun metronome() { seed(); shot("metronome", "metronome") }
    @Test fun dictionary() { seed(); shot("dictionary", "chords") }
    @Test fun profile() { seed(); shot("profile", "profile") }
    @Test fun import() { seed(); shot("import", "import?title=&text=${android.net.Uri.encode("Wonderwall - Oasis\n\nEm7   G\nToday is gonna be the day\nDsus4    A7sus4\nThat they're gonna throw it back to you")}") }
    @Test fun analyze() { seed(); shot("analyze", "analyze") }

    @Test fun howToPlay() {
        seed()
        val segs = listOf(Segment(0.0, 4.0, "Eb"), Segment(4.0, 8.0, "Ab"), Segment(8.0, 12.0, "Bb"), Segment(12.0, 16.0, "Cm"))
        val how = Analysis.howToPlay(segs, 96)
        val words = listOf(Word("Quando", 0.2, 0.6), Word("la", 0.7, 0.8), Word("sera", 0.9, 1.6), Word("scende", 4.1, 4.8), Word("piano", 4.9, 5.6), Word("resto", 8.2, 8.9), Word("qui", 9.0, 9.6))
        val cp = Analysis.chordPro("Prova", "Just", segs, words, how, "Ritmo")
        rule.setContent {
            JustTheme("light") {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                    HowToPlayCard(how)
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                    JustCard { SheetPreview(cp) }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/how-to-play.png")
    }

    @Test fun learn() { seed(); shot("learn", "learn") }
    @Test fun learnDark() { seed(); shot("learn-dark", "learn", "dark") }
    @Test fun course() { seed(); shot("course", "course/first-chords") }
    @Test fun lessonSwitch() { seed(); shot("lesson-switch", "lesson/p-pop-song") }
    @Test fun lessonRiff() { seed(); shot("lesson-riff", "lesson/m-frere") }
    @Test fun lessonChord() { seed(); shot("lesson-chord", "lesson/c-em") }
    @Test fun songLearn() { shot("song-learn", "learnsong/${seed()}") }
    @Test fun onboarding() { seed(); shot("onboarding", "onboarding") }
    @Test fun welcome() {
        seed()
        app.settings.update { it.copy(welcomed = false) }
        shot("welcome", null)
    }

    @Test fun legal() { seed(); shot("privacy", "legal/privacy") }
}
