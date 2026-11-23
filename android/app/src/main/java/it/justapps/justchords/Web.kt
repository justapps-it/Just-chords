package it.justapps.justchords

import android.net.Uri

/** Pure helpers for the WebView host (unit-tested on the JVM). */
object Web {
    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"

    private val MIME = mapOf(
        "html" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript", "css" to "text/css",
        "json" to "application/json", "webmanifest" to "application/manifest+json", "wasm" to "application/wasm",
        "svg" to "image/svg+xml", "png" to "image/png", "ico" to "image/x-icon", "woff2" to "font/woff2", "woff" to "font/woff",
        "txt" to "text/plain",
    )

    fun mime(path: String): String = MIME[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** Asset to serve for a request path: real files as they are, routes → index.html. */
    fun assetFor(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#').trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return "web/index.html"
        return if ('.' in clean.substringAfterLast('/')) "web/$clean" else "web/index.html"
    }

    /** URL that opens the importer with shared text (and link, if any). */
    fun shareUrl(subject: String?, text: String?): String {
        val link = text?.let { Regex("https?://\\S+").find(it)?.value }
        val b = StringBuilder("$ORIGIN/share?")
        fun add(k: String, v: String?) {
            if (!v.isNullOrBlank()) b.append(k).append('=').append(enc(v)).append('&')
        }
        add("title", subject)
        add("text", text)
        add("url", link)
        return b.toString().trimEnd('&', '?')
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun isInternal(url: String): Boolean = url.startsWith(ORIGIN)

    @Suppress("unused")
    fun parse(u: String): Uri = Uri.parse(u)
}
