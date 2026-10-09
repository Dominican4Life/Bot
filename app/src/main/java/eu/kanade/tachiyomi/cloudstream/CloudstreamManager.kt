package eu.kanade.tachiyomi.cloudstream

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.ref.WeakReference
import java.util.zip.ZipFile

/**
 * KMK: Installs and loads Cloudstream 3 plugins (.cs3) and keeps their repositories (`repo.json` with plugin
 * lists), as in Kototoro. Each provider a plugin registers becomes an anime source ([CloudstreamAnimeSource]).
 */
class CloudstreamManager(
    private val context: Context = Injekt.get<Application>(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val network: NetworkHelper by lazy { Injekt.get() }

    private val dir = File(context.filesDir, "cloudstream_plugins").apply { mkdirs() }
    private val codeDir = File(context.codeCacheDir, "cloudstream").apply { mkdirs() }
    private val metaFile = File(dir, "installed.json")
    private val prefs = context.getSharedPreferences("cloudstream_extensions", Context.MODE_PRIVATE)

    @Serializable
    data class InstalledMeta(
        val internalName: String,
        val fileName: String,
        val name: String,
        val version: Int,
        val repoUrl: String? = null,
        val repoName: String? = null,
        val iconUrl: String? = null,
        val language: String? = null,
    )

    data class Installed(
        val meta: InstalledMeta,
        val sources: List<CloudstreamAnimeSource>,
        val error: String?,
    ) {
        val internalName get() = meta.internalName
    }

    data class Available(
        val internalName: String,
        val name: String,
        val version: Int,
        val url: String,
        val iconUrl: String?,
        val language: String?,
        val description: String?,
        val tvTypes: List<String>,
        val repoUrl: String,
        val repoName: String,
        val status: Int,
    )

    data class Repo(val url: String, val name: String, val error: String? = null)

    private val _installed = MutableStateFlow<List<Installed>>(emptyList())
    val installedFlow: StateFlow<List<Installed>> = _installed.asStateFlow()

    private val _sources = MutableStateFlow<List<CloudstreamAnimeSource>>(emptyList())
    val sourcesFlow: StateFlow<List<CloudstreamAnimeSource>> = _sources.asStateFlow()

    private val _available = MutableStateFlow<List<Available>>(emptyList())
    val availableFlow: StateFlow<List<Available>> = _available.asStateFlow()

    private val _repos = MutableStateFlow<List<Repo>>(emptyList())
    val reposFlow: StateFlow<List<Repo>> = _repos.asStateFlow()

    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installingFlow: StateFlow<Set<String>> = _installing.asStateFlow()

    private val loaders = HashMap<String, List<MainAPI>>()

    init {
        _repos.value = repoEntries().map { Repo(it.first, it.second) }
        scope.launch {
            runCatching { setupRuntime() }.onFailure { logcat(LogPriority.ERROR, it) { "Cloudstream runtime setup failed" } }
            reloadAll()
        }
    }

    private fun setupRuntime() {
        setContext(WeakReference(context as Any))
        CloudStreamApp.context = context
        app.baseClient = network.client
    }

    // Installed

    private fun readMeta(): List<InstalledMeta> =
        runCatching { json.decodeFromString<List<InstalledMeta>>(metaFile.readText()) }.getOrDefault(emptyList())

    private fun writeMeta(list: List<InstalledMeta>) = metaFile.writeText(json.encodeToString(list))

    private suspend fun reloadAll() = mutex.withLock {
        val installed = readMeta().filter { File(dir, it.fileName).exists() }.map { load(it) }
        _installed.value = installed
        publish()
    }

    private fun publish() {
        _sources.value = _installed.value.flatMap { it.sources }.distinctBy { it.id }
    }

    private fun load(meta: InstalledMeta): Installed {
        val file = File(dir, meta.fileName)
        return try {
            val manifest = ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: error("No manifest.json in plugin")
                JSONObject(zip.getInputStream(entry).bufferedReader().readText())
            }
            val className = manifest.optString("pluginClassName").takeIf { it.isNotBlank() }
                ?: error("Plugin manifest names no class")
            file.setReadOnly()
            val loader = DexClassLoader(file.absolutePath, codeDir.absolutePath, null, context.classLoader)
            val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
            plugin.filename = file.absolutePath
            if (manifest.optBoolean("requiresResources", false) && plugin is Plugin) {
                plugin.resources = pluginResources(file)
            }
            if (plugin is Plugin) plugin.load(context) else plugin.load()
            val providers = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filter { it.sourcePlugin == plugin.filename }
            }
            providers.forEach { runCatching { it.init() } }
            loaders[meta.internalName] = providers
            Installed(
                meta = meta,
                sources = providers.map { CloudstreamAnimeSource(it, meta.internalName) },
                error = if (providers.isEmpty()) "The plugin registered no providers" else null,
            )
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Cloudstream plugin failed: ${file.name}" }
            Installed(meta, emptyList(), "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun pluginResources(file: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, file.absolutePath)
        return Resources(assets, context.resources.displayMetrics, context.resources.configuration)
    }

    private fun unload(internalName: String, fileName: String) {
        val path = File(dir, fileName).absolutePath
        loaders.remove(internalName)?.forEach { runCatching { APIHolder.removePluginMapping(it) } }
        synchronized(APIHolder.allProviders) { APIHolder.allProviders.removeIf { it.sourcePlugin == path } }
        synchronized(extractorApis) { extractorApis.removeIf { it.sourcePlugin == path } }
    }

    // Install / remove

    suspend fun install(available: Available): Result<Unit> = withContext(Dispatchers.IO) {
        _installing.update { it + available.internalName }
        try {
            val temp = File(context.cacheDir, "cs3_${System.nanoTime()}.cs3")
            network.client.newCall(GET(available.url)).awaitSuccess().use { response ->
                temp.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            addFile(
                temp,
                InstalledMeta(
                    internalName = available.internalName,
                    fileName = "${sanitize(available.internalName)}.cs3",
                    name = available.name,
                    version = available.version,
                    repoUrl = available.repoUrl,
                    repoName = available.repoName,
                    iconUrl = available.iconUrl,
                    language = available.language,
                ),
            )
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Cloudstream install failed: ${available.url}" }
            Result.failure(e)
        } finally {
            _installing.update { it - available.internalName }
        }
    }

    suspend fun importLocal(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: "plugin.cs3"
            val base = displayName.substringBeforeLast('.')
            val temp = File(context.cacheDir, "cs3_${System.nanoTime()}.cs3")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: error("Can't read the file")
            addFile(temp, InstalledMeta("local.$base", "local.${sanitize(base)}.cs3", base, 0)).map { base }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun addFile(temp: File, meta: InstalledMeta): Result<Unit> = mutex.withLock {
        val old = readMeta().firstOrNull { it.internalName == meta.internalName }
        if (old != null) unload(old.internalName, old.fileName)
        // A loaded dex can't be replaced in place: use a fresh name each time
        val fresh = File(dir, "${meta.fileName.removeSuffix(".cs3")}-${System.currentTimeMillis()}.cs3")
        temp.copyTo(fresh, overwrite = true)
        temp.delete()
        val newMeta = meta.copy(fileName = fresh.name)
        val loaded = load(newMeta)
        if (loaded.sources.isEmpty()) {
            unload(newMeta.internalName, newMeta.fileName)
            fresh.delete()
            return Result.failure(IllegalStateException(loaded.error ?: "Not a Cloudstream plugin"))
        }
        writeMeta(readMeta().filterNot { it.internalName == meta.internalName } + newMeta)
        old?.let { File(dir, it.fileName).delete() }
        _installed.update { list -> list.filterNot { it.internalName == meta.internalName } + loaded }
        publish()
        Result.success(Unit)
    }

    fun uninstall(internalName: String) {
        scope.launch {
            mutex.withLock {
                val meta = readMeta().firstOrNull { it.internalName == internalName } ?: return@withLock
                unload(meta.internalName, meta.fileName)
                writeMeta(readMeta().filterNot { it.internalName == internalName })
                File(dir, meta.fileName).delete()
                _installed.update { list -> list.filterNot { it.internalName == internalName } }
                publish()
            }
        }
    }

    // Repositories

    @Serializable
    private data class RepoManifest(
        val name: String? = null,
        val pluginLists: List<String> = emptyList(),
    )

    @Serializable
    private data class PluginEntry(
        val url: String,
        val status: Int = 1,
        val version: Int = 0,
        val name: String,
        val internalName: String,
        val description: String? = null,
        val language: String? = null,
        val iconUrl: String? = null,
        val tvTypes: List<String>? = null,
        @SerialName("repositoryUrl") val repositoryUrl: String? = null,
    )

    /** url to name */
    private fun repoEntries(): List<Pair<String, String>> =
        prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty().sorted().map { entry ->
            val url = entry.substringBefore('\n')
            url to entry.substringAfter('\n', url.repoFallbackName())
        }

    private fun String.repoFallbackName(): String =
        toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() }?.dropLast(1)?.lastOrNull() ?: this

    /**
     * Whether [url] is a Cloudstream repository: a `repo.json` listing plugin lists. Shortcodes and
     * `cloudstreamrepo://` links are turned into their https form first.
     */
    suspend fun probeRepo(url: String): RepoProbe? = withContext(Dispatchers.IO) {
        val normalized = normalizeRepoUrl(url) ?: return@withContext null
        runCatching {
            val body = network.client.newCall(GET(normalized)).awaitSuccess().use { it.body.string() }
            val manifest = json.decodeFromString<RepoManifest>(body)
            if (manifest.pluginLists.isEmpty()) null else RepoProbe(normalized, manifest.name ?: normalized.repoFallbackName())
        }.getOrNull()
    }

    data class RepoProbe(val url: String, val name: String)

    private fun normalizeRepoUrl(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("cloudstreamrepo://") -> "https://" + trimmed.removePrefix("cloudstreamrepo://")
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            else -> null
        }
    }

    fun addRepo(probe: RepoProbe) {
        val set = prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty()
            .filterNot { it.substringBefore('\n') == probe.url }
            .toSet() + "${probe.url}\n${probe.name}"
        prefs.edit().putStringSet(KEY_REPOS, set).apply()
        _repos.value = repoEntries().map { Repo(it.first, it.second) }
        scope.launch { refreshRepos() }
    }

    fun removeRepo(url: String) {
        val set = prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty().filterNot { it.substringBefore('\n') == url }.toSet()
        prefs.edit().putStringSet(KEY_REPOS, set).apply()
        _repos.value = repoEntries().map { Repo(it.first, it.second) }
        _available.update { list -> list.filterNot { it.repoUrl == url } }
    }

    suspend fun refreshRepos() = withContext(Dispatchers.IO) {
        val repos = repoEntries()
        val errors = HashMap<String, String>()
        val available = repos.flatMap { (url, name) ->
            try {
                val body = network.client.newCall(GET(url)).awaitSuccess().use { it.body.string() }
                val manifest = json.decodeFromString<RepoManifest>(body)
                manifest.pluginLists.flatMap { listUrl ->
                    val listBody = network.client.newCall(GET(listUrl)).awaitSuccess().use { it.body.string() }
                    json.decodeFromString<List<PluginEntry>>(listBody).map { entry ->
                        Available(
                            internalName = entry.internalName,
                            name = entry.name,
                            version = entry.version,
                            url = entry.url,
                            iconUrl = entry.iconUrl?.replace("%size%", "128"),
                            language = entry.language,
                            description = entry.description,
                            tvTypes = entry.tvTypes.orEmpty(),
                            repoUrl = url,
                            repoName = manifest.name ?: name,
                            status = entry.status,
                        )
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Cloudstream repo failed: $url" }
                errors[url] = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        }
        _available.value = available.filter { it.status != 0 }.distinctBy { it.internalName }
        _repos.value = repos.map { Repo(it.first, it.second, errors[it.first]) }
    }

    private fun sanitize(value: String) = value.lowercase().replace(Regex("[^a-z0-9._-]"), "-")

    companion object {
        private const val KEY_REPOS = "repos"
    }
}
