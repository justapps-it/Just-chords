package it.justapps.justchords.ui

import it.justapps.justchords.data.LinkAudio
import it.justapps.justchords.core.LinkKind
import java.io.File
import kotlinx.coroutines.CancellationException
import androidx.compose.material.icons.rounded.PlayArrow
import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import it.justapps.justchords.ChordsApp
import it.justapps.justchords.audio.CaptureService
import it.justapps.justchords.audio.Decoder
import it.justapps.justchords.audio.Recording
import it.justapps.justchords.core.Analysis
import it.justapps.justchords.core.Dsp
import it.justapps.justchords.core.HowToPlay
import it.justapps.justchords.core.LiveChords
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.Recognition
import it.justapps.justchords.core.Segment
import it.justapps.justchords.core.Sources
import it.justapps.justchords.core.VideoRef
import it.justapps.justchords.core.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisResult(val segs: List<Segment>, val how: HowToPlay, val words: List<Word>?, val lyricsFailed: Boolean)

/** Recording → chords, tempo, key, capo, strumming and (optionally) synced lyrics. */
suspend fun analyseRecording(a: ChordsApp, samples: FloatArray, sr: Int, lyrics: Boolean, onStage: (String, Float) -> Unit): AnalysisResult = withContext(Dispatchers.Default) {
    onStage("analyzeWorking", 0f)
    val tempo = Analysis.estimateTempo(samples, sr)
    val raw = Recognition.recognise(Dsp(), samples, sr) { p -> onStage("analyzeWorking", p) }
    val segs = Analysis.tidy(raw, tempo.bpm)
    val how = Analysis.howToPlay(segs, tempo.bpm)
    if (!lyrics || segs.none { it.chord != null }) return@withContext AnalysisResult(segs, how, null, false)
    val clips = Analysis.clipRanges(samples, sr)
    val words = mutableListOf<Word>()
    try {
        clips.forEachIndexed { i, (from, to) ->
            onStage("transcribing", i.toFloat() / clips.size)
            words += a.account.api.transcribe(Analysis.wav16k(samples, sr, from, to), from.toDouble() / sr)
        }
    } catch (e: Exception) {
        return@withContext AnalysisResult(segs, how, words.ifEmpty { null }, true)
    }
    AnalysisResult(segs, how, words.ifEmpty { null }, words.isEmpty())
}

private fun clipboard(ctx: Context): String? =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()

private fun mmss(s: Double) = "%d:%02d".format(s.toInt() / 60, s.toInt() % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(url0: String?, file0: String?) {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val st = settings()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(url0 ?: "") }
    var input by rememberSaveable { mutableStateOf(url0 ?: "") }
    var video by remember { mutableStateOf<VideoRef?>(url0?.let(Sources::videoRef)) }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var lyrics by rememberSaveable { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var mic by remember { mutableStateOf<Recording?>(null) }
    val captured by CaptureService.recording.collectAsState()
    val stoppedCapture by CaptureService.stopped.collectAsState()
    val captureError by CaptureService.error.collectAsState()
    val live = mic ?: captured
    var stage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }

    // The link: video id for the player, and title / artist when the platform tells.
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        video = Sources.videoRef(url) ?: video
        runCatching { a.account.api.link(url) }.onSuccess { li ->
            if (video == null) video = li.youtubeId?.let { VideoRef("youtube", it) } ?: li.videoId?.let { VideoRef(li.kind, it) }
            if (title.isBlank()) title = li.title ?: ""
            if (artist.isBlank()) artist = li.artist ?: ""
        }
        if (video == null) err = t("linkUnsupported")
    }

    fun analyse(samples: FloatArray, sr: Int) {
        if (samples.size < sr * 3) { err = t("analyzeNoChords"); return }
        result = null
        scope.launch {
            val r = analyseRecording(a, samples, sr, lyrics) { s, p -> stage = s; progress = p }
            stage = null
            result = r
            if (r.segs.none { it.chord != null }) err = t("analyzeNoChords")
        }
    }
    LaunchedEffect(stoppedCapture) {
        CaptureService.consumeStopped()?.let { r -> analyse(withContext(Dispatchers.Default) { r.samples() }, r.sampleRate) }
    }
    LaunchedEffect(captureError) { if (captureError != null) err = t("analyzeCaptureFailed") }

    LaunchedEffect(file0) {
        if (file0 == null) return@LaunchedEffect
        stage = "analyzeWorking"
        try {
            val pcm = Decoder.decode(ctx, Uri.parse(file0), 360) { p -> progress = p * 0.2f }
            analyse(pcm.samples, pcm.sampleRate)
        } catch (e: Exception) {
            stage = null
            err = e.message ?: e.javaClass.simpleName
        }
    }

    // Straight from the link: the phone downloads only the sound (no screen recording).
    val direct = Sources.linkKind(url) in setOf(LinkKind.YOUTUBE, LinkKind.TIKTOK, LinkKind.INSTAGRAM)
    var directFailed by rememberSaveable { mutableStateOf(false) }
    fun startDirect() {
        err = ""
        result = null
        stage = "analyzeDownloading"
        progress = 0f
        scope.launch {
            val f = File(ctx.cacheDir, "link-audio")
            try {
                val la = LinkAudio()
                val ref = la.resolve(url)
                if (title.isBlank()) ref.title?.let { title = it }
                if (artist.isBlank()) ref.artist?.let { artist = it }
                val referer = when (Sources.linkKind(url)) {
                    LinkKind.TIKTOK -> "https://www.tiktok.com/"
                    LinkKind.INSTAGRAM -> "https://www.instagram.com/"
                    else -> null
                }
                la.download(ref, f, referer) { p -> progress = p * 0.5f }
                stage = "analyzeWorking"
                val pcm = Decoder.decode(ctx, Uri.fromFile(f), 360) { p -> progress = 0.5f + p * 0.2f }
                f.delete()
                analyse(pcm.samples, pcm.sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Analyze", "direct link failed", e)
                stage = null
                directFailed = true
                err = t("analyzeDirectFailed")
            } finally {
                f.delete()
            }
        }
    }
    // A link shared to the app starts right away.
    LaunchedEffect(url0) { if (url0 != null && file0 == null && direct && result == null && stage == null) startDirect() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            stage = "analyzeWorking"
            try {
                val pcm = Decoder.decode(ctx, uri, 360) { p -> progress = p * 0.2f }
                analyse(pcm.samples, pcm.sampleRate)
            } catch (e: Exception) {
                stage = null
                err = e.message ?: e.javaClass.simpleName
            }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) CaptureService.start(ctx, res.resultCode, res.data!!)
    }
    fun startCapture() {
        err = ""
        result = null
        consent.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
    fun startMic() {
        err = ""
        result = null
        mic = runCatching { Recording.microphone() }.getOrElse { err = t("micDenied"); null }
    }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending?.invoke() else err = t("micDenied")
        pending = null
    }
    /** Microphone permission (needed for any recording, playback capture too) and, on Android 13+, notifications. */
    fun withAudio(go: () -> Unit) {
        val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (micOk && !notif) return go()
        pending = go
        askPerms.launch(listOfNotNull(Manifest.permission.RECORD_AUDIO.takeIf { !micOk }, Manifest.permission.POST_NOTIFICATIONS.takeIf { notif }).toTypedArray())
    }
    fun stopListening() {
        mic?.let { r ->
            mic = null
            r.stop()
            scope.launch { analyse(withContext(Dispatchers.Default) { r.samples() }, r.sampleRate) }
        }
        if (captured != null) CaptureService.stop(ctx)
    }
    DisposableEffect(Unit) { onDispose { mic?.stop() } }

    // Live chord while listening.
    var chord by remember { mutableStateOf<String?>(null) }
    val heard = remember { mutableStateListOf<String>() }
    var seconds by remember { mutableStateOf(0.0) }
    var loudest by remember { mutableFloatStateOf(0f) }
    if (live != null) {
        val view = LocalView.current
        DisposableEffect(live) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        LaunchedEffect(live) {
            heard.clear(); chord = null; loudest = 0f
            val dsp = Dsp()
            val lc = LiveChords()
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val (chroma, rms) = dsp.chroma(live.tail(4096), live.sampleRate.toFloat())
                    val name = lc.push(chroma, rms).chord?.name
                    withContext(Dispatchers.Main) {
                        chord = name
                        seconds = live.seconds
                        loudest = maxOf(loudest, live.level)
                        if (name != null && heard.lastOrNull() != name) { heard += name; if (heard.size > 24) heard.removeAt(0) }
                        if (live.full) stopListening()
                    }
                    delay(120)
                }
            }
        }
    }

    fun label(s: String) = Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s

    Page(t("analyze")) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Muted(t("analyzeLead"), Modifier.padding(horizontal = 4.dp)) }
            if (video == null && file0 == null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text(t("analyzeLink")) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        trailingIcon = { IconButton({ clipboard(ctx)?.let { input = it.trim() } }) { Icon(Icons.Rounded.ContentPaste, t("paste")) } })
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton({ err = ""; url = Sources.firstLink(input) ?: input.trim() }, enabled = input.isNotBlank()) { Text("OK") }
                }
            }
            video?.let { v ->
                item(key = "video") {
                    Box(
                        Modifier.fillMaxWidth().then(if (v.platform == "youtube") Modifier.aspectRatio(16f / 9f) else Modifier.height(560.dp))
                            .clip(MaterialTheme.shapes.large).background(Color.Black),
                    ) { VideoFrame(v, Modifier.fillMaxSize()) }
                }
            }
            if (live == null && stage == null && result == null) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (video != null && direct) {
                        Button(::startDirect, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                            Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(t("analyzeDirect"), fontWeight = FontWeight.SemiBold)
                        }
                        Muted(t("analyzeDirectHint"), small = true)
                    }
                    if (video != null) {
                        // Fallback: hear the video while it plays (MediaProjection playback capture).
                        val primary = !direct || directFailed
                        if (primary) {
                            Button({ withAudio(::startCapture) }, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(t("analyzePlay"), fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedButton({ withAudio(::startCapture) }, Modifier.fillMaxWidth().height(48.dp)) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t("analyzePlay"))
                            }
                        }
                        if (primary) Muted(t("analyzePlayHint"), small = true)
                    }
                    OutlinedButton({ withAudio(::startMic) }, Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t("analyzeMic")) }
                    OutlinedButton({ pickFile.launch(arrayOf("audio/*", "video/*")) }, Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t("analyzeFile")) }
                    JustCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("lyricsSync"), fontWeight = FontWeight.SemiBold)
                                Muted(t("lyricsNote"), small = true)
                            }
                            Switch(lyrics, { lyrics = it }, colors = SwitchDefaults.colors(checkedTrackColor = JustOrange))
                        }
                    }
                    Muted(t("analyzeMax"), small = true)
                }
            }
            if (live != null) item(key = "live") {
                JustCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(just.danger))
                        Spacer(Modifier.width(8.dp))
                        Eyebrow(t("analyzeListening"), Modifier.weight(1f))
                        Text("${mmss(seconds)} / 6:00", fontFamily = PlexMono, color = just.muted, fontSize = 13.sp)
                    }
                    LinearProgressIndicator({ (live.level * 4).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 10.dp), color = JustOrange)
                    Text(chord?.let(::label) ?: "…", Modifier.fillMaxWidth(), fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = JustOrange, fontFamily = PlexMono, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (heard.isNotEmpty()) Text(heard.joinToString("  ") { label(it) }, color = just.muted, fontFamily = PlexMono, fontSize = 14.sp, maxLines = 2)
                    if (seconds > 6 && loudest < 0.01f) Text(t("analyzeSilent"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(::stopListening, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                        Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text(t("analyzeStop"), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            stage?.let { s ->
                item(key = "stage") {
                    JustCard(Modifier.fillMaxWidth()) {
                        Text(t(s), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth(), color = JustOrange)
                    }
                }
            }
            if (err.isNotEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) }
            result?.takeIf { r -> r.segs.any { it.chord != null } }?.let { r ->
                item(key = "how") { HowToPlayCard(r.how) }
                if (r.lyricsFailed) item { Muted(t("lyricsFailed"), Modifier.padding(horizontal = 4.dp), small = true) }
                item(key = "meta") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(title, { title = it }, Modifier.weight(1f), label = { Text(t("title")) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                        OutlinedTextField(artist, { artist = it }, Modifier.weight(1f), label = { Text(t("artist")) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                    }
                }
                val cp = Analysis.chordPro(
                    title.ifBlank { t("untitled") }, artist, r.segs, r.words, r.how, t("strumming"),
                    Analysis.Labels(t("intro"), t("instrumental"), t("outro")),
                )
                item(key = "sheet") { JustCard(Modifier.fillMaxWidth()) { SheetPreview(cp) } }
                item(key = "save") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            val s = a.library.saveSong(cp) { it.copy(source = url.ifBlank { null }, youtube = video?.takeIf { v -> v.platform == "youtube" }?.id) }
                            nav.navigate("song/${s.id}") { popUpTo("home") }
                        }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) { Text(t("saveToLibrary"), fontWeight = FontWeight.SemiBold) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ result = null; err = "" }, Modifier.weight(1f)) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("analyzeAgain")) }
                            if (title.isNotBlank()) OutlinedButton({ nav.navigate("find?q=${enc("$title $artist".trim())}" + (video?.takeIf { it.platform == "youtube" }?.let { "&yt=${it.id}" } ?: "")) }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("officialChords"), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HowToPlayCard(how: HowToPlay) {
    val t = rememberT()
    val st = settings()
    JustCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(just.orangeSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayCircle, null, tint = JustOrange, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(t("howToPlay"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            LevelBadge(how.level)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(t("key"), how.key?.let { Music.formatKey(it, st.notation) } ?: "—", Modifier.weight(1f))
            Fact(t("capo"), if (how.capo == 0) "—" else "${how.capo}", Modifier.weight(1f), accent = how.capo > 0)
            Fact(t("tempoLabel"), if (how.bpm > 0) "${how.bpm}" else "—", Modifier.weight(1f), unit = "BPM")
        }
        Spacer(Modifier.height(12.dp))
        Text(if (how.capo == 0) t("noCapo") else "${t("capoAt")} ${how.capo}", fontWeight = FontWeight.SemiBold)
        Eyebrow(t("shapesLabel"), Modifier.padding(top = 12.dp, bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(how.shapes) { s -> ChordCard(s, Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s) }
        }
        Eyebrow(t("strumming"), Modifier.padding(top = 14.dp, bottom = 4.dp))
        Text(how.strum.pattern, fontFamily = PlexMono, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Muted(t("strumHint"), small = true)
        if (how.hard.isNotEmpty()) {
            Eyebrow(t("practice"), Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in how.hard) Text(h, Modifier.clip(CircleShape).background(just.soft).padding(horizontal = 12.dp, vertical = 6.dp), fontFamily = PlexMono, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, accent: Boolean = false) {
    Column(modifier.clip(MaterialTheme.shapes.medium).background(if (accent) just.orangeSoft else just.soft).padding(12.dp)) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = if (accent) JustOrange else MaterialTheme.colorScheme.onSurface)
            unit?.let { Text(" $it", color = just.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}
