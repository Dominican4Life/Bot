package eu.kanade.tachiyomi.jssource.lab

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkHooks
import eu.kanade.tachiyomi.network.NoNetLog
import eu.kanade.tachiyomi.network.SourceTag
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * KMK (r98, Source Lab): "Record and convert", the second level of Import from extension. Runs the installed
 * extension's own source object (manga `HttpSource` / anime `AnimeHttpSource`) and writes down what it does:
 *
 * - its settings (the PreferenceScreen its `setupPreferenceScreen` builds, read with a data store that never writes),
 * - its filters (`getFilterList()`, every Filter class with names, options and defaults),
 * - its behaviour: popular 1 and 2, latest 1, a search, the first title's details and chapters/episodes, and the
 *   first chapter's pages or the first episode's videos, each through the source's own methods with a 20 s limit,
 *   while the source's HTTP requests are captured (method, URL, request headers with cookie / authorization
 *   values cut, status, content type, up to 256 KB of a text body) next to what the source returned (see [Capture]
 *   for which requests count as the source's).
 *
 * The extension's own methods run for real, so they may save what they always save (a token, a domain they
 * followed) to its `source_<id>` preferences; only the settings screen is read with a store that writes nothing.
 *
 * [RecordedConverter] turns the recording into a JS source. Nothing here touches the library database; the capture
 * is a wrapper around the Diagnostics [NetworkHooks.Listener] that is put back when the recording ends.
 */
internal object ExtensionRecorder {

    const val STEP_TIMEOUT_MS = 20_000L
    const val BODY_EXCERPT_BYTES = 64 * 1024

    /**
     * The converter's copy of a text body: pages longer than the excerpt still need their selectors found. r98 fix:
     * 256 KB per request, at most 4 MB per step and 16 MB per recording (a body past a budget is kept as nothing).
     */
    private const val BODY_PARSE_BYTES = 256 * 1024L
    private const val STEP_BODY_BYTES = 4L * 1024 * 1024
    private const val RECORDING_BODY_BYTES = 16L * 1024 * 1024
    private const val REQUEST_BODY_BYTES = 8 * 1024L
    private const val MAX_REQUESTS_PER_STEP = 40

    /** After a step's time limit: how long the next step waits for the cancelled one to stop */
    private const val CANCEL_GRACE_MS = 2_000L

    // ---------- what a recording holds ----------

    data class RecordedRequest(
        val method: String,
        val url: String,
        val requestHeaders: List<Pair<String, String>>,   // Cookie / Authorization values replaced by "(present)"
        val requestBody: String?,
        val status: Int?,
        val contentType: String?,
        val server: String?,
        val body: String?,                                // up to 256 KB of a text reply (null for images, video, binary, no type)
        val truncated: Boolean,
        val error: String?,
        val ms: Long,
        val image: Boolean,
        val botCheck: String?,                            // "Cloudflare", "DDoS-Guard", … when this reply was a check
        val infrastructure: Boolean,                      // the bot check's own machinery (check.js, /.well-known/…)
    ) {
        val excerpt: String? get() = body?.take(BODY_EXCERPT_BYTES)
        val isJson: Boolean get() = body != null && (contentType?.contains("json", true) == true || body.trimStart().let { it.startsWith("{") || it.startsWith("[") })
        val isHtml: Boolean get() = body != null && !isJson && (contentType?.contains("html", true) == true || body.trimStart().startsWith("<"))
    }

    /** One run of the source's methods, with what came back as plain fields (see [RecordedConverter] for the keys) */
    data class RecordedStep(
        val id: String,                       // popular, popular2, latest, search, details, chapters, pages, videos
        val name: String,
        val status: StepStatus,
        val input: String?,                   // the url handed to the method (details, chapters, pages, videos)
        val query: String?,
        val page: Int?,
        val items: List<Map<String, String>>,
        val requests: List<RecordedRequest>,
        val error: String?,
        val ms: Long,
    ) {
        /** The requests that carry data: no images, no bot-check machinery, no failures */
        val dataRequests: List<RecordedRequest> get() = requests.filter { !it.image && !it.infrastructure && it.error == null }
    }

    data class RecordedSetting(
        val key: String,
        val title: String,
        val summary: String?,
        val type: String,                     // category, info, switch, list, multiSelect, text
        val options: List<Pair<String, String>>,   // label to value
        val default: JsonElement?,
        val changed: Boolean,                 // the owner's value differs from the default (the value itself is not kept in code)
        val current: String?,                 // list / text value now in effect, only to tell a domain setting
    )

    data class RecordedFilter(
        val type: String,                     // header, separator, select, text, checkbox, tristate, sort, group, multiSelect, autocomplete
        val name: String,
        val key: String?,
        val options: List<Pair<String, String>> = emptyList(),
        val defaultIndex: Int = 0,
        val defaultBool: Boolean = false,
        val defaultText: String = "",
        val defaultState: Int = 0,
        val ascending: Boolean = false,
        val defaultSelected: List<String> = emptyList(),
        val children: List<RecordedFilter> = emptyList(),
        val valuesFrom: String? = null,       // "field vals" when option values came from the filter object's own field
    )

    data class Recording(
        val row: ApkImport.ExtensionSourceRow,
        val anime: Boolean,
        val baseUrl: String,
        val headers: List<Pair<String, String>>,  // the source's own `headers` (no cookies), User-Agent only when not the app's
        val settings: List<RecordedSetting>,
        val settingsNote: String?,
        val filters: List<RecordedFilter>,
        val filtersNote: String?,
        val steps: List<RecordedStep>,
        val supportsLatest: Boolean,
        val hosters: Boolean,
        val botCheck: String?,                // the first bot check seen, by name
        val botCheckUrl: String?,
        val cookieNames: List<String>,        // names only, for the site's address
        val startedAt: Long,
    ) {
        fun step(id: String): RecordedStep? = steps.firstOrNull { it.id == id }
    }

    /** One row of the progress list */
    data class StepProgress(
        val id: String,
        val name: String,
        val status: StepStatus,
        val requests: Int = 0,
        val ms: Long = 0,
        val note: String? = null,
    )

    // ---------- the recording ----------

    private val lock = Mutex()

    /** Step bodies run here, so a source that blocks past the time limit is left behind instead of holding the step */
    private val stepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The loaded source object of [row], straight from the extension (never a JS source that took its id) */
    suspend fun findSource(row: ApkImport.ExtensionSourceRow): Any? {
        return if (row.anime) {
            val manager = Injekt.get<AnimeExtensionManager>()
            withTimeoutOrNull(15_000) { manager.isInitialized.first { it } }
            manager.installedExtensionsFlow.value.firstOrNull { it.pkgName == row.pkgName }
                ?.sources?.firstOrNull { it.id == row.sourceId }
        } else {
            val manager = Injekt.get<ExtensionManager>()
            withTimeoutOrNull(15_000) { manager.isInitialized.first { it } }
            manager.installedExtensionsFlow.value.firstOrNull { it.pkgName == row.pkgName }
                ?.sources?.firstOrNull { it.id == row.sourceId }
        }
    }

    /**
     * Records [row]'s source. [onProgress] gets the whole step list after every change (on the caller's thread of
     * the moment; post it to state). One recording at a time; the network wrapper is always removed in `finally`.
     */
    suspend fun record(
        context: Context,
        row: ApkImport.ExtensionSourceRow,
        onProgress: (List<StepProgress>) -> Unit,
    ): Recording = lock.withLock {
        val source = findSource(row)
            ?: throw IllegalStateException("${row.sourceName} isn't loaded any more (was the extension updated or removed?)")
        val anime = row.anime
        val startedAt = System.currentTimeMillis()
        val progress = LinkedHashMap<String, StepProgress>()
        fun show(p: StepProgress) {
            progress[p.id] = p
            runCatching { onProgress(progress.values.toList()) }
        }
        val plan = buildList {
            add("settings" to "Settings")
            add("filters" to "Filters")
            add("popular" to "Popular · page 1")
            add("popular2" to "Popular · page 2")
            add("latest" to "Latest · page 1")
            add("search" to "Search")
            add("details" to "Title details")
            add("chapters" to if (anime) "Episodes" else "Chapters")
            add((if (anime) "videos" else "pages") to if (anime) "Videos of the first episode" else "Pages of the first chapter")
        }
        plan.forEach { (id, name) -> progress[id] = StepProgress(id, name, StepStatus.Skipped, note = "waiting") }
        runCatching { onProgress(progress.values.toList()) }
        AppLog.add('I', "lab", "Recording ${row.sourceName} (${row.pkgName}, id ${row.sourceId}) started")

        // Settings and filters first: no network
        show(StepProgress("settings", "Settings", StepStatus.Running))
        val (settings, settingsNote) = readSettings(context, source, row.sourceId)
        val settingsStatus = if (settingsNote == null || settings.isNotEmpty()) StepStatus.Green else StepStatus.Amber
        val settingsCount = settings.count { it.type != "category" && it.type != "info" }
        show(StepProgress("settings", "Settings", settingsStatus, note = settingsNote ?: "$settingsCount settings"))
        show(StepProgress("filters", "Filters", StepStatus.Running))
        val (filters, filtersNote) = readFiltersTimed(source)
        val filtersStatus = if (filtersNote == null) StepStatus.Green else StepStatus.Amber
        show(StepProgress("filters", "Filters", filtersStatus, note = filtersNote ?: "${countFilters(filters)} filters"))

        val capture = Capture(row.sourceId, row.baseUrl)
        val installed = capture.install()
        val steps = ArrayList<RecordedStep>()
        var stepNumber = 0
        try {
            if (!installed) AppLog.add('W', "lab", "Recording ${row.sourceName}: the network capture isn't available, only results are recorded")
            suspend fun step(
                id: String,
                name: String,
                input: String? = null,
                query: String? = null,
                page: Int? = null,
                block: suspend () -> List<Map<String, String>>,
            ): RecordedStep {
                show(StepProgress(id, name, StepStatus.Running))
                val started = System.currentTimeMillis()
                // r98 fix: every capture carries its step's number; a request is kept for the step it started in
                // (on the step's own thread: always that step), and dropped once that step has ended
                val number = ++stepNumber
                capture.begin(number)
                var items: List<Map<String, String>> = emptyList()
                var error: String? = null
                val deferred = stepScope.async(capture.stepContext(number)) { block() }
                try {
                    items = withTimeout(STEP_TIMEOUT_MS) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    error = "no answer in ${STEP_TIMEOUT_MS / 1000} s"
                    // Give the step a moment to stop before the next one starts, so its requests stay its own
                    deferred.cancel()
                    if (withTimeoutOrNull(CANCEL_GRACE_MS) { deferred.join() } == null) {
                        AppLog.add('W', "lab", "Recording ${row.sourceName}: $id didn't stop ${CANCEL_GRACE_MS / 1000} s after its time limit; its later requests are dropped")
                    }
                } catch (e: CancellationException) {
                    deferred.cancel()
                    capture.end(number)
                    throw e
                } catch (e: Throwable) {
                    error = ApkImport.describe(e)
                } finally {
                    if (!deferred.isCompleted) deferred.cancel()
                }
                val requests = capture.end(number)
                val ms = System.currentTimeMillis() - started
                val status = when {
                    error != null -> StepStatus.Red
                    items.isEmpty() -> StepStatus.Amber
                    else -> StepStatus.Green
                }
                val recorded = RecordedStep(id, name, status, input, query, page, items, requests, error, ms)
                steps.add(recorded)
                val dataCount = recorded.dataRequests.size
                val checkSeen = requests.firstNotNullOfOrNull { it.botCheck }?.let { " · $it check seen" }.orEmpty()
                val summary = "${items.size} ${itemWord(id, items.size)} · $dataCount request${if (dataCount == 1) "" else "s"}$checkSeen"
                show(StepProgress(id, name, status, dataCount, ms, note = error ?: summary))
                AppLog.add('I', "lab", "Recorded ${row.sourceName} $id: ${items.size} items, ${requests.size} requests in $ms ms${error?.let { " ($it)" } ?: ""}")
                return recorded
            }

            fun skip(id: String, name: String, why: String) {
                show(StepProgress(id, name, StepStatus.Skipped, note = why))
            }

            if (anime) {
                val src = source as? AnimeCatalogueSource ?: throw IllegalStateException("${row.sourceName} is not a catalogue source")
                var first: SAnime? = null
                val popular = step("popular", "Popular · page 1", page = 1) {
                    src.getPopularAnime(1).animes.also { first = it.firstOrNull() }.map(::animeEntryOf)
                }
                if (popular.items.isNotEmpty()) {
                    step("popular2", "Popular · page 2", page = 2) { src.getPopularAnime(2).animes.map(::animeEntryOf) }
                } else {
                    skip("popular2", "Popular · page 2", "page 1 gave nothing")
                }
                if (src.supportsLatest) {
                    step("latest", "Latest · page 1", page = 1) { src.getLatestUpdates(1).animes.map(::animeEntryOf) }
                } else {
                    skip("latest", "Latest · page 1", "the source has no Latest")
                }
                val query = searchWord(first?.title)
                step("search", "Search \"$query\"", query = query, page = 1) {
                    src.getSearchAnime(1, query, src.getFilterList()).animes.map(::animeEntryOf)
                }
                val entry = first
                if (entry == null) {
                    skip("details", "Title details", "no title to open")
                    skip("chapters", "Episodes", "no title to open")
                    skip("videos", "Videos of the first episode", "no title to open")
                } else {
                    step("details", "Details of ${entry.title}", input = entry.url) {
                        listOf(animeDetailsOf(src.getAnimeDetails(entry.copyAnime())))
                    }
                    var episode: SEpisode? = null
                    val episodes = step("chapters", "Episodes of ${entry.title}", input = entry.url) {
                        src.getEpisodeList(entry.copyAnime()).also { episode = it.firstOrNull() }.map(::episodeOf)
                    }
                    val ep = episode
                    if (ep == null || episodes.items.isEmpty()) {
                        skip("videos", "Videos of the first episode", "no episode to open")
                    } else {
                        step("videos", "Videos of ${ep.name.ifBlank { "episode 1" }}", input = ep.url) {
                            videosOf(src, ep).map(::videoOf)
                        }
                    }
                }
            } else {
                val src = source as? CatalogueSource ?: throw IllegalStateException("${row.sourceName} is not a catalogue source")
                var first: SManga? = null
                val popular = step("popular", "Popular · page 1", page = 1) {
                    src.getPopularManga(1).mangas.also { first = it.firstOrNull() }.map(::mangaEntryOf)
                }
                if (popular.items.isNotEmpty()) {
                    step("popular2", "Popular · page 2", page = 2) { src.getPopularManga(2).mangas.map(::mangaEntryOf) }
                } else {
                    skip("popular2", "Popular · page 2", "page 1 gave nothing")
                }
                if (src.supportsLatest) {
                    step("latest", "Latest · page 1", page = 1) { src.getLatestUpdates(1).mangas.map(::mangaEntryOf) }
                } else {
                    skip("latest", "Latest · page 1", "the source has no Latest")
                }
                val query = searchWord(first?.title)
                step("search", "Search \"$query\"", query = query, page = 1) {
                    src.getSearchManga(1, query, src.getFilterList()).mangas.map(::mangaEntryOf)
                }
                val entry = first
                if (entry == null) {
                    skip("details", "Title details", "no title to open")
                    skip("chapters", "Chapters", "no title to open")
                    skip("pages", "Pages of the first chapter", "no title to open")
                } else {
                    step("details", "Details of ${entry.title}", input = entry.url) {
                        listOf(mangaDetailsOf(src.getMangaUpdate(entry.copyManga(), emptyList(), fetchDetails = true, fetchChapters = false).manga))
                    }
                    var chapter: SChapter? = null
                    val chapters = step("chapters", "Chapters of ${entry.title}", input = entry.url) {
                        src.getMangaUpdate(entry.copyManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
                            .also { chapter = it.firstOrNull() }.map(::chapterOf)
                    }
                    val ch = chapter
                    if (ch == null || chapters.items.isEmpty()) {
                        skip("pages", "Pages of the first chapter", "no chapter to open")
                    } else {
                        step("pages", "Pages of ${ch.name.ifBlank { "chapter 1" }}", input = ch.url) {
                            val pages = src.getPageList(ch)
                            // A source that finds each image on a page of its own: the first one shows how
                            val firstPage = pages.firstOrNull()
                            if (firstPage != null && firstPage.imageUrl.isNullOrBlank() && src is HttpSource) {
                                runCatching { firstPage.imageUrl = src.getImageUrl(firstPage) }
                                    .onFailure { if (it is CancellationException) throw it }
                            }
                            pages.map(::pageOf)
                        }
                    }
                }
            }
        } finally {
            capture.uninstall()
        }

        val all = steps.flatMap { it.requests }
        val check = all.firstOrNull { it.botCheck != null }
        val cookieNames = runCatching {
            row.baseUrl.toHttpUrlOrNull()?.let { url -> Injekt.get<NetworkHelper>().cookieJar.loadForRequest(url).map { it.name } }
        }.getOrNull().orEmpty().distinct()
        val cookieCheck = when {
            cookieNames.any { it.startsWith("__ddg") } -> "DDoS-Guard"
            cookieNames.any { it == "cf_clearance" } -> "Cloudflare"
            else -> null
        }
        val recording = Recording(
            row = row,
            anime = anime,
            baseUrl = row.baseUrl.trimEnd('/'),
            headers = headersOf(source),
            settings = settings,
            settingsNote = settingsNote,
            filters = filters,
            filtersNote = filtersNote,
            steps = steps,
            supportsLatest = (source as? CatalogueSource)?.supportsLatest ?: (source as? AnimeCatalogueSource)?.supportsLatest ?: false,
            hosters = anime && (source as? AnimeHttpSource)?.let(::hasHosters) == true,
            botCheck = check?.botCheck ?: cookieCheck,
            botCheckUrl = check?.url ?: if (cookieCheck != null) row.baseUrl else null,
            cookieNames = cookieNames,
            startedAt = startedAt,
        )
        val doneLine = "Recording ${row.sourceName} done: ${settings.size} settings, ${countFilters(filters)} filters, " +
            "${steps.count { it.status == StepStatus.Green }}/${steps.size} steps with results, ${all.size} requests" +
            (recording.botCheck?.let { ", $it check" } ?: "")
        AppLog.add('I', "lab", doneLine)
        recording
    }

    private fun itemWord(id: String, n: Int): String = when (id) {
        "details" -> "title"
        "chapters" -> if (n == 1) "item" else "items"
        "pages" -> if (n == 1) "page" else "pages"
        "videos" -> if (n == 1) "video" else "videos"
        else -> if (n == 1) "title" else "titles"
    }

    /** The first word of 3+ letters of the first popular title, else "a" */
    private fun searchWord(title: String?): String =
        title.orEmpty().split(Regex("""[^\p{L}\p{N}]+""")).firstOrNull { w -> w.length >= 3 && w.any { it.isLetter() } } ?: "a"

    fun countFilters(filters: List<RecordedFilter>): Int =
        filters.sumOf { f -> if (f.type == "group") countFilters(f.children) else if (f.type == "header" || f.type == "separator") 0 else 1 }

    // ---------- results as plain fields ----------

    private fun mangaEntryOf(m: SManga): Map<String, String> = buildMap {
        put("url", m.url)
        put("title", m.title)
        m.thumbnail_url?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
    }

    private fun animeEntryOf(a: SAnime): Map<String, String> = buildMap {
        put("url", a.url)
        put("title", a.title)
        a.thumbnail_url?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
    }

    private fun mangaDetailsOf(m: SManga): Map<String, String> = buildMap {
        put("url", m.url)
        put("title", m.title)
        m.author?.takeIf { it.isNotBlank() }?.let { put("author", it) }
        m.artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
        m.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        m.genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
        m.thumbnail_url?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
        put("status", m.status.toString())
    }

    private fun animeDetailsOf(a: SAnime): Map<String, String> = buildMap {
        put("url", a.url)
        put("title", a.title)
        a.author?.takeIf { it.isNotBlank() }?.let { put("author", it) }
        a.artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
        a.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        a.genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
        a.thumbnail_url?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
        put("status", a.status.toString())
    }

    private fun chapterOf(c: SChapter): Map<String, String> = buildMap {
        put("url", c.url)
        put("name", c.name)
        if (c.chapter_number >= 0f) put("number", c.chapter_number.toString())
        if (c.date_upload > 0L) put("date", c.date_upload.toString())
        c.scanlator?.takeIf { it.isNotBlank() }?.let { put("scanlator", it) }
    }

    private fun episodeOf(e: SEpisode): Map<String, String> = buildMap {
        put("url", e.url)
        put("name", e.name)
        if (e.episode_number >= 0f) put("number", e.episode_number.toString())
        if (e.date_upload > 0L) put("date", e.date_upload.toString())
        e.scanlator?.takeIf { it.isNotBlank() }?.let { put("scanlator", it) }
    }

    private fun pageOf(p: Page): Map<String, String> = buildMap {
        put("index", p.index.toString())
        if (p.url.isNotBlank()) put("url", p.url)
        p.imageUrl?.takeIf { it.isNotBlank() }?.let { put("imageUrl", it) }
    }

    private fun videoOf(v: Video): Map<String, String> = buildMap {
        put("url", v.videoUrl)
        put("quality", v.videoTitle)
        v.headers?.let { headers ->
            for (i in 0 until headers.size) {
                val name = headers.name(i)
                if (name.equals("Cookie", true) || name.equals("Authorization", true)) continue
                put("header:$name", headers.value(i))
            }
        }
    }

    private fun SManga.copyManga(): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
    }

    private fun SAnime.copyAnime(): SAnime = SAnime.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
    }

    /** Videos of [episode] the way the player asks: hosters when the source has them (first hoster), else the list */
    private suspend fun videosOf(source: AnimeSource, episode: SEpisode): List<Video> {
        if (source is AnimeHttpSource && hasHosters(source)) {
            val hosters = source.getHosterList(episode)
            val first = hosters.firstOrNull() ?: return emptyList()
            return first.videoList ?: source.getVideoList(first)
        }
        return source.getVideoList(episode)
    }

    /** Same test as the player's EpisodeLoader: the extension's own class declares a hoster method */
    private fun hasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<*>? = source.javaClass
        while (current != null && current != AnimeHttpSource::class.java && current != Any::class.java) {
            if (current.name.startsWith("eu.kanade.tachiyomi.animesource.")) return false
            if (current.declaredMethods.any { it.name in HOSTER_METHODS }) return true
            current = current.superclass
        }
        return false
    }

    private val HOSTER_METHODS = setOf("getHosterList", "hosterListRequest", "hosterListParse")

    /** The source's own `headers`, cookies and authorization left out; User-Agent only when it isn't the app's */
    private fun headersOf(source: Any): List<Pair<String, String>> {
        val headers = runCatching { (source as? HttpSource)?.headers ?: (source as? AnimeHttpSource)?.headers }.getOrNull()
            ?: return emptyList()
        val appAgent = runCatching { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }.getOrNull()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Cookie", true) || name.equals("Authorization", true)) continue
            if (name.equals("User-Agent", true) && value == appAgent) continue
            out.add(name to value)
        }
        return out
    }

    // ---------- settings ----------

    /**
     * Reads everything as "nothing saved", so each widget shows its default after it is attached; writes go nowhere.
     * r98 fix: never answers null where a widget may not expect it (a MultiSelectListPreference without a default
     * threw on a null set, and the whole settings read was lost): an empty set, an empty text.
     */
    private class DefaultsOnlyStore : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun putBoolean(key: String?, value: Boolean) {}
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun putInt(key: String?, value: Int) {}
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun putLong(key: String?, value: Long) {}
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun putFloat(key: String?, value: Float) {}
        override fun getString(key: String?, defValue: String?): String? = defValue ?: ""
        override fun putString(key: String?, value: String?) {}
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues ?: mutableSetOf()
        override fun putStringSet(key: String?, values: MutableSet<String>?) {}
    }

    @SuppressLint("RestrictedApi")
    private suspend fun readSettings(context: Context, source: Any, sourceId: Long): Pair<List<RecordedSetting>, String?> {
        val setup: (PreferenceScreen) -> Unit = when (source) {
            // In a `when` branch `{ … }` is a block, not a lambda: the parentheses make it a lambda
            is ConfigurableSource -> ({ screen: PreferenceScreen -> source.setupPreferenceScreen(screen) })
            is ConfigurableAnimeSource -> ({ screen: PreferenceScreen -> source.setupPreferenceScreen(screen) })
            else -> return emptyList<RecordedSetting>() to "the source has no settings"
        }
        val saved = context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
        // r98 fix: the extension's setupPreferenceScreen runs on Main (preferences need it), where it can't be
        // interrupted: an extension that blocks there holds Main until it returns. Only the WAIT is limited (on the
        // caller's IO thread), so the recording goes on with "settings not read: timed out"; the Main job is
        // cancelled, which only helps if the extension's code ever suspends or returns.
        val build = stepScope.async(Dispatchers.Main) {
            val manager = PreferenceManager(context)
            manager.preferenceDataStore = DefaultsOnlyStore()
            val screen = manager.createPreferenceScreen(context)
            setup(screen)
            screen
        }
        return try {
            val screen = withTimeoutOrNull(STEP_TIMEOUT_MS) { build.await() }
            if (screen == null) {
                build.cancel()
                AppLog.add('W', "lab", "Settings of source $sourceId not read: setupPreferenceScreen gave no answer in ${STEP_TIMEOUT_MS / 1000} s")
                return emptyList<RecordedSetting>() to "settings not read: timed out"
            }
            val out = ArrayList<RecordedSetting>()
            walk(screen, saved, out)
            out to null
        } catch (e: CancellationException) {
            build.cancel()
            throw e
        } catch (e: Throwable) {
            AppLog.add('W', "lab", "Settings of source $sourceId not read: ${ApkImport.describe(e)}")
            emptyList<RecordedSetting>() to "settings not read: ${ApkImport.describe(e)}"
        }
    }

    private fun walk(group: PreferenceGroup, saved: SharedPreferences, out: MutableList<RecordedSetting>) {
        for (i in 0 until group.preferenceCount) {
            val p = group.getPreference(i)
            val title = p.title?.toString().orEmpty()
            val summary = p.summary?.toString()?.takeIf { it.isNotBlank() && "%s" !in it }
            val key = p.key.orEmpty()
            when {
                p is PreferenceGroup -> {
                    if (title.isNotBlank()) out.add(RecordedSetting("", title, summary, "category", emptyList(), null, false, null))
                    walk(p, saved, out)
                }
                key.isBlank() -> if (summary != null || title.isNotBlank()) {
                    out.add(RecordedSetting("", title, summary ?: title, "info", emptyList(), null, false, null))
                }
                p is MultiSelectListPreference -> {
                    val options = options(p.entries, p.entryValues)
                    val default = JsonArray(p.values.orEmpty().map { JsonPrimitive(it) })
                    val now = runCatching { saved.getStringSet(key, null) }.getOrNull()
                    out.add(RecordedSetting(key, title, summary, "multiSelect", options, default, now != null && now != p.values.orEmpty().toSet(), null))
                }
                p is ListPreference -> {
                    val options = options(p.entries, p.entryValues)
                    // DefaultsOnlyStore answers "" for a list without a default: that is no default
                    val value = p.value?.takeIf { it.isNotEmpty() }
                    val default = value?.let { JsonPrimitive(it) }
                    val now = runCatching { saved.all[key]?.toString() }.getOrNull()
                    out.add(RecordedSetting(key, title, summary, "list", options, default, now != null && now != value.orEmpty(), now ?: value))
                }
                p is EditTextPreference -> {
                    val text = p.text?.takeIf { it.isNotEmpty() }
                    val default = text?.let { JsonPrimitive(it) }
                    val now = runCatching { saved.all[key]?.toString() }.getOrNull()
                    out.add(RecordedSetting(key, title, summary, "text", emptyList(), default, now != null && now != text.orEmpty(), now ?: text))
                }
                p is TwoStatePreference -> {
                    val now = runCatching { saved.all[key] as? Boolean }.getOrNull()
                    out.add(RecordedSetting(key, title, summary, "switch", emptyList(), JsonPrimitive(p.isChecked), now != null && now != p.isChecked, null))
                }
                else -> if (summary != null || title.isNotBlank()) {
                    // A plain Preference (a button, a note): shown as text, it has no value a JS source could keep
                    out.add(RecordedSetting("", title, summary ?: title, "info", emptyList(), null, false, null))
                }
            }
        }
    }

    private fun options(entries: Array<CharSequence>?, values: Array<CharSequence>?): List<Pair<String, String>> {
        val labels = entries.orEmpty().map { it.toString() }
        val vals = values?.map { it.toString() } ?: labels
        return labels.zip(vals)
    }

    // ---------- filters ----------

    /**
     * r98 fix: [readFilters] on a thread of its own, waited for at most [STEP_TIMEOUT_MS] on IO (the wait is
     * interruptible, so Stop cancels it). An extension whose getFilterList() blocks is left to finish in the
     * background; the recording goes on with "filters not read: no answer in 20 s".
     */
    private suspend fun readFiltersTimed(source: Any): Pair<List<RecordedFilter>, String?> {
        val task = FutureTask<Pair<List<RecordedFilter>, String?>> { readFilters(source) }
        Thread(task, "lab-recorder-filters").apply { isDaemon = true }.start()
        return try {
            withContext(Dispatchers.IO) { runInterruptible { task.get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS) } }
        } catch (e: TimeoutException) {
            AppLog.add('W', "lab", "Filters not read: getFilterList() gave no answer in ${STEP_TIMEOUT_MS / 1000} s (left running)")
            emptyList<RecordedFilter>() to "filters not read: no answer in ${STEP_TIMEOUT_MS / 1000} s"
        } catch (e: ExecutionException) {
            emptyList<RecordedFilter>() to "filters not read: ${ApkImport.describe(e.cause ?: e)}"
        }
    }

    private fun readFilters(source: Any): Pair<List<RecordedFilter>, String?> {
        return try {
            val keys = HashSet<String>()
            when (source) {
                is CatalogueSource -> source.getFilterList().list.mapNotNull { mangaFilter(it, keys) } to null
                is AnimeCatalogueSource -> source.getFilterList().list.mapNotNull { animeFilter(it, keys) } to null
                else -> emptyList<RecordedFilter>() to "no filters"
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            emptyList<RecordedFilter>() to "filters not read: ${ApkImport.describe(e)}"
        }
    }

    private fun mangaFilter(f: Filter<*>, keys: MutableSet<String>): RecordedFilter? = when (f) {
        is Filter.Header -> RecordedFilter("header", f.name, null)
        is Filter.Separator -> RecordedFilter("separator", f.name, null)
        is Filter.Select<*> -> select(f, f.name, f.values.map { it.toString() }, f.state, keys)
        is Filter.Text -> RecordedFilter("text", f.name, uniqueKey(f.name, keys), defaultText = f.state)
        is Filter.CheckBox -> RecordedFilter("checkbox", f.name, uniqueKey(idOf(f, f.name) ?: f.name, keys), defaultBool = f.state)
        is Filter.TriState -> RecordedFilter("tristate", f.name, uniqueKey(idOf(f, f.name) ?: f.name, keys), defaultState = f.state)
        is Filter.Sort -> RecordedFilter(
            type = "sort",
            name = f.name,
            key = uniqueKey(f.name, keys),
            options = f.values.map { it to it },
            defaultIndex = f.state?.index ?: 0,
            ascending = f.state?.ascending ?: false,
        )
        is Filter.Group<*> -> {
            val children = f.state.filterIsInstance<Filter<*>>()
            if (children.isNotEmpty() && children.all { it is Filter.CheckBox }) {
                RecordedFilter(
                    type = "multiSelect",
                    name = f.name,
                    key = uniqueKey(f.name, keys),
                    options = children.map { it.name to (idOf(it, it.name) ?: it.name) },
                    defaultSelected = children.filter { it.state == true }.map { idOf(it, it.name) ?: it.name },
                )
            } else {
                RecordedFilter("group", f.name, null, children = children.mapNotNull { mangaFilter(it, keys) })
            }
        }
        is Filter.AutoComplete -> RecordedFilter("autocomplete", f.name, uniqueKey(f.name, keys), options = f.values.map { it to it })
        else -> null
    }

    private fun animeFilter(f: AnimeFilter<*>, keys: MutableSet<String>): RecordedFilter? = when (f) {
        is AnimeFilter.Header -> RecordedFilter("header", f.name, null)
        is AnimeFilter.Separator -> RecordedFilter("separator", f.name, null)
        is AnimeFilter.Select<*> -> select(f, f.name, f.values.map { it.toString() }, f.state, keys)
        is AnimeFilter.Text -> RecordedFilter("text", f.name, uniqueKey(f.name, keys), defaultText = f.state)
        is AnimeFilter.CheckBox -> RecordedFilter("checkbox", f.name, uniqueKey(idOf(f, f.name) ?: f.name, keys), defaultBool = f.state)
        is AnimeFilter.TriState -> RecordedFilter("tristate", f.name, uniqueKey(idOf(f, f.name) ?: f.name, keys), defaultState = f.state)
        is AnimeFilter.Sort -> RecordedFilter(
            type = "sort",
            name = f.name,
            key = uniqueKey(f.name, keys),
            options = f.values.map { it to it },
            defaultIndex = f.state?.index ?: 0,
            ascending = f.state?.ascending ?: false,
        )
        is AnimeFilter.Group<*> -> {
            val children = f.state.filterIsInstance<AnimeFilter<*>>()
            if (children.isNotEmpty() && children.all { it is AnimeFilter.CheckBox }) {
                RecordedFilter(
                    type = "multiSelect",
                    name = f.name,
                    key = uniqueKey(f.name, keys),
                    options = children.map { it.name to (idOf(it, it.name) ?: it.name) },
                    defaultSelected = children.filter { it.state == true }.map { idOf(it, it.name) ?: it.name },
                )
            } else {
                RecordedFilter("group", f.name, null, children = children.mapNotNull { animeFilter(it, keys) })
            }
        }
        is AnimeFilter.AutoComplete -> RecordedFilter("autocomplete", f.name, uniqueKey(f.name, keys), options = f.values.map { it to it })
        else -> null
    }

    private fun select(f: Any, name: String, labels: List<String>, state: Int, keys: MutableSet<String>): RecordedFilter {
        val values = extraValues(f, labels)
        return RecordedFilter(
            type = "select",
            name = name,
            key = uniqueKey(name, keys),
            options = labels.mapIndexed { i, label -> label to (values?.getOrNull(i) ?: label) },
            defaultIndex = state.coerceAtLeast(0),
            valuesFrom = if (values != null) "the filter's own list of values" else null,
        )
    }

    private fun uniqueKey(name: String, keys: MutableSet<String>): String {
        val base = name.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_').ifBlank { "filter" }
        var key = base
        var n = 2
        while (!keys.add(key)) key = base + "_" + n++
        return key
    }

    /** Fields declared by the extension's own filter classes (never the library's Filter classes) */
    private fun ownFields(obj: Any): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            if (cls.name.startsWith("eu.kanade.tachiyomi.source.model.") || cls.name.startsWith("eu.kanade.tachiyomi.animesource.model.")) break
            cls.declaredFields.filterTo(out) { !Modifier.isStatic(it.modifiers) }
            cls = cls.superclass
        }
        return out
    }

    /**
     * The values a Select sends, when the extension keeps them next to the labels (the usual `UriPartFilter(name,
     * arrayOf(Pair("Label", "value")))`): an array or list of the same size whose items are pairs (second = value),
     * two-string arrays, or plain strings different from the labels.
     */
    private fun extraValues(filter: Any, labels: List<String>): List<String>? = runCatching {
        for (field in ownFields(filter)) {
            val value = runCatching {
                field.isAccessible = true
                field.get(filter)
            }.getOrNull() ?: continue
            val list: List<Any?> = when (value) {
                is Array<*> -> value.toList()
                is List<*> -> value
                else -> continue
            }
            if (list.size != labels.size || list.isEmpty()) continue
            val strings = list.map { item ->
                when (item) {
                    is Pair<*, *> -> item.second?.toString()
                    is Array<*> -> item.getOrNull(1)?.toString()
                    is String -> item
                    else -> null
                }
            }
            if (strings.any { it == null } || strings == labels) continue
            return@runCatching strings.map { it.orEmpty() }
        }
        null
    }.getOrNull()

    /** A checkbox's id kept by the extension (`class Genre(name: String, val id: String) : Filter.CheckBox(name)`) */
    private fun idOf(filter: Any, name: String): String? = runCatching {
        for (field in ownFields(filter)) {
            if (field.type != String::class.java) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(filter) as? String
            }.getOrNull() ?: continue
            if (value.isNotBlank() && value != name) return@runCatching value
        }
        null
    }.getOrNull()

    // ---------- the network capture ----------

    /**
     * Wraps the installed Diagnostics listener for the time of a recording: every call is passed on to it unchanged
     * (each guarded on its own), and while a step runs the request and the head of its reply are written down.
     *
     * r98 fix: only the recorded source's requests are kept, each for the step it started in:
     * - a request tagged with a [SourceTag] counts only when the tag is this source's id;
     * - an untagged one (APK extensions never tag theirs) counts when it is made on a thread that runs the step's
     *   own code ([stepContext]: a blocking `execute()`), or when its host is the site's, one the extension's own
     *   code already called, or one named in a reply kept so far (an `enqueue`d call runs on OkHttp's threads, where
     *   only the host can tell). Anything else the app does meanwhile is left out;
     * - a request belongs to the step it started in; once that step has ended (done, or timed out and given
     *   [CANCEL_GRACE_MS] to stop) its late requests are dropped, never counted for the next step.
     *
     * The hooks sit in the app's network interceptor, outside the extension's own interceptors: a request is seen
     * before they add to it (signed headers, tokens) and a reply after they changed it (decrypted, rewritten).
     */
    private class Capture(private val sourceId: Long, baseUrl: String) : NetworkHooks.Listener {

        private var delegate: NetworkHooks.Listener? = null
        private val baseHost = baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")

        /** The step whose code runs on this thread, set while a step's block runs ([stepContext]) */
        private val running = ThreadLocal<Int?>()

        private val lock = Any()

        @Volatile
        private var current = 0
        private val sinks = HashMap<Int, MutableList<RecordedRequest>>()
        private val startedIn = java.util.IdentityHashMap<Request, Int>()
        private val pending = java.util.IdentityHashMap<Request, BodyHead>()
        private val stepBytes = HashMap<Int, Long>()
        private var totalBytes = 0L
        private val hosts = HashSet<String>()
        private val kept = ArrayList<String>()

        private class BodyHead(val text: String?, val truncated: Boolean, val contentType: String?, val server: String?)

        /** The coroutine context element a step's block runs with: requests made on its thread are that step's */
        fun stepContext(number: Int) = running.asContextElement(number)

        fun install(): Boolean = synchronized(NetworkHooks) {
            delegate = NetworkHooks.listener
            NetworkHooks.listener = this
            true
        }

        fun uninstall() = synchronized(NetworkHooks) {
            // Put back what was there, unless something else replaced us meanwhile (then leave theirs)
            if (NetworkHooks.listener === this) NetworkHooks.listener = delegate
            synchronized(lock) {
                current = 0
                sinks.clear()
                startedIn.clear()
                pending.clear()
                kept.clear()
            }
        }

        fun begin(number: Int) = synchronized(lock) {
            sinks[number] = ArrayList()
            stepBytes[number] = 0L
            current = number
        }

        fun end(number: Int): List<RecordedRequest> = synchronized(lock) {
            if (current == number) current = 0
            // Requests of this step still on their way are dropped when they finish
            val late = startedIn.entries.filter { it.value == number }.map { it.key }
            late.forEach { startedIn.remove(it); pending.remove(it) }
            sinks.remove(number)?.toList().orEmpty()
        }

        /** The step [request] belongs to, or null when it isn't this recording's */
        private fun stepFor(tag: SourceTag?, request: Request): Int? {
            if (request.tag(NoNetLog::class.java) != null) return null
            val onThread = running.get()
            val number = onThread ?: current
            if (number == 0) return null
            val host = request.url.host.removePrefix("www.")
            return synchronized(lock) {
                if (!sinks.containsKey(number)) return@synchronized null
                val ours = when {
                    tag != null -> tag.id == sourceId
                    onThread != null -> true
                    else -> host == baseHost || host in hosts || kept.any { host in it }
                }
                if (!ours) return@synchronized null
                hosts.add(host)
                number
            }
        }

        override fun onCall(tag: SourceTag?, request: Request, response: Response?, error: Throwable?, ms: Long) {
            delegate?.let { d -> guard { d.onCall(tag, request, response, error, ms) } }
            guard {
                val (list, head) = synchronized(lock) {
                    val number = startedIn.remove(request)
                    val bodyHead = pending.remove(request)
                    val sink = number?.let { sinks[it] }
                    if (sink == null || sink.size >= MAX_REQUESTS_PER_STEP) null to null else sink to bodyHead
                }
                if (list == null) return@guard
                val contentType = head?.contentType ?: response?.header("Content-Type")
                val image = contentType?.startsWith("image/", true) == true
                val body = head?.text
                val server = head?.server ?: response?.header("Server")
                val status = response?.code
                val check = botCheckOf(request.url.toString(), status, server, body)
                val recorded = RecordedRequest(
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = request.headers.map { (name, value) ->
                        name to if (name.equals("Cookie", true) || name.equals("Authorization", true)) "(present)" else value
                    },
                    requestBody = requestBodyOf(request),
                    status = status,
                    contentType = contentType,
                    server = server,
                    body = body,
                    truncated = head?.truncated == true,
                    error = error?.let { "${it.javaClass.simpleName}: ${it.message}" },
                    ms = ms,
                    image = image,
                    botCheck = check,
                    infrastructure = isInfrastructure(request.url.toString()),
                )
                synchronized(lock) { if (list.size < MAX_REQUESTS_PER_STEP) list.add(recorded) }
            }
        }

        override fun bodyCounter(): Boolean = delegate?.let { d -> guard(false) { d.bodyCounter() } } ?: false

        override fun wrapBody(tag: SourceTag?, response: Response): Response =
            delegate?.let { d -> guard(response) { d.wrapBody(tag, response) } } ?: response

        override fun beforeProceed(tag: SourceTag?, request: Request) {
            delegate?.let { d -> guard { d.beforeProceed(tag, request) } }
            guard {
                val number = stepFor(tag, request) ?: return@guard
                synchronized(lock) { startedIn[request] = number }
            }
        }

        override fun afterResponse(tag: SourceTag?, request: Request, response: Response): Response {
            val r = delegate?.let { d -> guard(response) { d.afterResponse(tag, request, response) } } ?: response
            guard {
                val number = synchronized(lock) { startedIn[request] } ?: return@guard
                val type = r.header("Content-Type")
                val server = r.header("Server")
                // No Content-Type is not text: nothing is read ahead for it
                if (!isText(type)) {
                    synchronized(lock) { pending[request] = BodyHead(null, false, type, server) }
                    return@guard
                }
                val allowance = synchronized(lock) {
                    minOf(BODY_PARSE_BYTES, STEP_BODY_BYTES - (stepBytes[number] ?: 0L), RECORDING_BODY_BYTES - totalBytes)
                }
                if (allowance <= 0L) {
                    synchronized(lock) { pending[request] = BodyHead(null, true, type, server) }
                    return@guard
                }
                // peekBody reads ahead into the buffer, at most the allowance: the source still gets every byte
                val peeked = r.peekBody(allowance)
                val bytes = peeked.bytes()
                val charset = peeked.contentType()?.charset() ?: Charsets.UTF_8
                val text = String(bytes, charset)
                synchronized(lock) {
                    stepBytes[number] = (stepBytes[number] ?: 0L) + bytes.size
                    totalBytes += bytes.size
                    kept.add(text)
                    pending[request] = BodyHead(text, bytes.size.toLong() >= allowance, type, server)
                }
            }
            return r
        }

        private fun isText(type: String?): Boolean {
            if (type == null) return false
            val t = type.lowercase()
            return t.startsWith("text/") || "json" in t || "javascript" in t || "xml" in t || "mpegurl" in t ||
                "x-www-form-urlencoded" in t
        }

        private fun requestBodyOf(request: Request): String? = runCatching {
            val body = request.body ?: return@runCatching null
            if (body.isOneShot() || body.isDuplex()) return@runCatching null
            val length = body.contentLength()
            if (length < 0 || length > REQUEST_BODY_BYTES) return@runCatching null
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull()

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                NetworkHooks.reportFailure(e)
            }
        }

        private inline fun <T> guard(fallback: T, block: () -> T): T = try {
            block()
        } catch (e: Throwable) {
            NetworkHooks.reportFailure(e)
            fallback
        }
    }

    /** A reply that is a bot check rather than the page, by name; null when it isn't one */
    fun botCheckOf(url: String, status: Int?, server: String?, body: String?): String? {
        val lowerUrl = url.lowercase()
        if ("ddos-guard" in lowerUrl) return "DDoS-Guard"
        if ("/cdn-cgi/challenge-platform" in lowerUrl) return "Cloudflare"
        if (status !in CHECK_STATUSES) return null
        val s = server.orEmpty().lowercase()
        val b = body.orEmpty().take(20_000)
        return when {
            "ddos-guard" in s || "ddos-guard" in b.lowercase() || "__ddg" in b -> "DDoS-Guard"
            "cloudflare" in s && ("challenge-platform" in b || "cf-chl" in b || "Just a moment" in b) -> "Cloudflare"
            "challenge-platform" in b || "cf_chl_opt" in b -> "Cloudflare"
            "Checking your browser" in b || "captcha" in b.lowercase() -> "a bot check"
            else -> null
        }
    }

    private val CHECK_STATUSES = setOf(403, 429, 503)

    /** The bot check's own requests (scripts, beacons): never data */
    fun isInfrastructure(url: String): Boolean {
        val u = url.lowercase()
        return "ddos-guard.net" in u || "/.well-known/ddos-guard" in u || "/cdn-cgi/" in u || "challenges.cloudflare.com" in u
    }
}
