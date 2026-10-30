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
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.copyFor
import eu.kanade.tachiyomi.extension.util.InstallOrigins
import eu.kanade.tachiyomi.jssource.JsSourceManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.service.ExtensionStoreUrls
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * KMK: What to sync. Since r91 a device's selection says what it SENDS (r90: what it received): the receiving device
 * accepts or declines each sync instead, and applies everything that came.
 *
 * A null list means all; an empty category set counts as all too ([normalized]: the pop-up turns the tile off
 * instead). All lists are of this device's own things: [mangaCategories] / [animeCategories] are category names
 * ("" is Default), [sourceKeys] Code editor source keys, [repoUrls] repo index URLs, [installedKeys] and [apkKeys]
 * extension package names. The field names are saved as JSON (`pair_sync_selection`), so keep them.
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
    // KMK (r90) -->
    /** The installed extensions (package names) this device tells the other about; null = all */
    val installedKeys: Set<String>? = null,
    /** The sideloaded extensions (package names) whose APKs this device may send; null = all */
    val apkKeys: Set<String>? = null,
    // KMK <--
) {
    val syncsSources: Boolean get() = extensions && sources
    val syncsRepos: Boolean get() = extensions && repos
    val syncsInstalled: Boolean get() = extensions && installedExtensions
    val syncsApks: Boolean get() = extensions && sideloadedApks

    val isEmpty: Boolean
        get() = !mangaLibrary && !animeLibrary && !mangaHistory && !animeHistory &&
            !syncsSources && !syncsRepos && !syncsInstalled && !syncsApks

    /** KMK (r91): every tile and switch on, nothing narrowed (the pop-up's lines then say "everything") */
    val isEverything: Boolean
        get() = normalized() == SyncSelection()

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
        if (syncsRepos) add("repos")
        if (syncsInstalled) add("installed extensions")
        if (syncsApks) add("sideloaded APKs")
        if (syncsSources) add("code sources")
    }.joinToString(", ").ifEmpty { "nothing" }

    /** KMK (r91): an empty category set never means "send none" silently: it counts as all */
    fun normalized(): SyncSelection = copy(
        mangaCategories = mangaCategories?.takeIf { it.isNotEmpty() },
        animeCategories = animeCategories?.takeIf { it.isNotEmpty() },
    )

    /**
     * KMK (r91): only what an r90 device [wants] to receive (its selection meant that): the tiles both have on; the
     * categories stay this device's own
     */
    fun within(wants: SyncSelection): SyncSelection = copy(
        mangaLibrary = mangaLibrary && wants.mangaLibrary,
        animeLibrary = animeLibrary && wants.animeLibrary,
        mangaHistory = mangaHistory && wants.mangaHistory,
        animeHistory = animeHistory && wants.animeHistory,
        extensions = extensions && wants.extensions,
        sources = sources && wants.sources,
        repos = repos && wants.repos,
        installedExtensions = installedExtensions && wants.installedExtensions,
        sideloadedApks = sideloadedApks && wants.sideloadedApks,
    )
}

/** KMK (r91): one line of what a device sends, e.g. ("Anime", "2 of 4 categories · 8 titles") */
@Serializable
data class SummaryRow(val title: String, val detail: String)

/** KMK (r91): one build of a package a repo on the other device offers */
internal class RepoCopy(val signature: String, val version: Long)

/** KMK (r90): an extension installed on this device, for the Extensions pickers */
data class LocalExtension(val pkgName: String, val name: String, val isAnime: Boolean, val fromRepo: Boolean)

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

/** KMK (r89): the APK of an extension not from any repo, in Base64 (only read now, from r89 devices) */
@Serializable
internal class SyncApk(
    val pkgName: String,
    val name: String = "",
    val isAnime: Boolean = false,
    val versionCode: Long = 0L,
    val apk: String,
)

/** KMK (r90): an APK sent as its own raw frame after the payload, in this order */
@Serializable
internal class SyncApkInfo(
    val pkgName: String,
    val name: String = "",
    val isAnime: Boolean = false,
    val versionCode: Long = 0L,
    val size: Long = 0L,
    /** KMK (r91): the installed APK's signer, so the receiver keeps it over a repo build of another signer */
    val signatureHash: String = "",
)

/**
 * Everything one device sends: its Code editor sources, its manga and anime as a backup, and (r89) its repos and
 * extensions. New fields need defaults: older versions leave them out (and skip ones they don't know).
 */
@Serializable
internal class SyncPayload(
    val sources: List<SyncSource> = emptyList(),
    /** A gzipped app backup with the chosen manga and anime, in Base64 (r90 to r90: sent as a raw frame instead) */
    val backup: String? = null,
    // KMK (r89) -->
    val repos: List<SyncRepo> = emptyList(),
    val extensions: List<SyncExtension> = emptyList(),
    val apks: List<SyncApk> = emptyList(),
    /** Every extension package installed on the sender, so the other side sends only APKs it lacks */
    val installed: List<String>? = null,
    // KMK <--
    // KMK (r90) -->
    /**
     * What the sender wants to receive ([SyncSelection] as JSON); null from the device pressing Sync and from r89.
     * KMK (r91): an r91 device sends "everything" here, for an r90 device pressing Sync (it builds its export for it)
     */
    val wants: String? = null,
    /** True when the gzipped backup follows this payload as its own frame */
    val backupFrame: Boolean = false,
    /** The APKs that follow (after the backup frame), one frame each */
    val apkFiles: List<SyncApkInfo> = emptyList(),
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
data class ReceivedApk(
    val pkgName: String,
    val name: String,
    val isAnime: Boolean,
    val file: File,
    // KMK (r91): "" / 0 from an older version
    val signatureHash: String = "",
    val versionCode: Long = 0L,
)

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
    // KMK (r90): what this device sent, so the result card shows both ways -->
    val sentManga: Int = 0,
    val sentAnime: Int = 0,
    val sentHistoryEntries: Int = 0,
    val sentSources: Int = 0,
    val sentRepos: Int = 0,
    val sentExtensions: Int = 0,
    val sentApks: Int = 0,
    // KMK <--
) {
    /** What was received, in words */
    fun receivedText(): String = buildList {
        add(if (sourcesAdded == 1) "1 source" else "$sourcesAdded sources")
        add("$manga manga")
        add("$anime anime")
        if (historyEntries > 0) add("$historyEntries history entries")
        if (reposAdded > 0) add(if (reposAdded == 1) "1 repo" else "$reposAdded repos")
        if (missing.isNotEmpty() || apks.isNotEmpty()) add("${missing.size + apks.size} extensions to install")
        if (updates.isNotEmpty()) add("${updates.size} updates")
        if (errors > 0) add("$errors couldn't be added")
    }.joinToString(", ")

    /** KMK (r90): what was sent, in words */
    fun sentText(): String = buildList {
        add("$sentManga manga")
        add("$sentAnime anime")
        if (sentHistoryEntries > 0) add("$sentHistoryEntries history entries")
        if (sentSources > 0) add(if (sentSources == 1) "1 code source" else "$sentSources code sources")
        if (sentRepos > 0) add(if (sentRepos == 1) "1 repo" else "$sentRepos repos")
        if (sentExtensions > 0) add("$sentExtensions extensions listed")
        if (sentApks > 0) add(if (sentApks == 1) "1 APK" else "$sentApks APKs")
    }.joinToString(", ")

    override fun toString(): String = "got ${receivedText()}; sent ${sentText()}"
}

/** KMK (r89): what a paired device sent, decoded once (the device pressing Sync reads its list of extensions first) */
class ReceivedPayload internal constructor(internal val payload: SyncPayload) {
    /** The other device's installed extension packages, or null for an older version that doesn't say */
    val installed: Set<String>? get() = payload.installed?.toSet()

    /** KMK (r90): what the other device wants to receive, or null (the device pressing Sync, or r89) */
    val wants: SyncSelection? get() = payload.wants?.let { PairSyncPreferences.parseSelection(it) }

    /** KMK (r90): the gzipped backup, when it came as its own frame */
    internal var backupBytes: ByteArray? = null

    /** KMK (r90): the APKs that came as raw frames, saved in the cache */
    internal var receivedApks: List<ReceivedApk> = emptyList()

    /** KMK (r91): the repos were merged already (the device pressing Sync does it before building its export) */
    internal var repoImport: PairSyncData.RepoImport? = null

    /** KMK (r91): what the other device sent, as summary rows (set by the import) */
    internal var sentRows: List<SummaryRow>? = null
}

/** KMK (r90): what this device sends: the payload frame, then the backup frame (if any), then one frame per APK */
internal class SyncExport(
    val payload: ByteArray,
    val backup: ByteArray?,
    val apks: List<Pair<SyncApkInfo, File>>,
    val summary: SyncSummary,
)

/**
 * KMK: Builds what this device sends to a paired one and merges what it gets back. Both ways use the backup
 * restore's merge: entries are added, a chapter or episode read on either device stays read, the further progress
 * and the latest history time are kept, and nothing is taken away (since r90 not even a title's categories here).
 * Device files entries stay on their device (the files are only there). Repos are only added and extensions are only
 * offered (installs ask first). Titles sent only for their history never go into the other device's library.
 */
object PairSyncData {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser: ProtoBuf get() = Injekt.get()

    /** KMK (r90): one APK larger than this isn't sent or accepted */
    internal const val MAX_APK = 32L * 1024 * 1024

    /** KMK (r90): all APKs of one sync together stay under this */
    internal const val MAX_APKS = 128L * 1024 * 1024

    /** KMK (r90): the most the gzipped payload or backup frame can be */
    internal const val MAX_PAYLOAD = 192 * 1024 * 1024

    /** KMK (r90): the most a payload or backup can unpack to (more would run out of memory first on most phones) */
    private const val MAX_UNPACKED = 256L * 1024 * 1024

    private const val APK_DIR = "pair_sync_apks"

    /** KMK (r91): the most an encoded package list can be (the request's first frame is capped at 256 KiB) */
    private const val MAX_PACKAGES_TEXT = 150_000

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
     * KMK (r91): what this device sends: [selection] is this device's own (its tiles and pickers). [otherHas] is the
     * other device's installed packages and [otherRepos] the packages its repos offer: sideloaded APKs are only sent
     * for packages it lacks and can't get from its repos (those are listed instead, so it offers them from its repo).
     * [raw] (the other device is r90 or newer) sends the backup and the APKs as their own frames; an r89 device gets
     * the backup in the JSON and no APKs.
     */
    internal suspend fun export(
        selection: SyncSelection,
        otherHas: Set<String>?,
        otherRepos: Map<String, List<RepoCopy>>?,
        raw: Boolean,
        // KMK (r91): the sideloaded extensions as they were before the other device's repos were merged (null = now)
        sideloadedBefore: List<Triple<String, String, Boolean>>? = null,
    ): SyncExport {
        val own = selection.normalized()
        val sources = if (own.syncsSources) {
            Injekt.get<JsSourceManager>().exportSources(own.sourceKeys).map { (info, code) -> SyncSource(info, code) }
        } else {
            emptyList()
        }
        val backup = exportBackup(own)
        if (backup != null && backup.bytes.size > MAX_PAYLOAD) {
            throw IOException("The library is too big to send at once (${backup.bytes.size / 1024 / 1024} MB)")
        }
        if (own.syncsInstalled || own.syncsApks) ensureAvailable()
        val repos = if (own.syncsRepos) exportRepos(own.repoUrls) else emptyList()
        val listed = if (own.syncsInstalled) exportExtensions(own.installedKeys) else emptyList()
        val sideloadedHere = if (own.syncsApks) sideloadedBefore ?: sideloaded() else emptyList()
        val sideloadedKeys = sideloadedHere.map { it.first }.toSet()
        // KMK (r91): sideloaded here but in the other device's repos: listed, so it's offered from its repo (the
        // sideloaded set from before the merge: merging made such an extension count as from a repo, and it was
        // neither listed nor sent). The other device keeps a sent APK of another signer over that offer.
        val viaRepo = if (own.syncsApks && otherRepos != null) {
            exportExtensions(own.apkKeys).filter {
                it.pkgName in sideloadedKeys && it.pkgName in otherRepos && it.pkgName !in otherHas.orEmpty()
            }
        } else {
            emptyList()
        }
        val extensions = (listed + viaRepo).distinctBy { it.isAnime to it.pkgName }
        val apks = if (raw && own.syncsApks && otherHas != null) {
            exportApks(sideloadedHere, otherHas, otherRepos, own.apkKeys)
        } else {
            emptyList()
        }
        val payload = SyncPayload(
            sources = sources,
            backup = if (raw) null else backup?.bytes?.let { Base64.getEncoder().encodeToString(it) },
            repos = repos,
            extensions = extensions,
            installed = installedPackages(),
            // KMK (r91): "everything", for an r90 device pressing Sync (it builds its export for this); r91 ignores it
            wants = Json.encodeToString(SyncSelection.serializer(), SyncSelection()),
            backupFrame = raw && backup != null,
            apkFiles = apks.map { it.first },
        )
        val bytes = gzip(json.encodeToString(SyncPayload.serializer(), payload).toByteArray())
        if (bytes.size > MAX_PAYLOAD) {
            throw IOException("Too much to send at once (${bytes.size / 1024 / 1024} MB)")
        }
        return SyncExport(
            payload = bytes,
            backup = if (raw) backup?.bytes else null,
            apks = apks,
            summary = SyncSummary(
                sentManga = backup?.manga ?: 0,
                sentAnime = backup?.anime ?: 0,
                sentHistoryEntries = backup?.history ?: 0,
                sentSources = sources.size,
                sentRepos = repos.size,
                sentExtensions = extensions.size,
                sentApks = apks.size,
            ),
        )
    }

    /** KMK (r89): the manga and anime repos here, as (url, name, anime), for the Repos page and the payload */
    suspend fun repos(): List<Triple<String, String, Boolean>> {
        val manga = runCatching { Injekt.get<ExtensionStoreRepository>().getAll() }.getOrDefault(emptyList())
        val anime = runCatching { Injekt.get<AnimeExtensionStoreRepository>().getAll() }.getOrDefault(emptyList())
        return anime.map { Triple(it.indexUrl, it.name, true) } + manga.map { Triple(it.indexUrl, it.name, false) }
    }

    private suspend fun exportRepos(picked: Set<String>?): List<SyncRepo> =
        repos().filter { picked == null || it.first in picked }.map { (url, name, anime) -> SyncRepo(url, name, anime) }

    /** KMK (r90): the repos' lists may not have been read yet this run */
    private suspend fun ensureAvailable() {
        val manga = Injekt.get<ExtensionManager>()
        val anime = Injekt.get<AnimeExtensionManager>()
        if (manga.availableNow().isEmpty()) runCatching { manga.findAvailableExtensions() }
        if (anime.availableNow().isEmpty()) runCatching { anime.findAvailableExtensions() }
        // KMK (r91): tried (also when offline): the APK count no longer waits for them
        repoListsTried = true
    }

    /** KMK (r91): the repos' lists were read (or tried) this run */
    @Volatile private var repoListsTried = false

    /**
     * KMK (r90): every package any repo here offers (manga or anime). An installed extension counts as from a repo
     * when its package is in this list, not only when it was matched to a store: r89 called 12 of 2 sideloaded.
     */
    private fun repoPackages(): Set<String> =
        (Injekt.get<ExtensionManager>().availableNow() + Injekt.get<AnimeExtensionManager>().availableNow())
            .map { it.pkgName }
            .toSet()

    /** KMK (r91): reads the repos' lists when they weren't read yet this run (network; the caller limits the wait) */
    internal suspend fun loadRepoLists() = ensureAvailable()

    /**
     * KMK (r91): whether the repos' lists are read (or there's no repo of that kind), so "sideloaded" is right: before
     * that, every extension counts as sideloaded
     */
    internal suspend fun repoListsLoaded(): Boolean {
        if (repoListsTried) return true
        val manga = Injekt.get<ExtensionManager>().availableNow().isNotEmpty() ||
            runCatching { Injekt.get<ExtensionStoreRepository>().getAll().isEmpty() }.getOrDefault(true)
        val anime = Injekt.get<AnimeExtensionManager>().availableNow().isNotEmpty() ||
            runCatching { Injekt.get<AnimeExtensionStoreRepository>().getAll().isEmpty() }.getOrDefault(true)
        return manga && anime
    }

    /** KMK (r91): every package the repos here offer, as "package:signature:version" lines (what's in memory now) */
    internal fun repoCopies(): Set<String> =
        (Injekt.get<ExtensionManager>().availableNow() + Injekt.get<AnimeExtensionManager>().availableNow())
            .map { "${it.pkgName}:${it.signatureHash}:${it.versionCode}" }
            .toSet()

    /**
     * KMK (r91): a set of [repoCopies] lines, small enough for a Bluetooth request (gzip + Base64, one per line); null
     * when it's too big anyway (then nothing is left out because of it)
     */
    internal fun encodePackages(packages: Set<String>): String? = runCatching {
        Base64.getEncoder().encodeToString(gzip(packages.sorted().joinToString("\n").toByteArray()))
            .takeIf { it.length <= MAX_PACKAGES_TEXT }
    }.getOrNull()

    /**
     * KMK (r91): what [encodePackages] made, by package (a line without a signature has an empty one, so it never
     * matches), or null (none sent, an older version, or bad data)
     */
    internal fun decodePackages(text: String?): Map<String, List<RepoCopy>>? {
        if (text.isNullOrEmpty() || text.length > MAX_PACKAGES_TEXT) return null
        return runCatching {
            String(gunzip(Base64.getDecoder().decode(text))).split('\n')
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split(':')
                    parts[0] to RepoCopy(parts.getOrNull(1).orEmpty(), parts.getOrNull(2)?.toLongOrNull() ?: 0L)
                }
                .groupBy({ it.first }, { it.second })
        }.getOrNull()
    }

    /**
     * KMK (r91): the other device's repos offer this APK's own build ([signature], at least [version]), so it's
     * offered from there instead of sent
     */
    private fun coveredBy(repos: Map<String, List<RepoCopy>>?, pkg: String, signature: String, version: Long) =
        signature.isNotEmpty() &&
            repos?.get(pkg).orEmpty().any { it.signature == signature && it.version >= version }

    /** KMK (r90): the extensions installed here (manga and anime), for the Extensions pickers */
    suspend fun localExtensions(): List<LocalExtension> {
        ensureAvailable()
        val inRepos = repoPackages()
        val manga = Injekt.get<ExtensionManager>().installedExtensionsFlow.value.map {
            LocalExtension(it.pkgName, it.name, isAnime = false, fromRepo = it.store != null || it.pkgName in inRepos)
        }
        val anime = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value.map {
            LocalExtension(it.pkgName, it.name, isAnime = true, fromRepo = it.store != null || it.pkgName in inRepos)
        }
        return (anime + manga).sortedBy { it.name.lowercase() }
    }

    private fun exportExtensions(picked: Set<String>?): List<SyncExtension> {
        val inRepos = repoPackages()
        val manga = Injekt.get<ExtensionManager>().installedExtensionsFlow.value.map {
            SyncExtension(
                pkgName = it.pkgName,
                name = it.name,
                versionCode = it.versionCode,
                lang = it.lang,
                isAnime = false,
                fromRepo = it.store != null || it.pkgName in inRepos,
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
                fromRepo = it.store != null || it.pkgName in inRepos,
                repoUrl = it.store?.indexUrl,
                signatureHash = it.signatureHash,
            )
        }
        return (manga + anime).filter { picked == null || it.pkgName in picked }
    }

    /**
     * KMK (r89): the installed extensions here that no repo offers (sideloaded), as (pkgName, name, anime). r90: a
     * package any repo here lists counts as from a repo.
     */
    fun sideloaded(): List<Triple<String, String, Boolean>> {
        val inRepos = repoPackages()
        val manga = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .filter { it.store == null && it.pkgName !in inRepos }
            .map { Triple(it.pkgName, it.name, false) }
        val anime = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
            .filter { it.store == null && it.pkgName !in inRepos }
            .map { Triple(it.pkgName, it.name, true) }
        return manga + anime
    }

    /** KMK (r90): the sideloaded APKs to send, as files (sent as raw frames, never read into the JSON) */
    private fun exportApks(
        sideloaded: List<Triple<String, String, Boolean>>,
        otherHas: Set<String>,
        otherRepos: Map<String, List<RepoCopy>>?,
        picked: Set<String>?,
    ): List<Pair<SyncApkInfo, File>> {
        val context = Injekt.get<Application>()
        val versions = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .associate { it.pkgName to it.versionCode } +
            Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
                .associate { it.pkgName to it.versionCode }
        val signatures = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .associate { it.pkgName to it.signatureHash } +
            Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
                .associate { it.pkgName to it.signatureHash }
        var total = 0L
        return sideloaded
            // KMK (r91): not when the other device's repos have this same build (signer, and as new): it's offered
            // from there. A build of another signer (a patched one) is sent.
            .filter { (pkg) ->
                pkg !in otherHas && (picked == null || pkg in picked) &&
                    !coveredBy(otherRepos, pkg, signatures[pkg].orEmpty(), versions[pkg] ?: 0L)
            }
            .mapNotNull { (pkg, name, anime) ->
                val file = apkFile(context, pkg, anime) ?: return@mapNotNull null
                val size = file.length()
                if (size <= 0L || size > MAX_APK || total + size > MAX_APKS) return@mapNotNull null
                total += size
                SyncApkInfo(
                    pkgName = pkg,
                    name = name,
                    isAnime = anime,
                    versionCode = versions[pkg] ?: 0L,
                    size = size,
                    signatureHash = signatures[pkg].orEmpty(),
                ) to file
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

    private class BackupExport(val bytes: ByteArray, val manga: Int, val anime: Int, val history: Int)

    private suspend fun exportBackup(selection: SyncSelection): BackupExport? {
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
        val favorites = if (mangaLibrary) {
            Injekt.get<GetFavorites>().await()
                .filter { manga ->
                    pickedCategories == null ||
                        getCategories.await(manga.id).map { it.name }.ifEmpty { listOf("") }
                            .any { it in pickedCategories }
                }
        } else {
            emptyList()
        }
        val favoriteIds = favorites.map { it.id }.toSet()
        // KMK (r90): titles sent only for their history (a favorite outside the picked categories too)
        val historyOnly = if (mangaHistory) {
            Injekt.get<GetHistory>().subscribe("", null, null, null).first()
                .map { it.mangaId }
                .distinct()
                .filter { it !in favoriteIds }
                .mapNotNull { getManga.await(it) }
        } else {
            emptyList()
        }
        val historyOnlyKeys = historyOnly.map { it.source to it.url }.toSet()
        val mangaList = (favorites + historyOnly).filter { it.source != DeviceFilesIds.MANGA }
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
        // History only: the other device's library stays as it is (not in its library, in none of its categories)
        // KMK (r91): only the picked categories go, so a title carries only their orders (the receiver used to drop
        // the others)
        val backupCategories = if (mangaLibrary) {
            CategoriesBackupCreator()().filter { pickedCategories == null || it.name in pickedCategories }
        } else {
            emptyList()
        }
        val pickedOrders = backupCategories.map { it.order }.toSet()
        backupManga.forEach {
            if (!mangaLibrary || (it.source to it.url) in historyOnlyKeys) {
                it.favorite = false
                it.categories = emptyList()
            } else if (pickedCategories != null) {
                it.categories = it.categories.filter { order -> order in pickedOrders }
            }
        }

        // Anime
        val animeRepository = Injekt.get<AnimeRepository>()
        // KMK (r89): anime by category name too (an anime in no category counts as Default, "")
        val animeCategoryNames = animeRepository.getCategories().associate { it.id to it.name }
        val animeFavorites = if (animeLibrary) {
            animeRepository.getFavorites()
                .filter { anime ->
                    pickedAnimeCategories == null ||
                        animeRepository.getCategoryIds(anime.id).mapNotNull { animeCategoryNames[it] }
                            .ifEmpty { listOf("") }
                            .any { it in pickedAnimeCategories }
                }
        } else {
            emptyList()
        }
        val animeFavoriteIds = animeFavorites.map { it.id }.toSet()
        val animeHistoryOnly = if (animeHistory) {
            animeRepository.subscribeHistory().first()
                .map { it.animeId }
                .distinct()
                .filter { it !in animeFavoriteIds }
                .mapNotNull { animeRepository.getAnime(it) }
        } else {
            emptyList()
        }
        val animeHistoryOnlyKeys = animeHistoryOnly.map { it.source to it.url }.toSet()
        val animeList = (animeFavorites + animeHistoryOnly).filter {
            it.source != DeviceFilesIds.ANIME && it.source != eu.kanade.tachiyomi.jssource.ReaderVideos.SOURCE_ID
        }
        val helper = AnimeBackupHelper()
        val backupAnime = helper.createAnime(animeList, includeHistory = animeHistory).map {
            when {
                // KMK (r90): history only: not in the library and no category names (they'd be created and linked)
                !animeLibrary || (it.source to it.url) in animeHistoryOnlyKeys ->
                    it.copy(favorite = false, categories = emptyList())
                // KMK (r90): only the picked categories' names (createAnime puts all of them)
                pickedAnimeCategories != null ->
                    it.copy(categories = it.categories.filter { name -> name in pickedAnimeCategories })
                else -> it
            }
        }

        val backup = Backup(
            backupManga = backupManga,
            backupCategories = backupCategories,
            backupSources = SourcesBackupCreator()(backupManga),
            backupAnime = backupAnime,
            // KMK (r89): the anime categories weren't sent, so they were made as anime arrived (in no order)
            backupAnimeCategories = if (animeLibrary) {
                helper.createCategories().filter { pickedAnimeCategories == null || it.name in pickedAnimeCategories }
            } else {
                emptyList()
            },
        )
        return BackupExport(
            bytes = gzip(parser.encodeToByteArray(Backup.serializer(), backup)),
            manga = backupManga.size,
            anime = backupAnime.size,
            history = backupManga.sumOf { it.history.size } + backupAnime.sumOf { it.history.size },
        )
    }

    /** Reads what a paired device sent */
    fun decode(data: ByteArray): ReceivedPayload =
        ReceivedPayload(json.decodeFromString(SyncPayload.serializer(), String(gunzip(data))))

    /**
     * KMK (r91): the receiver no longer filters (the sender's tiles decide and this device accepted). Only the
     * invariant stays: a title not in the sender's library (history only) never gets a category here.
     */
    private fun cleanManga(backup: Backup): List<BackupManga> = backup.backupManga.onEach {
        if (!it.favorite) it.categories = emptyList()
    }

    /** KMK (r91): the same for anime */
    private fun cleanAnime(backup: Backup): List<BackupAnime> = backup.backupAnime.map {
        if (!it.favorite && it.categories.isNotEmpty()) it.copy(categories = emptyList()) else it
    }

    /**
     * KMK (r91): adds the other device's repos missing here before this device builds what it sends (the device
     * pressing Sync): an extension it has that those repos offer then counts as from a repo, not sideloaded
     */
    internal suspend fun mergeRepos(received: ReceivedPayload, log: (String) -> Unit) {
        if (received.repoImport != null) return
        received.repoImport = runCatching { importRepos(received.payload.repos, log) }.getOrDefault(RepoImport())
    }

    /**
     * Merges what a paired device sent; [log] tells the screen how it's going. KMK (r91): everything that came is
     * applied (this device accepted it; the sender's own tiles chose it).
     */
    suspend fun import(received: ReceivedPayload, log: (String) -> Unit): SyncSummary {
        val payload = received.payload
        var errors = 0

        val manager = Injekt.get<JsSourceManager>()
        val incomingSources = payload.sources
        var added = 0
        incomingSources.forEach { source ->
            currentCoroutineContext().ensureActive()
            runCatching { manager.importSource(source.info, source.code) }
                .onSuccess { if (it) added++ }
                .onFailure { errors++ }
        }
        if (incomingSources.isNotEmpty()) {
            log("Code editor sources: $added new or updated of ${incomingSources.size}")
        }

        var manga = 0
        var anime = 0
        var historyEntries = 0
        // KMK (r91): what came, for the "Get:" line until the next live check
        var mangaTitles = 0
        var mangaCategoryCount = 0
        var animeTitles = 0
        var animeCategoryCount = 0
        var mangaHistoryTitles = 0
        var animeHistoryTitles = 0
        val backupBytes = received.backupBytes ?: payload.backup?.let { Base64.getDecoder().decode(it) }
        if (backupBytes != null) {
            val backup = parser.decodeFromByteArray(Backup.serializer(), gunzip(backupBytes))
            val mangaList = cleanManga(backup)
            val mangaCategories = backup.backupCategories
            val animeList = cleanAnime(backup)
            val animeCategories = backup.backupAnimeCategories
            historyEntries = mangaList.sumOf { it.history.size } + animeList.sumOf { it.history.size }
            mangaTitles = mangaList.count { it.favorite }
            mangaCategoryCount = mangaCategories.size
            animeTitles = animeList.count { it.favorite }
            animeCategoryCount = animeCategories.size
            mangaHistoryTitles = mangaList.count { it.history.isNotEmpty() }
            animeHistoryTitles = animeList.count { it.history.isNotEmpty() }

            if (mangaCategories.isNotEmpty()) {
                runCatching { CategoriesRestorer()(mangaCategories) }.onFailure { errors++ }
            }
            if (mangaList.isNotEmpty()) log("Merging ${mangaList.size} manga…")
            // KMK (r90): categories only added, never taken off a title here
            val restorer = MangaRestorer(isSync = false, addCategoriesOnly = true)
            mangaList.forEach {
                currentCoroutineContext().ensureActive()
                runCatching { restorer.restore(it, mangaCategories) }
                    .onSuccess { manga++ }
                    .onFailure { errors++ }
            }
            val helper = AnimeBackupHelper()
            // KMK (r89): the anime categories first, in their order, before the anime that link to them
            if (animeCategories.isNotEmpty()) {
                runCatching { helper.restoreCategories(animeCategories) }.onFailure { errors++ }
            }
            if (animeList.isNotEmpty()) log("Merging ${animeList.size} anime…")
            animeList.forEach {
                currentCoroutineContext().ensureActive()
                runCatching { helper.restoreAnime(it) }
                    .onSuccess { anime++ }
                    .onFailure { errors++ }
            }
        }

        currentCoroutineContext().ensureActive()
        val repos = received.repoImport ?: runCatching { importRepos(payload.repos, log) }.getOrDefault(RepoImport())
        val (offered, updates) = runCatching { offers(payload.extensions, repos.manga, repos.anime) }
            .getOrDefault(emptyList<ExtensionOffer>() to emptyList<ExtensionOffer>())
        val allApks = received.receivedApks + saveOldApks(payload.apks)
        // KMK (r91): an APK of a package this device's repos offer becomes an offer from the repo (the file goes)
        val repoOffers = runCatching { apksFromRepos(allApks, offered) }.getOrDefault(emptyList())
        val allMissing = offered + repoOffers
        // KMK (r91): a repo offer replaces a sent APK only when it's the same signer's build, as new; an APK of
        // another signer (a patched build) is kept and that offer goes. An older version's APK (no signer) loses to
        // any offer, as before.
        val (apks, dropped) = allApks.partition { apk ->
            val offer = allMissing.firstOrNull { it.isAnime == apk.isAnime && it.pkgName == apk.pkgName }
            offer == null || (
                apk.signatureHash.isNotEmpty() &&
                    (offer.signatureHash != apk.signatureHash || offer.toVersion < apk.versionCode)
                )
        }
        dropped.forEach { runCatching { it.file.delete() } }
        val apkKeys = apks.map { it.isAnime to it.pkgName }.toSet()
        val missing = allMissing.filter { (it.isAnime to it.pkgName) !in apkKeys }

        received.sentRows = buildList {
            if (animeTitles > 0) add(SummaryRow("Anime", libraryDetail(animeCategoryCount, animeTitles)))
            if (mangaTitles > 0) add(SummaryRow("Manga", libraryDetail(mangaCategoryCount, mangaTitles)))
            historyRow(mangaHistoryTitles, animeHistoryTitles)?.let { add(it) }
            extensionsRow(
                repos = payload.repos.size,
                extensions = payload.extensions.size,
                apks = payload.apkFiles.size + payload.apks.size,
                sources = payload.sources.size,
            )?.let { add(it) }
        }

        return SyncSummary(
            sourcesAdded = added,
            manga = manga,
            anime = anime,
            errors = errors,
            historyEntries = historyEntries,
            reposAdded = repos.added,
            missing = missing,
            updates = updates,
            apks = apks,
        )
    }

    /**
     * KMK (r91): the APKs that came for packages this device's repos offer (and not offered already), as offers from
     * the repo
     */
    private suspend fun apksFromRepos(apks: List<ReceivedApk>, offered: List<ExtensionOffer>): List<ExtensionOffer> {
        if (apks.isEmpty()) return emptyList()
        ensureAvailable()
        val mangaAvailable = Injekt.get<ExtensionManager>().availableNow()
        val animeAvailable = Injekt.get<AnimeExtensionManager>().availableNow()
        val have = offered.map { it.isAnime to it.pkgName }.toSet()
        return apks.mapNotNull { apk ->
            if ((apk.isAnime to apk.pkgName) in have) return@mapNotNull null
            // KMK (r91): only the same signer's build, as new (a patched APK isn't swapped for the repo's)
            if (apk.signatureHash.isEmpty()) return@mapNotNull null
            val best = (if (apk.isAnime) animeAvailable else mangaAvailable)
                .filter {
                    it.pkgName == apk.pkgName && it.signatureHash == apk.signatureHash &&
                        it.versionCode >= apk.versionCode
                }
                .maxByOrNull { it.versionCode }
                ?: return@mapNotNull null
            ExtensionOffer(
                pkgName = apk.pkgName,
                name = apk.name.ifBlank { best.name },
                isAnime = apk.isAnime,
                signatureHash = best.signatureHash,
                fromVersion = 0L,
                toVersion = best.versionCode,
            )
        }.distinctBy { it.isAnime to it.pkgName }
    }

    /**
     * KMK (r91): what this device sends with [selection], as summary rows (empty = nothing). Quick: database counts
     * and what's in memory, no network (the repos' lists count only once read). History counts titles.
     */
    suspend fun summarizeSend(selection: SyncSelection): List<SummaryRow> {
        val own = selection.normalized()
        val rows = mutableListOf<SummaryRow>()
        val animeRepository = Injekt.get<AnimeRepository>()
        if (own.animeLibrary) {
            runCatching {
                val categories = animeRepository.getCategories()
                val names = categories.associate { it.id to it.name }
                val links = animeRepository.subscribeCategoryLinks().first()
                val picked = own.animeCategories
                val count = animeRepository.getFavorites().count { anime ->
                    anime.source != DeviceFilesIds.ANIME &&
                        anime.source != eu.kanade.tachiyomi.jssource.ReaderVideos.SOURCE_ID &&
                        (
                            picked == null ||
                                links[anime.id].orEmpty().mapNotNull { names[it] }.ifEmpty { listOf("") }
                                    .any { it in picked }
                            )
                }
                if (count > 0) {
                    val which = categoriesText(picked, categories.map { it.name })
                    rows += SummaryRow("Anime", "$which · ${titles(count)}")
                }
            }
        }
        if (own.mangaLibrary) {
            runCatching {
                val categories = Injekt.get<GetCategories>().await()
                val names = categories.associate { it.id to it.name }
                val picked = own.mangaCategories
                val count = Injekt.get<GetLibraryManga>().await()
                    .distinctBy { it.manga.id }
                    .count { item ->
                        item.manga.source != DeviceFilesIds.MANGA &&
                            (
                                picked == null ||
                                    item.categories.mapNotNull { names[it] }.ifEmpty { listOf("") }
                                        .any { it in picked }
                                )
                    }
                if (count > 0) {
                    val which = categoriesText(picked, categories.map { it.name })
                    rows += SummaryRow("Manga", "$which · ${titles(count)}")
                }
            }
        }
        val mangaHistory = if (own.mangaHistory) {
            runCatching {
                // KMK (r91): not Device files titles, which aren't sent
                Injekt.get<GetHistory>().subscribe("", null, null, null).first()
                    .filter { it.coverData.sourceId != DeviceFilesIds.MANGA }
                    .map { it.mangaId }
                    .distinct()
                    .size
            }.getOrDefault(0)
        } else {
            0
        }
        val animeHistory = if (own.animeHistory) {
            runCatching {
                animeRepository.subscribeHistory().first()
                    .filter {
                        it.source != DeviceFilesIds.ANIME &&
                            it.source != eu.kanade.tachiyomi.jssource.ReaderVideos.SOURCE_ID
                    }
                    .map { it.animeId }
                    .distinct()
                    .size
            }.getOrDefault(0)
        } else {
            0
        }
        historyRow(mangaHistory, animeHistory)?.let { rows += it }
        val repos = if (own.syncsRepos) {
            runCatching { repos().count { own.repoUrls == null || it.first in own.repoUrls } }.getOrDefault(0)
        } else {
            0
        }
        val extensions = if (own.syncsInstalled) {
            runCatching {
                (
                    Injekt.get<ExtensionManager>().installedExtensionsFlow.value.map { it.pkgName } +
                        Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value.map { it.pkgName }
                    ).count { own.installedKeys == null || it in own.installedKeys }
            }.getOrDefault(0)
        } else {
            0
        }
        // KMK (r91): null = not known yet: before the repos' lists are read every extension counts as sideloaded
        val apks: Int? = if (own.syncsApks) {
            if (runCatching { repoListsLoaded() }.getOrDefault(true)) {
                runCatching { sideloaded().count { own.apkKeys == null || it.first in own.apkKeys } }.getOrDefault(0)
            } else {
                null
            }
        } else {
            0
        }
        val sources = if (own.syncsSources) {
            runCatching {
                Injekt.get<JsSourceManager>().savedFlow.value
                    .count { own.sourceKeys == null || it.key in own.sourceKeys }
            }.getOrDefault(0)
        } else {
            0
        }
        extensionsRow(repos, extensions, apks, sources)?.let { rows += it }
        return rows
    }

    /** KMK (r91): the Extensions row's APK part before the repos' lists are read */
    internal const val APKS_CHECKING = "APKs: checking"

    private fun titles(count: Int): String = if (count == 1) "1 title" else "$count titles"

    /** KMK (r91): "All categories", or "2 of 4 categories" (Default counted once, like the pop-up's pages) */
    private fun categoriesText(picked: Set<String>?, all: List<String>): String {
        if (picked == null) return "All categories"
        val options = (listOf("") + all).distinct()
        return "${options.count { it in picked }} of ${options.size} categories"
    }

    /** KMK (r91): a received library line (the sender's category count isn't known, only what came) */
    private fun libraryDetail(categories: Int, count: Int): String = when (categories) {
        0 -> titles(count)
        1 -> "1 category · ${titles(count)}"
        else -> "$categories categories · ${titles(count)}"
    }

    private fun historyRow(manga: Int, anime: Int): SummaryRow? = when {
        manga > 0 && anime > 0 -> SummaryRow("History", "Anime & manga · ${titles(manga + anime)}")
        manga > 0 -> SummaryRow("History", "Manga · ${titles(manga)}")
        anime > 0 -> SummaryRow("History", "Anime · ${titles(anime)}")
        else -> null
    }

    private fun extensionsRow(repos: Int, extensions: Int, apks: Int?, sources: Int): SummaryRow? {
        val parts = buildList {
            if (repos > 0) add(if (repos == 1) "1 repo" else "$repos repos")
            if (extensions > 0) add(if (extensions == 1) "1 extension" else "$extensions extensions")
            when {
                apks == null -> add(APKS_CHECKING)
                apks > 0 -> add(if (apks == 1) "1 APK" else "$apks APKs")
            }
            if (sources > 0) add(if (sources == 1) "1 code source" else "$sources code sources")
        }
        return if (parts.isEmpty()) null else SummaryRow("Extensions", parts.joinToString(" · "))
    }

    /** KMK (r90): repos added, and whether any manga / anime repo was added (each reads its lists again) */
    internal class RepoImport(val added: Int = 0, val manga: Boolean = false, val anime: Boolean = false)

    /** KMK (r89): adds the other device's repos missing here (never removes one) */
    private suspend fun importRepos(repos: List<SyncRepo>, log: (String) -> Unit): RepoImport {
        if (repos.isEmpty()) return RepoImport()
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
        return RepoImport(added, mangaAdded, animeAdded)
    }

    /** KMK (r90): an extension installed (and trusted) here */
    private class Here(val versionCode: Long, val signatureHash: String, val store: ExtensionStore?)

    /**
     * KMK (r89): the other device's extensions (from repos) missing here, and the ones installed here in an older
     * version. r90: an untrusted install here gets neither (installing over it or "updating" it did nothing), and an
     * update is only offered when this device's own repo copy (the one Update installs) is newer.
     */
    private suspend fun offers(
        theirs: List<SyncExtension>,
        mangaReposChanged: Boolean,
        animeReposChanged: Boolean,
    ): Pair<List<ExtensionOffer>, List<ExtensionOffer>> {
        if (theirs.isEmpty()) return emptyList<ExtensionOffer>() to emptyList()
        val mangaManager = Injekt.get<ExtensionManager>()
        val animeManager = Injekt.get<AnimeExtensionManager>()
        // The repos' lists may not have been read yet this run (an added repo already read them again)
        if (mangaManager.availableNow().isEmpty() && !mangaReposChanged) {
            runCatching { mangaManager.findAvailableExtensions() }
        }
        if (animeManager.availableNow().isEmpty() && !animeReposChanged) {
            runCatching { animeManager.findAvailableExtensions() }
        }
        val mangaHere = mangaManager.installedExtensionsFlow.value
            .associate { it.pkgName to Here(it.versionCode, it.signatureHash, it.store) }
        val animeHere = animeManager.installedExtensionsFlow.value
            .associate { it.pkgName to Here(it.versionCode, it.signatureHash, it.store) }
        val mangaUntrusted = mangaManager.untrustedExtensionsFlow.value.map { it.pkgName }.toSet()
        val animeUntrusted = animeManager.untrustedExtensionsFlow.value.map { it.pkgName }.toSet()
        val mangaAvailable = mangaManager.availableNow()
        val animeAvailable = animeManager.availableNow()

        val missing = mutableListOf<ExtensionOffer>()
        val updates = mutableListOf<ExtensionOffer>()
        theirs.distinctBy { it.isAnime to it.pkgName }.forEach { ext ->
            if (ext.pkgName in (if (ext.isAnime) animeUntrusted else mangaUntrusted)) return@forEach
            val allAvailable = if (ext.isAnime) animeAvailable else mangaAvailable
            val available = allAvailable.filter { it.pkgName == ext.pkgName }
            if (available.isEmpty()) return@forEach
            val here = (if (ext.isAnime) animeHere else mangaHere)[ext.pkgName]
            if (here == null) {
                // KMK (r91): offered whenever a repo here has it, also when the other device sideloaded it (a device
                // without repos called every extension sideloaded)
                val best = available.firstOrNull { it.signatureHash == ext.signatureHash } ?: available.first()
                missing += ExtensionOffer(
                    pkgName = ext.pkgName,
                    name = ext.name.ifBlank { best.name },
                    isAnime = ext.isAnime,
                    signatureHash = best.signatureHash,
                    fromVersion = 0L,
                    toVersion = best.versionCode,
                )
            } else if (here.versionCode < ext.versionCode) {
                val copy = allAvailable.copyFor(
                    ext.pkgName,
                    here.signatureHash,
                    here.store,
                    InstallOrigins.repoOf(anime = ext.isAnime, ext.pkgName),
                ) ?: return@forEach
                if (copy.versionCode <= here.versionCode) return@forEach
                updates += ExtensionOffer(
                    pkgName = ext.pkgName,
                    name = ext.name.ifBlank { copy.name },
                    isAnime = ext.isAnime,
                    signatureHash = copy.signatureHash,
                    fromVersion = here.versionCode,
                    toVersion = copy.versionCode,
                )
            }
        }
        return missing to updates
    }

    private fun apkDir(): File = File(Injekt.get<Application>().cacheDir, APK_DIR)

    /**
     * KMK (r90): where an APK the other device sends is saved (the package name kept to safe characters). r91: a new
     * name each time, so removing an older result's file never takes one a running sync just saved
     */
    internal fun apkTarget(pkgName: String): File {
        val dir = apkDir().apply { mkdirs() }
        val safe = pkgName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }.take(200).ifEmpty { "apk" }
        return File(dir, "${safe}_${System.currentTimeMillis()}_${System.nanoTime() % 1_000_000L}.apk")
    }

    /** KMK (r90): saves an APK frame; null when it's installed here by now or couldn't be written */
    internal fun saveApk(info: SyncApkInfo, bytes: ByteArray): ReceivedApk? {
        if (info.pkgName in installedPackages()) return null
        return runCatching {
            val file = apkTarget(info.pkgName)
            file.writeBytes(bytes)
            ReceivedApk(
                pkgName = info.pkgName,
                name = info.name.ifBlank { info.pkgName },
                isAnime = info.isAnime,
                file = file,
                signatureHash = info.signatureHash,
                versionCode = info.versionCode,
            )
        }.getOrNull()
    }

    /**
     * KMK (r90): empties the received APKs folder, except [keep] (offers not tapped yet). r91: and except files saved
     * at or after [keepFrom] (a running sync's)
     */
    internal fun cleanApks(keep: Set<File> = emptySet(), keepFrom: Long = Long.MAX_VALUE) {
        runCatching {
            val keepPaths = keep.map { it.absolutePath }.toSet()
            apkDir().listFiles()?.forEach {
                if (it.absolutePath !in keepPaths && it.lastModified() < keepFrom) it.delete()
            }
        }
    }

    /** KMK (r90): deletes received APKs older than a day */
    fun cleanOldApks() {
        runCatching {
            val before = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            apkDir().listFiles()?.forEach { if (it.lastModified() < before) it.delete() }
        }
    }

    /** KMK (r89): the Base64 APKs an r89 device sent, saved in the cache for Install (r90: same limits as frames) */
    private fun saveOldApks(apks: List<SyncApk>): List<ReceivedApk> {
        if (apks.isEmpty()) return emptyList()
        var total = 0L
        return apks.mapNotNull { apk ->
            val bytes = runCatching { Base64.getDecoder().decode(apk.apk) }.getOrNull() ?: return@mapNotNull null
            if (bytes.size > MAX_APK || total + bytes.size > MAX_APKS) return@mapNotNull null
            total += bytes.size
            saveApk(SyncApkInfo(apk.pkgName, apk.name, apk.isAnime, apk.versionCode, bytes.size.toLong()), bytes)
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** KMK (r90): unpacks at most [MAX_UNPACKED] bytes, so a bad payload can't fill the memory */
    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream(minOf(bytes.size.toLong() * 4, 64L * 1024 * 1024).toInt())
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_UNPACKED) throw IOException("What the other device sent is too big")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
}
