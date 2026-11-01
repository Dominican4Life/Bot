package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/*
 * KMK (r100, Source Lab): "Lists and options" of Build by tapping: the search address recorded from a real search
 * on the site, what Popular / Latest show and their names, extra tabs, search filters offered from address changes,
 * and settings, all without writing code. The values go into the generic-list template (v3) as placeholders:
 * paths override Derive's guesses, the lists are JSON text the template parses (`tabsList`, `filtersList`,
 * `settingsList`; never a name ending in `Json`, which Generator fills from its twin).
 */

@Immutable
internal data class ExtrasTab(val name: String, val path: String)

@Immutable
internal data class ExtrasOption(val label: String, val value: String)

@Immutable
internal data class ExtrasFilter(
    val key: String,
    val name: String,
    val type: String, // select|checkbox|text
    val options: List<ExtrasOption> = emptyList(),
    val onValue: String = "1",
    /** The options as the editor shows them, one `Label = value` per line ([options] is parsed from it) */
    val optionsText: String = BuilderExtrasLogic.formatOptions(options),
)

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
    fun pathOf(url: String, site: String): PathResult = withPage(relative(url, site))

    fun withPage(path: String): PathResult {
        if ("{page}" in path) return PathResult(path, false)
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
    fun searchPathOf(url: String, word: String, site: String): PathResult? {
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
        return withPage(rel.replaceRange(range, "{q}"))
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

    /** The getFilters format (JsFilters) plus `param` (the address parameter) and `on` (a ticked checkbox's value) */
    fun filtersJson(filters: List<ExtrasFilter>): String = JsonArray(
        filters.filter { it.key.isNotBlank() }
            .distinctBy { it.key.trim() }
            .map { f ->
                val key = f.key.trim()
                val options = f.options
                val type = when (f.type) {
                    "checkbox" -> "checkbox"
                    "select" -> if (options.isEmpty()) "text" else "select"
                    else -> "text"
                }
                val map = LinkedHashMap<String, JsonElement>()
                map["key"] = JsonPrimitive(key)
                map["name"] = JsonPrimitive(f.name.trim().ifEmpty { prettyName(key) })
                map["type"] = JsonPrimitive(type)
                if (type == "select") {
                    map["options"] = JsonArray(
                        options.map { JsonObject(mapOf("label" to JsonPrimitive(it.label), "value" to JsonPrimitive(it.value))) },
                    )
                }
                map["param"] = JsonPrimitive(key)
                map["on"] = JsonPrimitive(f.onValue.trim().ifEmpty { "1" })
                JsonObject(map)
            },
    ).toString()

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

    /** Set and read on the main thread (the pane, the WebView client) */
    private var site: String? = null

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
        val found = if (s.searchPath == null) BuilderExtrasLogic.searchPathOf(url, s.searchWord, site) else null
        if (found != null) {
            n = n.copy(
                searchPath = found.path,
                searchFrom = url,
                rev = n.rev + 1,
                message = if (found.addedPage) BuilderExtrasLogic.NO_PAGE_MESSAGE else null,
            )
            logs += 'I' to "extras search recorded: ${found.path}"
        }
        val previous = s.lastUrl ?: return n
        val exclude = HashSet<String>().apply {
            n.filters.forEach { add(it.key) }
            addAll(dismissed)
            BuilderExtrasLogic.searchParam(n.searchPath)?.let { param -> add(param) }
        }
        val changed = BuilderExtrasLogic.changedParams(previous, url, s.searchWord, exclude)
        if (changed.isEmpty()) return n
        val offers = n.offers.toMutableList()
        val path = BuilderExtrasLogic.pathOf(url, site).path
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
            val at = s.filters.indexOfFirst { it.key == param }
            val filters = if (at >= 0 && options.isNotEmpty()) {
                val all = BuilderExtrasLogic.withAny(options)
                val f = s.filters[at].copy(type = "select", options = all, optionsText = BuilderExtrasLogic.formatOptions(all))
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
    fun templateValues(site: String): Map<String, String> {
        val s = mutable.value
        val out = LinkedHashMap<String, String>()
        s.popularPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["popularPath"] = BuilderExtrasLogic.forSite(it, site) }
        s.latestPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["latestPath"] = BuilderExtrasLogic.forSite(it, site) }
        s.searchPath?.trim()?.takeIf { it.isNotEmpty() }?.let { out["searchPath"] = BuilderExtrasLogic.forSite(it, site) }
        out["tabPopularName"] = s.popularName.trim()
        out["tabLatestName"] = s.latestName.trim()
        out["tabsList"] = BuilderExtrasLogic.tabsJson(s.tabs, site)
        out["filtersList"] = BuilderExtrasLogic.filtersJson(s.filters)
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

    fun setSearchPath(path: String) = mutable.update { it.copy(searchPath = path.ifBlank { null }) }

    /** Forget the recorded search (the next address with the word records again) */
    fun clearSearch() = mutable.update { it.copy(searchPath = null, searchFrom = null, rev = it.rev + 1) }

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

    /** "Add a filter by hand" */
    fun addFilter() {
        var key = ""
        mutable.update { s ->
            var n = s.filters.size + 1
            while (s.filters.any { it.key == "filter$n" }) n++
            key = "filter$n"
            s.copy(
                filters = s.filters + ExtrasFilter(key = key, name = "Filter $n", type = "select", options = listOf(ExtrasOption("Any", ""))),
                rev = s.rev + 1,
            )
        }
        AppLog.add('I', "lab", "extras filter $key (select, 1 options) added by hand")
    }

    fun setFilterKey(index: Int, key: String) = updateFilter(index) { it.copy(key = key.trim()) }

    fun setFilterName(index: Int, name: String) = updateFilter(index) { it.copy(name = name) }

    fun setFilterType(index: Int, type: String) {
        updateFilter(index) { it.copy(type = type) }
        state.value.filters.getOrNull(index)?.let { f ->
            AppLog.add('I', "lab", "extras filter ${f.key} (${f.type}, ${f.options.size} options)")
        }
    }

    fun setFilterOptionsText(index: Int, text: String) =
        updateFilter(index) { it.copy(optionsText = text, options = BuilderExtrasLogic.parseOptions(text)) }

    fun setFilterOnValue(index: Int, value: String) = updateFilter(index) { it.copy(onValue = value) }

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
        return BuilderExtrasLogic.pathOf(url, siteFor(url))
    }

    private fun noteFor(result: BuilderExtrasLogic.PathResult): String? =
        if (result.addedPage) BuilderExtrasLogic.NO_PAGE_MESSAGE else null

    private fun nameFromPath(path: String): String {
        val word = path.substringBefore('?').trim('/').substringAfterLast('/')
            .let { if (it == "{page}" || it.all(Char::isDigit)) path.substringBefore('?').trim('/').split('/').dropLast(2).lastOrNull().orEmpty() else it }
        return BuilderExtrasLogic.prettyName(word.ifEmpty { "Tab" })
    }

    private companion object {
        const val MAX_SEEN = 30
        const val MAX_OPTIONS = 60
    }
}
