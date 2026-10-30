package eu.kanade.tachiyomi.pairsync

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import coil3.imageLoader
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import eu.kanade.tachiyomi.jssource.ReaderVideos
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** KMK (r92): one title of this device with downloads, as the Downloaded chapters / episodes picker shows it */
data class DownloadedTitle(
    /** What [SyncSelection.mangaDownloadKeys] / [SyncSelection.animeDownloadKeys] hold: "<source id>:<title url>" */
    val key: String,
    val title: String,
    /** Downloaded chapters or episodes */
    val items: Int,
    val bytes: Long,
    val thumbnailUrl: String?,
    // KMK (r92): what "Unread only" (unwatched) and "Last 10" send of it, so the sizes follow the mode; and its cover
    // for the app's cover loader (source headers, the library's copy, a custom cover)
    val unreadItems: Int = items,
    val unreadBytes: Long = bytes,
    val last10Items: Int = items,
    val last10Bytes: Long = bytes,
    val cover: MangaCover? = null,
) {
    /** The chapters / episodes [mode] ("all", "unread", "last10") sends of it */
    fun itemsFor(mode: String): Int = when (mode) {
        "unread" -> unreadItems
        "last10" -> last10Items
        else -> items
    }

    /** Their size */
    fun bytesFor(mode: String): Long = when (mode) {
        "unread" -> unreadBytes
        "last10" -> last10Bytes
        else -> bytes
    }
}

/** KMK (r92): the covers this device has for the titles a selection sends (found on this device, so sendable) */
data class CoversEstimate(val count: Int, val bytes: Long)

/** KMK (r92): one bar of a sync's progress, e.g. ("Covers", "812 of 1,520", 0.53f, false) */
data class TransferPart(val name: String, val detail: String, val fraction: Float, val done: Boolean)

/** KMK (r92): one file of an extra item, with its name in the chapter folder (or the episode's file name) */
@Serializable
internal class ExtraFile(val name: String, val size: Long = 0L)

/**
 * KMK (r92): one thing the extras phase can send: a title's cover ([kind] = [PairSyncExtras.KIND_COVER]), or one
 * downloaded chapter / episode ([item] = its url). Titles are found by [source] + [url] on the other device, and every
 * file goes where that device's own downloads go (never a path from here).
 */
@Serializable
internal class ExtraItem(
    val kind: Int,
    val anime: Boolean = false,
    val source: Long,
    val url: String,
    /** A cover the user set here (custom) */
    val custom: Boolean = false,
    val item: String? = null,
    /** A chapter saved as one .cbz */
    val archive: Boolean = false,
    val files: List<ExtraFile> = emptyList(),
) {
    fun bytes(): Long = files.sumOf { it.size }
}

/** KMK (r92): everything one device would send in the extras phase, in the order it's sent */
@Serializable
internal class ExtrasManifest(val items: List<ExtraItem> = emptyList())

/** KMK (r92): the answer to a manifest: the indexes of the items the other device still needs */
@Serializable
internal class ExtrasNeed(val items: List<Int> = emptyList())

/**
 * KMK (r92): covers and downloaded chapters / episodes for pair sync (only over Wi-Fi or Wi-Fi Direct). The sender
 * finds each title's cover (its custom cover, the library's own copy, else Coil's image cache) and the downloads the
 * user picked; the receiver says what it lacks and writes each file to its own places for its own matching title:
 * library covers into the library's cover copy, other covers into the image cache, downloads where its own
 * downloads go, through a temporary name that's renamed once complete (an interrupted file is dropped and sent again
 * next time). A receiver's custom cover is never replaced.
 */
internal object PairSyncExtras {

    /** The extras protocol's version, sent as "extras" in the request and in "accepted" */
    const val VERSION = 1

    const val KIND_COVER = 0
    const val KIND_CHAPTER = 1
    const val KIND_EPISODE = 2

    /** Files go in frames of at most this many bytes (an episode is never held in memory whole) */
    const val CHUNK = 4 * 1024 * 1024

    /** Kept free on the receiver beyond what comes */
    const val MARGIN = 64L * 1024 * 1024

    /** A cover bigger than this isn't sent */
    private const val MAX_COVER = 16L * 1024 * 1024

    /** How long the covers count is reused */
    private const val CACHE_FOR = 60_000L

    /**
     * KMK (r92): how long the downloads scan is reused (it can take minutes on a big downloads folder); it's read
     * again sooner when files come in a sync, or when the anime downloads change
     */
    private const val DOWNLOADS_FOR = 10L * 60_000L

    /** The most a manifest can unpack to */
    private const val MAX_UNPACKED = 64L * 1024 * 1024

    /** KMK (r92): the most a manifest sent from here unpacks to (the rest waits for the next sync) */
    private const val MAX_MANIFEST = MAX_UNPACKED - 4L * 1024 * 1024

    /** Typical speeds, for the prompt's time */
    const val P2P_SPEED = 20L * 1024 * 1024
    const val LAN_SPEED = 10L * 1024 * 1024

    // Same names as Downloader (TMP_DIR_SUFFIX) and AnimeDownloadManager (TMP), so their clean-ups know them
    private const val CHAPTER_TMP = "_tmp"
    private const val EPISODE_TMP = ".tmp"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app: Application get() = Injekt.get()

    // Selection helpers

    /** The selection sends covers of this kind (only for the titles it sends) */
    private fun SyncSelection.coversOf(anime: Boolean): Boolean = if (anime) {
        animeCovers && (animeLibrary || animeHistory)
    } else {
        mangaCovers && (mangaLibrary || mangaHistory)
    }

    /** KMK (r92): only with the library tile on (the other device only takes downloads of titles it has) */
    private fun SyncSelection.downloadsOf(anime: Boolean): Boolean =
        if (anime) animeDownloads && animeLibrary else mangaDownloads && mangaLibrary

    private fun SyncSelection.keysOf(anime: Boolean): Set<String>? = if (anime) animeDownloadKeys else mangaDownloadKeys

    private fun SyncSelection.modeOf(anime: Boolean): String = if (anime) animeDownloadMode else mangaDownloadMode

    private fun keyOf(source: Long, url: String) = "$source:$url"

    private fun animeSendable(source: Long) = source != DeviceFilesIds.ANIME && source != ReaderVideos.SOURCE_ID

    // Covers (sender)

    /** Where a cover is on this device: a file (custom or the library's copy) or an entry of the image cache */
    private class CoverRef(val custom: Boolean, val file: File?, val cacheKey: String?, val size: Long) {
        fun open(): InputStream {
            if (file != null) return file.inputStream()
            val bytes = cacheKey?.let { readCache(it) } ?: throw IOException("The cover is gone")
            return ByteArrayInputStream(bytes)
        }
    }

    /** A title whose cover may go: what decides whether a selection sends it, and its cover here (null = none) */
    private class CoverCandidate(
        val anime: Boolean,
        val source: Long,
        val url: String,
        val favorite: Boolean,
        val categories: List<String>,
        val inHistory: Boolean,
        val ref: CoverRef?,
    )

    /** The same titles the backup part sends: the library in the picked categories, and titles with history */
    private fun CoverCandidate.sentWith(selection: SyncSelection): Boolean {
        val picked = if (anime) selection.animeCategories else selection.mangaCategories
        val library = if (anime) selection.animeLibrary else selection.mangaLibrary
        val history = if (anime) selection.animeHistory else selection.mangaHistory
        return (library && favorite && (picked == null || categories.any { it in picked })) || (history && inHistory)
    }

    private fun readCache(key: String): ByteArray? {
        val diskCache = app.imageLoader.diskCache ?: return null
        val snapshot = diskCache.openSnapshot(key) ?: return null
        return try {
            diskCache.fileSystem.read(snapshot.data) { readByteArray() }
        } finally {
            snapshot.close()
        }
    }

    /** The first of [keys] the image cache has, with its size */
    private fun cacheRef(keys: List<String>): CoverRef? {
        val diskCache = app.imageLoader.diskCache ?: return null
        for (key in keys) {
            val snapshot = diskCache.openSnapshot(key) ?: continue
            val size = try {
                diskCache.fileSystem.metadataOrNull(snapshot.data)?.size ?: 0L
            } finally {
                snapshot.close()
            }
            if (size > 0L) return CoverRef(custom = false, file = null, cacheKey = key, size = size)
        }
        return null
    }

    private fun fileRef(file: File?, custom: Boolean): CoverRef? {
        if (file == null || !file.isFile) return null
        val size = file.length()
        return if (size > 0L) CoverRef(custom = custom, file = file, cacheKey = null, size = size) else null
    }

    // Same keys as MangaKeyer / MangaCoverKeyer (the image cache is keyed by them)
    private fun mangaCoverRef(coverCache: CoverCache, manga: Manga): CoverRef? {
        fileRef(coverCache.getCustomCoverFile(manga.id), custom = true)?.let { return it }
        fileRef(coverCache.getCoverFile(manga.thumbnailUrl), custom = false)?.let { return it }
        val cover = manga.asMangaCover()
        val keys = listOfNotNull(
            cover.url?.let { "$it;${cover.lastModified}" },
            manga.thumbnailUrl?.let { "$it;${manga.coverLastModified}" },
        ).distinct()
        return cacheRef(keys)
    }

    // Anime covers are keyed by -id (Anime.coverId) and lastModified 0 (Anime.asMangaCover)
    private fun animeCoverRef(coverCache: CoverCache, anime: Anime): CoverRef? {
        fileRef(coverCache.getCustomCoverFile(-anime.id), custom = true)?.let { return it }
        fileRef(coverCache.getCoverFile(anime.thumbnailUrl), custom = false)?.let { return it }
        return cacheRef(listOfNotNull(anime.thumbnailUrl?.let { "$it;0" }))
    }

    private suspend fun mangaCandidates(): List<CoverCandidate> {
        val coverCache = Injekt.get<CoverCache>()
        val names = Injekt.get<GetCategories>().await().associate { it.id to it.name }
        val library = Injekt.get<GetLibraryManga>().await()
            .distinctBy { it.manga.id }
            .filter { it.manga.source != DeviceFilesIds.MANGA && !it.manga.isLocal() }
        val history = runCatching {
            Injekt.get<GetHistory>().subscribe("", null, null, null).first().map { it.mangaId }.toSet()
        }.getOrDefault(emptySet())
        val libraryIds = library.map { it.manga.id }.toSet()
        val result = ArrayList<CoverCandidate>(library.size)
        for (item in library) {
            currentCoroutineContext().ensureActive()
            val manga = item.manga
            result += CoverCandidate(
                anime = false,
                source = manga.source,
                url = manga.url,
                favorite = true,
                categories = item.categories.mapNotNull { names[it] }.ifEmpty { listOf("") },
                inHistory = manga.id in history,
                ref = runCatching { mangaCoverRef(coverCache, manga) }.getOrNull(),
            )
        }
        val getManga = Injekt.get<GetManga>()
        for (id in history) {
            if (id in libraryIds) continue
            currentCoroutineContext().ensureActive()
            val manga = getManga.await(id) ?: continue
            if (manga.source == DeviceFilesIds.MANGA || manga.isLocal()) continue
            result += CoverCandidate(
                anime = false,
                source = manga.source,
                url = manga.url,
                favorite = false,
                categories = emptyList(),
                inHistory = true,
                ref = runCatching { mangaCoverRef(coverCache, manga) }.getOrNull(),
            )
        }
        return result
    }

    private suspend fun animeCandidates(): List<CoverCandidate> {
        val coverCache = Injekt.get<CoverCache>()
        val repository = Injekt.get<AnimeRepository>()
        val names = repository.getCategories().associate { it.id to it.name }
        val links = runCatching { repository.subscribeCategoryLinks().first() }.getOrDefault(emptyMap())
        val favorites = repository.getFavorites().filter { animeSendable(it.source) }
        val history = runCatching {
            repository.subscribeHistory().first().filter { animeSendable(it.source) }.map { it.animeId }.toSet()
        }.getOrDefault(emptySet())
        val favoriteIds = favorites.map { it.id }.toSet()
        val result = ArrayList<CoverCandidate>(favorites.size)
        for (anime in favorites) {
            currentCoroutineContext().ensureActive()
            result += CoverCandidate(
                anime = true,
                source = anime.source,
                url = anime.url,
                favorite = true,
                categories = links[anime.id].orEmpty().mapNotNull { names[it] }.ifEmpty { listOf("") },
                inHistory = anime.id in history,
                ref = runCatching { animeCoverRef(coverCache, anime) }.getOrNull(),
            )
        }
        for (id in history) {
            if (id in favoriteIds) continue
            currentCoroutineContext().ensureActive()
            val anime = repository.getAnime(id) ?: continue
            if (!animeSendable(anime.source)) continue
            result += CoverCandidate(
                anime = true,
                source = anime.source,
                url = anime.url,
                favorite = false,
                categories = emptyList(),
                inHistory = true,
                ref = runCatching { animeCoverRef(coverCache, anime) }.getOrNull(),
            )
        }
        return result
    }

    // Downloads (sender)

    /**
     * One downloaded chapter or episode here. KMK (r92): only what the counts and the manifest need (file names and
     * sizes, and where it is as text), no file handles: those are opened when it's sent ([ItemReader]), so a big
     * downloads folder isn't held in memory
     */
    private class DlItem(
        val url: String,
        val read: Boolean,
        val number: Double,
        val order: Long,
        val archive: Boolean,
        /** The chapter's folder, its .cbz, or the episode's file (a URI) */
        val location: String,
        val names: List<String>,
        val sizes: LongArray,
    ) {
        val bytes: Long = sizes.sum()
    }

    private class DlTitle(
        val key: String,
        val title: String,
        val thumbnailUrl: String?,
        val source: Long,
        val url: String,
        val items: List<DlItem>,
        // KMK (r92): for the picker's cover
        val cover: MangaCover?,
    )

    /** "all", "unread" (or unwatched) or "last10" (the 10 highest numbers) of a title's downloads */
    private fun pick(items: List<DlItem>, mode: String): List<DlItem> = when (mode) {
        "unread" -> items.filter { !it.read }
        "last10" -> items.sortedWith(compareByDescending<DlItem> { it.number }.thenBy { it.order }).take(10)
        else -> items
    }

    /** The library manga with downloads here ([keys]: only those; null = all), with their files */
    private suspend fun scanManga(keys: Set<String>?): List<DlTitle> {
        val provider = Injekt.get<DownloadProvider>()
        val sourceManager = Injekt.get<SourceManager>()
        val getChapters = Injekt.get<GetChaptersByMangaId>()
        val favorites = Injekt.get<GetFavorites>().await()
            .filter { it.source != DeviceFilesIds.MANGA && !it.isLocal() }
            .filter { keys == null || keyOf(it.source, it.url) in keys }
        val result = ArrayList<DlTitle>()
        for ((sourceId, mangas) in favorites.groupBy { it.source }) {
            currentCoroutineContext().ensureActive()
            val source = sourceManager.getOrStub(sourceId)
            // Every folder of the source, listed once (the best folder wins a name)
            val folders = runCatching {
                provider.findSourceDirs(source).asReversed()
                    .flatMap { it.listFiles().orEmpty().toList() }
                    .filter { it.isDirectory && it.name != null }
                    .associateBy { it.name }
            }.getOrDefault(emptyMap())
            if (folders.isEmpty()) continue
            for (manga in mangas) {
                currentCoroutineContext().ensureActive()
                val dir = folders[provider.getMangaDirName(manga.ogTitle)] ?: continue
                val items = runCatching { mangaItems(provider, dir, getChapters.await(manga.id)) }
                    .getOrDefault(emptyList())
                if (items.isEmpty()) continue
                result += DlTitle(
                    key = keyOf(manga.source, manga.url),
                    title = manga.title,
                    thumbnailUrl = manga.thumbnailUrl,
                    source = manga.source,
                    url = manga.url,
                    items = items,
                    cover = runCatching { manga.asMangaCover() }.getOrNull(),
                )
            }
        }
        return result
    }

    private fun mangaItems(provider: DownloadProvider, dir: UniFile, chapters: List<Chapter>): List<DlItem> {
        val entries = dir.listFiles().orEmpty().filter { it.name != null }.associateBy { it.name }
        if (entries.isEmpty()) return emptyList()
        return chapters.mapNotNull { chapter ->
            val found = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                .firstNotNullOfOrNull { entries[it] } ?: return@mapNotNull null
            val name = found.name.orEmpty()
            val files: List<Pair<String, Long>> = when {
                found.isDirectory -> found.listFiles().orEmpty()
                    .filter { it.isFile }
                    .mapNotNull { file -> file.name?.let { it to file.length() } }
                name.endsWith(".cbz", ignoreCase = true) -> listOf(name to found.length())
                else -> emptyList()
            }
            if (files.isEmpty()) return@mapNotNull null
            DlItem(
                url = chapter.url,
                read = chapter.read,
                number = chapter.chapterNumber,
                order = chapter.sourceOrder,
                archive = !found.isDirectory,
                location = found.uri.toString(),
                names = files.map { it.first },
                sizes = files.map { it.second }.toLongArray(),
            )
        }
    }

    /** The library anime with downloaded episodes here ([keys]: only those; null = all) */
    private suspend fun scanAnime(keys: Set<String>?): List<DlTitle> {
        val manager = Injekt.get<AnimeDownloadManager>()
        val repository = Injekt.get<AnimeRepository>()
        val favorites = repository.getFavorites()
            .filter { animeSendable(it.source) }
            .filter { keys == null || keyOf(it.source, it.url) in keys }
        val result = ArrayList<DlTitle>()
        for (anime in favorites) {
            currentCoroutineContext().ensureActive()
            val items = runCatching { animeItems(manager, anime, repository.getEpisodes(anime.id)) }
                .getOrDefault(emptyList())
            if (items.isEmpty()) continue
            result += DlTitle(
                key = keyOf(anime.source, anime.url),
                title = anime.title,
                thumbnailUrl = anime.thumbnailUrl,
                source = anime.source,
                url = anime.url,
                items = items,
                // Keyed like Anime.asMangaCover (id -id, lastModified 0), so the cover loader finds its copies
                cover = runCatching {
                    MangaCover(
                        mangaId = -anime.id,
                        sourceId = anime.source,
                        isMangaFavorite = anime.favorite,
                        ogUrl = anime.thumbnailUrl,
                        lastModified = 0L,
                    )
                }.getOrNull(),
            )
        }
        return result
    }

    private fun animeItems(manager: AnimeDownloadManager, anime: Anime, episodes: List<Episode>): List<DlItem> {
        // KMK (r90 rules): the manager finds the folder (case twins too) and says which episodes are downloaded
        val dir = manager.animeDir(anime, create = false) ?: return emptyList()
        val videos = dir.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name?.let { AnimeDownloadManager.isEpisodeVideo(it) } == true }
            .associateBy { it.name.orEmpty().substringBeforeLast('.') }
        if (videos.isEmpty()) return emptyList()
        return episodes.mapNotNull { episode ->
            if (!manager.isDownloaded(anime, episode)) return@mapNotNull null
            val file = videos[episodeBaseName(episode)] ?: manager.findEpisodeFile(anime, episode)
                ?: return@mapNotNull null
            val name = file.name ?: return@mapNotNull null
            DlItem(
                url = episode.url,
                read = episode.seen,
                number = episode.episodeNumber,
                order = episode.sourceOrder,
                archive = false,
                location = file.uri.toString(),
                names = listOf(name),
                sizes = longArrayOf(file.length()),
            )
        }
    }

    /** The same name as AnimeDownloadManager.episodeBaseName (r87): `scanlator_name_E<number>` or `_U<hash>` */
    private fun episodeBaseName(episode: Episode): String {
        val name = listOfNotNull(episode.scanlator?.takeIf { it.isNotBlank() }, episode.name).joinToString("_")
        val number = episode.episodeNumber
        val tag = if (number >= 0) {
            "E" + if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
        } else {
            "U" + episode.url.hashCode().toUInt().toString(16)
        }
        return DiskUtil.buildValidFilename(name, maxBytes = 200) + "_" + tag
    }

    // Counts for the pop-up, the summary and the request (read in the background and kept; the sync reuses them)

    /** KMK (r92): a count, when it was read and what [Cached]'s stamp said then */
    private class CacheEntry<V>(val at: Long, val stamp: Any?, val value: V)

    /**
     * One count per kind (manga = false, anime = true): reused for [keepFor] ms while [stamp] stays the same, one read
     * at a time (a read still running is shared)
     */
    private class Cached<T>(
        private val keepFor: Long,
        private val stamp: (Boolean) -> Any? = { null },
        private val compute: suspend (Boolean) -> T,
    ) {
        private val done = HashMap<Boolean, CacheEntry<T>>()
        private val running = HashMap<Boolean, Deferred<T>>()

        /** Changes on [clear], so a read that started before doesn't store what it found */
        private var generation = 0

        private fun fresh(anime: Boolean): CacheEntry<T>? {
            val entry = done[anime] ?: return null
            val age = System.currentTimeMillis() - entry.at
            return entry.takeIf { age in 0L until keepFor && it.stamp == stamp(anime) }
        }

        /** The count when it's fresh, without waiting; null otherwise */
        fun peek(anime: Boolean): T? = synchronized(this) { fresh(anime)?.value }

        fun get(anime: Boolean): Deferred<T> {
            synchronized(this) {
                val fresh = fresh(anime)
                if (fresh != null) return CompletableDeferred(fresh.value)
                val current = running[anime]
                if (current != null && current.isActive) return current
                val before = stamp(anime)
                val started = generation
                val job = scope.async {
                    val value = compute(anime)
                    synchronized(this@Cached) {
                        if (generation == started) done[anime] = CacheEntry(System.currentTimeMillis(), before, value)
                    }
                    value
                }
                running[anime] = job
                return job
            }
        }

        fun clear() {
            synchronized(this) {
                generation++
                done.clear()
                running.clear()
            }
        }
    }

    private val covers = Cached<List<CoverCandidate>>(CACHE_FOR) { anime ->
        if (anime) animeCandidates() else mangaCandidates()
    }

    // KMK (r92): kept longer (see DOWNLOADS_FOR); the anime downloads say when they change
    private val downloadScans = Cached<List<DlTitle>>(
        keepFor = DOWNLOADS_FOR,
        stamp = { anime ->
            if (anime) runCatching { Injekt.get<AnimeDownloadManager>().changes.value }.getOrNull() else null
        },
    ) { anime -> if (anime) scanAnime(null) else scanManga(null) }

    /**
     * A sync just changed the library here: the covers are counted again next time. KMK (r92): the downloads scan
     * stays (only files coming in change it, see [afterReceive])
     */
    fun forget() {
        covers.clear()
    }

    suspend fun downloadedTitles(anime: Boolean): List<DownloadedTitle> = titlesOf(downloadScans.get(anime).await())

    /** KMK (r92): [downloadedTitles] when they're counted already (the picker shows them at once), else null */
    fun downloadedTitlesNow(anime: Boolean): List<DownloadedTitle>? = downloadScans.peek(anime)?.let { titlesOf(it) }

    private fun titlesOf(scan: List<DlTitle>): List<DownloadedTitle> = scan
        .map { title ->
            val unread = pick(title.items, "unread")
            val last10 = pick(title.items, "last10")
            DownloadedTitle(
                key = title.key,
                title = title.title,
                items = title.items.size,
                bytes = title.items.sumOf { it.bytes },
                thumbnailUrl = title.thumbnailUrl,
                unreadItems = unread.size,
                unreadBytes = unread.sumOf { it.bytes },
                last10Items = last10.size,
                last10Bytes = last10.sumOf { it.bytes },
                cover = title.cover,
            )
        }
        .sortedByDescending { it.bytes }

    /** The covers found here for the titles [selection] sends of this kind (whether its covers switch is on or not) */
    suspend fun coversEstimate(anime: Boolean, selection: SyncSelection): CoversEstimate {
        val own = selection.normalized()
        val found = covers.get(anime).await().mapNotNull { candidate ->
            candidate.ref?.takeIf { candidate.sentWith(own) && it.size <= MAX_COVER }
        }
        return CoversEstimate(found.size, found.sumOf { it.size })
    }

    /** The downloads [selection] sends of this kind: (titles, chapters or episodes, bytes) */
    suspend fun downloadsEstimate(anime: Boolean, selection: SyncSelection): Triple<Int, Int, Long> {
        if (!selection.downloadsOf(anime)) return Triple(0, 0, 0L)
        val keys = selection.keysOf(anime)
        val mode = selection.modeOf(anime)
        val picked = downloadScans.get(anime).await()
            .filter { keys == null || it.key in keys }
            .map { pick(it.items, mode) }
            .filter { it.isNotEmpty() }
        return Triple(picked.size, picked.sumOf { it.size }, picked.sumOf { items -> items.sumOf { it.bytes } })
    }

    /** What [selection] would send in all (covers + downloads), and the downloads' part of it */
    suspend fun estimate(selection: SyncSelection): Pair<Long, Long> {
        val own = selection.normalized()
        var total = 0L
        var downloadBytes = 0L
        for (anime in listOf(false, true)) {
            if (own.coversOf(anime)) total += coversEstimate(anime, own).bytes
            val bytes = downloadsEstimate(anime, own).third
            total += bytes
            downloadBytes += bytes
        }
        return total to downloadBytes
    }

    // Free space (receiver)

    /**
     * The free space here for what comes: the app's storage (covers) and its cache (the image cache), and with
     * [downloads] the downloads folder; the smallest known one, null when none is known
     */
    fun freeBytes(downloads: Boolean): Long? {
        val values = buildList {
            add(DiskUtil.getAvailableStorageSpace(app.getExternalFilesDir(null) ?: app.filesDir))
            add(DiskUtil.getAvailableStorageSpace(app.cacheDir))
            if (downloads) add(downloadsFree())
        }.filter { it >= 0L }
        return values.minOrNull()
    }

    /** The downloads folder's free space; a picked folder (content://) on the phone's own storage is measured there */
    private fun downloadsFree(): Long {
        val dir = runCatching { Injekt.get<StorageManager>().getDownloadsDirectory() }.getOrNull() ?: return -1L
        val direct = DiskUtil.getAvailableStorageSpace(dir)
        if (direct >= 0L) return direct
        val uri = dir.uri
        if (uri.scheme != "content") return -1L
        val document = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (document?.startsWith("primary:") != true) return -1L
        @Suppress("DEPRECATION")
        return DiskUtil.getAvailableStorageSpace(Environment.getExternalStorageDirectory())
    }

    // The manifest (sender)

    /**
     * KMK (r92): how one item's files are read when it's sent; a chapter folder is listed only then (one handle per
     * item, not one per page file)
     */
    abstract class ItemReader {
        /** The item's file [name], or null when it's gone */
        abstract fun open(name: String): InputStream?

        /** The item was sent: what was listed for it is let go */
        open fun release() = Unit
    }

    private class CoverReader(private val ref: CoverRef) : ItemReader() {
        override fun open(name: String): InputStream? = ref.open()
    }

    /** A .cbz or an episode: the file itself */
    private class FileReader(private val location: String) : ItemReader() {
        override fun open(name: String): InputStream? = UniFile.fromUri(app, Uri.parse(location))?.openInputStream()
    }

    /** A chapter folder: listed on its first file */
    private class FolderReader(private val location: String) : ItemReader() {
        private var files: Map<String, UniFile>? = null

        override fun open(name: String): InputStream? {
            val listed = files ?: UniFile.fromUri(app, Uri.parse(location))?.listFiles().orEmpty()
                .filter { it.name != null }
                .associateBy { it.name.orEmpty() }
                .also { files = it }
            return listed[name]?.openInputStream()
        }

        override fun release() {
            files = null
        }
    }

    /** What this device sends: the manifest, and how each item's files are read (in the same order) */
    class Outgoing(val manifest: ExtrasManifest, val readers: List<ItemReader>)

    /**
     * Covers first, then manga downloads, then anime downloads. KMK (r92): from the counts when they're fresh (or
     * being read), so a scan that's still running isn't started a second time
     */
    suspend fun outgoing(selection: SyncSelection): Outgoing {
        val own = selection.normalized()
        val items = ArrayList<ExtraItem>()
        val readers = ArrayList<ItemReader>()
        for (anime in listOf(false, true)) {
            if (!own.coversOf(anime)) continue
            val candidates = runCatching { covers.get(anime).await() }.getOrDefault(emptyList())
            for (candidate in candidates) {
                val ref = candidate.ref ?: continue
                if (ref.size > MAX_COVER || !candidate.sentWith(own)) continue
                items += ExtraItem(
                    kind = KIND_COVER,
                    anime = anime,
                    source = candidate.source,
                    url = candidate.url,
                    custom = ref.custom,
                    files = listOf(ExtraFile("cover", ref.size)),
                )
                readers.add(CoverReader(ref))
            }
        }
        for (anime in listOf(false, true)) {
            if (!own.downloadsOf(anime)) continue
            val keys = own.keysOf(anime)
            val titles = runCatching { downloadScans.get(anime).await() }.getOrDefault(emptyList())
                .filter { keys == null || it.key in keys }
            for (title in titles) {
                currentCoroutineContext().ensureActive()
                for (item in pick(title.items, own.modeOf(anime))) {
                    items += ExtraItem(
                        kind = if (anime) KIND_EPISODE else KIND_CHAPTER,
                        anime = anime,
                        source = title.source,
                        url = title.url,
                        item = item.url,
                        archive = item.archive,
                        files = item.names.mapIndexed { index, name -> ExtraFile(name, item.sizes[index]) },
                    )
                    readers.add(
                        if (anime || item.archive) FileReader(item.location) else FolderReader(item.location),
                    )
                }
            }
        }
        return Outgoing(ExtrasManifest(items), readers)
    }

    /** KMK (r92): a manifest ready to send, with the first [items] of the outgoing ones (the rest wait) */
    class EncodedManifest(val bytes: ByteArray, val items: Int)

    /**
     * KMK (r92): the manifest, cut to what the other device unpacks ([MAX_MANIFEST]); a huge "all downloads" sends
     * its first part now and the rest next sync, instead of failing the whole phase
     */
    fun encodeManifest(out: Outgoing): EncodedManifest {
        val all = out.manifest.items
        // A rough size first, so no huge text is built only to be cut
        var count = all.size
        var approx = 0L
        for ((index, item) in all.withIndex()) {
            approx += 96L + item.url.length + (item.item?.length ?: 0) + item.files.sumOf { 32L + it.name.length }
            if (approx > MAX_MANIFEST) {
                count = index
                break
            }
        }
        var raw = manifestJson(all, count)
        while (raw.size > MAX_MANIFEST && count > 0) {
            count = (count * (MAX_MANIFEST.toDouble() / raw.size) * 0.9).toInt()
            raw = manifestJson(all, count)
        }
        return EncodedManifest(gzip(raw), count)
    }

    private fun manifestJson(items: List<ExtraItem>, count: Int): ByteArray =
        json.encodeToString(ExtrasManifest.serializer(), ExtrasManifest(items.subList(0, count))).toByteArray()

    fun decodeManifest(bytes: ByteArray): ExtrasManifest =
        json.decodeFromString(ExtrasManifest.serializer(), String(gunzip(bytes)))

    fun encodeNeed(items: List<Int>): ByteArray =
        gzip(json.encodeToString(ExtrasNeed.serializer(), ExtrasNeed(items)).toByteArray())

    /** The indexes the other device asked for, checked against the manifest's [size], in order */
    fun decodeNeed(bytes: ByteArray, size: Int): List<Int> =
        json.decodeFromString(ExtrasNeed.serializer(), String(gunzip(bytes))).items
            .filter { it in 0 until size }
            .distinct()
            .sorted()

    // The plan (receiver)

    /** Where one needed item goes here */
    sealed class Target {
        class CoverFileTarget(val file: File) : Target()
        class CoverCacheTarget(val key: String) : Target()
        class ChapterTarget(val manga: Manga, val chapter: Chapter, val source: Source) : Target()
        class EpisodeTarget(val anime: Anime, val episode: Episode) : Target()
    }

    /** What this device needs of the other's manifest ([need], in order) and where each goes */
    class Plan(val need: List<Int>, val targets: Map<Int, Target>, val noRoom: Int = 0) {
        /** Anime whose folders got files (read again at the end) */
        internal val touchedAnime = HashMap<Long, Anime>()

        /** KMK (r92): each manga's downloads folder here (found once per manga) and the names in it */
        internal val mangaDirs = HashMap<Long, UniFile>()
        internal val dirNames = HashMap<Long, MutableSet<String>>()
    }

    /**
     * The items of [manifest] this device lacks: its own title (by source + url) must be here, a cover only when it
     * has none (never over a custom cover), a chapter / episode only when its source is installed here and it isn't
     * downloaded yet. Cut to the free space ([Plan.noRoom]: how many didn't fit).
     */
    suspend fun plan(manifest: ExtrasManifest): Plan {
        // KMK (r92): the merge just added titles: the anime folders' letter-case check reads the names again
        if (manifest.items.any { it.kind == KIND_EPISODE }) {
            runCatching { Injekt.get<AnimeDownloadManager>().forgetLibraryNames() }
        }
        val lookup = Lookup()
        val downloads = manifest.items.any { it.kind != KIND_COVER }
        var budget = freeBytes(downloads)?.let { it - MARGIN }
        val need = ArrayList<Int>()
        val targets = HashMap<Int, Target>()
        var noRoom = 0
        for ((index, item) in manifest.items.withIndex()) {
            currentCoroutineContext().ensureActive()
            val target = runCatching { lookup.targetFor(item) }.getOrNull() ?: continue
            val size = item.bytes()
            if (budget != null) {
                if (size > budget) {
                    noRoom++
                    continue
                }
                budget -= size
            }
            need += index
            targets[index] = target
        }
        return Plan(need, targets, noRoom).also { plan ->
            plan.mangaDirs.putAll(lookup.mangaDirs)
            lookup.folderNames.forEach { (id, names) -> plan.dirNames[id] = names.toMutableSet() }
        }
    }

    /** This device's rows, read once per title */
    private class Lookup {
        val coverCache = Injekt.get<CoverCache>()
        val getManga = Injekt.get<GetManga>()
        val getChapters = Injekt.get<GetChaptersByMangaId>()
        val repository = Injekt.get<AnimeRepository>()
        val sourceManager = Injekt.get<SourceManager>()
        val animeSources = Injekt.get<AnimeSourceManager>()
        val provider = Injekt.get<DownloadProvider>()
        val animeManager = Injekt.get<AnimeDownloadManager>()
        val mangaRows = HashMap<String, Manga?>()
        val animeRows = HashMap<String, Anime?>()
        val chapters = HashMap<Long, Map<String, Chapter>>()
        val episodes = HashMap<Long, Map<String, Episode>>()
        val folderNames = HashMap<Long, Set<String>>()

        // KMK (r92): the folders found, for the writers (no second search per chapter)
        val mangaDirs = HashMap<Long, UniFile>()

        suspend fun manga(item: ExtraItem): Manga? {
            val key = keyOf(item.source, item.url)
            if (key in mangaRows) return mangaRows[key]
            val row = getManga.await(item.url, item.source)
            mangaRows[key] = row
            return row
        }

        suspend fun anime(item: ExtraItem): Anime? {
            val key = keyOf(item.source, item.url)
            if (key in animeRows) return animeRows[key]
            val row = repository.getAnimeByUrl(item.url, item.source)
            animeRows[key] = row
            return row
        }

        suspend fun targetFor(item: ExtraItem): Target? = when (item.kind) {
            KIND_COVER -> coverTarget(item)
            KIND_CHAPTER -> chapterTarget(item)
            KIND_EPISODE -> episodeTarget(item)
            else -> null
        }

        private suspend fun coverTarget(item: ExtraItem): Target? {
            if (item.files.size != 1 || item.bytes() > MAX_COVER) return null
            val id: Long
            val favorite: Boolean
            val thumbnailUrl: String?
            val cacheKey: String?
            if (item.anime) {
                val anime = anime(item) ?: return null
                id = -anime.id
                favorite = anime.favorite
                thumbnailUrl = anime.thumbnailUrl
                cacheKey = anime.thumbnailUrl?.let { "$it;0" }
            } else {
                val manga = manga(item) ?: return null
                if (manga.isLocal()) return null
                id = manga.id
                favorite = manga.favorite
                thumbnailUrl = manga.thumbnailUrl
                cacheKey = manga.asMangaCover().let { cover -> cover.url?.let { "$it;${cover.lastModified}" } }
            }
            // Never over (or under) a custom cover set here
            val custom = coverCache.getCustomCoverFile(id)
            if (custom.exists()) return null
            if (favorite) {
                if (item.custom) return Target.CoverFileTarget(custom)
                val file = coverCache.getCoverFile(thumbnailUrl) ?: return null
                return if (file.exists() && file.length() > 0L) null else Target.CoverFileTarget(file)
            }
            val key = cacheKey ?: return null
            val diskCache = app.imageLoader.diskCache ?: return null
            val snapshot = diskCache.openSnapshot(key)
            if (snapshot != null) {
                snapshot.close()
                return null
            }
            return Target.CoverCacheTarget(key)
        }

        private suspend fun chapterTarget(item: ExtraItem): Target? {
            val url = item.item ?: return null
            if (item.files.isEmpty() || (item.archive && item.files.size != 1)) return null
            val manga = manga(item) ?: return null
            if (manga.isLocal()) return null
            // Only into an installed source's folder (the folder name comes from the source)
            val source = sourceManager.get(manga.source) ?: return null
            val chapter = chapters.getOrPut(manga.id) { getChapters.await(manga.id).associateBy { it.url } }[url]
                ?: return null
            val names = folderNames.getOrPut(manga.id) {
                val dir = provider.findMangaDir(manga.ogTitle, source)
                if (dir != null) mangaDirs[manga.id] = dir
                dir?.listFiles().orEmpty().mapNotNull { it.name }.toSet()
            }
            if (provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).any { it in names }) {
                return null
            }
            return Target.ChapterTarget(manga, chapter, source)
        }

        private suspend fun episodeTarget(item: ExtraItem): Target? {
            val url = item.item ?: return null
            if (item.files.size != 1) return null
            val anime = anime(item) ?: return null
            if (animeSources.get(anime.source) == null) return null
            val episode = episodes.getOrPut(anime.id) { repository.getEpisodes(anime.id).associateBy { it.url } }[url]
                ?: return null
            if (animeManager.isDownloaded(anime, episode)) return null
            return Target.EpisodeTarget(anime, episode)
        }
    }

    // Writing (receiver)

    /** One needed item being received: where each file goes, then saved (or dropped) */
    abstract class Writer {
        /** Where file [index] ([name] on the other device) is written; null = its data is read and dropped */
        abstract fun output(index: Int, name: String): OutputStream?

        /** Every file came ([ok]) or not: saved under its real name, or dropped. True when saved. */
        abstract suspend fun finish(ok: Boolean): Boolean

        /** The sync stopped in the middle of it: what was written goes */
        abstract fun abort()
    }

    suspend fun writer(plan: Plan, index: Int, item: ExtraItem): Writer? = when (val target = plan.targets[index]) {
        is Target.CoverFileTarget -> CoverWriter(target.file, null)
        is Target.CoverCacheTarget -> CoverWriter(null, target.key)
        is Target.ChapterTarget -> chapterWriter(plan, target, item.archive)
        is Target.EpisodeTarget -> episodeWriter(plan, target, item)
        null -> null
    }

    /**
     * The anime that got episodes are read again (their ✓ and counts). KMK (r92): and the counts, the downloads scan
     * only when downloads came
     */
    fun afterReceive(plan: Plan) {
        val manager = Injekt.get<AnimeDownloadManager>()
        plan.touchedAnime.values.forEach { runCatching { manager.refresh(it) } }
        covers.clear()
        if (plan.targets.values.any { it is Target.ChapterTarget || it is Target.EpisodeTarget }) downloadScans.clear()
    }

    /** A cover: kept in memory (small), then written in one go */
    private class CoverWriter(private val file: File?, private val key: String?) : Writer() {
        private val bytes = ByteArrayOutputStream()

        override fun output(index: Int, name: String): OutputStream? = if (index == 0) bytes else null

        override suspend fun finish(ok: Boolean): Boolean {
            if (!ok || bytes.size() == 0) return false
            val data = bytes.toByteArray()
            return runCatching {
                if (file != null) writeCoverFile(file, data) else key != null && writeCache(key, data)
            }.getOrDefault(false)
        }

        override fun abort() = Unit
    }

    private fun writeCoverFile(file: File, data: ByteArray): Boolean {
        // An empty leftover is replaced; a real cover (or a custom one set meanwhile) never
        if (file.exists() && file.length() > 0L) return false
        file.delete()
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(data)
        if (part.renameTo(file)) return true
        part.delete()
        return false
    }

    private fun writeCache(key: String, data: ByteArray): Boolean {
        val diskCache = app.imageLoader.diskCache ?: return false
        val editor = diskCache.openEditor(key) ?: return false
        return try {
            diskCache.fileSystem.write(editor.data) { write(data) }
            editor.commit()
            true
        } catch (_: Exception) {
            runCatching { editor.abort() }
            false
        }
    }

    /** A file name from the other device, without any folder part */
    private fun safeName(name: String): String? =
        name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() && it != "." && it != ".." }

    /** A chapter: into "<name>_tmp" (a folder, or "<name>.cbz_tmp"), renamed when complete, like the downloader */
    private fun chapterWriter(plan: Plan, target: Target.ChapterTarget, archive: Boolean): Writer? {
        val provider = Injekt.get<DownloadProvider>()
        // KMK (r92): the manga's folder and its names are found once per manga (the plan's), not per chapter
        val id = target.manga.id
        val dir = plan.mangaDirs[id]
            ?: provider.getMangaDir(target.manga.ogTitle, target.source).getOrNull()?.also { plan.mangaDirs[id] = it }
            ?: return null
        val names = plan.dirNames.getOrPut(id) { HashSet() }
        val dirName = provider.getChapterDirName(target.chapter.name, target.chapter.scanlator, target.chapter.url)
        val finalName = if (archive) "$dirName.cbz" else dirName
        val tmpName = finalName + CHAPTER_TMP
        if (names.remove(tmpName)) dir.findFile(tmpName)?.delete()
        val tmp = (if (archive) dir.createFile(tmpName) else dir.createDirectory(tmpName)) ?: return null
        return object : Writer() {
            override fun output(index: Int, name: String): OutputStream? {
                if (archive) return if (index == 0) tmp.openOutputStream() else null
                val file = safeName(name)?.let { tmp.createFile(it) } ?: return null
                return file.openOutputStream()
            }

            override suspend fun finish(ok: Boolean): Boolean {
                if (!ok || finalName in names || !tmp.renameTo(finalName)) {
                    tmp.delete()
                    return false
                }
                names.add(finalName)
                runCatching { Injekt.get<DownloadCache>().addChapter(dirName, dir, target.manga) }
                return true
            }

            override fun abort() {
                runCatching { tmp.delete() }
            }
        }
    }

    /** An episode: into "<base>.tmp" in the anime's own folder (r90 case-twin rules), renamed when complete */
    private suspend fun episodeWriter(plan: Plan, target: Target.EpisodeTarget, item: ExtraItem): Writer? {
        val manager = Injekt.get<AnimeDownloadManager>()
        // KMK (r92): under the downloader's folder lock (a download of the same anime could make a twin folder)
        val dir = manager.animeDirLocked(target.anime, create = true) ?: return null
        val base = episodeBaseName(target.episode)
        val ext = item.files.firstOrNull()?.name?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { AnimeDownloadManager.isEpisodeVideo("x.$it") } ?: "mkv"
        val finalName = "$base.$ext"
        val tmpName = base + EPISODE_TMP
        dir.findFile(tmpName)?.delete()
        val tmp = dir.createFile(tmpName) ?: return null
        return object : Writer() {
            override fun output(index: Int, name: String): OutputStream? =
                if (index == 0) tmp.openOutputStream() else null

            override suspend fun finish(ok: Boolean): Boolean {
                if (!ok || dir.findFile(finalName) != null || !tmp.renameTo(finalName)) {
                    tmp.delete()
                    return false
                }
                plan.touchedAnime[target.anime.id] = target.anime
                return true
            }

            override fun abort() {
                runCatching { tmp.delete() }
            }
        }
    }

    // Progress

    /** The bars of one direction of the extras phase: covers, manga downloads, anime downloads */
    class Progress(items: List<ExtraItem>, indexes: List<Int>) {
        private class Kind {
            val left = HashMap<String, Int>()
            var titles = 0
            var titlesDone = 0
            var bytes = 0L
            var bytesDone = 0L
        }

        var totalBytes = 0L
            private set
        var doneBytes = 0L
            private set
        private var covers = 0
        private var coversDone = 0
        private val manga = Kind()
        private val anime = Kind()

        init {
            for (index in indexes) {
                val item = items[index]
                totalBytes += item.bytes()
                if (item.kind == KIND_COVER) {
                    covers++
                } else {
                    val kind = kindOf(item)
                    val key = keyOf(item.source, item.url)
                    kind.left[key] = (kind.left[key] ?: 0) + 1
                    kind.bytes += item.bytes()
                }
            }
            manga.titles = manga.left.size
            anime.titles = anime.left.size
        }

        private fun kindOf(item: ExtraItem) = if (item.anime) anime else manga

        fun bytes(item: ExtraItem, count: Long) {
            doneBytes += count
            if (item.kind != KIND_COVER) kindOf(item).bytesDone += count
        }

        /** [item] is done (saved or not) */
        fun itemDone(item: ExtraItem) {
            if (item.kind == KIND_COVER) {
                coversDone++
                return
            }
            val kind = kindOf(item)
            val key = keyOf(item.source, item.url)
            val left = (kind.left[key] ?: 1) - 1
            kind.left[key] = left
            if (left == 0) kind.titlesDone++
        }

        fun parts(library: TransferPart): List<TransferPart> = buildList {
            add(library)
            if (covers > 0) {
                add(
                    TransferPart(
                        name = "Covers",
                        detail = "%,d of %,d".format(coversDone, covers),
                        fraction = (coversDone.toFloat() / covers).coerceIn(0f, 1f),
                        done = coversDone >= covers,
                    ),
                )
            }
            listOf("Manga downloads" to manga, "Anime downloads" to anime).forEach { (name, kind) ->
                if (kind.titles == 0) return@forEach
                val done = kind.titlesDone >= kind.titles
                add(
                    TransferPart(
                        name = name,
                        detail = "${kind.titlesDone} of ${titles(kind.titles)} · " +
                            "${gb(kind.bytesDone)} / ${gb(kind.bytes)} GB",
                        fraction = if (done || kind.bytes <= 0L) {
                            if (done) 1f else 0f
                        } else {
                            (kind.bytesDone.toFloat() / kind.bytes).coerceIn(0f, 1f)
                        },
                        done = done,
                    ),
                )
            }
        }
    }

    // Words

    private fun titles(count: Int) = if (count == 1) "1 title" else "%,d titles".format(count)

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1024.0 / 1024.0 / 1024.0)

    /** "41 GB" (from 10 GB), "0.6 GB" (from half a GB), "210 MB", "48 KB"; KMK (r92): the pop-up uses it too */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> "0 MB"
        bytes >= 10L * 1024 * 1024 * 1024 -> "%.0f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 512L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> "%,d MB".format((bytes + 512 * 1024) / 1024 / 1024)
        else -> "${(bytes + 1023) / 1024} KB"
    }

    /** "≈ 40 s", "≈ 3 min", "≈ 1 h 10 min" at [perSecond] */
    fun etaText(bytes: Long, perSecond: Long): String {
        val seconds = (bytes / perSecond.coerceAtLeast(1L)).coerceAtLeast(1L)
        return when {
            seconds < 60L -> "≈ $seconds s"
            seconds < 3600L -> "≈ ${(seconds + 30) / 60} min"
            else -> "≈ ${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }

    /**
     * The summary rows: ("Covers", "up to 1,520 · 210 MB") and ("Downloads", "3 manga titles · 0.6 GB"). KMK (r92):
     * "up to": only the covers the other device lacks go
     */
    suspend fun summaryRows(selection: SyncSelection): List<SummaryRow> {
        val own = selection.normalized()
        val rows = ArrayList<SummaryRow>()
        var coverCount = 0
        var coverBytes = 0L
        for (anime in listOf(false, true)) {
            if (!own.coversOf(anime)) continue
            val estimate = coversEstimate(anime, own)
            coverCount += estimate.count
            coverBytes += estimate.bytes
        }
        if (coverCount > 0) rows += SummaryRow("Covers", "up to %,d · %s".format(coverCount, sizeText(coverBytes)))
        val manga = downloadsEstimate(false, own)
        val anime = downloadsEstimate(true, own)
        val bytes = manga.third + anime.third
        val what = when {
            manga.first > 0 && anime.first > 0 -> "${manga.first} manga + ${anime.first} anime titles"
            manga.first > 0 -> "${manga.first} manga " + if (manga.first == 1) "title" else "titles"
            anime.first > 0 -> "${anime.first} anime " + if (anime.first == 1) "title" else "titles"
            else -> null
        }
        if (what != null) rows += SummaryRow("Downloads", "$what · ${sizeText(bytes)}")
        return rows
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream()
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
