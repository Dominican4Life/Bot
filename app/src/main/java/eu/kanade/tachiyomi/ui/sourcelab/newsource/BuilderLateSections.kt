package eu.kanade.tachiyomi.ui.sourcelab.newsource

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

/*
 * KMK (r105, Source Lab): Suggestions sections that load late. A "You may like" section the site's script fills in
 * after the page loaded is in the WebView (the pick found its cards) but not in the page's own HTML, which is all the
 * source reads. After a Suggestions pick the builder reads the raw page (plain HTTP, the app's client): when the
 * section's cards aren't there it tries the requests the WebView made on that page (recorded by BuilderScreen's
 * shouldInterceptRequest, [record]), newest first, and keeps the first whose reply (HTML, or JSON holding HTML) has the
 * cards. Its address becomes the group's `late` template: the title address's path parts as {seg1}…, the title path
 * as {path}, a value found in one of the page's attributes as {attr:name}, or {attr:selector|name} when the page's
 * first element with that attribute isn't the one holding it (generic-list v8 fills them per title).
 * Nothing found: a warning on the card ("filled by the site's script; not in the page").
 *
 * r106: the card says what the check saw ([Outcome.note]: "Checked: 12 cards in its reply"); a section's "Load more"
 * button scanned in the WebView ([findMore]): the request one press made whose reply holds the new cards, as a template
 * with `{page}` where its page number (or offset) is ([pagedOf]), so generic-list v9 asks for the next pages itself.
 */
internal object BuilderLateSections {

    data class Outcome(val late: String, val warning: String, val note: String = "")

    private data class Seen(val url: String, val page: String, val at: Long)

    private val seen = ArrayDeque<Seen>()
    private const val KEEP = 120
    private const val TRIES = 10
    const val WARNING = "This section is filled by the site's script after the page loads; it isn't in the page the source reads"

    // No braces in these patterns (ICU)
    private val ASSET = Regex("""\.(?:png|jpe?g|gif|webp|avif|svg|ico|css|js|mjs|woff2?|ttf|otf|eot|mp4|m3u8|ts|webm|mp3|map)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    private val NOISE = Regex("""google|doubleclick|analytics|facebook|twitter|hotjar|cloudflareinsights|/cdn-cgi/|recaptcha|gtag|adsystem|adservice""", RegexOption.IGNORE_CASE)
    private val COMBINATOR = Regex("""\s+[>+~]\s+""")

    /** A request the builder's WebView made while [page] was shown (any thread: shouldInterceptRequest's) */
    fun record(url: String, page: String?) {
        val u = url.trim()
        if (!u.startsWith("http") || ASSET.containsMatchIn(u) || NOISE.containsMatchIn(u)) return
        synchronized(seen) {
            seen.addLast(Seen(u, norm(page.orEmpty()), System.currentTimeMillis()))
            while (seen.size > KEEP) seen.removeFirst()
        }
    }

    private fun norm(url: String): String = url.trim().substringBefore('#').trimEnd('/')

    /** The requests made on [pageUrl] (same host), newest first, the page itself left out */
    fun requestsOn(pageUrl: String): List<String> {
        val page = norm(pageUrl)
        val host = page.toHttpUrlOrNull()?.host ?: return emptyList()
        val list = synchronized(seen) { seen.toList() }
        return list.asReversed()
            .filter { it.page == page && norm(it.url) != page && it.url.toHttpUrlOrNull()?.host?.endsWith(host.removePrefix("www.")) == true }
            .map { it.url }
            .distinct()
    }

    /**
     * Checks [item] (the section's card selector) on [pageUrl]'s raw HTML, then on the page's requests. [samples]: card
     * titles seen in the WebView (a reply holding one of them counts too).
     */
    suspend fun check(item: String, samples: List<String>, pageUrl: String): Outcome? {
        val page = fetch(pageUrl) ?: return null
        val doc = Jsoup.parse(page, pageUrl)
        if (count(doc, item) > 0) return null
        AppLog.add('I', "lab", "Builder: suggestions $item: 0 in the raw page (the WebView has them): looking for the request that fills it")
        for (url in requestsOn(pageUrl).take(TRIES)) {
            val body = try {
                fetch(url, xhr = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            val reply = replyDoc(body, url)
            val hit = count(reply, item) > 0 || shorter(reply, item) > 0 ||
                samples.any { s -> s.length >= 4 && reply.text().contains(s.take(30)) }
            AppLog.add('D', "lab", "Builder: late section try $url → ${if (hit) "has the cards" else "no"}")
            if (hit) {
                val late = templateOf(url, pageUrl, doc)
                AppLog.add('I', "lab", "Builder: suggestions $item come from $url → $late")
                val n = count(reply, item).takeIf { it > 0 } ?: shorter(reply, item)
                val seen = samples.count { s -> s.length >= 4 && reply.text().contains(s.take(30)) }
                val note = when {
                    n > 0 && samples.isNotEmpty() -> "Checked: $n cards in its reply, $seen of the ${samples.size} titles you see"
                    n > 0 -> "Checked: $n cards in its reply"
                    else -> "Checked: its reply holds the titles you see"
                }
                return Outcome(late, "", note)
            }
        }
        AppLog.add('W', "lab", "Builder: suggestions $item: no request of the page has the cards")
        return Outcome("", WARNING)
    }

    private fun count(doc: Document, css: String): Int = runCatching { doc.select(css).size }.getOrDefault(0)

    /** r106: what "Scan it now" found: the request template with {page}, the first press's page number and the step */
    data class More(val request: String, val from: Int, val step: Int, val shown: String)

    /**
     * r106: after one press of a section's Load more in the WebView: the request whose reply holds the new cards
     * ([samples]: titles of the cards it added; else the [item] selector), among [requests] (the page's own record of
     * the press, newest last) and then the page's recorded requests; as a template ([templateOf]) with {page}
     * ([pagedOf]). Null when no request has them or none has a page number (the hidden browser presses then)
     */
    suspend fun findMore(item: String, samples: List<String>, pageUrl: String, requests: List<String>, before: Int): More? {
        val pageDoc = fetch(pageUrl)?.let { Jsoup.parse(it, pageUrl) }
        val host = pageUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
        val tries = (requests.asReversed() + requestsOn(pageUrl))
            .map { it.trim() }
            .filter { u ->
                u.startsWith("http") && !ASSET.containsMatchIn(u) && !NOISE.containsMatchIn(u) && norm(u) != norm(pageUrl) &&
                    (host == null || u.toHttpUrlOrNull()?.host?.endsWith(host) == true)
            }
            .distinct()
            .take(TRIES)
        for (url in tries) {
            val body = try {
                fetch(url, xhr = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            val reply = replyDoc(body, url)
            val text = reply.text()
            val hit = samples.filter { it.length >= 4 }.let { list -> list.isNotEmpty() && list.any { text.contains(it.take(30)) } } ||
                (samples.isEmpty() && (count(reply, item) > 0 || shorter(reply, item) > 0))
            AppLog.add('D', "lab", "Builder: load more try $url → ${if (hit) "has the new cards" else "no"}")
            if (!hit) continue
            val template = templateOf(url, pageUrl, pageDoc)
            val paged = pagedOf(template, url, before)
            if (paged == null) {
                AppLog.add('I', "lab", "Builder: load more comes from $url, but it has no page number: the browser presses it")
                return null
            }
            AppLog.add('I', "lab", "Builder: load more comes from $url → ${paged.first} (from ${paged.second}, step ${paged.third})")
            return More(paged.first, paged.second, paged.third, BuilderExtrasLogic.relative(url, pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }.orEmpty()))
        }
        AppLog.add('W', "lab", "Builder: load more: no request of the press holds the new cards (${tries.size} tried)")
        return null
    }

    // r106 fix: only these names count (an id or a type that happens to be 2 isn't a page)
    private val PAGE_NAMES = setOf("page", "p", "pg")
    private val OFFSET_NAMES = setOf("offset", "start", "from", "skip")

    /** r106 fix: the page a scan's single press asks for (its path number must be this one) */
    private const val SCANNED_PAGE = 2

    /**
     * r106: [template] (a load-more request as a template) with `{page}` where [requestUrl]'s page number is: a page
     * parameter (page=2 → {page}, from 2, step 1), an offset (offset=12 → from 12, step 12; [cards] the section's
     * cards before the press when it is 0), or a path part after a page word / at the end. r106 fix: stricter, only
     * the parameters page / p / pg / offset / start / from / skip, or a path number equal to the page the scan saw
     * ([SCANNED_PAGE], step 1); anything else is null (the hidden browser presses the button instead)
     */
    fun pagedOf(template: String, requestUrl: String, cards: Int): Triple<String, Int, Int>? {
        val req = requestUrl.toHttpUrlOrNull() ?: return null
        val q = template.indexOf('?')
        val path = if (q < 0) template else template.substring(0, q)
        val query = if (q < 0) "" else template.substring(q + 1)
        val pairs = query.split('&').filter { it.isNotEmpty() }.toMutableList()
        fun setPage(name: String): Boolean {
            val at = pairs.indexOfFirst { it.substringBefore('=') == name }
            if (at < 0) return false
            pairs[at] = name + "={page}"
            return true
        }
        fun rebuilt(): String = path + if (pairs.isEmpty()) "" else "?" + pairs.joinToString("&")
        for (name in req.queryParameterNames) {
            val v = req.queryParameter(name)?.toIntOrNull() ?: continue
            if (name.lowercase() in PAGE_NAMES && v >= 1 && setPage(name)) return Triple(rebuilt(), v, 1)
        }
        for (name in req.queryParameterNames) {
            val v = req.queryParameter(name)?.toIntOrNull() ?: continue
            if (name.lowercase() in OFFSET_NAMES && setPage(name)) {
                val step = if (v > 0) v else cards.coerceAtLeast(1)
                return Triple(rebuilt(), if (v > 0) v else step, step)
            }
        }
        val segs = path.split('/')
        for (i in segs.indices.reversed()) {
            val v = segs[i].toIntOrNull() ?: continue
            val word = segs.getOrNull(i - 1)?.lowercase()
            if (v == SCANNED_PAGE && (word in PAGE_NAMES || i == segs.lastIndex)) {
                val out = segs.toMutableList().also { it[i] = "{page}" }.joinToString("/")
                return Triple(out + if (pairs.isEmpty()) "" else "?" + pairs.joinToString("&"), v, 1)
            }
        }
        return null
    }

    /** The template's shorterSelect: a reply holds the cards without the page around them */
    fun shorter(doc: Document, css: String): Int {
        val parts = css.split(COMBINATOR)
        for (k in 1 until parts.size) {
            val n = count(doc, parts.drop(k).joinToString(" > "))
            if (n > 0) return n
        }
        return 0
    }

    /** A reply as a document: HTML, or JSON whose text values hold the HTML (joined), as the template's lateDoc */
    fun replyDoc(body: String, url: String): Document {
        val t = body.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            val parts = ArrayList<String>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonPrimitive -> if (e.isString && '<' in e.content) parts.add(e.content)
                    is JsonArray -> e.forEach { walk(it) }
                    is JsonObject -> e.values.forEach { walk(it) }
                }
            }
            runCatching { walk(Json.parseToJsonElement(t)) }
            if (parts.isNotEmpty()) return Jsoup.parse(parts.joinToString("\n"), url)
        }
        return Jsoup.parse(t, url)
    }

    /**
     * [requestUrl] as a template for any title: a part equal to one of the title address's path parts → {segN}; the
     * title's path → {path}; a value found in an attribute of the title page ([doc]) → {attr:name} or
     * {attr:selector|name} ([attrRef]). Relative to the
     * site when on the title's host.
     */
    fun templateOf(requestUrl: String, titleUrl: String, doc: Document?): String {
        val title = titleUrl.toHttpUrlOrNull()
        val req = requestUrl.toHttpUrlOrNull() ?: return requestUrl
        val segs = title?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        val path = title?.encodedPath?.trimEnd('/').orEmpty()
        fun token(value: String): String? {
            if (value.length < 2) return null
            val at = segs.indexOf(value)
            if (at >= 0 && value.any { it.isLetterOrDigit() } && !(value.length <= 3 && segs.count { it == value } > 1)) return "{seg${at + 1}}"
            if (path.length > 1 && value.trimEnd('/') == path) return "{path}"
            if (doc != null && value.length >= 3 && value.any(Char::isDigit)) {
                val held = attributeHolding(doc, value)
                if (held != null) return "{attr:${attrRef(doc, held.first, held.second)}}"
            }
            return null
        }
        val outPath = req.encodedPathSegments.joinToString("/", prefix = "/") { seg ->
            val decoded = runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
            // the first two parts (often /en/ajax) are kept unless they are the title's own
            token(decoded) ?: seg
        }
        val query = req.queryParameterNames.joinToString("&") { name ->
            val v = req.queryParameter(name).orEmpty()
            "$name=" + (token(v) ?: java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val origin = if (title != null && title.host == req.host) "" else "${req.scheme}://${req.host}"
        return origin + outPath + (if (query.isNotEmpty()) "?$query" else "")
    }

    /**
     * The element of [doc] and the name of its attribute whose whole value is [value] (data-id="777"), favouring
     * data-* ones
     */
    private fun attributeHolding(doc: Document, value: String): Pair<Element, String>? {
        var fallback: Pair<Element, String>? = null
        for (el in doc.allElements) {
            for (a in el.attributes()) {
                if (a.value.trim() != value) continue
                val key = a.key.lowercase()
                if (key in SKIP_ATTRS) continue
                if (key.startsWith("data-")) return el to a.key
                if (fallback == null) fallback = el to a.key
            }
        }
        return fallback
    }

    /**
     * r105 fix: how the template finds [name] of [el]: "name" when [el] is the page's first element having it (the
     * template reads `[name]`'s first), else "selector|name" with a selector whose first match is [el] (its tag with the
     * attribute, its id, or its css path). No braces or bars in the selector (the template's {attr:…} token)
     */
    fun attrRef(doc: Document, el: Element, name: String): String {
        fun first(css: String): Element? = runCatching { doc.selectFirst(css) }.getOrNull()
        if (first("[$name]") === el) return name
        val candidates = ArrayList<String>()
        candidates += "${el.tagName()}[$name]"
        el.id().takeIf { it.isNotBlank() && ID_SAFE.matches(it) }?.let { candidates += "#$it" }
        el.classNames().firstOrNull { ID_SAFE.matches(it) }?.let { candidates += "${el.tagName()}.$it[$name]" }
        candidates += runCatching { el.cssSelector() }.getOrDefault("")
        val css = candidates.firstOrNull { c -> c.isNotBlank() && '{' !in c && '}' !in c && '|' !in c && first(c) === el }
        return if (css == null) name else "$css|$name"
    }

    private val ID_SAFE = Regex("""[A-Za-z][\w-]*""")

    private val SKIP_ATTRS = setOf("class", "style", "href", "src", "content", "alt", "title", "value", "srcset", "rel", "type")

    private suspend fun fetch(url: String, xhr: Boolean = false): String? = withContext(Dispatchers.IO) {
        val network = Injekt.get<NetworkHelper>()
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: url
        val builder = Request.Builder().url(url).header("Referer", origin)
        if (xhr) builder.header("X-Requested-With", "XMLHttpRequest")
        val agent = runCatching { network.defaultUserAgentProvider() }.getOrNull().orEmpty()
        if (agent.isNotBlank()) builder.header("User-Agent", agent)
        network.client.newCall(builder.build()).await().use { response ->
            if (response.code >= 400) null else response.body.string()
        }
    }
}
