package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
 */
@Composable
internal fun ReadListDialog(
    url: String,
    onDismiss: () -> Unit,
    onDone: (BuilderListReader.Result) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pause by remember { mutableStateOf(BuilderListReader.defaultPause()) }
    var pages by remember { mutableFloatStateOf(20f) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<BuilderListReader.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val stop = remember { AtomicBoolean(false) }

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
        title = { Text("Read the whole list") },
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
                    }
                }
            }
        },
        confirmButton = {
            when {
                running -> TextButton(onClick = { stop.set(true) }) { Text("Stop") }
                done != null && done.items.isNotEmpty() -> TextButton(onClick = { onDone(done) }) {
                    Text(
                        if (done.items.size > BuilderExtrasLogic.MAX_GROUP_OPTIONS) {
                            "Make a filter (first ${BuilderExtrasLogic.MAX_GROUP_OPTIONS} of ${done.items.size})"
                        } else {
                            "Make a filter (${done.items.size})"
                        },
                    )
                }
                else -> TextButton(onClick = { start() }) { Text(if (done == null) "Start" else "Read again") }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }) { Text(if (running) "Cancel" else "Close") }
        },
    )
}
