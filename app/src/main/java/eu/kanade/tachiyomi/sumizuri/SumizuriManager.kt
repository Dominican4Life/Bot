package eu.kanade.tachiyomi.sumizuri

import android.app.Application
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

/**
 * KMK: Keeps the JS / JSON sources made in the Code editor (or imported), and turns them into manga and anime
 * sources for the source managers. Each source lives in its own folder: its code and what's known about it.
 */
class SumizuriManager(private val context: Application) {

    private val root = File(context.filesDir, "sumizuri_sources").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _saved = MutableStateFlow(load().also { SumizuriIcons.prepare(context, it) })

    /** Every saved source, newest change first */
    val savedFlow: StateFlow<List<SumizuriSourceInfo>> = _saved.asStateFlow()

    /** Tag lists for the sources' autocomplete filters */
    val tags = SumizuriTags(context, ::code)

    private val runtimes = HashMap<String, SumizuriRuntime>()
    private val runtimeLock = Mutex()

    val mangaSourcesFlow: StateFlow<List<SumizuriMangaSource>> = _saved
        .map { list -> list.filterNot { it.anime }.map { SumizuriMangaSource(it, this) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val animeSourcesFlow: StateFlow<List<SumizuriAnimeSource>> = _saved
        .map { list -> list.filter { it.anime }.map { SumizuriAnimeSource(it, this) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun dir(key: String) = File(root, key)

    private fun load(): List<SumizuriSourceInfo> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { folder ->
            runCatching {
                sumizuriJson.decodeFromString(SumizuriSourceInfo.serializer(), File(folder, META).readText())
            }.onFailure { AppLog.add('W', "sumizuri", "Couldn't read source ${folder.name}: ${it.message}") }
                .getOrNull()
        }
        .sortedByDescending { it.updatedAt }

    fun get(key: String): SumizuriSourceInfo? = _saved.value.firstOrNull { it.key == key }

    fun code(key: String): String? = get(key)?.let { info ->
        runCatching { File(dir(key), codeFile(info.json)).readText() }.getOrNull()
    }

    private fun codeFile(json: Boolean) = if (json) "source.json" else "source.js"

    fun newKey(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Saves [code] under [key] (new or existing): it is run once to read its name, language and so on, and what it
     * offers (filters, catalogs, settings). Throws with the source's own error when it can't run.
     */
    suspend fun save(key: String, code: String, json: Boolean, animeHint: Boolean?, overrides: SumizuriSourceInfo? = null): SumizuriSourceInfo {
        val existing = get(key)
        val runtime = SumizuriRuntime.create(context, key, SumizuriSourceInfo.idFor(key), overrides?.name ?: "new source", code, json)
        val info = try {
            describe(runtime, key, json, animeHint, existing, overrides)
        } finally {
            runtime.close()
        }
        val folder = dir(key).apply { mkdirs() }
        File(folder, codeFile(!json)).delete()
        File(folder, codeFile(json)).writeText(code)
        File(folder, META).writeText(sumizuriJson.encodeToString(SumizuriSourceInfo.serializer(), info))
        invalidate(key)
        showLanguage(info)
        SumizuriIcons.prepare(context, listOf(info))
        _saved.value = (listOf(info) + _saved.value.filterNot { it.key == key }).sortedByDescending { it.updatedAt }
        AppLog.add('I', "sumizuri", "Saved ${if (info.anime) "anime" else "manga"} source ${info.name} (${if (json) "JSON" else "JS"})")
        return info
    }

    /** Reads what a running source says about itself */
    suspend fun describe(
        runtime: SumizuriRuntime,
        key: String,
        json: Boolean,
        animeHint: Boolean?,
        existing: SumizuriSourceInfo?,
        overrides: SumizuriSourceInfo? = null,
    ): SumizuriSourceInfo {
        val meta = runtime.metadata()
        val hasVideos = meta.bool("hasVideos") == true
        val hasPages = meta.bool("hasPages") == true
        val anime = when {
            hasVideos && !hasPages -> true
            hasPages && !hasVideos -> false
            else -> animeHint ?: hasVideos
        }
        suspend fun optional(method: String, flag: String) =
            if (meta.bool(flag) == true) runCatching { runtime.call(method) }.getOrNull() else null
        return SumizuriSourceInfo(
            key = key,
            name = overrides?.name?.takeIf { it.isNotBlank() } ?: meta.str("name")?.takeIf { it.isNotBlank() } ?: existing?.name ?: "Untitled source",
            lang = overrides?.lang?.takeIf { it.isNotBlank() } ?: meta.str("lang") ?: existing?.lang ?: "en",
            iconUrl = overrides?.iconUrl?.takeIf { it.isNotBlank() } ?: meta.str("iconUrl") ?: existing?.iconUrl,
            baseUrl = overrides?.baseUrl?.takeIf { it.isNotBlank() } ?: meta.str("baseUrl") ?: existing?.baseUrl.orEmpty(),
            json = json,
            anime = anime,
            hasDetails = meta.bool("hasDetails") == true,
            headers = meta.obj("headers")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty(),
            filters = optional("getFilters", "hasFilters"),
            catalogs = optional("getCatalogs", "hasCatalogs"),
            preferences = optional("getSourcePreferences", "hasPreferences"),
            hasTags = meta.bool("hasTags") == true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** A saved source is listed in Browse even when its language was turned off there */
    private fun showLanguage(info: SumizuriSourceInfo) {
        runCatching {
            val preferences = Injekt.get<SourcePreferences>()
            if (info.anime) {
                val disabled = preferences.animeDisabledLanguages()
                if (info.displayLang in disabled.get()) disabled.set(disabled.get() - info.displayLang)
            } else {
                val enabled = preferences.enabledLanguages()
                if (info.displayLang !in enabled.get()) enabled.set(enabled.get() + info.displayLang)
            }
            val disabledSources = preferences.disabledSources()
            if (info.id.toString() in disabledSources.get()) disabledSources.set(disabledSources.get() - info.id.toString())
        }
    }

    // KMK --> device sync

    /** Saved sources (all, or those with [keys]) as (details as JSON, code), to send to a paired device */
    fun exportSources(keys: Set<String>? = null): List<Pair<String, String>> = _saved.value
        .filter { keys == null || it.key in keys }
        .mapNotNull { info ->
            code(info.key)?.let { sumizuriJson.encodeToString(SumizuriSourceInfo.serializer(), info) to it }
        }

    /**
     * Adds a source received from a paired device, as it was saved there (it isn't run). Returns false when this
     * device already has the same or a newer copy.
     */
    fun importSource(infoJson: String, code: String): Boolean {
        val info = sumizuriJson.decodeFromString(SumizuriSourceInfo.serializer(), infoJson)
        val existing = get(info.key)
        if (existing != null && existing.updatedAt >= info.updatedAt) return false
        val folder = dir(info.key).apply { mkdirs() }
        File(folder, codeFile(!info.json)).delete()
        File(folder, codeFile(info.json)).writeText(code)
        File(folder, META).writeText(sumizuriJson.encodeToString(SumizuriSourceInfo.serializer(), info))
        invalidate(info.key)
        showLanguage(info)
        SumizuriIcons.prepare(context, listOf(info))
        _saved.value = (listOf(info) + _saved.value.filterNot { it.key == info.key }).sortedByDescending { it.updatedAt }
        AppLog.add('I', "sumizuri", "Got ${info.name} from a paired device")
        return true
    }
    // KMK <--

    fun delete(key: String) {
        invalidate(key)
        dir(key).deleteRecursively()
        File(context.filesDir, "sumizuri_storage/$key").deleteRecursively()
        tags.forget(key)
        _saved.value = _saved.value.filterNot { it.key == key }
    }

    private fun invalidate(key: String) {
        scope.launch {
            runtimeLock.withLock { runtimes.remove(key) }?.close()
        }
    }

    /** The running source for [info], started on first use and kept */
    suspend fun runtime(info: SumizuriSourceInfo): SumizuriRuntime = runtimeLock.withLock {
        runtimes[info.key] ?: run {
            val code = code(info.key) ?: throw IllegalStateException("The code of ${info.name} is missing")
            SumizuriRuntime.create(context, info.key, info.id, info.name, code, info.json).also {
                it.metadata()
                runtimes[info.key] = it
            }
        }
    }

    /** Picks up filters / catalogs / settings a source now gives, without a save */
    fun refreshDescription(info: SumizuriSourceInfo) {
        scope.launch {
            runCatching {
                val fresh = describe(runtime(info), info.key, info.json, info.anime, info).copy(updatedAt = info.updatedAt)
                val changed = fresh.filters != info.filters || fresh.catalogs != info.catalogs ||
                    fresh.preferences != info.preferences || fresh.hasTags != info.hasTags
                if (changed) {
                    File(dir(info.key), META).writeText(sumizuriJson.encodeToString(SumizuriSourceInfo.serializer(), fresh))
                    _saved.value = _saved.value.map { if (it.key == info.key) fresh else it }
                }
            }
        }
    }

    private companion object {
        const val META = "meta.json"
    }
}
