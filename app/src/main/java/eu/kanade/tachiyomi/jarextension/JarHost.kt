package eu.kanade.tachiyomi.jarextension

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONTokener
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * KMK: What both plugin ABIs need from the app: the network, a WebView to run scripts, per-source settings,
 * and bitmaps for image descrambling.
 */
object JarHost {

    val context: Context get() = Injekt.get<Application>()
    val network: NetworkHelper get() = Injekt.get()

    /** Per-source settings a parser reads through its config (domain, user agent, ...) */
    fun prefs(sourceName: String) =
        context.getSharedPreferences("jar_source_${sourceName.lowercase()}", Context.MODE_PRIVATE)

    @Suppress("UNCHECKED_CAST")
    fun <T> readConfig(sourceName: String, key: String, defaultValue: T): T {
        val prefs = prefs(sourceName)
        if (!prefs.contains(key)) return defaultValue
        return runCatching {
            when (defaultValue) {
                is Boolean -> prefs.getBoolean(key, defaultValue) as T
                is Int -> prefs.getInt(key, defaultValue) as T
                is Long -> prefs.getLong(key, defaultValue) as T
                is String -> (prefs.getString(key, defaultValue) ?: defaultValue) as T
                null -> prefs.getString(key, null) as T
                else -> defaultValue
            }
        }.getOrDefault(defaultValue)
    }

    fun writeConfig(sourceName: String, key: String, value: String?) {
        prefs(sourceName).edit().putString(key, value).apply()
    }

    /**
     * Loads [baseUrl] in a hidden WebView and runs [script] once the page is ready, retrying until it returns
     * something other than null or [timeout] runs out.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun evaluateJs(baseUrl: String?, script: String, timeout: Long = 15_000L): String? {
        return withTimeoutOrNull(timeout) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                    val webView = WebView(context)
                    val handler = Handler(Looper.getMainLooper())
                    var finished = false
                    fun finish(value: String?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        runCatching { webView.stopLoading() }
                        runCatching { webView.destroy() }
                        if (cont.isActive) cont.resume(value)
                    }
                    fun attempt() {
                        if (finished) return
                        webView.evaluateJavascript(script) { raw ->
                            val value = decodeJsResult(raw)
                            if (value != null) {
                                finish(value)
                            } else {
                                handler.postDelayed({ attempt() }, 500)
                            }
                        }
                    }
                    with(webView.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = network.defaultUserAgentProvider()
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            attempt()
                        }
                    }
                    if (baseUrl.isNullOrBlank()) {
                        webView.loadDataWithBaseURL(null, "<html></html>", "text/html", "utf-8", null)
                    } else {
                        webView.loadUrl(baseUrl)
                    }
                    cont.invokeOnCancellation { handler.post { finish(null) } }
                }
            }
        }
    }

    private fun decodeJsResult(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        return runCatching {
            when (val value = JSONTokener(raw).nextValue()) {
                is String -> value
                org.json.JSONObject.NULL -> null
                else -> value.toString()
            }
        }.getOrDefault(raw)
    }

    /** Decodes an image response, lets [redraw] rework it, and returns it re-encoded as PNG */
    fun redraw(response: Response, redraw: (Bitmap) -> Bitmap): Response {
        val body = response.body
        val source = body.byteStream().use { BitmapFactory.decodeStream(it) } ?: return response
        val result = redraw(source)
        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, out)
        return response.newBuilder()
            .body(out.toByteArray().toResponseBody("image/png".toMediaType()))
            .build()
    }

    fun createBitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun draw(target: Bitmap, source: Bitmap, src: android.graphics.Rect, dst: android.graphics.Rect) {
        Canvas(target).drawBitmap(source, src, dst, null)
    }
}
