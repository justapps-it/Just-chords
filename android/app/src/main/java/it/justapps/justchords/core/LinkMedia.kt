package it.justapps.justchords.core

import org.json.JSONObject

/**
 * Where the sound of a shared video is, read from the public page the phone
 * downloads (TikTok, Instagram). YouTube goes through NewPipeExtractor
 * (data/LinkAudio.kt). Only the audio is used, for "Analizza".
 */
data class MediaRef(
    val url: String,
    val title: String?,
    val artist: String?,
    val seconds: Int?,
    /** Other addresses to try if the first does not download. */
    val fallbacks: List<String> = emptyList(),
)

object LinkMedia {
    private val rehydration = Regex("""<script[^>]+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val sigi = Regex("""<script[^>]+id="SIGI_STATE"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** TikTok video page → the video's own sound first, the "sound" track (mp3) as fallback. */
    fun tiktok(html: String): MediaRef? {
        val item = tiktokItem(html) ?: return null
        val video = item.optJSONObject("video") ?: JSONObject()
        val music = item.optJSONObject("music") ?: JSONObject()
        val urls = listOf(
            video.optString("playAddr"),
            video.optString("downloadAddr"),
            music.optString("playUrl"),
        ).filter { it.startsWith("https://") }.distinct()
        if (urls.isEmpty()) return null
        val author = item.optJSONObject("author")
        val sound = music.optString("title").takeIf { it.isNotBlank() && !it.startsWith("original sound") && !it.startsWith("suono originale") }
        return MediaRef(
            urls.first(),
            title = sound ?: item.optString("desc").ifBlank { null }?.let(::cleanCaption),
            artist = (if (sound != null) music.optString("authorName") else null)?.ifBlank { null }
                ?: author?.optString("nickname")?.ifBlank { null } ?: author?.optString("uniqueId")?.ifBlank { null },
            seconds = video.optInt("duration").takeIf { it > 0 },
            fallbacks = urls.drop(1),
        )
    }

    private fun tiktokItem(html: String): JSONObject? {
        rehydration.find(html)?.let { m ->
            val scope = runCatching { JSONObject(m.groupValues[1]).getJSONObject("__DEFAULT_SCOPE__") }.getOrNull() ?: return@let
            // Desktop pages use webapp.video-detail, mobile ones webapp.reflow.video.detail.
            for (k in listOf("webapp.video-detail", "webapp.reflow.video.detail")) {
                scope.optJSONObject(k)?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")?.let { return it }
            }
        }
        sigi.find(html)?.let { m ->
            val items = runCatching { JSONObject(m.groupValues[1]).getJSONObject("ItemModule") }.getOrNull() ?: return null
            val k = items.keys().asSequence().firstOrNull() ?: return null
            return items.optJSONObject(k)
        }
        return null
    }

    /** Hashtags and mentions out, first line only. */
    fun cleanCaption(s: String): String? =
        s.lineSequence().first().replace(Regex("""[#@][\p{L}\p{N}_.]+"""), "").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { null }

    private val videoUrl = Regex("""\\*"video_url\\*"\s*:\s*\\*"((?:[^"\\]|\\.)+?)\\*"""")
    private val owner = Regex("""\\*"username\\*"\s*:\s*\\*"([A-Za-z0-9._]+)\\*"""")

    private val ogVideo = Regex("""<meta[^>]+property="og:video(?::secure_url)?"[^>]+content="([^"]+)"""")
    private val videoVersions = Regex("""\\*"video_versions\\*"\s*:\s*\[\s*\{[^\]]*?\\*"url\\*"\s*:\s*\\*"((?:[^"\\]|\\.)+?)\\*"""")

    /** Instagram embed or post page → the video file, when the page carries it (video_url, video_versions or og:video). */
    fun instagram(html: String): MediaRef? {
        val raw = videoUrl.find(html)?.groupValues?.get(1)
            ?: videoVersions.find(html)?.groupValues?.get(1)
            ?: ogVideo.find(html)?.groupValues?.get(1)
            ?: return null
        val url = unescapeJson(raw)
        if (!url.startsWith("https://")) return null
        return MediaRef(url, title = null, artist = owner.find(html)?.groupValues?.get(1), seconds = null)
    }

    /** Instagram post URL → its plain page. */
    fun instagramPage(url: String): String? =
        Regex("""instagram\.com/(?:[A-Za-z0-9._]+/)?(p|reels?|tv)/([A-Za-z0-9_-]+)""").find(url)?.let { m ->
            val kind = if (m.groupValues[1].startsWith("reel")) "reel" else m.groupValues[1]
            "https://www.instagram.com/$kind/${m.groupValues[2]}/"
        }

    /** The numeric id of a TikTok video, from its URL or page. */
    fun tiktokId(url: String, html: String = ""): String? =
        Regex("""/(?:video|v|photo)/(\d{8,})""").find(url)?.groupValues?.get(1)
            ?: Regex("""tiktok\.com/@[^/"]*/(?:video|photo)/(\d{8,})""").find(html)?.groupValues?.get(1)

    /** Undoes one or more levels of JSON string escaping (the embed nests JSON in JSON). */
    fun unescapeJson(s: String): String {
        var cur = s
        repeat(3) {
            if (!cur.contains('\\')) return cur.replace("&amp;", "&")
            val sb = StringBuilder()
            var i = 0
            while (i < cur.length) {
                val c = cur[i]
                if (c == '\\' && i + 1 < cur.length) {
                    val n = cur[i + 1]
                    when (n) {
                        'u' -> if (i + 5 < cur.length) {
                            sb.append(cur.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue
                        }
                        '/', '\\', '"' -> { sb.append(n); i += 2; continue }
                        'n' -> { sb.append('\n'); i += 2; continue }
                    }
                }
                sb.append(c)
                i++
            }
            cur = sb.toString()
        }
        return cur.replace("&amp;", "&")
    }

    /** Instagram post/reel URL → its embed page, or null. */
    fun instagramEmbed(url: String): String? =
        Regex("""instagram\.com/(?:[A-Za-z0-9._]+/)?(p|reels?|tv)/([A-Za-z0-9_-]+)""").find(url)?.let { m ->
            val kind = if (m.groupValues[1].startsWith("reel")) "reel" else m.groupValues[1]
            "https://www.instagram.com/$kind/${m.groupValues[2]}/embed/captioned/"
        }
}
