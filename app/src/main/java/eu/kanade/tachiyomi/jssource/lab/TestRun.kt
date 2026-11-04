package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.jssource.JsConsoleEntry
import eu.kanade.tachiyomi.jssource.JsFilters
import eu.kanade.tachiyomi.jssource.JsMapping
import eu.kanade.tachiyomi.jssource.JsPreferences
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.heal.HealedPick
import eu.kanade.tachiyomi.jssource.lab.heal.PickFingerprints
import eu.kanade.tachiyomi.jssource.lab.heal.PickHealer
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel.Item
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel.Kind
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel.Result
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * KMK (r96): the Source Lab's Test Run: the Code editor's "Run all" as a visible pipeline with fixed thresholds
 * (D§2.5), the image and video checks (C§8.3), and the saved runs under `js_sources/<key>/testruns/` (last 10).
 *
 * [pipeline] runs the source's methods over any [JsRuntime] (the editor's test copy, or the Re-test job's own) and
 * gives the editor's `Result`s, so the Test panel's cards and the Lab's steps come from the same calls. [run] turns
 * them into [TestStep]s, probes the first image or each hoster's first video, and saves the [TestRun]. A run that
 * is cancelled writes nothing.
 */
internal object TestRuns {

    private const val KEEP = 10
    private const val SKIPPED = "Skipped: "

    private val filesDir: File get() = Injekt.get<Application>().filesDir

    // ---------- saved runs ----------

    private fun dir(key: String): File {
        require(JsSourceManager.KEY_PATTERN.matches(key)) { "Not a valid source key: $key" }
        return LabPaths.testRuns(filesDir, key)
    }

    /** Every saved run of [key], newest first */
    fun list(key: String): List<TestRun> = dir(key).listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") }
        .sortedByDescending { it.name }
        .mapNotNull { file -> runCatching { jsJson.decodeFromString(TestRun.serializer(), file.readText()) }.getOrNull() }

    fun latest(key: String): TestRun? = list(key).firstOrNull()

    fun previous(key: String): TestRun? = list(key).getOrNull(1)

    fun load(key: String, id: String): TestRun? = list(key).firstOrNull { it.id == id }

    private fun write(run: TestRun, console: List<ConsoleRow>) {
        val folder = dir(run.key)
        if (folder.parentFile?.isDirectory != true) return
        folder.mkdirs()
        File(folder, "${run.id}.json").writeText(jsJson.encodeToString(TestRun.serializer(), run))
        // KMK (r102): the console with times and request details beside the run (`<id>.console`, not `.json`, so
        // list() never reads it as a run)
        runCatching {
            File(folder, "${run.id}$CONSOLE_SUFFIX")
                .writeText(jsJson.encodeToString(ListSerializer(ConsoleRow.serializer()), console.takeLast(CONSOLE_KEEP)))
        }
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
        val kept = folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.toSet()
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(CONSOLE_SUFFIX) && it.name.removeSuffix(CONSOLE_SUFFIX) !in kept }
            .forEach { runCatching { it.delete() } }
    }

    // ---------- the console (r102) ----------

    private const val CONSOLE_SUFFIX = ".console"
    private const val CONSOLE_KEEP = 800

    private val liveConsoles = MutableStateFlow<Map<String, LiveConsole>>(emptyMap())

    /** The console of each source's newest Test Run in this session, by source key; it grows while a run runs */
    val consoles: StateFlow<Map<String, LiveConsole>> = liveConsoles.asStateFlow()

    /** The saved console of [run] (r102 and later), else its plain lines (older runs: no times, no details) */
    fun loadConsole(run: TestRun): List<ConsoleRow> {
        val saved = runCatching {
            val file = File(dir(run.key), "${run.id}$CONSOLE_SUFFIX")
            if (file.isFile) jsJson.decodeFromString(ListSerializer(ConsoleRow.serializer()), file.readText()) else null
        }.getOrNull()
        return saved ?: run.console.map(::plainRow)
    }

    private val PLAIN_LINE = Regex("""^\[(\w+)\](?:\[([^\]]*)\])?\s?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** `[net][popular] GET … -> 200 in 85ms` → a row without a time */
    fun plainRow(line: String): ConsoleRow {
        val m = PLAIN_LINE.find(line) ?: return ConsoleRow(-1, "I", "", line)
        return ConsoleRow(-1, levelLetter(m.groupValues[1]), m.groupValues[2], m.groupValues[3])
    }

    private fun levelLetter(level: String): String = when (level) {
        "error" -> "E"
        "warn" -> "W"
        "debug" -> "D"
        "net" -> "N"
        else -> "I"
    }

    private fun row(entry: JsConsoleEntry, startedAt: Long): ConsoleRow = ConsoleRow(
        ms = (entry.at - startedAt).coerceAtLeast(0L),
        level = levelLetter(entry.level),
        step = entry.step.orEmpty(),
        text = entry.text,
        detail = entry.detail,
    )

    private fun addRow(key: String, token: Long, row: ConsoleRow) {
        liveConsoles.update { all ->
            val live = all[key]
            if (live == null || live.token != token) all else all + (key to live.copy(rows = (live.rows + row).takeLast(CONSOLE_KEEP)))
        }
    }

    /** A probe of the run itself (first image, each hoster's video) as a console row of [step] */
    private fun requestRow(request: TestRequest, step: String, startedAt: Long): ConsoleRow = ConsoleRow(
        ms = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
        level = "N",
        step = step,
        text = "${request.method} ${request.url} -> ${request.status ?: "null"} in ${request.ms}ms",
        detail = request.error?.let { "Error: $it" },
    )

    // ---------- the pipeline over a runtime ----------

    /**
     * Tabs (page 1 each) → Popular → Latest → search → filters → settings (r102, when the source has them) → title →
     * chapters → pages or hosters, each as a `Result` with
     * its step id, the `[net]` lines it logged and the raw objects. [onRunning] gets the step's title before it
     * starts, [onResult] each result as it ends. The search word is [query], else the first title's first word.
     */
    suspend fun pipeline(
        rt: JsRuntime,
        anime: Boolean,
        query: String,
        onRunning: (String) -> Unit = {},
        onResult: (Result) -> Unit = {},
    ): List<Result> {
        val results = ArrayList<Result>()
        suspend fun step(id: String, title: String, block: suspend () -> Result): Result {
            onRunning(title)
            rt.stepTag = id
            val before = rt.console.size
            val started = System.currentTimeMillis()
            val result = try {
                val r = block()
                r.copy(title = r.title.ifBlank { title }, millis = System.currentTimeMillis() - started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result(title, false, System.currentTimeMillis() - started, text = e.message ?: e.toString())
            } finally {
                rt.stepTag = null
            }
            val logged = rt.console.let { lines -> if (lines.size >= before) lines.drop(before) else lines }
                .filter { it.startsWith("[net]") || it.startsWith("[warn]") || it.startsWith("[error]") }
            val tagged = result.copy(step = id, requests = logged)
            results.add(tagged)
            onResult(tagged)
            return tagged
        }
        fun skipped(id: String, title: String, why: String): Result =
            Result(title, true, 0, text = SKIPPED + why, step = id).also {
                results.add(it)
                onResult(it)
            }

        val meta = runCatching { rt.metadata() }.getOrNull()
        val tabs = runCatching {
            if (meta?.flag("hasCatalogs") == true) JsFilters.catalogs(rt.call("getCatalogs")).filter { it.tab } else emptyList()
        }.getOrDefault(emptyList())
        val lists = ArrayList<Result>()
        tabs.forEach { tab ->
            lists.add(step(StepId.tab(tab.id), "Tab ${tab.name} · page 1") { entries(rt.call("getCatalog", tab.id, 1)) })
        }
        if (tabs.isEmpty() || meta?.flag("hasPopular") != false) {
            lists.add(step(StepId.POPULAR, "Popular · page 1") { entries(rt.call("getPopular", 1)) })
        }
        if (tabs.isEmpty() || meta?.flag("hasLatest") != false) {
            lists.add(step(StepId.LATEST, "Latest · page 1") { entries(rt.call("getLatest", 1)) })
        }
        val firstEntry = lists.firstNotNullOfOrNull { r -> r.items.firstOrNull { it.kind == Kind.Entry } }
        val word = query.trim().ifBlank { firstEntry?.title?.let(::searchWord).orEmpty() }
        val search = if (word.isBlank()) {
            skipped(StepId.SEARCH, "Search", "no title to search for")
        } else {
            step(StepId.SEARCH, "Search \"$word\"") { entries(rt.call("search", word, 1, emptyMap<String, Any>())) }
        }
        // KMK (r102): Filters (each filter's first value against the unfiltered first page) and Settings
        if (meta?.flag("hasFilters") == true) {
            step(STEP_FILTERS, "Filters") { filtersCheck(rt, lists) }
        }
        if (meta?.flag("hasPreferences") == true) {
            step(STEP_SETTINGS, "Settings") { settingsCheck(rt) }
        }
        val entry = firstEntry ?: search.items.firstOrNull { it.kind == Kind.Entry }
        if (entry == null) {
            skipped(StepId.TITLE, "Title", "no entry to open")
            skipped(StepId.CHAPTERS, if (anime) "Episodes" else "Chapters", "no entry to open")
            skipped(if (anime) StepId.HOSTERS else StepId.PAGES, if (anime) "Hosters" else "Pages", "no entry to open")
            return results
        }
        if (runCatching { rt.has("getDetails") }.getOrDefault(false)) {
            step(StepId.TITLE, "Title · ${entry.title}") {
                val details = rt.call("getDetails", entry.url) as? JsonObject
                val filled = details?.let(::detailFields).orEmpty()
                // KMK (r103): getDetails may leave coverUrl out on purpose (generic-list v4+ does when the title page
                // has none: the app keeps the list's cover). The list gave this title a cover: not counted as missing
                val listCover = details != null && "coverUrl" !in filled && !entry.imageUrl.isNullOrBlank()
                Result(
                    title = "",
                    ok = true,
                    millis = 0,
                    text = if (details == null) {
                        "getDetails returned nothing"
                    } else {
                        "${filled.size} of 8 fields: ${filled.joinToString(", ")}" + if (listCover) LIST_COVER else ""
                    },
                    raw = listOfNotNull(details),
                )
            }
        } else {
            skipped(StepId.TITLE, "Title", "this source has no getDetails")
        }
        // KMK (r107): Suggestions: getRelated on the same title, when the source has it (no step otherwise)
        if (runCatching { rt.has("getRelated") }.getOrDefault(false)) {
            step(STEP_RELATED, "Suggestions · ${entry.title}") { relatedCheck(rt, entry.url) }
        }
        val chapters = step(StepId.CHAPTERS, "${if (anime) "Episodes" else "Chapters"} of ${entry.title}") {
            chapters(rt.call("getChapterList", entry.url), anime)
        }
        val chapter = chapters.items.firstOrNull()
        if (chapter == null) {
            skipped(if (anime) StepId.HOSTERS else StepId.PAGES, if (anime) "Hosters" else "Pages", "no ${if (anime) "episode" else "chapter"} to open")
            return results
        }
        if (anime) {
            step(StepId.HOSTERS, "Videos of ${chapter.title}") { videos(rt.call("getVideoList", chapter.url)) }
        } else {
            step(StepId.PAGES, "Pages of ${chapter.title}") { pages(rt.call("getPageList", chapter.url)) }
        }
        return results
    }

    /** The first word of [title] with three letters or more, else the whole title */
    fun searchWord(title: String): String {
        val words = title.split(Regex("""[\s:：,.!?()\[\]'"-]+""")).filter { it.isNotBlank() }
        return words.firstOrNull { it.count { c -> c.isLetterOrDigit() } >= 3 } ?: title.trim()
    }

    /** KMK (r103): the Title result's text ends with this when coverUrl was left out and the list gave a cover */
    private const val LIST_COVER = " · coverUrl from the list"

    private val DETAIL_FIELDS = listOf("title", "coverUrl", "description", "author", "genres", "status", "webUrl", "rating")

    /** Which of the eight detail fields [details] filled */
    fun detailFields(details: JsonObject): List<String> = DETAIL_FIELDS.filter { key ->
        when (val v = details[key]) {
            null -> false
            is JsonArray -> v.isNotEmpty()
            is JsonPrimitive -> !v.contentOrNull.isNullOrBlank() && v.contentOrNull != "null"
            else -> true
        }
    }

    private fun entries(element: JsonElement): Result {
        val list = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            title = "",
            ok = true,
            millis = 0,
            items = list.map { e ->
                Item(title = e.str("title").orEmpty(), subtitle = e.str("url"), imageUrl = e.str("coverUrl"), kind = Kind.Entry, url = e.str("url").orEmpty())
            },
            text = if (list.isEmpty()) "No results: the source returned an empty list." else "${list.size} entries",
            raw = list.take(60),
        )
    }

    private fun chapters(element: JsonElement, anime: Boolean): Result {
        val list = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            title = "",
            ok = true,
            millis = 0,
            items = list.map { ch ->
                Item(
                    title = ch.str("title") ?: ch.str("number")?.let { "${if (anime) "Episode" else "Chapter"} $it" } ?: ch.str("url").orEmpty(),
                    subtitle = listOfNotNull(ch.str("dateUploaded"), ch.str("scanlator")).joinToString(" · ").ifBlank { null },
                    imageUrl = ch.str("thumbnailUrl") ?: ch.str("seasonCoverUrl"),
                    kind = Kind.Chapter,
                    url = ch.str("url").orEmpty(),
                )
            },
            text = "${list.size} ${if (anime) "episodes" else "chapters"}",
            raw = list.take(60),
        )
    }

    private fun pages(element: JsonElement): Result {
        val list = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            title = "",
            ok = true,
            millis = 0,
            items = list.mapIndexed { index, p ->
                val number = (p.str("index")?.toIntOrNull() ?: index) + 1
                Item(
                    title = if (p.str("videoUrl") != null || p.str("videoListUrl") != null) "Page $number · video" else "Page $number",
                    subtitle = p.str("videoUrl") ?: p.str("videoListUrl") ?: p.str("imageUrl") ?: p.str("text")?.take(120),
                    imageUrl = p.str("imageUrl") ?: p.str("thumbnailUrl"),
                    kind = Kind.Page,
                    url = p.str("videoUrl") ?: p.str("imageUrl").orEmpty(),
                )
            },
            text = "${list.size} pages",
            raw = list.take(60),
        )
    }

    private fun videos(element: JsonElement): Result {
        val list = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            title = "",
            ok = true,
            millis = 0,
            items = list.map { v ->
                Item(
                    title = v.str("quality") ?: "Video",
                    subtitle = listOfNotNull(v.str("server"), v.str("url")).joinToString(" · "),
                    kind = Kind.Video,
                    url = v.str("url").orEmpty(),
                )
            },
            text = "${list.size} videos",
            raw = list.take(60),
        )
    }

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    // ---------- Filters and Settings steps (r102) ----------

    const val STEP_FILTERS = "filters"
    const val STEP_SETTINGS = "settings"
    private const val MAX_FILTERS = 6
    private const val WORKS = "changes the list"
    private const val NOTHING_TO_COMPARE = "nothing to compare with"
    private const val SAME = "same as without it"
    private const val FAILED = "failed: "
    private const val PROBLEM = " · ! "

    /** The values a filter is tried with: its first non-empty option (Sort: its first two), as search() gets them */
    private fun tryValues(g: JsFilters.Group): List<Pair<String, JsonElement>> {
        fun filled(o: JsFilters.Option): Boolean = when (val v = o.value) {
            is JsonPrimitive -> !v.contentOrNull.isNullOrBlank()
            is JsonNull -> false
            else -> true
        }
        return when (g.type) {
            JsFilters.Type.Select -> g.options.firstOrNull(::filled)?.let { listOf(it.label to it.value) }.orEmpty()
            JsFilters.Type.MultiSelect, JsFilters.Type.AutoComplete ->
                g.options.firstOrNull(::filled)?.let { listOf(it.label to JsonArray(listOf(it.value))) }.orEmpty()
            JsFilters.Type.Checkbox -> listOf((if (g.defaultChecked) "off" else "on") to JsonPrimitive(!g.defaultChecked))
            JsFilters.Type.TriState -> listOf("include" to JsonPrimitive(1))
            // KMK (r107) fix: a collection sends its first entry's value, as the drop-down does
            JsFilters.Type.Collection -> g.collection?.entries?.firstOrNull()?.let { listOf(it.label to JsonPrimitive(it.value)) }.orEmpty()
            // r105 fix: a Sort's empty "Site default" (first) sends nothing: its first two real orders are tried
            JsFilters.Type.Sort -> g.options.withIndex().filter { filled(it.value) }.take(2).map { iv ->
                iv.value.label to buildJsonObject {
                    put("index", JsonPrimitive(iv.index))
                    put("value", iv.value.value)
                    put("ascending", JsonPrimitive(false))
                }
            }
            else -> emptyList()
        }
    }

    /** What one search() of the Filters step gave: the titles' addresses (null when it threw), the error, the request */
    private data class Searched(val urls: List<String>?, val error: String?, val address: String)

    /**
     * The addresses of a list search() returned; null when it threw (cancellation goes through). r103: with the
     * address it fetched ("GET /en/trending/today?page=1", the first request it logged), so a wrong learned address
     * shows in the step.
     */
    private suspend fun searchUrls(rt: JsRuntime, filters: Map<String, JsonElement>): Searched {
        val started = System.currentTimeMillis()
        val outcome: Pair<List<String>?, String?> = try {
            val list = (rt.call("search", "", 1, filters) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("url") }
            list to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null to (e.message ?: e.toString()).lineSequence().firstOrNull().orEmpty().take(120)
        }
        val (urls, error) = outcome
        val request = runCatching {
            rt.consoleEntries.asSequence()
                .filter { it.at >= started && it.level == "net" }
                .mapNotNull { parseNetLine(it.line) }
                .firstOrNull { it.url.isNotBlank() }
        }.getOrNull()
        val address = request?.let { "${it.method} ${pathOf(it.url)}" } ?: "no request logged"
        return Searched(urls, error, address)
    }

    /** `https://site/en/trending/today?page=1` → `/en/trending/today?page=1` (the address as the site sees it) */
    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash)
    }

    /** r103: a Filters try's subtitle is "<address> → <verdict>"; the verdict alone (older runs: the whole subtitle) */
    private fun verdictOf(subtitle: String?): String = subtitle.orEmpty().substringAfter(ARROW)

    private const val ARROW = " → "

    /** r105: the Filters step's first item when the list without filters is empty */
    private const val BASE_TITLE = "Without filters"
    private const val EMPTY_BASE = "0 titles · filters are on a page with nothing listed"

    /**
     * Each filter (max 6, r105: the built-in List first) once with its first non-empty value, a Sort with its first two, through `search("", 1,
     * { key: value })`; the titles count and whether the list differs from the unfiltered first page (Sort: from
     * each other). One item per try; its subtitle carries the verdict [judge] reads.
     */
    private suspend fun filtersCheck(rt: JsRuntime, lists: List<Result>): Result {
        val raw = rt.call("getFilters")
        val groups = JsFilters.flat(JsFilters.groups(raw))
        // r105: each filter's role as getFilters says it (generic-list v8: "page" picks the list, "extra" adds to it)
        val roles = HashMap<String, String>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonArray -> e.forEach { walk(it) }
                is JsonObject -> {
                    val key = e.str("key")
                    val role = e.str("role")
                    if (key != null && role != null) roles[key] = role
                    walk(e["filters"] ?: e["children"] ?: e["items"])
                }
                else -> Unit
            }
        }
        runCatching { walk(raw) }
        val tried = groups.filter { tryValues(it).isNotEmpty() }.take(MAX_FILTERS)
        if (tried.isEmpty()) {
            return Result(
                title = "",
                ok = true,
                millis = 0,
                text = if (groups.isEmpty()) "getFilters returned no filters" else "${groups.size} filters, none with a value to try",
            )
        }
        val unfiltered = searchUrls(rt, emptyMap())
        val base = unfiltered.urls?.takeIf { it.isNotEmpty() } ?: lists.firstOrNull { it.items.isNotEmpty() }?.items?.map { it.url }
        val items = ArrayList<Item>()
        // r105: the list the filters go on already gives nothing: every try would say "0 titles" for the wrong reason
        if (unfiltered.urls != null && unfiltered.urls.isEmpty()) {
            val line = "$BASE_TITLE$ARROW${unfiltered.address}$ARROW$EMPTY_BASE"
            AppLog.add('W', "lab", "Test Run filter $line")
            runCatching { rt.onConsole?.invoke(JsConsoleEntry(System.currentTimeMillis(), "warn", STEP_FILTERS, line)) }
            items.add(Item(title = BASE_TITLE, subtitle = "${unfiltered.address}$ARROW$EMPTY_BASE", kind = Kind.Other, url = ""))
        }
        tried.forEach { g ->
            val values = tryValues(g)
            val outcomes = values.map { (label, value) -> label to searchUrls(rt, mapOf(g.key to value)) }
            outcomes.forEachIndexed { index, (label, outcome) ->
                val urls = outcome.urls
                val against = if (g.type == JsFilters.Type.Sort && outcomes.size == 2) {
                    outcomes[1 - index].second.urls
                } else {
                    base
                }
                val verdict = when {
                    urls == null -> FAILED + outcome.error.orEmpty()
                    urls.isEmpty() -> "0 titles · check this filter's address (made from a menu: pick it again and press Make filter)"
                    against == null -> "${urls.size} titles · $NOTHING_TO_COMPARE"
                    urls != against -> "${urls.size} titles · $WORKS"
                    else -> "${urls.size} titles · $SAME"
                }
                // r103: the exact address tried, so a wrong learned address is visible (Logs, the run's console, the step)
                // r105: with its role (page: picks the list; extra: added to the list)
                val name = g.name + (roles[g.key]?.let { " ($it)" } ?: "")
                val line = "$name = $label$ARROW${outcome.address}$ARROW$verdict"
                AppLog.add('D', "lab", "Test Run filter $line")
                runCatching { rt.onConsole?.invoke(JsConsoleEntry(System.currentTimeMillis(), "info", STEP_FILTERS, line)) }
                items.add(Item(title = "$name = $label", subtitle = "${outcome.address}$ARROW$verdict", kind = Kind.Other, url = ""))
            }
        }
        // KMK (r107) fix: a live collection's directory: getDirectory's page 1 once (its own item, not a try)
        val liveDirectory = groups.firstOrNull { it.type == JsFilters.Type.Collection && it.collection?.live == true }
        if (liveDirectory != null && runCatching { rt.has("getDirectory") }.getOrDefault(false)) {
            val verdict = try {
                val r = rt.call("getDirectory", liveDirectory.key, 1, "", false, emptyMap<String, String>())
                val n = JsMapping.list((r as? JsonObject)?.get("items") ?: r).size
                if (n > 0) "$n entries on page 1" else "0 entries on page 1 · check the directory's card selector"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FAILED + (e.message ?: e.toString()).lineSequence().firstOrNull().orEmpty().take(120)
            }
            val line = "$DIRECTORY${liveDirectory.name}${ARROW}getDirectory$ARROW$verdict"
            AppLog.add('D', "lab", "Test Run filter $line")
            runCatching { rt.onConsole?.invoke(JsConsoleEntry(System.currentTimeMillis(), "info", STEP_FILTERS, line)) }
            items.add(Item(title = "$DIRECTORY${liveDirectory.name}", subtitle = "getDirectory$ARROW$verdict", kind = Kind.Other, url = ""))
        }
        return Result(title = "", ok = true, millis = 0, items = items, text = "${items.size} tries")
    }

    /** KMK (r107) fix: the Filters step's item for a live collection's getDirectory page 1 */
    private const val DIRECTORY = "Directory · "

    // ---------- Suggestions step (r107) ----------

    const val STEP_RELATED = "related"

    /** The text after this in the Suggestions result is why the title page itself wasn't read */
    private const val PAGE_NOTE = " · title page: "

    /** generic-list v10's console line for a section that failed (failed sections are left out of the result) */
    private val RELATED_FAILED = Regex("""^Suggestions (.+?) failed after \d+ ms: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** generic-list v10's console line when the title page couldn't be read (sections read elsewhere may still come) */
    private val RELATED_PAGE = Regex("""^Suggestions: the title page wasn't read: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * getRelated(url) gives one list or named groups `{ "You may like": [cards], … }`: one item per group with its
     * card count and first title; groups the source left out because they failed (its console says
     * "Suggestions <name> failed after N ms: <why>") are added as failed items. The raw object of each item is
     * `{ group, cards, titles[≤5], error? }`.
     */
    private suspend fun relatedCheck(rt: JsRuntime, url: String): Result {
        val started = System.currentTimeMillis()
        val result = rt.call("getRelated", url)
        val failures = LinkedHashMap<String, String>()
        var pageError: String? = null
        runCatching {
            rt.consoleEntries.filter { it.at >= started }.forEach { e ->
                RELATED_FAILED.find(e.text)?.let { m -> failures[m.groupValues[1]] = m.groupValues[2].trim() }
                RELATED_PAGE.find(e.text)?.let { m -> pageError = m.groupValues[1].trim() }
            }
        }
        val groups: List<Pair<String, JsonElement>> = when (result) {
            is JsonObject -> result.entries.map { it.key to it.value }
            else -> listOf("" to result)
        }
        val items = ArrayList<Item>()
        val raws = ArrayList<JsonObject>()
        groups.forEach { (name, value) ->
            val shown = name.ifBlank { "One list" }
            val ownError = ((value as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
            val cards = JsMapping.list(value)
            val error = ownError ?: failures[name]?.takeIf { cards.isEmpty() }
            val first = cards.firstOrNull()
            val noUrl = cards.count { it.str("url").isNullOrBlank() }
            val subtitle = when {
                error != null -> FAILED + error.take(200)
                cards.isEmpty() -> "0 cards"
                else -> "${cards.size} cards · first: ${first?.str("title").orEmpty().take(80)}" +
                    (if (noUrl > 0) " · $noUrl without url" else "")
            }
            items.add(Item(title = shown, subtitle = subtitle, imageUrl = first?.str("coverUrl"), kind = Kind.Other, url = first?.str("url").orEmpty()))
            raws.add(groupRaw(shown, cards, error))
        }
        failures.filterKeys { failed -> groups.none { it.first == failed } }.forEach { (name, why) ->
            items.add(Item(title = name, subtitle = FAILED + why.take(200), kind = Kind.Other, url = ""))
            raws.add(groupRaw(name, emptyList(), why))
        }
        val total = raws.sumOf { (it["cards"] as? JsonPrimitive)?.intOrNull ?: 0 }
        val text = "${items.size} ${if (items.size == 1) "group" else "groups"}, $total cards" +
            (pageError?.let { PAGE_NOTE + it.take(160) }.orEmpty())
        return Result(title = "", ok = true, millis = 0, items = items, text = text, raw = raws)
    }

    private fun groupRaw(name: String, cards: List<JsonObject>, error: String?): JsonObject = buildJsonObject {
        put("group", JsonPrimitive(name))
        put("cards", JsonPrimitive(cards.size))
        put("titles", JsonArray(cards.take(5).map { JsonPrimitive(it.str("title").orEmpty()) }))
        if (error != null) put("error", JsonPrimitive(error))
    }

    /** getSourcePreferences(): keys and defaults, and what the app can't use (no key, twice, bad default) */
    private suspend fun settingsCheck(rt: JsRuntime): Result {
        val raw = rt.call("getSourcePreferences")
        val list = raw as? JsonArray ?: return Result("", false, 0, text = "getSourcePreferences didn't return a list")
        val defs = JsPreferences.parse(raw)
        val general = ArrayList<String>()
        val nonObjects = list.count { it !is JsonObject }
        if (nonObjects > 0) general.add("$nonObjects entries aren't objects")
        val dropped = list.count { it is JsonObject } - defs.size
        if (dropped > 0) general.add("$dropped without a key (not shown)")
        val settings = defs.filter { it.type != JsPreferences.Type.Category && it.type != JsPreferences.Type.Info }
        val twice = settings.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (twice.isNotEmpty()) general.add("key used twice: ${twice.joinToString(", ")}")
        val items = settings.map { def ->
            val default = def.defaultValue
            val text = when (default) {
                null -> "none"
                is JsonPrimitive -> default.contentOrNull ?: "null"
                else -> default.toString().take(60)
            }
            val problem = when (def.type) {
                JsPreferences.Type.List -> when {
                    def.options.isEmpty() -> "a list with no options"
                    default is JsonPrimitive && def.options.none { it.value == default.contentOrNull } ->
                        "default \"$text\" isn't one of the options"
                    else -> null
                }
                JsPreferences.Type.MultiList -> when {
                    def.options.isEmpty() -> "a list with no options"
                    default != null && default !is JsonArray -> "default isn't a list"
                    else -> null
                }
                JsPreferences.Type.Switch ->
                    if (default != null && (default as? JsonPrimitive)?.booleanOrNull == null) "default isn't true or false" else null
                JsPreferences.Type.Slider -> def.slider?.let { sl ->
                    val numbers = text.split('-').mapNotNull { it.trim().toIntOrNull() }
                    if (default != null && (numbers.isEmpty() || numbers.any { it < sl.min || it > sl.max })) {
                        "default $text is outside ${sl.min}–${sl.max}"
                    } else {
                        null
                    }
                }
                else -> null
            }
            Item(
                title = def.title.ifBlank { def.key },
                subtitle = "${def.key} · ${def.type.name.lowercase()} · default $text" + (problem?.let { PROBLEM + it }.orEmpty()),
                kind = Kind.Other,
                url = "",
            )
        }
        val text = "${settings.size} settings: ${settings.take(8).joinToString(", ") { it.key }}" +
            (if (general.isNotEmpty()) PROBLEM + general.joinToString("; ") else "")
        return Result(title = "", ok = true, millis = 0, items = items, text = text)
    }

    // ---------- a full run ----------

    /**
     * Runs the pipeline on [rt], judges every step (D§2.5), probes the first page image or each hoster's first video
     * with the source's [headers], saves the run and returns it. [onProgress] gets the steps so far, the running
     * one last with [StepStatus.Running].
     */
    suspend fun run(
        key: String,
        rt: JsRuntime,
        anime: Boolean,
        headers: Map<String, String>,
        baseUrl: String,
        query: String,
        // KMK (r116): the code under test (the editor's text, saved or not) for self-healing; null = the saved code
        code: String? = null,
        onProgress: (List<TestStep>) -> Unit = {},
    ): TestRun = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val steps = ArrayList<TestStep>()
        var running: TestStep? = null
        fun publish() = onProgress(steps + listOfNotNull(running))
        var effectiveQuery = query
        // KMK (r116): the Title step's detail fields that came back empty (for self-healing)
        var missingDetails: List<String> = emptyList()
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        // KMK (r102): the Test Run console: every console line of this run, live, then saved beside the run
        val token = System.nanoTime()
        liveConsoles.update { it + (key to LiveConsole(key = key, token = token, running = true)) }
        val sink: (JsConsoleEntry) -> Unit = { entry -> addRow(key, token, row(entry, startedAt)) }
        rt.onConsole = sink
        try {
            pipeline(
                rt = rt,
                anime = anime,
                query = query,
                onRunning = { title ->
                    running = TestStep(id = "", name = title, status = StepStatus.Running, count = 0, ms = 0, evidence = "")
                    publish()
                },
                onResult = { result ->
                    if (result.step == StepId.SEARCH) effectiveQuery = result.title.substringAfter('"', "").substringBeforeLast('"', "")
                    if (result.step == StepId.TITLE && result.ok && result.text?.startsWith(SKIPPED) != true) {
                        val filled = result.raw.firstOrNull()?.let(::detailFields).orEmpty() +
                            if (result.text?.endsWith(LIST_COVER) == true) listOf("coverUrl") else emptyList()
                        missingDetails = DETAIL_FIELDS.filter { it !in filled }
                    }
                    steps.add(judge(result, anime))
                    running = null
                    publish()
                },
            )
            // The image check (manga) or the video checks (anime), after the pipeline so the source's calls stay in order
            val sourceHeaders = headers.toMutableMap()
            if (sourceHeaders.keys.none { it.equals("Referer", ignoreCase = true) } && baseUrl.startsWith("http")) {
                sourceHeaders["Referer"] = baseUrl.trimEnd('/') + "/"
            }
            if (!anime) {
                val pagesIndex = steps.indexOfFirst { it.id == StepId.PAGES }
                if (pagesIndex >= 0 && steps[pagesIndex].status == StepStatus.Green) {
                    running = steps[pagesIndex].copy(status = StepStatus.Running)
                    publish()
                    val before = steps[pagesIndex].requests.size
                    steps[pagesIndex] = withImageCheck(steps[pagesIndex], sourceHeaders)
                    steps[pagesIndex].requests.drop(before).forEach { addRow(key, token, requestRow(it, StepId.PAGES, startedAt)) }
                    running = null
                    publish()
                }
            } else {
                val hosters = steps.firstOrNull { it.id == StepId.HOSTERS }
                if (hosters != null && hosters.status != StepStatus.Red && hosters.status != StepStatus.Skipped) {
                    running = TestStep(StepId.VIDEO, "Video", StepStatus.Running, 0, 0, "")
                    publish()
                    val video = videoStep(hosters, sourceHeaders)
                    video.requests.forEach { addRow(key, token, requestRow(it, StepId.VIDEO, startedAt)) }
                    steps.add(video)
                    running = null
                    publish()
                } else if (hosters != null) {
                    steps.add(TestStep(StepId.VIDEO, "Video", StepStatus.Skipped, 0, 0, "no hoster to play"))
                    publish()
                }
            }
            // KMK (r116): self-healing: broken list / title steps look for their picks again (the code isn't changed)
            try {
                val tested = code ?: runCatching { Injekt.get<JsSourceManager>().code(key) }.getOrNull()
                if (tested != null) healSteps(key, steps, tested, sourceHeaders, missingDetails)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('W', "lab", "Self-healing of $key failed: ${e.message ?: e}")
            }
            publish()
            val run = TestRun(
                id = runId,
                key = key,
                startedAt = startedAt,
                totalMs = System.currentTimeMillis() - startedAt,
                query = effectiveQuery,
                steps = steps,
                console = rt.console.takeLast(200),
                version = runCatching { History.latestVersion(key) }.getOrDefault(0),
                rulesVersion = runCatching { Lint.rulesVersion() }.getOrNull(),
                appRelease = BuildConfig.RELEASE_NUMBER,
            )
            val consoleRows = liveConsoles.value[key]?.takeIf { it.token == token }?.rows.orEmpty()
            liveConsoles.update { all -> all[key]?.takeIf { it.token == token }?.let { all + (key to it.copy(runId = run.id, running = false)) } ?: all }
            runCatching { write(run, consoleRows) }.onFailure { AppLog.add('W', "lab", "Couldn't save the Test Run of $key: ${it.message}") }
            runCatching { History.noteTestRun(key, run.id) }
            AppLog.add(
                'I',
                "lab",
                "Test Run of $key: ${run.passed} of ${run.counted} passed in ${run.totalMs} ms (${run.worst.name.lowercase()})" +
                    " · console ${consoleRows.size} lines",
            )
            run
        } finally {
            if (rt.onConsole === sink) rt.onConsole = null
            liveConsoles.update { all -> all[key]?.takeIf { it.token == token && it.running }?.let { all + (key to it.copy(running = false)) } ?: all }
        }
    }

    /** One `Result` → one judged step (the thresholds of D§2.5) */
    private fun judge(result: Result, anime: Boolean): TestStep {
        val id = result.step.orEmpty()
        val name = stepName(id, result, anime)
        val partIds = when (id) {
            STEP_FILTERS -> listOf("getFilters", "search")
            STEP_SETTINGS -> listOf("getSourcePreferences")
            STEP_RELATED -> listOf("getRelated")
            else -> PartIndex.partsOfStep(id, anime)
        }
        val requests = result.requests.mapNotNull(::parseNetLine)
        val items = result.items.take(60).mapIndexed { index, item ->
            TestItem(
                title = item.title,
                subtitle = item.subtitle,
                imageUrl = item.imageUrl,
                url = item.url,
                kind = when (item.kind) {
                    Kind.Entry -> "entry"
                    Kind.Chapter -> "chapter"
                    Kind.Page -> "page"
                    Kind.Video -> "video"
                    Kind.Other -> "other"
                },
                raw = result.raw.getOrNull(index),
            )
        }
        fun step(status: StepStatus, count: Int, evidence: String) =
            TestStep(id, name, status, count, result.millis, evidence, items, requests, partIds)
        if (result.text?.startsWith(SKIPPED) == true) {
            return step(StepStatus.Skipped, 0, result.text.removePrefix(SKIPPED))
        }
        // KMK (r107) fix: Suggestions are optional: a failed getRelated is a warning (Amber), never Red
        if (!result.ok) return step(if (id == STEP_RELATED) StepStatus.Amber else StepStatus.Red, 0, failureWords(result.text.orEmpty(), requests))
        val count = result.items.size
        return when {
            id.startsWith("tab:") || id == StepId.POPULAR || id == StepId.LATEST -> {
                val total = result.raw.size.coerceAtLeast(count)
                val withUrl = result.items.count { it.url.isNotBlank() }
                val withCover = result.items.count { !it.imageUrl.isNullOrBlank() }
                val dropped = total - withUrl
                val noCover = count > 0 && withCover * 5 < count * 4
                when {
                    count == 0 -> step(StepStatus.Red, 0, "0 entries after ${requests.lastOrNull()?.status ?: "no reply"}")
                    dropped > 0 -> step(StepStatus.Amber, count, "page 1 · $dropped without url · covers $withCover/$count")
                    noCover -> step(StepStatus.Amber, count, "page 1 · covers $withCover/$count (more than 20 % missing)")
                    else -> step(StepStatus.Green, count, "page 1 · covers $withCover/$count · all with url")
                }
            }
            id == StepId.SEARCH -> when {
                count == 0 -> step(StepStatus.Amber, 0, "0 entries for this word · ${requests.lastOrNull()?.status ?: "no request logged"}")
                else -> step(StepStatus.Green, count, "page 1 · first: ${result.items.first().title.take(60)}")
            }
            id == StepId.TITLE -> {
                val details = result.raw.firstOrNull()
                // r103: coverUrl left out but the list gave the title a cover (the app keeps it): counted as there
                val listCover = result.text?.endsWith(LIST_COVER) == true
                val filled = details?.let(::detailFields).orEmpty().let { if (listCover) it + "coverUrl" else it }
                val missing = DETAIL_FIELDS - filled.toSet()
                val coverNote = if (listCover) " · cover from the list" else ""
                when {
                    details == null -> step(StepStatus.Amber, 0, "getDetails returned nothing")
                    filled.size >= 6 -> step(StepStatus.Green, filled.size, "${filled.size} of 8 fields$coverNote" + if (missing.isEmpty()) "" else " · without ${missing.joinToString(", ")}")
                    else -> step(StepStatus.Amber, filled.size, "only ${filled.size} of 8 fields$coverNote · missing ${missing.joinToString(", ")}")
                }
            }
            id == StepId.CHAPTERS -> {
                val raw = result.raw
                val unnumbered = raw.count { (it["number"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() == null }
                val undated = raw.count { JsMapping.date(it.str("dateUploaded")) == 0L }
                val numbers = raw.mapNotNull { (it["number"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                val range = if (numbers.isEmpty()) "" else "numbers ${trimNumber(numbers.min())}–${trimNumber(numbers.max())}"
                when {
                    count == 0 -> step(StepStatus.Red, 0, "0 ${if (anime) "episodes" else "chapters"} after ${requests.lastOrNull()?.status ?: "no reply"}")
                    unnumbered > 0 || undated > 0 -> step(
                        StepStatus.Amber,
                        count,
                        listOfNotNull(
                            unnumbered.takeIf { it > 0 }?.let { "$it without number" },
                            undated.takeIf { it > 0 }?.let { "$it without date" },
                        ).joinToString(" · ") + " (of the first ${raw.size})",
                    )
                    else -> step(StepStatus.Green, count, listOf(range, "dates read").filter { it.isNotEmpty() }.joinToString(" · "))
                }
            }
            id == StepId.PAGES -> when {
                count == 0 -> step(StepStatus.Red, 0, "0 pages after ${requests.lastOrNull()?.status ?: "no reply"}")
                else -> step(StepStatus.Green, count, "$count pages")
            }
            id == StepId.HOSTERS -> {
                val servers = result.raw.mapNotNull { it.str("server")?.takeIf { s -> s.isNotBlank() } }.distinct()
                when {
                    count == 0 -> step(StepStatus.Red, 0, "0 videos after ${requests.lastOrNull()?.status ?: "no reply"}")
                    servers.isEmpty() -> step(StepStatus.Amber, count, "$count videos without a server (ungrouped)")
                    else -> step(StepStatus.Green, servers.size, servers.joinToString(" · "))
                }
            }
            id == STEP_FILTERS -> {
                // r105: the empty unfiltered list is a warning of its own, not one of the tries
                val emptyBase = result.items.firstOrNull { it.title == BASE_TITLE }
                val tries = result.items.filter { it.title != BASE_TITLE && !it.title.startsWith(DIRECTORY) }
                // KMK (r107) fix: a live collection's getDirectory page 1, said after the tries (a failure makes it Amber)
                val directory = result.items.firstOrNull { it.title.startsWith(DIRECTORY) }
                val dirNote = directory?.let { " · ${it.title.removePrefix(DIRECTORY)}: ${verdictOf(it.subtitle)}" }.orEmpty()
                val dirBad = directory != null && verdictOf(directory.subtitle).let { it.startsWith(FAILED) || it.startsWith("0 ") }
                val warning = emptyBase?.let { "filters are on a page with nothing listed (${it.subtitle.orEmpty().substringBefore(ARROW)}) · " }.orEmpty()
                val verdicts = tries.map { verdictOf(it.subtitle) }
                val works = verdicts.count { WORKS in it || NOTHING_TO_COMPARE in it }
                val failed = verdicts.count { it.startsWith(FAILED) }
                // r103: "Sort = Today → GET /en/trending/today?page=1 → 0 titles" (the address tried, then the verdict)
                val off = tries.filter { item -> verdictOf(item.subtitle).let { WORKS !in it && NOTHING_TO_COMPARE !in it } }
                    .take(2).joinToString(" · ") { "${it.title}$ARROW${it.subtitle}" }
                val n = tries.size
                when {
                    n == 0 -> step(StepStatus.Amber, 0, warning + result.text.orEmpty() + dirNote)
                    failed == n -> step(StepStatus.Red, 0, "${warning}every filter failed · ${off.take(240)}$dirNote")
                    works == n && emptyBase == null && !dirBad -> step(StepStatus.Green, works, "$works of $n tries change the list$dirNote")
                    else -> step(StepStatus.Amber, works, "$warning$works of $n tries change the list · ${off.take(240)}$dirNote")
                }
            }
            id == STEP_SETTINGS -> {
                val problems = result.items.filter { PROBLEM in it.subtitle.orEmpty() }
                val general = result.text.orEmpty().substringAfter(PROBLEM, "")
                when {
                    count == 0 && general.isEmpty() -> step(StepStatus.Amber, 0, "getSourcePreferences returned no settings")
                    problems.isNotEmpty() || general.isNotEmpty() -> step(
                        StepStatus.Amber,
                        count,
                        (listOfNotNull(general.takeIf { it.isNotEmpty() }) +
                            problems.take(3).map { "${it.title}: ${it.subtitle.orEmpty().substringAfter(PROBLEM)}" })
                            .joinToString(" · "),
                    )
                    else -> step(StepStatus.Green, count, result.text.orEmpty().take(160))
                }
            }
            // KMK (r107): Suggestions: count = all cards; Amber when any group failed or none came (optional)
            id == STEP_RELATED -> {
                val cards = result.raw.sumOf { (it["cards"] as? JsonPrimitive)?.intOrNull ?: 0 }
                val failed = result.items.count { it.subtitle.orEmpty().startsWith(FAILED) }
                val page = result.text.orEmpty().substringAfter(PAGE_NOTE, "").takeIf { it.isNotBlank() }
                    ?.let { "title page not read: $it · " }.orEmpty()
                val groups = result.items.joinToString(" · ") { item ->
                    val sub = item.subtitle.orEmpty()
                    if (sub.startsWith(FAILED)) "${item.title}: ${sub.take(160)}" else "${item.title} ${sub.substringBefore(" · ")}"
                }
                when {
                    result.items.isEmpty() -> step(StepStatus.Amber, 0, "${page}getRelated gave no suggestions for this title")
                    // KMK (r107) fix: optional: Amber even when every group failed
                    failed == result.items.size -> step(StepStatus.Amber, 0, (page + groups).take(300))
                    failed > 0 || cards == 0 -> step(StepStatus.Amber, cards, (page + groups).take(300))
                    else -> step(StepStatus.Green, cards, (page + groups).take(300))
                }
            }
            else -> step(StepStatus.Green, count, result.text.orEmpty().take(120))
        }
    }

    private fun stepName(id: String, result: Result, anime: Boolean): String = when {
        id.startsWith("tab:") -> result.title
        id == StepId.POPULAR -> "Popular"
        id == StepId.LATEST -> "Latest"
        id == StepId.SEARCH -> result.title.ifBlank { "Search" }
        id == StepId.TITLE -> "Title"
        id == StepId.CHAPTERS -> if (anime) "Episodes" else "Chapters"
        id == StepId.PAGES -> "Pages"
        id == StepId.HOSTERS -> "Hosters"
        id == STEP_FILTERS -> "Filters"
        id == STEP_SETTINGS -> "Settings"
        id == STEP_RELATED -> "Suggestions"
        else -> result.title
    }

    private fun trimNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

    /** The failure in words: the exception's first line plus the last failed request */
    private fun failureWords(message: String, requests: List<TestRequest>): String {
        val first = message.lineSequence().firstOrNull().orEmpty().take(140)
        val failed = requests.lastOrNull { it.status == null || it.status >= 400 }
        return if (failed != null && failed.url !in first) "$first · ${failed.method} ${shortUrl(failed.url)} → ${failed.status ?: failed.error ?: "no reply"}" else first
    }

    private fun shortUrl(url: String): String = url.substringAfter("://").take(70)

    // KMK (r106): `· 3412 bytes` at the end is the reply's size (JsRuntime's fetch logs it)
    private val NET_LINE = Regex("""^\[net\](?:\[[^\]]*\])?\s+(\w+)\s+(\S+)\s+->\s+(\d+|null)(?:\s+in\s+(\d+)ms)?(\s+\(through the browser\))?(?:\s+·\s+(\d+) bytes)?""")
    private val FETCH_FAILED = Regex("""^\[warn\](?:\[[^\]]*\])?\s+fetch failed:\s*(.*)$""")

    /** `[net] GET url -> 200 in 85ms`, `[net][popular] …`, `(through the browser)`, `[warn] fetch failed: …` */
    fun parseNetLine(line: String): TestRequest? {
        NET_LINE.find(line)?.let { m ->
            return TestRequest(
                method = m.groupValues[1],
                url = m.groupValues[2],
                status = m.groupValues[3].toIntOrNull(),
                ms = m.groupValues[4].toLongOrNull() ?: 0L,
                viaBrowser = m.groupValues[5].isNotEmpty(),
                bytes = m.groupValues[6].toLongOrNull(),
            )
        }
        FETCH_FAILED.find(line)?.let { m ->
            return TestRequest(method = "GET", url = "", status = null, ms = 0, error = m.groupValues[1].take(200))
        }
        return null
    }

    // ---------- self-healing picks (r116) ----------

    /** The Title step's detail fields → the builder's pick labels */
    private val DETAIL_LABELS = linkedMapOf(
        "coverUrl" to "bigCover",
        "description" to "description",
        "genres" to "tags",
        "author" to "author",
        "status" to "status",
    )

    /**
     * Popular / Latest with 0 entries (or titles / covers empty on every entry) and a Title step with empty fields:
     * when the source has pick fingerprints and generic-list selectors, the step's page is read again (one GET of the
     * address the step fetched, the source's headers) and [PickHealer] looks for each broken pick. Found ones go into
     * [TestStep.healed] and the step turns Amber "N picks moved". Nothing is written into the code here.
     */
    private fun healSteps(
        key: String,
        steps: MutableList<TestStep>,
        code: String,
        headers: Map<String, String>,
        missingDetails: List<String>,
    ) {
        val fps = PickFingerprints.loadAll(key)
        if (fps.isEmpty()) return
        val selectors = PickHealer.selectorsInCode(code)
        if (selectors.isEmpty()) return
        val pages = HashMap<String, Document?>()
        fun page(step: TestStep): Document? {
            val url = step.requests.firstOrNull { it.method == "GET" && it.url.startsWith("http") && (it.status ?: 0) in 200..299 }?.url
                ?: step.requests.firstOrNull { it.method == "GET" && it.url.startsWith("http") }?.url
                ?: return null
            return pages.getOrPut(url) { fetchPage(url, headers) }
        }
        fun works(doc: Document, sel: String): Boolean = runCatching { doc.select(sel).isNotEmpty() }.getOrDefault(false)
        for (i in steps.indices) {
            val step = steps[i]
            val healed = ArrayList<HealedPick>()
            when (step.id) {
                StepId.POPULAR, StepId.LATEST -> {
                    if (step.status == StepStatus.Skipped) continue
                    val noTitles = step.items.isNotEmpty() && step.items.all { it.title.isBlank() }
                    val noCovers = step.items.isNotEmpty() && step.items.all { it.imageUrl.isNullOrBlank() }
                    if (step.count > 0 && !noTitles && !noCovers) continue
                    val doc = page(step) ?: continue
                    var itemSel = selectors["item"].orEmpty()
                    if (itemSel.isBlank() || !works(doc, itemSel)) {
                        val fp = fps["item"]
                        val found = fp?.let { PickHealer.heal(doc, "item", itemSel, it, null) }
                        if (found != null) {
                            healed.add(found)
                            itemSel = found.newSelector
                        }
                    }
                    val cards = if (itemSel.isBlank()) emptyList() else runCatching { doc.select(itemSel).toList() }.getOrDefault(emptyList())
                    if (cards.isNotEmpty()) {
                        for (label in PickHealer.CARD_LABELS) {
                            val sel = selectors[label].orEmpty()
                            val fp = fps[label] ?: continue
                            // A blank selector: the code's own fallback (first link / img) is used, nothing to heal
                            if (sel.isBlank()) continue
                            val inSomeCard = cards.any { card -> runCatching { card.selectFirst(sel) != null }.getOrDefault(false) }
                            if (inSomeCard) continue
                            PickHealer.heal(doc, label, sel, fp, itemSel)?.let { healed.add(it) }
                        }
                    }
                }
                StepId.TITLE -> {
                    if (step.status == StepStatus.Skipped || missingDetails.isEmpty()) continue
                    val labels = missingDetails.mapNotNull { DETAIL_LABELS[it] }.filter { it in fps && selectors[it].orEmpty().isNotBlank() }
                    if (labels.isEmpty()) continue
                    val doc = page(step) ?: continue
                    for (label in labels) {
                        val sel = selectors[label].orEmpty()
                        if (works(doc, sel)) continue
                        PickHealer.heal(doc, label, sel, fps.getValue(label), null)?.let { healed.add(it) }
                    }
                }
                else -> continue
            }
            if (healed.isEmpty()) continue
            val moved = if (healed.size == 1) "1 pick moved" else "${healed.size} picks moved"
            steps[i] = step.copy(status = StepStatus.Amber, evidence = "$moved · ${step.evidence}", healed = healed)
            AppLog.add(
                'I',
                "lab",
                "Self-healing of $key, ${step.name}: " +
                    healed.joinToString(" · ") { "${it.label} '${it.oldSelector}' → '${it.newSelector}' (${it.score}, ${it.matches}/${it.total})" },
            )
        }
    }

    /** The page at [url] as Jsoup reads it (≤ 3 MB, 2xx only), else null; never throws but for cancellation */
    private fun fetchPage(url: String, headers: Map<String, String>): Document? {
        val builder = Headers.Builder()
        headers.forEach { (k, v) -> runCatching { builder.set(k, v) } }
        if (builder["User-Agent"] == null) builder.set("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider())
        return try {
            val request = Request.Builder().url(url).headers(builder.build()).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.add('D', "lab", "Self-healing: ${response.code} for ${shortUrl(url)}")
                    return null
                }
                val bytes = response.body.byteStream().use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (out.size() < 3 * 1024 * 1024) {
                        val n = stream.read(chunk)
                        if (n <= 0) break
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray()
                }
                // The page's own charset (meta / BOM), UTF-8 when it names none
                Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, response.request.url.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.add('D', "lab", "Self-healing: couldn't read ${shortUrl(url)}: ${e.message}")
            null
        }
    }

    // ---------- image and video checks ----------

    private val client by lazy {
        Injekt.get<NetworkHelper>().client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class Probe(val status: Int?, val ms: Long, val bytes: ByteArray, val contentType: String?, val error: String?) {
        val ok: Boolean get() = status != null && status in 200..299
    }

    /** `HEAD`, then on 405 `GET` with `Range: bytes=0-0`; 15 s; never throws */
    fun head(url: String, headers: Map<String, String>): Probe = probe(url, headers, head = true)

    private fun probe(url: String, headers: Map<String, String>, head: Boolean, rangeEnd: Int = 0): Probe {
        val started = System.currentTimeMillis()
        val builder = Headers.Builder()
        headers.forEach { (k, v) -> runCatching { builder.set(k, v) } }
        if (builder["User-Agent"] == null) builder.set("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider())
        return try {
            val base = Request.Builder().url(url).headers(builder.build())
            val request = if (head) base.head().build() else base.get().header("Range", "bytes=0-$rangeEnd").build()
            client.newCall(request).execute().use { response ->
                if (head && response.code == 405) {
                    return probe(url, headers, head = false, rangeEnd = rangeEnd)
                }
                val body = if (head) ByteArray(0) else runCatching {
                    response.body.byteStream().use { stream ->
                        val buffer = ByteArray(rangeEnd + 1)
                        var read = 0
                        while (read < buffer.size) {
                            val n = stream.read(buffer, read, buffer.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        buffer.copyOf(read)
                    }
                }.getOrDefault(ByteArray(0))
                Probe(response.code, System.currentTimeMillis() - started, body, response.header("Content-Type"), null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Probe(null, System.currentTimeMillis() - started, ByteArray(0), null, e.message ?: e.toString())
        }
    }

    /**
     * The first page image answered 2xx with the source's headers → Green; 403/404 → Amber. On 403/404 a second
     * HEAD goes out without a Referer, so the evidence says whether the Referer is what the server refuses (the
     * source's headers always carry one here: the run adds the base URL when the source sets none).
     */
    private fun withImageCheck(step: TestStep, headers: Map<String, String>): TestStep {
        val image = step.items.firstOrNull { it.imageUrl?.startsWith("http") == true }?.imageUrl
            ?: step.items.firstOrNull { it.url.startsWith("http") }?.url
            ?: return step.copy(evidence = step.evidence + " · no http image to check")
        val probe = head(image, headers)
        val request = TestRequest("HEAD", image, probe.status, probe.ms, error = probe.error)
        val status = probe.status
        return when {
            probe.ok -> step.copy(evidence = "${step.evidence} · first image $status (${probe.ms} ms)", requests = step.requests + request)
            status == 403 || status == 404 -> {
                val bare = head(image, headers.filterKeys { !it.equals("Referer", ignoreCase = true) })
                val bareRequest = TestRequest("HEAD", image, bare.status, bare.ms, error = bare.error)
                val verdict = when {
                    bare.ok -> "answered $status with the source's Referer but ${bare.status} without one: the server refuses that Referer"
                    else -> "answered $status with the source's headers and ${bare.status ?: bare.error ?: "no reply"} without a Referer"
                }
                step.copy(
                    status = StepStatus.Amber,
                    evidence = "${step.evidence} · first image $verdict",
                    requests = step.requests + request + bareRequest,
                )
            }
            else -> step.copy(
                status = StepStatus.Amber,
                evidence = "${step.evidence} · first image: ${status ?: probe.error ?: "no reply"}",
                requests = step.requests + request,
            )
        }
    }

    /**
     * One check per hoster (its first video), KMK (r116): through [VideoCheck] with the same headers on every request
     * (playlist → quality → key → first piece; DASH; files by their first bytes). Green only when every server's check
     * is ok; a playlist that answers but whose quality / key / piece fails is Amber with the reason; Red when no
     * server answers at all.
     */
    private suspend fun videoStep(hosters: TestStep, sourceHeaders: Map<String, String>): TestStep {
        val videos = hosters.items.filter { it.kind == "video" && it.url.startsWith("http") }
        val byServer = LinkedHashMap<String, TestItem>()
        videos.forEach { v ->
            val server = v.raw?.str("server")?.takeIf { it.isNotBlank() } ?: v.title
            if (server !in byServer) byServer[server] = v
        }
        if (byServer.isEmpty()) return TestStep(StepId.VIDEO, "Video", StepStatus.Red, 0, 0, "no video with an http address to play")
        val started = System.currentTimeMillis()
        val requests = ArrayList<TestRequest>()
        val notes = ArrayList<String>()
        var played = 0
        var partly = 0
        byServer.forEach { (server, video) ->
            val own = (video.raw?.get("headers") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            val headers = sourceHeaders + own
            val trace = VideoCheck.trace(video.url, headers)
            trace.hops.forEach { hop ->
                requests.add(TestRequest("GET", hop.url, hop.status, hop.ms, viaBrowser = false, error = hop.error, bytes = hop.bytes.toLong()))
            }
            val check = trace.result
            val quality = video.title.takeIf { it != "Video" }?.let { " $it" }.orEmpty()
            when {
                check.ok -> {
                    played++
                    notes.add("$server played$quality (${check.detail})")
                }
                check.stage == "quality" || check.stage == "key" || check.stage == "piece" -> {
                    partly++
                    notes.add("$server: ${check.detail}")
                }
                else -> notes.add("$server: ${check.detail}")
            }
        }
        val status = when {
            played == byServer.size -> StepStatus.Green
            played == 0 && partly == 0 -> StepStatus.Red
            else -> StepStatus.Amber
        }
        return TestStep(
            id = StepId.VIDEO,
            name = "Video",
            status = status,
            count = played,
            ms = System.currentTimeMillis() - started,
            evidence = (if (status == StepStatus.Red) notes else notes.sortedBy { it.contains(" played") }).joinToString(" · "),
            items = byServer.values.toList(),
            requests = requests,
            partIds = PartIndex.partsOfStep(StepId.VIDEO, anime = true),
        )
    }

    // ---------- against the previous run ----------

    data class StepDiff(val id: String, val name: String, val was: TestStep?, val now: TestStep) {
        val changed: Boolean get() = was == null || was.status != now.status || was.count != now.count
    }

    /** Per step of [now]: what the previous run had (matched by step id) */
    fun compare(previous: TestRun?, now: TestRun): List<StepDiff> = now.steps.map { step ->
        StepDiff(step.id, step.name, previous?.steps?.firstOrNull { it.id == step.id }, step)
    }
}

/**
 * KMK (r102): one line of the Test Run console: [ms] since the run started (-1: an older run's line without a time),
 * [level] E / W / I / D, or N for a request, the [step] id it belongs to ("" before the first step), its [text] and,
 * for a request, its headers and a failed reply's first 2 KB ([detail]).
 */
@Serializable
internal data class ConsoleRow(
    val ms: Long,
    val level: String,
    val step: String,
    val text: String,
    val detail: String? = null,
)

/** KMK (r102): a source's newest Test Run console in this session; [token] tells runs apart, [runId] once saved */
internal data class LiveConsole(
    val key: String,
    val token: Long,
    val running: Boolean,
    val runId: String? = null,
    val rows: List<ConsoleRow> = emptyList(),
)
