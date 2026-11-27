package it.justapps.justchords.data

import it.justapps.justchords.core.LinkKind
import it.justapps.justchords.core.LinkMedia
import it.justapps.justchords.core.MediaRef
import it.justapps.justchords.core.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.CookieManager
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The sound of a shared link, fetched by the phone itself (no screen
 * recording): YouTube through NewPipeExtractor, TikTok and Instagram from
 * their public pages. The file goes to the app cache and is deleted after
 * the analysis; nothing is uploaded.
 */
class LinkAudio(private val userAgent: String = UA) {
    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"
        /** About 8 minutes of 128 kbit/s audio, or a short video. */
        const val MAX_BYTES = 24L shl 20

        @Volatile private var newPipeReady = false

        private fun readAll(c: HttpURLConnection, cap: Int = 6 shl 20): String {
            val s = (if (c.responseCode < 400) c.inputStream else c.errorStream) ?: return ""
            s.use {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < cap) {
                    val n = it.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                return out.toString("UTF-8")
            }
        }
    }

    class NotFound(message: String) : Exception(message)

    /** Cookies of the platform pages (TikTok media URLs need the page's cookies). */
    private val cookies = CookieManager()

    private fun open(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Language", "it,en;q=0.8")
        val uri = URI(url)
        cookies.cookieStore.get(uri).takeIf { it.isNotEmpty() }?.let { list ->
            c.setRequestProperty("Cookie", list.joinToString("; ") { "${it.name}=${it.value}" })
        }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun keepCookies(c: HttpURLConnection) {
        val uri = runCatching { c.url.toURI() }.getOrNull() ?: return
        c.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { h ->
            runCatching { HttpCookie.parse(h) }.getOrNull()?.forEach { ck ->
                if (ck.domain == null) ck.domain = uri.host
                if (ck.path == null) ck.path = "/"
                cookies.cookieStore.add(uri, ck)
            }
        }
    }

    /** A page, following redirects by hand so cookies of every hop are kept. */
    private fun page(start: String): Pair<String, String> {
        var url = start
        repeat(6) {
            val c = open(url, mapOf("Accept" to "text/html,application/xhtml+xml"))
            c.instanceFollowRedirects = false
            try {
                val code = c.responseCode
                keepCookies(c)
                if (code in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("redirect")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return url to readAll(c)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    // ------------------------------------------------------------ resolve

    suspend fun resolve(link: String): MediaRef = withContext(Dispatchers.IO) {
        val kind = Sources.linkKind(link)
        when (kind) {
            LinkKind.YOUTUBE -> youtube(link)
            LinkKind.TIKTOK -> {
                val (final, html) = page(link)
                LinkMedia.tiktok(html) ?: run {
                    // Short links and app links can land on a page without the data: ask for the plain video page.
                    val id = LinkMedia.tiktokId(final, html) ?: LinkMedia.tiktokId(link) ?: throw NotFound("TikTok: no video id in $final")
                    LinkMedia.tiktok(page("https://www.tiktok.com/@_/video/$id").second)
                } ?: throw NotFound("TikTok: the page has no video data")
            }
            LinkKind.INSTAGRAM -> {
                val tries = listOfNotNull(LinkMedia.instagramEmbed(link), LinkMedia.instagramPage(link))
                if (tries.isEmpty()) throw NotFound("Instagram: not a post link")
                tries.firstNotNullOfOrNull { u -> runCatching { LinkMedia.instagram(page(u).second) }.getOrNull() }
                    ?: throw NotFound("Instagram: the post page has no video (private, or Instagram asks to log in)")
            }
            else -> throw NotFound("unsupported")
        }
    }

    private fun youtube(link: String): MediaRef {
        initNewPipe()
        val info = StreamInfo.getInfo(ServiceList.YouTube, link)
        val streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val ordered = pickAudio(streams)
        if (ordered.isEmpty()) throw NotFound("youtube")
        return MediaRef(
            ordered.first().content,
            title = info.name,
            artist = info.uploaderName?.removeSuffix(" - Topic"),
            seconds = info.duration.toInt().takeIf { it > 0 },
            fallbacks = ordered.drop(1).take(2).map { it.content },
        )
    }

    /** AAC (m4a) first, as every Android decodes it; then by modest bitrate (enough for chords, quick to fetch). */
    private fun pickAudio(s: List<AudioStream>): List<AudioStream> =
        s.sortedWith(
            compareBy<AudioStream>({ if (it.format == MediaFormat.M4A) 0 else 1 }, { it.averageBitrate.let { b -> if (b <= 0) 999 else kotlin.math.abs(b - 128) } }),
        )

    private fun initNewPipe() {
        if (newPipeReady) return
        synchronized(LinkAudio::class.java) {
            if (!newPipeReady) {
                NewPipe.init(NewPipeDownloader(userAgent), Localization("it", "IT"), ContentCountry("IT"))
                newPipeReady = true
            }
        }
    }

    // ----------------------------------------------------------- download

    /**
     * Downloads [ref] to [dest] (or one of its fallbacks), in ranges like a
     * player does, up to [MAX_BYTES]. Returns the address that worked.
     */
    suspend fun download(ref: MediaRef, dest: File, referer: String?, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (url in listOf(ref.url) + ref.fallbacks) {
            try {
                fetchTo(url, dest, referer, onProgress)
                if (dest.length() > 1024) return@withContext url
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        dest.delete()
        throw last ?: IOException("empty")
    }

    private suspend fun fetchTo(start: String, dest: File, referer: String?, onProgress: (Float) -> Unit) {
        var url = start
        val chunk = 1L shl 20
        var total = -1L
        var got = 0L
        dest.outputStream().use { out ->
            while (got < MAX_BYTES && (total < 0 || got < total)) {
                coroutineContext.ensureActive()
                val headers = buildMap {
                    put("Range", "bytes=$got-${got + chunk - 1}")
                    put("Accept", "*/*")
                    if (referer != null) put("Referer", referer)
                }
                var c = open(url, headers)
                var hops = 0
                // Redirects by hand: media servers send ranges on to another node, and the next chunk should go straight there.
                while (c.apply { instanceFollowRedirects = false }.responseCode in 300..399 && hops++ < 5) {
                    val next = URL(URL(url), c.getHeaderField("Location") ?: break).toString()
                    c.disconnect()
                    url = next
                    c = open(url, headers)
                }
                try {
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code from ${c.url.host}")
                    if (total < 0) {
                        total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: c.contentLengthLong.takeIf { code == 200 } ?: -1L
                    }
                    var n = 0L
                    c.inputStream.use { s ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = s.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            n += r
                            got += r
                            if (got >= MAX_BYTES) break
                        }
                    }
                    if (total > 0) onProgress((got.toFloat() / minOf(total, MAX_BYTES)).coerceAtMost(1f))
                    // A server that ignores Range sends everything at once.
                    if (code == 200 || n == 0L) break
                } finally {
                    c.disconnect()
                }
            }
        }
        onProgress(1f)
    }
}

/** HTTP for NewPipeExtractor, on HttpURLConnection (no OkHttp needed). */
private class NewPipeDownloader(private val userAgent: String) : Downloader() {
    override fun execute(request: Request): Response {
        val c = URL(request.url()).openConnection() as HttpURLConnection
        c.requestMethod = request.httpMethod()
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", userAgent)
        request.headers().forEach { (k, vs) ->
            c.setRequestProperty(k, null)
            vs.forEach { v -> c.addRequestProperty(k, v) }
        }
        try {
            request.dataToSend()?.let { body ->
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val s = if (code < 400) c.inputStream else c.errorStream
            val body = s?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return Response(code, c.responseMessage ?: "", c.headerFields.filterKeys { it != null }, body, c.url.toString())
        } finally {
            c.disconnect()
        }
    }
}
