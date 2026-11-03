package eu.kanade.tachiyomi.jssource.lab.sitecheck

import android.content.Context
import eu.kanade.tachiyomi.jssource.JsBrowserFetch
import eu.kanade.tachiyomi.jssource.JsRender
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r115): the two ways the Lab looks at one page, shared by the Site check and Page source:
 * - [raw]: the app's own client (its cookies, user agent and bot-check handling), what a source receives;
 * - [rendered]: the builder's hidden browser ([JsRender], the same code as `host.render`): the page after its scripts,
 *   with the requests it made (`fetch` / XHR ones marked from the page's performance entries).
 * Nothing here is new machinery: [raw] is the request BuilderListReader sends, [rendered] is host.render.
 */
internal object PageFetch {

    /** What the app's client got for one address. [error] is set when no answer came (then [code] is 0) */
    data class Raw(
        val url: String,
        val code: Int,
        val contentType: String,
        val body: String,
        val ms: Long,
        val server: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null && code in 200..399
        val botCheck: Boolean get() = error == null && JsBrowserFetch.isChallenge(code, server, body)
        val json: Boolean get() = "json" in contentType.lowercase() || body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
    }

    /** One request the hidden browser's page made. [type]: fetch, xhr, script, css, img, html, other */
    data class Req(val url: String, val type: String, val bytes: Long) {
        val data: Boolean get() = type == "fetch" || type == "xhr"
    }

    /** The page as the hidden browser shows it; [error] set when it couldn't be opened */
    data class Rendered(
        val url: String,
        val html: String,
        val requests: List<Req>,
        val ms: Long,
        val timedOut: Boolean,
        val error: String?,
    )

    private const val MAX_CHARS = 4_000_000

    suspend fun raw(url: String, referer: String? = null): Raw = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val network = Injekt.get<NetworkHelper>()
            val builder = Request.Builder().url(url)
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            val agent = runCatching { network.defaultUserAgentProvider() }.getOrNull().orEmpty()
            if (agent.isNotBlank()) builder.header("User-Agent", agent)
            network.client.newCall(builder.build()).await().use { response ->
                val text = response.body.string()
                Raw(
                    url = response.request.url.toString(),
                    code = response.code,
                    contentType = response.header("Content-Type").orEmpty(),
                    body = if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text,
                    ms = System.currentTimeMillis() - started,
                    server = response.header("Server"),
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Raw(url, 0, "", "", System.currentTimeMillis() - started, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** The page's resource entries with their initiator: [url, initiatorType, bytes] each */
    private const val ENTRIES_SCRIPT =
        "JSON.stringify(performance.getEntriesByType('resource').slice(0, 600).map(function (e) {" +
            " return [e.name, e.initiatorType || '', Math.round(e.transferSize || e.encodedBodySize || 0)]; }))"

    suspend fun rendered(context: Context, url: String, timeoutMs: Long = 20_000L): Rendered {
        val started = System.currentTimeMillis()
        return try {
            val payload = buildJsonObject {
                put("url", url)
                put("capture", "^https?:")
                put("script", ENTRIES_SCRIPT)
                put("timeout", timeoutMs)
            }
            val result = JsRender.render(context, payload)
            val html = result["html"] as? String ?: ""
            val finalUrl = result["url"] as? String ?: url
            val seen = LinkedHashMap<String, Req>()
            // Typed entries first (they say fetch / xmlhttprequest), then whatever else the WebView asked for
            val entries = (result["result"] as? String)?.takeIf { !it.startsWith("ERROR") }
            if (entries != null) {
                runCatching {
                    (jsJson.parseToJsonElement(entries) as? JsonArray)?.forEach { row ->
                        val cells = row as? JsonArray ?: return@forEach
                        val name = (cells.getOrNull(0) as? JsonPrimitive)?.content ?: return@forEach
                        val initiator = (cells.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
                        val bytes = (cells.getOrNull(2) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
                        if (name !in seen) seen[name] = Req(name, typeOf(name, initiator), bytes)
                    }
                }.onFailure { AppLog.add('W', "lab", "Page fetch: the page's request list was not read: ${it.message}") }
            }
            (result["resources"] as? List<*>)?.forEach { any ->
                val name = any as? String ?: return@forEach
                if (name !in seen) seen[name] = Req(name, typeOf(name, ""), 0L)
            }
            val ms = System.currentTimeMillis() - started
            AppLog.add('D', "lab", "Page fetch: rendered $url in $ms ms (${html.length / 1024} KB, ${seen.size} requests)")
            Rendered(finalUrl, html, seen.values.toList(), ms, result["timedOut"] == true, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.add('W', "lab", "Page fetch: the hidden browser couldn't open $url: ${e.message}")
            Rendered(url, "", emptyList(), System.currentTimeMillis() - started, false, e.message ?: e.javaClass.simpleName)
        }
    }

    private val SCRIPT_EXT = Regex("""\.m?js(\?|$)""", RegexOption.IGNORE_CASE)
    private val CSS_EXT = Regex("""\.css(\?|$)""", RegexOption.IGNORE_CASE)
    private val IMG_EXT = Regex("""\.(png|jpe?g|gif|webp|avif|svg|ico)(\?|$)""", RegexOption.IGNORE_CASE)
    private val FONT_EXT = Regex("""\.(woff2?|ttf|otf)(\?|$)""", RegexOption.IGNORE_CASE)
    private val JSON_EXT = Regex("""\.json(\?|$)|/api/|/graphql|/wp-json/""", RegexOption.IGNORE_CASE)

    fun typeOf(url: String, initiator: String): String = when {
        initiator == "fetch" -> "fetch"
        initiator == "xmlhttprequest" -> "xhr"
        initiator == "script" || SCRIPT_EXT.containsMatchIn(url) -> "script"
        initiator == "css" || CSS_EXT.containsMatchIn(url) -> "css"
        initiator == "img" || IMG_EXT.containsMatchIn(url) || FONT_EXT.containsMatchIn(url) -> "img"
        JSON_EXT.containsMatchIn(url) -> "xhr"
        initiator == "iframe" || initiator == "navigation" -> "html"
        else -> "other"
    }

    /** "69 KB" / "812 B" */
    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
}
