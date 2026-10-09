package eu.kanade.tachiyomi.sumizuri

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
 * KMK: host.render(url, options) for Sumizuri sources: opens the page in a hidden WebView, waits for it (and for
 * `waitFor` / `waitForResource`), runs `script` if given, and hands back the page's HTML, address, the network
 * addresses matching `capture`, and the script's result. Ported from sumizuri-flutter's render_runner.
 */
internal object SumizuriRender {

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
            val webView = WebView(context).apply {
                setDefaultSettings()
                settings.userAgentString = userAgent
                settings.blockNetworkImage = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

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
                    val resources = state.strings("resources")
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
                        page.strings("resources").filter { capture.containsMatchIn(it) && seen.add(it) }
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
            val text = sumizuriJson.parseToJsonElement(raw).jsonPrimitive.content
            sumizuriJson.parseToJsonElement(text).jsonObject
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
          window.__sumizuriDone = false;
          window.__sumizuriResult = null;
          window.__sumizuriError = null;
          try {
            var val = (0, eval)(${user.jsQuoted()});
            if (val && typeof val.then === 'function') {
              val.then(function (res) {
                window.__sumizuriResult = res === undefined || res === null ? null : String(res);
                window.__sumizuriDone = true;
              }).catch(function (err) {
                window.__sumizuriError = 'ERROR: ' + (err && err.message ? err.message : String(err));
                window.__sumizuriDone = true;
              });
            } else {
              window.__sumizuriResult = val === undefined || val === null ? null : String(val);
              window.__sumizuriDone = true;
            }
          } catch (e) {
            window.__sumizuriError = 'ERROR: ' + (e && e.message ? e.message : String(e));
            window.__sumizuriDone = true;
          }
          return 'started';
        })()
    """.trimIndent()

    private val POLL_SCRIPT = """
        (function () {
          return JSON.stringify({ done: window.__sumizuriDone === true, result: window.__sumizuriResult, error: window.__sumizuriError });
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
