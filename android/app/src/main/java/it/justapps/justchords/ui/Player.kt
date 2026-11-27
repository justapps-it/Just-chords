package it.justapps.justchords.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import it.justapps.justchords.data.LinkAudio
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.justapps.justchords.core.Sources
import it.justapps.justchords.core.VideoRef
import it.justapps.justchords.data.Song
import it.justapps.justchords.data.Video

/** The song's YouTube video under the toolbar, played by the app itself (no embed). */
@Composable
fun YouTubePlayer(song: Song, onClose: () -> Unit) {
    val t = rememberT()
    val a = app()
    var id by remember { mutableStateOf(song.youtube ?: "") }
    var alts by remember { mutableStateOf<List<Video>>(emptyList()) }
    var state by remember { mutableStateOf(if (song.youtube != null) "idle" else "search") }
    var pick by remember { mutableStateOf(false) }
    val query = "${song.artist} ${song.title}".trim()

    fun choose(v: String) {
        id = v
        state = "idle"
        a.library.patchSong(song.id, sync = true) { it.copy(youtube = v) }
    }
    LaunchedEffect(Unit) {
        try {
            val r = a.account.api.videos(query)
            alts = r
            if (song.youtube == null) { if (r.isEmpty()) state = "error" else choose(r[0].id) }
        } catch (e: Exception) {
            if (song.youtube == null) state = "error"
        }
    }

    JustCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("▶ ${t("listenSong")}", Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClose) { Icon(Icons.Rounded.Close, t("close")) }
        }
        when {
            id.isNotEmpty() && state == "idle" -> NativeYouTube(id)
            state == "search" -> Muted(t("searching"), Modifier.padding(8.dp))
            else -> Muted(t("noVideo"), Modifier.padding(8.dp))
        }
        if (alts.size > 1) Box {
            TextButton({ pick = true }) {
                Text(alts.find { it.id == id }?.title?.let(Sources::decodeEntities) ?: t("otherVideo"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(pick, { pick = false }) {
                for (v in alts) DropdownMenuItem({ Text(Sources.decodeEntities(v.title), maxLines = 2) }, { pick = false; choose(v.id) })
            }
        }
    }
}

/**
 * A YouTube video played natively: the phone fetches the streams (NewPipeExtractor) and
 * ExoPlayer plays video and sound together, with play/pause, a seek bar and the speed.
 */
@OptIn(UnstableApi::class)
@Composable
fun NativeYouTube(id: String, speeds: List<Float> = listOf(0.5f, 0.75f, 1f), initialSpeed: Int = speeds.lastIndex, onEnded: ((ExoPlayer) -> Unit)? = null, speedIndex: Int? = null) {
    val t = rememberT()
    val ctx = LocalContext.current
    var media by remember(id) { mutableStateOf<LinkAudio.Playable?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var ready by remember(id) { mutableStateOf(false) }
    var playing by remember(id) { mutableStateOf(false) }
    var pos by remember(id) { mutableFloatStateOf(0f) }
    var dur by remember(id) { mutableFloatStateOf(1f) }
    var speed by remember(id) { mutableIntStateOf(initialSpeed) }
    val ended by androidx.compose.runtime.rememberUpdatedState(onEnded)
    val player = remember(id) { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) { ready = true; dur = maxOf(1f, player.duration.toFloat()) }
                if (state == androidx.media3.common.Player.STATE_ENDED) ended?.invoke(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(id) {
        val m = runCatching { LinkAudio().youtubePlayable(id) }.getOrNull()
        if (m == null) { failed = true; return@LaunchedEffect }
        media = m
        val http = DefaultHttpDataSource.Factory().setUserAgent(LinkAudio.UA).setAllowCrossProtocolRedirects(true)
        val f = ProgressiveMediaSource.Factory(http)
        val audio = f.createMediaSource(MediaItem.fromUri(m.audio))
        player.setMediaSource(m.video?.let { MergingMediaSource(f.createMediaSource(MediaItem.fromUri(it)), audio) } ?: audio)
        player.setPlaybackSpeed(speeds[speed])
        player.prepare()
        player.play()
    }
    LaunchedEffect(speedIndex) { if (speedIndex != null) speed = speedIndex }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speeds[speed]) }
    LaunchedEffect(playing) {
        while (playing) {
            pos = player.currentPosition.toFloat()
            delay(250)
        }
    }

    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { useController = false; this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
        if (media != null && media?.video == null) AsyncImage("https://i.ytimg.com/vi/$id/hqdefault.jpg", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        when {
            failed -> Text(t("noVideo"), color = Color.White, modifier = Modifier.padding(16.dp))
            !ready -> CircularProgressIndicator(color = JustOrange)
        }
    }
    if (ready && !failed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = JustOrange)
            }
            Slider(pos, { pos = it; player.seekTo(it.toLong()) }, Modifier.weight(1f), valueRange = 0f..dur, colors = SliderDefaults.colors(thumbColor = JustOrange, activeTrackColor = JustOrange))
            Text("%d:%02d".format(pos.toInt() / 60000, pos.toInt() / 1000 % 60), color = just.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("speed"), color = just.muted, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            speeds.forEachIndexed { i, s ->
                FilterChip(i == speed, { speed = i }, { Text(if (s == 1f) "1×" else "$s×") }, Modifier.padding(end = 6.dp), colors = chipColors())
            }
        }
    }
}

/** The platform's official embedded player (YouTube, TikTok, Instagram) in a WebView: played, never downloaded. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoFrame(ref: VideoRef, modifier: Modifier) {
    val id = "${ref.platform}:${ref.id}"
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
        <body><iframe src="${Sources.embedUrl(ref)}"
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
        modifier = modifier,
    )
}
