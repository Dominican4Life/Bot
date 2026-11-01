package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.bool
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/*
 * KMK (r100, Source Lab): "Lists and options" of Build by tapping: the search address recorded from a real search
 * on the site, what Popular / Latest show and their names, extra tabs, search filters offered from address changes,
 * and settings, all without writing code. The values go into the generic-list template (v3) as placeholders:
 * paths override Derive's guesses, the lists are JSON text the template parses (`tabsList`, `filtersList`,
 * `settingsList`; never a name ending in `Json`, which Generator fills from its twin).
 *
 * r103: "Pick a menu on the site" (picker.js `menuMode`): one tap gives the site's menu groups ([MenuGroup]: a
 * heading and its entries, sub-headings as their own groups); each group's card makes its own filter, learns its
 * entries' addresses, or fetches its index page's full list, which becomes a Dropdown or a Text box with
 * suggestions (`type: "autocomplete"`, generic-list v6).
 *
 * r104 (the owner's 123av Browse sheet showed only "Trending" of his 5 filters, and Trending searched /en/search?sort=):
 * a filter without a usable address is never dropped silently ([BuilderExtrasLogic.filterProblem]: the editor shows
 * it in red, the write logs it); a param filter made from links keeps the links' own address ([ExtrasFilter.linkPath],
 * `/en/trending?sort={value}&page={page}`) and is written as a path filter when that page isn't the search page
 * ([BuilderExtrasLogic.filtersJson] with the search path); filters can be moved up / down ([BuilderExtrasModel.moveFilter]).
 */

@Immutable
internal data class ExtrasTab(val name: String, val path: String)

@Immutable
internal data class ExtrasOption(val label: String, val value: String)

@Immutable
internal data class ExtrasFilter(
    val key: String,
    val name: String,
    val type: String, // select|sort|checkbox|text; r103: autocomplete (a text box with the options as suggestions)
    val options: List<ExtrasOption> = emptyList(),
    val onValue: String = "1",
    /** The options as the editor shows them, one `Label = value` per line ([options] is parsed from it) */
    val optionsText: String = BuilderExtrasLogic.formatOptions(options),
    /** r101: "param" = the value goes in the address as [param]=value; "path" = it replaces `{value}` in [path] */
    val mode: String = "param",
    val param: String = key,
    val path: String = "",
    /** r101: made without an address parameter (by hand, or from entries without links): the editor asks for it */
    val askParam: Boolean = false,
    /**
     * r104: a param filter made from links: the links' own address with `{value}` for the parameter
     * (`/en/trending?sort={value}&page={page}`); written as a path filter when it isn't the search page. "" = none
     */
    val linkPath: String = "",
    /**
     * r104 fix: restored from a build saved before r104 (no `linkPath` saved) and made from a menu's links (a param
     * filter with its options): its links' own page is unknown, so it may search the wrong page (the owner's
     * Trending → /en/search?sort=today). The editor says to make it again from the menu ([BuilderExtrasLogic.OLD_LINKS])
     */
    val noLinkAddress: Boolean = false,
)

/**
 * r101: how the site numbers its pages, read from the owner's Next pick: `query` (`?name=2`), `pathWord`
 * (`/name/2`) or `pathEnd` (`/2` after the list's own path)
 */
@Immutable
internal data class PageRule(val kind: String, val name: String) {
    val shown: String
        get() = when (kind) {
            "query" -> "?$name={page}"
            "pathWord" -> "/$name/{page}"
            else -> "/{page}"
        }
}

/** r101: an entry the owner tapped opens a page whose whole list can be read ("Read the whole list") */
@Immutable
internal data class ListOffer(val url: String, val text: String)

@Immutable
internal data class ExtrasCustomSetting(
    val key: String,
    val name: String,
    val type: String, // switch|list|text
    val options: List<String> = emptyList(),
    /** A list's entries as the editor shows them, one per line ([options] is parsed from it) */
    val optionsText: String = options.joinToString("\n"),
)

@Immutable
internal data class FilterOffer(val param: String, val seen: List<String>, val path: String)

/** r103: one entry of a menu group (picker.js `menuPick`): its text, link, value and exact css path */
@Immutable
internal data class MenuItem(val text: String, val href: String?, val value: String?, val path: String?)

/**
 * r103: a menu group of the site as its card shows it: [name] (the heading, cleaned: "Year: All ▾" → "Year"; "" when
 * the list has no heading: the filter is then named by its entries, e.g. "Sort"), the
 * headings above it ([headingPath]), the css path of the element that opens it ([opener]), its own link ([href]: an
 * index page whose full list can be fetched), its [items], the page it was picked on, the `<select name>` when it is
 * one ([param]); [made] = the key of the filter made from it
 */
@Immutable
internal data class MenuGroup(
    val id: Int,
    val name: String,
    val headingPath: List<String>,
    val opener: String?,
    val href: String?,
    val index: Boolean,
    val items: List<MenuItem>,
    val pageUrl: String,
    val param: String? = null,
    val made: String? = null,
) {
    /** Every entry is a button / text without a link: "Learn addresses" can find where each goes */
    val noLinks: Boolean get() = items.isNotEmpty() && items.none { !it.href.isNullOrBlank() }
}

/** r102: one entry of a picked list, for "Learn this list": its text, its css path when the page sent one, its link */
@Immutable
internal data class LearnEntry(val text: String, val path: String?, val href: String?)

/**
 * r102: the last list picked for a filter, kept so "Learn this list" can press its entries: the page it was picked on,
 * the css path of its menu's button ([opener], from picker.js), the entries' look-alike selector, the filter's name
 */
@Immutable
internal data class LearnGroup(
    val pageUrl: String,
    val opener: String?,
    val likeSelector: String,
    val name: String,
    val entries: List<LearnEntry>,
    /** Some entries are links: the card offers "Check where each entry goes" instead of "Learn this list" */
    val hasLinks: Boolean,
)

/**
 * r102: an entry of a learning run: [status] waiting|pressing|learned|nochange|missing|filled|missed; [url] where it
 * went. r102 fix: "missing" = learn.js didn't find the entry on the page (never made the filter's "Any")
 */
@Immutable
internal data class LearnRow(val text: String, val url: String?, val status: String)

/** r102: a "Learn this list" run as the card shows it */
@Immutable
internal data class LearnRun(
    val name: String,
    val rows: List<LearnRow>,
    val running: Boolean,
    /** The pattern found ("year=<text>", "/en/<text>"), or null */
    val pattern: String?,
    val note: String?,
    val pressed: Int,
    val pauseMinMs: Long,
    val pauseMaxMs: Long,
)

@Immutable
internal data class BuilderExtras(
    val searchWord: String = "test",
    val searchPath: String? = null,
    val popularPath: String? = null,
    val popularName: String = "",
    val latestPath: String? = null,
    val latestName: String = "",
    val tabs: List<ExtrasTab> = emptyList(),
    val filters: List<ExtrasFilter> = emptyList(),
    val qualitySetting: Boolean = false,
    val serverSetting: Boolean = false,
    val hideWordsSetting: Boolean = false,
    val custom: List<ExtrasCustomSetting> = emptyList(),
    val offers: List<FilterOffer> = emptyList(),
    val optionsFor: Map<String, List<ExtrasOption>> = emptyMap(),
    val lastUrl: String? = null,
    val message: String? = null,
    /**
     * Bumped whenever the model itself changes what a text field shows (a recorded search, "Use this page", options
     * from the page, a row added or removed), never by typing: the pane's fields re-read their text when it changes
     */
    val rev: Int = 0,
    /** The address the search was recorded from (the "Recorded ✓" line) */
    val searchFrom: String? = null,
    /** r101: [searchPath] came from the site's search box (a real search recorded later replaces it) */
    val searchFromForm: Boolean = false,
    /** r101: the page numbering read from the Next pick (null: the `page={page}` guess) */
    val pageRule: PageRule? = null,
    /** r101: "Pick a list on the site" is waiting for a tap (BuilderScreenModel sends it to the page) */
    val groupMode: Boolean = false,
    /** r101: the last tapped entry that opens a list page */
    val listOffer: ListOffer? = null,
    /** r102: the last list picked for a filter ("Learn this list" presses its entries), null when there is none */
    val learnGroup: LearnGroup? = null,
    /** r102: the current or last "Learn this list" run */
    val learn: LearnRun? = null,
    /** r102: the owner tapped a menu's button ("Year: All ▾") instead of one of its entries: the card says so */
    val menuHint: Boolean = false,
    /** r103: the groups of the last menu picked on the site ("Pick a menu on the site"), one card each */
    val menuGroups: List<MenuGroup> = emptyList(),
    /**
     * r103 fix: the menu hint card's text when it isn't the r102 "menu's button" one (picker.js `hint`: a dropdown
     * whose menu the site adds only when it opens); null = the r102 text
     */
    val menuHintText: String? = null,
)

/** Pure functions of "Lists and options", kept apart from the model so they can be checked on their own */
internal object BuilderExtrasLogic {

    const val NO_PAGE_MESSAGE = "This page has no page number in its address: the Lab will try ?page=2 for more"

    // No braces in these patterns (ICU)
    private val PAGE_QUERY = Regex("""([?&](?:page|p)=)(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGE_PATH = Regex("""(/page/)(\d+)""", RegexOption.IGNORE_CASE)
    private const val WORD_BEFORE = "/=?&+"
    private const val WORD_AFTER = "/&?#+."
    private val PAGE_PARAMS = setOf("page", "p")

    data class PathResult(val path: String, val addedPage: Boolean)

    /** [url] without [site]'s origin (other origins keep the whole address) and without the #fragment */
    fun relative(url: String, site: String): String {
        val clean = url.trim().substringBefore('#')
        val origin = site.trim().trimEnd('/')
        if (origin.isEmpty() || !clean.startsWith(origin, ignoreCase = true)) return clean
        val rest = clean.substring(origin.length)
        return when {
            rest.isEmpty() -> "/"
            rest.startsWith("/") -> rest
            rest.startsWith("?") -> "/$rest"
            else -> clean // "https://example.com.evil.net/…": another host that starts the same
        }
    }

    /**
     * The path of a page as a list path: [relative] to [site], the page number (`page=N`, `p=N`, `/page/N`) turned
     * into `{page}`; with none, `page={page}` is appended (`?` or `&`) and [PathResult.addedPage] says so.
     */
    fun pathOf(url: String, site: String, rule: PageRule? = null): PathResult = withPage(relative(url, site), rule)

    /** r101: with a [rule] (from the Next pick) the page number follows it; else the r100 guess */
    fun withPage(path: String, rule: PageRule? = null): PathResult {
        if ("{page}" in path) return PathResult(path, false)
        if (rule != null) return PathResult(applyRule(path, rule), false)
        PAGE_QUERY.find(path)?.let { m ->
            val digits = m.groups[2] ?: return@let
            return PathResult(path.replaceRange(digits.range, "{page}"), false)
        }
        PAGE_PATH.find(path)?.let { m ->
            val digits = m.groups[2] ?: return@let
            return PathResult(path.replaceRange(digits.range, "{page}"), false)
        }
        val joiner = when {
            '?' !in path -> "?"
            path.endsWith("?") || path.endsWith("&") -> ""
            else -> "&"
        }
        return PathResult("$path${joiner}page={page}", true)
    }

    /**
     * The search path from a loaded address that holds [word] (raw, `+`-joined or %-encoded, any case) as a whole
     * value or path segment: the word becomes `{q}` and the page rule of [pathOf] applies. Null when it isn't there.
     * A match in the query wins over one in the path; the host is never searched.
     */
    fun searchPathOf(url: String, word: String, site: String, rule: PageRule? = null): PathResult? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val rel = relative(url, site)
        val pathStart = if (rel.startsWith("/")) {
            0
        } else {
            val afterScheme = rel.indexOf("://").let { if (it < 0) 0 else it + 3 }
            rel.indexOf('/', afterScheme).let { if (it < 0) rel.length else it }
        }
        val queryStart = rel.indexOf('?', pathStart).let { if (it < 0) rel.length else it }
        val encoded = runCatching { URLEncoder.encode(w, "UTF-8") }.getOrDefault(w)
        val candidates = listOf(w, w.replace(' ', '+'), w.replace(" ", "%20"), encoded, encoded.replace("+", "%20"))
            .distinct()
            .sortedByDescending { it.length }
        var best: IntRange? = null
        for (candidate in candidates) {
            var from = pathStart
            while (true) {
                val at = rel.indexOf(candidate, from, ignoreCase = true)
                if (at < 0) break
                val end = at + candidate.length
                val before = rel.getOrNull(at - 1)
                val after = rel.getOrNull(end)
                if (before != null && before in WORD_BEFORE && (after == null || after in WORD_AFTER)) {
                    val range = at until end
                    val current = best
                    val better = current == null ||
                        (range.first >= queryStart && current.first < queryStart) ||
                        ((range.first >= queryStart) == (current.first >= queryStart) && range.first < current.first)
                    if (better) best = range
                }
                from = at + 1
            }
        }
        val range = best ?: return null
        return withPage(rel.replaceRange(range, "{q}"), rule)
    }

    /**
     * r101: the search path from the site's GET search form: the action's path (a GET form drops the action's own
     * query), `field={q}`, the hidden fields, and the page rule ([withPage])
     */
    fun searchPathOfForm(action: HttpUrl, field: String, hidden: List<Pair<String, String>>, site: String, rule: PageRule?): String {
        val base = relative(action.newBuilder().query(null).fragment(null).build().toString(), site)
        fun enc(v: String) = runCatching { URLEncoder.encode(v, "UTF-8") }.getOrDefault(v)
        val pairs = listOf(enc(field) + "={q}") + hidden.map { (k, v) -> enc(k) + "=" + enc(v) }
        return withPage(base + "?" + pairs.joinToString("&"), rule).path
    }

    // ---------- r101: the page rule from the Next pick ----------

    private val PAGE_WORDS = setOf("page", "p", "pg", "pages", "seite", "pagina")

    /** The page number put where [rule] says, replacing a number already there */
    fun applyRule(path: String, rule: PageRule): String {
        val q = path.indexOf('?')
        val pathPart = if (q < 0) path else path.substring(0, q)
        val query = if (q < 0) "" else path.substring(q)
        val trailing = pathPart.length > 1 && pathPart.endsWith("/") && !pathPart.endsWith("://")
        val base = if (trailing) pathPart.trimEnd('/') else pathPart
        val slash = if (trailing) "/" else ""
        return when (rule.kind) {
            "query" -> {
                val re = Regex("([?&]" + Regex.escape(rule.name) + "=)(\\d+)")
                val digits = re.find(query)?.groups?.get(2)
                if (digits != null) {
                    pathPart + query.replaceRange(digits.range, "{page}")
                } else {
                    val joiner = when {
                        query.isEmpty() -> "?"
                        query.endsWith("?") || query.endsWith("&") -> ""
                        else -> "&"
                    }
                    "$pathPart$query$joiner${rule.name}={page}"
                }
            }
            "pathWord" -> {
                val re = Regex("/" + Regex.escape(rule.name) + "/(\\d+)")
                val digits = re.find(pathPart)?.groups?.get(1)
                if (digits != null) {
                    pathPart.replaceRange(digits.range, "{page}") + query
                } else {
                    "$base/${rule.name}/{page}$slash$query"
                }
            }
            else -> {
                val last = base.substringAfterLast('/')
                if (last.isNotEmpty() && last.all(Char::isDigit) && base.count { it == '/' } > 1) {
                    base.dropLast(last.length) + "{page}" + slash + query
                } else {
                    "$base/{page}$slash$query"
                }
            }
        }
    }

    /**
     * The page rule from the Next link [href] (absolute or relative) on [pageUrl]: a query parameter that is 2 while
     * the page has none (or one more than the page's), `/page/2` (a page word and its number added, or one more), or
     * a number added at the end of the path. Null when the link has no page number the Lab can read.
     */
    fun pageRuleOf(href: String, pageUrl: String): PageRule? {
        val page = pageUrl.trim().toHttpUrlOrNull() ?: return null
        val next = page.resolve(href.trim()) ?: return null
        val queryNames = next.queryParameterNames.filter { name ->
            val nv = next.queryParameter(name)?.toIntOrNull() ?: return@filter false
            val pv = page.queryParameter(name)?.toIntOrNull()
            if (pv == null) nv == 2 && page.queryParameter(name) == null else nv == pv + 1
        }
        val queryName = queryNames.firstOrNull { it.lowercase() in PAGE_WORDS } ?: queryNames.firstOrNull()
        if (queryName != null) return PageRule("query", queryName)
        val hs = next.pathSegments.filter { it.isNotEmpty() }
        val ps = page.pathSegments.filter { it.isNotEmpty() }
        for (i in 1 until hs.size) {
            val nv = hs[i].toIntOrNull() ?: continue
            val word = hs[i - 1]
            if (word.isEmpty() || word.any(Char::isDigit)) continue
            val added = nv == 2 && ps == hs.filterIndexed { at, _ -> at != i - 1 && at != i }
            val moved = word.lowercase() in PAGE_WORDS && ps.size == hs.size &&
                ps.indices.all { at -> at == i || ps[at] == hs[at] } && ps[i].toIntOrNull() == nv - 1
            if (added || moved) return PageRule("pathWord", word)
        }
        val nv = hs.lastOrNull()?.toIntOrNull()
        if (nv != null) {
            val added = nv == 2 && ps == hs.dropLast(1)
            val moved = ps.size == hs.size && ps.dropLast(1) == hs.dropLast(1) && ps.lastOrNull()?.toIntOrNull() == nv - 1
            if (added || moved) return PageRule("pathEnd", "")
        }
        return null
    }

    /**
     * A path written before the Next pick, with the guessed `page={page}` replaced by [rule]. Paths without the
     * guess (a page number seen in the address, or typed) stay as they are.
     */
    fun reapplyRule(path: String?, rule: PageRule): String? {
        if (path == null) return null
        if (rule.kind == "query" && rule.name == "page") return path
        if ("page={page}" !in path) return path
        var p = path.replace("&page={page}", "")
        p = p.replace("?page={page}&", "?")
        if (p.endsWith("?page={page}")) p = p.removeSuffix("?page={page}")
        return if ("{page}" in p) p else applyRule(p, rule)
    }

    // ---------- r101: filters from a group of the site's entries ----------

    /** One entry of a tapped group (or of a list read by [BuilderListReader]) */
    data class GroupEntry(val text: String, val href: String?, val value: String?)

    data class GroupDerived(
        val filter: ExtrasFilter?,
        val note: String,
        /** Offer "Read the whole list" of this page (the tapped entry's link), or null */
        val listUrl: String?,
        val listText: String,
        /** For the log: entries used, and how */
        val used: Int,
        val how: String,
    )

    private class Link(val index: Int, val text: String, val url: HttpUrl, val segs: List<String>, val trailing: Boolean) {
        val shape: String = url.host + "|" + segs.size + "|" + url.queryParameterNames.sorted().joinToString(",")
    }

    // No braces in these patterns (ICU)
    private val COUNT_SUFFIX = Regex("""\s*\(\s*[\d.,]+\s*[kKmM]?\s*\)\s*$""")

    /** r101: "Action 1234 videos", "Action (1,234 items)": a number followed by what it counts */
    private val COUNT_WORDS = Regex(
        """\s+[(\[]?\s*\d[\d.,]*\s*[kKmM]?\+?\s*(?:videos?|items?|titles?|posts?|works?|galleries|gallery|entries|results|books|mangas?|comics?|movies?|episodes?|chapters?)\s*[)\]]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** r101: "Action 1,234" — a thousands-grouped number only (never "Top 100", "4K" or a year) */
    private val COUNT_GROUPED = Regex("""(?<=[^\s\d,.~–-])\s+\d+(?:,\d\d\d)+\s*$""")
    private val SPACES = Regex("""\s+""")
    private val SORT_WORDS = Regex(
        """\b(new|newest|latest|recent|recently|hot|popular|popularity|trending|top|rated|rating|views|viewed|most|oldest|release|released|date|updated|update|alphabetical|random|longest|shortest|relevance|a-z|z-a)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SORT_PARAMS = setOf("sort", "order", "orderby", "order_by", "sort_by", "sortby", "sortorder", "sort_order", "s_order")
    private const val MENU_SIZE = 12

    /**
     * An entry's text as an option label: its first line, spaces collapsed, a trailing count dropped ("(1,234)",
     * "1,234", "1234 videos"); a label that is only a number (or a year range) is kept as it is
     */
    fun cleanLabel(text: String): String {
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().replace(SPACES, " ")
        val cleaned = line.replace(COUNT_WORDS, "").replace(COUNT_SUFFIX, "").replace(COUNT_GROUPED, "").trim()
        return cleaned.ifEmpty { line.trim() }
    }

    /** Most of [labels] read like sort orders ("Release date", "Hot", "Recently added"), or the parameter says so */
    fun sortLike(labels: List<String>, param: String?): Boolean {
        if (param != null && param.lowercase() in SORT_PARAMS) return true
        if (labels.size < 2) return labels.size == 1 && SORT_WORDS.containsMatchIn(labels[0])
        val hits = labels.count { SORT_WORDS.containsMatchIn(it) }
        return hits >= 2 && hits * 5 >= labels.size * 3
    }

    /** A path segment that names a list ("genres", "category"), not a language code or a number */
    private fun meaningful(segment: String?): Boolean =
        segment != null && segment.length >= 3 && segment.any(Char::isLetter) && '{' !in segment

    private fun withoutFragment(url: HttpUrl): String = url.newBuilder().fragment(null).build().toString()

    /**
     * What a group of entries becomes. Links on the page's host are grouped by shape (host, number of path segments,
     * query names); the tapped entry's group is the consecutive run of like-shaped links around it (a menu's "New,
     * Hot, Recent" apart from its "Genres, Actresses, Makers"), else all like-shaped links, else the largest shape.
     * Same path, one query parameter differing → a param filter; one path segment differing → a path filter
     * (`{value}` there, the page rule added). Entries with values and no links → a param filter whose parameter the
     * owner types. A tapped link to another page (not a sort option) is offered for "Read the whole list".
     */
    fun deriveGroup(
        entries: List<GroupEntry>,
        tappedText: String,
        tappedHref: String?,
        pageUrl: String,
        rule: PageRule?,
        heading: String?,
        offers: List<FilterOffer> = emptyList(),
        /** r101: the address parameter the entries set when it is known (a `<select name=…>`), before [offers] */
        knownParam: String? = null,
        /** r103: how many options the filter keeps (a text box with suggestions keeps more) */
        maxOptions: Int = MAX_GROUP_OPTIONS,
    ): GroupDerived {
        val page = pageUrl.trim().toHttpUrlOrNull()
        val here = page?.let { withoutFragment(it) }
        val links = ArrayList<Link>()
        entries.forEachIndexed { index, e ->
            val raw = e.href?.trim().orEmpty()
            if (raw.isEmpty() || raw.startsWith("javascript:", ignoreCase = true)) return@forEachIndexed
            val url = (page?.resolve(raw) ?: raw.toHttpUrlOrNull()) ?: return@forEachIndexed
            if (page != null && url.host != page.host) return@forEachIndexed
            // "#" links resolve to this page with a fragment: a menu toggle, not an address
            if (url.fragment != null && withoutFragment(url) == here) return@forEachIndexed
            val segs = url.encodedPathSegments.filter { it.isNotEmpty() }
            val trailing = url.encodedPath.length > 1 && url.encodedPath.endsWith("/")
            links += Link(index, cleanLabel(e.text), url, segs, trailing)
        }
        val tappedClean = cleanLabel(tappedText)
        val tapped = tappedHref?.trim()?.takeIf { it.isNotEmpty() }?.let { h ->
            val abs = page?.resolve(h)?.toString() ?: h
            links.firstOrNull { it.url.toString() == abs }
        } ?: links.firstOrNull { tappedClean.isNotEmpty() && it.text == tappedClean }

        var group: List<Link> = emptyList()
        if (tapped != null) {
            val byIndex = links.associateBy { it.index }
            val around = ArrayList<Link>()
            var j = tapped.index - 1
            while (j >= 0) {
                val l = byIndex[j]
                if (l != null) {
                    if (l.shape != tapped.shape) break
                    around.add(0, l)
                }
                j--
            }
            around += tapped
            j = tapped.index + 1
            while (j < entries.size) {
                val l = byIndex[j]
                if (l != null) {
                    if (l.shape != tapped.shape) break
                    around += l
                }
                j++
            }
            group = around
            if (group.distinctBy { it.url.toString() }.size < 2) group = links.filter { it.shape == tapped.shape }
        } else if (links.isNotEmpty()) {
            group = links.groupBy { it.shape }.values.maxByOrNull { it.size }.orEmpty()
        }
        group = group.distinctBy { it.url.toString() }
        // r101: a sort option tapped in a menu that mixes sort orders with other pages ("New, Hot, Recent, Genres,
        // Actresses"): only its sort-like entries make the filter
        val tappedSort = tapped != null && sortLike(listOf(tapped.text), null)
        if (tappedSort) {
            val sorts = group.filter { sortLike(listOf(it.text), null) }
            if (sorts.size >= 2 && sorts.size < group.size) group = sorts
        }

        var filter: ExtrasFilter? = null
        var how = ""
        var note = ""
        var total = 0
        if (group.size >= 2) {
            val first = group.first()
            if (group.all { it.url.encodedPath == first.url.encodedPath }) {
                val names = first.url.queryParameterNames
                val varying = names.filter { name -> group.map { it.url.queryParameter(name).orEmpty() }.distinct().size > 1 }
                    .filter { it.lowercase() !in PAGE_WORDS || names.size == 1 }
                if (varying.size == 1) {
                    val param = varying.first()
                    val options = group.map { l ->
                        val v = l.url.queryParameter(param).orEmpty()
                        ExtrasOption(l.text.ifEmpty { v.ifEmpty { "Any" } }, v)
                    }
                    // r104: the links' own page with {value} for the parameter (Trending → /en/trending?sort={value})
                    val marked = first.url.newBuilder().setQueryParameter(param, LINK_MARK).build()
                    val linkRaw = marked.encodedPath + (marked.encodedQuery?.let { "?$it" } ?: "")
                    val linkPath = withPage(linkRaw.replace(LINK_MARK, "{value}"), rule).path
                    filter = makeFilter(options, "param", param, "", param, heading, maxOptions).copy(linkPath = linkPath)
                    total = optionCount(options, "param")
                    how = "param $param"
                } else {
                    note = if (varying.isEmpty()) {
                        "The tapped entries all open the same address: no filter was made."
                    } else {
                        "The tapped entries change ${varying.joinToString(", ")} at once: no filter was made."
                    }
                }
            } else {
                fun differing(g: List<Link>): List<Int> =
                    g.first().segs.indices.filter { at -> g.map { it.segs[at] }.distinct().size > 1 }
                var differ = differing(group)
                if (differ.size > 1) {
                    // A folded submenu next to another ("/en/trending/today" by "/en/types/censored"): keep the
                    // entries that differ from the tapped one in a single segment
                    val anchor = tapped ?: first
                    val narrowed = differ.map { at ->
                        group.filter { l -> l.segs.indices.all { k -> k == at || l.segs[k] == anchor.segs[k] } }
                    }.maxByOrNull { it.size }
                    if (narrowed != null && narrowed.size >= 2) {
                        group = narrowed
                        differ = differing(group)
                    }
                }
                val lead = group.first()
                val sameQuery = group.all { it.url.encodedQuery == lead.url.encodedQuery }
                if (differ.size == 1 && sameQuery) {
                    val at = differ.first()
                    val segs = lead.segs.toMutableList().also { it[at] = "{value}" }
                    val raw = "/" + segs.joinToString("/") + (if (lead.trailing) "/" else "") +
                        (lead.url.encodedQuery?.let { "?$it" } ?: "")
                    val result = withPage(raw, rule)
                    val options = group.map { l -> ExtrasOption(l.text.ifEmpty { l.segs[at] }, l.segs[at]) }
                    val before = lead.segs.getOrNull(at - 1)
                    val candidate = makeFilter(options, "path", "", result.path, before, heading, maxOptions)
                    // r101: a menu entry that opens an index page ("Genres" next to "Actresses", "Makers") whose
                    // neighbours share no list name in the address and aren't sort orders: those are other pages,
                    // not a list's options — no filter, only "Read the whole list" of the tapped page
                    val tappedLabel = tapped?.text.orEmpty()
                    val opensPage = tapped != null && !tappedSort && withoutFragment(tapped.url) != here
                    if (opensPage && candidate.type != "sort" && candidate.key == "list") {
                        how = "none (entries open other pages: ${result.path})"
                        note = "The entries next to \"$tappedLabel\" open other pages, not one list's " +
                            "options: no filter was made (tap a sort option for a Sort filter)."
                    } else {
                        filter = candidate
                        total = optionCount(options, "path")
                        how = "path ${result.path}"
                        if (result.addedPage) note = " Pages: guessed page={page} (tap the site's Next link as Next to read it)."
                    }
                } else {
                    note = "The tapped entries don't differ in one part of the address " +
                        "(${lead.url.encodedPath}, ${group[1].url.encodedPath}…): no filter was made."
                }
            }
        }
        if (filter == null && group.size < 2) {
            val valued = entries.filter { !it.value.isNullOrBlank() }
            if (valued.size >= 2) {
                val options = valued.map { e ->
                    val v = e.value.orEmpty().trim()
                    ExtrasOption(cleanLabel(e.text).ifEmpty { v }, v)
                }
                val values = options.map { it.value }.toSet()
                val param = knownParam?.trim()?.takeIf { it.isNotEmpty() }
                    ?: offers.firstOrNull { o -> o.seen.isNotEmpty() && o.seen.all { it in values } }?.param.orEmpty()
                filter = makeFilter(options, "param", param, "", param.ifEmpty { null }, heading, maxOptions)
                    .let { if (param.isEmpty()) it.copy(key = "", askParam = true) else it }
                total = optionCount(options, "param")
                how = if (param.isEmpty()) "values, parameter not known" else "values of $param"
                note = if (param.isEmpty()) {
                    " These entries have values but no links: type the address parameter they set " +
                        "(choose one on the site with nothing armed and look at the address: the part before =)."
                } else {
                    ""
                }
            } else if (links.size < 2) {
                // r102: entries without links and values (a dropdown of buttons) keep their texts as the options;
                // "All" / "Any" is the empty first option. "Learn this list" finds where each one goes.
                val options = entries.map { cleanLabel(it.text) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .map { label -> ExtrasOption(label, if (label.lowercase() in ANY_WORDS) "" else label) }
                if (options.size >= 2) {
                    val values = options.map { it.value }.filter { it.isNotEmpty() }.toSet()
                    val param = knownParam?.trim()?.takeIf { it.isNotEmpty() }
                        ?: offers.firstOrNull { o -> o.seen.isNotEmpty() && o.seen.all { it in values } }?.param.orEmpty()
                    filter = makeFilter(options, "param", param, "", param.ifEmpty { null }, heading, maxOptions)
                        .let { if (param.isEmpty()) it.copy(key = "", askParam = true) else it }
                    total = optionCount(options, "param")
                    how = if (param.isEmpty()) "texts (no links), parameter not known" else "texts of $param"
                    note = if (param.isEmpty()) {
                        " These entries are buttons without links: their texts are the options. Learn this list " +
                            "to find the address each one opens (or type the parameter they set)."
                    } else {
                        ""
                    }
                }
            }
        }

        val made = filter
        val tappedLink = tapped ?: links.singleOrNull()
        val listUrl = tappedLink?.takeIf { l ->
            withoutFragment(l.url) != here && !sortLike(listOf(l.text), null) &&
                (made == null || (made.mode == "path" && group.size <= MENU_SIZE))
        }?.let { withoutFragment(it.url) }
        val listText = tappedLink?.text?.ifEmpty { null } ?: tappedClean.ifEmpty { "this entry" }

        val parts = ArrayList<String>()
        if (made != null) {
            val where = if (made.mode == "path") {
                "picks change the address to ${made.path}"
            } else {
                "picks add ${made.param.ifEmpty { "…" }}=… to it"
            }
            parts += "Made the filter \"${made.name}\" from ${made.options.count { it.value.isNotEmpty() }} entries: $where."
            if (total > maxOptions) parts += "Kept the first $maxOptions of $total entries."
        } else if (group.size < 2 && note.isEmpty()) {
            parts += "Only one entry of that kind was found: no filter was made."
        }
        if (note.isNotBlank()) parts += note.trim()
        if (listUrl != null) parts += "\"$listText\" opens a list page: Read the whole list to make a filter of all its entries."
        return GroupDerived(
            filter = made,
            note = parts.joinToString(" "),
            listUrl = listUrl,
            listText = listText,
            used = made?.options?.size ?: 0,
            how = how,
        )
    }

    /** r102: entry texts that mean "no filter" (the dropdown's first, empty option) */
    private val ANY_WORDS = setOf("all", "any", "everything", "all types", "all years", "show all", "-", "—")

    // r103: + the side menus' ▸ ▹ ► ▶
    private val ARROWS = Regex("""[\s▾▴▼▲⌄⌃∨∧˅˄›»⏷⏶▸▹►▶]+$""")

    /**
     * r102: the list's name as a filter name: "Year: All ▾" (a menu button above the list) → "Year"; arrows and a
     * value after the colon dropped. Null when nothing is left.
     */
    fun cleanHeading(raw: String?): String? {
        var t = raw?.replace(SPACES, " ")?.trim().orEmpty().replace(ARROWS, "").trim()
        val colon = t.indexOf(':')
        if (colon > 0) t = t.substring(0, colon).trim()
        return t.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    /**
     * r102: the tapped entries are a menu's buttons ("Type: All ▾ / Year: All ▾ / Sort: Hot ▾"), not the entries of
     * one list: the tapped element says it opens a popup, or most of 2–8 link-less entries read "Name: value" and the
     * tapped one does too
     */
    fun menuButtons(entries: List<GroupEntry>, tappedText: String, tappedPopup: Boolean): Boolean {
        if (tappedPopup) return true
        val plain = entries.filter { it.href.isNullOrBlank() && it.text.isNotBlank() }
        if (plain.size < 2 || plain.size > 8 || plain.size < entries.size) return false
        // A menu's button reads "Type: All", "Year: 2024 ▾", "Sort: Hot": a short name, a colon, a short value
        fun buttonLike(raw: String): Boolean {
            val text = raw.replace(SPACES, " ").trim().replace(ARROWS, "").trim()
            val at = text.indexOf(':')
            return at in 1..24 && text.length - at in 2..42 && text.substring(at + 1).isNotBlank()
        }
        val hits = plain.count { buttonLike(it.text) }
        val tapped = tappedText.trim()
        return hits >= 2 && hits * 5 >= plain.size * 3 && (tapped.isEmpty() || buttonLike(tapped))
    }

    // ---------- r102: Learn this list: the pattern of the learned addresses ----------

    /**
     * Where an entry's text goes in its address: query parameter [name] (`kind` "param") or path segment [index]
     * (`kind` "path"), written as [variant] (text|lower|dash|underscore); [template] is a learned address to fill
     */
    data class LearnPattern(val kind: String, val name: String, val index: Int, val variant: String, val template: String, val shown: String)

    private val VARIANTS = listOf("text", "lower", "dash", "underscore")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private const val MARK = "labvalue0mark"

    /** An entry's text as the address writes it: as is, lowercased, or slugified with - or _ */
    fun variantOf(text: String, variant: String): String = when (variant) {
        "lower" -> text.lowercase()
        "dash" -> text.lowercase().replace(NON_WORD, "-").trim('-')
        "underscore" -> text.lowercase().replace(NON_WORD, "_").trim('_')
        else -> text
    }

    /**
     * One query parameter or path segment whose value is each learned entry's text (as is, lowercased or slugified,
     * the same way for all), the rest of the addresses being the same: [learned] is (text, address), ≥ 2 of them.
     * Null when there is none.
     */
    fun findPattern(learned: List<Pair<String, String>>): LearnPattern? {
        if (learned.size < 2) return null
        val urls = learned.mapNotNull { (text, url) ->
            val u = url.trim().toHttpUrlOrNull() ?: return@mapNotNull null
            val t = cleanLabel(text)
            if (t.isEmpty()) null else t to u.newBuilder().fragment(null).build()
        }
        if (urls.size < learned.size) return null
        val first = urls.first().second
        for (name in first.queryParameterNames) {
            for (variant in VARIANTS) {
                val values = urls.map { (t, u) -> u.queryParameter(name)?.takeIf { it == variantOf(t, variant) } }
                if (values.any { it.isNullOrEmpty() } || values.distinct().size < 2) continue
                val rest = urls.map { (_, u) -> u.newBuilder().setQueryParameter(name, MARK).build().toString() }
                if (rest.distinct().size != 1) continue
                return LearnPattern("param", name, -1, variant, first.toString(), "$name=<text>" + shownVariant(variant))
            }
        }
        val segs = first.pathSegments
        for (index in segs.indices) {
            for (variant in VARIANTS) {
                val values = urls.map { (t, u) ->
                    u.pathSegments.takeIf { it.size == segs.size }?.get(index)?.takeIf { it == variantOf(t, variant) }
                }
                if (values.any { it.isNullOrEmpty() } || values.distinct().size < 2) continue
                val rest = urls.map { (_, u) -> u.newBuilder().setPathSegment(index, MARK).build().toString() }
                if (rest.distinct().size != 1) continue
                val shownPath = "/" + segs.mapIndexed { at, seg -> if (at == index) "<text>" else seg }.joinToString("/") +
                    (first.encodedQuery?.let { "?$it" } ?: "")
                return LearnPattern("path", "", index, variant, first.toString(), shownPath + shownVariant(variant))
            }
        }
        return null
    }

    private fun shownVariant(variant: String): String = when (variant) {
        "lower" -> " (lowercase)"
        "dash" -> " (a-b)"
        "underscore" -> " (a_b)"
        else -> ""
    }

    /** The address [pattern] gives [text], or null (an empty value) */
    fun fillPattern(pattern: LearnPattern, text: String): String? {
        val value = variantOf(cleanLabel(text), pattern.variant).takeIf { it.isNotEmpty() } ?: return null
        val base = pattern.template.toHttpUrlOrNull() ?: return null
        return when (pattern.kind) {
            "param" -> base.newBuilder().setQueryParameter(pattern.name, value).build().toString()
            else -> if (pattern.index < base.pathSize) base.newBuilder().setPathSegment(pattern.index, value).build().toString() else null
        }
    }

    /** How many distinct options [makeFilter] sees before its [MAX_GROUP_OPTIONS] cap */
    private fun optionCount(options: List<ExtrasOption>, mode: String): Int =
        options.filter { it.value.isNotEmpty() || mode == "param" }.distinctBy { it.value }.size

    private fun makeFilter(
        options: List<ExtrasOption>,
        mode: String,
        param: String,
        path: String,
        keyBase: String?,
        heading: String?,
        maxOptions: Int = MAX_GROUP_OPTIONS,
    ): ExtrasFilter {
        val distinct = options.filter { it.value.isNotEmpty() || mode == "param" }.distinctBy { it.value }.take(maxOptions)
        val sort = sortLike(distinct.map { it.label }, param.ifEmpty { null })
        val key = when {
            mode == "param" && param.isNotEmpty() -> param
            meaningful(keyBase) -> keyBase.orEmpty()
            sort -> "sort"
            else -> "list"
        }
        val name = heading?.trim()?.takeIf { it.isNotEmpty() } ?: when {
            sort -> "Sort"
            mode == "param" && param.isNotEmpty() -> prettyName(param)
            meaningful(keyBase) -> prettyName(keyBase.orEmpty())
            else -> "Genres"
        }
        val all = if (sort) distinct else withAny(distinct)
        return ExtrasFilter(
            key = key,
            name = name,
            type = if (sort) "sort" else "select",
            options = all,
            mode = mode,
            param = param,
            path = path,
        )
    }

    private const val LINK_MARK = "labvalue0link"

    /** r104: what the editor says (in red) about a filter the source can't use yet; null = usable */
    const val NOT_READY = "Not in the source yet — Learn addresses or type the address name"

    /** r104 fix: the note on a filter made from links before r104 ([ExtrasFilter.noLinkAddress]) */
    const val OLD_LINKS = "Made before r104 without its links' address: it may search the wrong page. " +
        "Pick this menu again on the site and press Make filter to read its own page"

    /**
     * r104: why [f] can't go into the source (no parameter and no address: a dropdown of buttons not learned yet, a
     * filter added by hand), or null when it can. Such filters stay in the editor and are left out of `filtersList`
     * with a log line, never silently.
     */
    fun filterProblem(f: ExtrasFilter): String? {
        val param = f.param.trim().ifEmpty { if (f.askParam) "" else f.key.trim() }
        return when {
            f.mode == "path" && "{value}" !in f.path -> NOT_READY
            f.mode != "path" && param.isEmpty() && "{value}" !in f.linkPath -> NOT_READY
            else -> null
        }
    }

    /** r104: the path part of a list / search address (no query, no trailing slash, lowercase) for comparing pages */
    fun pagePathOf(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pathOnly = p.toHttpUrlOrNull()?.encodedPath ?: p.substringBefore('#').substringBefore('?')
        return pathOnly.trimEnd('/').lowercase().ifEmpty { "/" }
    }

    /**
     * r104: [f] opens its own links' page instead of adding a parameter to the search address: a param filter made
     * from links whose page isn't the search page ([searchPath]). r104 fix: no search known (none recorded, none in
     * the source's code) = a parameter filter as before (not every link-made filter as another page)
     */
    fun usesLinkPage(f: ExtrasFilter, searchPath: String?): Boolean {
        if (f.mode == "path" || "{value}" !in f.linkPath) return false
        val own = pagePathOf(f.linkPath) ?: return false
        val search = pagePathOf(searchPath) ?: return false
        return own != search
    }

    /** r101: [b] is a new pick of [a]'s list (same type, key, mode and path / parameter): it replaces [a] */
    fun sameFilter(a: ExtrasFilter, b: ExtrasFilter): Boolean =
        a.type == b.type && a.key.isNotEmpty() && a.key == b.key && a.mode == b.mode &&
            if (a.mode == "path") a.path == b.path else a.param.isNotBlank() && a.param == b.param

    /** [base], or base2, base3… when another filter has it */
    fun uniqueKey(base: String, taken: Collection<String>): String {
        val b = base.trim().ifEmpty { "filter" }
        if (b !in taken) return b
        var n = 2
        while ("$b$n" in taken) n++
        return "$b$n"
    }

    /** The list's name from its address: `/en/genres` → "Genres" */
    fun nameFromUrl(url: String): String? {
        val segs = url.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        val word = segs.lastOrNull { meaningful(it) && it.lowercase() !in PAGE_WORDS && !it.all(Char::isDigit) }
        return word?.let(::prettyName)
    }

    const val MAX_GROUP_OPTIONS = 400

    /** r103: a text box with suggestions keeps more of a long list (every actress, every maker) */
    const val MAX_SUGGESTIONS = 3000

    /**
     * r103: a filter made from a fetched list as a Text box with suggestions: type "autocomplete", the options
     * without the empty "Any" (an empty box means no filter); a Sort stays a Sort
     */
    /**
     * r103 fix: [f] as [type]: "select" (Dropdown) puts the empty "Any" option first ([withAny]: the app sends the
     * first option when nothing was picked), "autocomplete" drops it (an empty box is no filter), others keep theirs
     */
    fun withType(f: ExtrasFilter, type: String): ExtrasFilter {
        if (f.type == type) return f
        return when (type) {
            "select" -> {
                val options = withAny(f.options)
                f.copy(type = type, options = options, optionsText = formatOptions(options))
            }
            "autocomplete" -> {
                val options = f.options.filter { it.value.isNotEmpty() }
                f.copy(type = type, options = options, optionsText = formatOptions(options))
            }
            else -> f.copy(type = type)
        }
    }

    fun asAutocomplete(f: ExtrasFilter): ExtrasFilter {
        if (f.type == "sort") return f
        val options = f.options.filter { it.value.isNotEmpty() }
        return f.copy(type = "autocomplete", options = options, optionsText = formatOptions(options))
    }

    /**
     * r103: picker.js `menuPick`'s groups flattened into cards: a group with two or more entries, or with its own
     * link (an index page), is a card; its subgroups follow it (Collections → Genres, Actresses…)
     */
    fun menuGroupsOf(groups: List<JsonObject>, pageUrl: String, firstId: Int): List<MenuGroup> {
        val out = ArrayList<MenuGroup>()
        fun walk(g: JsonObject, parents: List<String>) {
            val rawHeading = g.str("heading").orEmpty()
            val path = g.arr("headingPath")?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it.isNotBlank() }
                ?: (parents + rawHeading).filter { it.isNotBlank() }
            val items = g.arr("items")?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                MenuItem(o.str("text").orEmpty(), o.str("href")?.takeIf { it.isNotBlank() }, o.str("value"), o.str("path")?.takeIf { it.isNotBlank() })
            }.orEmpty().filter { it.text.isNotBlank() || !it.href.isNullOrBlank() || it.value != null }
            val href = g.str("href")?.trim()?.takeIf { it.startsWith("http") }
            val name = cleanHeading(rawHeading)
                ?: path.lastOrNull()?.let(::cleanHeading)
                ?: href?.let(::nameFromUrl)
                ?: ""
            if (items.size >= 2 || href != null) {
                out += MenuGroup(
                    id = firstId + out.size,
                    name = name,
                    headingPath = path.map { cleanHeading(it) ?: it },
                    opener = g.str("opener")?.trim()?.takeIf { it.isNotEmpty() },
                    href = href,
                    index = g.bool("index") == true,
                    items = items.take(MAX_GROUP_OPTIONS),
                    pageUrl = pageUrl,
                    param = g.str("param")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            g.arr("subgroups")?.forEach { sub -> (sub as? JsonObject)?.let { walk(it, path) } }
        }
        groups.forEach { walk(it, emptyList()) }
        return out
    }

    /** The query parameter whose value is `{q}` in a search path ("q" in `/search?q={q}&page={page}`) */
    fun searchParam(searchPath: String?): String? {
        val query = searchPath?.substringAfter('?', "") ?: return null
        return query.split('&').firstOrNull { it.substringAfter('=', "") == "{q}" }?.substringBefore('=')
    }

    /**
     * The query parameters that changed between two consecutive addresses of the same page (same scheme, host and
     * path), page numbers, the search word's parameter, [exclude] and tracking ones left out: param → the values
     * seen (decoded, non-blank, in order).
     */
    fun changedParams(previous: String, current: String, word: String, exclude: Set<String>): List<Pair<String, List<String>>> {
        val a = previous.toHttpUrlOrNull() ?: return emptyList()
        val b = current.toHttpUrlOrNull() ?: return emptyList()
        if (a.scheme != b.scheme || a.host != b.host || a.encodedPath != b.encodedPath) return emptyList()
        val w = word.trim().lowercase()
        val names = LinkedHashSet<String>().apply {
            addAll(a.queryParameterNames)
            addAll(b.queryParameterNames)
        }
        val out = ArrayList<Pair<String, List<String>>>()
        for (name in names) {
            if (name.isBlank() || name.lowercase() in PAGE_PARAMS || name in exclude) continue
            if (name.startsWith("utm_") || name == "_") continue
            val before = a.queryParameter(name).orEmpty()
            val after = b.queryParameter(name).orEmpty()
            if (before == after) continue
            if (w.isNotEmpty() && (looksLike(before, w) || looksLike(after, w))) continue
            out.add(name to listOf(before, after).filter { it.isNotBlank() }.distinct())
        }
        return out
    }

    private fun looksLike(value: String, word: String): Boolean {
        val v = value.lowercase()
        return word in v || word.replace(' ', '+') in v
    }

    /** `Label = value` per line; a line without `=` is both label and value */
    fun parseOptions(text: String): List<ExtrasOption> = text.lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val at = line.indexOf('=')
        if (at < 0) {
            ExtrasOption(line.trim(), line.trim())
        } else {
            val label = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim()
            ExtrasOption(label.ifEmpty { value.ifEmpty { "Any" } }, value)
        }
    }

    fun formatOptions(options: List<ExtrasOption>): String = options.joinToString("\n") { "${it.label} = ${it.value}" }

    /** A dropdown's first option is what the app sends when nothing was picked: an empty one ("Any") goes first */
    fun withAny(options: List<ExtrasOption>): List<ExtrasOption> {
        val distinct = options.distinctBy { it.value }
        return if (distinct.any { it.value.isEmpty() }) {
            distinct.sortedBy { if (it.value.isEmpty()) 0 else 1 }
        } else {
            listOf(ExtrasOption("Any", "")) + distinct
        }
    }

    /** "sort_by" → "Sort by" */
    fun prettyName(param: String): String =
        param.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { param }

    /** A recorded path as the template takes it: relative to [site] when it is on it */
    fun forSite(path: String, site: String): String = if (path.startsWith("/")) path else relative(path, site)

    fun tabsJson(tabs: List<ExtrasTab>, site: String): String = JsonArray(
        tabs.filter { it.path.isNotBlank() }
            .distinctBy { it.path.trim() }
            .map { tab ->
                val path = forSite(tab.path.trim(), site)
                JsonObject(mapOf("name" to JsonPrimitive(tab.name.trim().ifEmpty { path }), "path" to JsonPrimitive(path)))
            },
    ).toString()

    /**
     * The getFilters format (JsFilters) plus how a value changes the address (r101): `mode` "param" (`param`=value)
     * or "path" (`{value}` in `path` replaced), and `on` (a ticked checkbox's value). `type` "sort" becomes a
     * JsFilters Sort group in the template. A param filter without a parameter, or a path filter without `{value}`,
     * is left out. r100 filters (no mode) are param filters named by their key.
     */
    fun filtersJson(filters: List<ExtrasFilter>, searchPath: String? = null): String {
        val keys = HashSet<String>()
        return JsonArray(
            filters.mapNotNull { f ->
                // r104: not usable yet: left out here; the editor shows it in red and templateValues logs it
                if (filterProblem(f) != null) return@mapNotNull null
                // r104: a filter made from links of another page than the search page opens that page
                val linkPage = usesLinkPage(f, searchPath)
                val mode = if (f.mode == "path" || linkPage) "path" else "param"
                val param = f.param.trim().ifEmpty { if (f.askParam) "" else f.key.trim() }
                val path = if (linkPage) f.linkPath.trim() else f.path.trim()
                if (mode == "param" && param.isEmpty()) return@mapNotNull null
                if (mode == "path" && "{value}" !in path) return@mapNotNull null
                val key = uniqueKey(f.key.trim().ifEmpty { param.ifEmpty { "filter" } }, keys)
                keys += key
                // r104 fix: the app's Dropdown / Sort always sends an option (the first when nothing was picked): a
                // filter that opens its own page gets an empty "Any" first, so it opens that page only when picked
                val options = if (mode == "path" && (f.type == "select" || f.type == "sort") && f.options.isNotEmpty()) {
                    withAny(f.options)
                } else {
                    f.options
                }
                val type = when (f.type) {
                    "checkbox" -> "checkbox"
                    "select" -> if (options.isEmpty()) "text" else "select"
                    "sort" -> if (options.isEmpty()) "text" else "sort"
                    // r103: a text box with suggestions (generic-list v6 maps the typed labels to values)
                    "autocomplete" -> "autocomplete"
                    else -> "text"
                }
                val map = LinkedHashMap<String, JsonElement>()
                map["key"] = JsonPrimitive(key)
                map["name"] = JsonPrimitive(f.name.trim().ifEmpty { prettyName(key) })
                map["type"] = JsonPrimitive(type)
                map["mode"] = JsonPrimitive(mode)
                if (mode == "param") map["param"] = JsonPrimitive(param) else map["path"] = JsonPrimitive(path)
                map["options"] = JsonArray(
                    if (type == "select" || type == "sort" || type == "autocomplete") {
                        options.map { JsonObject(mapOf("label" to JsonPrimitive(it.label), "value" to JsonPrimitive(it.value))) }
                    } else {
                        emptyList()
                    },
                )
                if (type == "checkbox") map["on"] = JsonPrimitive(f.onValue.trim().ifEmpty { "1" })
                JsonObject(map)
            },
        ).toString()
    }

    /** The getSourcePreferences format (JsPreferences.parse): key, title, type, options [{label, value}], defaultValue */
    fun settingsJson(s: BuilderExtras): String {
        val out = ArrayList<JsonElement>()
        fun option(v: String) = JsonObject(mapOf("label" to JsonPrimitive(v), "value" to JsonPrimitive(v)))
        if (s.qualitySetting) {
            out += JsonObject(
                mapOf(
                    "key" to JsonPrimitive("quality"),
                    "title" to JsonPrimitive("Preferred quality"),
                    "summary" to JsonPrimitive("Videos of this quality are listed first"),
                    "type" to JsonPrimitive("list"),
                    "options" to JsonArray(QUALITIES.map(::option)),
                    "defaultValue" to JsonPrimitive(QUALITIES.first()),
                ),
            )
        }
        if (s.serverSetting) {
            out += JsonObject(
                mapOf(
                    "key" to JsonPrimitive("server"),
                    "title" to JsonPrimitive("Preferred server"),
                    "summary" to JsonPrimitive("Videos from a server whose name contains this are listed first"),
                    "type" to JsonPrimitive("text"),
                    "defaultValue" to JsonPrimitive(""),
                ),
            )
        }
        if (s.hideWordsSetting) {
            out += JsonObject(
                mapOf(
                    "key" to JsonPrimitive("hideWords"),
                    "title" to JsonPrimitive("Hide titles containing"),
                    "summary" to JsonPrimitive("Words separated by commas; titles containing any of them are not listed"),
                    "type" to JsonPrimitive("text"),
                    "defaultValue" to JsonPrimitive(""),
                ),
            )
        }
        val taken = out.mapNotNull { (it as? JsonObject)?.str("key") }.toMutableSet()
        s.custom.forEach { c ->
            val key = c.key.trim()
            if (key.isEmpty() || !taken.add(key)) return@forEach
            val map = LinkedHashMap<String, JsonElement>()
            map["key"] = JsonPrimitive(key)
            map["title"] = JsonPrimitive(c.name.trim().ifEmpty { prettyName(key) })
            val entries = c.options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            when {
                c.type == "switch" -> {
                    map["type"] = JsonPrimitive("switch")
                    map["defaultValue"] = JsonPrimitive(false)
                }
                c.type == "list" && entries.isNotEmpty() -> {
                    map["type"] = JsonPrimitive("list")
                    map["options"] = JsonArray(entries.map(::option))
                    map["defaultValue"] = JsonPrimitive(entries.first())
                }
                else -> {
                    map["type"] = JsonPrimitive("text")
                    map["defaultValue"] = JsonPrimitive("")
                }
            }
            out += JsonObject(map)
        }
        return JsonArray(out).toString()
    }

    val QUALITIES = listOf("Best", "1080p", "720p", "480p", "360p")

    /** Generator refuses `{{name}}` in filled code: a typed `{{` can't make one */
    fun safe(value: String): String = value.replace("{{", "{ {")
}

/**
 * The state of "Lists and options" for one Build by tapping session. Plain (no screen scope): page loads come from
 * the WebView client on the main thread, options from the picker's bridge thread; every change goes through
 * [MutableStateFlow.update], so both are safe.
 */
internal class BuilderExtrasModel {

    private val mutable = MutableStateFlow(BuilderExtras())
    val state: StateFlow<BuilderExtras> = mutable.asStateFlow()

    /** Filter offers the owner dismissed: not offered again */
    private val dismissed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Set on the main thread (the pane), read there and on the picker's bridge thread */
    @Volatile
    private var site: String? = null

    /**
     * r102: pushes a script into the builder's WebView (BuilderScreenModel sets it). "Learn this list" loads the list
     * page with it (`location.assign`) and presses entries (`window.__labLearn.press`). Called on the main thread.
     */
    @Volatile
    var runInPage: ((String) -> Unit)? = null

    /** r102: the learning runs; [dispose] cancels them */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var learnJob: Job? = null

    @Volatile
    private var stopLearning = false

    /** r102: every address the WebView reports ([onPageUrl]), numbered, so a run can wait for the next one */
    private data class SeenUrl(val tick: Long, val url: String)

    private val seen = MutableStateFlow(SeenUrl(0L, ""))

    /** r102 fix: only the pages that finished loading ([onPageFinished]), so a run presses on a loaded page */
    private val finished = MutableStateFlow(SeenUrl(0L, ""))

    /** r102 fix: learn.js's answer to the last press ([onLearnLog]): the press id and pressed|missing|error */
    private data class PressOutcome(val id: Long, val state: String)

    private val outcome = MutableStateFlow(PressOutcome(0L, ""))

    @Volatile
    private var pressId = 0L

    /**
     * Anything besides the defaults set: the source is written as JS (the JSON format has no tabs, filters, settings
     * or names, and its paths are joined to the site, so an address on another site can't be said either)
     */
    val used: Boolean
        get() {
            val s = mutable.value
            val foreignPath = listOfNotNull(s.popularPath, s.latestPath, s.searchPath).any { it.isNotBlank() && !it.startsWith("/") }
            return s.popularName.isNotBlank() || s.latestName.isNotBlank() || s.tabs.any { it.path.isNotBlank() } ||
                s.filters.any { it.key.isNotBlank() } || s.qualitySetting || s.serverSetting || s.hideWordsSetting ||
                s.custom.any { it.key.isNotBlank() } || foreignPath
        }

    /** The site the paths are made relative to (the pane passes the builder's); else each page's own origin */
    fun setSite(siteUrl: String?) {
        site = siteUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun siteFor(url: String): String = site ?: Detector.normalize(url)

    /**
     * A page finished loading (or an in-page navigation): kept as [BuilderExtras.lastUrl]; an address with the
     * search word records the search path; a change of query parameters on the same page offers them as filters.
     */
    fun onPageLoaded(url: String?) {
        val u = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        val logs = ArrayList<Pair<Char, String>>()
        mutable.update { s ->
            logs.clear()
            reduceLoaded(s, u, logs)
        }
        logs.forEach { (level, text) -> AppLog.add(level, "lab", text) }
    }

    private fun reduceLoaded(s: BuilderExtras, url: String, logs: MutableList<Pair<Char, String>>): BuilderExtras {
        // onPageFinished and doUpdateVisitedHistory both report the same address
        if (s.lastUrl == url) return s
        val site = siteFor(url)
        var n = s.copy(lastUrl = url)
        // Recorded once (the first results page): a later sort / filter change on the results must not be baked into
        // the search address; "Forget it and record again" (or clearing the field) records anew
        // r101: a path from the site's search box is replaced by a real recorded search
        val found = if (s.searchPath == null || s.searchFromForm) {
            BuilderExtrasLogic.searchPathOf(url, s.searchWord, site, s.pageRule)
        } else {
            null
        }
        if (found != null) {
            n = n.copy(
                searchPath = found.path,
                searchFrom = url,
                searchFromForm = false,
                rev = n.rev + 1,
                message = if (found.addedPage) BuilderExtrasLogic.NO_PAGE_MESSAGE else null,
            )
            logs += 'I' to "extras search recorded: ${found.path}"
        }
        val previous = s.lastUrl ?: return n
        val exclude = HashSet<String>().apply {
            n.filters.forEach {
                add(it.key)
                if (it.param.isNotBlank()) add(it.param)
            }
            n.pageRule?.takeIf { it.kind == "query" }?.let { add(it.name) }
            addAll(dismissed)
            BuilderExtrasLogic.searchParam(n.searchPath)?.let { param -> add(param) }
        }
        val changed = BuilderExtrasLogic.changedParams(previous, url, s.searchWord, exclude)
        if (changed.isEmpty()) return n
        val offers = n.offers.toMutableList()
        val path = BuilderExtrasLogic.pathOf(url, site, n.pageRule).path
        changed.forEach { (param, seen) ->
            val at = offers.indexOfFirst { it.param == param }
            if (at < 0) {
                offers += FilterOffer(param, seen, path)
                logs += 'D' to "extras filter offer $param (${seen.joinToString(", ")})"
            } else {
                offers[at] = offers[at].copy(seen = (offers[at].seen + seen).distinct().take(MAX_SEEN), path = path)
            }
        }
        return n.copy(offers = offers)
    }

    /** From picker.js `optionsFor(param)`: `{ param, options: [{ label, value }] }` */
    fun onPageOptions(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val param = obj?.str("param")
        if (obj == null || param.isNullOrBlank()) {
            AppLog.add('W', "lab", "extras options: unreadable answer from the page (${json.take(120)})")
            return
        }
        val options = obj.arr("options")?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val value = o.str("value") ?: return@mapNotNull null
            ExtrasOption(o.str("label")?.trim()?.ifEmpty { null } ?: value, value)
        }.orEmpty().take(MAX_OPTIONS)
        AppLog.add('D', "lab", "extras options for $param: ${options.size} from the page")
        var applied: ExtrasFilter? = null
        mutable.update { s ->
            applied = null
            val at = s.filters.indexOfFirst { it.mode != "path" && (it.param == param || it.key == param) }
            val filters = if (at >= 0 && options.isNotEmpty()) {
                val sort = s.filters[at].type == "sort"
                val all = if (sort) options.distinctBy { it.value } else BuilderExtrasLogic.withAny(options)
                val f = s.filters[at].copy(
                    type = if (sort) "sort" else "select",
                    options = all,
                    optionsText = BuilderExtrasLogic.formatOptions(all),
                )
                applied = f
                s.filters.toMutableList().also { it[at] = f }
            } else {
                s.filters
            }
            s.copy(
                optionsFor = s.optionsFor + (param to options),
                filters = filters,
                rev = s.rev + 1,
                message = if (options.isEmpty()) {
                    "The page has no dropdown or links for \"$param\": the options are the values seen so far"
                } else {
                    s.message
                },
            )
        }
        applied?.let { AppLog.add('I', "lab", "extras filter ${it.key} (${it.type}, ${it.options.size} options)") }
    }

    /**
     * The template placeholders: the paths only when set (they override Derive's guesses), the tab names, and the
     * lists as JSON text ("[]" when empty)
     */
    fun templateValues(site: String, codeSearchPath: String? = null): Map<String, String> {
        val s = mutable.value
        val out = LinkedHashMap<String, String>()
        // r104 fix: the search page the filters are compared with: the recorded one, else the edited source's own
        // (no search known: link-made filters stay parameter filters)
        val searchPath = s.searchPath?.trim()?.ifEmpty { null } ?: codeSearchPath?.trim()?.ifEmpty { null }
        s.popularPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["popularPath"] = BuilderExtrasLogic.forSite(it, site) }
        s.latestPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["latestPath"] = BuilderExtrasLogic.forSite(it, site) }
        s.searchPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["searchPath"] = BuilderExtrasLogic.forSite(it, site) }
        out["tabPopularName"] = s.popularName.trim()
        out["tabLatestName"] = s.latestName.trim()
        out["tabsList"] = BuilderExtrasLogic.tabsJson(s.tabs, site)
        out["filtersList"] = BuilderExtrasLogic.filtersJson(s.filters, searchPath)
        // r104: never left out silently
        s.filters.forEachIndexed { i, f ->
            if (BuilderExtrasLogic.filterProblem(f) != null) {
                AppLog.add('W', "lab", "extras filter ${i + 1} \"${f.name}\" left out: no address parameter or path yet (Learn addresses or type it)")
            } else if (BuilderExtrasLogic.usesLinkPage(f, searchPath)) {
                AppLog.add('I', "lab", "extras filter ${i + 1} \"${f.name}\" opens its own page ${f.linkPath} (not the search page)")
            }
            if (f.noLinkAddress) {
                AppLog.add('W', "lab", "extras filter ${i + 1} \"${f.name}\" was made from links before r104: its own page is unknown (?${f.param}= on the search page)")
            }
        }
        out["settingsList"] = BuilderExtrasLogic.settingsJson(s)
        AppLog.add(
            'D',
            "lab",
            "extras written: search ${out["searchPath"] ?: "(Derive's)"}, ${s.tabs.size} tab(s), ${s.filters.size} filter(s), " +
                "settings ${out["settingsList"]?.length ?: 0} chars",
        )
        return out.mapValues { BuilderExtrasLogic.safe(it.value) }
    }

    // ---------- Search ----------

    fun setSearchWord(word: String) = mutable.update { it.copy(searchWord = word) }

    fun setSearchPath(path: String) = mutable.update { it.copy(searchPath = path.ifBlank { null }, searchFromForm = false) }

    /** Forget the recorded search (the next address with the word records again) */
    fun clearSearch() = mutable.update {
        it.copy(searchPath = null, searchFrom = null, searchFromForm = false, rev = it.rev + 1)
    }

    /**
     * r101, from picker.js (every page load and `readForm()`): the site's search box,
     * `{ action, method, field, hidden: { name: value } }`. A GET form gives the search path when none was recorded
     * yet (a recorded search, or one typed, wins); the page rule applies.
     */
    fun onSearchForm(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val action = obj?.str("action")?.trim().orEmpty()
        val field = obj?.str("field")?.trim().orEmpty()
        if (obj == null || field.isEmpty()) {
            AppLog.add('W', "lab", "extras search form: unreadable answer from the page (${json.take(120)})")
            return
        }
        val method = obj.str("method")?.trim()?.lowercase().orEmpty().ifEmpty { "get" }
        if (method != "get") {
            AppLog.add('D', "lab", "extras search form uses $method: not usable as an address")
            return
        }
        val hidden = obj.obj("hidden")?.mapNotNull { (name, value) ->
            val v = (value as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (name.isBlank() || name == field) null else name to v
        }.orEmpty()
        val current = mutable.value
        if (current.searchPath != null && !current.searchFromForm) return
        val url = action.toHttpUrlOrNull() ?: current.lastUrl?.toHttpUrlOrNull()?.resolve(action)
        if (url == null) {
            AppLog.add('W', "lab", "extras search form: no usable action ($action)")
            return
        }
        val path = BuilderExtrasLogic.searchPathOfForm(url, field, hidden, siteFor(url.toString()), current.pageRule)
        var set = false
        mutable.update { s ->
            set = false
            if (s.searchPath != null && (!s.searchFromForm || s.searchPath == path)) return@update s
            set = true
            s.copy(
                searchPath = path,
                searchFrom = "the site's search box",
                searchFromForm = true,
                rev = s.rev + 1,
                message = "Found the site's search box ✓",
            )
        }
        if (set) AppLog.add('I', "lab", "extras search from the site's form: $path")
    }

    // ---------- Pages (r101) ----------

    /**
     * r101, from the Next pick (label "next"): the link's page number pattern becomes the page rule for lists, tabs,
     * search and filters; paths written with the `page={page}` guess are rewritten to it
     */
    fun setNextHref(href: String, pageUrl: String) {
        val rule = BuilderExtrasLogic.pageRuleOf(href, pageUrl)
        if (rule == null) {
            AppLog.add('W', "lab", "extras page rule: the Next link $href on $pageUrl has no page number the Lab can read")
            mutable.update {
                it.copy(message = "The Next link ($href) has no page number the Lab can read: lists keep the ?page=2 guess")
            }
            return
        }
        mutable.update { s ->
            fun fix(path: String?) = BuilderExtrasLogic.reapplyRule(path, rule)
            s.copy(
                pageRule = rule,
                popularPath = fix(s.popularPath),
                latestPath = fix(s.latestPath),
                searchPath = fix(s.searchPath),
                tabs = s.tabs.map { it.copy(path = fix(it.path).orEmpty()) },
                filters = s.filters.map { f -> if (f.mode == "path") f.copy(path = fix(f.path).orEmpty()) else f },
                offers = s.offers.map { o -> o.copy(path = fix(o.path).orEmpty()) },
                message = if (s.message == BuilderExtrasLogic.NO_PAGE_MESSAGE) null else s.message,
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras page rule from the Next link: ${rule.shown} ($href)")
    }

    // ---------- Filters from the site (r101) ----------

    /** "Pick a list on the site" (on) and its Cancel (off); BuilderScreenModel passes it to the page */
    fun setGroupMode(on: Boolean) {
        mutable.update { it.copy(groupMode = on) }
        AppLog.add('D', "lab", "extras group mode ${if (on) "on" else "off"}")
    }

    /**
     * r101, from picker.js group mode:
     * `{ pageUrl, tapped: { text, href }, likeSelector, count, items: [{ text, href, value }], isSelect }`
     * r102: also `opener` (css path of the list's menu button, "" when none), `tappedOpener` (the tapped element is a
     * menu's own button) and items' `path` when the page sends them (all optional). Taps on a menu's
     * buttons show the menu hint instead; entries without links keep their texts; the list is kept for
     * "Learn this list".
     */
    fun onGroupPicked(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        mutable.update { it.copy(groupMode = false) }
        if (obj == null) {
            AppLog.add('W', "lab", "extras group: unreadable answer from the page (${json.take(120)})")
            return
        }
        val pageUrl = obj.str("pageUrl")?.trim().orEmpty().ifEmpty { mutable.value.lastUrl.orEmpty() }
        val tapped = obj.obj("tapped")
        val entries = obj.arr("items")?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            BuilderExtrasLogic.GroupEntry(o.str("text").orEmpty(), o.str("href"), o.str("value"))
        }.orEmpty().take(BuilderExtrasLogic.MAX_GROUP_OPTIONS)
        AppLog.add(
            'D',
            "lab",
            "extras group picked: ${entries.size} entries like ${obj.str("likeSelector").orEmpty()} " +
                "(tapped \"${tapped?.str("text").orEmpty().take(40)}\"${if (obj.bool("isSelect") == true) ", a dropdown" else ""})",
        )
        val isSelect = obj.bool("isSelect") == true
        val tappedText = tapped?.str("text").orEmpty()
        // r102: "Type: All ▾ / Year: All ▾ / Sort: Hot ▾" are the menus' buttons, not one list's entries
        val popup = obj.bool("tappedOpener") == true || tapped?.bool("popup") == true
        if (!isSelect && BuilderExtrasLogic.menuButtons(entries, tappedText, popup)) {
            mutable.update { it.copy(menuHint = true, menuHintText = null, message = null, rev = it.rev + 1) }
            AppLog.add(
                'I',
                "lab",
                "extras group: \"${tappedText.take(40)}\" is a menu's button (${entries.size} like it" +
                    (if (popup) ", opens a popup" else "") + "): no filter, hint shown",
            )
            return
        }
        val s = mutable.value
        val heading = BuilderExtrasLogic.cleanHeading(obj.str("heading"))
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = tappedText,
            tappedHref = tapped?.str("href"),
            pageUrl = pageUrl,
            rule = s.pageRule,
            heading = heading,
            offers = s.offers,
            // r101: a <select name="sort"> sets that parameter (picker.js sends its name)
            knownParam = if (isSelect) obj.str("name") else null,
        )
        applyDerived(derived, "the tapped list")
        // r102: kept for "Learn this list" (not a <select>: its options are read, never pressed)
        val paths = obj.arr("items")?.map { element -> (element as? JsonObject)?.str("path")?.takeIf { it.isNotBlank() } }.orEmpty()
        val learnEntries = entries.mapIndexedNotNull { at, e ->
            val text = e.text.trim()
            if (text.isEmpty()) null else LearnEntry(text, paths.getOrNull(at), e.href?.trim()?.takeIf { it.isNotEmpty() })
        }.distinctBy { it.text }
        val group = if (!isSelect && learnEntries.size >= 2 && pageUrl.startsWith("http")) {
            LearnGroup(
                pageUrl = pageUrl.substringBefore('#'),
                opener = obj.str("opener")?.trim()?.takeIf { it.isNotEmpty() },
                likeSelector = obj.str("likeSelector").orEmpty(),
                name = derived.filter?.name?.takeIf { it.isNotBlank() } ?: heading ?: "List",
                entries = learnEntries.take(BuilderExtrasLogic.MAX_GROUP_OPTIONS),
                hasLinks = learnEntries.any { e -> e.href?.let { !it.startsWith("javascript:", ignoreCase = true) } == true },
            )
        } else {
            null
        }
        mutable.update { st ->
            if (st.learn?.running == true) st.copy(menuHint = false) else st.copy(learnGroup = group, learn = null, menuHint = false)
        }
        if (group != null) {
            AppLog.add(
                'D',
                "lab",
                "extras learnable list \"${group.name}\": ${group.entries.size} entries" +
                    (if (group.hasLinks) ", with links" else ", no links") + ", menu button ${group.opener ?: "(none sent)"}",
            )
        }
    }

    /** r102: the menu hint's OK */
    fun dismissMenuHint() = mutable.update { it.copy(menuHint = false, menuHintText = null) }

    /**
     * r101: what [BuilderListReader] read from [url] becomes a filter, derived like a group pick. r103: [form] "select"
     * (a dropdown) or "autocomplete" (a text box with the entries as suggestions, up to
     * [BuilderExtrasLogic.MAX_SUGGESTIONS]); [name] the menu group's name (else the address's last word)
     */
    fun onListRead(url: String, result: BuilderListReader.Result, form: String = "select", name: String? = null) {
        val suggest = form == "autocomplete"
        val entries = result.items.map { BuilderExtrasLogic.GroupEntry(it.text, it.href, null) }
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = "",
            tappedHref = null,
            pageUrl = url,
            rule = mutable.value.pageRule,
            heading = name?.takeIf { it.isNotBlank() } ?: BuilderExtrasLogic.nameFromUrl(url),
            maxOptions = if (suggest) BuilderExtrasLogic.MAX_SUGGESTIONS else BuilderExtrasLogic.MAX_GROUP_OPTIONS,
        )
        val made = derived.filter?.let { if (suggest) BuilderExtrasLogic.asAutocomplete(it) else it }
        AppLog.add('I', "lab", "extras full list of $url as ${if (suggest) "a text box with suggestions" else "a dropdown"}")
        applyDerived(derived.copy(filter = made, listUrl = null), "the whole list (${result.pages} pages)")
    }

    // ---------- Pick a menu on the site (r103) ----------

    @Volatile
    private var nextMenuId = 1

    /**
     * r103, from picker.js `menuPick` (LabPicker.menu / console "LABMENU:"):
     * `{ pageUrl, tapped: { text, href }, groups: [{ heading, headingPath, opener, href, index, items, subgroups }] }`.
     * Every group with entries (or an index link) becomes a card; nothing is made until the owner says so.
     */
    fun onMenuPicked(json: String) {
        val obj = runCatching { jsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        mutable.update { it.copy(groupMode = false) }
        if (obj == null) {
            AppLog.add('W', "lab", "extras menu: unreadable answer from the page (${json.take(120)})")
            return
        }
        val pageUrl = obj.str("pageUrl")?.trim().orEmpty().ifEmpty { mutable.value.lastUrl.orEmpty() }.substringBefore('#')
        val raw = obj.arr("groups")?.mapNotNull { it as? JsonObject }.orEmpty()
        val cards = BuilderExtrasLogic.menuGroupsOf(raw, pageUrl, nextMenuId)
        nextMenuId += cards.size + 1
        val tapped = obj.obj("tapped")?.str("text").orEmpty().take(40)
        // r103 fix: a dropdown whose menu isn't in the page until it opens: no groups, the hint card says what to do
        val hint = obj.str("hint")?.trim()?.takeIf { it.isNotEmpty() && cards.isEmpty() }
        if (hint != null) {
            mutable.update { s -> s.copy(menuGroups = emptyList(), menuHint = true, menuHintText = hint, message = null, rev = s.rev + 1) }
            AppLog.add('I', "lab", "extras menu picked at \"$tapped\": its menu isn't in the page until it opens (hint shown)")
            return
        }
        mutable.update { s ->
            s.copy(
                menuGroups = cards,
                menuHint = false,
                menuHintText = null,
                message = if (cards.isEmpty()) {
                    "No menu found at \"$tapped\": tap a menu's heading (Trending, Types…), a dropdown's button or one entry of a list."
                } else {
                    null
                },
                rev = s.rev + 1,
            )
        }
        AppLog.add(
            'I',
            "lab",
            "extras menu picked at \"$tapped\": ${cards.size} group(s): " +
                cards.joinToString(" · ") { g -> "${g.headingPath.joinToString(" › ")} (${g.items.size}${if (g.href != null) ", list page" else ""})" },
        )
    }

    /** r103: a menu group card's "Make filter": a filter of that group's entries only (r101 derivation) */
    fun makeMenuFilter(id: Int) {
        val s = mutable.value
        val g = s.menuGroups.firstOrNull { it.id == id } ?: return
        val entries = g.items.map { BuilderExtrasLogic.GroupEntry(it.text, it.href, it.value) }
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = "",
            tappedHref = null,
            pageUrl = g.pageUrl,
            rule = s.pageRule,
            heading = g.name.ifBlank { null },
            offers = s.offers,
            knownParam = g.param,
        )
        applyDerived(derived.copy(listUrl = null), "the menu group \"${g.headingPath.joinToString(" › ")}\"")
        val made = derived.filter
        mutable.update { st ->
            st.copy(menuGroups = st.menuGroups.map { if (it.id == id) it.copy(made = made?.key?.ifEmpty { "?" } ?: "") else it })
        }
        outlineGroup(g)
    }

    /** r103: "Learn addresses": the group's entries become the list "Learn this list" presses (its card opens) */
    fun learnMenuGroup(id: Int) {
        val g = mutable.value.menuGroups.firstOrNull { it.id == id } ?: return
        if (learnJob?.isActive == true) return
        val entries = g.items.mapNotNull { e ->
            val text = e.text.trim()
            if (text.isEmpty()) null else LearnEntry(text, e.path, e.href?.trim()?.takeIf { it.isNotEmpty() })
        }.distinctBy { it.text }
        if (entries.size < 2 || !g.pageUrl.startsWith("http")) return
        val group = LearnGroup(
            pageUrl = g.pageUrl,
            opener = g.opener,
            likeSelector = "",
            name = g.name.ifBlank { "List" },
            entries = entries,
            hasLinks = entries.any { e -> e.href?.let { !it.startsWith("javascript:", ignoreCase = true) } == true },
        )
        mutable.update { it.copy(learnGroup = group, learn = null, rev = it.rev + 1) }
        AppLog.add('D', "lab", "extras learn the menu group \"${g.name.ifBlank { "List" }}\": ${entries.size} entries, menu button ${g.opener ?: "(none)"}")
        outlineGroup(g)
    }

    fun dismissMenuGroup(id: Int) = mutable.update { s -> s.copy(menuGroups = s.menuGroups.filter { it.id != id }) }

    /** Dashed green on this group's entries only */
    private fun outlineGroup(g: MenuGroup) {
        val paths = g.items.mapNotNull { it.path }.take(BuilderExtrasLogic.MAX_GROUP_OPTIONS)
        if (paths.isEmpty()) return
        val push = runInPage ?: return
        val list = JsonArray(paths.map { JsonPrimitive(it) })
        runCatching { push("window.__labPicker && window.__labPicker.outlineItems && window.__labPicker.outlineItems($list);") }
    }

    fun dismissListOffer() = mutable.update { it.copy(listOffer = null) }

    private fun applyDerived(
        derived: BuilderExtrasLogic.GroupDerived,
        from: String,
        /** r102: other filters the new one replaces (the texts-only filter of a list that was then learned) */
        replaces: (ExtrasFilter) -> Boolean = { false },
    ) {
        var added: ExtrasFilter? = null
        mutable.update { s ->
            added = null
            val f = derived.filter
            val filters = if (f == null) {
                s.filters
            } else {
                // r104 fix: a filter made again (the same list) keeps its place in the order the owner set
                val at = s.filters.indexOfFirst { sameFilter(it, f) || replaces(it) }
                val others = s.filters.filterNot { sameFilter(it, f) || replaces(it) }
                val made = if (f.key.isEmpty()) f else f.copy(key = BuilderExtrasLogic.uniqueKey(f.key, others.map { it.key }))
                added = made
                if (at < 0) others + made else others.toMutableList().also { it.add(at.coerceAtMost(it.size), made) }
            }
            s.copy(
                filters = filters,
                listOffer = derived.listUrl?.let { ListOffer(it, derived.listText) },
                message = derived.note.ifEmpty { null },
                groupMode = false,
                rev = s.rev + 1,
            )
        }
        val f = added
        if (f != null) {
            val where = if (f.mode == "path") f.path else f.param.ifEmpty { "(parameter to type)" }
            AppLog.add('I', "lab", "extras filter ${f.key.ifEmpty { "?" }} (${f.type}, ${f.mode} $where, ${f.options.size} options) from $from")
        } else {
            AppLog.add('D', "lab", "extras no filter from $from: ${derived.note}")
        }
        derived.listUrl?.let { AppLog.add('D', "lab", "extras offer: read the whole list of $it") }
    }

    /**
     * A new pick of the same list replaces its filter; a filter of another type or key (a Sort on the same
     * `/en/{value}` path as a menu's other pages) is never replaced: the new one is added under a unique key
     */
    private fun sameFilter(a: ExtrasFilter, b: ExtrasFilter): Boolean = BuilderExtrasLogic.sameFilter(a, b)

    // ---------- Learn this list (r102) ----------

    /** r102: the WebView loaded an address or its history changed (BuilderScreenModel calls it every time) */
    fun onPageUrl(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        seen.update { SeenUrl(it.tick + 1, u) }
    }

    /** r102 fix: a page finished loading (BuilderScreenModel calls it from the WebView's onPageFinished only) */
    fun onPageFinished(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        // (onPageUrl heard the same address just before: [seen] isn't ticked twice)
        finished.update { SeenUrl(it.tick + 1, u) }
    }

    /**
     * r102 fix: a "learn: …" line of learn.js (BuilderScreenModel.onPickerLog forwards them): "learn: pressed #<id> …",
     * "learn: missing #<id> …" or "learn: error #<id> …" says what became of press <id>.
     */
    fun onLearnLog(line: String) {
        val m = LEARN_OUTCOME.find(line) ?: return
        val id = m.groupValues[2].toLongOrNull() ?: return
        outcome.value = PressOutcome(id, m.groupValues[1])
    }

    /** r102: the card's "Not now": forget the picked list (a run in progress is stopped) */
    fun dismissLearn() {
        stopLearning = true
        mutable.update { it.copy(learnGroup = null, learn = null) }
    }

    /** r102: the card's Stop: the run ends after the current press, keeping what it learned */
    fun stopLearn() {
        if (learnJob?.isActive == true) {
            stopLearning = true
            AppLog.add('D', "lab", "learn: stop asked")
        }
    }

    /** r102: the builder is closed: a run in progress ends at once (BuilderScreenModel.onDispose) */
    fun dispose() {
        stopLearning = true
        learnJob?.cancel()
        learnJob = null
    }

    /**
     * r102: "Learn this list": presses the picked list's entries one at a time on its page and records where each
     * goes, pausing a random [pauseMinMs]..[pauseMaxMs] between presses. After 3 learned addresses a pattern (one
     * parameter or path segment holding the entry's text) fills in the rest without pressing.
     */
    fun startLearn(pauseMinMs: Long, pauseMaxMs: Long) {
        val group = mutable.value.learnGroup ?: return
        if (learnJob?.isActive == true) return
        if (runInPage == null) {
            mutable.update { it.copy(message = "The page isn't ready: open the list page in the builder first") }
            AppLog.add('W', "lab", "learn: no page to press in (runInPage not set)")
            return
        }
        val low = minOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        val high = maxOf(pauseMinMs, pauseMaxMs).coerceAtLeast(0L)
        stopLearning = false
        learnJob = scope.launch {
            try {
                runLearn(group, low, high)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add('E', "lab", "learn failed: ${e.message ?: e.javaClass.simpleName}")
                mutable.update { s ->
                    val run = s.learn ?: return@update s
                    s.copy(learn = run.copy(running = false, note = "Learning stopped: ${e.message ?: "an error"}"))
                }
            }
        }
    }

    private suspend fun runLearn(group: LearnGroup, low: Long, high: Long) {
        val rows = group.entries.map { e ->
            LearnRow(BuilderExtrasLogic.cleanLabel(e.text).ifEmpty { e.text }, null, "waiting")
        }.toMutableList()
        val base = group.pageUrl
        var pressed = 0
        var pattern: BuilderExtrasLogic.LearnPattern? = null
        var note: String? = null
        fun publish(running: Boolean) {
            val run = LearnRun(
                name = group.name,
                rows = rows.toList(),
                running = running,
                pattern = pattern?.shown,
                note = note,
                pressed = pressed,
                pauseMinMs = low,
                pauseMaxMs = high,
            )
            // "Not now" / "Make filter" during the run forgot the list: the card stays closed
            mutable.update { if (it.learnGroup == null) it else it.copy(learn = run) }
        }
        publish(true)
        AppLog.add('I', "lab", "learn \"${group.name}\": ${rows.size} entries on $base, pause $low–$high ms, menu button ${group.opener ?: "(none)"}")
        var triedPattern = false
        var stopReason: String? = null
        try {
            for (i in rows.indices) {
                if (stopLearning) {
                    stopReason = "Stopped by you after $pressed presses."
                    break
                }
                if (pressed >= MAX_PRESSES) {
                    stopReason = "Pressed $MAX_PRESSES entries, the most one run presses."
                    break
                }
                if (pressed > 0 && high > 0) {
                    pause(Random.nextLong(low, high + 1))
                    if (stopLearning) {
                        stopReason = "Stopped by you after $pressed presses."
                        break
                    }
                }
                rows[i] = rows[i].copy(status = "pressing")
                publish(true)
                if (!onPage(base) && !loadPage(base)) {
                    rows[i] = rows[i].copy(status = "waiting")
                    stopReason = "The list page didn't load again ($base): learning stopped."
                    AppLog.add('W', "lab", "learn: the list page $base didn't load in ${LOAD_MS / 1000} s")
                    break
                }
                pause(SETTLE_MS)
                if (stopLearning) {
                    rows[i] = rows[i].copy(status = "waiting")
                    stopReason = "Stopped by you after $pressed presses."
                    break
                }
                val from = clean(currentUrl())
                val before = seen.value.tick
                val e = group.entries[i]
                val id = press(group, e)
                pressed++
                // r102 fix: learn.js says whether it found the entry; a missing one is never "no change" (= "Any")
                val answer = waitFor(ANSWER_MS) { outcome.value.takeIf { it.id == id } }
                val gone = waitFor(0L) { seen.value.takeIf { it.tick > before && clean(it.url) != from } }
                val changed = when {
                    gone != null -> gone
                    answer == null || answer.state != "pressed" -> null
                    else -> waitFor(CHANGE_MS) { seen.value.takeIf { it.tick > before && clean(it.url) != from } }
                }
                if (changed == null && stopLearning) {
                    rows[i] = rows[i].copy(status = "waiting")
                    stopReason = "Stopped by you after $pressed presses."
                    break
                }
                if (changed == null && (answer == null || answer.state != "pressed")) {
                    rows[i] = rows[i].copy(status = "missing")
                    val why = answer?.state ?: "no answer from the page"
                    AppLog.add('D', "lab", "learn ${rows[i].text} → missing ($why)")
                } else if (changed == null) {
                    rows[i] = rows[i].copy(status = "nochange")
                    AppLog.add('D', "lab", "learn ${rows[i].text} → no change")
                } else {
                    rows[i] = rows[i].copy(status = "learned", url = changed.url.substringBefore('#'))
                    AppLog.add('D', "lab", "learn ${rows[i].text} → ${changed.url}")
                }
                val learned = rows.filter { it.status == "learned" && it.url != null }
                if (!triedPattern && learned.size >= PATTERN_AFTER) {
                    triedPattern = true
                    val found = BuilderExtrasLogic.findPattern(learned.map { it.text to it.url.orEmpty() })
                    if (found != null) {
                        pattern = found
                        for (k in rows.indices) {
                            if (rows[k].status != "waiting") continue
                            val url = BuilderExtrasLogic.fillPattern(found, rows[k].text)
                            rows[k] = rows[k].copy(status = if (url == null) "missed" else "filled", url = url)
                        }
                        publish(true)
                        AppLog.add('D', "lab", "learn: pattern ${found.shown} after $PATTERN_AFTER entries")
                        break
                    }
                    AppLog.add('D', "lab", "learn: no pattern in the first $PATTERN_AFTER addresses, pressing all")
                }
                publish(true)
            }
        } finally {
            val learned = rows.count { it.status == "learned" || it.status == "filled" }
            note = when {
                stopReason != null -> stopReason
                pattern != null -> "Pattern found after $PATTERN_AFTER: ${pattern?.shown} — the rest are filled in without pressing."
                learned == 0 -> "No entry changed the address: this list can't be learned (the site changes the page without a new address)."
                else -> "Pressed all ${rows.count { it.status == "learned" || it.status == "nochange" }}: no pattern, each address is the one seen." +
                    rows.count { it.status == "missing" }.let { m -> if (m > 0) " $m not found on the page." else "" }
            }
            publish(false)
            val how = pattern?.let { "pattern ${it.shown}" } ?: "pressed all"
            AppLog.add('I', "lab", "learn: $learned entries, $how" + (stopReason?.let { " ($it)" } ?: ""))
        }
        // Back to the list page, so the page shows what was learned from
        if (!onPage(base)) runCatching { loadPage(base) }
    }

    /** "Make "Year" filter": what was learned becomes a filter (the r101 derivation: param / path / sort) */
    fun makeLearnedFilter() {
        val s = mutable.value
        val run = s.learn ?: return
        val group = s.learnGroup ?: return
        stopLearning = true
        val entries = run.rows.mapNotNull { r ->
            when (r.status) {
                "learned", "filled" -> r.url?.let { BuilderExtrasLogic.GroupEntry(r.text, it, null) }
                // "All": pressing it leaves the list page as it is
                "nochange" -> BuilderExtrasLogic.GroupEntry(r.text, group.pageUrl, null)
                else -> null
            }
        }
        val derived = BuilderExtrasLogic.deriveGroup(
            entries = entries,
            tappedText = "",
            tappedHref = null,
            pageUrl = group.pageUrl,
            rule = s.pageRule,
            heading = group.name,
            offers = s.offers,
        )
        // A "no change" entry ("All") names the empty option
        val anyLabel = run.rows.firstOrNull { it.status == "nochange" }?.text
        val made = derived.filter?.let { f ->
            if (anyLabel == null || f.type == "sort") {
                f
            } else {
                val options = f.options.map { if (it.value.isEmpty()) it.copy(label = anyLabel) else it }
                f.copy(options = options, optionsText = BuilderExtrasLogic.formatOptions(options))
            }
        }
        val texts = run.rows.map { it.text }.toSet()
        applyDerived(derived.copy(filter = made, listUrl = null), "the learned list \"${group.name}\"") { old ->
            // the texts-only filter made when the list was picked
            old.askParam && old.param.isBlank() && old.options.count { it.label in texts } >= 2
        }
        if (made != null) {
            mutable.update { st ->
                st.copy(
                    learnGroup = null,
                    learn = null,
                    offers = if (made.mode == "param") st.offers.filter { it.param != made.param } else st.offers,
                )
            }
        }
    }

    private fun currentUrl(): String = seen.value.url.ifEmpty { mutable.value.lastUrl.orEmpty() }

    private fun clean(url: String): String = url.trim().substringBefore('#').trimEnd('/')

    private fun onPage(url: String): Boolean = clean(currentUrl()) == clean(url)

    /**
     * Loads [url] in the WebView and waits until it finished loading (r102 fix: onPageFinished, not the first history
     * event, which comes before the page's scripts ran), or any page finished, when the site redirects
     */
    private suspend fun loadPage(url: String): Boolean {
        val before = finished.value.tick
        inPage("location.assign(${JsonPrimitive(url)});")
        val loaded = waitFor(LOAD_MS) { finished.value.takeIf { it.tick > before && clean(it.url) == clean(url) } }
        if (loaded != null) return true
        return finished.value.tick > before
    }

    /**
     * r102 fix: waits up to [ms] for [find] to give a value, looking every 200 ms, and gives up at once when Stop was
     * pressed (so Stop answers within a fifth of a second); [ms] 0 = look once
     */
    private suspend fun <T : Any> waitFor(ms: Long, find: () -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            find()?.let { return it }
            val left = end - System.currentTimeMillis()
            if (left <= 0L || stopLearning) return null
            delay(minOf(left, WAIT_STEP_MS))
        }
    }

    /**
     * Presses [entry] through learn.js, waiting up to 5 s for learn.js to be injected after a load; returns the press
     * id learn.js reports the outcome under ([onLearnLog])
     */
    private suspend fun press(group: LearnGroup, entry: LearnEntry): Long {
        val id = ++pressId
        val request = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "opener" to (group.opener?.let { JsonPrimitive(it) } ?: JsonNull),
                "entry" to JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(entry.path ?: group.likeSelector),
                        "text" to JsonPrimitive(entry.text),
                    ),
                ),
            ),
        )
        inPage(
            "(function(){var r=$request;function go(k){if(window.__labLearn){window.__labLearn.press(r);}" +
                "else if(k<50){setTimeout(function(){go(k+1);},100);}}go(0);})();",
        )
        return id
    }

    private suspend fun inPage(script: String) {
        val push = runInPage ?: return
        withContext(Dispatchers.Main) { push(script) }
    }

    private suspend fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !stopLearning) {
            val step = minOf(left, WAIT_STEP_MS)
            delay(step)
            left -= step
        }
    }

    // ---------- Saved with the source (r102: Edit by tapping) ----------

    /** r102: what the owner set here, for the builder state saved with the source ([restore] reads it back) */
    fun snapshot(): JsonElement {
        val s = mutable.value
        fun str(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
        return JsonObject(
            mapOf(
                "v" to JsonPrimitive(1),
                "searchWord" to JsonPrimitive(s.searchWord),
                "searchPath" to str(s.searchPath),
                "searchFrom" to str(s.searchFrom),
                "searchFromForm" to JsonPrimitive(s.searchFromForm),
                "popularPath" to str(s.popularPath),
                "popularName" to JsonPrimitive(s.popularName),
                "latestPath" to str(s.latestPath),
                "latestName" to JsonPrimitive(s.latestName),
                "tabs" to JsonArray(s.tabs.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "path" to JsonPrimitive(it.path))) }),
                "filters" to JsonArray(
                    s.filters.map { f ->
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive(f.key),
                                "name" to JsonPrimitive(f.name),
                                "type" to JsonPrimitive(f.type),
                                "optionsText" to JsonPrimitive(f.optionsText),
                                "options" to JsonArray(
                                    f.options.map { JsonObject(mapOf("label" to JsonPrimitive(it.label), "value" to JsonPrimitive(it.value))) },
                                ),
                                "onValue" to JsonPrimitive(f.onValue),
                                "mode" to JsonPrimitive(f.mode),
                                "param" to JsonPrimitive(f.param),
                                "path" to JsonPrimitive(f.path),
                                "askParam" to JsonPrimitive(f.askParam),
                                "linkPath" to JsonPrimitive(f.linkPath),
                                "noLinkAddress" to JsonPrimitive(f.noLinkAddress),
                            ),
                        )
                    },
                ),
                "qualitySetting" to JsonPrimitive(s.qualitySetting),
                "serverSetting" to JsonPrimitive(s.serverSetting),
                "hideWordsSetting" to JsonPrimitive(s.hideWordsSetting),
                "custom" to JsonArray(
                    s.custom.map { c ->
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive(c.key),
                                "name" to JsonPrimitive(c.name),
                                "type" to JsonPrimitive(c.type),
                                "optionsText" to JsonPrimitive(c.optionsText),
                            ),
                        )
                    },
                ),
                "pageRule" to (s.pageRule?.let { JsonObject(mapOf("kind" to JsonPrimitive(it.kind), "name" to JsonPrimitive(it.name))) } ?: JsonNull),
                "dismissed" to JsonArray(synchronized(dismissed) { dismissed.toList() }.map { JsonPrimitive(it) }),
            ),
        )
    }

    /** r102: puts back what [snapshot] saved (missing parts keep their defaults); the fields re-read their text */
    fun restore(json: JsonElement) {
        val o = json as? JsonObject
        if (o == null) {
            AppLog.add('W', "lab", "extras restore: not an object")
            return
        }
        val tabs = o.arr("tabs")?.mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            ExtrasTab(t.str("name").orEmpty(), t.str("path").orEmpty())
        }.orEmpty()
        val filters = o.arr("filters")?.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val options = f.arr("options")?.mapNotNull { oe ->
                val op = oe as? JsonObject ?: return@mapNotNull null
                ExtrasOption(op.str("label").orEmpty(), op.str("value").orEmpty())
            }.orEmpty()
            val key = f.str("key").orEmpty()
            val mode = f.str("mode") ?: "param"
            val askParam = f.bool("askParam") == true
            val type = f.str("type") ?: "select"
            // r104 fix: saved before r104 (no linkPath key) from a menu's links: a param filter with its options
            val oldLinks = f.bool("noLinkAddress") == true || (
                "linkPath" !in f && mode == "param" && !askParam && (type == "select" || type == "sort") &&
                    options.count { it.value.isNotEmpty() } >= 2 && !f.str("param").isNullOrBlank()
                )
            ExtrasFilter(
                key = key,
                name = f.str("name").orEmpty(),
                type = type,
                options = options,
                onValue = f.str("onValue") ?: "1",
                optionsText = f.str("optionsText") ?: BuilderExtrasLogic.formatOptions(options),
                mode = mode,
                param = f.str("param") ?: key,
                path = f.str("path").orEmpty(),
                askParam = askParam,
                linkPath = f.str("linkPath").orEmpty(),
                noLinkAddress = oldLinks,
            )
        }.orEmpty()
        val custom = o.arr("custom")?.mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val text = c.str("optionsText").orEmpty()
            ExtrasCustomSetting(
                key = c.str("key").orEmpty(),
                name = c.str("name").orEmpty(),
                type = c.str("type") ?: "switch",
                options = text.lines().map { it.trim() }.filter { it.isNotEmpty() },
                optionsText = text,
            )
        }.orEmpty()
        val rule = o.obj("pageRule")?.let { r -> r.str("kind")?.let { PageRule(it, r.str("name").orEmpty()) } }
        o.arr("dismissed")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { dismissed.add(it) } }
        mutable.update { s ->
            s.copy(
                searchWord = o.str("searchWord") ?: s.searchWord,
                searchPath = o.str("searchPath"),
                searchFrom = o.str("searchFrom"),
                searchFromForm = o.bool("searchFromForm") == true,
                popularPath = o.str("popularPath"),
                popularName = o.str("popularName").orEmpty(),
                latestPath = o.str("latestPath"),
                latestName = o.str("latestName").orEmpty(),
                tabs = tabs,
                filters = filters,
                qualitySetting = o.bool("qualitySetting") == true,
                serverSetting = o.bool("serverSetting") == true,
                hideWordsSetting = o.bool("hideWordsSetting") == true,
                custom = custom,
                pageRule = rule,
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras restored: ${tabs.size} tab(s), ${filters.size} filter(s), ${custom.size} setting(s)")
    }

    // ---------- Tabs ----------

    fun setPopularName(name: String) = mutable.update { it.copy(popularName = name) }

    fun setLatestName(name: String) = mutable.update { it.copy(latestName = name) }

    fun setPopularPath(path: String) = mutable.update { it.copy(popularPath = path.ifBlank { null }) }

    fun setLatestPath(path: String) = mutable.update { it.copy(latestPath = path.ifBlank { null }) }

    /** "Use this page" on the Popular row */
    fun usePageForPopular(pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        mutable.update { it.copy(popularPath = result.path, rev = it.rev + 1, message = noteFor(result)) }
        AppLog.add('I', "lab", "extras tab \"${state.value.popularName.ifBlank { "Popular" }}\" = ${result.path}")
    }

    /** "Use this page" on the Latest row */
    fun usePageForLatest(pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        mutable.update { it.copy(latestPath = result.path, rev = it.rev + 1, message = noteFor(result)) }
        AppLog.add('I', "lab", "extras tab \"${state.value.latestName.ifBlank { "Latest" }}\" = ${result.path}")
    }

    /** "Add a tab": [name] (blank → the path's last word) showing the current page */
    fun addTab(name: String, pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        val shown = name.trim().ifEmpty { nameFromPath(result.path) }
        mutable.update { it.copy(tabs = it.tabs + ExtrasTab(shown, result.path), rev = it.rev + 1, message = noteFor(result)) }
        AppLog.add('I', "lab", "extras tab \"$shown\" = ${result.path}")
    }

    fun useThisPageForTab(index: Int, pageUrl: String?) {
        val result = pagePath(pageUrl) ?: return
        var name: String? = null
        mutable.update { s ->
            val tab = s.tabs.getOrNull(index) ?: return@update s
            name = tab.name
            s.copy(tabs = s.tabs.toMutableList().also { it[index] = tab.copy(path = result.path) }, rev = s.rev + 1, message = noteFor(result))
        }
        name?.let { AppLog.add('I', "lab", "extras tab \"$it\" = ${result.path}") }
    }

    fun setTabName(index: Int, name: String) = updateTab(index) { it.copy(name = name) }

    fun setTabPath(index: Int, path: String) = updateTab(index) { it.copy(path = path) }

    fun removeTab(index: Int) = mutable.update { s ->
        if (index !in s.tabs.indices) s else s.copy(tabs = s.tabs.filterIndexed { i, _ -> i != index }, rev = s.rev + 1)
    }

    private fun updateTab(index: Int, change: (ExtrasTab) -> ExtrasTab) = mutable.update { s ->
        val tab = s.tabs.getOrNull(index) ?: return@update s
        s.copy(tabs = s.tabs.toMutableList().also { it[index] = change(tab) })
    }

    // ---------- Filters ----------

    /** "Make this a filter": a dropdown from the values seen (the page's options replace them when they come) */
    fun acceptOffer(param: String) {
        var made: ExtrasFilter? = null
        mutable.update { s ->
            made = null
            val offer = s.offers.firstOrNull { it.param == param } ?: return@update s
            val fromPage = s.optionsFor[param].orEmpty()
            val options = BuilderExtrasLogic.withAny(fromPage.ifEmpty { offer.seen.map { ExtrasOption(it, it) } })
            val filter = ExtrasFilter(key = param, name = BuilderExtrasLogic.prettyName(param), type = "select", options = options)
            made = filter
            s.copy(
                offers = s.offers.filter { it.param != param },
                filters = s.filters.filter { it.key != param } + filter,
                rev = s.rev + 1,
            )
        }
        made?.let { AppLog.add('I', "lab", "extras filter ${it.key} (${it.type}, ${it.options.size} options)") }
    }

    fun dismissOffer(param: String) {
        dismissed.add(param)
        mutable.update { s -> s.copy(offers = s.offers.filter { it.param != param }) }
        AppLog.add('D', "lab", "extras filter offer $param dismissed")
    }

    /** "Add a filter by hand": a dropdown whose address parameter the owner types (r101: no "filter1" default) */
    fun addFilter() {
        mutable.update { s ->
            val n = s.filters.size + 1
            s.copy(
                filters = s.filters + ExtrasFilter(
                    key = "",
                    name = "Filter $n",
                    type = "select",
                    options = listOf(ExtrasOption("Any", "")),
                    param = "",
                    askParam = true,
                ),
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras filter added by hand (select, parameter to type)")
    }

    fun setFilterKey(index: Int, key: String) = updateFilter(index) { it.copy(key = key.trim()) }

    /** r101: the address parameter of a param filter; its key follows it (unique among the filters) */
    fun setFilterParam(index: Int, param: String) = mutable.update { s ->
        val f = s.filters.getOrNull(index) ?: return@update s
        val p = param.trim()
        val others = s.filters.filterIndexed { i, _ -> i != index }.map { it.key }
        val key = if (p.isEmpty()) "" else BuilderExtrasLogic.uniqueKey(p, others)
        s.copy(filters = s.filters.toMutableList().also { it[index] = f.copy(param = p, key = key) })
    }

    fun setFilterName(index: Int, name: String) = updateFilter(index) { it.copy(name = name) }

    fun setFilterType(index: Int, type: String) {
        // r103 fix: Dropdown gets its empty "Any" option back first; a text box with suggestions drops it (rev: the
        // options editor shows the new lines)
        mutable.update { s ->
            val f = s.filters.getOrNull(index) ?: return@update s
            s.copy(filters = s.filters.toMutableList().also { it[index] = BuilderExtrasLogic.withType(f, type) }, rev = s.rev + 1)
        }
        state.value.filters.getOrNull(index)?.let { f ->
            AppLog.add('I', "lab", "extras filter ${f.key} (${f.type}, ${f.options.size} options)")
        }
    }

    fun setFilterOptionsText(index: Int, text: String) =
        updateFilter(index) { it.copy(optionsText = text, options = BuilderExtrasLogic.parseOptions(text)) }

    fun setFilterOnValue(index: Int, value: String) = updateFilter(index) { it.copy(onValue = value) }

    /** r104: the filter card's ▲ / ▼: the order is the source's (filtersList, the Browse sheet) */
    fun moveFilter(index: Int, delta: Int) {
        var moved = false
        mutable.update { s ->
            moved = false
            val to = index + delta
            if (index !in s.filters.indices || to !in s.filters.indices) return@update s
            moved = true
            val list = s.filters.toMutableList()
            val f = list.removeAt(index)
            list.add(to, f)
            s.copy(filters = list, rev = s.rev + 1)
        }
        if (moved) AppLog.add('D', "lab", "extras filter ${index + 1} moved ${if (delta < 0) "up" else "down"}")
    }

    fun removeFilter(index: Int) = mutable.update { s ->
        if (index !in s.filters.indices) s else s.copy(filters = s.filters.filterIndexed { i, _ -> i != index }, rev = s.rev + 1)
    }

    private fun updateFilter(index: Int, change: (ExtrasFilter) -> ExtrasFilter) = mutable.update { s ->
        val f = s.filters.getOrNull(index) ?: return@update s
        s.copy(filters = s.filters.toMutableList().also { it[index] = change(f) })
    }

    // ---------- Settings ----------

    fun setQualitySetting(on: Boolean) {
        mutable.update { it.copy(qualitySetting = on) }
        if (on) AppLog.add('I', "lab", "extras setting quality")
    }

    fun setServerSetting(on: Boolean) {
        mutable.update { it.copy(serverSetting = on) }
        if (on) AppLog.add('I', "lab", "extras setting server")
    }

    fun setHideWordsSetting(on: Boolean) {
        mutable.update { it.copy(hideWordsSetting = on) }
        if (on) AppLog.add('I', "lab", "extras setting hideWords")
    }

    fun addCustomSetting() {
        var key = ""
        mutable.update { s ->
            var n = s.custom.size + 1
            while (s.custom.any { it.key == "setting$n" }) n++
            key = "setting$n"
            s.copy(custom = s.custom + ExtrasCustomSetting(key = key, name = "Setting $n", type = "switch"), rev = s.rev + 1)
        }
        AppLog.add('I', "lab", "extras setting $key")
    }

    fun setCustomKey(index: Int, key: String) = updateCustom(index) { it.copy(key = key.trim()) }

    fun setCustomName(index: Int, name: String) = updateCustom(index) { it.copy(name = name) }

    fun setCustomType(index: Int, type: String) = updateCustom(index) { it.copy(type = type) }

    fun setCustomOptionsText(index: Int, text: String) = updateCustom(index) {
        it.copy(optionsText = text, options = text.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() })
    }

    fun removeCustomSetting(index: Int) = mutable.update { s ->
        if (index !in s.custom.indices) s else s.copy(custom = s.custom.filterIndexed { i, _ -> i != index }, rev = s.rev + 1)
    }

    private fun updateCustom(index: Int, change: (ExtrasCustomSetting) -> ExtrasCustomSetting) = mutable.update { s ->
        val c = s.custom.getOrNull(index) ?: return@update s
        s.copy(custom = s.custom.toMutableList().also { it[index] = change(c) })
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    // ---------- helpers ----------

    private fun pagePath(pageUrl: String?): BuilderExtrasLogic.PathResult? {
        val url = (pageUrl ?: mutable.value.lastUrl)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            mutable.update { it.copy(message = "No page is open yet: open the list on the site first") }
            return null
        }
        return BuilderExtrasLogic.pathOf(url, siteFor(url), mutable.value.pageRule)
    }

    /** The "no page number" guess note, only while there is no Next pick (with one, the rule is used) */
    private fun noteFor(result: BuilderExtrasLogic.PathResult): String? =
        if (result.addedPage && mutable.value.pageRule == null) BuilderExtrasLogic.NO_PAGE_MESSAGE else null

    private fun nameFromPath(path: String): String {
        val word = path.substringBefore('?').trim('/').substringAfterLast('/')
            .let { if (it == "{page}" || it.all(Char::isDigit)) path.substringBefore('?').trim('/').split('/').dropLast(2).lastOrNull().orEmpty() else it }
        return BuilderExtrasLogic.prettyName(word.ifEmpty { "Tab" })
    }

    private companion object {
        const val MAX_SEEN = 30
        const val MAX_OPTIONS = 60

        // r102: Learn this list
        const val MAX_PRESSES = 60
        const val PATTERN_AFTER = 3
        const val CHANGE_MS = 6_000L
        const val LOAD_MS = 15_000L
        const val SETTLE_MS = 700L

        /** r102 fix: how long learn.js may take to say what became of a press (5 s injection wait + menu opening) */
        const val ANSWER_MS = 8_000L

        /** r102 fix: how often a wait looks at Stop */
        const val WAIT_STEP_MS = 200L

        /** r102 fix: learn.js's outcome lines ("learn: pressed #12 …") */
        val LEARN_OUTCOME = Regex("^learn: (pressed|missing|error) #([0-9]+)")
    }
}
