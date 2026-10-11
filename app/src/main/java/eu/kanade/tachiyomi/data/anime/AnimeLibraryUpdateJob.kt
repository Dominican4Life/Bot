package eu.kanade.tachiyomi.data.anime

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK: Checks every anime in the library for new episodes. Runs with the manga library update.
 */
class AnimeLibraryUpdateJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val repository: AnimeRepository = Injekt.get()
    private val sourceManager: AnimeSourceManager = Injekt.get()

    override suspend fun doWork(): Result {
        val favorites = repository.getFavorites()
        if (favorites.isEmpty()) return Result.success()

        // Extensions load in the background after app start
        var tries = 0
        while (sourceManager.getCatalogueSources().isEmpty() && tries < 40) {
            delay(250)
            tries++
        }

        val done = AtomicInteger(0)
        val newEpisodes = AtomicInteger(0)
        val updatedTitles = mutableListOf<String>()
        val semaphore = Semaphore(5)

        showProgress(0, favorites.size)
        try {
            coroutineScope {
                favorites.groupBy { it.source }.values.map { animes ->
                    async {
                        semaphore.withPermit {
                            animes.forEach { anime ->
                                val source = sourceManager.get(anime.source)
                                if (source != null) {
                                    try {
                                        val count = repository.refreshFromSource(anime, source)
                                        if (count > 0) {
                                            newEpisodes.addAndGet(count)
                                            synchronized(updatedTitles) { updatedTitles += anime.title }
                                        }
                                    } catch (e: Throwable) {
                                        if (e is CancellationException) throw e
                                        logcat(LogPriority.ERROR, e) { "Failed to update ${anime.title}" }
                                    }
                                }
                                showProgress(done.incrementAndGet(), favorites.size)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            context.cancelNotification(Notifications.ID_ANIME_LIBRARY_PROGRESS)
        }

        if (newEpisodes.get() > 0) {
            showNewEpisodes(newEpisodes.get(), updatedTitles)
        }

        // For the library's "Airing time" sorting; done last since it takes a while and nothing waits for it
        AnimeAiringTimeFetcher(repository).refresh(repository.getFavorites())
        return Result.success()
    }

    private fun showProgress(current: Int, total: Int) {
        context.notify(Notifications.ID_ANIME_LIBRARY_PROGRESS, Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(context.stringResource(KMR.strings.anime_updating_library))
            setContentText("$current / $total")
            setProgress(total, current, false)
            setOnlyAlertOnce(true)
            setOngoing(true)
        }
    }

    private fun showNewEpisodes(count: Int, titles: List<String>) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        context.notify(Notifications.ID_NEW_EPISODES, Notifications.CHANNEL_NEW_CHAPTERS) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle(context.pluralStringResource(KMR.plurals.anime_new_episodes, count, count))
            setContentText(titles.joinToString(", "))
            setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString("\n")))
            setAutoCancel(true)
            pendingIntent?.let { setContentIntent(it) }
        }
    }

    companion object {
        private const val WORK_NAME = "AnimeLibraryUpdate"

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
                .addTag(WORK_NAME)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
