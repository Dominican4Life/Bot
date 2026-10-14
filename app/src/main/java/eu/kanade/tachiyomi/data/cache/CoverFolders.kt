package eu.kanade.tachiyomi.data.cache

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.interactor.UpdateManga
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
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
    private const val SOURCES_WAIT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var saveAllJob: Job? = null

    val enabled: Boolean
        get() = runCatching { Injekt.get<DownloadPreferences>().saveCoversInFolders().get() }.getOrDefault(false)

    /** Where a title's cover.jpg is written: its download folder, made if missing */
    private suspend fun folder(id: Long, sourceId: Long, anime: Boolean, create: Boolean): UniFile? = runCatching {
        if (anime) {
            val title = Injekt.get<AnimeRepository>().getAnime(id) ?: return@runCatching null
            Injekt.get<AnimeDownloadManager>().animeDir(title, create)
        } else {
            val manga = Injekt.get<GetManga>().await(id) ?: return@runCatching null
            if (manga.isLocal()) return@runCatching null
            val source = Injekt.get<SourceManager>().getOrStub(sourceId)
            val provider = Injekt.get<DownloadProvider>()
            // The original title, as downloads use: a title renamed in Edit info keeps its downloads' folder
            if (create) {
                provider.getMangaDir(manga.ogTitle, source).getOrNull()
            } else {
                provider.findMangaDir(manga.ogTitle, source)
            }
        }
    }.getOrNull()

    /** A title being looked for: the names its folder may have, and its source's name */
    private class Wanted(val names: List<String>, val sourceName: String?, val exact: String)

    /**
     * The folder holding a title's cover.jpg. First where downloads put it; when that's not there (a source whose
     * name was different, or that wasn't loaded yet), every source folder is searched for the title's folder, with
     * upper and lower case not mattering, the title's own source first.
     */
    private suspend fun findFolder(id: Long, sourceId: Long, anime: Boolean): UniFile? {
        awaitSources()
        folder(id, sourceId, anime, create = false)?.let { return it }
        val wanted = wanted(id, sourceId, anime) ?: return null
        val downloads = Injekt.get<StorageManager>().getDownloadsDirectory()
        if (downloads == null) {
            logOnce("no-downloads") {
                "No access to the download folder: choose it again in Settings › Data and storage"
            }
            return null
        }
        val root = if (anime) children(downloads)["anime"] ?: return miss(wanted) else downloads
        val sourceDirs = children(root).values.filter { it.isDirectory }
        val prefix = wanted.sourceName?.lowercase()
        val ordered = if (prefix.isNullOrBlank()) {
            sourceDirs
        } else {
            sourceDirs.sortedByDescending { (it.name ?: "").lowercase().startsWith(prefix) }
        }
        for (dir in ordered) {
            if (!anime && dir.name == "anime") continue
            val inside = children(dir)
            wanted.names.firstNotNullOfOrNull { inside[it.lowercase()] }?.let { found ->
                logcat("cover", LogPriority.INFO) { "Cover folder found as ${dir.name}/${found.name}" }
                return found
            }
        }
        return miss(wanted)
    }

    private fun miss(wanted: Wanted): UniFile? {
        logcat("cover", LogPriority.WARN) { "No download folder for ${wanted.exact}" }
        return null
    }

    private suspend fun wanted(id: Long, sourceId: Long, anime: Boolean): Wanted? = runCatching {
        if (anime) {
            val title = Injekt.get<AnimeRepository>().getAnime(id) ?: return@runCatching null
            val name = DiskUtil.buildValidFilename(title.title)
            val sourceName = Injekt.get<AnimeSourceManager>().get(sourceId)?.name
            Wanted(listOf(name), sourceName, "anime/${sourceName ?: sourceId}/$name")
        } else {
            val manga = Injekt.get<GetManga>().await(id) ?: return@runCatching null
            if (manga.isLocal()) return@runCatching null
            val source = Injekt.get<SourceManager>().getOrStub(sourceId)
            val provider = Injekt.get<DownloadProvider>()
            // Covers saved by r33–r35 went under the displayed title, which differs for renamed titles
            val names = listOf(manga.ogTitle, manga.title).map(provider::getMangaDirName).distinct()
            Wanted(
                names = names,
                sourceName = source.name.takeIf { it.isNotBlank() },
                exact = "${provider.getSourceDirName(source)}/${names.first()}",
            )
        }
    }.getOrNull()

    /** Folder contents by lower-case name, kept for a short while so a screen of covers doesn't list them each time */
    private val listings = ConcurrentHashMap<String, Pair<Long, Map<String, UniFile>>>()

    private fun children(dir: UniFile): Map<String, UniFile> {
        val key = dir.uri.toString()
        val now = System.currentTimeMillis()
        listings[key]?.let { (at, map) -> if (now - at < LISTING_TTL_MS) return map }
        val map = dir.listFiles().orEmpty().mapNotNull { file -> file.name?.let { it.lowercase() to file } }.toMap()
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
     * doesn't need downloading.
     */
    suspend fun restoreInto(id: Long, sourceId: Long, anime: Boolean, target: File): Boolean {
        if (!enabled) {
            logOnce("off") { "Covers aren't read from download folders: the setting is off" }
            return false
        }
        val dir = findFolder(id, sourceId, anime) ?: return false
        val saved = children(dir)[COVER_NAME] ?: dir.findFile(COVER_NAME)
        if (saved == null) {
            logcat("cover", LogPriority.WARN) { "No $COVER_NAME in ${dir.name}" }
            return false
        }
        return try {
            target.parentFile?.mkdirs()
            saved.openInputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            (target.length() > 0).also { ok ->
                if (ok) logcat("cover", LogPriority.DEBUG) { "Cover read from ${dir.name}/$COVER_NAME" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat("cover", LogPriority.WARN) { "Couldn't read ${dir.name}/$COVER_NAME: ${e.message}" }
            target.delete()
            false
        }
    }

    /**
     * After a restore: library titles whose cover isn't on the device get it from their download folder, and are
     * redrawn. Nothing is downloaded here.
     */
    fun fillMissingAfterRestore() {
        if (!enabled) return
        scope.launch {
            awaitSources()
            val coverCache = Injekt.get<CoverCache>()
            val updateManga = Injekt.get<UpdateManga>()
            var filled = 0
            Injekt.get<GetFavorites>().await()
                .filter { !it.isLocal() && RefreshMissingCovers.isMissing(it, coverCache) }
                .forEach { manga ->
                    currentCoroutineContext().ensureActive()
                    val target = coverCache.getCoverFile(manga.thumbnailUrl) ?: return@forEach
                    if (restoreInto(manga.id, manga.source, anime = false, target)) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                        filled++
                    }
                }
            runCatching { Injekt.get<AnimeRepository>().getFavorites() }.getOrDefault(emptyList())
                .forEach { anime ->
                    currentCoroutineContext().ensureActive()
                    val target = coverCache.getCoverFile(anime.thumbnailUrl) ?: return@forEach
                    if (!target.exists() && restoreInto(anime.id, anime.source, anime = true, target)) filled++
                }
            logcat("cover", LogPriority.INFO) { "After the restore, $filled covers came from download folders" }
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
            if (file.length() == cover.length()) return true
            file.openOutputStream().use { out -> cover.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    val isSavingAll: Boolean get() = saveAllJob?.isActive == true

    /** "Save all covers now": every library title's cover that's already on the device, into its folder */
    fun saveAll(context: Context) {
        if (isSavingAll) return
        val app = context.applicationContext
        saveAllJob = scope.launch {
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
