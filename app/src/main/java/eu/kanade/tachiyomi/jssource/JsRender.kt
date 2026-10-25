package eu.kanade.tachiyomi.jssource

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.resume

/**
 * KMK: host.render(url, options) for JS sources: opens the page in a hidden WebView, waits for it (and for
 * `waitFor` / `waitForResource`), runs `script` if given, and hands back the page's HTML, address, the network
 * addresses matching `capture`, and the script's result.
 */
internal object JsRender {

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val SETTLE_MS = 500L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(context: Context, payload: JsonObject): Map<String, Any?> {
        val url = payload.str("url") ?: throw IllegalArgumentException("host.render needs a url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "host.render needs an http(s) address" }
        if (!WebViewUtil.supportsWebView(context)) throw IllegalStateException("This device has no WebView to render pages with")
        val waitFor = payload.str("waitFor")
        val waitForResource = payload.str("waitForResource")?.let(::Regex)
        val capture = payload.str("capture")?.let(::Regex)
        val script = payload.str("script")
        val timeout = payload.long("timeout") ?: DEFAULT_TIMEOUT_MS
        val userAgent = payload.str("userAgent")
            ?: Injekt.get<NetworkHelper>().defaultUserAgentProvider()

        return withContext(Dispatchers.Main) {
            val loaded = CompletableDeferred<Unit>()
            // Every address the page and its frames ask for: a player inside an iframe loads its stream there,
            // where the page's own performance entries can't see it
            val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
            val webView = WebView(context).apply {
                setDefaultSettings()
                settings.userAgentString = userAgent
                settings.blockNetworkImage = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        request?.url?.toString()?.let { if (requested.size < 3000) requested.add(it) }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.complete(Unit)
                    }
                }
            }
            try {
                webView.loadUrl(url)
                val started = System.currentTimeMillis()
                withTimeoutOrNull(timeout) { loaded.await() }

                var timedOut = false
                while (true) {
                    val state = webView.evalJson(probeScript(waitFor))
                    val complete = (state["complete"] as? JsonPrimitive)?.content == "true"
                    val hasSelector = (state["hasSelector"] as? JsonPrimitive)?.content == "true"
                    val resources = state.strings("resources") + synchronized(requested) { requested.toList() }
                    val resourceReady = waitForResource == null || resources.any { waitForResource.containsMatchIn(it) }
                    if (complete && hasSelector && resourceReady) break
                    if (System.currentTimeMillis() - started >= timeout) {
                        timedOut = true
                        break
                    }
                    delay(250)
                }
                if (!timedOut) delay(SETTLE_MS)

                var result: String? = null
                if (script != null) {
                    webView.evalRaw(runScript(script))
                    val scriptStarted = System.currentTimeMillis()
                    while (true) {
                        val poll = webView.evalJson(POLL_SCRIPT)
                        if ((poll["done"] as? JsonPrimitive)?.content == "true") {
                            result = poll.str("error") ?: poll.str("result")
                            break
                        }
                        if (System.currentTimeMillis() - scriptStarted > 30_000) {
                            result = "ERROR: script timed out"
                            break
                        }
                        delay(100)
                    }
                }

                val page = webView.evalJson(CAPTURE_SCRIPT)
                val seen = HashSet<String>()
                mapOf(
                    "html" to page.str("html").orEmpty(),
                    "url" to (page.str("url") ?: url),
                    "resources" to if (capture == null) {
                        emptyList()
                    } else {
                        (page.strings("resources") + synchronized(requested) { requested.toList() })
                            .filter { capture.containsMatchIn(it) && seen.add(it) }
                    },
                    "result" to result,
                    "timedOut" to timedOut,
                )
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private fun JsonObject.strings(key: String): List<String> =
        arr(key)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private suspend fun WebView.evalRaw(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { cont.resume(it) }
    }

    /** Runs a script that returns JSON.stringify(...) and reads it back */
    private suspend fun WebView.evalJson(script: String): JsonObject {
        val raw = evalRaw(script) ?: return JsonObject(emptyMap())
        return runCatching {
            val text = jsJson.parseToJsonElement(raw).jsonPrimitive.content
            jsJson.parseToJsonElement(text).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun probeScript(selector: String?) = """
        (function () {
          var found = false;
          try { found = ${if (selector == null) "true" else "!!document.querySelector(${selector.jsQuoted()})"}; } catch (e) { found = false; }
          return JSON.stringify({
            complete: document.readyState === 'complete',
            hasSelector: found,
            resources: performance.getEntriesByType('resource').map(function (e) { return e.name; })
          });
        })()
    """.trimIndent()

    private fun runScript(user: String) = """
        (function () {
          window.__jsHostDone = false;
          window.__jsHostResult = null;
          window.__jsHostError = null;
          try {
            var val = (0, eval)(${user.jsQuoted()});
            if (val && typeof val.then === 'function') {
              val.then(function (res) {
                window.__jsHostResult = res === undefined || res === null ? null : String(res);
                window.__jsHostDone = true;
              }).catch(function (err) {
                window.__jsHostError = 'ERROR: ' + (err && err.message ? err.message : String(err));
                window.__jsHostDone = true;
              });
            } else {
              window.__jsHostResult = val === undefined || val === null ? null : String(val);
              window.__jsHostDone = true;
            }
          } catch (e) {
            window.__jsHostError = 'ERROR: ' + (e && e.message ? e.message : String(e));
            window.__jsHostDone = true;
          }
          return 'started';
        })()
    """.trimIndent()

    private val POLL_SCRIPT = """
        (function () {
          return JSON.stringify({ done: window.__jsHostDone === true, result: window.__jsHostResult, error: window.__jsHostError });
        })()
    """.trimIndent()

    private val CAPTURE_SCRIPT = """
        (function () {
          return JSON.stringify({
            html: document.documentElement.outerHTML,
            url: location.href,
            resources: performance.getEntriesByType('resource').map(function (e) { return e.name; })
          });
        })()
    """.trimIndent()
}
