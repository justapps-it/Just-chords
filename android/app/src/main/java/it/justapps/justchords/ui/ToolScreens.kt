package it.justapps.justchords.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import it.justapps.justchords.audio.Metronome
import it.justapps.justchords.audio.Mic
import it.justapps.justchords.core.Chord
import it.justapps.justchords.core.Dsp
import it.justapps.justchords.core.LiveChords
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.Notation
import it.justapps.justchords.core.Voicings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ToolsScreen() {
    val t = rememberT()
    val nav = LocalNav.current
    Page(t("tools"), onBack = null) { pad ->
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(TOOLS) { tool ->
                JustCard(Modifier.aspectRatio(1f), onClick = { nav.navigate(tool.route) }) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(just.orangeSoft), contentAlignment = Alignment.Center) {
                        Icon(tool.icon, null, tint = JustOrange, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(t(tool.key), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun midiName(m: Int, n: Notation) = Music.noteName(m % 12, n) + (m / 12 - 1)

/** Opens the microphone (asking permission) while the screen is shown. */
@Composable
private fun rememberMic(onDenied: () -> Unit): Pair<Mic?, () -> Unit> {
    val ctx = LocalContext.current
    var mic by remember { mutableStateOf<Mic?>(null) }
    val open = { mic = runCatching { Mic.open() }.getOrNull().also { if (it == null) onDenied() } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) open() else onDenied() }
    DisposableEffect(Unit) { onDispose { mic?.stop() } }
    return mic to {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) open()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun MicButton(onClick: () -> Unit) {
    val t = rememberT()
    Button(onClick, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
        Icon(Icons.Rounded.Mic, null)
        Spacer(Modifier.width(8.dp))
        Text(t("micStart"), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun KeepOn() {
    val v = LocalView.current
    DisposableEffect(Unit) { v.keepScreenOn = true; onDispose { v.keepScreenOn = false } }
}

// ------------------------------------------------------------------ tuner

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunerScreen() {
    val t = rememberT()
    val a = app()
    val st = settings()
    var err by remember { mutableStateOf("") }
    val (mic, start) = rememberMic { err = t("micDenied") }
    var tuningKey by remember { mutableStateOf(if (st.instrument == "ukulele") "ukulele" else st.tuning) }
    var reading by remember { mutableStateOf<Triple<Int, Double, Double>?>(null) }
    var menu by remember { mutableStateOf(false) }
    val tuning = if (tuningKey == "chromatic") null else Voicings.tuning(tuningKey)

    if (mic != null) {
        KeepOn()
        LaunchedEffect(mic, st.a4) {
            val dsp = Dsp()
            val hist = ArrayDeque<Float>()
            val n = if (mic.sampleRate > 30000) 4096 else 2048
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val frame = mic.read(n)
                    val rms = sqrt(frame.fold(0f) { s, x -> s + x * x } / n)
                    if (rms > 0.008f) {
                        val hz = dsp.yin(frame, mic.sampleRate.toFloat())
                        if (hz > 25 && hz < 2000 && dsp.confidence > 0.8f) {
                            hist.addLast(hz); if (hist.size > 5) hist.removeFirst()
                            val med = hist.sorted()[hist.size / 2].toDouble()
                            val (midi, cents) = Dsp.noteFromHz(med, st.a4.toDouble())
                            reading = Triple(midi, cents, med)
                        }
                    }
                    delay(50)
                }
            }
        }
    }

    var target: Int? = reading?.first
    var cents = reading?.second ?: 0.0
    if (reading != null && tuning != null) {
        val exact = reading!!.first + reading!!.second / 100
        target = tuning.strings.minBy { abs(it - exact) }
        cents = (exact - target) * 100
    }
    val inTune = reading != null && abs(cents) < 4
    val angle by animateFloatAsState((if (reading != null) cents.coerceIn(-50.0, 50.0) * 0.9 else 0.0).toFloat(), spring(dampingRatio = 0.7f, stiffness = 120f), label = "needle")
    val dial by animateColorAsState(if (inTune) just.ok else JustOrange, label = "dial")

    Page(t("tuner")) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton({ menu = true }) {
                            Text(if (tuningKey == "chromatic") t("chromatic") else if (tuningKey == "ukulele") "${t("ukulele")} (G C E A)" else tuning!!.name)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            for (tu in Voicings.TUNINGS) DropdownMenuItem({ Text(tu.name) }, { tuningKey = tu.id; menu = false })
                            DropdownMenuItem({ Text("${t("ukulele")} (G C E A)") }, { tuningKey = "ukulele"; menu = false })
                            DropdownMenuItem({ Text(t("chromatic")) }, { tuningKey = "chromatic"; menu = false })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("A4", fontFamily = PlexMono, color = just.muted)
                    IconButton({ a.settings.update { it.copy(a4 = (it.a4 - 1).coerceAtLeast(415)) } }) { Text("−", fontSize = 20.sp) }
                    Text("${st.a4}", fontFamily = PlexMono, fontWeight = FontWeight.SemiBold)
                    IconButton({ a.settings.update { it.copy(a4 = (it.a4 + 1).coerceAtMost(466)) } }) { Text("+", fontSize = 20.sp) }
                }
            }
            item {
                JustCard(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    val fg = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.fillMaxWidth().aspectRatio(200f / 120f)) {
                        val u = size.width / 200f
                        val c = Offset(100 * u, 105 * u)
                        for (i in 0..20) {
                            val ang = Math.toRadians((i - 10) * 4.5)
                            val long = i % 5 == 0
                            val r2 = if (long) 68f else 73f
                            drawLine(
                                if (i == 10) dial else fg.copy(alpha = if (long) 0.7f else 0.35f),
                                Offset(c.x + sin(ang).toFloat() * 80 * u, c.y - cos(ang).toFloat() * 80 * u),
                                Offset(c.x + sin(ang).toFloat() * r2 * u, c.y - cos(ang).toFloat() * r2 * u),
                                (if (long) 2f else 1f) * u, StrokeCap.Round,
                            )
                        }
                        val na = Math.toRadians(angle.toDouble())
                        drawLine(dial, c, Offset(c.x + sin(na).toFloat() * 75 * u, c.y - cos(na).toFloat() * 75 * u), 3 * u, StrokeCap.Round)
                        drawCircle(dial, 5 * u, c)
                    }
                    Text(target?.let { midiName(it, st.notation) } ?: "·", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = if (inTune) just.ok else MaterialTheme.colorScheme.onSurface)
                    Text(
                        reading?.let { "${if (cents > 0) "+" else ""}${cents.toInt()} cent · ${"%.1f".format(it.third)} Hz" } ?: t("tunerHint"),
                        Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = just.muted,
                    )
                }
            }
            tuning?.let { tu ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (m in tu.strings) {
                            val on = target == m
                            Box(Modifier.size(46.dp).clip(CircleShape).background(if (on) (if (inTune) just.ok else JustOrange) else just.soft), contentAlignment = Alignment.Center) {
                                Text(midiName(m, st.notation), color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                if (mic == null) MicButton(start)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ metronome

@Composable
fun MetronomeScreen() {
    val t = rememberT()
    val m = remember { Metronome() }
    var bpm by remember { mutableIntStateOf(100) }
    var beats by remember { mutableIntStateOf(4) }
    var sub by remember { mutableIntStateOf(1) }
    var running by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(-1) }
    val taps = remember { mutableStateListOf<Long>() }
    LaunchedEffect(bpm, beats, sub) { m.config = Metronome.Config(bpm, beats, sub) }
    DisposableEffect(Unit) { onDispose { m.stop() } }
    LaunchedEffect(running) {
        if (!running) { beat = -1; return@LaunchedEffect }
        while (isActive) { withFrameNanos { }; beat = m.beat }
    }
    if (running) KeepOn()

    Page(t("metronome")) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in 0 until beats) {
                    val on = i == beat
                    val scale by animateFloatAsState(if (on) 1.25f else 1f, label = "beat")
                    Box(
                        Modifier.size(if (beats > 7) 20.dp else 28.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                            .background(if (on) JustOrange else if (i == 0) just.orangeSoft else just.soft),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton({ bpm = (bpm - 1).coerceAtLeast(30) }, Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = just.soft, contentColor = MaterialTheme.colorScheme.onSurface)) { Text("−", fontSize = 26.sp) }
                Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$bpm", fontSize = 76.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp)
                    Eyebrow(t("bpm"))
                }
                FilledIconButton({ bpm = (bpm + 1).coerceAtMost(300) }, Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = just.soft, contentColor = MaterialTheme.colorScheme.onSurface)) { Text("+", fontSize = 26.sp) }
            }
            Slider(bpm.toFloat(), { bpm = it.toInt() }, valueRange = 30f..300f, modifier = Modifier.padding(vertical = 12.dp), colors = SliderDefaults.colors(thumbColor = JustOrange, activeTrackColor = JustOrange))
            Eyebrow(t("beats"), Modifier.align(Alignment.Start))
            Segmented(listOf(2, 3, 4, 5, 6, 7, 9, 12).map { it to "$it" }, beats, { beats = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Eyebrow(t("sub"), Modifier.align(Alignment.Start).padding(top = 8.dp))
            Segmented(listOf(1 to "♩", 2 to "♪♪", 3 to "3", 4 to "♬♬"), sub, { sub = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    { if (running) { m.stop(); running = false } else { m.start(); running = true } },
                    Modifier.weight(1f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange),
                ) {
                    Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) t("stop") else t("start"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
                OutlinedButton({
                    val now = System.currentTimeMillis()
                    taps += now
                    while (taps.size > 8) taps.removeAt(0)
                    Metronome.tapTempo(taps)?.takeIf { it in 30..300 }?.let { bpm = it }
                }, Modifier.weight(1f).height(60.dp)) { Text(t("tap"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------ listen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenScreen() {
    val t = rememberT()
    val a = app()
    val nav = LocalNav.current
    val st = settings()
    var err by remember { mutableStateOf("") }
    val (mic, start) = rememberMic { err = t("micDenied") }
    var chord by remember { mutableStateOf<String?>(null) }
    var ranking by remember { mutableStateOf<List<String>>(emptyList()) }
    val seq = remember { mutableStateListOf<String>() }

    if (mic != null) {
        KeepOn()
        LaunchedEffect(mic) {
            val dsp = Dsp()
            val live = LiveChords()
            val n = if (mic.sampleRate > 30000) 8192 else 4096
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val (chroma, rms) = dsp.chroma(mic.read(n), mic.sampleRate.toFloat())
                    val r = live.push(chroma, rms)
                    val name = r.chord?.name
                    withContext(Dispatchers.Main) {
                        chord = name
                        ranking = r.ranking.map { it.first }
                        if (name != null && seq.lastOrNull() != name) { seq += name; if (seq.size > 64) seq.removeAt(0) }
                    }
                    delay(100)
                }
            }
        }
    }
    fun label(s: String) = Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s

    Page(t("listen")) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item { Muted(t("listenHint")); Spacer(Modifier.height(16.dp)) }
            if (mic == null) item { MicButton(start) }
            else item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(chord?.let(::label) ?: "…", fontSize = 84.sp, fontWeight = FontWeight.ExtraBold, color = JustOrange, fontFamily = PlexMono)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { ranking.drop(1).take(3).forEach { Text(label(it), color = just.muted, fontFamily = PlexMono) } }
                    Spacer(Modifier.height(12.dp))
                    chord?.let { ChordCard(it, label(it), big = true) }
                    Muted(t("listening"), Modifier.padding(top = 8.dp), small = true)
                }
            }
            if (err.isNotEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error) }
            if (seq.isNotEmpty()) item {
                SectionHeader(t("history"), Modifier.padding(top = 20.dp)) {
                    IconButton({ seq.clear() }) { Icon(Icons.Rounded.Close, null) }
                }
                Text(seq.joinToString("  ") { label(it) }, fontFamily = PlexMono, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton({
                    val lines = seq.chunked(4).joinToString("\n") { l -> l.joinToString(" ") { "[$it]" } }
                    val s = a.library.saveSong("{title: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}}\n\n$lines\n")
                    nav.navigate("song/${s.id}")
                }) { Text(t("saveAsSong")) }
            }
        }
    }
}

// ------------------------------------------------------------------ dictionary

private val QUALITIES = listOf("", "m", "7", "m7", "maj7", "sus2", "sus4", "add9", "6", "m6", "9", "m9", "dim", "dim7", "m7b5", "aug", "5", "7sus4", "13")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen() {
    val t = rememberT()
    val a = app()
    val st = settings()
    var root by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    val chord = Chord(root, q)
    val flats = root in setOf(1, 3, 8, 10)
    val tuning = Voicings.tuning(if (st.instrument == "ukulele") "ukulele" else st.tuning)
    val list = remember(root, q, st.instrument, tuning) { if (st.instrument == "piano") emptyList() else Voicings.voicings(chord, tuning, 8) }

    Page(t("dictionary")) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Eyebrow(t("root"), Modifier.padding(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until 12) FilterChip(i == root, { root = i }, { Text(Music.noteName(i, st.notation, i in setOf(1, 3, 8, 10))) }, colors = chipColors())
                }
                Eyebrow(t("type"), Modifier.padding(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (x in QUALITIES) FilterChip(x == q, { q = x }, { Text(x.ifEmpty { "maj" }, fontFamily = PlexMono) }, colors = chipColors())
                }
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("guitar" to t("guitar"), "ukulele" to t("ukulele"), "piano" to t("piano")), st.instrument, { i -> a.settings.update { it.copy(instrument = i) } }, Modifier.fillMaxWidth())
                Text(Music.format(chord, st.notation, flats), style = MaterialTheme.typography.displaySmall, fontFamily = PlexMono, modifier = Modifier.padding(top = 16.dp))
                Muted("${t("notes")}: " + Music.notes(chord).joinToString(" – ") { Music.noteName(it, st.notation, flats) })
                Spacer(Modifier.height(12.dp))
            }
            if (st.instrument == "piano") item { JustCard(Modifier.fillMaxWidth()) { PianoDiagram(chord, 1.0f) } }
            else items(list.chunked(2)) { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (v in row) JustCard(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            FretDiagram(v, tuning.strings.size, st.lefty, 1.4f)
                            Text(v.frets.joinToString(" ") { if (it < 0) "x" else "$it" }, color = just.muted, fontFamily = PlexMono, fontSize = 12.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
