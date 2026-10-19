package eu.kanade.tachiyomi.util

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException

/**
 * Serves downloaded (content:// or file://) episodes over http on the local network, so a
 * Chromecast can play them. Started by [eu.kanade.tachiyomi.ui.player.cast.CastMediaBuilder].
 */
class LocalHttpServerService : Service() {

    companion object {
        const val CHANNEL_ID = "LocalHttpServerChannel"

        // KMK: 1 is the app updater's notification id
        const val NOTIFICATION_ID = -802
        const val ACTION_START = "eu.kanade.tachiyomi.ACTION_START_SERVER"
        const val ACTION_STOP = "eu.kanade.tachiyomi.ACTION_STOP_SERVER"

        fun stop() {
            try {
                val context = Injekt.get<Application>()
                // KMK: stopService, so stopping a server that isn't running doesn't start it first
                context.stopService(Intent(context, LocalHttpServerService::class.java))
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
            }
        }
    }

    private val prefserver: LocalHttpServerHolder by injectLazy()
    private var server: LocalHttpServer? = null

    override fun onCreate() {
        super.onCreate()

        val port = prefserver.port().get()
        server = try {
            LocalHttpServer(port, contentResolver).also { it.start() }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Error starting local HTTP server" }
            null
        } catch (e: NumberFormatException) {
            logcat(LogPriority.ERROR, e) { "Invalid local HTTP server port: $port" }
            null
        }
        if (server == null) {
            stopSelf()
            return
        }
        logcat(LogPriority.DEBUG) { "Local HTTP server started at $port" }

        createNotificationChannel()

        val stopIntent = Intent(this, LocalHttpServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(stringResource(MR.strings.app_name))
            .setContentText(stringResource(AMR.strings.server_local_notification_casting))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .addAction(
                R.drawable.ic_close_24dp,
                stringResource(SYMR.strings.action_stop),
                pendingStopIntent,
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || server == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        logcat(LogPriority.DEBUG) { "Stopping local HTTP server" }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Creates the notification channel (required for Android Oreo or higher).
     */
    private fun createNotificationChannel() {
        val name = stringResource(MR.strings.app_name)
        val descriptionText = stringResource(AMR.strings.server_local_notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
