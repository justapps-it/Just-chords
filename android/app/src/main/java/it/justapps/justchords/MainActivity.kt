package it.justapps.justchords

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Just-Chords for Android: the web app bundled in the APK (works offline from
 * the first launch) in a WebView, plus what a browser can't do: microphone
 * permission, keeping the screen on, importing text shared from other apps.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val req = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        if (ok) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        else {
            req.deny()
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "JustChordsAndroid")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != Web.HOST) return null
                val asset = Web.assetFor(url.path ?: "/")
                return runCatching {
                    WebResourceResponse(Web.mime(asset), if (Web.mime(asset).startsWith("text/")) "utf-8" else null, assets.open(asset))
                }.getOrElse { WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, "".byteInputStream()) }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (Web.isInternal(url)) return false
                // Ultimate Guitar, Accordi e Spartiti, GitHub…: in the browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) return@runOnUiThread request.deny()
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingPermission = request
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return runCatching { pickFile.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE)); true }.getOrElse {
                    fileCallback = null
                    false
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) web.loadUrl(startUrl(intent))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun startUrl(i: Intent?): String =
        if (i?.action == Intent.ACTION_SEND) Web.shareUrl(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
        else "${Web.ORIGIN}/"

    /** Answers a JS call through window.__jcNative(id, ok, status, url, body). */
    private fun reply(id: String, ok: Boolean, status: Int, url: String, body: String) = runOnUiThread {
        val js = "window.__jcNative&&window.__jcNative(${JSONObject.quote(id)},$ok,$status,${JSONObject.quote(url)},${JSONObject.quote(body)})"
        web.evaluateJavascript(js, null)
    }

    /** Downloads a chords page from the phone itself, like the browser would. */
    private suspend fun download(start: String): Triple<Int, String, String> = withContext(Dispatchers.IO) {
        var url = start
        repeat(5) {
            if (!Web.fetchAllowed(url)) return@withContext Triple(0, url, "blocked host")
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "text/html,application/json")
            c.setRequestProperty("Accept-Language", "it,en;q=0.8")
            c.setRequestProperty("User-Agent", web.settings.userAgentString)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: return@withContext Triple(code, url, "")).toString()
                    return@repeat
                }
                val stream = if (code < 400) c.inputStream else c.errorStream
                // Pages are a few hundred KB; stop at 4 MB (readNBytes needs API 33).
                val text = stream?.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < 4 * 1024 * 1024) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                } ?: ""
                return@withContext Triple(code, url, text)
            } finally {
                c.disconnect()
            }
        }
        Triple(0, url, "too many redirects")
    }

    private fun startGoogleSignIn(id: String) = lifecycleScope.launch {
        try {
            val option = GetSignInWithGoogleOption.Builder(getString(R.string.google_web_client_id)).build()
            val res = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, GetCredentialRequest(listOf(option)))
            val token = GoogleIdTokenCredential.createFrom(res.credential.data).idToken
            reply(id, true, 200, "", token)
        } catch (e: GetCredentialCancellationException) {
            reply(id, false, 0, "", "")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never crash on sign-in: the page shows the error instead.
            reply(id, false, 0, "", e.message ?: e.javaClass.simpleName)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun keepScreenOn(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @JavascriptInterface
        fun fetchText(id: String, url: String) {
            if (!Web.fetchAllowed(url)) return reply(id, false, 0, url, "blocked host")
            lifecycleScope.launch {
                val (code, finalUrl, body) = runCatching { download(url) }.getOrElse { Triple(0, url, it.message ?: "error") }
                reply(id, code in 200..299, code, finalUrl, body)
            }
        }

        @JavascriptInterface
        fun googleSignIn(id: String) {
            // Explicitly the activity's method: an unqualified call here resolved
            // to this bridge method itself and recursed until the app crashed.
            runOnUiThread { this@MainActivity.startGoogleSignIn(id) }
        }
    }
}
