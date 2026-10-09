package eu.kanade.tachiyomi.jarextension

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK: Installs, loads and updates JAR extensions (Kotatsu / Tsuki parser plugins) and keeps their repositories.
 *
 * A JAR repository is a GitHub repository whose latest release carries `.jar` files, the same as in Kototoro.
 * JARs are kept in the app's files, loaded on start, and their sources join the manga sources.
 */
class JarExtensionManager(
    private val context: Context = Injekt.get<Application>(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val network: NetworkHelper by lazy { Injekt.get() }

    private val dir = File(context.filesDir, "jar_extensions").apply { mkdirs() }
    private val metaFile = File(dir, "installed.json")
    private val prefs = context.getSharedPreferences("jar_extensions", Context.MODE_PRIVATE)

    @Serializable
    data class InstalledMeta(
        val pkgName: String,
        val fileName: String,
        val name: String,
        val versionName: String,
        val repoUrl: String? = null,
        val repoName: String? = null,
    )

    /** An installed JAR, loaded or not */
    data class Installed(
        val meta: InstalledMeta,
        val architecture: JarArchitecture?,
        val sources: List<JarSource>,
        val error: String?,
    ) {
        val pkgName get() = meta.pkgName
    }

    /** A JAR a repository offers */
    data class Available(
        val pkgName: String,
        val name: String,
        val versionName: String,
        val downloadUrl: String,
        val fileName: String,
        val repoUrl: String,
        val repoName: String,
    )

    data class Repo(val url: String, val name: String, val error: String? = null)

    private val _installed = MutableStateFlow<List<Installed>>(emptyList())
    val installedFlow: StateFlow<List<Installed>> = _installed.asStateFlow()

    private val _sources = MutableStateFlow<List<JarSource>>(emptyList())
    val sourcesFlow: StateFlow<List<JarSource>> = _sources.asStateFlow()

    private val _available = MutableStateFlow<List<Available>>(emptyList())
    val availableFlow: StateFlow<List<Available>> = _available.asStateFlow()

    private val _repos = MutableStateFlow<List<Repo>>(emptyList())
    val reposFlow: StateFlow<List<Repo>> = _repos.asStateFlow()

    /** Downloads in progress, by package */
    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installingFlow: StateFlow<Set<String>> = _installing.asStateFlow()

    init {
        _repos.value = repoUrls().map { Repo(it, repoDisplayName(it)) }
        scope.launch { reloadAll() }
    }

    // Installed

    private fun readMeta(): List<InstalledMeta> =
        runCatching { json.decodeFromString<List<InstalledMeta>>(metaFile.readText()) }.getOrDefault(emptyList())

    private fun writeMeta(list: List<InstalledMeta>) {
        metaFile.writeText(json.encodeToString(list))
    }

    private suspend fun reloadAll() = mutex.withLock {
        val metas = readMeta().filter { File(dir, it.fileName).exists() }
        val installed = metas.map { meta -> loadOne(meta) }
        _installed.value = installed
        _sources.value = installed.flatMap { it.sources }
    }

    private fun loadOne(meta: InstalledMeta): Installed {
        val file = File(dir, meta.fileName)
        val loaded = runCatching { JarExtensionLoader.load(context, file) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Failed to load ${file.name}" } }
            .getOrNull()
        val error = if (loaded == null) JarExtensionLoader.errors[file.name] ?: "Could not load this JAR" else null
        val sources = loaded?.sources.orEmpty()
            .filter { it.contentType != "NOVEL" }
            .map { JarSource(loaded!!, it, meta.pkgName) }
        return Installed(meta, loaded?.architecture, sources, error)
    }

    // Install / remove

    suspend fun install(available: Available): Result<Unit> = withContext(Dispatchers.IO) {
        _installing.update { it + available.pkgName }
        try {
            val temp = File(context.cacheDir, "jar_${System.nanoTime()}.jar")
            network.client.newCall(GET(available.downloadUrl)).awaitSuccess().use { response ->
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            addFile(
                temp,
                InstalledMeta(
                    pkgName = available.pkgName,
                    fileName = "${available.pkgName}.jar",
                    name = available.name,
                    versionName = available.versionName,
                    repoUrl = available.repoUrl,
                    repoName = available.repoName,
                ),
            )
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "JAR install failed: ${available.downloadUrl}" }
            Result.failure(e)
        } finally {
            _installing.update { it - available.pkgName }
        }
    }

    /** Imports a JAR the user picked from storage */
    suspend fun importLocal(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: "local.jar"
            val base = displayName.removeSuffix(".jar")
            val pkgName = "local." + sanitize(base)
            val temp = File(context.cacheDir, "jar_${System.nanoTime()}.jar")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: error("Can't read the file")
            addFile(temp, InstalledMeta(pkgName, "$pkgName.jar", base, "local"))
                .map { base }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun addFile(temp: File, meta: InstalledMeta): Result<Unit> = mutex.withLock {
        val target = File(dir, meta.fileName)
        // A loaded dex file can't be replaced in place: write a fresh name, then switch the metadata
        val fresh = if (target.exists()) File(dir, "${meta.pkgName}-${System.currentTimeMillis()}.jar") else target
        temp.copyTo(fresh, overwrite = true)
        temp.delete()
        val newMeta = meta.copy(fileName = fresh.name)
        val loaded = loadOne(newMeta)
        if (loaded.architecture == null) {
            fresh.delete()
            return Result.failure(IllegalStateException(loaded.error ?: "Not a supported parser JAR"))
        }
        val old = readMeta().firstOrNull { it.pkgName == meta.pkgName }
        writeMeta(readMeta().filterNot { it.pkgName == meta.pkgName } + newMeta)
        if (old != null && old.fileName != newMeta.fileName) File(dir, old.fileName).delete()
        _installed.update { list -> list.filterNot { it.pkgName == meta.pkgName } + loaded }
        _sources.value = _installed.value.flatMap { it.sources }
        Result.success(Unit)
    }

    fun uninstall(pkgName: String) {
        scope.launch {
            mutex.withLock {
                val meta = readMeta().firstOrNull { it.pkgName == pkgName } ?: return@withLock
                writeMeta(readMeta().filterNot { it.pkgName == pkgName })
                File(dir, meta.fileName).delete()
                _installed.update { list -> list.filterNot { it.pkgName == pkgName } }
                _sources.value = _installed.value.flatMap { it.sources }
            }
        }
    }

    // Repositories

    private fun repoUrls(): List<String> = prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty().sorted()

    /** Whether [url] is something JAR repositories are made of (a GitHub repository) */
    fun isJarRepoUrl(url: String): Boolean = githubRepo(url) != null

    fun addRepo(url: String): Result<Unit> {
        val repo = githubRepo(url) ?: return Result.failure(IllegalArgumentException("Not a GitHub repository URL"))
        val normalized = "https://github.com/${repo.first}/${repo.second}"
        prefs.edit().putStringSet(KEY_REPOS, repoUrls().toSet() + normalized).apply()
        _repos.value = repoUrls().map { Repo(it, repoDisplayName(it)) }
        scope.launch { refreshRepos() }
        return Result.success(Unit)
    }

    fun removeRepo(url: String) {
        prefs.edit().putStringSet(KEY_REPOS, repoUrls().toSet() - url).apply()
        _repos.value = repoUrls().map { Repo(it, repoDisplayName(it)) }
        _available.update { list -> list.filterNot { it.repoUrl == url } }
    }

    @Serializable
    private data class GitHubAsset(val name: String, val browser_download_url: String)

    @Serializable
    private data class GitHubRelease(val tag_name: String, val assets: List<GitHubAsset> = emptyList())

    suspend fun refreshRepos() = withContext(Dispatchers.IO) {
        val repos = repoUrls()
        val errors = HashMap<String, String>()
        val available = repos.flatMap { url ->
            val (owner, name) = githubRepo(url) ?: return@flatMap emptyList()
            try {
                val body = network.client
                    .newCall(GET("https://api.github.com/repos/$owner/$name/releases?per_page=10"))
                    .awaitSuccess()
                    .use { it.body.string() }
                val release = json.decodeFromString<List<GitHubRelease>>(body)
                    .firstOrNull { r -> r.assets.any { it.name.endsWith(".jar", ignoreCase = true) } }
                    ?: error("No release with .jar files")
                val jars = release.assets.filter { it.name.endsWith(".jar", ignoreCase = true) }
                jars.map { asset ->
                    val base = asset.name.removeSuffix(".jar").removeSuffix(".JAR")
                    Available(
                        pkgName = "$owner.$name.${sanitize(base)}".lowercase(),
                        name = if (jars.size == 1) name else base,
                        versionName = release.tag_name,
                        downloadUrl = asset.browser_download_url,
                        fileName = asset.name,
                        repoUrl = url,
                        repoName = repoDisplayName(url),
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "JAR repo failed: $url" }
                errors[url] = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        }
        _available.value = available
        _repos.value = repos.map { Repo(it, repoDisplayName(it), errors[it]) }
    }

    private fun githubRepo(url: String): Pair<String, String>? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (parsed.host != "github.com" && parsed.host != "www.github.com") return null
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private fun repoDisplayName(url: String): String =
        githubRepo(url)?.let { "${it.first}/${it.second}" } ?: url

    private fun sanitize(value: String) = value.lowercase().replace(Regex("[^a-z0-9._-]"), "-")

    companion object {
        private const val KEY_REPOS = "repos"
    }
}
