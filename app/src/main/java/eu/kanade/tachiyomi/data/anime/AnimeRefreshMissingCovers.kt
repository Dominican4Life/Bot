package eu.kanade.tachiyomi.data.anime

import android.content.Context
import androidx.core.app.NotificationCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.anime.coverId
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Refresh missing covers" in the Anime library menu, for the anime of the page being looked at (the anime
 * version of [eu.kanade.tachiyomi.data.library.RefreshMissingCovers]). A cover is missing when the anime has no
 * cover address or its picture isn't saved on the device. Each one is first just loaded again; only when that
 * fails are the anime's details read again from its source for a new cover address.
 */
object AnimeRefreshMissingCovers {

    private const val NOTIFICATION_ID = -105

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    private val isRunning: Boolean get() = job?.isActive == true

    /** False when a refresh is already going */
    fun start(context: Context, animes: List<Anime>): Boolean {
        if (isRunning) return false
        val app = context.applicationContext
        job = scope.launch { run(app, animes) }
        return true
    }

    private suspend fun run(context: Context, animes: List<Anime>) {
        val coverCache = Injekt.get<CoverCache>()
        val missing = animes.filter { isMissing(it, coverCache) }
        val title = context.stringResource(KMR.strings.refresh_missing_covers_title)
        if (missing.isEmpty()) {
            done(context, title, context.stringResource(KMR.strings.refresh_missing_covers_none))
            return
        }
        val repository = Injekt.get<AnimeRepository>()
        val sourceManager = Injekt.get<AnimeSourceManager>()
        var fixed = 0
        // "Pause between titles" from Settings › Library, between anime of the same source
        val libraryPreferences = Injekt.get<LibraryPreferences>()
        val pauseEnabled = libraryPreferences.updatePauseEnabled().get()
        val pauseRange = libraryPreferences.updatePause().get()
        var lastSource: Long? = null
        try {
            missing.forEachIndexed { index, anime ->
                currentCoroutineContext().ensureActive()
                if (lastSource == anime.source) PauseBetweenTitles.pause(pauseEnabled, pauseRange)
                lastSource = anime.source
                context.notify(NOTIFICATION_ID, Notifications.CHANNEL_LIBRARY_PROGRESS) {
                    setSmallIcon(R.drawable.ic_refresh_24dp)
                    setContentTitle(title)
                    setContentText(anime.title)
                    setProgress(missing.size, index, false)
                    setOnlyAlertOnce(true)
                    setOngoing(true)
                    priority = NotificationCompat.PRIORITY_LOW
                }
                if (load(context, anime)) {
                    fixed++
                    return@forEachIndexed
                }
                // The address itself may be dead: ask the source for the anime's details again
                val source = sourceManager.get(anime.source) ?: return@forEachIndexed
                val refreshed = try {
                    val details = source.getAnimeEpisodeUpdate(
                        anime.toSAnime(),
                        emptyList(),
                        fetchDetails = true,
                        fetchEpisodes = false,
                    ).anime
                    details.url = anime.url
                    if (details.title.isBlank()) details.title = anime.title
                    repository.updateFromSource(anime, details)
                    repository.getAnime(anime.id) ?: anime
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@forEachIndexed
                }
                if (load(context, refreshed)) fixed++
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

    private fun isMissing(anime: Anime, coverCache: CoverCache): Boolean {
        if (coverCache.getCustomCoverFile(anime.coverId()).exists()) return false
        val url = anime.thumbnailUrl
        if (url.isNullOrBlank()) return true
        return coverCache.getCoverFile(url)?.exists() != true
    }

    /** Loads the cover the way the library does, which also saves it on the device */
    private suspend fun load(context: Context, anime: Anime): Boolean {
        if (anime.thumbnailUrl.isNullOrBlank()) return false
        return try {
            val request = ImageRequest.Builder(context).data(anime.asMangaCover()).build()
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
