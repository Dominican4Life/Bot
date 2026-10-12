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

    // Only the sources switched on: one switched off is left out, so an APK with the same id is used instead
    val mangaSourcesFlow: StateFlow<List<SumizuriMangaSource>> = _saved
        .map { list -> list.filter { !it.anime && it.enabled }.map { SumizuriMangaSource(it, this) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val animeSourcesFlow: StateFlow<List<SumizuriAnimeSource>> = _saved
        .map { list -> list.filter { it.anime && it.enabled }.map { SumizuriAnimeSource(it, this) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // KMK --> APK sources switched off while a JS source has their id. Kept apart from the app's disabled sources,
    // which go by id too and would hide the JS source along with the APK.
    private val apkOffFile = File(root, "apk_off.txt")
    private val _apkOff = MutableStateFlow(
        runCatching { apkOffFile.readLines().mapNotNull { it.trim().toLongOrNull() }.toSet() }.getOrDefault(emptySet()),
    )

    /** Ids of APK sources switched off that a JS source has taken over */
    val apkOffFlow: StateFlow<Set<Long>> = _apkOff.asStateFlow()

    /** True when a saved JS source took [id] with its `id` line, so switching the APK goes through [setApkEnabled] */
    fun sharesId(id: Long, anime: Boolean): Boolean = _saved.value.any { it.anime == anime && it.customId == id }

    fun setApkEnabled(id: Long, enabled: Boolean) {
        val now = if (enabled) _apkOff.value - id else _apkOff.value + id
        _apkOff.value = now
        runCatching { apkOffFile.writeText(now.joinToString("\n")) }
    }

    /** Switches a JS source on or off. Off: the app ignores it, nothing is deleted */
    fun setEnabled(key: String, enabled: Boolean) {
        val info = get(key)?.takeIf { it.enabled != enabled }?.copy(enabled = enabled) ?: return
        runCatching {
            File(dir(key), META).writeText(sumizuriJson.encodeToString(SumizuriSourceInfo.serializer(), info))
        }
        if (!enabled) invalidate(key)
        _saved.value = _saved.value.map { if (it.key == key) info else it }
        AppLog.add('I', "sumizuri", "${info.name} switched ${if (enabled) "on" else "off"}")
    }
    // KMK <--

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
        // the `id` line was removed or changed: an APK switched off for the old id is used again
        existing?.customId?.takeIf { it != info.customId }?.let { setApkEnabled(it, true) }
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
            customId = customId(meta.str("id"), key, meta.str("name")),
            enabled = existing?.enabled ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * KMK: The id a source asked for with `id`, when it may have it. Not the ids of the app's own sources (E-Hentai,
     * local, merged…), and not one another saved JS source already has. A JS source with the id of an installed APK
     * takes its place: the APK stays installed but isn't used.
     */
    private fun customId(raw: String?, key: String, name: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val id = text.toLongOrNull()
        val reason = when {
            id == null -> "isn't a whole number"
            id in reservedIds() -> "belongs to one of the app's own sources"
            _saved.value.any { it.key != key && it.id == id } -> "is already used by another saved source"
            else -> null
        }
        if (reason == null) return id
        AppLog.add('W', "sumizuri", "${name ?: key}: id $text $reason, so the source keeps its own id")
        return null
    }

    private fun reservedIds(): Set<Long> = buildSet {
        add(tachiyomi.source.local.LocalSource.ID)
        add(exh.source.EH_SOURCE_ID)
        add(exh.source.EXH_SOURCE_ID)
        add(exh.source.MERGED_SOURCE_ID)
        addAll(exh.source.EHENTAI_EXT_SOURCES.keys)
        addAll(exh.source.EXHENTAI_EXT_SOURCES.keys)
        add(eu.kanade.tachiyomi.devicefiles.DeviceFilesIds.MANGA)
        add(eu.kanade.tachiyomi.devicefiles.DeviceFilesIds.ANIME)
        add(ReaderVideos.SOURCE_ID)
    }

    /** The id a source's settings and videos go by: its own id once saved, or the one made from its key */
    fun idOf(key: String): Long = get(key)?.id ?: SumizuriSourceInfo.idFor(key)

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

    // KMK --> the tab catalog each source was last opened on
    private val tabPrefs by lazy { context.getSharedPreferences("sumizuri_tabs", android.content.Context.MODE_PRIVATE) }

    fun lastTab(key: String): String? = runCatching { tabPrefs.getString(key, null) }.getOrNull()

    fun rememberTab(key: String, id: String) {
        runCatching { tabPrefs.edit().putString(key, id).apply() }
    }
    // KMK <--

    fun delete(key: String) {
        // an APK switched off for this source is used again
        get(key)?.customId?.let { setApkEnabled(it, true) }
        runCatching { tabPrefs.edit().remove(key).apply() }
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
