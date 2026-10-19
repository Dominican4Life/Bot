package mihon.feature.migration.anime

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import eu.kanade.tachiyomi.data.notification.BotCheckNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import mihon.feature.migration.list.MigrationProgress
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/**
 * KMK: What an anime migration is doing right now, for its notification, like [MigrationProgress] for manga (the
 * two can run at the same time). Null when no anime migration runs.
 */
object AnimeMigrationProgress {

    private val _state = MutableStateFlow<MigrationProgress.Status?>(null)
    val state: StateFlow<MigrationProgress.Status?> = _state.asStateFlow()

    fun update(status: MigrationProgress.Status) {
        _state.value = status
    }

    fun clear() {
        _state.value = null
    }
}

/**
 * KMK: Keeps the app running while an anime migration searches or moves titles and shows its progress, like
 * [mihon.feature.migration.list.MigrationProgressJob] for manga.
 */
class AnimeMigrationProgressJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (AnimeMigrationProgress.state.value == null) return Result.success()
        setForegroundSafely()
        val manager = NotificationManagerCompat.from(context)
        AnimeMigrationProgress.state
            .takeWhile { it != null }
            .collect { status ->
                if (status != null) {
                    runCatching { manager.notify(Notifications.ID_ANIME_MIGRATION_PROGRESS, notification(status)) }
                }
            }
        AnimeMigrationProgress.state.first { it == null }
        manager.cancel(Notifications.ID_ANIME_MIGRATION_PROGRESS)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val status = AnimeMigrationProgress.state.value
            ?: MigrationProgress.Status(moving = false, done = 0, total = 0)
        return ForegroundInfo(
            Notifications.ID_ANIME_MIGRATION_PROGRESS,
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
            setContentIntent(ActiveAnimeMigration.showIntent(context))
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(KMR.strings.migrate_stop),
                ActiveAnimeMigration.cancelIntent(context),
            )
        }.build()

    companion object {
        private const val TAG = "AnimeMigrationProgress"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnimeMigrationProgressJob>().addTag(TAG).build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * KMK: The anime migration that's running, kept so it carries on after the back arrow and its notification can
 * bring the matching screen back, like [mihon.feature.migration.list.ActiveMigration] for manga.
 */
object ActiveAnimeMigration {

    /** The notification's "open the migration" action, handled by MainActivity */
    const val ACTION_SHOW = "app.komikku.SHOW_ANIME_MIGRATION"

    @Volatile
    var model: AnimeMigrationListScreenModel? = null
        private set

    fun start(newModel: AnimeMigrationListScreenModel) {
        val old = model
        model = newModel
        if (old != null && old !== newModel) old.stop()
    }

    fun clear(finished: AnimeMigrationListScreenModel, context: Context) {
        if (model === finished) {
            model = null
            cancelDoneNotification(context)
        }
    }

    fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CANCEL,
        Intent(context, CancelReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission") // checked by canNotify
    fun showDoneNotification(context: Context) {
        if (!BotCheckNotifier.canNotify(context)) return
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_done_24dp)
            setContentTitle(context.stringResource(KMR.strings.migrate_notif_done))
            setContentText(context.stringResource(KMR.strings.migrate_notif_done_text))
            setContentIntent(showIntent(context))
            addAction(R.drawable.ic_close_24dp, context.stringResource(KMR.strings.migrate_stop), cancelIntent(context))
            setAutoCancel(true)
        }.build()
        NotificationManagerCompat.from(context).notify(Notifications.ID_ANIME_MIGRATION_DONE, notification)
    }

    fun cancelDoneNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(Notifications.ID_ANIME_MIGRATION_DONE)
    }

    /** The Cancel button on the anime migration's notifications */
    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            model?.stop()
            AnimeMigrationProgress.clear()
            cancelDoneNotification(context)
        }
    }

    private const val REQUEST_SHOW = 7111
    private const val REQUEST_CANCEL = 7112
}
