package mihon.feature.migration.list

import android.app.PendingIntent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlin.random.Random

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

/** The random pause between titles chosen in the migration options, as seconds ("1-3"), or null for none */
fun migrationDelayRange(value: String): LongRange? {
    val parts = value.split('-')
    if (parts.size != 2) return null
    val from = parts[0].toLongOrNull() ?: return null
    val to = parts[1].toLongOrNull() ?: return null
    return if (to <= 0) null else from..to
}

/** Waits a random time in the chosen range, so a site doesn't see requests at a fixed, bot-like pace */
suspend fun migrationPause(value: String) {
    val range = migrationDelayRange(value) ?: return
    delay(Random.nextLong(range.first * 1000, range.last * 1000 + 1))
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
            setContentTitle(if (status.moving) "Migrating titles" else "Finding titles on the new sources")
            setContentText(
                buildString {
                    append("${status.done} of ${status.total}")
                    status.pausedSeconds?.let { append(" · site asked to slow down, waiting ${it}s") }
                },
            )
            setProgress(status.total.coerceAtLeast(1), status.done.coerceAtMost(status.total), status.total == 0)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_PROGRESS)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                setContentIntent(
                    PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            }
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
