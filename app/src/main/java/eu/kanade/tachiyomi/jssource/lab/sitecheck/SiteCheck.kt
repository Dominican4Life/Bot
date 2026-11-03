package eu.kanade.tachiyomi.jssource.lab.sitecheck

import android.content.Context
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.jssource.lab.DetectionResult
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.lab.FingerprintFile
import eu.kanade.tachiyomi.jssource.lab.Fingerprints
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * KMK (r115, mockup ESiteCheck): "Check the site" on the New-source screen. Before anything is tapped, the Lab looks at
 * the address the owner pasted the two ways a source can read it and says which way will work:
 *
 * 1. the app's client ([PageFetch.raw]: what a source gets; a bot check shows here),
 * 2. the hidden browser ([PageFetch.rendered]: the page after its scripts, and the requests it made),
 * 3. raw vs rendered: the title links each holds ([SiteRead.cards], the builder's own list reading),
 * 4. the site's own data requests (fetch / XHR seen while rendering, up to [MAX_DATA] fetched again with the app's
 *    client and read as JSON: the largest list of objects in them),
 * 5. the next page (rel=next / a page-2 link, [SiteRead.nextPage]) and whether the raw page 2 has new titles,
 * 6. embedded data (`__NEXT_DATA__`, Nuxt, a store's first state),
 * 7. a known engine ([Detector], the fingerprints the old "Detect" used; at most 3 more requests).
 *
 * From that, "The plan": write from a template (engine ≥ 60 %), read the site's data, read with the hidden browser, or
 * read the plain HTML, each marked works / won't work with one sentence why. At most 1 + 1 + [MAX_DATA] + 1 + 3
 * requests to the site; nothing is saved.
 */
internal object SiteCheck {

    private const val MAX_DATA = 3
    private const val LIST_MIN = 5

    enum class Mark { Ok, Warn, Fail, Running, Skipped }

    @Immutable
    data class Item(val id: String, val mark: Mark, val title: String, val note: String = "", val figure: String = "")

    enum class PlanKind { Template, Data, Browser, Html }

    @Immutable
    data class Plan(
        val kind: PlanKind,
        val title: String,
        val body: String,
        val chips: List<String> = emptyList(),
        val works: Boolean,
        val recommended: Boolean = false,
    )

    @Immutable
    data class DataRequest(val url: String, val short: String, val list: SiteRead.DataList)

    @Immutable
    data class Result(
        val url: String,
        val items: List<Item>,
        val running: Boolean,
        val plans: List<Plan> = emptyList(),
        val rawCards: Int = -1,
        val renderedCards: Int = -1,
        val rawBytes: Int = 0,
        val renderedBytes: Int = 0,
        val dataRequests: List<DataRequest> = emptyList(),
        val embedded: List<String> = emptyList(),
        val nextPage: String? = null,
        val botCheck: Boolean = false,
        val detection: DetectionResult? = null,
        val progress: Float = 0f,
    ) {
        /** What still needs the owner: the Warn / Fail lines */
        val left: List<Item> get() = items.filter { it.mark == Mark.Warn || it.mark == Mark.Fail }
        val best: Plan? get() = plans.firstOrNull { it.recommended } ?: plans.firstOrNull { it.works }
    }

    /** The address as typed, with https:// added (the path is kept: a list page checks best) */
    fun address(input: String): String {
        val t = input.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    suspend fun run(
        context: Context,
        input: String,
        kindHint: Boolean?,
        fingerprints: FingerprintFile?,
        onUpdate: (Result) -> Unit,
    ): Result = coroutineScope {
        val url = address(input)
        val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val items = LinkedHashMap<String, Item>()
        var result = Result(url = url, items = emptyList(), running = true)
        fun show(item: Item, progress: Float) {
            items[item.id] = item
            result = result.copy(items = items.values.toList(), progress = progress)
            onUpdate(result)
        }
        AppLog.add('I', "lab", "Site check: $url")
        val started = System.currentTimeMillis()

        // 1 + 2: the app's client and the hidden browser at the same time
        show(Item("load", Mark.Running, "Loading the page with the app's connection"), 0.05f)
        show(Item("render", Mark.Running, "Opening it in the hidden browser"), 0.08f)
        val renderJob = async { PageFetch.rendered(context, url) }
        val raw = PageFetch.raw(url, referer = null)
        val rawDoc: Document? = if (raw.error == null && raw.body.isNotBlank()) {
            withContext(Dispatchers.Default) { runCatching { Jsoup.parse(raw.body, raw.url) }.getOrNull() }
        } else {
            null
        }
        val seconds = String.format(java.util.Locale.ROOT, "%.1f s", raw.ms / 1000.0)
        when {
            raw.error != null -> show(Item("load", Mark.Fail, "The page didn't load", raw.error), 0.2f)
            raw.botCheck -> show(
                Item("load", Mark.Fail, "The site answers with a bot check", "Open it in WebView, pass the check, then check again", seconds),
                0.2f,
            )
            raw.code >= 400 -> show(Item("load", Mark.Fail, "The site answered ${raw.code}", raw.url, seconds), 0.2f)
            else -> show(Item("load", Mark.Ok, "Page loads without a bot check", PageFetch.size(raw.body.length.toLong()), seconds), 0.2f)
        }
        result = result.copy(botCheck = raw.botCheck, rawBytes = raw.body.length)

        val rendered = renderJob.await()
        val renderedDoc: Document? = if (rendered.error == null && rendered.html.isNotBlank()) {
            withContext(Dispatchers.Default) { runCatching { Jsoup.parse(rendered.html, rendered.url) }.getOrNull() }
        } else {
            null
        }
        if (rendered.error != null) {
            show(Item("render", Mark.Warn, "The hidden browser couldn't open it", rendered.error), 0.45f)
        } else {
            val note = if (rendered.timedOut) "still loading after 20 s: what it showed by then" else "${rendered.requests.size} requests"
            show(
                Item("render", Mark.Ok, "Opened it in the hidden browser", note, String.format(java.util.Locale.ROOT, "%.1f s", rendered.ms / 1000.0)),
                0.45f,
            )
        }
        result = result.copy(renderedBytes = rendered.html.length)

        // 3: raw vs rendered
        val rawCards = rawDoc?.let { withContext(Dispatchers.Default) { SiteRead.cards(it, raw.url) } }
        val renderedCards = renderedDoc?.let { withContext(Dispatchers.Default) { SiteRead.cards(it, rendered.url) } }
        val rawN = rawCards?.count ?: 0
        val renN = renderedCards?.count ?: 0
        val sizes = "Raw ${PageFetch.size(raw.body.length.toLong())} · Rendered ${if (renderedDoc != null) PageFetch.size(rendered.html.length.toLong()) else "—"}"
        val compare = when {
            rawN >= LIST_MIN && (renN == 0 || rawN * 5 >= renN * 3) ->
                Item("compare", Mark.Ok, "What a source gets is what you see", "Raw page: $rawN title links · Rendered: $renN", sizes)
            renN >= LIST_MIN && rawN < LIST_MIN ->
                Item("compare", Mark.Warn, "The list is built by the site's scripts", "Raw page: $rawN title links · Rendered: $renN", sizes)
            rawN >= LIST_MIN ->
                Item("compare", Mark.Warn, "Scripts add titles after the page loads", "Raw page: $rawN title links · Rendered: $renN", sizes)
            else -> Item(
                "compare",
                Mark.Warn,
                "No list of titles found on this page",
                "Paste a list page (latest, popular, a genre) and check again",
                sizes,
            )
        }
        show(compare, 0.55f)
        result = result.copy(rawCards = rawN, renderedCards = renN)

        // 4: the site's own data requests
        val candidates = SiteRead.dataCandidates(rendered.requests, url).take(MAX_DATA)
        val found = ArrayList<DataRequest>()
        if (candidates.isEmpty()) {
            show(Item("data", Mark.Skipped, "No data requests seen", if (renderedDoc == null) "the hidden browser showed nothing" else "the page asks for no JSON"), 0.7f)
        } else {
            show(Item("data", Mark.Running, "Reading ${candidates.size} data ${if (candidates.size == 1) "request" else "requests"}"), 0.6f)
            for (req in candidates) {
                val reply = PageFetch.raw(req.url, referer = url)
                if (!reply.ok || !reply.json) continue
                val list = withContext(Dispatchers.Default) { SiteRead.dataList(reply.body) } ?: continue
                found.add(DataRequest(req.url, SiteRead.shortAddress(req.url, url), list))
            }
            val top = found.maxByOrNull { it.list.items }
            if (top != null) {
                val roles = if (top.list.roles.isEmpty()) "fields ${top.list.keys.take(4).joinToString(", ")}" else "with " + top.list.roles.joinToString(", ")
                show(Item("data", Mark.Ok, "Found the site's own data request", "${top.short} → ${top.list.items} items $roles"), 0.7f)
            } else {
                show(Item("data", Mark.Skipped, "Data requests seen, none lists titles", candidates.first().url.take(80)), 0.7f)
            }
        }
        result = result.copy(dataRequests = found.sortedByDescending { it.list.items })

        // 5: the next page
        val nextDoc = rawDoc ?: renderedDoc
        val next = nextDoc?.let { SiteRead.nextPage(it, if (rawDoc != null) raw.url else rendered.url) }
        if (next == null) {
            show(Item("next", Mark.Warn, "No next-page link found", "rel=next, “Next” or a page-2 link"), 0.8f)
        } else {
            val page2 = PageFetch.raw(next, referer = url)
            val shape = rawCards?.shape ?: renderedCards?.shape
            val page2Doc = if (page2.ok) withContext(Dispatchers.Default) { runCatching { Jsoup.parse(page2.body, page2.url) }.getOrNull() } else null
            val cards2 = page2Doc?.let { withContext(Dispatchers.Default) { SiteRead.cards(it, page2.url, shape) } }
            val before = (rawCards?.hrefs.orEmpty() + renderedCards?.hrefs.orEmpty()).toSet()
            val fresh = cards2?.hrefs?.count { it !in before } ?: 0
            val short = SiteRead.shortAddress(next, url)
            when {
                !page2.ok -> show(Item("next", Mark.Warn, "Next page: $short", "it didn't load (${page2.error ?: page2.code})"), 0.8f)
                fresh > 0 -> show(Item("next", Mark.Ok, "Next page: $short", "tried, $fresh new titles"), 0.8f)
                else -> show(Item("next", Mark.Warn, "Next page: $short", "tried: the raw page 2 shows no new titles"), 0.8f)
            }
        }
        result = result.copy(nextPage = next)

        // 6: embedded data
        val embedded = (rawDoc ?: renderedDoc)?.let { SiteRead.embedded(it) }.orEmpty()
        if (embedded.isEmpty()) {
            show(Item("embedded", Mark.Skipped, "No embedded data"), 0.85f)
        } else {
            show(Item("embedded", Mark.Ok, "Embedded data: ${embedded.first()}", embedded.drop(1).joinToString(" · ")), 0.85f)
        }
        result = result.copy(embedded = embedded)

        // 7: a known engine (the fingerprints the old Detect used)
        var detection: DetectionResult? = null
        if (raw.ok && !raw.botCheck) {
            show(Item("engine", Mark.Running, "Comparing with the engines the Lab knows"), 0.9f)
            detection = try {
                Detector(context, fingerprints ?: Fingerprints.load()).detect(url, kindHint) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('W', "lab", "Site check: engine detection failed: ${e.message}")
                null
            }
            val best = detection?.best
            when {
                best != null && detection?.writeOffered == true ->
                    show(Item("engine", Mark.Ok, "Engine: ${best.engineName.substringBefore(" (")}", "a template fits", "${best.confidence} %"), 1f)
                best != null -> show(
                    Item("engine", Mark.Skipped, "No known engine", "closest: ${best.engineName.substringBefore(" (")} ${best.confidence} %"),
                    1f,
                )
                else -> show(Item("engine", Mark.Skipped, "No known engine"), 1f)
            }
        } else {
            show(Item("engine", Mark.Skipped, "Engine not compared", "the page didn't load"), 1f)
        }

        val plans = plans(rawN, renN, renderedDoc != null, found, embedded, detection, next != null, raw.botCheck)
        result = result.copy(plans = plans, detection = detection, running = false, progress = 1f)
        onUpdate(result)
        AppLog.add(
            'I',
            "lab",
            "Site check of $host in ${System.currentTimeMillis() - started} ms: raw $rawN / rendered $renN title links, " +
                "${found.size} data lists, next ${next != null}, embedded ${embedded.size}, engine ${detection?.best?.engineId ?: "none"}" +
                " → plan ${result.best?.kind ?: "none"}",
        )
        result
    }

    /** "The plan": every way of reading the site, in the order the Lab suggests them */
    fun plans(
        rawN: Int,
        renN: Int,
        rendered: Boolean,
        data: List<DataRequest>,
        embedded: List<String>,
        detection: DetectionResult?,
        hasNext: Boolean,
        botCheck: Boolean,
    ): List<Plan> {
        val htmlWorks = rawN >= LIST_MIN
        val browserWorks = rendered && renN >= LIST_MIN
        val dataWorks = data.isNotEmpty() || embedded.any { it.startsWith("__NEXT_DATA__") || it.startsWith("__NUXT__") }
        val engine = detection?.takeIf { it.writeOffered }?.best
        val out = ArrayList<Plan>()
        if (engine != null) {
            out.add(
                Plan(
                    PlanKind.Template,
                    "Write it from the ${engine.engineName.substringBefore(" (")} template",
                    "${engine.markers.count { it.matched }} of ${engine.markers.size} markers matched (${engine.confidence} %): the template already reads lists, " +
                        "titles and chapters of this engine.",
                    listOf("${engine.confidence} %", "no tapping"),
                    works = true,
                ),
            )
        }
        val top = data.firstOrNull()
        out.add(
            Plan(
                PlanKind.Data,
                "Read the site's data",
                when {
                    top != null -> "The list comes from ${top.short} as JSON (${top.list.items} items" +
                        (if (top.list.roles.isNotEmpty()) " with ${top.list.roles.joinToString(", ")}" else "") +
                        "). Fast and works even when the cards are made by scripts. Build by tapping reads HTML, so " +
                        "use the json-api / nextjs template or Record and convert."
                    dataWorks -> "The page carries its data (${embedded.first()}). Use the nextjs or json-api template."
                    else -> "No data request or embedded data was found on this page."
                },
                buildList {
                    if (top != null) add("${top.list.items} / page")
                    if (hasNext) add("next page")
                    if (embedded.isNotEmpty()) add(embedded.first().substringBefore(" ("))
                },
                works = dataWorks,
            ),
        )
        out.add(
            Plan(
                PlanKind.Browser,
                "Read the page with the hidden browser",
                when {
                    browserWorks && !htmlWorks -> "The hidden browser shows $renN titles. Build by tapping works on that page; " +
                        "the code then needs host.render for the list (about 2 s a page)."
                    browserWorks -> "Works too, slower (about 2 s a page). Use it if the plain page misses parts."
                    !rendered -> "The hidden browser couldn't open the page."
                    else -> "The hidden browser shows no list either."
                },
                works = browserWorks,
            ),
        )
        out.add(
            Plan(
                PlanKind.Html,
                "Read the plain HTML",
                when {
                    htmlWorks -> "The raw page already has $rawN title links: Build by tapping writes a source that reads it."
                    botCheck -> "Won't work yet: the app gets a bot check. Pass it in WebView first."
                    else -> "Won't work here: the raw page has ${if (rawN == 0) "no" else "only $rawN"} title links."
                },
                listOf("no code", "Build by tapping"),
                works = htmlWorks,
            ),
        )
        // Which one the Lab suggests: a fitting engine, else HTML when it works, else the data, else the browser
        val pick = when {
            engine != null -> PlanKind.Template
            htmlWorks -> PlanKind.Html
            dataWorks -> PlanKind.Data
            browserWorks -> PlanKind.Browser
            else -> null
        }
        return out.map { it.copy(recommended = it.kind == pick) }
            .sortedWith(compareByDescending<Plan> { it.recommended }.thenByDescending { it.works })
    }
}
