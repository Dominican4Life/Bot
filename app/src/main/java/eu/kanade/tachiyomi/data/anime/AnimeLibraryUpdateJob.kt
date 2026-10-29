package eu.kanade.tachiyomi.data.anime

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.PauseBetweenTitles
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK: Checks the anime in the library for new episodes. Runs with the manga library update (unless the anime
 * update has its own schedule in Settings › Library › Anime), on its own schedule, or from a manual refresh.
 *
 * Skips anime as set in the anime update settings (completed, unseen episodes, not started, categories, smart
 * update) and can queue the new episodes for download afterwards.
 */
class AnimeLibraryUpdateJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val repository: AnimeRepository = Injekt.get()
    private val sourceManager: AnimeSourceManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_ANIME_LIBRARY_PROGRESS,
            progressNotification(0, 0, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override suspend fun doWork(): Result {
        val libraryPreferences = Injekt.get<LibraryPreferences>()
        if (tags.contains(WORK_NAME_AUTO)) {
            // Android 8 can't hold the Wi-Fi rule in the work constraints
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val restrictions = libraryPreferences.autoUpdateDeviceRestrictions().get()
                if (DEVICE_ONLY_ON_WIFI in restrictions && !context.isConnectedToWifi()) return Result.retry()
            }
            // A manual update is running: try again later
            if (context.workManager.isRunning(WORK_NAME)) return Result.retry()
        } else if (context.workManager.isRunning(WORK_NAME_AUTO)) {
            // The scheduled update is already checking everything
            return Result.success()
        }

        val allFavorites = repository.getFavorites()
        if (allFavorites.isEmpty()) return Result.success()

        // KMK: "Update category" in the anime library checks just that page's anime
        val onlyIds = inputData.getLongArray(KEY_ANIME_IDS)?.toSet()
        val favorites = if (onlyIds != null) {
            filterForUpdate(allFavorites.filter { it.id in onlyIds }, ignoreCategories = true)
        } else {
            filterForUpdate(allFavorites)
        }
        val skipped = allFavorites.size - favorites.size
        if (favorites.isEmpty()) return Result.success()

        setForegroundSafely()

        // Extensions load in the background after app start
        var tries = 0
        while (sourceManager.getCatalogueSources().isEmpty() && tries < 40) {
            delay(250)
            tries++
        }

        val downloadCheck = newEpisodesDownloadCheck()

        val done = AtomicInteger(0)
        // KMK (r88): titles that failed, anime whose source isn't installed and "no episodes" answers are counted
        // apart; before, a missing source counted as checked and a kept-episodes answer as a crash-level failure
        val failed = mutableListOf<String>()
        val noSource = AtomicInteger(0)
        val noEpisodes = AtomicInteger(0)
        val updated = mutableListOf<Pair<Anime, Int>>()
        val semaphore = Semaphore(5)
        val pauseEnabled = libraryPreferences.updatePauseEnabled().get()
        val pauseRange = libraryPreferences.updatePause().get()

        showProgress(0, favorites.size, null)
        try {
            coroutineScope {
                favorites.groupBy { it.source }.values.map { animes ->
                    async {
                        semaphore.withPermit {
                            // "Pause between titles" counts between titles of the same source
                            var updatedInSource = 0
                            animes.forEach { anime ->
                                val source = sourceManager.get(anime.source)
                                if (source == null) {
                                    noSource.incrementAndGet()
                                } else {
                                    if (updatedInSource++ > 0) PauseBetweenTitles.pause(pauseEnabled, pauseRange)
                                    showProgress(done.get(), favorites.size, anime.title)
                                    try {
                                        val autoDownload = downloadCheck(anime)
                                        // KMK (r88): only the really new episodes; rows carried over after the source
                                        // changed its links get new ids but aren't new (they were auto-downloaded
                                        // again when this compared ids with the ones saved before)
                                        val result = repository.refreshFromSourceResult(anime, source)
                                        val count = result.newCount
                                        if (count > 0) {
                                            synchronized(updated) { updated += anime to count }
                                            if (autoDownload) downloadNewEpisodes(anime, result.newEpisodeIds.toSet())
                                        }
                                    } catch (e: AnimeRepository.NoEpisodesException) {
                                        // KMK (r88): a skip with a warning (AnimeRepository logs it), not a failure
                                        noEpisodes.incrementAndGet()
                                    } catch (e: Throwable) {
                                        if (e is CancellationException) throw e
                                        synchronized(failed) { failed += anime.title }
                                        logcat(LogPriority.ERROR, e) { "Failed to update ${anime.title}" }
                                    }
                                }
                                showProgress(done.incrementAndGet(), favorites.size, null)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            context.cancelNotification(Notifications.ID_ANIME_LIBRARY_PROGRESS)
        }

        val checked = favorites.size - noSource.get()
        logcat(LogPriority.INFO, tag = "anime") {
            "Anime update: $checked checked, $skipped skipped, ${noSource.get()} without their source, " +
                "${noEpisodes.get()} with no episodes from the source, ${failed.size} failed, " +
                "${updated.size} with new episodes"
        }
        if (updated.isNotEmpty()) {
            showNewEpisodes(updated.sortedBy { it.first.title.lowercase() }, failed.size)
        }
        // KMK (r88): failures used to show only inside the new-episodes notification
        if (updated.isEmpty() && failed.isNotEmpty()) {
            showFailures(failed.sortedBy { it.lowercase() })
        } else {
            context.cancelNotification(ID_ANIME_UPDATE_ERRORS)
        }
        return Result.success()
    }

    /** The favorites the update settings allow */
    private suspend fun filterForUpdate(favorites: List<Anime>, ignoreCategories: Boolean = false): List<Anime> {
        val skip = AnimePrefs.updateSkip().get()
        val included = if (ignoreCategories) emptySet() else AnimePrefs.updateCategories().get()
        val excluded = if (ignoreCategories) emptySet() else AnimePrefs.updateCategoriesExclude().get()

        val links = if (included.isNotEmpty() || excluded.isNotEmpty()) {
            repository.subscribeCategoryLinks().first()
        } else {
            emptyMap()
        }
        val stats = if (AnimePrefs.SKIP_HAS_UNSEEN in skip || AnimePrefs.SKIP_NOT_STARTED in skip) {
            repository.subscribeLibraryStats().first()
        } else {
            emptyMap()
        }
        val now = System.currentTimeMillis()

        return favorites.filter { anime ->
            if (!AnimePrefs.matchesCategories(links[anime.id].orEmpty(), included, excluded)) return@filter false
            if (AnimePrefs.SKIP_COMPLETED in skip && anime.status == SAnime.COMPLETED.toLong()) return@filter false
            val stat = stats[anime.id]
            if (AnimePrefs.SKIP_HAS_UNSEEN in skip && stat != null && stat.unseen > 0) return@filter false
            if (AnimePrefs.SKIP_NOT_STARTED in skip && stat != null && stat.total > 0 && !stat.started) {
                return@filter false
            }
            if (AnimePrefs.SKIP_SMART in skip && isBeforeExpectedRelease(repository.getEpisodes(anime.id), now)) {
                return@filter false
            }
            true
        }
    }

    /** Returns a check for whether an anime's new episodes should be downloaded */
    private suspend fun newEpisodesDownloadCheck(): (Anime) -> Boolean {
        if (!AnimePrefs.downloadNewEpisodes().get()) return { false }
        val included = AnimePrefs.downloadNewCategories().get()
        val excluded = AnimePrefs.downloadNewCategoriesExclude().get()
        if (included.isEmpty() && excluded.isEmpty()) return { true }
        val links = repository.subscribeCategoryLinks().first()
        return { anime -> AnimePrefs.matchesCategories(links[anime.id].orEmpty(), included, excluded) }
    }

    private suspend fun downloadNewEpisodes(anime: Anime, newIds: Set<Long>) {
        val fresh = repository.getEpisodes(anime.id)
            .filter { it.id in newIds && !it.seen }
            .sortedByDescending { it.sourceOrder }
        if (fresh.isEmpty()) return
        Injekt.get<AnimeDownloadManager>().enqueue(anime, fresh)
    }

    private fun showProgress(current: Int, total: Int, title: String?) {
        context.notify(Notifications.ID_ANIME_LIBRARY_PROGRESS, progressNotification(current, total, title))
    }

    private fun progressNotification(current: Int, total: Int, title: String?) =
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(context.stringResource(KMR.strings.anime_updating_library))
            if (total > 0) {
                setContentText(
                    if (title != null) {
                        context.stringResource(KMR.strings.anime_update_progress_title, current, total, title)
                    } else {
                        "$current / $total"
                    },
                )
                setProgress(total, current, false)
            } else {
                setProgress(0, 0, true)
            }
            setOnlyAlertOnce(true)
            setOngoing(true)
        }.build()

    private fun showNewEpisodes(updated: List<Pair<Anime, Int>>, failed: Int) {
        val count = updated.sumOf { it.second }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val lines = updated.map { (anime, n) ->
            context.stringResource(KMR.strings.anime_update_summary_line, anime.title, n)
        } + listOfNotNull(
            failed.takeIf { it > 0 }?.let { context.stringResource(KMR.strings.anime_update_failed_count, it) },
        )
        context.notify(Notifications.ID_NEW_EPISODES, Notifications.CHANNEL_NEW_CHAPTERS) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle(context.pluralStringResource(KMR.plurals.anime_new_episodes, count, count))
            setContentText(updated.joinToString(", ") { it.first.title })
            setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            setNumber(updated.size)
            setAutoCancel(true)
            pendingIntent?.let { setContentIntent(it) }
        }
    }

    /** KMK (r88): the anime that couldn't be updated, when no anime got new episodes */
    private fun showFailures(titles: List<String>) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        context.notify(ID_ANIME_UPDATE_ERRORS, Notifications.CHANNEL_LIBRARY_ERROR) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle(context.stringResource(KMR.strings.anime_update_failed_count, titles.size))
            setContentText(titles.joinToString(", "))
            setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString("\n")))
            setAutoCancel(true)
            pendingIntent?.let { setContentIntent(it) }
        }
    }

    companion object {
        private const val WORK_NAME = "AnimeLibraryUpdate"
        private const val WORK_NAME_AUTO = "AnimeLibraryUpdate-auto"

        // KMK (r88): outside the ids in Notifications (fork ids -1801..-1813)
        private const val ID_ANIME_UPDATE_ERRORS = -1814

        private const val DAY = 24L * 60 * 60 * 1000

        /**
         * Smart update: guesses how often the anime gets episodes (median gap between the last 5 upload days,
         * 1 to 28 days) and returns true while less than half of that has passed since the newest one.
         */
        internal fun isBeforeExpectedRelease(episodes: List<Episode>, now: Long): Boolean {
            val days = episodes.asSequence()
                .map { it.dateUpload }
                .filter { it in 1..now }
                .map { it / DAY }
                .distinct()
                .sortedDescending()
                .take(5)
                .toList()
            if (days.size < 2) return false
            val gaps = days.zipWithNext { a, b -> a - b }.sorted()
            val median = if (gaps.size % 2 == 1) {
                gaps[gaps.size / 2].toDouble()
            } else {
                (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
            }
            val interval = (median * DAY).toLong().coerceIn(DAY, 28 * DAY)
            return now < days.first() * DAY + interval / 2
        }

        private const val KEY_ANIME_IDS = "anime_ids"

        /**
         * Manual update (Updates screen, manga library refresh, anime library menu). [animeIds] limits it to those
         * anime (one page of the anime library). False when an update is already running.
         */
        fun startNow(context: Context, animeIds: List<Long>? = null): Boolean {
            if (context.workManager.isRunning(WORK_NAME)) return false
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
                .addTag(WORK_NAME)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply { if (animeIds != null) setInputData(workDataOf(KEY_ANIME_IDS to animeIds.toLongArray())) }
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        /**
         * Called when the manga library update starts. A manual manga update always checks the anime too; an
         * automatic one only when the anime update is set to run with it.
         */
        fun startWithManga(context: Context, automatic: Boolean) {
            if (!automatic || AnimePrefs.updateInterval().get() == AnimePrefs.UPDATE_WITH_MANGA) startNow(context)
        }

        /** (Re)schedules the anime update's own periodic run, with the manga update's device restrictions */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val interval = prefInterval ?: AnimePrefs.updateInterval().get()
            if (interval <= 0) {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
                return
            }
            val restrictions = Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions().get()
            val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val networkRequest = NetworkRequest.Builder().apply {
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (DEVICE_ONLY_ON_WIFI in restrictions) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }.build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkRequest(networkRequest, networkType)
                .setRequiresCharging(DEVICE_CHARGING in restrictions)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AnimeLibraryUpdateJob>(
                interval.toLong(),
                TimeUnit.HOURS,
                10,
                TimeUnit.MINUTES,
            )
                .addTag(WORK_NAME_AUTO)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniquePeriodicWork(WORK_NAME_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
