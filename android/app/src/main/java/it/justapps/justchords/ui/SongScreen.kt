package it.justapps.justchords.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.justapps.justchords.core.ChordPro
import it.justapps.justchords.core.Chord
import it.justapps.justchords.core.Levels
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.Notation
import it.justapps.justchords.core.RenderOptions
import it.justapps.justchords.core.Signal
import it.justapps.justchords.data.Song
import kotlinx.coroutines.launch
import kotlin.math.floor

data class StageList(val id: String, val index: Int, val ids: List<String>)

@Composable
fun SongRoute(id: String, stage: Boolean) {
    val lib by app().library.state.collectAsState()
    val song = lib.song(id)
    if (song == null) {
        if (lib.loaded) LaunchedEffect(Unit) { } // deleted: the screen pops itself
        return
    }
    SongScreen(song, stage, null)
}

@Composable
fun StageSetlistRoute(listId: String, index: Int) {
    val lib by app().library.state.collectAsState()
    val list = lib.setlists.find { it.id == listId } ?: return
    val ids = list.songs.filter { lib.song(it) != null }
    if (ids.isEmpty()) return
    val i = index.coerceIn(0, ids.size - 1)
    androidx.compose.runtime.key(ids[i]) { SongScreen(lib.song(ids[i])!!, true, StageList(list.id, i, ids)) }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongScreen(song: Song, stage: Boolean, setlist: StageList?) {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val st = settings()
    val lib by a.library.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val density = LocalDensity.current

    val parsed = remember(song.chordpro) { ChordPro.parse(song.chordpro) }
    val chords = remember(parsed) { ChordPro.songChords(parsed) }
    val sheetCapo = parsed.capo
    val capo = song.capo ?: sheetCapo
    // Shapes move opposite to the capo so the song sounds the same.
    val shift = song.transpose + (sheetCapo - capo)
    var simplify by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var scrolling by remember { mutableStateOf(false) }
    var listen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val originalKey = remember(parsed, chords) { parsed.key?.let(Music::parseKey) ?: Music.detectKey(chords) }
    val soundingKey = originalKey?.let { it.copy(tonic = Music.mod12(it.tonic + sheetCapo + song.transpose)) }
    val shapeKey = originalKey?.let { it.copy(tonic = Music.mod12(it.tonic + shift)) }
    val opts = RenderOptions(shift, st.notation, Music.prefersFlats(shapeKey), simplify)

    val unique = remember(chords, shift, simplify, st.notation, opts.flats) {
        val seen = LinkedHashMap<String, String>()
        for (c in chords) {
            var x = Music.transpose(c, shift)
            if (simplify) x = Music.simplify(x)
            val sym = Music.format(x)
            if (sym !in seen) seen[sym] = Music.format(x, st.notation, opts.flats)
        }
        seen.toList()
    }
    val capoTips = remember(chords, sheetCapo, song.transpose) {
        Music.suggestCapo(chords.map { Music.transpose(it, sheetCapo + song.transpose) }).take(3)
    }
    val difficulty = remember(chords, shift, simplify, parsed) {
        Levels.chords(chords.map { Music.transpose(it, shift).let { x -> if (simplify) Music.simplify(x) else x } }, Levels.changesPerLine(parsed))
    }

    LaunchedEffect(song.id) {
        a.library.patchSong(song.id) { it.copy(opened = System.currentTimeMillis()) }
        a.account.signal(Signal.OPEN, song.title, song.artist)
    }
    fun patch(f: (Song) -> Song) = a.library.patchSong(song.id, patch = f)

    KeepScreenOn(stage || scrolling)

    // Autoscroll: same speed as the website, in dp per second.
    LaunchedEffect(scrolling, song.scroll) {
        if (!scrolling) return@LaunchedEffect
        var last = withFrameNanos { it }
        var acc = 0f
        while (true) {
            val now = withFrameNanos { it }
            acc += (now - last) / 1e9f * (6 + song.scroll * 7) * density.density
            last = now
            val px = floor(acc)
            if (px >= 1) {
                acc -= px
                list.scrollBy(px)
                if (!list.canScrollForward) { scrolling = false; break }
            }
        }
    }

    fun nextSong(d: Int) {
        val sl = setlist ?: return
        val i = sl.index + d
        if (i < 0 || i >= sl.ids.size) return
        nav.navigate("stage/${sl.id}/$i") { popUpTo("setlist/${sl.id}") }
    }
    fun exitStage() {
        if (setlist != null) nav.popBackStack("setlist/${setlist.id}", false) else nav.popBackStack()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageH = constraints.maxHeight * 0.75f
        fun page(d: Int) = scope.launch {
            if (d > 0 && !list.canScrollForward && setlist != null) nextSong(1) else list.animateScrollBy(d * pageH)
        }
        val focus = remember { FocusRequester() }
        if (stage) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        val scale = song.font.toFloat() * (if (stage) 1.25f else 1f)
        Column(
            Modifier.fillMaxSize().then(
                if (stage) Modifier.focusRequester(focus).focusable().onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    // Bluetooth page turners send these keys.
                    when (e.key) {
                        Key.DirectionDown, Key.PageDown, Key.Spacebar, Key.Enter, Key.VolumeDown -> { page(1); true }
                        Key.DirectionUp, Key.PageUp, Key.VolumeUp -> { page(-1); true }
                        Key.DirectionRight -> { nextSong(1); true }
                        Key.DirectionLeft -> { nextSong(-1); true }
                        Key.S -> { scrolling = !scrolling; true }
                        else -> false
                    }
                } else Modifier,
            ),
        ) {
            if (!stage) {
                TopAppBar(
                    title = {},
                    navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                    actions = {
                        IconButton({
                            if (!song.favorite) a.account.signal(Signal.FAVORITE, song.title, song.artist)
                            a.library.patchSong(song.id, sync = true) { it.copy(favorite = !it.favorite) }
                        }) { Icon(if (song.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, t("favorites"), tint = if (song.favorite) JustOrange else LocalContentColorOrDefault()) }
                        IconButton({ nav.navigate("learnsong/${song.id}") }) { Icon(Icons.Rounded.School, t("learn"), tint = JustOrange) }
                        IconButton({ listen = !listen }) { Icon(Icons.Rounded.SlowMotionVideo, t("listenSong"), tint = if (listen) JustOrange else LocalContentColorOrDefault()) }
                        IconButton({ nav.navigate("stage/song/${song.id}") }) { Icon(Icons.Rounded.Fullscreen, t("stage")) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(t("edit")) }, { menu = false; nav.navigate("edit/${song.id}") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                for (l in lib.setlists) DropdownMenuItem(
                                    { Text("${t("addToSetlist")}: ${l.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    {
                                        menu = false
                                        a.library.saveSetlists(lib.setlists.map { x -> if (x.id == l.id && song.id !in x.songs) x.copy(songs = x.songs + song.id, updated = System.currentTimeMillis()) else x })
                                    },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                                )
                                DropdownMenuItem({ Text(t("export")) }, {
                                    menu = false
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, listOf(song.title, song.artist).filter { it.isNotEmpty() }.joinToString(" - "))
                                        .putExtra(Intent.EXTRA_TEXT, song.chordpro)
                                    ctx.startActivity(Intent.createChooser(send, t("export")))
                                }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                                DropdownMenuItem({ Text(t("delete"), color = MaterialTheme.colorScheme.error) }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
                            }
                        }
                    },
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize().then(
                        // Stage: tap the top third to go back a page, anywhere else to turn it.
                        if (stage) Modifier.pointerInput(setlist?.index) {
                            detectTapGestures { o -> page(if (o.y < size.height / 3f) -1 else 1) }
                        } else Modifier,
                    ),
                    state = list,
                    contentPadding = PaddingValues(top = if (stage) 24.dp else 0.dp, bottom = 140.dp),
                ) {
                    item(key = "head") {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Text(parsed.title.ifEmpty { t("untitled") }, style = if (stage) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge)
                            if (parsed.artist.isNotEmpty()) Text(parsed.artist, color = just.muted, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                soundingKey?.let { k ->
                                    MetaPill("${t("key")} ${Music.formatKey(k, st.notation)}" + if (song.transpose != 0 && originalKey != null) " (${t("original")} ${Music.formatKey(originalKey.copy(tonic = Music.mod12(originalKey.tonic + sheetCapo)), st.notation)})" else "")
                                }
                                if (capo > 0) MetaPill("${t("capo")} $capo", accent = true)
                                parsed.tempo?.let { MetaPill("♩ ${it.toInt()}") }
                                if (chords.isNotEmpty()) LevelBadge(difficulty.level)
                            }
                            if (difficulty.hard.isNotEmpty() && !stage) Muted("${t("hardChords")}: ${difficulty.hard.joinToString(" ")}", Modifier.padding(top = 6.dp), small = true)
                        }
                    }
                    if (!stage) item(key = "toggles") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(simplify, { simplify = !simplify }, { Text(t("simplify")) }, colors = chipColors())
                            FilterChip(st.notation == Notation.IT, { a.settings.update { s -> s.copy(notation = if (s.notation == Notation.IT) Notation.EN else Notation.IT) } }, { Text(if (st.notation == Notation.IT) "Do Re Mi" else "C D E") }, colors = chipColors())
                            for (inst in listOf("guitar", "ukulele", "piano")) {
                                FilterChip(st.instrument == inst, { a.settings.update { it.copy(instrument = inst) } }, { Text(t(inst)) }, colors = chipColors())
                            }
                        }
                    }
                    if (listen && !stage) item(key = "player") { YouTubePlayer(song) { listen = false } }
                    val tip = capoTips.firstOrNull()
                    if (!stage && tip != null && tip.capo != capo && tip.score + 0.5 < (capoTips.find { it.capo == capo }?.score ?: 99.0)) item(key = "tip") {
                        Surface(
                            { patch { it.copy(capo = tip.capo) } },
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium, color = just.orangeSoft,
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lightbulb, null, tint = JustOrange)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("${t("capoSuggest")}: ${if (tip.capo == 0) "—" else tip.capo}", fontWeight = FontWeight.SemiBold)
                                    Text(chordsPreview(chords, sheetCapo + song.transpose - tip.capo, st.notation), color = just.muted, fontFamily = PlexMono, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (unique.isNotEmpty() && !stage) item(key = "strip") {
                        Column(Modifier.padding(top = 12.dp)) {
                            Eyebrow(t("chordsInSong"), Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(unique, key = { it.first }) { (sym, label) -> ChordCard(sym, label) { zoom = sym } }
                            }
                        }
                    }
                    item(key = "gap") { Spacer(Modifier.height(12.dp)) }
                    sheetItems(parsed, opts, scale) { c -> zoom = ChordPro.displayChord(c, opts.copy(notation = Notation.EN, flats = false)) }
                    if (stage) item(key = "hint") { Muted(t("pedalHint"), Modifier.padding(20.dp), small = true) }
                }
                ControlBar(
                    song, capo, capoTips.firstOrNull()?.capo, scrolling, stage, setlist,
                    onTranspose = { d -> patch { it.copy(transpose = it.transpose + d) } },
                    onCapo = { c -> patch { it.copy(capo = c) } },
                    onFont = { d -> patch { it.copy(font = ((it.font + d) * 100).toInt() / 100.0).let { s -> s.copy(font = s.font.coerceIn(0.7, 2.2)) } } },
                    onScroll = { scrolling = !scrolling },
                    onSpeed = { v -> patch { it.copy(scroll = v) } },
                    onNext = ::nextSong,
                    onExit = ::exitStage,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    if (stage) BackHandler { exitStage() }

    zoom?.let { z ->
        ModalBottomSheet({ zoom = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.fillMaxWidth().padding(bottom = 32.dp), contentAlignment = Alignment.Center) {
                ChordCard(z, Music.parseChord(z)?.let { Music.format(it, st.notation, opts.flats) } ?: z, big = true)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        { confirmDelete = false },
        title = { Text(t("delete")) },
        text = { Text(t("deleteConfirm")) },
        confirmButton = {
            TextButton({
                confirmDelete = false
                nav.popBackStack()
                a.library.deleteSong(song.id)
            }) { Text(t("delete"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(t("cancel")) } },
    )
}

@Composable
private fun LocalContentColorOrDefault(): Color = androidx.compose.material3.LocalContentColor.current

@Composable
fun chipColors() = FilterChipDefaults.filterChipColors(selectedContainerColor = just.orangeSoft, selectedLabelColor = JustOrange)

@Composable
private fun MetaPill(text: String, accent: Boolean = false) {
    Text(
        text,
        Modifier.clip(CircleShape).background(if (accent) just.orangeSoft else just.soft).padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (accent) JustOrange else MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp, fontWeight = FontWeight.Medium,
    )
}

private fun chordsPreview(chords: List<Chord>, shift: Int, n: Notation): String {
    val seen = LinkedHashSet<String>()
    for (c in chords) {
        seen += Music.format(Music.transpose(c.copy(bass = null), shift), n)
        if (seen.size >= 5) break
    }
    return seen.joinToString(" ")
}

/** Floating bar: transpose, capo, text size, autoscroll (the toolbar of the website). */
@Composable
private fun ControlBar(
    song: Song, capo: Int, bestCapo: Int?, scrolling: Boolean, stage: Boolean, setlist: StageList?,
    onTranspose: (Int) -> Unit, onCapo: (Int) -> Unit, onFont: (Double) -> Unit, onScroll: () -> Unit, onSpeed: (Int) -> Unit,
    onNext: (Int) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier,
) {
    val t = rememberT()
    var capoMenu by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(false) }
    Column(modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(speed) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(280.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(t("speed"))
                    Slider(song.scroll.toFloat(), { onSpeed(it.toInt()) }, valueRange = 0f..10f, steps = 9, modifier = Modifier.weight(1f).padding(start = 12.dp),
                        colors = SliderDefaults.colors(thumbColor = JustOrange, activeTrackColor = JustOrange))
                }
            }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stage) {
                    IconButton(onExit) { Icon(Icons.Rounded.Close, t("exitStage")) }
                    if (setlist != null) {
                        IconButton({ onNext(-1) }, enabled = setlist.index > 0) { Icon(Icons.Rounded.ChevronLeft, t("prev")) }
                        Text("${setlist.index + 1}/${setlist.ids.size}", fontFamily = PlexMono, fontSize = 13.sp)
                        IconButton({ onNext(1) }, enabled = setlist.index < setlist.ids.size - 1) { Icon(Icons.Rounded.ChevronRight, t("next")) }
                    }
                    BarDivider()
                }
                IconButton({ onTranspose(-1) }) { Icon(Icons.Rounded.Remove, "−1") }
                Text(if (song.transpose > 0) "+${song.transpose}" else "${song.transpose}", fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ onTranspose(1) }) { Icon(Icons.Rounded.Add, "+1") }
                BarDivider()
                Box {
                    TextButton({ capoMenu = true }) {
                        Text("${t("capo")} ${if (capo == 0) "—" else capo}", color = if (capo > 0) JustOrange else MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(capoMenu, { capoMenu = false }) {
                        for (i in 0..9) DropdownMenuItem({ Text((if (i == 0) "—" else "$i") + if (bestCapo == i) "  ★" else "") }, { capoMenu = false; onCapo(i) })
                    }
                }
                BarDivider()
                IconButton({ onFont(-0.1) }) { Icon(Icons.Rounded.TextDecrease, "A−") }
                IconButton({ onFont(0.1) }) { Icon(Icons.Rounded.TextIncrease, "A+") }
                BarDivider()
                IconButton(onScroll) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (scrolling) JustOrange else just.orangeSoft), contentAlignment = Alignment.Center) {
                        Icon(if (scrolling) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, t("autoscroll"), tint = if (scrolling) Color.White else JustOrange)
                    }
                }
                TextButton({ speed = !speed }) { Text("${song.scroll}", fontFamily = PlexMono) }
            }
        }
    }
}

@Composable
private fun BarDivider() = Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(just.line))
