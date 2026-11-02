package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * KMK (r101, Source Lab): "Read the whole list" of an index page (all genres, all actresses…), page after page, with
 * a random pause between pages so the site doesn't see a bot-like pace. Plain HTTP through the app's client (its
 * cookies and Cloudflare handling), the app's user agent and the site as Referer; Jsoup finds the list: the largest
 * group of links (≥ 5) sharing one path shape, outside nav / header / footer / aside. What it reads becomes a filter
 * ([BuilderExtrasModel.onListRead]).
 *
 * KMK (r107): a directory is **scanned** ([scan]): each entry's card and its parts read apart (the place number, the
 * name element's own text only, videos / views / likes counts, the picture, a Follow button that is never kept), the
 * site's own sort options and other menus on that page, the page address with `{page}` and the entry count it shows.
 * The owner's r106 report had whole cards as names ("FC FC2 33,977 videos + Follow Following") and a series directory
 * saved as a Sort (its "views" counts read like sort words). [readDirectory] reads a page range with the scan's
 * selectors; generic-list v10's getDirectory reads the same selectors, so what the card shows is what Browse gets.
 */
internal object BuilderListReader {

    @Immutable
    data class Item(val text: String, val href: String)

    /** [items] deduped by href in page order; [reason] says why it stopped; [shape] the list's link shape */
    @Immutable
    data class Result(val items: List<Item>, val pages: Int, val reason: String, val shape: String?)

    private const val MIN_GROUP = 5
    private val OUTSIDE = setOf("nav", "header", "footer", "aside")

    // No braces in these patterns (ICU)
    private val NEXT_TEXT = Regex("""^(?:next(?:\s+page)?|next\s*[›»>→]+|[›»>→])$""", RegexOption.IGNORE_CASE)
    private val PAGER_TEXT = Regex("""^(?:\d\d?\d?\d?|[‹«<←›»>→]+|prev(?:ious)?|next|first|last|…|\.\.\.)$""", RegexOption.IGNORE_CASE)
    private val PAGE_QUERY = Regex("""[?&](?:page|p|pg)=(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_PATH = Regex("""/page/(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Reads [url] and its next pages, up to [maxPages], pausing a random [pauseMinMs]..[pauseMaxMs] between pages.
     * [onProgress] (page, entries so far) runs on the caller's thread; [shouldStop] ends it after the current page,
     * keeping what was read. Cancelling the coroutine stops at once.
     */
    suspend fun read(
        url: String,
        maxPages: Int,
        pauseMinMs: Long,
        pauseMaxMs: Long,
        onProgress: (page: Int, entries: Int) -> Unit,
        shouldStop: () -> Boolean = { false },
    ): Result {
        val items = LinkedHashMap<String, Item>()
        val visited = HashSet<String>()
        val limit = maxPages.coerceIn(1, 500)
        val low = minOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val high = maxOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val referer = Detector.normalize(url) + "/"
        var shape: String? = null
        var pageUrl = url.trim()
        var pages = 0
        var reason = ""
        AppLog.add('I', "lab", "extras read list from $pageUrl: up to $limit pages, pause ${low}–$high ms")
        while (true) {
            if (shouldStop()) {
                reason = "stopped by you after page $pages"
                break
            }
            var paused = 0L
            if (pages > 0) {
                paused = Random.nextLong(low, high + 1)
                pauseFor(paused, shouldStop)
                if (shouldStop()) {
                    reason = "stopped by you after page $pages"
                    break
                }
            }
            visited += clean(pageUrl)
            val fetched = try {
                fetch(pageUrl, referer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = "page ${pages + 1} could not be read (${e.message ?: e.javaClass.simpleName})"
                break
            }
            if (fetched.first >= 400) {
                reason = "the site answered ${fetched.first} for page ${pages + 1}"
                break
            }
            val current = pageUrl
            val doc = withContext(Dispatchers.Default) { Jsoup.parse(fetched.second, current) }
            pages++
            val (found, links) = pageLinks(doc, current, shape)
            if (shape == null) shape = found
            if (links.isEmpty()) {
                reason = if (pages == 1) {
                    "page 1 has no group of $MIN_GROUP or more links like each other outside the menus"
                } else {
                    "page $pages has none of the list's links"
                }
                break
            }
            var added = 0
            links.forEach { item ->
                val old = items[item.href]
                if (old == null) {
                    items[item.href] = item
                    added++
                } else if (old.text.isBlank() && item.text.isNotBlank()) {
                    items[item.href] = item
                }
            }
            AppLog.add('D', "lab", "extras read list page $pages: ${links.size} entries (paused $paused ms)")
            onProgress(pages, items.size)
            if (added == 0 && pages > 1) {
                reason = "page $pages repeated entries already read"
                break
            }
            if (pages >= limit) {
                reason = "read $limit pages, the limit you set"
                break
            }
            val next = nextPage(doc, current, visited)
            if (next == null) {
                reason = "no next page after page $pages"
                break
            }
            pageUrl = next
        }
        AppLog.add('I', "lab", "extras read list: ${items.size} entries from $pages pages ($reason)")
        return Result(items.values.toList(), pages, reason, shape)
    }

    /** Waits [ms] in small steps, ending early when [shouldStop] says so */
    private suspend fun pauseFor(ms: Long, shouldStop: () -> Boolean) {
        var left = ms
        while (left > 0 && !shouldStop()) {
            val step = minOf(left, 250L)
            delay(step)
            left -= step
        }
    }

    private suspend fun fetch(url: String, referer: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val network = Injekt.get<NetworkHelper>()
        val builder = Request.Builder().url(url).header("Referer", referer)
        val agent = runCatching { network.defaultUserAgentProvider() }.getOrNull().orEmpty()
        if (agent.isNotBlank()) builder.header("User-Agent", agent)
        network.client.newCall(builder.build()).await().use { response -> response.code to response.body.string() }
    }

    private fun clean(url: String): String =
        url.toHttpUrlOrNull()?.let { it.newBuilder().fragment(null).build().toString() } ?: url.substringBefore('#')

    /**
     * The shape that groups a list's links: host + the path without its last segment ("/en/genres" for
     * "/en/genres/action"); links with a query keep their whole path and add the query's names
     */
    fun shapeOf(url: HttpUrl): String {
        val segs = url.encodedPathSegments.filter { it.isNotEmpty() }
        val names = url.queryParameterNames.sorted()
        return if (names.isEmpty()) {
            url.host + "/" + segs.dropLast(1).joinToString("/") + "#" + segs.size
        } else {
            url.host + "/" + segs.joinToString("/") + "?" + names.joinToString(",")
        }
    }

    private fun labelOf(a: Element): String {
        val text = a.text().trim()
        if (text.isNotEmpty()) return text
        val title = a.attr("title").trim()
        if (title.isNotEmpty()) return title
        return a.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
    }

    /**
     * The list's links on one page: those of shape [wanted] (the first page's), or else the largest shape group of
     * at least [MIN_GROUP] distinct links. Links inside nav / header / footer / aside, to other hosts, to this page,
     * and page-number links ("2", "Next") are left out. Returns the shape and the links (deduped by href).
     */
    fun pageLinks(doc: Document, pageUrl: String, wanted: String?): Pair<String?, List<Item>> {
        val page = pageUrl.toHttpUrlOrNull() ?: return null to emptyList()
        val here = page.newBuilder().fragment(null).build().toString()
        val groups = LinkedHashMap<String, LinkedHashMap<String, Item>>()
        // hrefs whose label is a link's own text: a card's title link wins over its picture link's alt
        val textual = HashSet<String>()
        for (a in doc.select("a[href]")) {
            if (a.parents().any { it.normalName() in OUTSIDE }) continue
            val abs = a.absUrl("href")
            if (abs.isEmpty()) continue
            val url = abs.toHttpUrlOrNull() ?: continue
            if (url.host != page.host) continue
            val href = url.newBuilder().fragment(null).build().toString()
            if (href == here) continue
            val own = a.text().trim()
            val text = own.ifEmpty { labelOf(a) }
            if (PAGER_TEXT.matches(text)) continue
            val group = groups.getOrPut(shapeOf(url)) { LinkedHashMap() }
            val old = group[href]
            val better = own.isNotEmpty() && href !in textual
            if (old == null || better || (old.text.isBlank() && text.isNotBlank())) group[href] = Item(text, href)
            if (own.isNotEmpty()) textual += href
        }
        val key = if (wanted != null) {
            wanted.takeIf { it in groups }
        } else {
            groups.entries.filter { it.value.size >= MIN_GROUP }.maxByOrNull { it.value.size }?.key
        }
        return key to (key?.let { groups[it]?.values?.toList() }.orEmpty())
    }

    /** The page number of an address (`page=N`, `p=N`, `/page/N`), 1 when it has none */
    fun pageNumber(url: String): Int =
        PAGE_QUERY.find(url)?.groups?.get(1)?.value?.toIntOrNull()
            ?: PAGE_PATH.find(url)?.groups?.get(1)?.value?.toIntOrNull()
            ?: 1

    /**
     * The next page: `rel=next`, else a link whose text (or aria-label / title) is Next, ›, », else a link whose
     * address has the page number one above this page's. Same host, not read yet.
     */
    fun nextPage(doc: Document, pageUrl: String, visited: Set<String>): String? {
        val page = pageUrl.toHttpUrlOrNull() ?: return null
        val here = page.newBuilder().fragment(null).build().toString()
        fun usable(abs: String): String? {
            val u = abs.toHttpUrlOrNull() ?: return null
            if (u.host != page.host) return null
            val s = u.newBuilder().fragment(null).build().toString()
            return if (s == here || s in visited) null else s
        }
        for (el in doc.select("a[rel~=next][href], link[rel~=next][href]")) {
            val s = usable(el.absUrl("href"))
            if (s != null) return s
        }
        val anchors = doc.select("a[href]")
        for (a in anchors) {
            val label = a.text().trim().ifEmpty { a.attr("aria-label").trim() }.ifEmpty { a.attr("title").trim() }
            if (!NEXT_TEXT.matches(label)) continue
            val s = usable(a.absUrl("href"))
            if (s != null) return s
        }
        val wanted = pageNumber(here) + 1
        for (a in anchors) {
            val abs = a.absUrl("href")
            val n = PAGE_QUERY.find(abs)?.groups?.get(1)?.value?.toIntOrNull()
                ?: PAGE_PATH.find(abs)?.groups?.get(1)?.value?.toIntOrNull()
            if (n != wanted) continue
            val s = usable(abs)
            if (s != null) return s
        }
        return null
    }

    /**
     * r106: a directory's first page as its card shows it: the names of its list ([entries]), the highest page number its
     * pager links to ([pages], 1 = none) and whether a next page is found ("Name list with pages found (1, 2, 3 … 22)")
     */
    @Immutable
    data class Probe(val entries: Int, val pages: Int, val hasNext: Boolean, val note: String)

    suspend fun probe(url: String): Probe {
        val referer = Detector.normalize(url) + "/"
        val fetched = fetch(url, referer)
        if (fetched.first >= 400) return Probe(0, 1, false, "the site answered ${fetched.first}")
        val doc = withContext(Dispatchers.Default) { Jsoup.parse(fetched.second, url) }
        val (_, links) = pageLinks(doc, url, null)
        val next = nextPage(doc, url, emptySet())
        val highest = highestPage(doc, url)
        val pages = if (next != null) maxOf(highest, 2) else highest
        return Probe(links.size, pages, next != null, if (links.isEmpty()) "no list of names found on its first page" else "")
    }

    /** The highest page number the pager of [doc] links to (a link reading N whose address has page N), 1 = none */
    private fun highestPage(doc: Document, url: String): Int {
        val host = url.toHttpUrlOrNull()?.host
        var highest = 1
        for (a in doc.select("a[href]")) {
            val n = a.text().trim().replace(",", "").toIntOrNull() ?: continue
            if (n < 2 || n > 100_000) continue
            val abs = a.absUrl("href")
            val u = abs.toHttpUrlOrNull() ?: continue
            val last = u.pathSegments.lastOrNull { it.isNotEmpty() }
            if (u.host != host || (pageNumber(abs) != n && last != n.toString())) continue
            // KMK (r108) fix: numbered links of this list only (see the Last link below)
            if (listPathOf(u.encodedPath) != listPathOf(url.toHttpUrlOrNull()?.encodedPath ?: "")) continue
            if (n > highest) highest = n
        }
        // KMK (r107) fix: a pager showing only 1 2 3 … and "Last" / »: the last page's number in that link's address
        // "/list/75": this page's path (without its own page number) with the number added
        val listPath = url.toHttpUrlOrNull()?.let { h ->
            val p = h.encodedPath.trimEnd('/')
            if (p.substringAfterLast('/').toIntOrNull() != null) p.substringBeforeLast('/') else p
        }
        for (a in doc.select("a[href]")) {
            val label = a.text().trim().ifEmpty { a.attr("aria-label").trim() }.ifEmpty { a.attr("title").trim() }
            if (!LAST_TEXT.matches(label)) continue
            val abs = a.absUrl("href")
            val u = abs.toHttpUrlOrNull() ?: continue
            if (u.host != host) continue
            // KMK (r108) fix: only this directory's own pager: a "»" / Last link elsewhere on the page (the site's
            // header pointing at its main list) gave every directory that list's page count (2,257 for all)
            if (listPathOf(u.encodedPath) != listPath?.let(::listPathOf)) continue
            val n = PAGE_QUERY.find(abs)?.groups?.get(1)?.value?.toIntOrNull()
                ?: PAGE_PATH.find(abs)?.groups?.get(1)?.value?.toIntOrNull()
                ?: u.encodedPath.trimEnd('/').takeIf { it.substringBeforeLast('/') == listPath }
                    ?.substringAfterLast('/')?.toIntOrNull()
                ?: continue
            if (n in 2..100_000 && n > highest) highest = n
        }
        return highest
    }

    /** r108: a list path without its page part ("/x/page/3" → "/x", "/x/3" → "/x", "/x/" → "/x") */
    private fun listPathOf(path: String): String {
        val p = PAGE_PATH.replace(path, "").trimEnd('/')
        return if (p.substringAfterLast('/').toIntOrNull() != null) p.substringBeforeLast('/') else p
    }

    // KMK (r107) fix: a pager's link to its last page
    private val LAST_TEXT = Regex("""^(?:last(?:\s+page)?|last\s*[»>]+|»»|>>|»\|?|⇥|⏭)$""", RegexOption.IGNORE_CASE)

    // ---------- r107: a directory scanned part by part (scan) and read over a page range (readDirectory) ----------

    /**
     * r107: one part of a directory entry: [kind] number|title|videos|views|likes|picture|follow, found inside each
     * entry's card by [selector] ("" = the card itself); [example] as the scan card shows it; [attr] the attribute a
     * title (alt / title) or a picture (src, data-src…) is read from; [own] = the name is the element's own text
     * (a count sits inside it); [bare] = a count without its unit word ("20,837")
     */
    @Immutable
    data class DirPart(
        val kind: String,
        val selector: String,
        val example: String,
        val attr: String = "",
        val own: Boolean = false,
        val bare: Boolean = false,
    )

    /** r107: one entry of a directory: its clean name, its link and the parts read apart ("" = none) */
    @Immutable
    data class DirEntry(
        val label: String,
        val href: String,
        val number: String = "",
        val videos: String = "",
        val views: String = "",
        val likes: String = "",
        val picture: String = "",
    )

    /**
     * r107: what [scan] found on a directory's first page: the entries' card selector ([item]), the link inside it
     * ([link], "" = the first link of the list's shape), the [parts], page 1's [entries], the pages ([pages], [hasNext],
     * [pageTemplate] = page N's address with `{page}`, null = not found), the entry count the site shows ([total], 0 =
     * none), the site's sort options ([sorts], sent as [sortParam]=value; [orderParam] with [ascValue] / [descValue]
     * when the site offers ascending too) and its other menus ([menus]); [note] = why nothing was found
     */
    @Immutable
    data class DirScan(
        val url: String,
        val shape: String?,
        val item: String,
        val link: String,
        val parts: List<DirPart>,
        val entries: List<DirEntry>,
        val pages: Int,
        val hasNext: Boolean,
        val pageTemplate: String?,
        val total: Int,
        val sorts: List<ExtrasOption>,
        val sortParam: String,
        val orderParam: String,
        val ascValue: String,
        val descValue: String,
        val menus: List<CollectionMenu>,
        val note: String,
    ) {
        fun part(kind: String): DirPart? = parts.firstOrNull { it.kind == kind }

        /** Entries were found with their selectors: the scan card (else the r106 "Read all pages") */
        val ok: Boolean get() = item.isNotEmpty() && entries.isNotEmpty() && part("title") != null
    }

    /** r107: a page range read with a scan's selectors: the entries (deduped by link), the pages read, why it ended */
    @Immutable
    data class DirRead(val entries: List<DirEntry>, val pages: Int, val reason: String)

    // No braces in these patterns (ICU)
    private const val NUM = """(\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?)"""
    private val VIDEOS = Regex(
        NUM + """\s*(?:videos?|movies?|clips?|titles?|works?|items?|posts?|galleries|gallery|books?|mangas?|comics?|episodes?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val VIEWS = Regex(NUM + """\s*views?\b""", RegexOption.IGNORE_CASE)
    private val LIKES = Regex(NUM + """\s*(?:likes?\b|♥|❤)""", RegexOption.IGNORE_CASE)
    private val LIKES_BEFORE = Regex("""(?:♥|❤)\s*""" + NUM, RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^#?\s*""" + NUM + """\s*$""", RegexOption.IGNORE_CASE)
    private val ANY_NUM = Regex(NUM, RegexOption.IGNORE_CASE)
    private val PLACE = Regex("""^#?\s*(\d\d?\d?\d?)\.?$""")
    private val PLACE_START = Regex("""^\s*#?\s*(\d\d?\d?\d?)\.?(?!\d)""")
    private val FOLLOW = Regex(
        """^(?:[+＋]?\s*(?:follow(?:ing|ed)?|unfollow|subscribed?|unsubscribe)\s*)+$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNIT_ONLY = Regex(
        """^(?:videos?|views?|likes?|followers?|subscribers?|movies?|titles?|works?|items?|posts?|episodes?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val FOLLOW_TAIL = Regex("""\s*[+＋]?\s*(?:follow(?:ing|ed)?|unfollow|subscribed?|unsubscribe)\s*$""", RegexOption.IGNORE_CASE)
    private val COUNT_TAIL = Regex(
        """\s*[·•|,]?\s*\d[\d.,]*(?:\s?[KMB](?![A-Za-z]))?\+?\s*(?:videos?|movies?|clips?|titles?|works?|items?|posts?|galleries|gallery|books?|mangas?|comics?|episodes?|views?|likes?|followers?|♥|❤)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val SEPARATOR_TAIL = Regex("""\s*[·•|]\s*$""")
    private val GROUPED_TAIL = Regex("""^(.*[^\s\d.,])\s+\d\d?\d?(?:,\d\d\d)+\s*$""")
    private val LOGO_HINT = Regex("""avatar|logo|initial|letter|thumb|icon|placeholder|abbr""", RegexOption.IGNORE_CASE)
    private val NAME_HINT = Regex("""name|title|label|heading""", RegexOption.IGNORE_CASE)
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val COUNT_HINTS = mapOf(
        "videos" to Regex("""video|film|movie|play|clip""", RegexOption.IGNORE_CASE),
        "views" to Regex("""view|eye|watch""", RegexOption.IGNORE_CASE),
        "likes" to Regex("""like|heart|fav|love""", RegexOption.IGNORE_CASE),
    )
    private val CLASS_OK = Regex("""^[A-Za-z_][A-Za-z0-9_-]*$""")
    private val CLASS_STATE = setOf("active", "selected", "current", "first", "last", "odd", "even", "hover", "show", "open")
    private val PAGE_NUM_QUERY = Regex("""([?&](?:page|p|pg|paged)=)(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_NUM_PATH = Regex("""(/page/)(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_PARAMS = setOf("page", "p", "pg", "paged", "offset", "start", "from", "skip")
    private val SORT_PARAMS = setOf("sort", "order", "orderby", "order_by", "sort_by", "sortby", "sortorder", "sort_order", "s_order", "o", "by")
    private val ORDER_PARAMS = setOf("dir", "direction", "order", "sort_dir", "sortdir", "asc")
    private val ORDER_VALUES = setOf("asc", "desc", "ascending", "descending")
    private val SORT_TEXT = Regex(
        """\b(most|least|name|a\s*[-–]\s*z|z\s*[-–]\s*a|newest|oldest|latest|recent|recently|popular|popularity|trending|top|rated|rating|views?|viewed|videos?|followers?|likes?|liked|today|daily|weekly|monthly|yearly|alphabetical|random|updated|release|added)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOTAL_WORD = Regex(
        """(\d[\d,.]*)\s+(?:results?|entries|items|names|series|actress(?:es)?|actors?|makers?|studios?|labels?|genres?|tags?|categories|models?|channels?|performers?|stars?|idols?|people|artists?|authors?|groups?|parodies|characters?|collections?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOTAL_OF = Regex("""\bof\s+(\d[\d,.]*)\b""", RegexOption.IGNORE_CASE)
    private val PAGE_OF = Regex("""\bpages?\s*\d[\d,.]*\s*$""", RegexOption.IGNORE_CASE)

    // KMK (r107) fix: a place number at the start of a name's own text ("01 Slice of Life")
    private val LEAD_PLACE = Regex("""^\s*#?\s*(\d\d?\d?\d?)\.?\s+(?=\S)""")
    private val SEARCH_INPUTS = "input[type=search], input[name=q], input[name=s], input[name=keyword], input[name=search], input[name=query]"
    private const val SAMPLE_CARDS = 10

    /**
     * r107: an entry's name without what a whole card adds to it: a Follow / Following button's words and trailing
     * counts ("Slice of Life 1,234 videos · 2.1M views + Follow Following" → "Slice of Life"). Same as the template's
     * entryLabel
     */
    fun entryLabel(raw: String?): String {
        var s = raw.orEmpty().replace(SPACES, " ").trim()
        while (s.isNotEmpty()) {
            val before = s
            s = s.replace(FOLLOW_TAIL, "").trim()
            s = s.replace(COUNT_TAIL, "").trim()
            s = s.replace(SEPARATOR_TAIL, "").trim()
            s = GROUPED_TAIL.find(s)?.groupValues?.get(1)?.trim() ?: s
            if (s == before) break
        }
        return s
    }

    private val SPACES = Regex("""\s+""")

    /**
     * r107: "FC FC2" → "FC2", "MO Moody's" → "Moody's": a logo's letters before the name they start. KMK (r107) fix: the
     * first word is any 1–3 characters the rest starts with, whatever their case ("S1 S1 Number One Style", "1P 1Pondo",
     * "K. K.m. Produce", "S- S-Cute", "I' I'm An Amateur", "U& U&K"); the template's dropInitials
     */
    fun dropInitials(t: String): String {
        val sp = t.indexOf(' ')
        if (sp <= 0) return t
        val first = t.substring(0, sp)
        val rest = t.substring(sp + 1).trim()
        return if (first.length <= 3 && rest.length >= first.length && rest.startsWith(first, ignoreCase = true)) rest else t
    }

    /** r107: a count as the site shows it, its unit word dropped ("4,476 videos" → "4,476"); the template's countIn */
    fun countOf(kind: String, text: String, bare: Boolean): String {
        val m = when (kind) {
            "number" -> PLACE_START.find(text)
            "videos" -> VIDEOS.find(text)
            "views" -> VIEWS.find(text)
            "likes" -> LIKES.find(text) ?: LIKES_BEFORE.find(text)
            else -> null
        } ?: if (bare && kind != "number") ANY_NUM.find(text) else null
        return m?.groupValues?.getOrNull(1).orEmpty().replace(SPACES, "").trimEnd('.', ',')
    }

    /** r107: the value an entry's link gives a filter [path] (its part at `{value}`, or the `{value}` parameter) */
    fun valueOfHref(path: String, href: String): String {
        val url = href.toHttpUrlOrNull() ?: return ""
        val q = path.indexOf('?')
        val segs = (if (q < 0) path else path.substring(0, q)).split('/').filter { it.isNotEmpty() }
        val at = segs.indexOf("{value}")
        if (at >= 0) {
            val hs = url.encodedPathSegments.filter { it.isNotEmpty() }
            for (i in 0 until at) {
                if ('{' !in segs[i] && !hs.getOrNull(i).orEmpty().equals(segs[i], ignoreCase = true)) return ""
            }
            return hs.getOrNull(at).orEmpty()
        }
        val name = Regex("""[?&]([^=&?#]+)=\{value\}""").find(path)?.groupValues?.get(1) ?: return ""
        return url.queryParameter(name).orEmpty()
    }

    /**
     * r107: a directory's first page, part by part: the list's links (the largest group of one shape), each one's
     * card (the largest element holding only that entry's links), the cards' selector, each part's selector checked
     * on up to [SAMPLE_CARDS] cards, page 1's entries read with them, the pager, the total, the sort options and menus
     */
    suspend fun scan(url: String): DirScan {
        val referer = Detector.normalize(url) + "/"
        val fetched = fetch(url, referer)
        if (fetched.first >= 400) return emptyScan(url, "the site answered ${fetched.first}")
        return withContext(Dispatchers.Default) { scanDoc(Jsoup.parse(fetched.second, url), url) }
    }

    private fun emptyScan(url: String, note: String) = DirScan(
        url = url, shape = null, item = "", link = "", parts = emptyList(), entries = emptyList(), pages = 1, hasNext = false,
        pageTemplate = null, total = 0, sorts = emptyList(), sortParam = "", orderParam = "", ascValue = "", descValue = "",
        menus = emptyList(), note = note,
    )

    private fun identitySet(): MutableSet<Element> = Collections.newSetFromMap(IdentityHashMap())

    fun scanDoc(doc: Document, url: String): DirScan {
        val next = nextPage(doc, url, emptySet())
        val highest = highestPage(doc, url)
        val pages = if (next != null) maxOf(highest, 2) else highest
        val template = pageTemplate(doc, url, next)
        val (shape, anchors) = groupAnchors(doc, url)
        if (shape == null || anchors.isEmpty()) {
            return emptyScan(url, "no list of names found on its first page").copy(
                pages = pages, hasNext = next != null, pageTemplate = template,
            )
        }
        val hrefOf = IdentityHashMap<Element, String>()
        anchors.forEach { (href, els) -> els.forEach { hrefOf[it] = href } }
        val cards = anchors.map { (href, els) -> climb(els.first(), href, hrefOf) }
        val cardSet = identitySet().apply { addAll(cards) }
        val item = itemSelector(doc, cards)
        val samples = cards.take(SAMPLE_CARDS).zip(anchors.take(SAMPLE_CARDS).map { it.second.first() })
        val found = samples.map { (card, a) -> card to classify(card, a) }
        val parts = ArrayList<DirPart>()
        // the link: the first list link of each card
        val link = pickSelector(found.map { (card, f) -> card to f.link }) { c, e, p -> pick(c, p) === e }.orEmpty()
        // the place number only when the sample cards count up (01, 02, 03…)
        val places = found.mapNotNull { (_, f) ->
            f.number?.ownText()?.trim()?.let { t ->
                (if (f.numberInTitle) LEAD_PLACE.find(t) else PLACE.find(t))?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        val counting = places.size >= 2 && places.zipWithNext().all { (a, b) -> b == a + 1 } && places.size * 2 >= found.size
        // KMK (r107) fix: the number sits in the name's text: the number part is the title's element (added below)
        val numberInTitle = counting && found.count { it.second.numberInTitle } * 2 >= found.count { it.second.number != null }
        val kinds = listOf("number", "title", "videos", "views", "likes", "picture")
        for (kind in kinds) {
            if (kind == "number" && (!counting || numberInTitle)) continue
            val pairs = found.map { (card, f) -> card to f.element(kind) }
            if (pairs.none { it.second != null }) continue
            val bareHere = found.any { kind in it.second.bare }
            val sel = pickSelector(pairs) { c, e, p ->
                val got = pick(c, p)
                when (kind) {
                    "videos", "views", "likes" -> got != null && countOf(kind, got.text(), bareHere) == countOf(kind, e.text(), bareHere)
                    else -> got === e
                }
            } ?: continue
            val f0 = found.first { it.second.element(kind) != null }.second
            parts += DirPart(
                kind = kind,
                selector = sel,
                example = "",
                attr = when (kind) {
                    "title" -> f0.titleAttr
                    "picture" -> f0.pictureAttr
                    else -> ""
                },
                own = kind == "title" && f0.titleOwn,
                bare = kind in f0.bare,
            )
        }
        if (numberInTitle) {
            parts.firstOrNull { it.kind == "title" && it.attr.isEmpty() }?.let { t -> parts.add(0, DirPart("number", t.selector, "")) }
        }
        val follow = found.firstOrNull { it.second.follow != null }?.second?.follow
        val entries = cards.mapNotNull { entryOf(it, shape, link, parts) }.distinctBy { it.href }
        val withExamples = parts.map { p ->
            val v = entries.firstOrNull { valueFor(it, p.kind).isNotEmpty() }?.let { valueFor(it, p.kind) }.orEmpty()
            p.copy(
                example = if (v.isEmpty()) "" else when (p.kind) {
                    "videos" -> if (p.bare) v else "$v videos"
                    "views" -> if (p.bare) v else "$v views"
                    "likes" -> if (p.bare) v else "$v ♥"
                    "picture" -> (p.selector.ifEmpty { "img" }) + (if (p.attr.isNotEmpty()) " (${p.attr})" else "")
                    else -> v
                },
            )
        }.filter { it.example.isNotBlank() } + listOfNotNull(follow?.let { DirPart("follow", "", "a button · never kept") })
        val pageMenus = siteMenus(doc, url, cardSet)
        val total = totalOf(doc, cardSet, entries.size)
        AppLog.add(
            'D',
            "lab",
            "extras directory scan $url: ${entries.size} entries in $item, parts " +
                withExamples.joinToString(", ") { "${it.kind}=${it.selector.ifEmpty { "(card)" }}" } +
                ", pages $pages${if (template != null) " ($template)" else ""}, total $total, sorts ${pageMenus.sorts.size}" +
                (if (pageMenus.sortParam.isNotEmpty()) " (${pageMenus.sortParam})" else "") + ", menus ${pageMenus.menus.size}",
        )
        return DirScan(
            url = url,
            shape = shape,
            item = item,
            link = link,
            parts = withExamples,
            entries = entries,
            pages = pages,
            hasNext = next != null,
            pageTemplate = template,
            total = total,
            sorts = pageMenus.sorts,
            sortParam = pageMenus.sortParam,
            orderParam = pageMenus.orderParam,
            ascValue = pageMenus.ascValue,
            descValue = pageMenus.descValue,
            menus = pageMenus.menus,
            note = if (entries.isEmpty()) "its entries couldn't be read apart" else "",
        )
    }

    private fun valueFor(e: DirEntry, kind: String): String = when (kind) {
        "number" -> e.number
        "title" -> e.label
        "videos" -> e.videos
        "views" -> e.views
        "likes" -> e.likes
        "picture" -> e.picture
        else -> ""
    }

    /**
     * r107: reads pages [from]..[to] of [scan]'s directory (page N from its `{page}` address; without one, page 1 and
     * its Next links), pausing a random [pauseMinMs]..[pauseMaxMs] between pages. [onProgress] (pages done, entries so
     * far, the pause before the next page in ms, 0 = none); [shouldStop] ends it after the current page
     */
    suspend fun readDirectory(
        scan: DirScan,
        from: Int,
        to: Int,
        pauseMinMs: Long,
        pauseMaxMs: Long,
        onProgress: (done: Int, entries: Int, pauseMs: Long) -> Unit,
        shouldStop: () -> Boolean = { false },
    ): DirRead {
        val template = scan.pageTemplate
        val start = if (template == null) 1 else from.coerceAtLeast(1)
        val count = (to - from + 1).coerceIn(1, 5_000)
        val end = start + count - 1
        val low = minOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val high = maxOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val referer = Detector.normalize(scan.url) + "/"
        val items = LinkedHashMap<String, DirEntry>()
        val visited = HashSet<String>()
        var n = start
        var pageUrl: String? = if (template == null || start == 1) scan.url else template.replace("{page}", start.toString())
        var done = 0
        var reason = ""
        AppLog.add('I', "lab", "extras read directory ${scan.url}: pages $start–$end, pause $low–$high ms")
        while (true) {
            if (shouldStop()) {
                reason = "stopped by you after $done page" + if (done == 1) "" else "s"
                break
            }
            if (done > 0) {
                val paused = Random.nextLong(low, high + 1)
                onProgress(done, items.size, paused)
                pauseFor(paused, shouldStop)
                if (shouldStop()) {
                    reason = "stopped by you after $done page" + if (done == 1) "" else "s"
                    break
                }
            }
            val url = pageUrl
            if (url == null) {
                reason = "no next page after page ${n - 1}"
                break
            }
            visited += clean(url)
            val fetched = try {
                fetch(url, referer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = "page $n could not be read (${e.message ?: e.javaClass.simpleName})"
                break
            }
            if (fetched.first >= 400) {
                reason = "the site answered ${fetched.first} for page $n"
                break
            }
            val doc = withContext(Dispatchers.Default) { Jsoup.parse(fetched.second, url) }
            done++
            val found = withContext(Dispatchers.Default) {
                runCatching { doc.select(scan.item) }.getOrNull().orEmpty().mapNotNull { entryOf(it, scan.shape, scan.link, scan.parts) }
            }
            var added = 0
            found.forEach { if (items.putIfAbsent(it.href, it) == null) added++ }
            AppLog.add('D', "lab", "extras read directory page $n: ${found.size} entries, $added new")
            onProgress(done, items.size, 0L)
            if (found.isEmpty()) {
                reason = "page $n has none of the list's entries"
                break
            }
            if (added == 0) {
                reason = "page $n repeated entries already read"
                break
            }
            if (n >= end) {
                reason = if (template == null) "read $count pages, the limit you set" else "read pages $start–$end, the range you set"
                break
            }
            n++
            pageUrl = if (template != null) template.replace("{page}", n.toString()) else nextPage(doc, url, visited)
        }
        AppLog.add('I', "lab", "extras read directory: ${items.size} entries from $done pages ($reason)")
        return DirRead(items.values.toList(), done, reason)
    }

    /** The list's links by href (each with every anchor that has it), in page order: the largest group of one shape */
    private fun groupAnchors(doc: Document, pageUrl: String): Pair<String?, List<Pair<String, List<Element>>>> {
        val page = pageUrl.toHttpUrlOrNull() ?: return null to emptyList()
        val here = page.newBuilder().fragment(null).build().toString()
        val groups = LinkedHashMap<String, LinkedHashMap<String, MutableList<Element>>>()
        for (a in doc.select("a[href]")) {
            if (a.parents().any { it.normalName() in OUTSIDE }) continue
            val url = a.absUrl("href").toHttpUrlOrNull() ?: continue
            if (url.host != page.host) continue
            val href = url.newBuilder().fragment(null).build().toString()
            if (href == here) continue
            val text = a.text().trim().ifEmpty { labelOf(a) }
            if (PAGER_TEXT.matches(text)) continue
            groups.getOrPut(shapeOf(url)) { LinkedHashMap() }.getOrPut(href) { ArrayList() }.add(a)
        }
        val best = groups.entries.filter { it.value.size >= MIN_GROUP }.maxByOrNull { it.value.size } ?: return null to emptyList()
        return best.key to best.value.entries.map { it.key to it.value.toList() }
    }

    /** An entry's card: the largest element around its link that holds no other entry's link */
    private fun climb(a: Element, href: String, hrefOf: IdentityHashMap<Element, String>): Element {
        var card = a
        while (true) {
            val up = card.parent() ?: break
            if (up.normalName() == "body" || up.normalName() == "html") break
            val other = up.select("a[href]").any { el -> hrefOf[el]?.let { it != href } == true }
            if (other) break
            card = up
        }
        return card
    }

    private fun classesOf(el: Element): List<String> =
        el.classNames().filter { CLASS_OK.matches(it) && it.lowercase() !in CLASS_STATE }.sorted()

    private fun simple(el: Element): String = el.normalName() + classesOf(el).joinToString("") { ".$it" }

    private fun common(els: List<Element>): String? {
        val tag = els.first().normalName()
        if (els.any { it.normalName() != tag }) return null
        val shared = els.map { classesOf(it).toSet() }.reduce { a, b -> a intersect b }.sorted()
        return tag + shared.joinToString("") { ".$it" }
    }

    /**
     * The cards' selector: their tag and shared classes, else with their parents' ("ul.grid > li"), the first that finds
     * (nearly) all of them and not many more; else the most precise one tried
     */
    private fun itemSelector(doc: Document, cards: List<Element>): String {
        val own = common(cards) ?: cards.first().normalName()
        val candidates = ArrayList<String>()
        if ('.' in own) candidates += own
        var level: List<Element> = cards
        var chain = own
        for (depth in 1..3) {
            val parents = level.mapNotNull { it.parent() }
            if (parents.size != level.size) break
            val p = common(parents) ?: break
            chain = "$p > $chain"
            candidates += chain
            level = parents
        }
        if ('.' !in own) candidates += own
        for (c in candidates) {
            val found = runCatching { doc.select(c) }.getOrNull() ?: continue
            val ids = identitySet().apply { addAll(found) }
            val covered = cards.count { it in ids }
            if (covered * 10 >= cards.size * 9 && found.size <= cards.size * 2 + 2) return c
        }
        return candidates.lastOrNull { it.contains(" > ") } ?: own
    }

    /** [sel] inside [card] ("" = the card itself), null when it finds nothing or isn't valid */
    private fun pick(card: Element, sel: String): Element? =
        if (sel.isEmpty()) card else runCatching { card.selectFirst(sel) }.getOrNull()

    /** The ways to find [el] inside [card]: its own tag and classes, then the steps down from the card */
    private fun selectorsFor(card: Element, el: Element): List<String> {
        if (el === card) return listOf("")
        val out = ArrayList<String>()
        out += simple(el)
        val steps = ArrayList<String>()
        var at: Element? = el
        while (at != null && at !== card) {
            steps.add(0, simple(at))
            at = at.parent()
        }
        if (at === card) out += "> " + steps.joinToString(" > ")
        return out.distinct()
    }

    /**
     * The selector that finds each sample card's element best ([same] says whether a pick is right); null when no
     * card has one. One right pick in two cards that have it is needed
     */
    private fun pickSelector(pairs: List<Pair<Element, Element?>>, same: (Element, Element, String) -> Boolean): String? {
        val have = pairs.filter { it.second != null }
        if (have.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for ((card, el) in have) {
            for (sel in selectorsFor(card, el!!)) {
                val score = have.count { (c, e) -> runCatching { same(c, e!!, sel) }.getOrDefault(false) }
                if (score > bestScore) {
                    best = sel
                    bestScore = score
                }
            }
            if (bestScore == have.size) break
        }
        return best?.takeIf { bestScore * 2 >= have.size }
    }

    /** What one card holds, part by part (the elements, before selectors are chosen) */
    private class Found {
        var link: Element? = null
        var number: Element? = null

        /** KMK (r107) fix: the place number is the start of the title's own text ("01 Slice of Life") */
        var numberInTitle = false
        var title: Element? = null
        var titleAttr = ""
        var titleOwn = false
        val counts = HashMap<String, Element>()
        val bare = HashSet<String>()
        var picture: Element? = null
        var pictureAttr = ""
        var follow: Element? = null

        fun element(kind: String): Element? = when (kind) {
            "number" -> number
            "title" -> title
            "picture" -> picture
            else -> counts[kind]
        }
    }

    private fun classify(card: Element, link: Element): Found {
        val f = Found()
        f.link = link
        val all = card.allElements.toList()
        f.follow = all.firstOrNull { e ->
            val t = e.text().trim()
            t.isNotEmpty() && t.length <= 40 && FOLLOW.matches(t)
        }
        val follow = f.follow
        fun outside(e: Element): Boolean = follow == null || (e !== follow && e.parents().none { it === follow })
        // counts: the smallest element holding "N videos", "N views", "N likes"
        for (kind in listOf("videos", "views", "likes")) {
            fun hit(t: String): Boolean = when (kind) {
                "videos" -> VIDEOS.containsMatchIn(t)
                "views" -> VIEWS.containsMatchIn(t)
                else -> LIKES.containsMatchIn(t) || LIKES_BEFORE.containsMatchIn(t)
            }
            all.firstOrNull { e -> outside(e) && hit(e.text()) && e.children().none { hit(it.text()) } }?.let { f.counts[kind] = it }
        }
        val leaves = all.filter { outside(it) && it.ownText().isNotBlank() }
        val firstLeaf = leaves.firstOrNull()
        if (firstLeaf != null && PLACE.matches(firstLeaf.ownText().trim())) f.number = firstLeaf
        fun onlyCounts(t: String): Boolean {
            val rest = t.replace(VIDEOS, "").replace(VIEWS, "").replace(LIKES, "").replace(LIKES_BEFORE, "").trim()
            return rest.none { it.isLetter() } || UNIT_ONLY.matches(rest)
        }
        val candidates = leaves.filter { e -> e !== f.number && !onlyCounts(e.ownText().trim()) }
        fun initials(e: Element): Boolean {
            val t = e.ownText().trim()
            // KMK (r107) fix: any 1–3 characters ("S1", "1P", "K.", "S-", "U&"), whatever their case
            if (t.isEmpty() || t.length > 3 || t.any { it.isWhitespace() }) return false
            val hint = e.className() + " " + (e.parent()?.className() ?: "")
            if (LOGO_HINT.containsMatchIn(hint)) return true
            return candidates.any { o -> o !== e && o.ownText().trim().let { it.length > t.length && it.startsWith(t, ignoreCase = true) } }
        }
        val names = candidates.filterNot(::initials)
        val title = names.firstOrNull { e -> e.normalName() in HEADINGS || NAME_HINT.containsMatchIn(e.className()) }
            ?: names.firstOrNull()
        if (title != null) {
            f.title = title
            f.titleOwn = title.children().any { it.text().isNotBlank() }
            // KMK (r107) fix: no number element, the name starts with it: the number part is read from the name
            if (f.number == null && LEAD_PLACE.containsMatchIn(title.ownText().trim())) {
                f.number = title
                f.numberInTitle = true
            }
        } else if (link.attr("title").isNotBlank()) {
            f.title = link
            f.titleAttr = "title"
        } else {
            card.select("img[alt]").firstOrNull { it.attr("alt").isNotBlank() }?.let {
                f.title = it
                f.titleAttr = "alt"
            }
        }
        // numbers without a unit word: the counts the card has no word for, by the hint around them, else in order
        val counted = f.counts.values
        val bare = leaves.filter { e ->
            e !== f.number && e !== f.title && BARE.matches(e.ownText().trim()) &&
                counted.none { c -> c === e || e.parents().any { it === c } || c.parents().any { it === e } }
        }
        val missing = listOf("videos", "views", "likes").filter { it !in f.counts }.toMutableList()
        for (e in bare) {
            if (missing.isEmpty()) break
            val hint = listOfNotNull(
                e.className(),
                e.parent()?.className(),
                e.attr("title"),
                e.attr("aria-label"),
                e.previousElementSibling()?.className(),
                e.children().joinToString(" ") { it.className() },
            ).joinToString(" ")
            val kind = missing.firstOrNull { k -> COUNT_HINTS[k]?.containsMatchIn(hint) == true } ?: missing.first()
            f.counts[kind] = e
            f.bare += kind
            missing.remove(kind)
        }
        val base = card.baseUri()
        card.select("img").firstOrNull { outside(it) && pictureOf(it, "", base) != null }?.let { img ->
            f.picture = img
            f.pictureAttr = pictureOf(img, "", base)?.first.orEmpty()
        }
        return f
    }

    private val PICTURE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src")
    private val PLACEHOLDER = Regex(
        """^(placeholder|blank|spacer|transparent|pixel|1x1|empty|lazy|lazyload|loading|loader|grey|gray|no[-_]?(image|img|cover|thumb|poster))([-_.](min|small|\d+x\d+))?\.(gif|png|jpe?g|webp|svg)$""",
    )

    /** An img's picture: (attribute, absolute address) from [first] then the lazy-load attributes, srcset and src */
    private fun pictureOf(img: Element, first: String, base: String): Pair<String, String>? {
        val names = (listOf(first) + PICTURE_ATTRS).filter { it.isNotEmpty() }.distinct()
        for (n in names) {
            var v = img.attr(n).trim()
            if (v.isEmpty()) continue
            v = if (n.contains("srcset")) v.split(Regex(""",\s+""")).first().trim().split(SPACES).first() else v.split(SPACES).first()
            if (v.isEmpty() || v.startsWith("data:", ignoreCase = true) || v.startsWith("about:", ignoreCase = true)) continue
            val file = v.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            if (PLACEHOLDER.matches(file)) continue
            val abs = base.toHttpUrlOrNull()?.resolve(v)?.toString() ?: v
            if (!abs.startsWith("http")) continue
            return n to abs
        }
        return null
    }

    /** One card as an entry, read with the scan's selectors (the template's directoryItems does the same) */
    private fun entryOf(card: Element, shape: String?, link: String, parts: List<DirPart>): DirEntry? {
        var a = if (link.isNotEmpty()) pick(card, link) else null
        if (a != null && a.normalName() != "a") a = a.selectFirst("a[href]")
        if (a == null) {
            a = if (card.normalName() == "a" && card.hasAttr("href")) {
                card
            } else {
                card.select("a[href]").firstOrNull { el -> shape == null || el.absUrl("href").toHttpUrlOrNull()?.let(::shapeOf) == shape }
            }
        }
        if (a == null) return null
        val url = a.absUrl("href").toHttpUrlOrNull() ?: return null
        if (shape != null && shapeOf(url) != shape) return null
        val href = url.newBuilder().fragment(null).build().toString()
        val t = parts.firstOrNull { it.kind == "title" }
        val titleText = t?.let { p ->
            pick(card, p.selector)?.let { e ->
                when {
                    p.attr.isNotEmpty() -> e.attr(p.attr)
                    p.own -> e.ownText()
                    else -> e.text()
                }
            }
        }
        // KMK (r107) fix: the number read from the name's own text ("01 Slice of Life") is not part of the name
        val numberPart = parts.firstOrNull { it.kind == "number" }
        val inTitle = t != null && numberPart != null && t.attr.isEmpty() && numberPart.selector == t.selector
        val name = if (inTitle) titleText?.replace(LEAD_PLACE, "") else titleText
        // KMK (r107) fix: the whole link's text ("FC FC2") without a logo's letters
        val label = entryLabel(name).ifEmpty { entryLabel(a.attr("title")) }.ifEmpty { dropInitials(entryLabel(a.text())) }
        if (label.isEmpty()) return null
        fun count(kind: String): String {
            val p = parts.firstOrNull { it.kind == kind } ?: return ""
            val e = pick(card, p.selector) ?: return ""
            return countOf(kind, e.text(), p.bare)
        }
        val picture = parts.firstOrNull { it.kind == "picture" }?.let { p ->
            pick(card, p.selector)?.let { e ->
                val img = if (e.normalName() == "img") e else e.selectFirst("img") ?: e
                pictureOf(img, p.attr, card.baseUri())?.second
            }
        }.orEmpty()
        return DirEntry(label, href, count("number"), count("videos"), count("views"), count("likes"), picture)
    }

    /** Page N's address with `{page}`: from the pager's "2" link, else the Next link when it is page 2; null = none */
    private fun pageTemplate(doc: Document, url: String, next: String?): String? {
        val host = url.toHttpUrlOrNull()?.host
        val two = doc.select("a[href]").firstOrNull { a ->
            a.text().trim() == "2" && a.absUrl("href").toHttpUrlOrNull()?.host == host && templateFrom(a.absUrl("href"), 2) != null
        }
        two?.let { return templateFrom(it.absUrl("href").substringBefore('#'), 2) }
        return next?.let { templateFrom(it.substringBefore('#'), 2) }
    }

    /** [abs] with its page number [n] (`page=n`, `/page/n`, or its last path part) as `{page}`; null = not there */
    fun templateFrom(abs: String, n: Int): String? {
        PAGE_NUM_QUERY.findAll(abs).firstOrNull { it.groupValues[2].toIntOrNull() == n }?.let { m ->
            val digits = m.groups[2] ?: return null
            return abs.replaceRange(digits.range, "{page}")
        }
        PAGE_NUM_PATH.findAll(abs).firstOrNull { it.groupValues[2].toIntOrNull() == n }?.let { m ->
            val digits = m.groups[2] ?: return null
            return abs.replaceRange(digits.range, "{page}")
        }
        val pathEnd = abs.substringBefore('?')
        val tail = "/$n"
        val trimmed = pathEnd.trimEnd('/')
        if (trimmed.endsWith(tail) && trimmed.length > tail.length + 8) {
            val at = trimmed.length - n.toString().length
            return abs.substring(0, at) + "{page}" + abs.substring(at + n.toString().length)
        }
        return null
    }

    /** r107: the entry count the page shows ("25,786 series", "1 – 24 of 25,786"), 0 when none (never inside a card) */
    private fun totalOf(doc: Document, cards: Set<Element>, entries: Int): Int {
        var best = 0
        for (e in doc.select("h1, h2, h3, h4, h5, h6, p, span, div, small, strong, b, em, li")) {
            val t = e.ownText().trim()
            if (t.isEmpty() || t.length > 80 || !t.any(Char::isDigit)) continue
            if (e in cards || e.parents().any { it in cards }) continue
            for (m in TOTAL_WORD.findAll(t) + TOTAL_OF.findAll(t)) {
                // KMK (r107) fix: "Page 1 of 75" is the page count, not the entries'
                if (PAGE_OF.containsMatchIn(t.substring(0, m.range.first))) continue
                val n = m.groupValues[1].replace(",", "").replace(".", "").toIntOrNull() ?: continue
                if (n > best && n >= entries && n < 10_000_000) best = n
            }
        }
        return best
    }

    private data class PageMenus(
        val sorts: List<ExtrasOption>,
        val sortParam: String,
        val orderParam: String,
        val ascValue: String,
        val descValue: String,
        val menus: List<CollectionMenu>,
    )

    /**
     * r107: the directory page's own menus: `<select>`s (a search form's left out) and groups of links to this same
     * page that differ in one address parameter. Read like sort orders (or named sort / order…) → the sort options;
     * asc / desc → the order; the others ("Height", "Cup", "Age") → menus
     */
    private fun siteMenus(doc: Document, url: String, cards: Set<Element>): PageMenus {
        val page = url.toHttpUrlOrNull() ?: return PageMenus(emptyList(), "", "", "", "", emptyList())
        val here = page.encodedPath.trimEnd('/')
        // param → (value → label), and a name shown for it
        val groups = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val shown = HashMap<String, String>()
        fun addLink(abs: String, label: String) {
            val u = abs.toHttpUrlOrNull() ?: return
            if (u.host != page.host || u.encodedPath.trimEnd('/') != here) return
            for (name in u.queryParameterNames) {
                if (name.lowercase() in PAGE_PARAMS) continue
                val v = u.queryParameter(name).orEmpty()
                if (v.isEmpty()) continue
                groups.getOrPut(name) { LinkedHashMap() }.putIfAbsent(v, label)
            }
        }
        for (a in doc.select("a[href]")) {
            if (a in cards || a.parents().any { it in cards || it.normalName() == "nav" || it.normalName() == "footer" }) continue
            if (a.parents().any { it.normalName() == "header" && it.parent()?.normalName() == "body" }) continue
            val label = BuilderExtrasLogic.cleanLabel(a.text())
            if (label.isEmpty() || label.length > 40) continue
            addLink(a.absUrl("href"), label)
        }
        for (sel in doc.select("select")) {
            if (sel.parents().any { it.normalName() == "form" && it.select(SEARCH_INPUTS).isNotEmpty() }) continue
            val opts = sel.select("option").map { o ->
                val text = o.text().trim()
                ExtrasOption(text, if (o.hasAttr("value")) o.attr("value").trim() else text)
            }.filter { it.label.isNotEmpty() }
            if (opts.size < 2) continue
            // a select whose values are addresses works like links
            val addresses = opts.filter { it.value.startsWith("/") || it.value.startsWith("http") || it.value.startsWith("?") }
            if (addresses.size * 2 >= opts.size) {
                addresses.forEach { o -> page.resolve(o.value)?.toString()?.let { addLink(it, o.label) } }
                continue
            }
            val name = sel.attr("name").trim().ifEmpty { sel.id().trim() }
            if (name.isEmpty() || name.lowercase() in PAGE_PARAMS) continue
            val map = groups.getOrPut(name) { LinkedHashMap() }
            opts.filter { it.value.isNotEmpty() }.forEach { map.putIfAbsent(it.value, it.label) }
            val id = sel.id().trim()
            val label = (if (id.isNotEmpty()) runCatching { doc.selectFirst("label[for=$id]") }.getOrNull()?.text() else null)
                ?: sel.attr("aria-label")
            BuilderExtrasLogic.cleanHeading(label)?.let { shown[name] = it }
        }
        var sorts = emptyList<ExtrasOption>()
        var sortParam = ""
        var orderParam = ""
        var asc = ""
        var desc = ""
        val menus = ArrayList<CollectionMenu>()
        for ((name, values) in groups) {
            if (values.size < 2) continue
            val options = values.map { (v, l) -> ExtrasOption(l, v) }
            val lowerValues = values.keys.map { it.lowercase() }
            if (sortParam != name && lowerValues.all { it in ORDER_VALUES } && (name.lowercase() in ORDER_PARAMS || orderParam.isEmpty())) {
                orderParam = name
                asc = values.keys.firstOrNull { it.lowercase().startsWith("asc") }.orEmpty()
                desc = values.keys.firstOrNull { it.lowercase().startsWith("desc") }.orEmpty()
                continue
            }
            val labels = options.map { it.label }
            val sortish = name.lowercase() in SORT_PARAMS || labels.count { SORT_TEXT.containsMatchIn(it) } * 5 >= labels.size * 3
            if (sortish && sortParam.isEmpty()) {
                sorts = options.take(30)
                sortParam = name
            } else if (options.size <= 300) {
                menus += CollectionMenu(name, shown[name] ?: BuilderExtrasLogic.prettyName(name), options)
            }
        }
        if (orderParam.isNotEmpty() && (asc.isEmpty() || desc.isEmpty())) orderParam = ""
        return PageMenus(sorts, sortParam, orderParam, asc, desc, menus.take(8))
    }

    /** The pause range in seconds from the migration setting ("1-3"), within 0–10 and on half seconds; else 1–3 */
    fun defaultPause(): ClosedFloatingPointRange<Float> {
        val saved = runCatching { Injekt.get<SourcePreferences>().migrationDelay().get() }.getOrNull().orEmpty()
        val parts = saved.split('-').map { it.trim().toFloatOrNull() }
        val from = parts.getOrNull(0)
        val to = parts.getOrNull(1)
        if (parts.size != 2 || from == null || to == null || to <= 0f) return 1f..3f
        fun snap(v: Float) = ((v.coerceIn(0f, 10f) * 2f).roundToInt() / 2f)
        val low = snap(minOf(from, to))
        val high = snap(maxOf(from, to))
        return low..high
    }
}

/**
 * The "Read the whole list" dialog: the page, a two-point slider for the random pause between pages (0–10 s, half
 * seconds, from the migration setting), how many pages to read (1–100), Start / Stop, progress, then "Make a filter".
 * r103 ("Fetch the full list" of a menu group, [name]): after reading, the filter's form: **Dropdown** (select) or
 * **Text box with suggestions** (autocomplete; chosen at first for long lists); [onDone] gets "select" / "autocomplete".
 */
@Composable
internal fun ReadListDialog(
    url: String,
    onDismiss: () -> Unit,
    onDone: (BuilderListReader.Result, String) -> Unit,
    name: String? = null,
) {
    val scope = rememberCoroutineScope()
    var pause by remember { mutableStateOf(BuilderListReader.defaultPause()) }
    var pages by remember { mutableFloatStateOf(20f) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<BuilderListReader.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val stop = remember { AtomicBoolean(false) }
    // r103: null = not chosen yet (a long list starts as a text box with suggestions)
    var form by remember { mutableStateOf<String?>(null) }

    fun seconds(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    fun start() {
        stop.set(false)
        result = null
        running = true
        progress = "Page 1 · 0 entries"
        val range = pause
        val count = pages.roundToInt()
        job = scope.launch {
            try {
                result = BuilderListReader.read(
                    url = url,
                    maxPages = count,
                    pauseMinMs = (range.start * 1000f).roundToLong(),
                    pauseMaxMs = (range.endInclusive * 1000f).roundToLong(),
                    onProgress = { page, entries -> progress = "Page $page · $entries entries" },
                    shouldStop = { stop.get() },
                )
            } finally {
                running = false
            }
        }
    }

    fun close() {
        job?.cancel()
        onDismiss()
    }

    val done = result
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(if (name.isNullOrBlank()) "Fetch the full list" else "Fetch the full list: $name") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    url,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Every page of this list is read, following its Next link. A random pause between pages keeps " +
                        "the site from seeing a bot-like pace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Pause between pages: ${seconds(pause.start)}–${seconds(pause.endInclusive)} s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RangeSlider(
                    value = pause,
                    onValueChange = { pause = it },
                    valueRange = 0f..10f,
                    // half-second stops: 21 values, 19 between the ends
                    steps = 19,
                    enabled = !running,
                )
                Text("Pages to read: ${pages.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = pages,
                    onValueChange = { pages = it },
                    valueRange = 1f..100f,
                    steps = 98,
                    enabled = !running,
                )
                val shown = progress
                if (shown != null) {
                    Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (done != null) {
                    Text(
                        "Read ${done.items.size} entries from ${done.pages} page" + (if (done.pages == 1) "" else "s") +
                            ": ${done.reason}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (done.items.isNotEmpty()) {
                        Text(
                            done.items.take(6).joinToString(", ") { item ->
                                BuilderExtrasLogic.cleanLabel(item.text).ifBlank { item.href.substringAfterLast('/') }
                            } +
                                if (done.items.size > 6) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // r103: the filter's form
                        val chosen = form ?: defaultForm(done.items.size)
                        Text("The filter is a", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = chosen == "select", onClick = { form = "select" }, label = { Text("Dropdown") })
                            FilterChip(
                                selected = chosen == "autocomplete",
                                onClick = { form = "autocomplete" },
                                label = { Text("Text box with suggestions") },
                            )
                        }
                        Text(
                            if (chosen == "autocomplete") {
                                "Typed names are matched to the list (any case); a name not in it is sent as a slug (Slice of Life → slice-of-life)."
                            } else {
                                "One entry is picked from the list."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                running -> TextButton(onClick = { stop.set(true) }) { Text("Stop") }
                done != null && done.items.isNotEmpty() -> {
                    val chosen = form ?: defaultForm(done.items.size)
                    val cap = if (chosen == "autocomplete") BuilderExtrasLogic.MAX_SUGGESTIONS else BuilderExtrasLogic.MAX_GROUP_OPTIONS
                    TextButton(onClick = { onDone(done, chosen) }) {
                        Text(
                            if (done.items.size > cap) {
                                "Make a filter (first $cap of ${done.items.size})"
                            } else {
                                "Make a filter (${done.items.size})"
                            },
                        )
                    }
                }
                else -> TextButton(onClick = { start() }) { Text(if (done == null) "Start" else "Read again") }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }) { Text(if (running) "Cancel" else "Close") }
        },
    )
}

/** r103: a list longer than a dropdown reads well as a text box with suggestions */
private fun defaultForm(entries: Int): String = if (entries > 60) "autocomplete" else "select"
