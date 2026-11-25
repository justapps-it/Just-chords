package it.justapps.justchords.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import it.justapps.justchords.ChordsApp
import it.justapps.justchords.core.ChordPro
import it.justapps.justchords.core.LinkKind
import it.justapps.justchords.core.SearchResult
import it.justapps.justchords.core.Signal
import it.justapps.justchords.core.Sources
import it.justapps.justchords.data.LinkInfo
import kotlinx.coroutines.launch

val SOURCE_NAME = mapOf("ug" to "Ultimate Guitar", "aes" to "Accordi e Spartiti")

/** Imports a song page and opens it (Find.tsx openChords). */
suspend fun openChords(a: ChordsApp, nav: NavHostController, url: String, title: String? = null, artist: String? = null, youtube: String? = null) {
    val lib = a.library
    lib.findBySource(url)?.let { existing ->
        if (youtube != null && existing.youtube == null) lib.patchSong(existing.id, sync = true) { it.copy(youtube = youtube) }
        nav.navigate("song/${existing.id}")
        return
    }
    val s = a.account.api.song(url)
    var cp = ChordPro.importText(s.text, ChordPro.Hint(s.title.ifEmpty { title }, s.artist.ifEmpty { artist }, url))
    val artistLine = Regex("(\\{artist:[^}]*\\}\\n?)")
    if (s.capo != null && !cp.contains("{capo:")) cp = cp.replaceFirst(artistLine, "$1{capo: ${s.capo}}\n")
    if (s.key != null && !cp.contains("{key:")) cp = cp.replaceFirst(artistLine, "$1{key: ${s.key}}\n")
    val song = lib.saveSong(cp) { it.copy(source = url, youtube = youtube) }
    a.account.signal(Signal.IMPORT, song.title, song.artist)
    nav.navigate("song/${song.id}") { popUpTo("home") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindScreen(q0: String?, url: String?, hintTitle: String?, hintArtist: String?, hintYt: String?) {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(q0 ?: "") }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var results by remember { mutableStateOf<List<SearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(url, q0) {
        results = null
        err = ""
        var text = query
        if (url != null) {
            val kind = Sources.linkKind(url)
            if (kind == LinkKind.CHORDS) {
                busy = url
                try {
                    openChords(a, nav, url, hintTitle, hintArtist, hintYt)
                } catch (e: Exception) {
                    err = t("fetchFailed"); busy = null; results = emptyList()
                }
                return@LaunchedEffect
            }
            if (kind == null) {
                err = t("linkUnsupported"); return@LaunchedEffect
            }
            if (kind == LinkKind.TIKTOK || kind == LinkKind.INSTAGRAM) {
                nav.navigate("analyze?url=${enc(url)}") { popUpTo("find?q={q}&url={url}&t={t}&a={a}&yt={yt}") { inclusive = true } }
                return@LaunchedEffect
            }
            searching = true
            try {
                val li = a.account.api.link(url)
                info = li
                text = listOfNotNull(li.title, li.artist).joinToString(" ")
                query = text
            } catch (e: Exception) {
                err = t("linkFailed"); searching = false
                return@LaunchedEffect
            }
        }
        if (text.trim().length < 2) {
            searching = false; return@LaunchedEffect
        }
        searching = true
        results = runCatching { a.account.api.search(text) }.getOrElse { emptyList() }
        searching = false
    }

    val yt = info?.youtubeId ?: hintYt
    val submit = {
        val v = query.trim()
        if (Regex("^https?://").containsMatchIn(v)) nav.navigate("find?url=${enc(v)}")
        else if (v.isNotEmpty()) nav.navigate("find?q=${enc(v)}" + (yt?.let { "&yt=$it" } ?: "")) { launchSingleTop = true }
    }
    fun open(r: SearchResult) {
        busy = r.url
        err = ""
        scope.launch {
            try {
                openChords(a, nav, r.url, r.title, r.artist, yt)
            } catch (e: Exception) {
                err = t("fetchFailed")
            }
            busy = null
        }
    }
    fun browse(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }

    Page(t("findChords"), onBack = if (nav.previousBackStackEntry != null) ({}) else null) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item { SearchField(query, { query = it }, t("searchOrLink"), Modifier.padding(horizontal = 16.dp), submit) }
            info?.let { li ->
                item { Column {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip({}, { Text(if (li.kind == "spotify") "Spotify" else "YouTube") })
                        Spacer(Modifier.width(10.dp))
                        Text(li.title ?: "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                        li.artist?.let { Text(" · $it", color = just.muted, maxLines = 1) }
                    }
                    if (li.youtubeId != null) {
                        FilledTonalButton(
                            { nav.navigate("analyze?url=${enc(li.url)}") },
                            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t("analyze"))
                        }
                    }
                } }
            }
            if (err.isNotEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
            if (busy != null && url != null && Sources.linkKind(url) == LinkKind.CHORDS) item {
                Column(Modifier.padding(20.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = JustOrange)
                    Spacer(Modifier.height(8.dp))
                    Muted(t("loadingChords"))
                }
            }
            if (searching) item {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = JustOrange, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Muted(t("searching"))
                }
            }
            if (q0 == null && url == null && results == null && !searching) item { Muted(t("findHint"), Modifier.padding(20.dp)) }
            results?.let { list ->
                items(list, key = { it.url }) { r -> ResultRow(r, busy == r.url, busy == null) { open(r) } }
                if (list.isEmpty() && query.isNotBlank()) item {
                    JustCard(Modifier.padding(16.dp).fillMaxWidth()) {
                        Muted(t("fallbackHint"))
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (s in listOf("ug", "aes")) AssistChip({ browse(Sources.searchUrl(s, query)) }, { Text(SOURCE_NAME[s]!!) }, trailingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)) })
                            AssistChip(
                                { nav.navigate("import?title=${enc(hintTitle ?: query)}") }, { Text(t("paste")) },
                                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = just.orangeSoft),
                            )
                        }
                    }
                }
            }
            item { Muted(t("sourceNote"), Modifier.padding(20.dp), small = true) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultRow(r: SearchResult, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.artist, color = just.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Eyebrow(SOURCE_NAME[r.source] ?: r.source, color = if (r.source == "ug") JustOrange else just.muted)
                r.rating?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Star, null, tint = JustOrange, modifier = Modifier.size(12.dp))
                        Text(" $it" + (r.votes?.let { v -> " ($v)" } ?: ""), fontSize = 12.sp, color = just.muted)
                    }
                }
                r.level?.let { LevelBadge(it) }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = JustOrange, strokeWidth = 2.dp)
    }
}
