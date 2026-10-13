package eu.kanade.tachiyomi.data.cache

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.BotCheckNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK: "Save covers in download folders": each library title's cover is kept as cover.jpg in its folder in the
 * downloads location (on the SD card if that's where downloads go). When the app's own cover copy is missing, as
 * after a fresh install or a restore, the cover is read from there instead of being downloaded again.
 */
object CoverFolders {

    private const val COVER_NAME = "cover.jpg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var saveAllJob: Job? = null

    val enabled: Boolean
        get() = runCatching { Injekt.get<DownloadPreferences>().saveCoversInFolders().get() }.getOrDefault(false)

    /** The title's folder: manga under its source in the downloads location, anime under "anime" */
    private suspend fun folder(id: Long, sourceId: Long, anime: Boolean, create: Boolean): UniFile? = runCatching {
        if (anime) {
            val title = Injekt.get<AnimeRepository>().getAnime(id) ?: return@runCatching null
            Injekt.get<AnimeDownloadManager>().animeDir(title, create)
        } else {
            val manga = Injekt.get<GetManga>().await(id) ?: return@runCatching null
            if (manga.isLocal()) return@runCatching null
            val source = Injekt.get<SourceManager>().getOrStub(sourceId)
            val provider = Injekt.get<DownloadProvider>()
            if (create) {
                provider.getMangaDir(manga.title, source).getOrNull()
            } else {
                provider.findMangaDir(manga.title, source)
            }
        }
    }.getOrNull()

    /**
     * Copies the title's saved cover.jpg into [target] (the app's cover copy). True when there was one, so the cover
     * doesn't need downloading.
     */
    suspend fun restoreInto(id: Long, sourceId: Long, anime: Boolean, target: File): Boolean {
        if (!enabled) return false
        val saved = folder(id, sourceId, anime, create = false)?.findFile(COVER_NAME) ?: return false
        return try {
            target.parentFile?.mkdirs()
            saved.openInputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            target.length() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            target.delete()
            false
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
