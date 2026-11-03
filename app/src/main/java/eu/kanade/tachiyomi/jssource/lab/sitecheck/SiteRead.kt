package eu.kanade.tachiyomi.jssource.lab.sitecheck

import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.PickCheck
import eu.kanade.tachiyomi.ui.sourcelab.newsource.BuilderListReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

/**
 * KMK (r115): what the Site check and Page source read out of one page. Plain Jsoup / JSON, no Android, no network.
 * The list of titles is the builder's own reading ([BuilderListReader.pageLinks]: the largest group of ≥ 5 links of
 * one path shape outside nav / header / footer / aside), the next page is [BuilderListReader.nextPage].
 */
internal object SiteRead {

    /** The title links of a page: their shape (null = no list) and the addresses */
    data class Cards(val shape: String?, val hrefs: List<String>) {
        val count: Int get() = hrefs.size
    }

    fun cards(doc: Document, url: String, shape: String? = null): Cards {
        val (found, links) = runCatching { BuilderListReader.pageLinks(doc, url, shape) }.getOrElse { null to emptyList() }
        return Cards(found, links.map { it.href })
    }

    fun nextPage(doc: Document, url: String): String? =
        runCatching { BuilderListReader.nextPage(doc, url, emptySet()) }.getOrNull()

    /** The way the next-page address differs from the page's own: "?page=2", "/page/2/", or the path */
    fun shortAddress(url: String, base: String? = null): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val b = base?.toHttpUrlOrNull()
        val path = u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
        if (b != null && b.host == u.host && b.encodedPath == u.encodedPath && u.encodedQuery != null) return "?" + u.encodedQuery
        return if (b != null && b.host == u.host) path else u.host + path
    }

    /** Embedded data a page carries for its own scripts (`__NEXT_DATA__`, Nuxt, a store's first state, JSON-LD) */
    fun embedded(doc: Document): List<String> {
        val found = ArrayList<String>()
        doc.selectFirst("script#__NEXT_DATA__")?.let { found.add("__NEXT_DATA__ (${PageFetch.size(it.data().length.toLong())})") }
        doc.select("script[type=application/json][id]").forEach { s ->
            val id = s.id()
            if (id != "__NEXT_DATA__" && found.size < 6 && s.data().length > 200) found.add("$id (${PageFetch.size(s.data().length.toLong())})")
        }
        val ld = doc.select("script[type=application/ld+json]")
        if (ld.isNotEmpty()) found.add("JSON-LD ×${ld.size}")
        val inline = doc.select("script:not([src])").joinToString("\n") { it.data() }
        STORES.forEach { (marker, name) -> if (marker in inline) found.add(name) }
        return found
    }

    private val STORES = listOf(
        "window.__NUXT__" to "__NUXT__ (Nuxt)",
        "__INITIAL_STATE__" to "__INITIAL_STATE__",
        "__PRELOADED_STATE__" to "__PRELOADED_STATE__",
        "__APOLLO_STATE__" to "__APOLLO_STATE__",
        "self.__next_f" to "Next.js page data (self.__next_f)",
    )

    /**
     * The largest list of objects (≥ 3) in a JSON reply: its path ("data.items"), size, the first object's keys and
     * what they look like (title / cover / link). null = not JSON, or no such list.
     */
    data class DataList(val path: String, val items: Int, val keys: List<String>, val roles: List<String>)

    fun dataList(text: String): DataList? {
        val root = runCatching { jsJson.parseToJsonElement(text.trim()) }.getOrNull() ?: return null
        var best: Pair<String, JsonArray>? = null
        fun walk(e: JsonElement, path: String, depth: Int) {
            if (depth > 6) return
            when (e) {
                is JsonArray -> {
                    val objects = e.count { it is JsonObject }
                    if (objects >= 3 && objects * 2 >= e.size && (best == null || e.size > best!!.second.size)) best = path to e
                    e.take(3).forEachIndexed { i, child -> walk(child, "$path[$i]", depth + 1) }
                }
                is JsonObject -> e.entries.take(60).forEach { (k, v) -> walk(v, if (path.isEmpty()) k else "$path.$k", depth + 1) }
                else -> Unit
            }
        }
        walk(root, "", 0)
        val (path, array) = best ?: return null
        val first = array.firstOrNull { it is JsonObject } as? JsonObject ?: return null
        val keys = first.keys.toList()
        val roles = buildList {
            if (keys.any { TITLE_KEY.matches(it) }) add("title")
            if (keys.any { COVER_KEY.containsMatchIn(it) }) add("cover")
            if (keys.any { LINK_KEY.matches(it) }) add("link")
        }
        return DataList(path.ifEmpty { "(the reply itself)" }, array.size, keys.take(10), roles)
    }

    private val TITLE_KEY = Regex("(?i)^(title|name|post_title|series_name|manga_title)$")
    private val COVER_KEY = Regex("(?i)(cover|thumb|poster|image|img|picture)")
    private val LINK_KEY = Regex("(?i)^(url|link|href|slug|permalink|path|id)$")

    /** Requests that are never the site's own data (counters, ads, tag managers) */
    private val NOT_DATA = Regex(
        "(?i)google-analytics|googletagmanager|gtag|/collect\\b|beacon|/analytics|/metrics|sentry|hotjar|clarity\\.ms|" +
            "facebook\\.|doubleclick|googlesyndication|cloudflareinsights|/cdn-cgi/|recaptcha|hcaptcha",
    )

    /** The page's requests that may be its own data: fetch / XHR first, then addresses that look like an API */
    fun dataCandidates(requests: List<PageFetch.Req>, pageUrl: String): List<PageFetch.Req> {
        val host = pageUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
        return requests
            .filter { it.type == "fetch" || it.type == "xhr" }
            .filter { !NOT_DATA.containsMatchIn(it.url) && !PickCheck.AD_HOSTS.containsMatchIn(it.url) }
            .sortedByDescending { r ->
                val h = r.url.toHttpUrlOrNull()?.host?.removePrefix("www.")
                (if (host != null && h != null && (h == host || h.endsWith(".$host") || host.endsWith(".$h"))) 2 else 0) +
                    (if ("api" in r.url || ".json" in r.url) 1 else 0)
            }
    }

    /** Elements matching [selector] in [doc]; -1 when the selector can't be read by Jsoup */
    fun count(doc: Document?, selector: String?): Int {
        if (doc == null || selector.isNullOrBlank()) return -1
        return runCatching { doc.select(selector).size }.getOrDefault(-1)
    }
}
