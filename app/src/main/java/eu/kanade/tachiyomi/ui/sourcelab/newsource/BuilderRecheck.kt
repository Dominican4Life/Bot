package eu.kanade.tachiyomi.ui.sourcelab.newsource

import eu.kanade.tachiyomi.jssource.lab.Derive
import eu.kanade.tachiyomi.jssource.lab.PickCheck
import eu.kanade.tachiyomi.jssource.lab.sitecheck.PageFetch
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * KMK (r115, mockup EHelper2): Helper 2's re-run. The tap helper ([PickCheck.check]) judged a tap by the element
 * touched on one page as the WebView shows it; this runs the derived selectors again where the source will run them:
 * on the RAW page (the app's client, [PageFetch.raw]) the pick was made on, and on up to 3 titles (the tapped title
 * page + 2 other title links of the list). Each label gets a [PickCheck.Recheck] (found on N of M, matches in Raw vs
 * shown), which [PickCheck.row] turns into one checklist row.
 *
 * Pages are kept in [Pages] (by address, 8 at most) so a re-run after every pick costs no request; only a new title
 * or a new list page is fetched. At most 1 list + 3 titles + 1 chapter page per builder session in the usual case.
 */
internal object BuilderRecheck {

    /** What one run needs from the builder's state (copied, so the run never reads state that changes meanwhile) */
    data class Input(
        val derived: Map<String, String>,
        val raws: Map<String, BuilderScreenModel.RawPick>,
        val picked: Set<String>,
        val tagGroups: List<Derive.TagGroup>,
        val singleEpisode: Boolean,
    )

    /** The raw pages fetched in this builder session: address → document (null = it didn't load) */
    class Pages {
        private val docs = LinkedHashMap<String, Document?>()

        fun get(url: String): Document? = synchronized(docs) { docs[url] }

        fun has(url: String): Boolean = synchronized(docs) { url in docs }

        fun put(url: String, doc: Document?) = synchronized(docs) {
            docs[url] = doc
            while (docs.size > 8) docs.remove(docs.keys.first())
        }
    }

    private val LIST_LABELS = setOf("item", "title", "cover", "next")
    private val TITLE_LABELS = setOf("chapter", "date", "number", "bigCover", "description", "author", "status", "tags")

    /** The addresses one run reads: the list page, up to 3 title pages, the chapter page (null / empty when none) */
    data class Plan(val list: String?, val titles: List<String>, val chapter: String?)

    fun plan(input: Input): Plan {
        val list = LIST_LABELS.firstNotNullOfOrNull { input.raws[it]?.pageUrl?.takeIf { u -> u.isNotBlank() && it in input.picked } }
        val tapped = TITLE_LABELS.firstNotNullOfOrNull { label ->
            input.raws[label]?.pageUrl?.takeIf { it.isNotBlank() && (label in input.picked || label == "tags") }
        }
        val titleRaw = input.raws["title"] ?: input.raws["item"]
        val others = titleRaw?.hrefs.orEmpty()
            .mapNotNull { resolve(titleRaw?.pageUrl.orEmpty(), it) }
            .map { clean(it) }
        val titles = buildList {
            if (tapped != null) add(clean(tapped))
            others.forEach { if (size < 3 && it !in this) add(it) }
        }.takeIf { tapped != null || input.picked.any { it in TITLE_LABELS } || input.tagGroups.isNotEmpty() }.orEmpty()
        val chapter = listOf("image").firstNotNullOfOrNull { input.raws[it]?.pageUrl?.takeIf { u -> u.isNotBlank() && it in input.picked } }
        return Plan(list?.let { clean(it) }, titles, chapter?.let { clean(it) })
    }

    /** Fetches what [plan] needs and is not in [pages] yet, then checks every picked label */
    suspend fun run(input: Input, pages: Pages, referer: String): Map<String, PickCheck.Recheck> {
        val plan = plan(input)
        val wanted = listOfNotNull(plan.list) + plan.titles + listOfNotNull(plan.chapter)
        var fetched = 0
        for (url in wanted) {
            if (pages.has(url)) continue
            val raw = PageFetch.raw(url, referer)
            fetched++
            val doc = if (raw.ok && !raw.botCheck) {
                withContext(Dispatchers.Default) { runCatching { Jsoup.parse(raw.body, raw.url) }.getOrNull() }
            } else {
                AppLog.add('D', "lab", "helper2: $url not read (${raw.error ?: raw.code})")
                null
            }
            pages.put(url, doc)
        }
        val result = withContext(Dispatchers.Default) {
            check(input, plan.list?.let { pages.get(it) }, plan.titles.map { pages.get(it) }, plan.chapter?.let { pages.get(it) })
        }
        AppLog.add(
            'D',
            "lab",
            "helper2: ${result.size} picks re-run on ${if (plan.list != null) "the list + " else ""}${plan.titles.size} titles" +
                "${if (plan.chapter != null) " + a chapter" else ""} ($fetched fetched): " +
                result.values.joinToString(", ") { "${it.label} ${it.found}/${it.tested}${if (it.raw >= 0) " raw ${it.raw}/${it.shown}" else ""}" },
        )
        return result
    }

    /** The checks themselves, on documents already read (pure: same pages, same answer) */
    fun check(input: Input, list: Document?, titleDocs: List<Document?>, chapter: Document?): Map<String, PickCheck.Recheck> {
        val out = LinkedHashMap<String, PickCheck.Recheck>()
        val d = input.derived
        fun shown(label: String): Int = input.raws[label]?.count ?: -1

        // ---- the list page (raw), one page: matches per card
        if (list != null) {
            val itemSel = d["itemSelector"].orEmpty()
            val cards: List<Element> = sel(list, itemSel)
            if ("item" in input.picked) {
                out["item"] = PickCheck.Recheck("item", tested = 1, found = if (cards.isNotEmpty()) 1 else 0, raw = cards.size, shown = shown("item"), what = "list page")
            }
            if ("title" in input.picked && cards.isNotEmpty()) {
                val titleSel = d["titleSelector"].orEmpty().ifBlank { "a" }
                val ok = cards.count { card -> first(card, titleSel)?.let { a -> a.absUrl("href").isNotBlank() || a.selectFirst("a[href]") != null } == true }
                out["title"] = PickCheck.Recheck("title", tested = cards.size, found = ok, raw = ok, shown = shown("title"), what = "cards")
            } else if ("title" in input.picked) {
                out["title"] = PickCheck.Recheck("title", tested = 1, found = 0, raw = 0, shown = shown("title"), what = "list page")
            }
            if ("cover" in input.picked && cards.isNotEmpty()) {
                out["cover"] = coverCheck(cards, d["coverSelector"].orEmpty().ifBlank { "img" }, d["coverAttr"].orEmpty().ifBlank { "src" }, shown("cover"))
            }
            if ("next" in input.picked) {
                val n = sel(list, d["nextSelector"].orEmpty()).count { it.absUrl("href").isNotBlank() || it.selectFirst("a[href]") != null }
                out["next"] = PickCheck.Recheck("next", tested = 1, found = if (n > 0) 1 else 0, raw = n, shown = shown("next"), what = "list page")
            }
        }

        // ---- the title pages (raw), up to 3
        val titles = titleDocs.filterNotNull()
        if (titles.isNotEmpty()) {
            val first = titleDocs.firstOrNull()
            fun perTitle(label: String, selector: String, ok: (List<Element>) -> Boolean = { it.isNotEmpty() }, note: (List<List<Element>>) -> String = { "" }) {
                if (label !in input.picked || selector.isBlank()) return
                val found = titles.map { sel(it, selector) }
                val raw = if (first != null) sel(first, selector).size else -1
                out[label] = PickCheck.Recheck(
                    label,
                    tested = titles.size,
                    found = found.count(ok),
                    raw = raw,
                    shown = shown(label),
                    note = note(found),
                )
            }
            if (!input.singleEpisode) perTitle("chapter", d["chapterSelector"].orEmpty())
            // Date / Number: the element tapped, anywhere on the page (their derived selectors are relative to a row)
            perTitle("date", input.raws["date"]?.likeSelector.orEmpty())
            perTitle("number", input.raws["number"]?.likeSelector.orEmpty())
            perTitle("description", d["descriptionSelector"].orEmpty(), ok = { els -> els.any { it.text().trim().length >= 20 } }) { found ->
                val lengths = found.mapNotNull { els -> els.firstOrNull()?.text()?.trim()?.length?.takeIf { it > 0 } }
                if (lengths.isEmpty()) "" else if (lengths.min() == lengths.max()) "${lengths.min()} characters" else "${lengths.min()}–${lengths.max()} characters"
            }
            perTitle("author", d["authorSelector"].orEmpty(), ok = { els -> els.any { it.text().isNotBlank() } })
            perTitle("status", d["statusSelector"].orEmpty(), ok = { els -> els.any { it.text().isNotBlank() } })
            if ("bigCover" in input.picked) {
                val coverSel = d["detailsCover"].orEmpty()
                if (coverSel.isNotBlank()) {
                    val attr = d["detailsCoverAttr"].orEmpty().ifBlank { "src" }
                    out["bigCover"] = coverCheck(titles.mapNotNull { first(it, coverSel) }, null, attr, -1)
                        .let { c -> c.copy(label = "bigCover", tested = titles.size, what = "titles", raw = if (first != null) sel(first, coverSel).size else -1, shown = shown("bigCover")) }
                }
            }
            // Tags: every group's row on every title; a group missing on some titles is kept as optional
            val groups = input.tagGroups.filter { it.selector.isNotBlank() }
            if (groups.isNotEmpty()) {
                val perGroup = groups.map { g -> titles.count { doc -> sel(doc, g.selector).isNotEmpty() } }
                val onFirst = if (first != null) groups.count { g -> sel(first, g.selector).isNotEmpty() } else -1
                val missing = groups.indices.filter { perGroup[it] < titles.size }
                val split = groups.indexOfFirst { Derive.needsSplit(it) }
                val note = when {
                    missing.isEmpty() -> "${groups.size} ${if (groups.size == 1) "row" else "rows"}"
                    else -> missing.take(2).joinToString(", ") { "“${groups[it].name}” on ${perGroup[it]} of ${titles.size}" } +
                        " (kept as optional)"
                }
                out["tags"] = PickCheck.Recheck(
                    "tags",
                    tested = titles.size,
                    // a title counts when it has at least one of the rows (the others are optional)
                    found = titles.count { doc -> groups.any { g -> sel(doc, g.selector).isNotEmpty() } },
                    raw = onFirst,
                    shown = groups.size,
                    note = note,
                    fix = if (split >= 0) PickCheck.Fix(PickCheck.FixKind.SplitRows, "Split into rows", split.toString()) else null,
                )
            }
        }

        // ---- the chapter page (raw): manga page pictures
        if (chapter != null && "image" in input.picked) {
            val n = sel(chapter, d["imageSelector"].orEmpty().ifBlank { "img" }).size
            out["image"] = PickCheck.Recheck("image", tested = 1, found = if (n > 0) 1 else 0, raw = n, shown = shown("image"), what = "chapter page")
        }
        return out
    }

    /**
     * Covers: how many of [els] (cards, or the pictures themselves when [selector] is null) give a real picture address
     * from [attr]; when most don't (a placeholder `src`), the attribute that does is offered as the fix
     */
    private fun coverCheck(els: List<Element>, selector: String?, attr: String, shown: Int): PickCheck.Recheck {
        // a picked box (a <picture>, a link around the image) is read through its first <img> when it has no address itself
        val pictures = els.mapNotNull { if (selector == null) it else first(it, selector) }
            .map { p -> if (p.normalName() != "img" && p.attr(attr).isBlank()) p.selectFirst("img") ?: p else p }
        val good = pictures.count { real(it, attr) }
        val tested = els.size
        if (tested > 0 && good * 5 < tested * 4) {
            val other = Derive.COVER_ATTRS.filter { it != attr }.maxByOrNull { a -> pictures.count { real(it, a) } }
            val otherGood = other?.let { a -> pictures.count { real(it, a) } } ?: 0
            if (other != null && otherGood * 5 >= tested * 4) {
                return PickCheck.Recheck(
                    "cover",
                    tested = tested,
                    found = good,
                    raw = pictures.size,
                    shown = shown,
                    what = "covers",
                    note = "$attr is a placeholder in Raw, $other has the picture",
                    fix = PickCheck.Fix(PickCheck.FixKind.UseAttr, "Read $other", other),
                )
            }
        }
        return PickCheck.Recheck(
            "cover",
            tested = tested,
            found = good,
            raw = pictures.size,
            shown = shown,
            what = "covers",
            note = "reads $attr",
        )
    }

    /** The attribute holds a picture address (not empty, not a data: placeholder, not a 1×1 gif) */
    private fun real(el: Element, attr: String): Boolean {
        val v = el.attr(attr).trim().let { if (attr == "srcset") it.substringBefore(' ').substringBefore(',') else it }
        if (v.isEmpty() || v.startsWith("data:") || v == "#" || "blank.gif" in v || "placeholder" in v.lowercase()) return false
        return el.absUrl(attr).isNotBlank() || v.startsWith("http") || v.startsWith("//") || v.startsWith("/")
    }

    private fun sel(root: Element, selector: String): List<Element> {
        if (selector.isBlank()) return emptyList()
        return runCatching { root.select(selector).toList() }.getOrDefault(emptyList())
    }

    private fun first(root: Element, selector: String): Element? =
        runCatching { root.selectFirst(selector) }.getOrNull()

    private fun resolve(base: String, href: String): String? {
        if (href.isBlank()) return null
        return base.toHttpUrlOrNull()?.resolve(href)?.toString() ?: href.toHttpUrlOrNull()?.toString()
    }

    private fun clean(url: String): String = url.substringBefore('#')
}
