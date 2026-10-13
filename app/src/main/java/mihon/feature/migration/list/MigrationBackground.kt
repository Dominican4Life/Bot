package mihon.feature.migration.list

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/**
 * KMK: What a migration is doing right now, for its notification. Set by [MigrationListScreenModel] while it searches
 * and while it moves titles; null when no migration runs.
 */
object MigrationProgress {

    data class Status(
        /** False while looking for each title on the new sources, true while moving them */
        val moving: Boolean,
        val done: Int,
        val total: Int,
        /** Set while waiting because a site asked to slow down */
        val pausedSeconds: Int? = null,
    )

    private val _state = MutableStateFlow<Status?>(null)
    val state: StateFlow<Status?> = _state.asStateFlow()

    fun update(status: Status) {
        _state.value = status
    }

    fun clear() {
        _state.value = null
    }
}

/** True when an error means the site wants fewer requests: too many requests, a bot check or a block */
fun isSlowDownError(e: Throwable): Boolean {
    if (e is HttpException) return e.code == 429 || e.code == 403 || e.code == 503
    val text = generateSequence(e) { it.cause }.take(4).joinToString(" ") { it.message.orEmpty() }
    return SLOW_DOWN.containsMatchIn(text)
}

private val SLOW_DOWN = Regex(
    "\\b(429|403|503)\\b|too many requests|rate.?limit|cloudflare|captcha|bot check|challenge",
    RegexOption.IGNORE_CASE,
)

/**
 * KMK: Keeps the app running while a migration searches or moves titles, so it carries on while you use other
 * screens or other apps, and shows its progress in a notification. The work itself stays in the migration screen.
 */
class MigrationProgressJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (MigrationProgress.state.value == null) return Result.success()
        setForegroundSafely()
        val manager = NotificationManagerCompat.from(context)
        MigrationProgress.state
            .takeWhile { it != null }
            .collect { status ->
                if (status != null) {
                    runCatching { manager.notify(Notifications.ID_MIGRATION_PROGRESS, notification(status)) }
                }
            }
        // Wait for the last update to be cleared before the notification goes
        MigrationProgress.state.first { it == null }
        manager.cancel(Notifications.ID_MIGRATION_PROGRESS)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val status = MigrationProgress.state.value ?: MigrationProgress.Status(moving = false, done = 0, total = 0)
        return ForegroundInfo(
            Notifications.ID_MIGRATION_PROGRESS,
            notification(status),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notification(status: MigrationProgress.Status) =
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            val total = status.total
            val done = status.done.coerceAtMost(total)
            setContentTitle(
                context.stringResource(
                    if (status.moving) KMR.strings.migrate_notif_migrating else KMR.strings.migrate_notif_matching,
                    done,
                    total,
                ),
            )
            status.pausedSeconds?.let {
                setContentText(context.stringResource(KMR.strings.migrate_notif_slow_down, it))
            }
            setProgress(total.coerceAtLeast(1), done, total == 0)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Tap: back to the matching screen. Cancel: stops the migration
            setContentIntent(ActiveMigration.showIntent(context))
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(KMR.strings.migrate_stop),
                ActiveMigration.cancelIntent(context),
            )
        }.build()

    companion object {
        private const val TAG = "MigrationProgress"

        /** Starts the notification and keeps the app running until the migration clears its progress */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<MigrationProgressJob>().addTag(TAG).build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
