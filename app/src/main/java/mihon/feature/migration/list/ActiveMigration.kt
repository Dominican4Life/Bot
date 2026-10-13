package mihon.feature.migration.list

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.BotCheckNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/**
 * KMK: The migration that's running, kept here so it carries on in the background after the back arrow, and the
 * matching screen can come back to it from its notification.
 */
object ActiveMigration {

    /** The notification's "open the migration" action, handled by MainActivity */
    const val ACTION_SHOW = "app.komikku.SHOW_MIGRATION"

    @Volatile
    var model: MigrationListScreenModel? = null
        private set

    /** A new migration replaces (and stops) any one still running */
    fun start(newModel: MigrationListScreenModel) {
        val old = model
        model = newModel
        if (old != null && old !== newModel) old.stop()
    }

    fun clear(finished: MigrationListScreenModel, context: Context) {
        if (model === finished) {
            model = null
            cancelDoneNotification(context)
        }
    }

    /** Opens the app on the matching screen */
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

    /** Stops the migration from its notification */
    fun cancelIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CANCEL,
        Intent(context, CancelReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Matching finished while the screen wasn't showing: tap to come back and pick Migrate or Copy */
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
        NotificationManagerCompat.from(context).notify(Notifications.ID_MIGRATION_DONE, notification)
    }

    fun cancelDoneNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(Notifications.ID_MIGRATION_DONE)
    }

    /** The Cancel button on the migration's notifications */
    class CancelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            model?.stop()
            MigrationProgress.clear()
            cancelDoneNotification(context)
        }
    }

    private const val REQUEST_SHOW = 7101
    private const val REQUEST_CANCEL = 7102
}
