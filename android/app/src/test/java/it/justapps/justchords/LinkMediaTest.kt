package it.justapps.justchords

import it.justapps.justchords.core.LinkMedia
import it.justapps.justchords.data.LinkAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LinkMediaTest {
    private fun tiktokPage(scopeKey: String, music: String = "original sound") = """
        <html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
        {"__DEFAULT_SCOPE__":{"$scopeKey":{"statusCode":0,"itemInfo":{"itemStruct":{
          "id":"1","desc":"Wonderwall cover 🎸 #guitar #fyp @friend",
          "author":{"uniqueId":"strummer","nickname":"Strum Er"},
          "video":{"playAddr":"https://v16.example/video.mp4?a=1","downloadAddr":"","duration":42},
          "music":{"title":"$music","authorName":"Oasis","playUrl":"https://cdn.example/sound.mp3"}
        }}}}}
        </script></html>
    """.trimIndent()

    @Test fun tiktokDesktopAndMobilePages() {
        for (k in listOf("webapp.video-detail", "webapp.reflow.video.detail")) {
            val m = LinkMedia.tiktok(tiktokPage(k))!!
            assertEquals("https://v16.example/video.mp4?a=1", m.url)
            assertEquals(listOf("https://cdn.example/sound.mp3"), m.fallbacks)
            assertEquals("Wonderwall cover 🎸", m.title)
            assertEquals("Strum Er", m.artist)
            assertEquals(42, m.seconds)
        }
    }

    @Test fun tiktokNamedSoundGivesSongAndArtist() {
        val m = LinkMedia.tiktok(tiktokPage("webapp.video-detail", music = "Wonderwall"))!!
        assertEquals("Wonderwall", m.title)
        assertEquals("Oasis", m.artist)
    }

    @Test fun tiktokWithoutDataIsNull() {
        assertNull(LinkMedia.tiktok("<html>nothing</html>"))
    }

    @Test fun instagramEmbedVideoUrl() {
        // The embed nests JSON in a JS string: quotes and slashes are escaped twice.
        val html = """<script>s.handle({"contextJSON":"{\"gql_data\":{\"shortcode_media\":{\"owner\":{\"username\":\"player.one\"},""" +
            """\"video_url\":\"https:\\\/\\\/scontent.cdninstagram.com\\\/v\\\/t50\\\/clip.mp4?efg=x\\u0026oh=1\"}}}"})</script>"""
        val m = LinkMedia.instagram(html)!!
        assertEquals("https://scontent.cdninstagram.com/v/t50/clip.mp4?efg=x&oh=1", m.url)
        assertEquals("player.one", m.artist)
        assertNull(LinkMedia.instagram("<html>photo only</html>"))
    }

    @Test fun instagramPostPageFallbacks() {
        val og = """<meta property="og:video" content="https://scontent.cdninstagram.com/o1/v/clip.mp4?a=1&amp;b=2" />"""
        assertEquals("https://scontent.cdninstagram.com/o1/v/clip.mp4?a=1&b=2", LinkMedia.instagram(og)!!.url)
        val vv = """{"video_versions":[{"type":101,"url":"https:\\/\\/scontent.cdninstagram.com\\/v.mp4?x=1","width":720}]}"""
        assertEquals("https://scontent.cdninstagram.com/v.mp4?x=1", LinkMedia.instagram(vv)!!.url)
        assertEquals("https://www.instagram.com/reel/AbC/", LinkMedia.instagramPage("https://www.instagram.com/reels/AbC/?igsh=x"))
    }

    @Test fun tiktokIds() {
        assertEquals("7312345678901234567", LinkMedia.tiktokId("https://www.tiktok.com/@a.b/video/7312345678901234567?_r=1"))
        assertEquals("7312345678901234567", LinkMedia.tiktokId("https://www.tiktok.com/", """{"canonical":"https://www.tiktok.com/@x/video/7312345678901234567"}"""))
        assertNull(LinkMedia.tiktokId("https://www.tiktok.com/?_r=1"))
    }

    @Test fun instagramEmbedAddress() {
        assertEquals("https://www.instagram.com/reel/AbC_12-x/embed/captioned/", LinkMedia.instagramEmbed("https://www.instagram.com/reels/AbC_12-x/?igsh=1"))
        assertEquals("https://www.instagram.com/p/AbC/embed/captioned/", LinkMedia.instagramEmbed("https://instagram.com/someone/p/AbC/"))
        assertNull(LinkMedia.instagramEmbed("https://www.instagram.com/someone/"))
    }

    // Real platforms: only with JC_ONLINE=1 (they change, and CI must not depend on them).
    private val online = System.getenv("JC_ONLINE") == "1"

    @Test fun onlineYoutube() = runBlocking {
        assumeTrue(online)
        val m = LinkAudio().resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        println("youtube: ${m.title} / ${m.artist} / ${m.seconds}s / ${m.url.take(80)}")
        assertTrue(m.url.startsWith("https://"))
        assertNotNull(m.title)
        assertTrue((m.seconds ?: 0) > 200)
    }

    @Test fun onlineTiktok() = runBlocking {
        assumeTrue(online)
        val m = LinkAudio().resolve("https://www.tiktok.com/@scout2015/video/6718335390845095173")
        println("tiktok: ${m.title} / ${m.artist} / ${m.url.take(80)} + ${m.fallbacks.size}")
        assertTrue(m.url.startsWith("https://"))
    }

    /** Downloads too, to JC_OUT (checked with ffprobe by hand). */
    @Test fun onlineDownload() = runBlocking {
        assumeTrue(online)
        val out = java.io.File(System.getenv("JC_OUT") ?: return@runBlocking)
        val la = LinkAudio()
        for ((name, link, referer) in listOf(
            Triple("yt", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null),
            Triple("tt", "https://www.tiktok.com/@scout2015/video/6718335390845095173", "https://www.tiktok.com/"),
        )) {
            val m = la.resolve(link)
            val f = java.io.File(out, name)
            val t0 = System.currentTimeMillis()
            println("$name hosts: " + (listOf(m.url) + m.fallbacks).map { java.net.URI(it).host })
            val used = runCatching { la.download(m, f, referer) {} }
            println("$name: ${used.exceptionOrNull() ?: "ok"} ${f.length()} bytes in ${System.currentTimeMillis() - t0} ms from ${used.getOrNull()?.take(60)}")
        }
    }
}
