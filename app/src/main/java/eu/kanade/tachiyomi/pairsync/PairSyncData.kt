package eu.kanade.tachiyomi.pairsync

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.AnimeBackupHelper
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jssource.JsSourceManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.service.ExtensionStoreUrls
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * KMK: What to sync, chosen on the device that presses Sync (the other device sends the same choice back).
 * A null list means all: [mangaCategories] / [animeCategories] are category names ("" is Default), matched by name on
 * each device since their ids differ; [sourceKeys] are Code editor source keys, the same on both devices once synced;
 * [repoUrls] are repo index URLs. The field names are saved as JSON (`pair_sync_selection`), so keep them.
 */
@Serializable
data class SyncSelection(
    val mangaLibrary: Boolean = true,
    val mangaCategories: Set<String>? = null,
    val animeLibrary: Boolean = true,
    /** Code editor sources; only count while [extensions] is on */
    val sources: Boolean = true,
    val sourceKeys: Set<String>? = null,
    val mangaHistory: Boolean = true,
    val animeHistory: Boolean = true,
    // KMK (r89) -->
    val animeCategories: Set<String>? = null,
    /** The Extensions tile: repos, installed extensions, sideloaded APKs and Code editor sources */
    val extensions: Boolean = true,
    val repos: Boolean = true,
    val repoUrls: Set<String>? = null,
    /** Offer the other device's missing extensions and newer versions (from repos) */
    val installedExtensions: Boolean = true,
    /** Send APKs of extensions not from any repo that the other device doesn't have */
    val sideloadedApks: Boolean = true,
    // KMK <--
) {
    val syncsSources: Boolean get() = extensions && sources
    val syncsRepos: Boolean get() = extensions && repos
    val syncsInstalled: Boolean get() = extensions && installedExtensions
    val syncsApks: Boolean get() = extensions && sideloadedApks

    val isEmpty: Boolean
        get() = !mangaLibrary && !animeLibrary && !mangaHistory && !animeHistory &&
            !syncsSources && !syncsRepos && !syncsInstalled && !syncsApks

    fun describe(): String = buildList {
        if (animeLibrary) {
            add(if (animeCategories == null) "anime library" else "anime in ${animeCategories.size} categories")
        }
        if (mangaLibrary) {
            add(if (mangaCategories == null) "manga library" else "manga in ${mangaCategories.size} categories")
        }
        when {
            mangaHistory && animeHistory -> add("all history")
            mangaHistory -> add("manga history")
            animeHistory -> add("anime history")
        }
        if (syncsRepos) add(if (repoUrls == null) "repos" else "${repoUrls.size} repos")
        if (syncsInstalled) add("installed extensions")
        if (syncsApks) add("sideloaded APKs")
        if (syncsSources) add(if (sourceKeys == null) "all code sources" else "${sourceKeys.size} code sources")
    }.joinToString(", ")
}

@Serializable
internal class SyncSource(val info: String, val code: String)

/** KMK (r89): an extension repo of the sending device */
@Serializable
internal class SyncRepo(val url: String, val name: String = "", val anime: Boolean = false)

/** KMK (r89): an extension installed on the sending device */
@Serializable
internal class SyncExtension(
    val pkgName: String,
    val name: String = "",
    val versionCode: Long = 0L,
    val lang: String = "",
    val isAnime: Boolean = false,
    val fromRepo: Boolean = false,
    val repoUrl: String? = null,
    val signatureHash: String = "",
)

/** KMK (r89): the APK of an extension not from any repo, in Base64 */
@Serializable
internal class SyncApk(
    val pkgName: String,
    val name: String = "",
    val isAnime: Boolean = false,
    val versionCode: Long = 0L,
    val apk: String,
)

/**
 * Everything one device sends: its Code editor sources, its manga and anime as a backup, and (r89) its repos and
 * extensions. New fields need defaults: older versions leave them out (and skip ones they don't know).
 */
@Serializable
internal class SyncPayload(
    val sources: List<SyncSource> = emptyList(),
    /** A gzipped app backup with the chosen manga and anime, in Base64 */
    val backup: String? = null,
    // KMK (r89) -->
    val repos: List<SyncRepo> = emptyList(),
    val extensions: List<SyncExtension> = emptyList(),
    val apks: List<SyncApk> = emptyList(),
    /** Every extension package installed on the sender, so the other side sends only APKs it lacks */
    val installed: List<String>? = null,
    // KMK <--
)

/** KMK (r89): an extension the other device has that can be installed (or updated) here from a repo */
data class ExtensionOffer(
    val pkgName: String,
    val name: String,
    val isAnime: Boolean,
    val signatureHash: String,
    val fromVersion: Long,
    val toVersion: Long,
)

/** KMK (r89): an APK the other device sent (not from a repo), saved in the cache until installed */
data class ReceivedApk(val pkgName: String, val name: String, val isAnime: Boolean, val file: File)

data class SyncSummary(
    val sourcesAdded: Int = 0,
    val manga: Int = 0,
    val anime: Int = 0,
    val errors: Int = 0,
    // KMK (r89) -->
    val historyEntries: Int = 0,
    val reposAdded: Int = 0,
    val missing: List<ExtensionOffer> = emptyList(),
    val updates: List<ExtensionOffer> = emptyList(),
    val apks: List<ReceivedApk> = emptyList(),
    // KMK <--
) {
    override fun toString(): String = buildList {
        add(if (sourcesAdded == 1) "1 source" else "$sourcesAdded sources")
        add("$manga manga")
        add("$anime anime")
        if (historyEntries > 0) add("$historyEntries history entries")
        if (reposAdded > 0) add(if (reposAdded == 1) "1 repo" else "$reposAdded repos")
        if (missing.isNotEmpty() || apks.isNotEmpty()) add("${missing.size + apks.size} extensions to install")
        if (updates.isNotEmpty()) add("${updates.size} updates")
        if (errors > 0) add("$errors couldn't be added")
    }.joinToString(", ")
}

/** KMK (r89): what a paired device sent, decoded once (the device pressing Sync reads its list of extensions first) */
class ReceivedPayload internal constructor(internal val payload: SyncPayload) {
    /** The other device's installed extension packages, or null for an older version that doesn't say */
    val installed: Set<String>? get() = payload.installed?.toSet()
}

/**
 * KMK: Builds what this device sends to a paired one and merges what it gets back. Both ways use the backup
 * restore's merge: entries are added, a chapter or episode read on either device stays read, the further progress
 * and the latest history time are kept, and nothing is taken away. Device files entries stay on their device (the
 * files are only there). Repos are only added and extensions are only offered (installs ask first).
 */
object PairSyncData {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser: ProtoBuf get() = Injekt.get()

    /** One APK larger than this isn't sent */
    private const val MAX_APK = 64L * 1024 * 1024

    /** All APKs together stay under this */
    private const val MAX_APKS = 256L * 1024 * 1024

    /** Every extension package installed here (manga and anime, trusted or not) */
    fun installedPackages(): List<String> {
        val manga = Injekt.get<ExtensionManager>()
        val anime = Injekt.get<AnimeExtensionManager>()
        return (
            manga.installedExtensionsFlow.value.map { it.pkgName } +
                manga.untrustedExtensionsFlow.value.map { it.pkgName } +
                anime.installedExtensionsFlow.value.map { it.pkgName } +
                anime.untrustedExtensionsFlow.value.map { it.pkgName }
            ).distinct()
    }

    /**
     * What this device sends. [otherHas] is the other device's installed packages: sideloaded APKs are only sent for
     * packages it lacks, and none when it didn't say (an older version).
     */
    suspend fun export(selection: SyncSelection, otherHas: Set<String>?): ByteArray {
        val sources = if (selection.syncsSources) {
            val manager = Injekt.get<JsSourceManager>()
            val keys = selection.sourceKeys
            manager.exportSources(keys).map { (info, code) -> SyncSource(info, code) }
        } else {
            emptyList()
        }
        val backup = exportBackup(selection)?.let { Base64.getEncoder().encodeToString(it) }
        val payload = SyncPayload(
            sources = sources,
            backup = backup,
            repos = if (selection.syncsRepos) exportRepos(selection.repoUrls) else emptyList(),
            extensions = if (selection.syncsInstalled) exportExtensions() else emptyList(),
            apks = if (selection.syncsApks && otherHas != null) exportApks(otherHas) else emptyList(),
            installed = installedPackages(),
        )
        val bytes = json.encodeToString(SyncPayload.serializer(), payload).toByteArray()
        return gzip(bytes)
    }

    /** KMK (r89): the manga and anime repos here, as (url, name, anime), for the Repos page and the payload */
    suspend fun repos(): List<Triple<String, String, Boolean>> {
        val manga = runCatching { Injekt.get<ExtensionStoreRepository>().getAll() }.getOrDefault(emptyList())
        val anime = runCatching { Injekt.get<AnimeExtensionStoreRepository>().getAll() }.getOrDefault(emptyList())
        return anime.map { Triple(it.indexUrl, it.name, true) } + manga.map { Triple(it.indexUrl, it.name, false) }
    }

    private suspend fun exportRepos(picked: Set<String>?): List<SyncRepo> =
        repos().filter { picked == null || it.first in picked }.map { (url, name, anime) -> SyncRepo(url, name, anime) }

    private fun exportExtensions(): List<SyncExtension> {
        val manga = Injekt.get<ExtensionManager>().installedExtensionsFlow.value.map {
            SyncExtension(
                pkgName = it.pkgName,
                name = it.name,
                versionCode = it.versionCode,
                lang = it.lang,
                isAnime = false,
                fromRepo = it.store != null,
                repoUrl = it.store?.indexUrl,
                signatureHash = it.signatureHash,
            )
        }
        val anime = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value.map {
            SyncExtension(
                pkgName = it.pkgName,
                name = it.name,
                versionCode = it.versionCode,
                lang = it.lang,
                isAnime = true,
                fromRepo = it.store != null,
                repoUrl = it.store?.indexUrl,
                signatureHash = it.signatureHash,
            )
        }
        return manga + anime
    }

    /** KMK (r89): the installed extensions here that no repo offers (sideloaded), as (pkgName, name, anime) */
    fun sideloaded(): List<Triple<String, String, Boolean>> {
        val manga = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .filter { it.store == null }
            .map { Triple(it.pkgName, it.name, false) }
        val anime = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
            .filter { it.store == null }
            .map { Triple(it.pkgName, it.name, true) }
        return manga + anime
    }

    private fun exportApks(otherHas: Set<String>): List<SyncApk> {
        val context = Injekt.get<Application>()
        val versions = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .associate { it.pkgName to it.versionCode } +
            Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
                .associate { it.pkgName to it.versionCode }
        var total = 0L
        return sideloaded()
            .filter { (pkg) -> pkg !in otherHas }
            .mapNotNull { (pkg, name, anime) ->
                val file = apkFile(context, pkg, anime) ?: return@mapNotNull null
                val size = file.length()
                if (size <= 0L || size > MAX_APK || total + size > MAX_APKS) return@mapNotNull null
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
                total += size
                SyncApk(
                    pkgName = pkg,
                    name = name,
                    isAnime = anime,
                    versionCode = versions[pkg] ?: 0L,
                    apk = Base64.getEncoder().encodeToString(bytes),
                )
            }
    }

    /** The APK of an installed extension: the system's copy, else the app's private copy (see ExtensionLoader) */
    private fun apkFile(context: Context, pkgName: String, anime: Boolean): File? {
        val shared = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(pkgName, 0).sourceDir
        }.getOrNull()?.let(::File)
        if (shared != null && shared.isFile && shared.canRead()) return shared
        // KMK: same folders and file names as ExtensionLoader / AnimeExtensionLoader.getPrivateExtensionDir
        val private = File(context.filesDir, (if (anime) "anime_exts" else "exts") + "/$pkgName.ext")
        return private.takeIf { it.isFile && it.canRead() }
    }

    private suspend fun exportBackup(selection: SyncSelection): ByteArray? {
        val mangaLibrary = selection.mangaLibrary
        val mangaHistory = selection.mangaHistory
        val animeLibrary = selection.animeLibrary
        val animeHistory = selection.animeHistory
        val getCategories = Injekt.get<GetCategories>()
        // Picked categories, by name (an entry in no category counts as Default, "")
        val pickedCategories = selection.mangaCategories
        val pickedAnimeCategories = selection.animeCategories
        if (!mangaLibrary && !mangaHistory && !animeLibrary && !animeHistory) return null

        // Manga
        val getManga = Injekt.get<GetManga>()
        val mangaList = buildMap {
            if (mangaLibrary) {
                Injekt.get<GetFavorites>().await()
                    .filter { manga ->
                        pickedCategories == null ||
                            getCategories.await(manga.id).map { it.name }.ifEmpty { listOf("") }
                                .any { it in pickedCategories }
                    }
                    .forEach { put(it.id, it) }
            }
            if (mangaHistory) {
                Injekt.get<GetHistory>().subscribe("", null, null, null).first()
                    .map { it.mangaId }
                    .distinct()
                    .forEach { id -> if (id !in this) getManga.await(id)?.let { put(id, it) } }
            }
        }.values.filter { it.source != DeviceFilesIds.MANGA }
        val options = BackupOptions(
            libraryEntries = true,
            categories = mangaLibrary,
            chapters = true,
            tracking = mangaLibrary,
            history = mangaHistory,
            readEntries = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            privateSettings = false,
            customInfo = mangaLibrary,
            savedSearchesFeeds = false,
        )
        val backupManga = if (mangaList.isEmpty()) emptyList() else MangaBackupCreator()(mangaList, options)
        if (!mangaLibrary) {
            // History only: the other device's library stays as it is
            backupManga.forEach {
                it.favorite = false
                it.categories = emptyList()
            }
        }

        // Anime
        val animeRepository = Injekt.get<AnimeRepository>()
        // KMK (r89): anime by category name too (an anime in no category counts as Default, "")
        val animeCategoryNames = animeRepository.getCategories().associate { it.id to it.name }
        val animeList = buildMap {
            if (animeLibrary) {
                animeRepository.getFavorites()
                    .filter { anime ->
                        pickedAnimeCategories == null ||
                            animeRepository.getCategoryIds(anime.id).mapNotNull { animeCategoryNames[it] }
                                .ifEmpty { listOf("") }
                                .any { it in pickedAnimeCategories }
                    }
                    .forEach { put(it.id, it) }
            }
            if (animeHistory) {
                animeRepository.subscribeHistory().first()
                    .map { it.animeId }
                    .forEach { id -> if (id !in this) animeRepository.getAnime(id)?.let { put(id, it) } }
            }
        }.values.filter { it.source != DeviceFilesIds.ANIME && it.source != eu.kanade.tachiyomi.jssource.ReaderVideos.SOURCE_ID }
        val helper = AnimeBackupHelper()
        val backupAnime = helper.createAnime(animeList, includeHistory = animeHistory)
            .map { if (animeLibrary) it else it.copy(favorite = false) }

        val backup = Backup(
            backupManga = backupManga,
            backupCategories = if (mangaLibrary) {
                CategoriesBackupCreator()().filter { pickedCategories == null || it.name in pickedCategories }
            } else {
                emptyList()
            },
            backupSources = SourcesBackupCreator()(backupManga),
            backupAnime = backupAnime,
            // KMK (r89): the anime categories weren't sent, so they were made as anime arrived (in no order)
            backupAnimeCategories = if (animeLibrary) {
                helper.createCategories().filter { pickedAnimeCategories == null || it.name in pickedAnimeCategories }
            } else {
                emptyList()
            },
        )
        return gzip(parser.encodeToByteArray(Backup.serializer(), backup))
    }

    /** Reads what a paired device sent */
    fun decode(data: ByteArray): ReceivedPayload =
        ReceivedPayload(json.decodeFromString(SyncPayload.serializer(), String(gunzip(data))))

    /** Merges what a paired device sent; [log] tells the screen how it's going */
    suspend fun import(received: ReceivedPayload, log: (String) -> Unit): SyncSummary {
        val payload = received.payload
        var errors = 0

        val manager = Injekt.get<JsSourceManager>()
        val added = payload.sources.count { source ->
            runCatching { manager.importSource(source.info, source.code) }
                .onFailure { errors++ }
                .getOrDefault(false)
        }
        if (payload.sources.isNotEmpty()) log("Code editor sources: $added new or updated of ${payload.sources.size}")

        var manga = 0
        var anime = 0
        var historyEntries = 0
        val backupBytes = payload.backup
        if (backupBytes != null) {
            val backup = parser.decodeFromByteArray(
                Backup.serializer(),
                gunzip(Base64.getDecoder().decode(backupBytes)),
            )
            historyEntries = backup.backupManga.sumOf { it.history.size } + backup.backupAnime.sumOf { it.history.size }

            if (backup.backupCategories.isNotEmpty()) {
                runCatching { CategoriesRestorer()(backup.backupCategories) }.onFailure { errors++ }
            }
            if (backup.backupManga.isNotEmpty()) log("Merging ${backup.backupManga.size} manga…")
            val restorer = MangaRestorer(isSync = false)
            backup.backupManga.forEach {
                runCatching { restorer.restore(it, backup.backupCategories) }
                    .onSuccess { manga++ }
                    .onFailure { errors++ }
            }
            val helper = AnimeBackupHelper()
            // KMK (r89): the anime categories first, in their order, before the anime that link to them
            if (backup.backupAnimeCategories.isNotEmpty()) {
                runCatching { helper.restoreCategories(backup.backupAnimeCategories) }.onFailure { errors++ }
            }
            if (backup.backupAnime.isNotEmpty()) log("Merging ${backup.backupAnime.size} anime…")
            backup.backupAnime.forEach {
                runCatching { helper.restoreAnime(it) }
                    .onSuccess { anime++ }
                    .onFailure { errors++ }
            }
        }

        val reposAdded = importRepos(payload.repos, log)
        val (missing, updates) = runCatching { offers(payload.extensions, reposAdded > 0) }
            .getOrDefault(emptyList<ExtensionOffer>() to emptyList<ExtensionOffer>())
        val apks = saveApks(payload.apks)

        return SyncSummary(
            sourcesAdded = added,
            manga = manga,
            anime = anime,
            errors = errors,
            historyEntries = historyEntries,
            reposAdded = reposAdded,
            missing = missing,
            updates = updates,
            apks = apks,
        )
    }

    /** KMK (r89): adds the other device's repos missing here (never removes one); the number added */
    private suspend fun importRepos(repos: List<SyncRepo>, log: (String) -> Unit): Int {
        if (repos.isEmpty()) return 0
        val mangaRepository = Injekt.get<ExtensionStoreRepository>()
        val animeRepository = Injekt.get<AnimeExtensionStoreRepository>()
        val mangaHave = runCatching { mangaRepository.getAll() }.getOrDefault(emptyList())
            .map { ExtensionStoreUrls.key(it.indexUrl) }.toMutableSet()
        val animeHave = runCatching { animeRepository.getAll() }.getOrDefault(emptyList())
            .map { ExtensionStoreUrls.key(it.indexUrl) }.toMutableSet()
        var added = 0
        var failed = 0
        var mangaAdded = false
        var animeAdded = false
        repos.forEach { repo ->
            val key = ExtensionStoreUrls.key(repo.url)
            val have = if (repo.anime) animeHave else mangaHave
            if (key in have) return@forEach
            val result = runCatching {
                if (repo.anime) {
                    animeRepository.insert(repo.url).getOrThrow()
                } else {
                    mangaRepository.insert(repo.url).getOrThrow()
                }
            }
            if (result.isSuccess) {
                have += key
                added++
                if (repo.anime) animeAdded = true else mangaAdded = true
            } else {
                failed++
            }
        }
        if (mangaAdded) runCatching { Injekt.get<ExtensionManager>().findAvailableExtensions() }
        if (animeAdded) runCatching { Injekt.get<AnimeExtensionManager>().findAvailableExtensions() }
        if (added > 0 || failed > 0) {
            log("Repos: $added added" + if (failed > 0) ", $failed couldn't be reached" else "")
        }
        return added
    }

    /**
     * KMK (r89): the other device's extensions (from repos) missing here, and the ones installed here in an older
     * version, each only when a repo here offers it.
     */
    private suspend fun offers(
        theirs: List<SyncExtension>,
        reposChanged: Boolean,
    ): Pair<List<ExtensionOffer>, List<ExtensionOffer>> {
        if (theirs.isEmpty()) return emptyList<ExtensionOffer>() to emptyList()
        val mangaManager = Injekt.get<ExtensionManager>()
        val animeManager = Injekt.get<AnimeExtensionManager>()
        // The repos' lists may not have been read yet this run
        if (mangaManager.availableNow().isEmpty() && !reposChanged) {
            runCatching { mangaManager.findAvailableExtensions() }
        }
        if (animeManager.availableNow().isEmpty() && !reposChanged) {
            runCatching { animeManager.findAvailableExtensions() }
        }
        val mangaInstalled = mangaManager.installedExtensionsFlow.value.associate { it.pkgName to it.versionCode } +
            mangaManager.untrustedExtensionsFlow.value.associate { it.pkgName to it.versionCode }
        val animeInstalled = animeManager.installedExtensionsFlow.value.associate { it.pkgName to it.versionCode } +
            animeManager.untrustedExtensionsFlow.value.associate { it.pkgName to it.versionCode }
        val mangaAvailable = mangaManager.availableNow().groupBy { it.pkgName }
        val animeAvailable = animeManager.availableNow().groupBy { it.pkgName }

        val missing = mutableListOf<ExtensionOffer>()
        val updates = mutableListOf<ExtensionOffer>()
        theirs.distinctBy { it.isAnime to it.pkgName }.forEach { ext ->
            val installed = (if (ext.isAnime) animeInstalled else mangaInstalled)[ext.pkgName]
            val available = (if (ext.isAnime) animeAvailable else mangaAvailable)[ext.pkgName].orEmpty()
            if (available.isEmpty()) return@forEach
            val best = available.firstOrNull { it.signatureHash == ext.signatureHash } ?: available.first()
            when {
                installed == null && ext.fromRepo -> missing += ExtensionOffer(
                    pkgName = ext.pkgName,
                    name = ext.name.ifBlank { best.name },
                    isAnime = ext.isAnime,
                    signatureHash = best.signatureHash,
                    fromVersion = 0L,
                    toVersion = best.versionCode,
                )
                installed != null && installed < ext.versionCode &&
                    available.any { it.versionCode > installed } -> updates += ExtensionOffer(
                    pkgName = ext.pkgName,
                    name = ext.name.ifBlank { best.name },
                    isAnime = ext.isAnime,
                    signatureHash = best.signatureHash,
                    fromVersion = installed,
                    toVersion = available.maxOf { it.versionCode },
                )
                else -> Unit
            }
        }
        return missing to updates
    }

    /** KMK (r89): the APKs the other device sent, saved in the cache for Install (not ones installed here by now) */
    private fun saveApks(apks: List<SyncApk>): List<ReceivedApk> {
        if (apks.isEmpty()) return emptyList()
        val context = Injekt.get<Application>()
        val have = installedPackages().toSet()
        val dir = File(context.cacheDir, "pair_sync_apks").apply { mkdirs() }
        return apks.filter { it.pkgName !in have }.mapNotNull { apk ->
            runCatching {
                // The package name is only used as a file name: keep it to safe characters
                val safe = apk.pkgName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }.take(200)
                val file = File(dir, "$safe.apk")
                file.writeBytes(Base64.getDecoder().decode(apk.apk))
                ReceivedApk(apk.pkgName, apk.name.ifBlank { apk.pkgName }, apk.isAnime, file)
            }.getOrNull()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
