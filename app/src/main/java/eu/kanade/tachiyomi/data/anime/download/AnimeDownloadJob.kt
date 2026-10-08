package eu.kanade.tachiyomi.data.anime.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: Runs the anime download queue in the background, with a progress notification. */
class AnimeDownloadJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val manager: AnimeDownloadManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_ANIME_DOWNLOAD_PROGRESS,
            progressNotification(applicationContext.stringResource(KMR.strings.anime_downloader_title), null, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        try {
            var lastUpdate = 0L
            manager.runQueue { item, percent, remaining ->
                val now = System.currentTimeMillis()
                // Don't flood the notification shade
                if (now - lastUpdate < 700 && percent in 1..99) return@runQueue
                lastUpdate = now
                applicationContext.notify(
                    Notifications.ID_ANIME_DOWNLOAD_PROGRESS,
                    progressNotification(
                        applicationContext.stringResource(
                            KMR.strings.anime_download_progress,
                            item.anime.title,
                            item.episode.name,
                        ),
                        applicationContext.stringResource(KMR.strings.anime_download_queued, remaining),
                        percent,
                    ),
                )
            }
        } finally {
            applicationContext.cancelNotification(Notifications.ID_ANIME_DOWNLOAD_PROGRESS)
        }
        // Tell about failures once the queue is done
        val failed = manager.lastFailures()
        if (failed.isNotEmpty()) {
            applicationContext.notify(Notifications.ID_ANIME_DOWNLOAD_ERROR, Notifications.CHANNEL_DOWNLOADER_ERROR) {
                setSmallIcon(android.R.drawable.stat_sys_warning)
                setContentTitle(
                    applicationContext.stringResource(KMR.strings.anime_download_failed, failed.first().episode.name),
                )
                setContentText(failed.first().error)
                setAutoCancel(true)
            }
        }
        return Result.success()
    }

    private fun progressNotification(title: String, text: String?, percent: Int) =
        applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(title)
            text?.let { setContentText(it) }
            setOnlyAlertOnce(true)
            setOngoing(true)
            setProgress(100, percent, percent <= 0)
            setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    Intent(applicationContext, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }.build()

    companion object {
        private const val WORK_NAME = "AnimeDownloader"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnimeDownloadJob>()
                .addTag(WORK_NAME)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** Status of one episode, for the episode rows */
fun Download.State.isActive() = this == Download.State.QUEUE || this == Download.State.DOWNLOADING
