package it.justapps.justchords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTest {
    @Test fun routesServeIndex() {
        assertEquals("web/index.html", Web.assetFor("/"))
        assertEquals("web/index.html", Web.assetFor("/share"))
        assertEquals("web/assets/index-abc.js", Web.assetFor("/assets/index-abc.js"))
        assertEquals("web/dsp.wasm", Web.assetFor("/dsp.wasm?x=1"))
        assertEquals("web/index.html", Web.assetFor("/../secret.txt"))
    }

    @Test fun mimeTypes() {
        assertEquals("application/wasm", Web.mime("web/dsp.wasm"))
        assertEquals("text/javascript", Web.mime("a.js"))
    }

    @Test fun shareUrl() {
        val u = Web.shareUrl("Wonderwall", "Look https://tabs.example/x?a=1 nice")
        assertTrue(u.startsWith("https://appassets.androidplatform.net/share?title=Wonderwall&text=Look%20https"))
        assertTrue(u.endsWith("&url=https%3A%2F%2Ftabs.example%2Fx%3Fa%3D1"))
        assertEquals("https://appassets.androidplatform.net/share", Web.shareUrl(null, null))
    }
}
