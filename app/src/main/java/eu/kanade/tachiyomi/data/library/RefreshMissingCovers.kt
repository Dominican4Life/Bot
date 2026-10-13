package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.core.app.NotificationCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.PauseBetweenTitles
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Refresh missing covers" in the Library menu, for the titles of the tab being looked at. A cover is missing
 * when the title has no cover address or its picture isn't saved on the device. Each one is first just loaded
 * again; only when that fails are the title's details read again from its source for a new cover address.
 */
object RefreshMissingCovers {

    private const val NOTIFICATION_ID = -104

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** False when a refresh is already going */
    fun start(context: Context, mangas: List<Manga>): Boolean {
        if (isRunning) return false
        val app = context.applicationContext
        job = scope.launch { run(app, mangas) }
        return true
    }

    private suspend fun run(context: Context, mangas: List<Manga>) {
        val coverCache = Injekt.get<CoverCache>()
        val missing = mangas.filter { !it.isLocal() && isMissing(it, coverCache) }
        val title = context.stringResource(KMR.strings.refresh_missing_covers_title)
        if (missing.isEmpty()) {
            done(context, title, context.stringResource(KMR.strings.refresh_missing_covers_none))
            return
        }
        val updateFromRemote = Injekt.get<UpdateMangaFromRemote>()
        val getManga = Injekt.get<GetManga>()
        var fixed = 0
        // "Pause between titles" from Settings › Library, between titles of the same source
        val libraryPreferences = Injekt.get<LibraryPreferences>()
        val pauseEnabled = libraryPreferences.updatePauseEnabled().get()
        val pauseRange = libraryPreferences.updatePause().get()
        var lastSource: Long? = null
        try {
            missing.forEachIndexed { index, manga ->
                currentCoroutineContext().ensureActive()
                if (lastSource == manga.source) PauseBetweenTitles.pause(pauseEnabled, pauseRange)
                lastSource = manga.source
                context.notify(NOTIFICATION_ID, Notifications.CHANNEL_LIBRARY_PROGRESS) {
                    setSmallIcon(R.drawable.ic_refresh_24dp)
                    setContentTitle(title)
                    setContentText(manga.title)
                    setProgress(missing.size, index, false)
                    setOnlyAlertOnce(true)
                    setOngoing(true)
                    priority = NotificationCompat.PRIORITY_LOW
                }
                if (load(context, manga)) {
                    fixed++
                    return@forEachIndexed
                }
                // The address itself may be dead: ask the source for the title's details again
                updateFromRemote(manga, fetchDetails = true, manualFetch = true)
                val fresh = getManga.await(manga.id) ?: manga
                if (load(context, fresh)) fixed++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep what was fixed so far
        }
        done(
            context,
            title,
            context.stringResource(KMR.strings.refresh_missing_covers_done, fixed, missing.size),
        )
    }

    private fun isMissing(manga: Manga, coverCache: CoverCache): Boolean {
        if (coverCache.getCustomCoverFile(manga.id).exists()) return false
        val url = manga.thumbnailUrl
        if (url.isNullOrBlank()) return true
        return coverCache.getCoverFile(url)?.exists() != true
    }

    /** Loads the cover the way the library does, which also saves it on the device */
    private suspend fun load(context: Context, manga: Manga): Boolean {
        if (manga.thumbnailUrl.isNullOrBlank()) return false
        return try {
            val request = ImageRequest.Builder(context).data(manga.asMangaCover()).build()
            context.imageLoader.execute(request) is SuccessResult
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun done(context: Context, title: String, text: String) {
        context.cancelNotification(NOTIFICATION_ID)
        context.notify(NOTIFICATION_ID, Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(title)
            setContentText(text)
            setAutoCancel(true)
        }
    }
}
