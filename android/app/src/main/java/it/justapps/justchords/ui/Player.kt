package it.justapps.justchords.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.justapps.justchords.core.Sources
import it.justapps.justchords.data.ApiError
import it.justapps.justchords.data.Song
import it.justapps.justchords.data.Video

/** YouTube player under the toolbar, to play along with the sheet (Player.tsx). */
@Composable
fun YouTubePlayer(song: Song, onClose: () -> Unit) {
    val t = rememberT()
    val a = app()
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(song.youtube ?: "") }
    var alts by remember { mutableStateOf<List<Video>>(emptyList()) }
    var state by remember { mutableStateOf(if (song.youtube != null) "idle" else "search") }
    var paste by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    val query = "${song.artist} ${song.title}".trim()

    fun choose(v: String) {
        id = v
        state = "idle"
        a.library.patchSong(song.id, sync = true) { it.copy(youtube = v) }
    }
    LaunchedEffect(Unit) {
        if (song.youtube != null) return@LaunchedEffect
        try {
            val r = a.account.api.videos(query)
            if (r.isEmpty()) state = "error" else {
                alts = r
                choose(r[0].id)
            }
        } catch (e: Exception) {
            state = if (e is ApiError && e.status == 501) "nokey" else "error"
        }
    }

    JustCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("▶ ${t("listenSong")}", Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClose) { Icon(Icons.Rounded.Close, t("close")) }
        }
        if (id.isNotEmpty() && state == "idle") {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) { YouTubeFrame(id) }
        } else if (state == "search") Muted(t("searching"), Modifier.padding(8.dp))
        Spacer(Modifier.height(8.dp))
        if (alts.size > 1) Box {
            TextButton({ pick = true }) {
                Text(alts.find { it.id == id }?.title?.let(Sources::decodeEntities) ?: t("otherVideo"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(pick, { pick = false }) {
                for (v in alts) DropdownMenuItem({ Text(Sources.decodeEntities(v.title), maxLines = 2) }, { pick = false; choose(v.id) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                paste, { paste = it }, Modifier.weight(1f),
                placeholder = { Text(t("pasteYoutube"), maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton({ Sources.youtubeId(paste.trim())?.let { choose(it); paste = "" } }, enabled = Sources.youtubeId(paste.trim()) != null) { Text("OK") }
        }
        TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))) } }) {
            Text(t("searchYoutube"))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeFrame(id: String) {
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
        <body><iframe src="https://www.youtube-nocookie.com/embed/$id?rel=0&playsinline=1&modestbranding=1"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></body></html>
    """.trimIndent()
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // "Watch on YouTube" and other links: in the YouTube app or the browser.
                        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
                web = this
            }
        },
        update = {
            if (it.tag != id) {
                it.tag = id
                it.loadDataWithBaseURL("https://chords.justapps.it/", html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
