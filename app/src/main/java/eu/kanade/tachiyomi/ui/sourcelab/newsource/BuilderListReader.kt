package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.network.BotCheckGate
import eu.kanade.tachiyomi.ui.sourcelab.newsource.dir.DirCache
import eu.kanade.tachiyomi.ui.sourcelab.newsource.dir.DirPager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * [items] deduped by href in page order; [reason] says why it stopped; [shape] the list's link shape. KMK (r119):
     * [ended] end (the list ended by itself) | limit | stopped | failed | botcheck; only end / limit are remembered
     */
    @Immutable
    data class Result(val items: List<Item>, val pages: Int, val reason: String, val shape: String?, val ended: String = "")

    private const val MIN_GROUP = 5
    private val OUTSIDE = setOf("nav", "header", "footer", "aside")

    // No braces in these patterns (ICU)
    private val NEXT_TEXT = Regex("""^(?:next(?:\s+page)?|next\s*[›»>→]+|[›»>→])$""", RegexOption.IGNORE_CASE)
    private val PAGER_TEXT = Regex("""^(?:\d\d?\d?\d?|[‹«<←›»>→]+|prev(?:ious)?|next|first|last|…|\.\.\.)$""", RegexOption.IGNORE_CASE)
    private val PAGE_QUERY = Regex("""[?&](?:page|p|pg)=(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_PATH = Regex("""/page/(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Reads [url] and its next pages, up to [maxPages], pausing a random [pauseMinMs]..[pauseMaxMs] between pages.
     * [onProgress] runs on the caller's thread; [shouldStop] ends it (KMK (r119): the pages in flight are cancelled),
     * keeping what was read. Cancelling the coroutine stops at once.
     *
     * KMK (r119): when page 1's Next link is page 2 of a numbered address, pages 2… are read a few at a time
     * ([DirPager.readRange], in page order, one at a time while the site is asked slowly); else one after another by
     * their Next links as before. A bot check page stops the read ([DirPager.BotCheckFound]); a 429 is asked once more
     * after the site's pace.
     */
    suspend fun read(
        url: String,
        maxPages: Int,
        pauseMinMs: Long,
        pauseMaxMs: Long,
        onProgress: (DirPager.Progress) -> Unit,
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
        var ended = "end"
        var saw429 = false
        fun add(links: List<Item>): Int {
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
            return added
        }
        AppLog.add('I', "lab", "extras read list from $pageUrl: up to $limit pages, pause ${low}–$high ms")
        while (true) {
            if (shouldStop()) {
                reason = "stopped by you after page $pages"
                ended = "stopped"
                break
            }
            var paused = 0L
            if (pages > 0) {
                paused = maxOf(Random.nextLong(low, high + 1), DirPager.paceFor(pageUrl, saw429).paceMs)
                onProgress(DirPager.Progress(pages, items.size, 0, 0, paused))
                DirPager.pauseFor(paused, shouldStop)
                if (shouldStop()) {
                    reason = "stopped by you after page $pages"
                    ended = "stopped"
                    break
                }
            }
            onProgress(DirPager.Progress(pages, items.size, pages + 1, pages + 1, 0L))
            visited += clean(pageUrl)
            val fetched = try {
                fetchPaced(pageUrl, referer) { saw429 = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (DirPager.isBotCheckError(e)) {
                    reason = "page ${pages + 1}: ${DirPager.BOT_CHECK_TEXT}"
                    ended = "botcheck"
                } else {
                    reason = "page ${pages + 1} could not be read (${e.message ?: e.javaClass.simpleName})"
                    ended = "failed"
                }
                break
            }
            if (fetched.first >= 400) {
                reason = "the site answered ${fetched.first} for page ${pages + 1}"
                ended = if (pages > 0 && (fetched.first == 404 || fetched.first == 410)) "end" else "failed"
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
            val added = add(links)
            AppLog.add('D', "lab", "extras read list page $pages: ${links.size} entries (paused $paused ms)")
            onProgress(DirPager.Progress(pages, items.size, 0, 0, 0L))
            if (added == 0 && pages > 1) {
                reason = "page $pages repeated entries already read"
                break
            }
            if (pages >= limit) {
                reason = "read $limit pages, the limit you set"
                ended = "limit"
                break
            }
            val next = nextPage(doc, current, visited)
            if (next == null) {
                reason = "no next page after page $pages"
                break
            }
            // KMK (r119): page 2 of a numbered address: the rest a few at a time, in page order
            val template = if (pages == 1) {
                templateFrom(next.substringBefore('#'), 2)?.takeIf { clean(it.replace("{page}", "2")) == clean(next) }
            } else {
                null
            }
            if (template != null) {
                val highest = highestPage(doc, current)
                val last = if (highest > 1) minOf(limit, highest) else limit
                var pageEnded = "end"
                val end = DirPager.readRange(
                    first = 2,
                    last = last,
                    urlOf = { n -> template.replace("{page}", n.toString()) },
                    referer = referer,
                    pauseMinMs = low,
                    pauseMaxMs = high,
                    shouldStop = shouldStop,
                    onSet = { from, to, pause -> onProgress(DirPager.Progress(pages, items.size, from, to, pause)) },
                    onPage = { n, page ->
                        if (page.code >= 400) {
                            pageEnded = if (page.code == 404 || page.code == 410) "end" else "failed"
                            return@readRange "the site answered ${page.code} for page $n"
                        }
                        val d = withContext(Dispatchers.Default) { Jsoup.parse(page.body, page.url) }
                        pages++
                        val (_, more) = pageLinks(d, page.url, shape)
                        if (more.isEmpty()) return@readRange "page $n has none of the list's links"
                        val got = add(more)
                        AppLog.add('D', "lab", "extras read list page $n: ${more.size} entries, $got new")
                        onProgress(DirPager.Progress(pages, items.size, 0, 0, 0L))
                        if (got == 0) "page $n repeated entries already read" else null
                    },
                )
                reason = end.reason ?: when {
                    end.kind == "stopped" -> "stopped by you after page $pages"
                    last >= limit -> "read $limit pages, the limit you set"
                    else -> "no next page after page $pages"
                }
                ended = when (end.kind) {
                    "stopped", "botcheck", "failed" -> end.kind
                    else -> if (end.reason == null) (if (last >= limit) "limit" else "end") else pageEnded
                }
                break
            }
            pageUrl = next
        }
        AppLog.add('I', "lab", "extras read list: ${items.size} entries from $pages pages ($reason)")
        return Result(items.values.toList(), pages, reason, shape, ended)
    }

    /**
     * KMK (r119): one page; a 429 (after the client's own Retry-After wait) marks the site slow, waits its pace and
     * asks once more ([on429] lets the caller go one page at a time from then on). A bot check throws
     */
    private suspend fun fetchPaced(url: String, referer: String, on429: () -> Unit): Pair<Int, String> {
        val first = DirPager.fetch(url, referer)
        if (first.code != 429) return first.code to first.body
        on429()
        runCatching { BotCheckGate.markSlow(url) }
        val pace = DirPager.paceFor(url, saw429 = true).paceMs
        AppLog.add('I', "lab", "extras directory read: 429 for $url, one page at a time, asked again in $pace ms")
        delay(pace)
        val again = DirPager.fetch(url, referer)
        return again.code to again.body
    }

    /** KMK (r119): through [DirPager.fetch] (a bot check page throws [DirPager.BotCheckFound]) */
    private suspend fun fetch(url: String, referer: String): Pair<Int, String> =
        DirPager.fetch(url, referer).let { it.code to it.body }

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
        /**
         * KMK (r109): the page's custom drop-downs not read yet ("Sort: Most videos" whose entries are buttons, or a list
         * the site adds only when it opens): the card's "Learn the menus" presses them on the page
         */
        val custom: List<BuilderDirMenus.Menu> = emptyList(),
    ) {
        fun part(kind: String): DirPart? = parts.firstOrNull { it.kind == kind }

        /** Entries were found with their selectors: the scan card (else the r106 "Read all pages") */
        val ok: Boolean get() = item.isNotEmpty() && entries.isNotEmpty() && part("title") != null
    }

    /**
     * r107: a page range read with a scan's selectors: the entries (deduped by link), the pages read, why it ended.
     * KMK (r119): [ended] as [Result.ended]
     */
    @Immutable
    data class DirRead(val entries: List<DirEntry>, val pages: Int, val reason: String, val ended: String = "")

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
                    "picture" -> (p.selector.ifEmpty { "the card" }) + (if (p.attr.isNotEmpty()) " (${p.attr})" else "")
                    else -> v
                },
            )
        }.filter { it.example.isNotBlank() } + listOfNotNull(follow?.let { DirPart("follow", "", "a button · never kept") })
        val siteFound = siteMenus(doc, url, cardSet)
        // KMK (r109): the custom drop-downs ("Sort: Most videos", "Height: Any height"): their link entries give codes now,
        // the others are pressed on the page later; they win over the plain link groups of the same parameter
        val customMenus = runCatching { BuilderDirMenus.find(doc, url, cardSet) }.getOrDefault(emptyList())
        val custom = BuilderDirMenus.resolve(customMenus, url)
        val pageMenus = if (custom.sorts.isEmpty() && custom.menus.isEmpty()) {
            siteFound
        } else {
            val sortFromCustom = custom.sorts.isNotEmpty()
            val sortParam = if (sortFromCustom) custom.sortParam else siteFound.sortParam
            siteFound.copy(
                sorts = if (sortFromCustom) custom.sorts else siteFound.sorts,
                sortParam = sortParam,
                menus = (custom.menus + siteFound.menus.filter { it.key !in custom.claimed && it.key != sortParam }).take(8),
            )
        }
        val pending = custom.pending.filter { m -> !(m.sort && pageMenus.sorts.isNotEmpty()) }
        val total = totalOf(doc, cardSet, entries.size)
        AppLog.add(
            'D',
            "lab",
            "extras directory scan $url: ${entries.size} entries in $item, parts " +
                withExamples.joinToString(", ") { "${it.kind}=${it.selector.ifEmpty { "(card)" }}" } +
                ", pages $pages${if (template != null) " ($template)" else ""}, total $total, sorts ${pageMenus.sorts.size}" +
                (if (pageMenus.sortParam.isNotEmpty()) " (${pageMenus.sortParam})" else "") + ", menus ${pageMenus.menus.size}" +
                (if (customMenus.isNotEmpty()) ", drop-downs " + customMenus.joinToString(", ") { "${it.label}: ${it.current} (${it.items.size})" } else "") +
                (if (pending.isNotEmpty()) ", to press ${pending.joinToString(", ") { it.label }}" else ""),
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
            custom = pending,
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
     * its Next links), pausing a random [pauseMinMs]..[pauseMaxMs] between pages. [onProgress] (pages counted from
     * [from]: 1 = the first page of the range); [shouldStop] ends it (KMK (r119): the pages in flight are cancelled).
     *
     * KMK (r119): with a `{page}` address the pages are read a few at a time ([DirPager.readRange]: in page order, one
     * at a time while the site is asked slowly or after a 429, stopped by a bot check page)
     */
    suspend fun readDirectory(
        scan: DirScan,
        from: Int,
        to: Int,
        pauseMinMs: Long,
        pauseMaxMs: Long,
        onProgress: (DirPager.Progress) -> Unit,
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
        var done = 0
        var reason = ""
        var ended = "end"
        fun s(n: Int) = if (n == 1) "" else "s"
        suspend fun entriesOf(body: String, url: String): List<DirEntry> = withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(body, url)
            runCatching { doc.select(scan.item) }.getOrNull().orEmpty().mapNotNull { entryOf(it, scan.shape, scan.link, scan.parts) }
        }
        AppLog.add('I', "lab", "extras read directory ${scan.url}: pages $start–$end, pause $low–$high ms")
        if (template != null) {
            // KMK (r119): numbered pages, a few at a time, handed back in page order
            var pageEnded = "end"
            val run = DirPager.readRange(
                first = start,
                last = end,
                urlOf = { n -> if (n == 1) scan.url else template.replace("{page}", n.toString()) },
                referer = referer,
                pauseMinMs = low,
                pauseMaxMs = high,
                shouldStop = shouldStop,
                onSet = { a, b, pause -> onProgress(DirPager.Progress(done, items.size, a - start + 1, b - start + 1, pause)) },
                onPage = { n, page ->
                    if (page.code >= 400) {
                        pageEnded = if (done > 0 && (page.code == 404 || page.code == 410)) "end" else "failed"
                        return@readRange "the site answered ${page.code} for page $n"
                    }
                    val found = entriesOf(page.body, page.url)
                    done++
                    var added = 0
                    found.forEach { if (items.putIfAbsent(it.href, it) == null) added++ }
                    AppLog.add('D', "lab", "extras read directory page $n: ${found.size} entries, $added new")
                    onProgress(DirPager.Progress(done, items.size, 0, 0, 0L))
                    when {
                        found.isEmpty() -> "page $n has none of the list's entries"
                        added == 0 -> "page $n repeated entries already read"
                        else -> null
                    }
                },
            )
            reason = run.reason ?: if (run.kind == "stopped") "stopped by you after $done page${s(done)}" else "read pages $start–$end, the range you set"
            ended = when (run.kind) {
                "stopped", "botcheck", "failed" -> run.kind
                else -> if (run.reason == null) "limit" else pageEnded
            }
            AppLog.add('I', "lab", "extras read directory: ${items.size} entries from $done pages ($reason)")
            return DirRead(items.values.toList(), done, reason, ended)
        }
        // No page numbers in its address: page 1 and its Next links, one after another
        val visited = HashSet<String>()
        var saw429 = false
        var n = start
        var pageUrl: String? = scan.url
        while (true) {
            if (shouldStop()) {
                reason = "stopped by you after $done page${s(done)}"
                ended = "stopped"
                break
            }
            if (done > 0) {
                val paused = maxOf(Random.nextLong(low, high + 1), DirPager.paceFor(scan.url, saw429).paceMs)
                onProgress(DirPager.Progress(done, items.size, 0, 0, paused))
                DirPager.pauseFor(paused, shouldStop)
                if (shouldStop()) {
                    reason = "stopped by you after $done page${s(done)}"
                    ended = "stopped"
                    break
                }
            }
            val url = pageUrl
            if (url == null) {
                reason = "no next page after page ${n - 1}"
                break
            }
            onProgress(DirPager.Progress(done, items.size, done + 1, done + 1, 0L))
            visited += clean(url)
            val fetched = try {
                fetchPaced(url, referer) { saw429 = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (DirPager.isBotCheckError(e)) {
                    reason = "page $n: ${DirPager.BOT_CHECK_TEXT}"
                    ended = "botcheck"
                } else {
                    reason = "page $n could not be read (${e.message ?: e.javaClass.simpleName})"
                    ended = "failed"
                }
                break
            }
            if (fetched.first >= 400) {
                reason = "the site answered ${fetched.first} for page $n"
                ended = if (done > 0 && (fetched.first == 404 || fetched.first == 410)) "end" else "failed"
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
            onProgress(DirPager.Progress(done, items.size, 0, 0, 0L))
            if (found.isEmpty()) {
                reason = "page $n has none of the list's entries"
                break
            }
            if (added == 0) {
                reason = "page $n repeated entries already read"
                break
            }
            if (n >= end) {
                reason = "read $count pages, the limit you set"
                ended = "limit"
                break
            }
            n++
            pageUrl = nextPage(doc, url, visited)
        }
        AppLog.add('I', "lab", "extras read directory: ${items.size} entries from $done pages ($reason)")
        return DirRead(items.values.toList(), done, reason, ended)
    }

    /**
     * KMK (r109): "Tap the picture on the page": the picture part from the owner's tap on one card. [selectors] are the
     * ways to reach the tapped picture inside its card (picker.js `pictureMode`: the steps down from the card, its own
     * tag and classes), [attrs] the attributes that held its address on the live page. The directory's first page is read
     * again and the selector + attribute that give a picture on the most cards (at least half) wins; the entries are read
     * again with it. Returns the new scan, or null and why
     */
    suspend fun withPicture(scan: DirScan, selectors: List<String>, attrs: List<String>): Pair<DirScan?, String> {
        val referer = Detector.normalize(scan.url) + "/"
        // KMK (r119): a bot check page says so (never solved)
        val fetched = try {
            fetch(scan.url, referer)
        } catch (e: DirPager.BotCheckFound) {
            return null to DirPager.BOT_CHECK_TEXT
        }
        if (fetched.first >= 400) return null to "the site answered ${fetched.first}"
        return withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(fetched.second, scan.url)
            val cards = runCatching { doc.select(scan.item) }.getOrNull().orEmpty().toList()
            if (cards.isEmpty()) return@withContext null to "the cards (${scan.item}) aren't in the page's HTML"
            val base = doc.baseUri().ifEmpty { scan.url }
            val tries = (attrs + "").distinct()
            var best: Triple<String, String, Int>? = null
            for (sel in selectors.distinct()) {
                for (attr in tries) {
                    val hits = cards.count { c -> pick(c, sel)?.let { e -> pictureIn(e, attr, base) } != null }
                    if (hits > (best?.third ?: 0)) best = Triple(sel, attr, hits)
                }
            }
            val found = best
            if (found == null || found.third * 2 < cards.size) {
                return@withContext null to if (found == null) {
                    "the page's HTML has no picture there (the site's script puts it in later)"
                } else {
                    "a picture on only ${found.third} of ${cards.size} cards"
                }
            }
            // the attribute that really held it on the first card that has one (pictureIn's own order otherwise)
            val attr = cards.firstNotNullOfOrNull { c -> pick(c, found.first)?.let { e -> pictureIn(e, found.second, base)?.first } }.orEmpty()
            val part = DirPart("picture", found.first, "", attr = attr)
            val parts = scan.parts.filter { it.kind != "picture" && it.kind != "follow" } + part
            val entries = cards.mapNotNull { entryOf(it, scan.shape, scan.link, parts) }.distinctBy { it.href }
            val example = (found.first.ifEmpty { "the card" }) + (if (attr.isNotEmpty()) " ($attr)" else "")
            val kept = scan.parts.filter { it.kind != "picture" }.let { old ->
                val at = old.indexOfFirst { it.kind == "follow" }
                if (at < 0) old + part.copy(example = example) else old.toMutableList().also { it.add(at, part.copy(example = example)) }
            }
            AppLog.add('I', "lab", "extras directory picture ${scan.url}: ${found.first.ifEmpty { "(card)" }} ${attr.ifEmpty { "-" }} on ${found.third} of ${cards.size} cards")
            scan.copy(parts = kept, entries = entries.ifEmpty { scan.entries }) to ""
        }
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
        // KMK (r109): no <img> with a picture (the owner's round faces): a <picture>'s <source>, a background-image or
        // data-bg on the card or inside it, an <img> inside <noscript>
        if (f.picture == null) {
            // KMK (r109) fix: the card itself only with a picture's address that isn't its own link (no fake part)
            val linkUrl = link.absUrl("href")
            fun real(el: Element): Boolean {
                val got = pictureOwn(el, "", base) ?: return false
                return el !== card || (looksPicture(got.second) && got.second != linkUrl)
            }
            val holders = listOf(card) + runCatching { card.select(PICTURE_HOLDERS) }.getOrNull().orEmpty()
            holders.firstOrNull { outside(it) && real(it) }?.let { el ->
                f.picture = el
                f.pictureAttr = pictureOwn(el, "", base)?.first.orEmpty()
            }
        }
        return f
    }

    private val PICTURE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src")

    // KMK (r109): more lazy-load attributes, a <source>'s, a background's and a poster; then "style" (background-image)
    // KMK (r109) fix: no data-url (a card's link); data-echo / data-image / data-img / data-thumb only with a picture's address
    private val MORE_PICTURE_ATTRS = listOf(
        "data-lazy", "data-original-src", "data-hi-res-src", "lazy-src", "data-echo", "data-image", "data-img",
        "data-bg", "data-background", "data-background-image", "data-bg-src", "data-thumb", "poster", "style",
    )

    // KMK (r109) fix: the attributes known to hold a picture; any other gives one only when its address looks like one
    private val PLAIN_PICTURE_ATTRS = PICTURE_ATTRS.toSet() + setOf(
        "style", "noscript", "data-lazy", "data-original-src", "data-hi-res-src", "lazy-src", "data-bg", "data-background",
        "data-background-image", "data-bg-src", "poster",
    )
    private val PICTURE_PATH = Regex("""\.(?:jpe?g|png|webp|gif|avif|bmp)$|/(?:img|image|thumb|cover|poster|avatar|photo|pic)""", RegexOption.IGNORE_CASE)

    /** KMK (r109) fix: a picture's address: a picture file's extension or a picture folder in its path */
    private fun looksPicture(url: String): Boolean =
        PICTURE_PATH.containsMatchIn(url.substringAfter("://").substringAfter('/', "").let { "/$it" }.substringBefore('?').substringBefore('#'))
    private const val PICTURE_HOLDERS =
        "picture source, [style*=background], [data-bg], [data-background], [data-background-image], [data-bg-src], noscript"

    // No braces in these patterns (ICU)
    private val BG_URL = Regex("""background(?:-image)?\s*:[^;]*url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val PICTURE_NAME = Regex("""src|img|image|bg|background|poster|thumb|avatar|photo""", RegexOption.IGNORE_CASE)
    private val IMAGE_FILE = Regex("""\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val NOSCRIPT_IMG = Regex("""<img[^>]+?(?:data-src|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER = Regex(
        """^(placeholder|blank|spacer|transparent|pixel|1x1|empty|lazy|lazyload|loading|loader|grey|gray|no[-_]?(image|img|cover|thumb|poster))([-_.](min|small|\d+x\d+))?\.(gif|png|jpe?g|webp|svg)$""",
    )

    /** An img's picture: (attribute, absolute address) from [first] then the lazy-load attributes, srcset and src */
    private fun pictureOf(img: Element, first: String, base: String): Pair<String, String>? {
        val names = (listOf(first) + PICTURE_ATTRS).filter { it.isNotEmpty() }.distinct()
        for (n in names) {
            var v = attrPicture(img, n)
            if (v.isEmpty()) continue
            v = if (n.contains("srcset")) v.split(Regex(""",\s+""")).first().trim().split(SPACES).first() else v.split(SPACES).first()
            if (v.isEmpty() || v.startsWith("data:", ignoreCase = true) || v.startsWith("about:", ignoreCase = true)) continue
            val file = v.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            if (PLACEHOLDER.matches(file)) continue
            val abs = base.toHttpUrlOrNull()?.resolve(v)?.toString() ?: v
            if (!abs.startsWith("http")) continue
            if (n !in PLAIN_PICTURE_ATTRS && !looksPicture(abs)) continue // KMK (r109) fix
            return n to abs
        }
        return null
    }

    /**
     * KMK (r109): an attribute's picture text: a background-image's url() for "style", a <noscript>'s <img> for
     * "noscript", else the attribute without the quotes a script binding puts around a literal ('https://…')
     */
    private fun attrPicture(el: Element, name: String): String = when (name) {
        "style" -> BG_URL.find(el.attr("style"))?.groupValues?.get(1)?.trim().orEmpty()
        "noscript" -> if (el.normalName() == "noscript") {
            NOSCRIPT_IMG.find(el.html().ifBlank { el.data() })?.groupValues?.get(1)?.trim().orEmpty()
        } else {
            ""
        }
        else -> el.attr(name).trim().removeSurrounding("'").removeSurrounding("\"")
    }

    /**
     * KMK (r109): an element's own picture (any element, not only an img): [first], the lazy-load attributes, srcset,
     * src, a background's, a poster, a background-image style, a noscript's img, then any attribute named like a
     * picture holding a picture file's address
     */
    private fun pictureOwn(el: Element, first: String, base: String): Pair<String, String>? {
        val names = (listOf(first) + PICTURE_ATTRS + MORE_PICTURE_ATTRS + "noscript").filter { it.isNotEmpty() }.distinct()
        pictureOf(el, "", base)?.takeIf { first.isEmpty() || it.first == first }?.let { return it }
        for (n in names) {
            val got = pictureFrom(el, n, base)
            if (got != null) return n to got
        }
        for (a in el.attributes()) {
            if (a.key in names || !PICTURE_NAME.containsMatchIn(a.key)) continue
            val v = a.value.trim().removeSurrounding("'").removeSurrounding("\"")
            if (!IMAGE_FILE.containsMatchIn(v.substringBefore(' '))) continue
            val got = pictureFrom(el, a.key, base) ?: continue
            return a.key to got
        }
        return null
    }

    /** One attribute's picture address (absolute, never a placeholder / data: address), null when none */
    private fun pictureFrom(el: Element, name: String, base: String): String? {
        var v = attrPicture(el, name)
        if (v.isEmpty()) return null
        v = if (name.contains("srcset")) v.split(Regex(""",\s+""")).first().trim().split(SPACES).first() else v.split(SPACES).first()
        if (v.isEmpty() || v.startsWith("data:", ignoreCase = true) || v.startsWith("about:", ignoreCase = true)) return null
        val file = v.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
        if (PLACEHOLDER.matches(file)) return null
        val abs = base.toHttpUrlOrNull()?.resolve(v)?.toString() ?: v
        // KMK (r109) fix: a loose attribute (data-image, data-thumb, a picked one…) only with a picture's address
        if (name !in PLAIN_PICTURE_ATTRS && !looksPicture(abs)) return null
        return abs.takeIf { it.startsWith("http") }
    }

    /**
     * KMK (r109): the picture of a part's element [e]: its own ([attr] first), else an img inside it, a <picture>'s
     * source, a noscript's img or a background inside it
     */
    private fun pictureIn(e: Element, attr: String, base: String): Pair<String, String>? {
        pictureOwn(e, attr, base)?.let { return it }
        e.select("img").firstNotNullOfOrNull { pictureOf(it, attr, base) }?.let { return it }
        return runCatching { e.select(PICTURE_HOLDERS) }.getOrNull().orEmpty().firstNotNullOfOrNull { pictureOwn(it, attr, base) }
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
        // KMK (r109): the part may be a background, a <source> or a wrapper (pictureIn looks inside it too)
        val picture = parts.firstOrNull { it.kind == "picture" }?.let { p ->
            pick(card, p.selector)?.let { e -> pictureIn(e, p.attr, card.baseUri())?.second }
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
    // KMK (r119): when the list shown was remembered (0 = read now)
    var cachedAt by remember { mutableLongStateOf(0L) }

    // KMK (r119): a list read in the last 7 days is shown at once ("Read 2 h ago · Refresh")
    LaunchedEffect(url) {
        val cached = withContext(Dispatchers.IO) { runCatching { DirCache.getList(url, 1) }.getOrNull() }
        if (cached != null && result == null && !running) {
            result = cached.first
            cachedAt = cached.second
            AppLog.add('I', "lab", "extras read list from $url: ${cached.first.items.size} entries remembered (${DirCache.agoText(cached.second)})")
        }
    }

    fun seconds(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    fun start() {
        stop.set(false)
        result = null
        cachedAt = 0L
        running = true
        val range = pause
        val count = pages.roundToInt()
        progress = "Reading page 1 of $count…"
        job = scope.launch {
            try {
                val read = BuilderListReader.read(
                    url = url,
                    maxPages = count,
                    pauseMinMs = (range.start * 1000f).roundToLong(),
                    pauseMaxMs = (range.endInclusive * 1000f).roundToLong(),
                    onProgress = { p -> progress = progressText(p, count) },
                    shouldStop = { stop.get() },
                )
                result = read
                withContext(Dispatchers.IO) { runCatching { DirCache.putList(url, read, count) } }
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
                    "Every page of this list is read, following its Next link (up to ${DirPager.DEFAULT_IN_FLIGHT} at a " +
                        "time when its pages are numbered, one at a time while the site asks for a slower pace). A random " +
                        "pause between them keeps the site from seeing a bot-like pace.",
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
                    // KMK (r119): a remembered list: when it was read, Refresh reads it again
                    if (cachedAt > 0L && !running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                DirCache.agoText(cachedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { start() }) { Text("Refresh") }
                        }
                    }
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

/** KMK (r119): "Reading pages 4–6 of 12…" (pages in flight), "Next page in 2.5 s" (a pause), with the entries so far */
internal fun progressText(p: DirPager.Progress, of: Int): String {
    val head = when {
        p.from > 0 && p.to > p.from -> "Reading pages ${p.from}–${p.to} of $of…"
        p.from > 0 -> "Reading page ${p.from} of $of…"
        p.pauseMs > 0 -> "Next page in ${String.format(java.util.Locale.US, "%.1f", p.pauseMs / 1000f)} s"
        else -> "${p.done} of $of pages read"
    }
    return "$head · ${p.entries} entries"
}

/** r103: a list longer than a dropdown reads well as a text box with suggestions */
private fun defaultForm(entries: Int): String = if (entries > 60) "autocomplete" else "select"
