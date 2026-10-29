package eu.kanade.tachiyomi.ui.codeeditor

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsFilters
import eu.kanade.tachiyomi.jssource.JsPreferences
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The Code editor: write or import a JS / JSON source, test it (popular, latest, search, catalogs,
 * filters, settings, info, and drill into a title's chapters and pages / videos), and save it as a source.
 */
class SourceEditorScreenModel(
    initialKey: String?,
    private val manager: JsSourceManager = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateScreenModel<SourceEditorScreenModel.State>(State()) {

    @Immutable
    data class State(
        val key: String = "",
        val saved: Boolean = false,
        val json: Boolean = false,
        val anime: Boolean = false,
        val code: String = SourceTemplates.templateFor(json = false, anime = false),
        val name: String = "",
        val iconUrl: String = "",
        val lang: String = "en",
        val baseUrl: String = "",
        val dirty: Boolean = false,
        val running: String? = null,
        val results: List<Result> = emptyList(),
        val console: List<String> = emptyList(),
        val message: String? = null,
        /** The source's settings, while the Source settings dialog is open */
        val settings: List<JsPreferences.Definition>? = null,
        /** Set right after a save, so the screen can offer to open the source in Browse */
        val justSaved: JsSourceInfo? = null,
        /** Why the last save failed, shown in a dialog */
        val saveError: String? = null,
    )

    /** One test run, newest first in the panel */
    @Immutable
    data class Result(
        val title: String,
        val ok: Boolean,
        val millis: Long,
        val items: List<Item> = emptyList(),
        val text: String? = null,
    )

    /** An entry, chapter, page or video in a result, with what to run when it's opened */
    @Immutable
    data class Item(
        val title: String,
        val subtitle: String? = null,
        val imageUrl: String? = null,
        val kind: Kind,
        val url: String,
    )

    enum class Kind { Entry, Chapter, Page, Video, Other }

    private var runtime: JsRuntime? = null
    private var runtimeCode: String? = null
    private val runtimeMutex = Mutex()
    private var job: Job? = null

    init {
        val info = initialKey?.let { manager.get(it) }
        val code = initialKey?.let { manager.code(it) }
        mutableState.update {
            if (info != null && code != null) {
                State(
                    key = info.key,
                    saved = true,
                    json = info.json,
                    anime = info.anime,
                    code = code,
                    name = info.name,
                    iconUrl = info.iconUrl.orEmpty(),
                    lang = info.lang,
                    baseUrl = info.baseUrl,
                )
            } else {
                State(key = manager.newKey())
            }
        }
    }

    val savedSources get() = manager.savedFlow

    // Editing

    fun setCode(code: String) {
        mutableState.update { it.copy(code = code, dirty = true) }
        detectDetails()
    }

    private var detectJob: kotlinx.coroutines.Job? = null

    /**
     * Reads the name, icon, language, address and kind (manga or anime) from the code a moment after it stops
     * changing (pasted, typed or imported), so the Details panel follows the code without a button
     */
    fun detectDetails(delayMs: Long = 1200) {
        detectJob?.cancel()
        detectJob = screenModelScope.launchIO {
            kotlinx.coroutines.delay(delayMs)
            if (SourceTemplates.isTemplate(state.value.code)) return@launchIO
            val meta = try {
                runtime().metadata()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launchIO
            }
            fun value(key: String) = meta.str(key)?.takeIf { it.isNotBlank() }
            mutableState.update {
                it.copy(
                    name = value("name") ?: it.name,
                    iconUrl = value("iconUrl") ?: it.iconUrl,
                    lang = value("lang") ?: it.lang,
                    baseUrl = value("baseUrl") ?: it.baseUrl,
                    anime = when {
                        meta["hasVideos"]?.jsonPrimitive?.contentOrNull == "true" &&
                            meta["hasPages"]?.jsonPrimitive?.contentOrNull != "true" -> true
                        meta["hasPages"]?.jsonPrimitive?.contentOrNull == "true" &&
                            meta["hasVideos"]?.jsonPrimitive?.contentOrNull != "true" -> false
                        else -> it.anime
                    },
                )
            }
        }
    }

    /** Switches between JS and JSON; an untouched template becomes the other kind's template */
    fun setJson(json: Boolean) = mutableState.update {
        if (it.json == json) return@update it
        val code = if (SourceTemplates.isTemplate(it.code)) SourceTemplates.templateFor(json, it.anime) else it.code
        it.copy(json = json, code = code, dirty = it.dirty || code != it.code)
    }

    fun setAnime(anime: Boolean) = mutableState.update {
        if (it.anime == anime) return@update it
        val code = if (SourceTemplates.isTemplate(it.code)) SourceTemplates.templateFor(it.json, anime) else it.code
        it.copy(anime = anime, code = code)
    }

    fun setName(value: String) = mutableState.update { it.copy(name = value, dirty = true) }
    fun setIconUrl(value: String) = mutableState.update { it.copy(iconUrl = value, dirty = true) }
    fun setLang(value: String) = mutableState.update { it.copy(lang = value, dirty = true) }
    fun setBaseUrl(value: String) = mutableState.update { it.copy(baseUrl = value, dirty = true) }

    fun importCode(fileName: String?, text: String) {
        val json = fileName?.endsWith(".json", ignoreCase = true) == true ||
            (fileName?.endsWith(".js", ignoreCase = true) != true && text.trimStart().startsWith("{"))
        val info = quickInfo(text, json)
        closeRuntime()
        mutableState.update {
            it.copy(
                // Importing over a saved source starts a new one, so the saved one isn't replaced by mistake
                key = if (it.saved) manager.newKey() else it.key,
                saved = false,
                json = json,
                code = text,
                anime = json && "\"videos\"" in text || !json && "getVideoList" in text,
                // Straight from the file's text, so the Details show at once (the run below fills any gaps)
                name = info["name"].orEmpty(),
                lang = info["lang"] ?: "en",
                baseUrl = info["baseUrl"].orEmpty(),
                iconUrl = info["iconUrl"].orEmpty(),
                results = emptyList(),
                console = emptyList(),
                dirty = true,
            )
        }
        message("Imported ${fileName ?: "file"}")
        detectDetails(delayMs = 0)
    }

    /**
     * name, lang, baseUrl and iconUrl read from the code's text without running it: the top-level keys of a JSON
     * definition, or `name: "..."` / `NAME = "..."` in a script.
     */
    private fun quickInfo(code: String, json: Boolean): Map<String, String> {
        val keys = mapOf("name" to "NAME", "lang" to "LANG", "baseUrl" to "BASE_URL", "iconUrl" to "ICON_URL")
        if (json) {
            val obj = runCatching {
                jsJson.parseToJsonElement(eu.kanade.tachiyomi.jssource.stripJsonComments(code)) as? JsonObject
            }.getOrNull()
            if (obj != null) return keys.keys.mapNotNull { k -> obj.str(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        }
        return keys.mapNotNull { (key, global) ->
            val pattern = Regex("""(?:\b$key\s*:|\b$global\s*=|"$key"\s*:)\s*(["'`])((?:\\.|(?!\1).)*)\1""")
            pattern.find(code)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
    }

    fun newSource(json: Boolean, anime: Boolean) {
        closeRuntime()
        mutableState.value = State(
            key = manager.newKey(),
            json = json,
            anime = anime,
            code = SourceTemplates.templateFor(json, anime),
        )
    }

    fun open(key: String) {
        closeRuntime()
        val info = manager.get(key) ?: return
        val code = manager.code(key) ?: return
        mutableState.value = State(
            key = info.key,
            saved = true,
            json = info.json,
            anime = info.anime,
            code = code,
            name = info.name,
            iconUrl = info.iconUrl.orEmpty(),
            lang = info.lang,
            baseUrl = info.baseUrl,
        )
    }

    fun delete(): Boolean {
        val key = state.value.key
        if (!state.value.saved) return false
        manager.delete(key)
        closeRuntime()
        return true
    }

    fun consumeSaved() = mutableState.update { it.copy(justSaved = null) }

    fun dismissSaveError() = mutableState.update { it.copy(saveError = null) }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    private fun message(text: String) = mutableState.update { it.copy(message = text) }

    // Saving

    fun save() {
        val s = state.value
        screenModelScope.launchIO {
            mutableState.update { it.copy(running = "Saving") }
            runCatching {
                manager.save(
                    key = s.key,
                    code = s.code,
                    json = s.json,
                    animeHint = s.anime,
                    overrides = JsSourceInfo(
                        key = s.key,
                        name = s.name.trim(),
                        lang = s.lang.trim(),
                        iconUrl = s.iconUrl.trim(),
                        baseUrl = s.baseUrl.trim(),
                    ),
                )
            }.onSuccess { info ->
                mutableState.update {
                    it.copy(
                        saved = true,
                        dirty = false,
                        running = null,
                        anime = info.anime,
                        name = info.name,
                        lang = info.lang,
                        iconUrl = info.iconUrl.orEmpty(),
                        baseUrl = info.baseUrl,
                        justSaved = info,
                    )
                }
            }.onFailure { e ->
                AppLog.add('E', "js", "Saving ${s.name.ifBlank { "a source" }} failed: $e")
                mutableState.update { it.copy(running = null, saveError = e.message ?: e.toString()) }
            }
        }
    }

    // Testing

    private suspend fun runtime(): JsRuntime = runtimeMutex.withLock {
        val s = state.value
        val current = runtime
        if (current != null && runtimeCode == s.json.toString() + s.code) return@withLock current
        current?.close()
        ensureTestCopy(s.key)
        JsRuntime.create(
            context = context,
            key = s.key,
            sourceId = manager.idOf(s.key),
            name = s.name.ifBlank { "editor" },
            code = s.code,
            json = s.json,
            testing = true,
            // KMK: tests use their own copy of the settings and storage, so they don't change the saved source
            testCopy = true,
        ).also {
            runtime = it
            runtimeCode = s.json.toString() + s.code
        }
    }

    /** KMK: which source (key and id) the test copy of the settings and storage was last filled for */
    private var testCopyOf: String? = null

    /** KMK: fills the test copy from the saved source's settings and storage once per source opened here */
    @Synchronized
    private fun ensureTestCopy(key: String) {
        val which = "$key:${manager.idOf(key)}"
        if (testCopyOf == which) return
        manager.prepareTestCopy(key)
        testCopyOf = which
    }

    /** KMK: the settings the editor's Settings dialog edits: the test copy, not the saved source's */
    fun testPreferences(): SharedPreferences {
        val key = state.value.key
        ensureTestCopy(key)
        return JsPreferences.testOf(context, manager.idOf(key))
    }

    private fun closeRuntime() {
        job?.cancel()
        runtime?.close()
        runtime = null
        runtimeCode = null
    }

    /** Fills the Details fields the user left empty from what the source says about itself */
    fun loadInfo() = run("Info") { rt ->
        val meta = rt.metadata()
        mutableState.update {
            it.copy(
                name = it.name.ifBlank { meta.str("name").orEmpty() },
                iconUrl = it.iconUrl.ifBlank { meta.str("iconUrl").orEmpty() },
                lang = it.lang.takeIf { l -> l.isNotBlank() && l != "en" } ?: meta.str("lang") ?: it.lang,
                baseUrl = it.baseUrl.ifBlank { meta.str("baseUrl").orEmpty() },
                anime = when {
                    meta["hasVideos"]?.jsonPrimitive?.contentOrNull == "true" &&
                        meta["hasPages"]?.jsonPrimitive?.contentOrNull != "true" -> true
                    meta["hasPages"]?.jsonPrimitive?.contentOrNull == "true" &&
                        meta["hasVideos"]?.jsonPrimitive?.contentOrNull != "true" -> false
                    else -> it.anime
                },
            )
        }
        Result("Info", true, 0, text = pretty(meta))
    }

    fun runPopular(page: Int = 1) = run("Popular · page $page") { entries(it.call("getPopular", page)) }

    fun runLatest(page: Int = 1) = run("Latest · page $page") { entries(it.call("getLatest", page)) }

    fun runSearch(query: String, page: Int = 1) = run("Search \"$query\" · page $page") {
        entries(it.call("search", query, page, emptyMap<String, Any>()))
    }

    fun runCatalogs() = run("Catalogs") { rt ->
        val catalogs = rt.call("getCatalogs")
        val list = (catalogs as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        Result(
            "Catalogs",
            true,
            0,
            items = list.map { Item(it.s("name") ?: it.s("id").orEmpty(), it.s("id"), null, Kind.Other, "catalog:" + it.s("id")) },
            text = if (list.isEmpty()) "No catalogs" else null,
        )
    }

    fun runFilters() = run("Filters") { Result("Filters", true, 0, text = pretty(it.call("getFilters"))) }

    fun runPreferences() = run("Source settings") { Result("Source settings", true, 0, text = pretty(it.call("getSourcePreferences"))) }

    /**
     * Popular, Latest and a search, one after another, then the first title's chapters and its first chapter.
     * A source with tab catalogs runs page 1 of each tab first, in the order Browse shows them, and takes its test
     * title from the first tab; Popular and Latest then only run when the source has them.
     */
    fun runAll(query: String) {
        job?.cancel()
        job = screenModelScope.launchIO {
            val tabs = tabCatalogs()
            val tabResults = tabs.map { tab ->
                execute("Tab ${tab.name} · page 1") { entries(it.call("getCatalog", tab.id, 1)) }
            }
            val meta = if (tabs.isEmpty()) null else runCatching { runtime().metadata() }.getOrNull()
            val popular = if (tabs.isEmpty() || meta?.flag("hasPopular") != false) {
                execute("Popular · page 1") { entries(it.call("getPopular", 1)) }
            } else {
                null
            }
            if (tabs.isEmpty() || meta?.flag("hasLatest") != false) {
                execute("Latest · page 1") { entries(it.call("getLatest", 1)) }
            }
            if (query.isNotBlank()) {
                execute("Search \"$query\"") { entries(it.call("search", query, 1, emptyMap<String, Any>())) }
            }
            val lists = if (tabs.isEmpty()) listOf(popular) else tabResults + popular
            val entry = lists.firstNotNullOfOrNull { r -> r?.items?.firstOrNull { it.kind == Kind.Entry } } ?: return@launchIO
            val chapters = execute("Chapters of ${entry.title}") { chapters(it, entry.url) } ?: return@launchIO
            val chapter = chapters.items.firstOrNull() ?: return@launchIO
            val what = if (state.value.anime) "Videos" else "Pages"
            execute("$what of ${chapter.title}") { content(it, chapter.url) }
        }
    }

    /** The source's tab catalogs ("tab": true), in the order Browse shows them; none when it has no catalogs */
    private suspend fun tabCatalogs(): List<JsFilters.Catalog> = runCatching {
        val rt = runtime()
        if (rt.metadata().flag("hasCatalogs") != true) return@runCatching emptyList<JsFilters.Catalog>()
        JsFilters.catalogs(rt.call("getCatalogs")).filter { it.tab }
    }.getOrDefault(emptyList())

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /** Opens an item of a result: a title's details and chapters, a chapter's pages or videos, a catalog's list */
    fun openItem(item: Item) {
        when {
            item.kind == Kind.Entry -> run("Chapters of ${item.title}") { chapters(it, item.url) }
            item.kind == Kind.Chapter -> run(if (state.value.anime) "Videos of ${item.title}" else "Pages of ${item.title}") { content(it, item.url) }
            item.url.startsWith("videolist:") -> {
                val url = item.url.removePrefix("videolist:")
                run("Videos of ${item.title.substringBefore(" · ")}") { videos(it, url) }
            }
            item.url.startsWith("catalog:") -> {
                val id = item.url.removePrefix("catalog:")
                run("Catalog ${item.title}") { entries(it.call("getCatalog", id, 1)) }
            }
            else -> Unit
        }
    }

    private suspend fun chapters(rt: JsRuntime, entryUrl: String): Result {
        val details = runCatching { rt.call("getDetails", entryUrl) }.getOrNull() as? JsonObject
        val list = (rt.call("getChapterList", entryUrl) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val label = if (state.value.anime) "episodes" else "chapters"
        return Result(
            title = "",
            ok = true,
            millis = 0,
            items = list.map { ch ->
                Item(
                    title = ch.s("title") ?: ch.s("number")?.let { "${if (state.value.anime) "Episode" else "Chapter"} $it" } ?: ch.s("url").orEmpty(),
                    subtitle = listOfNotNull(ch.s("dateUploaded"), ch.s("scanlator"), ch.s("season")?.let { "season $it" })
                        .joinToString(" · ").ifBlank { null },
                    imageUrl = ch.s("thumbnailUrl") ?: ch.s("seasonCoverUrl"),
                    kind = Kind.Chapter,
                    url = ch.s("url").orEmpty(),
                )
            },
            text = buildString {
                if (details != null) {
                    append(details.s("title").orEmpty())
                    details.s("status")?.let { append(" · ").append(it) }
                    details.s("description")?.let { append("\n").append(it.take(400)) }
                    append("\n\n")
                }
                append("${list.size} $label")
            },
        )
    }

    private suspend fun content(rt: JsRuntime, chapterUrl: String): Result {
        if (state.value.anime) return videos(rt, chapterUrl)
        return pages(rt, chapterUrl)
    }

    private suspend fun videos(rt: JsRuntime, url: String): Result {
        val videos = (rt.call("getVideoList", url) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            "",
            true,
            0,
            items = videos.map { v ->
                val extras = listOfNotNull(
                    (v["headers"] as? JsonObject)?.size?.takeIf { it > 0 }?.let { "$it headers" },
                    (v["subtitles"] as? JsonArray)?.size?.takeIf { it > 0 }?.let { "$it subtitles" },
                    (v["audioTracks"] as? JsonArray)?.size?.takeIf { it > 0 }?.let { "$it audio tracks" },
                )
                Item(
                    title = v.s("quality") ?: "Video",
                    subtitle = (listOf(v.s("url").orEmpty()) + extras).joinToString(" · "),
                    kind = Kind.Video,
                    url = v.s("url").orEmpty(),
                )
            },
            text = "${videos.size} videos",
        )
    }

    private suspend fun pages(rt: JsRuntime, chapterUrl: String): Result {
        val pages = (rt.call("getPageList", chapterUrl) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            "",
            true,
            0,
            items = pages.map { p ->
                val number = (p.s("index")?.toIntOrNull() ?: 0) + 1
                val video = p.s("videoUrl")
                val listUrl = p.s("videoListUrl")
                Item(
                    // KMK: a page with a videoUrl or videoListUrl plays from the reader; tapping a videoListUrl page
                    // here runs getVideoList on it
                    title = when {
                        listUrl != null -> "Page $number · video (tap to test)"
                        video != null -> "Page $number · video"
                        else -> "Page $number"
                    },
                    subtitle = video ?: listUrl ?: p.s("imageUrl") ?: p.s("text")?.take(120),
                    imageUrl = p.s("imageUrl") ?: p.s("thumbnailUrl"),
                    kind = if (listUrl != null) Kind.Other else Kind.Page,
                    url = if (listUrl != null) "videolist:$listUrl" else video ?: p.s("imageUrl").orEmpty(),
                )
            },
            text = pages.count { it.s("videoUrl") != null || it.s("videoListUrl") != null }.let { videos ->
                if (videos == 0) "${pages.size} pages" else "${pages.size} pages, $videos of them videos"
            },
        )
    }

    private fun entries(element: JsonElement): Result {
        val list = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Result(
            title = "",
            ok = true,
            millis = 0,
            items = list.map { e ->
                Item(
                    title = e.s("title").orEmpty(),
                    subtitle = e.s("url"),
                    imageUrl = e.s("coverUrl"),
                    kind = Kind.Entry,
                    url = e.s("url").orEmpty(),
                )
            },
            text = "${list.size} entries",
        )
    }

    private fun run(title: String, block: suspend (JsRuntime) -> Result) {
        job?.cancel()
        job = screenModelScope.launchIO { execute(title, block) }
    }

    /** Runs one test, adds its result (or error) to the panel and returns it, or null when it failed */
    private suspend fun execute(title: String, block: suspend (JsRuntime) -> Result): Result? {
        mutableState.update { it.copy(running = title) }
        val started = System.currentTimeMillis()
        val result = try {
            val rt = runtime()
            val r = block(rt)
            r.copy(title = r.title.ifBlank { title }, millis = System.currentTimeMillis() - started)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result(title, false, System.currentTimeMillis() - started, text = e.message ?: e.toString())
        }
        val console = runtime?.console.orEmpty()
        mutableState.update {
            it.copy(running = null, results = (listOf(result) + it.results).take(20), console = console)
        }
        return result.takeIf { it.ok }
    }

    /** Opens the Source settings dialog, with what getSourcePreferences() offers */
    fun openSettings() = run("Source settings") { rt ->
        val raw = rt.call("getSourcePreferences")
        val defs = JsPreferences.parse(raw)
        mutableState.update { it.copy(settings = defs) }
        Result("Source settings", true, 0, text = if (defs.isEmpty()) "This source has no settings" else pretty(raw))
    }

    fun closeSettings() = mutableState.update { it.copy(settings = null) }

    fun clearResults() {
        runtime?.clearConsole()
        mutableState.update { it.copy(results = emptyList(), console = emptyList()) }
    }

    override fun onDispose() {
        closeRuntime()
        super.onDispose()
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun pretty(element: JsonElement): String = prettyJson.encodeToString(JsonElement.serializer(), element)

    private val prettyJson = kotlinx.serialization.json.Json(from = jsJson) { prettyPrint = true }

    /** KMK: the id this source's settings are kept under (its own `id`, once saved with one) */
    fun sourceId(key: String): Long = manager.idOf(key)
}
