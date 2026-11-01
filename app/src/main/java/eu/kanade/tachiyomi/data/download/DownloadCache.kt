package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
class DownloadCache(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    // KMK -->
    private val downloadPreferences: DownloadPreferences = Injekt.get()
    // KMK <--

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     * KMK (r103): the setting's default is 12 hours: the app updates the index itself on every download, delete
     * and rename, and Settings › Advanced › "Reindex downloads" forces a walk.
     */
    private val renewInterval // = 1.hours.inWholeMilliseconds
        // KMK -->
        get() = downloadPreferences.downloadCacheRenewInterval().get()
            .hours.inWholeMilliseconds
    // KMK <--

    /**
     * The last time the cache was refreshed.
     */
    private var lastRenew = 0L
    private var renewalJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1000L) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        // KMK: v4 since manga downloads moved into downloads/Manga, so the index is built again there
        get() = File(context.cacheDir, "dl_index_cache_v4")

    private val rootDownloadsDirMutex = Mutex()

    /** KMK (r99): one [linkFolders] at a time (the start-up run and a renew); declared before `init` uses it */
    private val linkMutex = Mutex()
    private var rootDownloadsDir = RootDirectory(storageManager.getMangaDownloadsDirectory())

    init {
        // Attempt to read cache file
        scope.launch {
            rootDownloadsDirMutex.withLock {
                try {
                    if (diskCacheFile.exists()) {
                        val diskCache = diskCacheFile.inputStream().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        rootDownloadsDir = diskCache
                        isIndexLoaded = true
                        lastRenew = System.currentTimeMillis()
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to initialize from disk cache" }
                    diskCacheFile.delete()
                }
            }
        }

        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)

        // KMK (r99): link the download folders to their source ids once at start, also when the index came from the
        // disk cache (its renew may be hours away). A folder found under a source's older name means the index
        // from the disk cache lacks it: listed again
        scope.launch {
            try {
                // Only with every source loaded: a folder of a source missing from the list would count as unclaimed
                withTimeoutOrNull(30.seconds) {
                    extensionManager.isInitialized.first { it }
                    sourceManager.isInitialized.first { it }
                } ?: return@launch
                val favorites = Injekt.get<tachiyomi.domain.manga.interactor.GetFavorites>().await()
                if (favorites.isEmpty()) return@launch
                val sources = getSources()
                val recovered = linkFolders(sources, sources.map { it.id to provider.sourceMatch(it) })
                if (recovered > 0) invalidateCache()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "DownloadCache: linking the source folders failed" }
            }
        }
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl the url of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean,
    ): Boolean {
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
        }

        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(
                    chapterName,
                    chapterScanlator,
                    chapterUrl,
                ).any { it in mangaDir.chapterDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
    }

    // KMK -->
    /**
     * KMK (r96): true once the index holds a listing (the disk cache, or one renew finished). Before that
     * [rootDownloadsDir] is the empty initial root and "no folder" means nothing.
     */
    @Volatile
    var isIndexLoaded = false
        private set

    /**
     * KMK (r96): whether the index knows a download folder for [manga], and that folder, without touching storage.
     * `false` when the index has no folder for it (a title that was never downloaded has none: nothing to write
     * there). The `UniFile` may be null when the index came from the disk cache; callers then look it up.
     * **`null` when the index isn't loaded yet** ([isIndexLoaded]): the caller falls back to a storage lookup.
     */
    fun findMangaDirInIndex(manga: Manga): Pair<Boolean, UniFile?>? {
        renewCache()
        if (!isIndexLoaded) return null
        val mangaDir = rootDownloadsDir.sourceDirs[manga.source]
            ?.mangaDirs?.get(provider.getMangaDirName(manga.ogTitle))
            ?: return false to null
        return true to mangaDir.dir
    }
    // KMK <--

    /**
     * Returns the amount of downloaded chapters for a manga.
     * This method is quick, but might count other junk files
     * It's still maybe useful while developing the clean-up features
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[
                provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */),
            ]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[
                provider.getMangaDirName(
                    // SY -->
                    manga.ogTitle,
                    // SY <--
                ),
            ] ?: return
            provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                if (it in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= it
                }
            }
        }

        notifyChanges()
    }

    // SY -->
    suspend fun removeFolders(folders: List<String>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.ogTitle)] ?: return
            folders.forEach { chapter ->
                if (chapter in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= chapter
                }
            }
        }
    }

    // SY <--

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[
                provider.getMangaDirName(
                    // SY -->
                    manga.ogTitle,
                    // SY <--
                ),
            ] ?: return
            chapters.forEach { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                    if (it in mangaDir.chapterDirs) {
                        mangaDir.chapterDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    /**
     * Renames a manga in this cache.
     *
     * @param manga the manga being renamed.
     * @param mangaUniFile the manga's new directory.
     * @param newTitle the manga's new title.
     */
    suspend fun renameManga(manga: Manga, mangaUniFile: UniFile, newTitle: String) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldMangaDirName = provider.getMangaDirName(/* KMK --> */ manga.ogTitle /* KMK --> */)
            var oldChapterDirs: MutableSet<String>? = null
            // Save the old name's cached chapter dirs
            if (sourceDir.mangaDirs.containsKey(oldMangaDirName)) {
                oldChapterDirs = sourceDir.mangaDirs[oldMangaDirName]?.chapterDirs
                sourceDir.mangaDirs -= oldMangaDirName
            }

            // Retrieve/create the cached manga directory for new name
            val newMangaDirName = provider.getMangaDirName(newTitle)
            var mangaDir = sourceDir.mangaDirs[newMangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += newMangaDirName to mangaDir
            }

            // Add the old chapters to new name's cache
            if (!oldChapterDirs.isNullOrEmpty()) {
                mangaDir.chapterDirs += oldChapterDirs
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: Source) {
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun invalidateCache() {
        lastRenew = 0L
        renewalJob?.cancel()
        diskCacheFile.delete()
        renewCache(
            // KMK -->
            renewInterval = 0L,
            // KMK <--
        )
    }

    // KMK -->
    fun cleanInvalidDownloads() {
        lastRenew = 0L
        renewalJob?.cancel()
        diskCacheFile.delete()
        renewCache()
    }
    // KMK <--

    /**
     * Renews the downloads cache.
     */
    private fun renewCache(
        // KMK -->
        renewInterval: Long = this.renewInterval,
        // KMK <--
    ) {
        // Avoid renewing cache if in the process nor too often
        if (lastRenew + renewInterval >= System.currentTimeMillis() ||
            // KMK -->
            renewInterval < 0L ||
            // KMK <--
            renewalJob?.isActive == true
        ) {
            return
        }

        renewalJob = scope.launchIO {
            // KMK: running out of memory while listing the folders stops this listing (tried again later) instead
            // of closing the app
            try {
                renew()
            } catch (e: OutOfMemoryError) {
                // What was listed before stays
                System.gc()
                eu.kanade.tachiyomi.util.system.AppLog.add(
                    'E',
                    "DownloadCache",
                    "Ran out of memory listing the download folders; trying again later",
                )
                _isInitializing.emit(false)
            }
        }.also {
            it.invokeOnCompletion(onCancelling = true) { exception ->
                if (exception != null && exception !is CancellationException) {
                    logcat(LogPriority.ERROR, exception) { "DownloadCache: failed to create cache" }
                }
                lastRenew = System.currentTimeMillis()
                // KMK: skipped for an empty library: try again in a minute rather than after the usual wait
                if (libraryWasEmpty && renewInterval > 60_000L) lastRenew -= renewInterval - 60_000L
                notifyChanges()
                // KMK: the libraries count downloads again now the folders were looked at (after a restore, the
                // download badges stayed empty until the setting was switched off and on)
                eu.kanade.tachiyomi.ui.library.LibraryRefresh.request()
            }
        }

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private suspend fun renew() = kotlinx.coroutines.coroutineScope {
        run {
            if (lastRenew == 0L) {
                _isInitializing.emit(true)
            }

            // Try to wait until extensions and sources have loaded
            // SY -->
            var sources = emptyList<Source>()
            withTimeoutOrNull(30.seconds) {
                extensionManager.isInitialized.first { it }
                sourceManager.isInitialized.first { it }

                sources = getSources()
            }
            // SY <--

            // KMK: nothing in the library yet (a fresh install): don't list the download folders. Checked again in a
            // minute, and a restore asks for a full reindex when it's done
            val favorites = runCatching {
                Injekt.get<tachiyomi.domain.manga.interactor.GetFavorites>().await()
            }.getOrNull()
            libraryWasEmpty = favorites?.isEmpty() ?: false
            if (libraryWasEmpty) {
                _isInitializing.emit(false)
                return@run
            }

            // KMK (r103): downloads/Manga is resolved and listed once per re-scan, for the linking and the index
            // (r99 listed it twice). Listed when first needed, after the linking has waited for the sources
            val started = System.nanoTime()
            val rootDir = storageManager.getMangaDownloadsDirectory()
            var rootChildren: List<UniFile>? = null
            var rootListed = 0
            val listRoot: () -> List<UniFile>? = {
                if (rootDir == null) {
                    null
                } else {
                    rootChildren ?: rootDir.listFiles().orEmpty().toList().also {
                        rootChildren = it
                        rootListed++
                    }
                }
            }

            // KMK: a folder belongs to a source by name whatever its tag, (EN), (JS), (ALL), and numbered copies too
            val matches = sources.map { it.id to provider.sourceMatch(it) }

            // KMK (r99): folders are linked to source ids (a renamed source keeps its folder). Older names are
            // recovered once per source, and every folder today's rules give a source is linked to it
            if (favorites != null) linkFolders(sources, matches, listRoot)
            val linkKind = SourceFolderLinks.Kind.MANGA
            val linkedOwners = SourceFolderLinks.owners(linkKind)
            val sourceIds = sources.mapTo(HashSet()) { it.id }

            rootDownloadsDirMutex.withLock {
                val updatedRootDir = RootDirectory(rootDir)

                val bySource = HashMap<Long, MutableList<Pair<UniFile, Int>>>()
                listRoot().orEmpty()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .forEach { dir ->
                        // KMK (r99): a folder linked to a source's id is that source's, before any name rule
                        // (ranked by link order, ahead of every name rank)
                        val linkedIds = linkedOwners[dir.name!!].orEmpty().filter { it in sourceIds }
                        linkedIds.forEach { id ->
                            val order = SourceFolderLinks.folders(linkKind, id).indexOf(dir.name!!)
                            bySource.getOrPut(id) { mutableListOf() } += dir to (order - LINKED_RANK_BASE)
                        }
                        matches
                            .mapNotNull { (id, match) -> match.rank(dir.name!!)?.let { id to it } }
                            .minByOrNull { it.second }
                            ?.takeIf { (id, _) -> id !in linkedIds }
                            ?.let { (id, rank) -> bySource.getOrPut(id) { mutableListOf() } += dir to rank }
                    }

                val folders = bySource.mapValues { (_, list) -> list.sortedBy { it.second }.map { it.first } }
                updatedRootDir.sourceDirs = folders.mapValues { (_, dirs) -> SourceDirectory(dirs.first()) }

                // KMK: three sources at a time: listing every source at once needed a lot of memory together
                val gate = kotlinx.coroutines.sync.Semaphore(3)
                updatedRootDir.sourceDirs.map { (sourceId, sourceDir) ->
                    async {
                        gate.acquire()
                        try {
                        // Titles from every folder of the source; the best folder wins when a title is in two
                        val mangaDirs = LinkedHashMap<String, MangaDirectory>()
                        folders[sourceId].orEmpty().forEach { folder ->
                            folder.listFiles().orEmpty()
                                .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                .forEach { if (it.name!! !in mangaDirs) mangaDirs[it.name!!] = MangaDirectory(it) }
                        }
                        sourceDir.mangaDirs = mangaDirs

                        sourceDir.mangaDirs.values.forEach { mangaDir ->
                            val chapterDirs = mangaDir.dir?.listFiles().orEmpty()
                                .mapNotNull {
                                    when {
                                        // Ignore incomplete downloads
                                        it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                                        // Folder of images
                                        it.isDirectory -> it.name
                                        // CBZ files
                                        it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                        // Anything else is irrelevant
                                        else -> null
                                    }
                                }
                                .toMutableSet()

                            mangaDir.chapterDirs = chapterDirs
                        }
                        } finally {
                            gate.release()
                        }
                    }
                }
                    .awaitAll()

                rootDownloadsDir = updatedRootDir
                isIndexLoaded = true

                eu.kanade.tachiyomi.util.system.AppLog.add(
                    'D',
                    "download",
                    "Download index re-scan: ${updatedRootDir.sourceDirs.values.sumOf { it.mangaDirs.size }} " +
                        "titles in ${updatedRootDir.sourceDirs.size} sources, downloads/Manga listed $rootListed×, " +
                        "${(System.nanoTime() - started) / 1_000_000} ms",
                )
            }

            _isInitializing.emit(false)
        }
    }

    // KMK
    @Volatile
    private var libraryWasEmpty = false

    /**
     * KMK (r99): links the manga download folders to their source ids before the index is built
     * ([SourceFolderLinks.recoverAndSeed]): a JS source renamed in the Source Lab ("Mitaku JS" → "Mitaku") keeps
     * showing the downloads in its old folder. Only with every source loaded, also the switched-off, hidden and
     * uninstalled ones ([SourceFolderLinks.knownSources]), so a folder of one of them is never taken as unclaimed;
     * when they didn't load, nothing is linked and no source is marked tried. A failure here only skips the linking.
     */
    private suspend fun linkFolders(
        sources: List<Source>,
        matches: List<Pair<Long, SourceFolders.Match>>,
        // KMK (r103): renew's one listing of downloads/Manga (null result: no such folder); null = list it here
        rootListing: (() -> List<UniFile>?)? = null,
    ): Int {
        if (sources.isEmpty()) return 0
        return linkMutex.withLock {
            try {
                val known = SourceFolderLinks.knownSources(SourceFolderLinks.Kind.MANGA) ?: return@withLock 0
                linkFoldersLocked(sources, matches, known, rootListing)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                eu.kanade.tachiyomi.util.system.AppLog.add(
                    'W',
                    "download",
                    "Couldn't link the download folders to their sources: ${e.message}",
                )
                0
            }
        }
    }

    private fun linkFoldersLocked(
        sources: List<Source>,
        matches: List<Pair<Long, SourceFolders.Match>>,
        known: List<SourceFolderLinks.Owner>,
        rootListing: (() -> List<UniFile>?)?,
    ): Int {
        val children = if (rootListing != null) {
            rootListing() ?: return 0
        } else {
            val root = storageManager.getMangaDownloadsDirectory() ?: return 0
            root.listFiles().orEmpty().toList()
        }
        val matchById = matches.toMap()
        val candidates = sources.mapNotNull { source ->
            val match = matchById[source.id] ?: return@mapNotNull null
            SourceFolderLinks.Candidate(
                id = source.id,
                displayName = source.name,
                baseName = match.baseName,
                match = match,
                jsKey = (source as? eu.kanade.tachiyomi.jssource.JsMangaSource)?.info?.key,
                sanitize = provider::validFolderName,
            )
        }
        return SourceFolderLinks.recoverAndSeed(
            SourceFolderLinks.Kind.MANGA,
            children,
            candidates,
            known,
        )
    }

    private fun getSources(): List<Source> {
        // SY -->
        return sourceManager.getVisibleOnlineSources() + sourceManager.getStubSources()
        // SY <--
    }

    private fun notifyChanges() {
        scope.launchNonCancellable {
            _changes.send(Unit)
        }
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1000)
            ensureActive()
            val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                logcat(
                    priority = LogPriority.ERROR,
                    throwable = e,
                    message = { "Failed to write disk cache file" },
                )
            }
        }
    }
}

/** KMK (r99): folders linked to a source id rank ahead of every name rank in the index (link order kept) */
private const val LINKED_RANK_BASE = 1000

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var chapterDirs: MutableSet<String> = mutableSetOf(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Application>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
