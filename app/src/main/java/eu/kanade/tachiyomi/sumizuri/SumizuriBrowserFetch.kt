package eu.kanade.tachiyomi.sumizuri

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * KMK: Requests made from inside a hidden browser page on the site, like Sumizuri does.
 *
 * Cloudflare often lets a real browser through while it keeps challenging the app's own connection (it tells them
 * apart by more than cookies), so the app's usual Cloudflare helper can't pass. When that happens, a source's
 * request is sent with the page's own fetch(): the browser's connection, cookies and user agent. One page is kept
 * open per site for a few minutes, so later requests are quick, and the site goes this way from then on.
 */
internal object SumizuriBrowserFetch {

    private const val IDLE_MS = 3 * 60_000L
    private const val CHALLENGE_WAIT_MS = 30_000L

    /** Sites whose requests go through the browser first */
    val browserFirst: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class Tab(val webView: WebView, var lastUse: Long)

    private val tabs = HashMap<String, Tab>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val handler = Handler(Looper.getMainLooper())

    fun originOf(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }

    /** Whether a reply is a Cloudflare (or similar) bot check instead of the page */
    fun isChallenge(code: Int, server: String?, body: String): Boolean =
        code in listOf(403, 429, 503) &&
            (server?.contains("cloudflare", true) == true || "cf-chl" in body || "challenge-platform" in body) &&
            listOf("challenge-platform", "cf-chl", "Just a moment", "challenge-error-title", "cf_chl_opt", "Attention Required")
                .any { it in body }

    suspend fun fetch(
        context: Context,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        binary: Boolean,
    ): Map<String, Any?> {
        val origin = originOf(url) ?: throw IllegalArgumentException("Not a web address: $url")
        val lock = locks.getOrPut(origin) { Mutex() }
        return lock.withLock {
            val started = System.currentTimeMillis()
            val tab = withContext(Dispatchers.Main) { openTab(context, origin) }
            try {
                val result = withContext(Dispatchers.Main) { run(tab.webView, url, method, headers, body, binary) }
                AppLog.add('D', "net", "BROWSER $method $url -> ${result["statusCode"]} in ${System.currentTimeMillis() - started} ms")
                result
            } finally {
                withContext(Dispatchers.Main) {
                    tab.lastUse = System.currentTimeMillis()
                    handler.postDelayed({ closeIfIdle(origin) }, IDLE_MS + 1000)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun openTab(context: Context, origin: String): Tab {
        tabs[origin]?.let { return it }
        val loaded = CompletableDeferred<Unit>()
        val webView = WebView(context.applicationContext).apply {
            setDefaultSettings()
            settings.userAgentString = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
            settings.blockNetworkImage = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
        }
        webView.loadUrl("$origin/")
        withTimeoutOrNull(CHALLENGE_WAIT_MS) { loaded.await() }
        // Wait out a bot check the browser can pass by itself
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < CHALLENGE_WAIT_MS) {
            val state = webView.evalString(
                "(function(){var t=document.title||'';var h=document.documentElement?document.documentElement.innerHTML:'';" +
                    "return (/Just a moment|Attention Required|Checking your browser/i.test(t)||h.indexOf('challenge-platform')>=0&&h.length<60000)?'challenge':document.readyState;})()",
            )
            if (state == "complete" || state == "interactive") break
            delay(500)
        }
        val stillChallenged = webView.evalString(
            "(function(){return /Just a moment|Attention Required|Checking your browser/i.test(document.title||'')?'yes':'no';})()",
        ) == "yes"
        if (stillChallenged) {
            webView.destroy()
            throw IllegalStateException(
                "$origin wants you to pass its bot check: open the source in WebView, pass it, then try again",
            )
        }
        return Tab(webView, System.currentTimeMillis()).also { tabs[origin] = it }
    }

    private fun closeIfIdle(origin: String) {
        val tab = tabs[origin] ?: return
        if (System.currentTimeMillis() - tab.lastUse >= IDLE_MS) {
            tabs.remove(origin)
            tab.webView.stopLoading()
            tab.webView.destroy()
        }
    }

    // Headers a page's fetch() may not set; the browser sends its own
    private val FORBIDDEN = setOf(
        "user-agent", "referer", "cookie", "origin", "host", "content-length", "accept-encoding", "connection",
        "sec-fetch-mode", "sec-fetch-site", "sec-fetch-dest",
    )

    private suspend fun run(
        webView: WebView,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        binary: Boolean,
    ): Map<String, Any?> {
        val id = "__szf" + System.nanoTime()
        val sendHeaders = JsonObject(headers.filterKeys { it.lowercase() !in FORBIDDEN }.mapValues { JsonPrimitive(it.value) })
        val referrer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
        val options = buildString {
            append("{method:").append(method.jsQuoted())
            append(",headers:").append(sendHeaders.toString())
            append(",credentials:'include'")
            if (body != null && method != "GET" && method != "HEAD") append(",body:").append(body.jsQuoted())
            if (referrer != null && originOf(referrer) == originOf(url)) append(",referrer:").append(referrer.jsQuoted())
            append("}")
        }
        val read = if (binary) {
            "r.arrayBuffer().then(function(buf){var u=new Uint8Array(buf),s='';for(var i=0;i<u.length;i+=32768)" +
                "s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));return {b64:btoa(s)};})"
        } else {
            "r.text().then(function(t){return {text:t};})"
        }
        webView.evalString(
            """
            (function(){
              window.$id = null;
              fetch(${url.jsQuoted()}, $options).then(function(r){
                var h = {}; r.headers.forEach(function(v,k){ h[k] = v; });
                return $read.then(function(b){ window.$id = JSON.stringify({status:r.status,url:r.url,headers:h,text:b.text,b64:b.b64}); });
              }).catch(function(e){ window.$id = JSON.stringify({error:String(e && e.message || e)}); });
              return 'started';
            })()
            """.trimIndent(),
        )
        val started = System.currentTimeMillis()
        while (true) {
            val raw = webView.evalString("(function(){var v=window.$id;if(v){window.$id=null;}return v||'';})()")
            if (!raw.isNullOrEmpty()) {
                val reply = sumizuriJson.parseToJsonElement(raw).jsonObject
                reply.str("error")?.let { throw java.io.IOException("Browser request failed: $it") }
                return buildMap {
                    put("statusCode", reply.int("status") ?: 0)
                    put("url", reply.str("url") ?: url)
                    if (binary) put("bodyBase64", reply.str("b64").orEmpty()) else put("body", reply.str("text").orEmpty())
                    put("headers", reply.obj("headers")?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty())
                }
            }
            if (System.currentTimeMillis() - started > 45_000) throw java.io.IOException("The browser request timed out")
            delay(60)
        }
    }

    /** Runs a script and gives back the string it returned */
    private suspend fun WebView.evalString(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { raw ->
            val value = runCatching { sumizuriJson.parseToJsonElement(raw ?: "null").jsonPrimitive.content }.getOrNull()
                ?.takeUnless { raw == "null" }
            cont.resume(value)
        }
    }
}
