package eu.kanade.tachiyomi.diagnostics

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.flow.update

/**
 * KMK (r97): The notifications of the Diagnostics feature (design §2.3): the ongoing one while a recording runs
 * (Stop · Mark · Float · Thread dump), the "report ready" one when it ends (tap → Diagnostics › Report), and the
 * stall one, only when the main thread stalls three times in a minute while the app is in the foreground. Channel
 * [DiagActions.CHANNEL], registered in `Notifications.createChannels`.
 */
internal object RecordingNotification {

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, DiagActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun open(context: Context, tab: DiagTab): PendingIntent = PendingIntent.getActivity(
        context,
        tab.ordinal + 7_000,
        Intent(context, MainActivity::class.java)
            .setAction(DiagActions.OPEN)
            .putExtra(DiagActions.EXTRA_TAB, tab.name)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Shows or refreshes the ongoing notification (called once a second by the engine while recording) */
    fun showRecording(context: Context, state: RecordingState) {
        val now = System.currentTimeMillis()
        val left = state.remainingMs(now)?.let { ms -> " · ${ms / 60_000}:${"%02d".format(ms / 1000 % 60)} left" } ?: ""
        val text = "${"%,d".format(state.lines)} lines · ${"%.1f".format(state.traceBytes / (1024.0 * 1024))} MB traces$left"
        val builder = context.notificationBuilder(DiagActions.CHANNEL) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Recording diagnostics (${state.duration.label})")
            setContentText(text)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setContentIntent(open(context, DiagTab.CAPTURE))
            addAction(R.drawable.ic_close_24dp, "Stop", action(context, DiagActions.STOP_RECORDING))
            addAction(R.drawable.ic_info_24dp, "Mark", action(context, DiagActions.MARK))
            addAction(R.drawable.ic_info_24dp, "Float", action(context, DiagActions.FLOAT))
            addAction(R.drawable.ic_info_24dp, "Thread dump", action(context, DiagActions.THREAD_DUMP))
        }
        runCatching { context.notify(DiagActions.ID_RECORDING, builder.build()) }
    }

    fun hideRecording(context: Context) {
        runCatching { context.cancelNotification(DiagActions.ID_RECORDING) }
    }

    /** The recording ended and its report is built */
    fun showResult(context: Context, result: ReportResult) {
        val builder = context.notificationBuilder(DiagActions.CHANNEL) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle("Recording ended · report ready")
            setContentText("${"%.1f".format(result.bytes / (1024.0 * 1024))} MB · tap to share")
            setAutoCancel(true)
            setContentIntent(open(context, DiagTab.REPORT))
            setPriority(NotificationCompat.PRIORITY_LOW)
        }
        runCatching { context.notify(DiagActions.ID_RESULT, builder.build()) }
    }

    private val stallTimes = ArrayDeque<Long>()

    /** A main-thread stall: the notification only on the third in a minute while in the foreground */
    fun onStall(context: Context?, now: Long) {
        if (context == null || !Watchdogs.inForeground) return
        val show = synchronized(stallTimes) {
            stallTimes.addLast(now)
            while (stallTimes.isNotEmpty() && now - stallTimes.first() > 60_000L) stallTimes.removeFirst()
            stallTimes.size >= 3
        }
        if (!show) return
        val builder = context.notificationBuilder(DiagActions.CHANNEL) {
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentTitle("Komikku keeps stalling")
            setContentText(Health.lastStallText ?: "Main thread stalled repeatedly")
            setAutoCancel(true)
            setContentIntent(open(context, DiagTab.HEALTH))
            addAction(R.drawable.ic_info_24dp, "Thread dump", action(context, DiagActions.THREAD_DUMP))
        }
        runCatching { context.notify(DiagActions.ID_STALL, builder.build()) }
    }
}

/** KMK (r97): The notification buttons (and the tile's actions) land here. Not exported. */
internal class DiagActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = Diag.engine ?: return
        when (intent.action) {
            DiagActions.STOP_RECORDING -> engine.stopRecording()
            DiagActions.MARK -> engine.mark(null)
            DiagActions.FLOAT -> FloatingLogState.visible.update { !it }
            DiagActions.THREAD_DUMP -> Watchdogs.threadDumpNow()
        }
    }
}
