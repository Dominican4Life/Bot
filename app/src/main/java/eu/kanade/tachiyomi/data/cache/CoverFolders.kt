package eu.kanade.tachiyomi.data.cache

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.library.RefreshMissingCovers
import eu.kanade.tachiyomi.data.notification.BotCheckNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: "Save covers in download folders": each library title's cover is kept as cover.jpg in its folder in the
 * downloads location (on the SD card if that's where downloads go). When the app's own cover copy is missing, as
 * after a fresh install or a restore, the cover is read from there instead of being downloaded again.
 */
object CoverFolders {

    private const val COVER_NAME = "cover.jpg"
    private const val LISTING_TTL_MS = 2 * 60_000L
    private const val MAX_LISTINGS = 64
    private const val SOURCES_WAIT_MS = 20_000L
    private const val PREVIEWS = "previews"
    private const val ADD_DELAY_MS = 3_000L
    private const val INDEX_TTL_MS = 15 * 60_000L
    private const val MISS_TTL_MS = 30 * 60_000L
    private const val IMPORT_UPDATE_CHUNK = 500

    /** The folders that sort downloads by kind, not sources */
    private val TYPE_FOLDERS = setOf("manga", "anime")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var saveAllJob: Job? = null

    val enabled: Boolean
        get() = runCatching { Injekt.get<DownloadPreferences>().saveCoversInFolders().get() }.getOrDefault(false)

    /** Where a title's cover.jpg is written: its download folder, made if missing */
    private suspend fun folder(id: Long, sourceId: Long, anime: Boolean, create: Boolean): UniFile? = runCatching {
        if (anime) {
            val title = Injekt.get<AnimeRepository>().getAnime(animeId(id)) ?: return@runCatching null
            Injekt.get<AnimeDownloadManager>().animeDir(title, create)
        } else {
            val manga = Injekt.get<GetManga>().await(id) ?: return@runCatching null
            if (manga.isLocal()) return@runCatching null
            val source = Injekt.get<SourceManager>().getOrStub(manga.source)
            val provider = Injekt.get<DownloadProvider>()
            // The original title, as downloads use: a title renamed in Edit info keeps its downloads' folder
            if (create) {
                provider.getMangaDir(manga.ogTitle, source).getOrNull()
            } else {
                provider.findMangaDir(manga.ogTitle, source)
            }
        }
    }.getOrNull()

    /** Anime covers are drawn with negative ids (kept apart from manga in the cover caches) */
    private fun animeId(id: Long): Long = if (id < 0) -id else id

    /** A title being looked for: the names its folder may have, its source's folder and name */
    private class Wanted(
        val names: List<String>,
        val sourceDir: String?,
        val sourceName: String?,
        val exact: String,
    )

    /**
     * The folder holding a title's cover.jpg, looked up in [FolderIndex] (the download folders listed once), so
     * scrolling the library doesn't search the SD card again for each cover. A title with no folder is remembered for
     * a while ([MISS_TTL_MS]) and goes straight to downloading its cover.
     */
    private suspend fun findFolder(id: Long, sourceId: Long, anime: Boolean): UniFile? {
        val key = missKey(id, anime)
        misses[key]?.let { at -> if (System.currentTimeMillis() - at < MISS_TTL_MS) return null }
        awaitSources()
        val wanted = wanted(id, sourceId, anime) ?: return null
        val index = index() ?: return null
        index.find(wanted, anime)?.let { return it }
        misses[key] = System.currentTimeMillis()
        logcat("cover", LogPriority.WARN) { "No download folder for ${wanted.exact}" }
        return null
    }

    private fun missKey(id: Long, anime: Boolean) = if (anime) -animeId(id) else id

    /** Titles with no download folder, by when they were looked for */
    private val misses = ConcurrentHashMap<Long, Long>()

    private suspend fun wanted(id: Long, sourceId: Long, anime: Boolean): Wanted? = runCatching {
        if (anime) {
            val title = Injekt.get<AnimeRepository>().getAnime(animeId(id)) ?: return@runCatching null
            val name = DiskUtil.buildValidFilename(title.title)
            val sourceName = Injekt.get<AnimeSourceManager>().get(title.source)?.name
            Wanted(listOf(name), null, sourceName, "Anime/${sourceName ?: title.source}/$name")
        } else {
            val manga = Injekt.get<GetManga>().await(id) ?: return@runCatching null
            if (manga.isLocal()) return@runCatching null
            wantedManga(manga)
        }
    }.getOrNull()

    private fun wantedManga(manga: Manga): Wanted {
        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
        val provider = Injekt.get<DownloadProvider>()
        // Covers saved by r33–r35 went under the displayed title, which differs for renamed titles
        val names = listOf(manga.ogTitle, manga.title).map(provider::getMangaDirName).distinct()
        val sourceDir = provider.getSourceDirName(source)
        return Wanted(
            names = names,
            sourceDir = sourceDir,
            sourceName = source.name.takeIf { it.isNotBlank() },
            exact = "Manga/$sourceDir/${names.first()}",
        )
    }

    /**
     * Every title folder in the download folders, listed in one go: by "source/title" and by title alone (for a
     * source whose folder name changed), upper and lower case not mattering. Kept for [INDEX_TTL_MS]; folders made or
     * removed by the app in the meantime are added or taken out as that happens.
     */
    private class FolderIndex(val at: Long) {
        /** "manga|source/title" or "anime|source/title" */
        val exact = ConcurrentHashMap<String, UniFile>()

        /** "manga|title" → the folders with that title, with their source folder's name */
        val byTitle = ConcurrentHashMap<String, MutableList<Pair<String, UniFile>>>()

        fun add(kind: String, sourceDir: String, dir: UniFile) {
            val title = dir.name?.lowercase() ?: return
            val source = sourceDir.lowercase()
            exact["$kind|$source/$title"] = dir
            byTitle.getOrPut("$kind|$title") { java.util.Collections.synchronizedList(mutableListOf()) }
                .add(source to dir)
        }

        fun remove(dir: UniFile) {
            val uri = dir.uri
            exact.values.removeAll { it.uri == uri }
            byTitle.values.forEach { list -> list.removeAll { it.second.uri == uri } }
        }

        fun find(wanted: Wanted, anime: Boolean): UniFile? {
            val kind = if (anime) "anime" else "manga"
            val source = wanted.sourceDir?.lowercase()
            if (source != null) {
                wanted.names.firstNotNullOfOrNull { exact["$kind|$source/${it.lowercase()}"] }?.let { return it }
            }
            // The title's folder under another source folder: the title's own source first
            val prefix = wanted.sourceName?.lowercase().orEmpty()
            return wanted.names.firstNotNullOfOrNull { name ->
                val found = byTitle["$kind|${name.lowercase()}"]?.toList().orEmpty()
                (found.firstOrNull { prefix.isNotBlank() && it.first.startsWith(prefix) } ?: found.firstOrNull())
                    ?.second
                    ?.also { logcat("cover", LogPriority.INFO) { "Cover folder found as ${it.name}" } }
            }
        }
    }

    @Volatile
    private var folderIndex: FolderIndex? = null
    private val indexLock = Mutex()

    /** The download folders' index, listed again once it's [INDEX_TTL_MS] old; null without access to them */
    private suspend fun index(): FolderIndex? {
        // KMK: nothing in the library yet (a fresh install): don't list the download folders for covers
        if (libraryIsEmpty()) return null
        folderIndex?.takeIf { System.currentTimeMillis() - it.at < INDEX_TTL_MS }?.let { return it }
        return indexLock.withLock {
            folderIndex?.takeIf { System.currentTimeMillis() - it.at < INDEX_TTL_MS }?.let { return@withLock it }
            buildIndex()?.also { folderIndex = it }
        }
    }

    @Volatile
    private var emptyCheck: Pair<Long, Boolean>? = null

    /** True when neither the manga nor the anime library has anything; checked at most every 30 s */
    private suspend fun libraryIsEmpty(): Boolean {
        val now = System.currentTimeMillis()
        emptyCheck?.let { (at, empty) -> if (now - at < 30_000) return empty }
        val empty = runCatching {
            Injekt.get<GetFavorites>().await().isEmpty() &&
                Injekt.get<AnimeRepository>().getFavorites().isEmpty()
        }.getOrDefault(false)
        emptyCheck = now to empty
        return empty
    }

    private fun buildIndex(): FolderIndex? {
        val storage = Injekt.get<StorageManager>()
        val downloads = storage.getDownloadsDirectory()
        if (downloads == null) {
            logOnce("no-downloads") {
                "No access to the download folder: choose it again in Settings › Data and storage"
            }
            return null
        }
        val started = System.currentTimeMillis()
        val index = FolderIndex(started)
        // downloads/Manga and downloads/Anime; for manga also downloads itself, where they were before r37
        val roots = listOfNotNull(
            storage.getMangaDownloadsDirectory(create = false)?.let { "manga" to it },
            "manga" to downloads,
            storage.getAnimeDownloadsDirectory(create = false)?.let { "anime" to it },
        )
        roots.forEach { (kind, root) ->
            root.listFiles().orEmpty()
                .filter { it.isDirectory && (it.name ?: "").lowercase() !in TYPE_FOLDERS }
                .forEach { sourceDir ->
                    val sourceName = sourceDir.name ?: return@forEach
                    sourceDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { index.add(kind, sourceName, it) }
                }
        }
        // Titles missed before may have a folder now
        misses.clear()
        logcat("cover", LogPriority.INFO) {
            "Download folders listed: ${index.exact.size} titles in ${System.currentTimeMillis() - started} ms"
        }
        return index
    }

    /** Lists the download folders again the next time a cover is looked for */
    private fun forgetIndex() {
        folderIndex = null
        emptyCheck = null
        misses.clear()
        listings.clear()
    }

    /** Folder contents by lower-case name, kept for a short while so a folder isn't listed twice in a row */
    private val listings = ConcurrentHashMap<String, Pair<Long, Map<String, UniFile>>>()

    /** KMK: folder listings kept now (for the memory lines in Logs) */
    val listingCount: Int get() = listings.size

    /** KMK: lets go of the kept folder listings (memory running low) */
    fun dropListings() = listings.clear()

    private fun children(dir: UniFile): Map<String, UniFile> {
        val key = dir.uri.toString()
        val now = System.currentTimeMillis()
        listings[key]?.let { (at, map) -> if (now - at < LISTING_TTL_MS) return map }
        val map = dir.listFiles().orEmpty().mapNotNull { file -> file.name?.let { it.lowercase() to file } }.toMap()
        // KMK: listings past their time are let go, and at most MAX_LISTINGS are kept. They were never thrown away
        // before: after a restore (every title's folder listed) they held hundreds of thousands of files in memory
        listings.entries.removeIf { now - it.value.first >= LISTING_TTL_MS }
        if (listings.size >= MAX_LISTINGS) {
            listings.entries.sortedBy { it.value.first }.take(listings.size - MAX_LISTINGS + 1)
                .forEach { listings.remove(it.key) }
        }
        listings[key] = now to map
        return map
    }

    /** Sources load a moment after the app opens; before that their folder names aren't known */
    private suspend fun awaitSources() {
        withTimeoutOrNull(SOURCES_WAIT_MS) { Injekt.get<SourceManager>().isInitialized.first { it } }
    }

    private val loggedOnce = ConcurrentHashMap.newKeySet<String>()

    private fun logOnce(key: String, message: () -> String) {
        if (loggedOnce.add(key)) logcat("cover", LogPriority.WARN, message)
    }

    /**
     * Copies the title's saved cover.jpg into [target] (the app's cover copy). True when there was one, so the cover
     * doesn't need downloading. From then on the cover loads from the app's copy, not the folder.
     */
    suspend fun restoreInto(id: Long, sourceId: Long, anime: Boolean, target: File): Boolean {
        if (!enabled) {
            logOnce("off") { "Covers aren't read from download folders: the setting is off" }
            return false
        }
        val dir = findFolder(id, sourceId, anime) ?: return false
        return copyInto(dir, target)
    }

    private fun copyInto(dir: UniFile, target: File): Boolean {
        val saved = children(dir)[COVER_NAME] ?: dir.findFile(COVER_NAME)
        if (saved == null) {
            logcat("cover", LogPriority.WARN) { "No $COVER_NAME in ${dir.name}" }
            return false
        }
        return try {
            target.parentFile?.mkdirs()
            saved.openInputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            (target.length() > 0).also { ok ->
                if (ok) {
                    logcat("cover", LogPriority.DEBUG) { "Cover read from ${dir.name}/$COVER_NAME" }
                } else {
                    target.delete()
                }
            }
        } catch (e: Exception) {
            logcat("cover", LogPriority.WARN) { "Couldn't read ${dir.name}/$COVER_NAME: ${e.message}" }
            target.delete()
            false
        }
    }

    @Volatile
    private var importJob: Job? = null

    val isImporting: Boolean get() = importJob?.isActive == true

    /** After a restore: the covers missing from the device come from the download folders, with progress shown */
    fun fillMissingAfterRestore() {
        if (!enabled) return
        importAll(Injekt.get<Application>())
    }

    /**
     * "Import covers from download folders": every library title whose cover isn't on the device gets its saved
     * cover.jpg copied into the app, once, with a progress notification. The download folders are listed a single
     * time for all of them, and the library is redrawn once at the end. Nothing is downloaded here.
     */
    fun importAll(context: Context, notify: Boolean = true) {
        if (isImporting) return
        val app = context.applicationContext
        importJob = scope.launch {
            awaitSources()
            forgetIndex()
            val index = index()
            val title = app.stringResource(KMR.strings.import_covers_title)
            if (index == null) {
                if (notify) done(app, title, app.stringResource(KMR.strings.import_covers_no_access))
                return@launch
            }
            val coverCache = Injekt.get<CoverCache>()
            val manga = Injekt.get<GetFavorites>().await()
                .filter { !it.isLocal() && RefreshMissingCovers.isMissing(it, coverCache) }
            val anime = runCatching { Injekt.get<AnimeRepository>().getFavorites() }.getOrDefault(emptyList())
                .filter { coverCache.getCoverFile(it.thumbnailUrl)?.exists() == false }
            val total = manga.size + anime.size
            val filled = mutableListOf<Long>()
            var noFolder = 0
            var checked = 0
            manga.forEach { item ->
                currentCoroutineContext().ensureActive()
                val target = coverCache.getCoverFile(item.thumbnailUrl)
                val dir = index.find(wantedManga(item), anime = false)
                when {
                    target == null -> Unit
                    dir == null -> noFolder++
                    copyInto(dir, target) -> filled += item.id
                }
                if (notify && ++checked % 20 == 0) progress(app, title, checked, total)
            }
            var animeFilled = 0
            anime.forEach { item ->
                currentCoroutineContext().ensureActive()
                val target = coverCache.getCoverFile(item.thumbnailUrl)
                val dir = wanted(item.id, item.source, anime = true)?.let { index.find(it, anime = true) }
                when {
                    target == null -> Unit
                    dir == null -> noFolder++
                    copyInto(dir, target) -> animeFilled++
                }
                if (notify && ++checked % 20 == 0) progress(app, title, checked, total)
            }
            // One update for all of them, so the library is redrawn once instead of once per cover
            if (filled.isNotEmpty()) {
                val now = java.time.Instant.now().toEpochMilli()
                filled.chunked(IMPORT_UPDATE_CHUNK).forEach { ids ->
                    Injekt.get<UpdateManga>().awaitAll(ids.map { MangaUpdate(id = it, coverLastModified = now) })
                }
            }
            val imported = filled.size + animeFilled
            logcat("cover", LogPriority.INFO) {
                "Covers imported from download folders: $imported of $total missing ($noFolder had no folder)"
            }
            if (notify) done(app, title, app.stringResource(KMR.strings.import_covers_done, imported, total))
        }
    }

    /** Keeps [cover] (the app's cover copy) as the title's cover.jpg, in the background */
    fun save(id: Long, sourceId: Long, anime: Boolean, cover: File) {
        if (!enabled) return
        scope.launch { write(id, sourceId, anime, cover) }
    }

    private suspend fun write(id: Long, sourceId: Long, anime: Boolean, cover: File): Boolean {
        if (!cover.exists() || cover.length() == 0L) return false
        return try {
            val dir = folder(id, sourceId, anime, create = true) ?: return false
            val file = dir.findFile(COVER_NAME) ?: dir.createFile(COVER_NAME) ?: return false
            if (file.length() != cover.length()) {
                file.openOutputStream().use { out -> cover.inputStream().use { it.copyTo(out) } }
            }
            remember(dir, id, anime)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A title just added to the library: its cover (the one being shown, or loaded now) is saved to its folder. The
     * cover being shown moves into the library's own copy when it's drawn again, so this waits a moment first.
     */
    fun onAddedToLibrary(id: Long, anime: Boolean) {
        if (!enabled) return
        scope.launch {
            delay(ADD_DELAY_MS)
            val coverCache = Injekt.get<CoverCache>()
            val app = Injekt.get<Application>()
            val (sourceId, url, data) = if (anime) {
                val title = Injekt.get<AnimeRepository>().getAnime(animeId(id)) ?: return@launch
                Triple(title.source, title.thumbnailUrl, title.asMangaCover())
            } else {
                val manga = Injekt.get<GetManga>().await(id) ?: return@launch
                if (manga.isLocal()) return@launch
                Triple(manga.source, manga.thumbnailUrl, manga.asMangaCover())
            }
            var file = (if (anime) null else coverCache.getCustomCoverFile(id).takeIf { it.exists() })
                ?: coverCache.getCoverFile(url)?.takeIf { it.exists() }
            if (file == null && !url.isNullOrBlank()) {
                runCatching { app.imageLoader.execute(ImageRequest.Builder(app).data(data).build()) }
                file = coverCache.getCoverFile(url)?.takeIf { it.exists() }
            }
            if (file != null && write(animeIdOr(id, anime), sourceId, anime, file)) {
                logcat("cover", LogPriority.DEBUG) { "Cover saved for a title added to the library" }
            }
        }
    }

    private fun animeIdOr(id: Long, anime: Boolean) = if (anime) animeId(id) else id

    /** A folder the app just wrote to: added to the index, and the title isn't a miss anymore */
    private fun remember(dir: UniFile, id: Long, anime: Boolean) {
        misses.remove(missKey(id, anime))
        val sourceDir = dir.parentFile?.name ?: return
        folderIndex?.add(if (anime) "anime" else "manga", sourceDir, dir)
    }

    /**
     * A title taken out of the library: what was made for it here goes (cover.jpg and the episode previews), and its
     * folder too when nothing else is left in it. Downloaded chapters or episodes are left to the remove choice.
     */
    fun onRemovedFromLibrary(id: Long, anime: Boolean) {
        scope.launch { cleanUp(id, anime) }
    }

    /** Many manga taken out at once: their folders are cleaned one after another, in the background */
    fun onRemovedFromLibrary(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        scope.launch { mangaIds.forEach { cleanUp(it, anime = false) } }
    }

    private suspend fun cleanUp(id: Long, anime: Boolean) {
        try {
            val dir = folder(id, 0L, anime, create = false) ?: return
            dir.findFile(COVER_NAME)?.delete()
            dir.findFile(PREVIEWS)?.delete()
            if (dir.listFiles().isNullOrEmpty()) {
                dir.delete()
                folderIndex?.remove(dir)
            }
            listings.remove(dir.uri.toString())
            logcat("cover", LogPriority.DEBUG) { "Cover folder cleaned up for ${dir.name}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat("cover", LogPriority.WARN) { "Couldn't clean up a removed title's folder: ${e.message}" }
        }
    }

    /** An episode preview's file in the anime's folder */
    private fun previewName(key: String) = DiskUtil.buildValidFilename("$key.jpg")

    /** An episode preview saved in the anime's folder, or null */
    suspend fun readPreview(animeId: Long, key: String): ByteArray? {
        if (!enabled) return null
        return try {
            val dir = folder(animeId, 0L, anime = true, create = false) ?: return null
            val previews = children(dir)[PREVIEWS] ?: return null
            val file = children(previews)[previewName(key).lowercase()] ?: return null
            file.openInputStream().use { it.readBytes() }.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Keeps an episode preview in the anime's folder, in the background */
    fun savePreview(animeId: Long, key: String, bytes: ByteArray) {
        if (!enabled || bytes.isEmpty()) return
        scope.launch {
            try {
                val dir = folder(animeId, 0L, anime = true, create = true) ?: return@launch
                val previews = dir.findFile(PREVIEWS) ?: dir.createDirectory(PREVIEWS) ?: return@launch
                val name = previewName(key)
                if (previews.findFile(name) != null) return@launch
                val file = previews.createFile(name) ?: return@launch
                file.openOutputStream().use { it.write(bytes) }
                listings.remove(previews.uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the preview loads from the internet next time
            }
        }
    }

    val isSavingAll: Boolean get() = saveAllJob?.isActive == true

    /** When the setting is turned on: every library title's cover that's already on the device, into its folder */
    fun saveAll(context: Context, force: Boolean = false) {
        if (isSavingAll || (!force && !enabled)) return
        val app = context.applicationContext
        saveAllJob = scope.launch {
            forgetIndex()
            val coverCache = Injekt.get<CoverCache>()
            val manga = Injekt.get<GetFavorites>().await().filterNot { it.isLocal() }.map { manga ->
                Triple(manga.id, manga.source, coverOf(coverCache, manga.id, manga.thumbnailUrl))
            }
            // Anime have no custom covers here: only their own cover copy
            val anime = runCatching { Injekt.get<AnimeRepository>().getFavorites() }.getOrDefault(emptyList())
                .map { Triple(it.id, it.source, coverCache.getCoverFile(it.thumbnailUrl)?.takeIf { f -> f.exists() }) }
            val all = manga.map { it to false } + anime.map { it to true }
            val title = app.stringResource(KMR.strings.save_covers_title)
            var saved = 0
            all.forEachIndexed { index, (item, isAnime) ->
                currentCoroutineContext().ensureActive()
                val (id, sourceId, file) = item
                if (file != null && write(id, sourceId, isAnime, file)) saved++
                if (index % 20 == 0) progress(app, title, index, all.size)
            }
            done(app, title, app.stringResource(KMR.strings.save_covers_done, saved))
        }
    }

    private fun coverOf(coverCache: CoverCache, id: Long, url: String?): File? =
        coverCache.getCustomCoverFile(id).takeIf { it.exists() }
            ?: coverCache.getCoverFile(url)?.takeIf { it.exists() }

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun progress(context: Context, title: String, current: Int, total: Int) {
        if (!BotCheckNotifier.canNotify(context)) return
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(title)
            setContentText("$current / $total")
            setProgress(total, current, false)
            setOnlyAlertOnce(true)
            setOngoing(true)
            priority = NotificationCompat.PRIORITY_LOW
        }.build()
        NotificationManagerCompat.from(context).notify(Notifications.ID_SAVE_COVERS, notification)
    }

    @SuppressLint("MissingPermission") // checked by canNotify
    private fun done(context: Context, title: String, text: String) {
        if (!BotCheckNotifier.canNotify(context)) return
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_done_24dp)
            setContentTitle(title)
            setContentText(text)
            setAutoCancel(true)
        }.build()
        NotificationManagerCompat.from(context).notify(Notifications.ID_SAVE_COVERS, notification)
    }
}
