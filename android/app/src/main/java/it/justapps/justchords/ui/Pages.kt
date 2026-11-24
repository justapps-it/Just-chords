package it.justapps.justchords.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.justapps.justchords.audio.Decoder
import it.justapps.justchords.core.ChordPro
import it.justapps.justchords.core.Dsp
import it.justapps.justchords.core.Recognition
import it.justapps.justchords.core.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fileName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "audio"

private fun clipboardText(ctx: Context): String? =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth().heightIn(min = 260.dp),
        placeholder = { Text(placeholder) },
        textStyle = TextStyle(fontFamily = PlexMono, fontSize = 14.sp, lineHeight = 20.sp),
        shape = MaterialTheme.shapes.medium,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(title0: String?, text0: String?, audio: Boolean) {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedUrl = remember { text0?.let(Sources::firstLink) ?: "" }
    val sharedText = remember { (text0 ?: "").replace(sharedUrl, "").trim() }
    var raw by rememberSaveable { mutableStateOf(if (sharedText.lines().size > 2) sharedText else "") }
    var title by rememberSaveable { mutableStateOf(title0 ?: "") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var err by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(0) }

    val chordpro = remember(raw, sharedUrl) { if (raw.isNotBlank()) ChordPro.importText(raw, ChordPro.Hint(url = sharedUrl.ifEmpty { null })) else "" }
    val finalCp = remember(chordpro, title) {
        if (chordpro.isEmpty()) "" else if (title.isNotBlank() && Regex("\\{title: (Senza titolo|Untitled)\\}").containsMatchIn(chordpro))
            chordpro.replaceFirst(Regex("\\{title: [^}]*\\}"), "{title: ${title.trim()}}") else chordpro
    }

    fun analyse(uri: Uri) {
        err = ""
        progress = 0f
        scope.launch {
            try {
                val pcm = Decoder.decode(ctx, uri) { progress = it }
                val segs = withContext(Dispatchers.Default) { Recognition.recognise(Dsp(), pcm.samples, pcm.sampleRate) { p -> progress = 0.3f + p * 0.7f } }
                val name = title.ifBlank { fileName(ctx, uri).substringBeforeLast('.') }
                raw = Recognition.toChordPro(segs, name)
                title = name
                tab = 1
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            progress = null
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::analyse) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val type = ctx.contentResolver.getType(uri) ?: ""
        if (type.startsWith("audio/") || type.startsWith("video/")) return@rememberLauncherForActivityResult analyse(uri)
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            if (text != null) {
                raw = text
                if (title.isBlank()) title = fileName(ctx, uri).substringBeforeLast('.')
            }
        }
    }
    LaunchedEffect(audio) { if (audio) pickAudio.launch(arrayOf("audio/*", "video/*")) }

    Page(t("importTitle")) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Muted(t("importLead"))
                    if (sharedUrl.isNotEmpty() && raw.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        JustCard(Modifier.fillMaxWidth(), padding = 14.dp) { Text("${t("sharedUrl")} $sharedUrl", fontSize = 14.sp) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ clipboardText(ctx)?.let { raw = it } }, { Text(t("pasteFromClipboard")) }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) })
                        AssistChip({ pickFile.launch(arrayOf("text/*", "application/octet-stream", "audio/*")) }, { Text(t("openFile")) }, leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) })
                        AssistChip({ pickAudio.launch(arrayOf("audio/*", "video/*")) }, { Text(t("fromAudio")) }, leadingIcon = { Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)) })
                    }
                    progress?.let {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator({ it }, Modifier.fillMaxWidth(), color = JustOrange)
                        Muted("${t("analysing")} ${(it * 100).toInt()}%", small = true)
                    }
                    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(t("title")) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                    Spacer(Modifier.height(8.dp))
                    PrimaryTabRow(tab, containerColor = Color.Transparent) {
                        Tab(tab == 0, { tab = 0 }, text = { Text(t("paste")) })
                        Tab(tab == 1, { tab = 1 }, text = { Text(t("preview")) }, enabled = finalCp.isNotEmpty())
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tab == 0 || finalCp.isEmpty()) CodeField(raw, { raw = it }, t("pasteHere"))
                    else JustCard(Modifier.fillMaxWidth()) { SheetPreview(finalCp) }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        {
                            val s = a.library.saveSong(finalCp)
                            nav.navigate("song/${s.id}") { popUpTo("home") }
                        },
                        Modifier.fillMaxWidth().height(52.dp), enabled = finalCp.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = JustOrange),
                    ) { Text(t("save"), fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(8.dp))
                    Muted(t("sourceNote"), small = true)
                }
            }
        }
    }
}

/** Chords-over-lyrics pasted into the editor become ChordPro (Editor.tsx onPaste). */
fun convertPaste(old: String, new: String): String {
    val added = new.length - old.length
    if (added < 40) return new
    var p = 0
    while (p < old.length && old[p] == new[p]) p++
    if (!new.endsWith(old.substring(p))) return new
    val pasted = new.substring(p, p + added)
    if (pasted.lines().size <= 2 || Regex("\\[[A-G]").containsMatchIn(pasted)) return new
    val conv = ChordPro.importText(pasted).replace(Regex("^\\{title:[^}]*\\}\\n(\\{artist:[^}]*\\}\\n)?\\n?"), "")
    return new.substring(0, p) + conv + new.substring(p + added)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(id: String) {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val song = remember { a.library.state.value.song(id) } ?: return
    var src by rememberSaveable { mutableStateOf(song.chordpro) }
    var tab by rememberSaveable { mutableStateOf(0) }
    Page(t("edit"), actions = {
        TextButton({ a.library.saveSong(src, song.id); nav.popBackStack() }) { Text(t("save"), color = JustOrange, fontWeight = FontWeight.SemiBold) }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Muted(t("editorHelp"), small = true)
                    Spacer(Modifier.height(8.dp))
                    PrimaryTabRow(tab, containerColor = Color.Transparent) {
                        Tab(tab == 0, { tab = 0 }, text = { Text(t("edit")) })
                        Tab(tab == 1, { tab = 1 }, text = { Text(t("preview")) })
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tab == 0) CodeField(src, { v -> src = convertPaste(src, v) }, "")
                    else JustCard(Modifier.fillMaxWidth()) { SheetPreview(src) }
                }
            }
        }
    }
}

@Composable
fun SetlistScreen(id: String) {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val lib by a.library.state.collectAsState()
    val list = lib.setlists.find { it.id == id } ?: return
    var adding by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun update(f: (it.justapps.justchords.data.Setlist) -> it.justapps.justchords.data.Setlist) =
        a.library.saveSetlists(lib.setlists.map { if (it.id == list.id) f(it).copy(updated = System.currentTimeMillis()) else it })
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= list.songs.size) return
        update { l -> l.copy(songs = l.songs.toMutableList().also { s -> val x = s[i]; s[i] = s[j]; s[j] = x }) }
    }
    Page(list.name, actions = {
        IconButton({ rename = true }) { Icon(Icons.Rounded.Edit, null) }
        IconButton({ confirm = true }) { Icon(Icons.Rounded.Delete, t("delete")) }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 40.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ nav.navigate("stage/${list.id}/0") }, enabled = list.songs.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(t("play"))
                    }
                    OutlinedButton({ adding = !adding }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(t("addSongs")) }
                }
            }
            itemsIndexed(list.songs.mapNotNull { lib.song(it) }, key = { _, s -> s.id }) { i, s ->
                SongRow(s, trailing = {
                    Row {
                        IconButton({ move(i, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Up") }
                        IconButton({ move(i, 1) }, enabled = i < list.songs.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Down") }
                        IconButton({ update { l -> l.copy(songs = l.songs - s.id) } }) { Icon(Icons.Rounded.Close, t("remove")) }
                    }
                }) { nav.navigate("song/${s.id}") }
            }
            if (adding) {
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = just.line); Eyebrow(t("addSongs"), Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                itemsIndexed(lib.songs.filter { it.id !in list.songs }.sortedBy { it.title.lowercase() }, key = { _, s -> "add" + s.id }) { _, s ->
                    SongRow(s, trailing = { Icon(Icons.Rounded.Add, null, tint = JustOrange) }) { update { l -> l.copy(songs = l.songs + s.id) } }
                }
            }
        }
    }
    if (rename) NameDialog(t("setlistName"), t("setlistName"), list.name, { rename = false }) { n -> update { it.copy(name = n) }; rename = false }
    if (confirm) AlertDialog(
        { confirm = false }, title = { Text("${t("delete")}?") },
        confirmButton = {
            TextButton({
                confirm = false
                nav.popBackStack()
                a.library.saveSetlists(lib.setlists.filter { it.id != list.id })
            }) { Text(t("delete"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text(t("cancel")) } },
    )
}
