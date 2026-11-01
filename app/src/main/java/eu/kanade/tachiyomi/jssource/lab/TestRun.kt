package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.jssource.HlsRelay
import eu.kanade.tachiyomi.jssource.JsFilters
import eu.kanade.tachiyomi.jssource.JsMapping
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel.Item
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel.Kind
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel.Result
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.Request
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

    private fun write(run: TestRun) {
        val folder = dir(run.key)
        if (folder.parentFile?.isDirectory != true) return
        folder.mkdirs()
        File(folder, "${run.id}.json").writeText(jsJson.encodeToString(TestRun.serializer(), run))
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }

    // ---------- the pipeline over a runtime ----------

    /**
     * Tabs (page 1 each) → Popular → Latest → search → title → chapters → pages or hosters, each as a `Result` with
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
                Result(
                    title = "",
                    ok = true,
                    millis = 0,
                    text = if (details == null) "getDetails returned nothing" else "${filled.size} of 8 fields: ${filled.joinToString(", ")}",
                    raw = listOfNotNull(details),
                )
            }
        } else {
            skipped(StepId.TITLE, "Title", "this source has no getDetails")
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
        onProgress: (List<TestStep>) -> Unit = {},
    ): TestRun = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val steps = ArrayList<TestStep>()
        var running: TestStep? = null
        fun publish() = onProgress(steps + listOfNotNull(running))
        var effectiveQuery = query
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
                steps[pagesIndex] = withImageCheck(steps[pagesIndex], sourceHeaders)
                running = null
                publish()
            }
        } else {
            val hosters = steps.firstOrNull { it.id == StepId.HOSTERS }
            if (hosters != null && hosters.status != StepStatus.Red && hosters.status != StepStatus.Skipped) {
                running = TestStep(StepId.VIDEO, "Video", StepStatus.Running, 0, 0, "")
                publish()
                steps.add(videoStep(hosters, sourceHeaders))
                running = null
                publish()
            } else if (hosters != null) {
                steps.add(TestStep(StepId.VIDEO, "Video", StepStatus.Skipped, 0, 0, "no hoster to play"))
                publish()
            }
        }
        val run = TestRun(
            id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt)),
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
        runCatching { write(run) }.onFailure { AppLog.add('W', "lab", "Couldn't save the Test Run of $key: ${it.message}") }
        runCatching { History.noteTestRun(key, run.id) }
        AppLog.add(
            'I',
            "lab",
            "Test Run of $key: ${run.passed} of ${run.counted} passed in ${run.totalMs} ms (${run.worst.name.lowercase()})",
        )
        run
    }

    /** One `Result` → one judged step (the thresholds of D§2.5) */
    private fun judge(result: Result, anime: Boolean): TestStep {
        val id = result.step.orEmpty()
        val name = stepName(id, result, anime)
        val partIds = PartIndex.partsOfStep(id, anime)
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
        if (!result.ok) return step(StepStatus.Red, 0, failureWords(result.text.orEmpty(), requests))
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
                val filled = details?.let(::detailFields).orEmpty()
                val missing = DETAIL_FIELDS - filled.toSet()
                when {
                    details == null -> step(StepStatus.Amber, 0, "getDetails returned nothing")
                    filled.size >= 6 -> step(StepStatus.Green, filled.size, "${filled.size} of 8 fields" + if (missing.isEmpty()) "" else " · without ${missing.joinToString(", ")}")
                    else -> step(StepStatus.Amber, filled.size, "only ${filled.size} of 8 fields · missing ${missing.joinToString(", ")}")
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

    private val NET_LINE = Regex("""^\[net\](?:\[[^\]]*\])?\s+(\w+)\s+(\S+)\s+->\s+(\d+|null)(?:\s+in\s+(\d+)ms)?(\s+\(through the browser\))?""")
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
            )
        }
        FETCH_FAILED.find(line)?.let { m ->
            return TestRequest(method = "GET", url = "", status = null, ms = 0, error = m.groupValues[1].take(200))
        }
        return null
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

    /** One probe per hoster (its first video): HLS through the relay, files by their first bytes */
    private fun videoStep(hosters: TestStep, sourceHeaders: Map<String, String>): TestStep {
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
        byServer.forEach { (server, video) ->
            val own = (video.raw?.get("headers") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            val headers = sourceHeaders + own
            val hls = "m3u8" in video.url.lowercase() || (video.raw?.str("type")?.lowercase()?.contains("hls") == true)
            val target = if (hls) {
                val builder = Headers.Builder()
                headers.forEach { (k, v) -> runCatching { builder.set(k, v) } }
                runCatching { HlsRelay.wrap(video.url, builder.build()) }.getOrDefault(video.url)
            } else {
                video.url
            }
            val probe = probe(target, if (hls && target != video.url) emptyMap() else headers, head = false, rangeEnd = 2047)
            requests.add(TestRequest("GET", video.url, probe.status, probe.ms, viaBrowser = false, error = probe.error))
            val kind = mediaKind(probe)
            val redirects = 0
            when {
                probe.ok && kind != null -> {
                    played++
                    notes.add("$server played${video.title.takeIf { it != "Video" }?.let { " $it" }.orEmpty()} ($kind)")
                }
                probe.ok -> notes.add("$server: $kind${probe.status} but not a playlist or a video file (${probe.contentType ?: "no content type"})")
                else -> notes.add(
                    "$server: ${probe.status ?: probe.error ?: "no reply"}" +
                        (if (redirects > 0) " after $redirects redirects" else "") +
                        if (headers.keys.none { it.equals("Referer", true) }) " · no Referer on the request" else "",
                )
            }
        }
        val status = when {
            played == byServer.size -> StepStatus.Green
            played == 0 -> StepStatus.Red
            else -> StepStatus.Amber
        }
        return TestStep(
            id = StepId.VIDEO,
            name = "Video",
            status = status,
            count = played,
            ms = System.currentTimeMillis() - started,
            evidence = (if (status == StepStatus.Red) notes else notes.sortedBy { it.contains("played") }).joinToString(" · "),
            items = byServer.values.toList(),
            requests = requests,
            partIds = PartIndex.partsOfStep(StepId.VIDEO, anime = true),
        )
    }

    /** "hls", "mp4", "mkv", "mpeg" or null when the first bytes aren't a playlist or a video container */
    private fun mediaKind(probe: Probe): String? {
        val b = probe.bytes
        val text = String(b, 0, b.size.coerceAtMost(64), Charsets.ISO_8859_1)
        return when {
            text.trimStart().startsWith("#EXTM3U") -> "hls"
            probe.contentType?.contains("mpegurl", ignoreCase = true) == true -> "hls"
            b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
            b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "moov" -> "mp4"
            b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "mkv"
            b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0xBA.toByte() -> "mpeg"
            probe.contentType?.startsWith("video/", ignoreCase = true) == true -> "video"
            else -> null
        }
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
