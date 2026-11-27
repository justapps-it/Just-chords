package it.justapps.justchords.ui

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.justapps.justchords.audio.PracticeClock
import it.justapps.justchords.core.ChordPro
import it.justapps.justchords.core.Course
import it.justapps.justchords.core.Dsp
import it.justapps.justchords.core.L10n
import it.justapps.justchords.core.Learn
import it.justapps.justchords.core.Lesson
import it.justapps.justchords.core.LessonType
import it.justapps.justchords.core.LiveChords
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.PlanLabels
import it.justapps.justchords.core.RenderOptions
import it.justapps.justchords.core.SongPlan
import it.justapps.justchords.data.LinkAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LEVEL_KEYS = mapOf(1 to "learnBeginner", 2 to "level2", 3 to "level3")

@Composable
fun levelName(level: Int): String = rememberT()(LEVEL_KEYS[level] ?: "learnBeginner")

private fun lessonIcon(t: LessonType): ImageVector = when (t) {
    LessonType.CHORD -> Icons.Rounded.TouchApp
    LessonType.SWITCH -> Icons.Rounded.SwapHoriz
    LessonType.STRUM -> Icons.Rounded.GraphicEq
    LessonType.RIFF -> Icons.Rounded.MusicNote
    LessonType.PROGRESSION -> Icons.Rounded.PlayArrow
}

// ------------------------------------------------------------- Learn tab

@Composable
fun LearnScreen() {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val st = settings()
    val acc by a.account.state.collectAsState()
    val lib by a.library.state.collectAsState()
    val level = remember(acc.profile.level, lib.songs) { a.account.effectiveLevel(lib.songs) }
    val done = acc.profile.done.toSet()
    val (mine, others) = remember(level) { Learn.coursesFor(level) }
    val next = remember(level, done) { Learn.nextLesson(level, done) }

    Page(t("learn"), onBack = null) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Muted(t("learnLead"), Modifier.padding(horizontal = 4.dp)) }
            item {
                JustCard(Modifier.fillMaxWidth()) {
                    Eyebrow(t("yourLevel"))
                    Spacer(Modifier.height(8.dp))
                    Segmented(listOf(1 to levelName(1), 2 to levelName(2), 3 to levelName(3)), level, { a.account.setProfile(acc.profile.copy(level = it)) }, Modifier.fillMaxWidth())
                }
            }
            if (acc.profile.onboarded == 0L) item {
                JustCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("onboarding") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Quiz, null, tint = JustOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("takeTest"), fontWeight = FontWeight.SemiBold)
                            Muted(t("takeTestText"), small = true)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = just.muted)
                    }
                }
            }
            item {
                if (next != null) {
                    val (course, lesson) = next
                    JustCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("lesson/${enc(lesson.id)}") }) {
                        Eyebrow(t("continueLesson"), color = JustOrange)
                        Spacer(Modifier.height(6.dp))
                        Text(lesson.title.of(st.lang), style = MaterialTheme.typography.titleLarge)
                        Muted(course.title.of(st.lang), small = true)
                        Spacer(Modifier.height(12.dp))
                        Button({ nav.navigate("lesson/${enc(lesson.id)}") }, colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(t("learnStart"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else JustCard(Modifier.fillMaxWidth()) { Text(t("allDone")) }
            }
            item { SectionHeader(t("coursesForYou")) }
            items(mine, key = { it.id }) { c -> CourseCard(c, done) }
            item { SectionHeader(t("otherCourses")) }
            items(others, key = { it.id }) { c -> CourseCard(c, done) }
        }
    }
}

@Composable
private fun CourseCard(c: Course, done: Set<String>) {
    val nav = LocalNav.current
    val st = settings()
    val t = rememberT()
    val p = Learn.progress(c, done)
    JustCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("course/${c.id}") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title.of(st.lang), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Muted(c.desc.of(st.lang), small = true)
            }
            if (p >= 1f) Icon(Icons.Rounded.CheckCircle, null, tint = JustOrange)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator({ p }, Modifier.weight(1f).height(6.dp).clip(CircleShape), color = JustOrange, trackColor = just.line)
            Spacer(Modifier.width(10.dp))
            Text("${c.lessons.count { it.id in done }}/${c.lessons.size} ${t("lessons")}", color = just.muted, fontSize = 12.sp, fontFamily = PlexMono)
        }
    }
}

@Composable
fun CourseScreen(id: String) {
    val nav = LocalNav.current
    val st = settings()
    val t = rememberT()
    val acc by app().account.state.collectAsState()
    val c = Learn.courses.firstOrNull { it.id == id } ?: return
    val done = acc.profile.done.toSet()
    Page(c.title.of(st.lang)) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Muted("${levelName(c.level)} · ${c.desc.of(st.lang)}", Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            items(c.lessons, key = { it.id }) { l ->
                LessonRow(l.title.of(st.lang), lessonIcon(l.type), l.id in done, t("lessonDone")) { nav.navigate("lesson/${enc(l.id)}") }
            }
        }
    }
}

@Composable
private fun LessonRow(title: String, icon: ImageVector, done: Boolean, doneLabel: String, sub: String? = null, onClick: () -> Unit) {
    JustCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (done) JustOrange else just.orangeSoft), contentAlignment = Alignment.Center) {
                Icon(if (done) Icons.Rounded.CheckCircle else icon, if (done) doneLabel else null, tint = if (done) Color.White else JustOrange)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                sub?.let { Muted(it, small = true) }
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = just.muted)
        }
    }
}

// --------------------------------------------------------- lesson route

/** A course lesson, or (with [songId]) a lesson of a song's plan. */
@Composable
fun LessonRoute(id: String, songId: String?) {
    val a = app()
    val st = settings()
    val acc by a.account.state.collectAsState()
    val lib by a.library.state.collectAsState()
    val level = a.account.effectiveLevel(lib.songs)
    val lesson: Lesson? = if (songId != null) {
        val song = lib.songs.firstOrNull { it.id == songId }
        remember(song?.chordpro, level) { song?.let { Learn.songPlan(it.chordpro, level, planLabels()).lessons.firstOrNull { l -> l.id == id } } }
    } else Learn.lessonById(id)?.second
    if (lesson == null) return
    val doneId = if (songId != null) "song:$songId:$id" else id
    Page(lesson.title.of(st.lang)) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { LessonPlayer(lesson, doneId in acc.profile.done) { a.account.markDone(doneId) } }
        }
    }
}

private fun planLabels() = PlanLabels(
    L10n(I18n.it["stepChords"]!!, I18n.en["stepChords"]!!), L10n(I18n.it["stepSwitch"]!!, I18n.en["stepSwitch"]!!),
    L10n(I18n.it["stepRhythm"]!!, I18n.en["stepRhythm"]!!), L10n(I18n.it["stepRiff"]!!, I18n.en["stepRiff"]!!), L10n(I18n.it["stepPlay"]!!, I18n.en["stepPlay"]!!),
)

/** True when what the microphone hears fits the chord to learn (an Fmaj7 heard as F or Am is fine). */
fun chordMatches(target: String, heard: String?): Boolean {
    val t = Music.parseChord(target) ?: return false
    val h = Music.parseChord(heard ?: return false) ?: return false
    if (t.root == h.root && (t.suffix == "5" || Music.simplify(t).suffix == Music.simplify(h).suffix)) return true
    val tn = Music.notes(t).toSet()
    return Music.notes(h).all { it in tn } && Music.notes(h).size >= 3
}

// ---------------------------------------------------------- the player

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LessonPlayer(lesson: Lesson, done: Boolean, onDone: () -> Unit) {
    val t = rememberT()
    val st = settings()
    fun label(s: String) = Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s
    val steps = remember(lesson) { Learn.tempoSteps(lesson.tempo) }
    val tl = remember(lesson) { Learn.timeline(lesson) }
    val notes = remember(lesson) { lesson.notes?.let(Learn::parseNotes) ?: emptyList() }
    var stage by remember(lesson) { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf<PracticeClock?>(null) }
    var pos by remember { mutableDoubleStateOf(-4.0) }
    var finished by remember(lesson) { mutableStateOf(false) }
    var complete by remember(lesson) { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { clock?.stop() } }

    fun stopClock() {
        clock?.stop()
        clock = null
    }
    fun play(listenOnly: Boolean = false) {
        stopClock()
        finished = false
        listening = listenOnly
        val bpm = steps.getOrElse(stage) { 70 }
        val sounds = if (listenOnly) tl.steps.filter { it.note != null }.map { s -> Triple(s.at, notes[s.note!!].frets.map { (str, f) -> Learn.midiOf(str, f) }, s.beats) } else emptyList()
        clock = PracticeClock(bpm, tl.total, tl.bar, if (listenOnly) 0 else tl.bar, sounds, clicks = !listenOnly) {
            // On the audio thread: hand over to the UI.
            finished = true
        }.also { it.start() }
    }
    if (clock != null) {
        KeepOn()
        LaunchedEffect(clock) {
            while (isActive) {
                pos = clock?.position ?: 0.0
                if (finished) {
                    stopClock()
                    if (!listening && stage == steps.lastIndex) {
                        complete = true
                        onDone()
                    }
                    break
                }
                delay(30)
            }
        }
    }

    lesson.tip?.let { tip -> JustCard(Modifier.fillMaxWidth(), padding = 16.dp) { Text(tip.of(st.lang)) } }
    Spacer(Modifier.height(12.dp))

    if (lesson.type == LessonType.CHORD) {
        ChordCheck(lesson.chord!!, done) { onDone() }
        return
    }

    // Tempo steps: slow → target.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Speed, null, tint = just.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        steps.forEachIndexed { i, bpm ->
            val on = i == stage
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (on) JustOrange else if (i < stage) just.orangeSoft else Color.Transparent)
                    .border(1.dp, if (on) JustOrange else just.line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("$bpm", color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
        Text("BPM", color = just.muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(12.dp))

    JustCard(Modifier.fillMaxWidth()) {
        val now = Learn.at(tl.steps, maxOf(0.0, pos))
        if (clock != null && pos < 0) {
            Text(t("getReady"), color = just.muted)
            Text("${(-pos).toInt() + 1}", Modifier.fillMaxWidth(), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = JustOrange, textAlign = TextAlign.Center, fontFamily = PlexMono)
        } else if (lesson.type == LessonType.RIFF) {
            RiffView(notes, if (clock != null) now.note?.note else null)
            Muted(t("tabLegend"), Modifier.padding(top = 8.dp), small = true)
        } else {
            val cur = now.chord?.chord ?: tl.steps.firstOrNull { it.chord != null }?.chord ?: ""
            val nxt = now.next?.chord
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(t("nowLabel"))
                    Text(label(cur), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = JustOrange, fontFamily = PlexMono)
                    if (nxt != null) Text("${t("nextLabel")}: ${label(nxt)}", color = just.muted, fontFamily = PlexMono, fontSize = 16.sp)
                }
                Box(Modifier.widthIn(max = 150.dp)) { ChordCard(cur, label(cur)) }
            }
            val pattern = Learn.parsePattern(if (lesson.type == LessonType.STRUM) lesson.pattern ?: "" else lesson.strum ?: "")
            if (pattern.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                StrumRow(pattern, if (clock != null && pos >= 0) ((pos % 4) / (4.0 / pattern.size)).toInt() else -1)
                Muted(t("strumLegend"), Modifier.padding(top = 6.dp), small = true)
            }
        }
        if (clock != null && pos >= 0) {
            Spacer(Modifier.height(10.dp))
            BeatDots(tl.bar, (pos % tl.bar).toInt())
            LinearProgressIndicator({ (pos / tl.total).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 10.dp), color = JustOrange, trackColor = just.line)
        }
    }
    Spacer(Modifier.height(12.dp))

    if (complete || (done && clock == null && stage == 0 && !finished)) {
        if (complete) JustCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = JustOrange, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(t("learnComplete"), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (clock != null) {
            Button(::stopClock, Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text(t("learnPause"), fontWeight = FontWeight.SemiBold)
            }
        } else if (finished && !listening && stage < steps.lastIndex) {
            Button({ stage++; play() }, Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                Icon(Icons.Rounded.Speed, null); Spacer(Modifier.width(6.dp)); Text("${t("learnFaster")} · ${steps[stage + 1]} BPM", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton({ play() }, Modifier.height(52.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text(t("learnAgain")) }
        } else {
            Button({ if (complete) { complete = false; stage = 0 }; play() }, Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp))
                Text(if (complete) t("learnAgain") else "${t("learnStart")} · ${steps.getOrElse(stage) { 0 }} BPM", fontWeight = FontWeight.SemiBold)
            }
        }
        if (lesson.type == LessonType.RIFF && clock == null) {
            OutlinedButton({ play(listenOnly = true) }, Modifier.height(52.dp)) { Icon(Icons.Rounded.Hearing, null); Spacer(Modifier.width(6.dp)); Text(t("listenRiff")) }
        }
        if (!done && !complete && clock == null) OutlinedButton(onDone, Modifier.height(52.dp)) { Text(t("markDone")) }
    }
}

@Composable
private fun BeatDots(bar: Int, beat: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(bar) { i ->
            Box(Modifier.padding(horizontal = 6.dp).size(if (i == beat) 16.dp else 12.dp).clip(CircleShape).background(if (i == beat) JustOrange else just.line))
        }
    }
}

@Composable
private fun StrumRow(pattern: List<Char>, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        pattern.forEachIndexed { i, c ->
            val on = i == current
            val sym = when (c) { 'D' -> "↓"; 'U' -> "↑"; 'X' -> "✕"; else -> "·" }
            Box(
                Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (on) JustOrange else if (c == '-') Color.Transparent else just.orangeSoft).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(sym, fontSize = if (pattern.size > 8) 16.sp else 22.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else if (c == '-') just.muted else JustOrange)
            }
        }
    }
}

@Composable
private fun RiffView(notes: List<it.justapps.justchords.core.Note>, current: Int?) {
    val (lines, cols) = remember(notes) { Learn.tabLines(notes) }
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val charW = with(density) { 9.6.sp.toPx() }
    LaunchedEffect(current) { current?.let { scroll.animateScrollTo(maxOf(0, (cols[it] * charW - 120).toInt())) } }
    Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        for (l in lines) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    val c = current?.let { cols[it] }
                    l.forEachIndexed { i, ch ->
                        if (c != null && i >= c && i < c + 2 && ch.isDigit()) {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, background = JustOrange, fontWeight = FontWeight.Bold))
                            append(ch)
                            pop()
                        } else append(ch)
                    }
                },
                fontFamily = PlexMono, fontSize = 16.sp, softWrap = false,
            )
        }
    }
}

/** Learn a chord: diagram, then the microphone checks it. */
@Composable
private fun ChordCheck(chord: String, done: Boolean, onHeard: () -> Unit) {
    val t = rememberT()
    val st = settings()
    var err by remember { mutableStateOf("") }
    val (mic, start) = rememberMic { err = t("micDenied") }
    var heard by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ChordCard(chord, Music.parseChord(chord)?.let { Music.format(it, st.notation) } ?: chord, big = true) }
    Spacer(Modifier.height(12.dp))
    if (mic != null && !ok) {
        KeepOn()
        DisposableEffect(mic) { onDispose { mic.stop() } }
        LaunchedEffect(mic) {
            val dsp = Dsp()
            val live = LiveChords()
            val n = if (mic.sampleRate > 30000) 8192 else 4096
            var streak = 0
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val (chroma, rms) = dsp.chroma(mic.read(n), mic.sampleRate.toFloat())
                    val name = live.push(chroma, rms).chord?.name
                    streak = if (chordMatches(chord, name)) streak + 1 else 0
                    withContext(Dispatchers.Main) {
                        heard = name
                        if (streak >= 4 && !ok) { ok = true; onHeard() }
                    }
                    delay(100)
                }
            }
        }
    }
    JustCard(Modifier.fillMaxWidth()) {
        when {
            ok || (done && mic == null) -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = JustOrange, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(if (ok) t("heard") else t("lessonDone"), style = MaterialTheme.typography.titleLarge)
            }
            mic == null -> {
                Muted(t("playChordCheck"))
                Spacer(Modifier.height(10.dp))
                MicButton(start)
            }
            else -> {
                Eyebrow(t("micOn"), color = JustOrange)
                Text(heard?.let { Music.parseChord(it)?.let { c -> Music.format(c, st.notation) } } ?: "…", fontSize = 44.sp, fontFamily = PlexMono, fontWeight = FontWeight.Bold, color = if (chordMatches(chord, heard)) JustOrange else just.muted)
                Muted(t("playChordCheck"), small = true)
            }
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
    }
    if (!done && !ok) TextButton2(t("markDone"), onHeard)
}

@Composable
private fun TextButton2(text: String, onClick: () -> Unit) = androidx.compose.material3.TextButton(onClick) { Text(text) }

// ------------------------------------------------------- learn a song

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongLearnScreen(songId: String) {
    val t = rememberT()
    val nav = LocalNav.current
    val st = settings()
    val a = app()
    val acc by a.account.state.collectAsState()
    val lib by a.library.state.collectAsState()
    val song = lib.songs.firstOrNull { it.id == songId } ?: return
    val level = a.account.effectiveLevel(lib.songs)
    val plan = remember(song.chordpro, level) { Learn.songPlan(song.chordpro, level, planLabels()) }
    val done = acc.profile.done.toSet()
    fun label(s: String) = Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s
    fun open(l: Lesson) = nav.navigate("lesson/${enc(l.id)}?song=${enc(songId)}")
    fun isDone(l: Lesson) = "song:$songId:${l.id}" in done

    Page("${t("learn")}: ${song.title}") { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Muted("${levelName(level)} · ${t("learnSongLead")}", Modifier.padding(horizontal = 4.dp)) }
            if (plan.capo > 0) item {
                JustCard(Modifier.fillMaxWidth(), padding = 16.dp) { Text("${t("putCapo")} ${plan.capo}", fontWeight = FontWeight.SemiBold, color = JustOrange) }
            }
            item { SectionHeader("1 · ${t("stepChords")}") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plan.chords) { c ->
                        val l = plan.lessons.first { it.id == "chord:${c.shape}" }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.width(120.dp)) { ChordCard(c.shape, label(c.shape)) { open(l) } }
                            Text(
                                when {
                                    isDone(l) -> "✓ ${t("lessonDone")}"
                                    c.from != null -> "${t("easierShape")} ${label(c.from)}"
                                    else -> " "
                                },
                                fontSize = 11.sp, color = if (isDone(l)) JustOrange else just.muted, textAlign = TextAlign.Center, modifier = Modifier.width(120.dp).padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            val switches = plan.lessons.filter { it.type == LessonType.SWITCH }
            if (switches.isNotEmpty()) {
                item { SectionHeader("2 · ${t("stepSwitch")}") }
                items(switches, key = { it.id }) { l -> LessonRow("${label(l.chords[0])} ↔ ${label(l.chords[1])}", Icons.Rounded.SwapHoriz, isDone(l), t("lessonDone")) { open(l) } }
            }
            item { SectionHeader("3 · ${t("stepRhythm")}") }
            plan.lessons.firstOrNull { it.type == LessonType.STRUM }?.let { l ->
                item { LessonRow(l.pattern!!.map { c -> when (c) { 'D' -> "↓"; 'U' -> "↑"; 'X' -> "✕"; else -> "·" } }.joinToString(" "), Icons.Rounded.GraphicEq, isDone(l), t("lessonDone"), "${plan.tempo.first} → ${plan.tempo.second} BPM") { open(l) } }
            }
            val riffs = plan.lessons.filter { it.type == LessonType.RIFF }
            if (riffs.isNotEmpty()) {
                item { SectionHeader("4 · ${t("stepRiff")}") }
                items(riffs, key = { it.id }) { l -> LessonRow(l.title.of(st.lang), Icons.Rounded.MusicNote, isDone(l), t("lessonDone")) { open(l) } }
            }
            plan.lessons.firstOrNull { it.type == LessonType.PROGRESSION }?.let { l ->
                item { SectionHeader("${if (riffs.isEmpty()) 4 else 5} · ${t("stepPlay")}") }
                item { LessonRow(l.chords.joinToString("  ") { label(it) }, Icons.Rounded.PlayArrow, isDone(l), t("lessonDone"), "${plan.tempo.first} → ${plan.tempo.second} BPM") { open(l) } }
            }
            item { PlayAlong(song.title, song.artist, song.youtube, level) }
            val parsed = ChordPro.parse(song.chordpro)
            item { Spacer(Modifier.height(4.dp)) }
            sheetItems(parsed, RenderOptions(-plan.capo, st.notation, false, false), 1f, null)
        }
    }
}

/** The original video, slowed down first, then at full speed (played by the app, no embed). */
@Composable
private fun PlayAlong(title: String, artist: String, youtube: String?, level: Int) {
    val t = rememberT()
    val a = app()
    val speeds = remember(level) { Learn.alongSpeeds(level) }
    var speed by remember { mutableIntStateOf(0) }
    var id by remember { mutableStateOf(youtube) }
    var started by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    JustCard(Modifier.fillMaxWidth()) {
        Text(t("playAlong"), style = MaterialTheme.typography.titleLarge)
        Muted(t("playAlongHint"), small = true)
        Spacer(Modifier.height(10.dp))
        val v = id
        if (started && v != null) {
            NativeYouTube(v, speeds, initialSpeed = 0, speedIndex = speed, onEnded = { mp ->
                // At the end: again, a bit faster.
                if (speed < speeds.lastIndex) {
                    speed++
                    mp.seekTo(0)
                    mp.play()
                }
            })
        } else {
            Button(
                {
                    scope.launch {
                        if (id == null) id = runCatching { a.account.api.videos("$title $artist".trim()).firstOrNull()?.id }.getOrNull()
                        if (id == null) failed = true else started = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = JustOrange),
            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(t("learnStart")) }
            if (failed) Text(t("noVideo"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
