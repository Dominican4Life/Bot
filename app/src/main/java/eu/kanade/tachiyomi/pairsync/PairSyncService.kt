package eu.kanade.tachiyomi.pairsync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.pairsync.PairSyncPopup
import eu.kanade.tachiyomi.util.system.buildNotificationChannel
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK (r93): keeps the app running while a pair sync moves data or applies it (a foreground service of type
 * dataSync), and shows the sync in notifications: waiting for the other device, the transfer with Stop, applying,
 * the other device's request (Accept / Decline) when the Sync pop-up isn't showing, and the result. Every
 * notification opens the Sync pop-up. The notifications come from [PairSyncNotifier], which also runs without the
 * service (Android 12+ may refuse to start one from the background; then the notifications are plain ones).
 */
class PairSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // KMK (r93): every startForegroundService needs its startForeground, even when the sync just ended
        PairSyncNotifier.createChannels(this)
        val started = try {
            val notification = PairSyncNotifier.foregroundNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID_PROGRESS, notification)
            }
            // KMK (r93): only now may stopService run (stopped before startForeground = the app is crashed)
            running = true
            true
        } catch (e: Exception) {
            false
        }
        if (!started || !PairSyncNotifier.isActive(PairSync.status.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        PairSyncNotifier.ensure(applicationContext)
        return START_NOT_STICKY
    }

    /** KMK (r93): Android 15+ ends a dataSync service after 6 h; not stopping it then crashes the app */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /** KMK (r93): the notifications' Stop / Cancel, Accept and Decline (not exported; explicit intents only) */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val accept = intent.action == PairSyncService.ACTION_ACCEPT
            val app = context.applicationContext
            when (intent.action) {
                PairSyncService.ACTION_STOP -> {
                    PairSync.stopSync()
                    // KMK (r93): a tap on the notification lets the app start its foreground service for a little
                    // while: what came in full is applied right after a Stop (an idle service stops itself)
                    PairSyncNotifier.startForegroundNow(app)
                }
                PairSyncService.ACTION_ACCEPT, PairSyncService.ACTION_DECLINE -> {
                    val id = intent.getLongExtra(PairSyncService.EXTRA_REQUEST, -1L)
                    context.cancelNotification(PairSyncService.ID_REQUEST)
                    if (id >= 0L) PairSync.answerIncoming(id, accept = accept)
                    // A tap on the notification lets the app start its foreground service for a little while
                    if (accept) PairSyncNotifier.startForegroundNow(app)
                }
            }
        }
    }

    companion object {
        /** MainActivity opens the Sync pop-up for an intent with this action */
        const val ACTION_OPEN = "eu.kanade.tachiyomi.pairsync.OPEN"

        internal const val ACTION_STOP = "eu.kanade.tachiyomi.pairsync.STOP"
        internal const val ACTION_ACCEPT = "eu.kanade.tachiyomi.pairsync.ACCEPT"
        internal const val ACTION_DECLINE = "eu.kanade.tachiyomi.pairsync.DECLINE"
        internal const val EXTRA_REQUEST = "request"

        internal const val CHANNEL_PROGRESS = "pair_sync_channel"
        internal const val CHANNEL_REQUEST = "pair_sync_request_channel"

        // KMK (r93): ids no other notification uses (Notifications.kt goes down to -1813)
        internal const val ID_PROGRESS = -1901
        internal const val ID_REQUEST = -1902
        internal const val ID_RESULT = -1903

        /** KMK (r93): the service is up and has called startForeground (safe to stop) */
        @Volatile internal var running = false

        /**
         * Called by the core whenever a sync leaves Idle and when a request comes in: the notifications follow the
         * sync from then on, and the foreground service runs while data moves or is applied. Never throws.
         */
        fun start(context: Context) {
            val app = context.applicationContext
            runCatching { PairSyncNotifier.ensure(app) }
            if (PairSyncNotifier.isActive(PairSync.status.value)) PairSyncNotifier.startForeground(app)
        }

        /** Starts the service; Android 12+ refuses from the background (ForegroundServiceStartNotAllowedException) */
        internal fun startService(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, PairSyncService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        internal fun stopService(context: Context) {
            runCatching { context.stopService(Intent(context, PairSyncService::class.java)) }
        }
    }
}

/**
 * KMK (r93): follows [PairSync.status], [PairSync.incoming], [PairSync.result] and the pop-up's visibility, and keeps
 * the sync's notifications up to date (at most every 500 ms). It runs from [PairSyncService.start] until the sync is
 * Idle with no request waiting.
 */
internal object PairSyncNotifier {

    private const val EVERY = 500L

    /** How long a fresh start waits for the core's phase before it may end (the core may set it just after) */
    private const val GRACE = 3_000L

    /** How long Idle must last before it ends (the result can come just after Idle) */
    private const val IDLE_FOR = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var job: Job? = null

    @Volatile private var lastStart = 0L

    @Volatile private var foregroundTried = false

    /** KMK (r93): the last error announced ([PairSync.Status.errorTime]); kept between runs, so each shows once */
    @Volatile private var announcedError = 0L

    private val names = ConcurrentHashMap<String, String>()

    private data class Snapshot(
        val status: PairSync.Status,
        val incoming: IncomingRequest?,
        val result: PairSync.SyncResult?,
        val pending: PendingApply?,
        val visible: Boolean,
    )

    fun isActive(status: PairSync.Status): Boolean = status.phase != SyncPhase.Idle

    fun ensure(context: Context) {
        synchronized(lock) {
            lastStart = System.currentTimeMillis()
            if (job?.isActive == true) return
            job = scope.launch { runCatching { follow(context) } }
        }
    }

    /** KMK (r93): tried again on later changes when Android refused it (an app in the background) */
    fun startForeground(context: Context) {
        if (foregroundTried) return
        foregroundTried = PairSyncService.startService(context)
    }

    /** KMK (r93): right after a notification tap (Android allows a foreground service start for a little while) */
    fun startForegroundNow(context: Context) {
        // The notifications follow again (they reset this once the sync is idle)
        runCatching { ensure(context) }
        if (PairSyncService.startService(context)) foregroundTried = true
    }

    /** KMK (r93): the other device's request can show as a notification (the app's and the channel's are on) */
    fun requestsCanShow(context: Context): Boolean = runCatching {
        val manager = NotificationManagerCompat.from(context)
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannelCompat(PairSyncService.CHANNEL_REQUEST)?.importance !=
            NotificationManagerCompat.IMPORTANCE_NONE
    }.getOrDefault(true)

    /** The service's first notification (the current state) */
    fun foregroundNotification(context: Context): Notification {
        val snapshot = snapshot()
        return progressNotification(context, snapshot)
            ?: context.notificationBuilder(PairSyncService.CHANNEL_PROGRESS) {
                setSmallIcon(R.drawable.ic_sync_24dp)
                setContentTitle("Sync devices")
                setOngoing(true)
                setOnlyAlertOnce(true)
                setContentIntent(openIntent(context))
            }.build()
    }

    private fun snapshot() = Snapshot(
        status = PairSync.status.value,
        incoming = PairSync.incoming.value,
        result = PairSync.result.value,
        pending = PairSync.pendingApply.value,
        visible = PairSyncPopup.visible.value,
    )

    private suspend fun follow(context: Context) {
        createChannels(context)
        var last: Snapshot? = null
        // Only a result that comes after this start is announced (KMK (r93): errors by their time, see below)
        var resultTime = PairSync.result.value?.time
        var requestShown: Long? = null
        var idleSince = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val snap = snapshot()
            val active = isActive(snap.status)
            if (snap != last) {
                last = snap
                // Progress (waiting, transferring, applying)
                val progress = if (active) progressNotification(context, snap) else null
                if (progress != null) {
                    context.notify(PairSyncService.ID_PROGRESS, progress)
                    if (!foregroundTried) startForeground(context)
                } else {
                    context.cancelNotification(PairSyncService.ID_PROGRESS)
                }
                // The other device's request, while the pop-up isn't showing
                val request = snap.incoming
                if (request != null && !snap.visible) {
                    if (requestShown != request.id) {
                        requestShown = request.id
                        context.notify(PairSyncService.ID_REQUEST, requestNotification(context, request))
                    }
                } else if (requestShown != null) {
                    requestShown = null
                    context.cancelNotification(PairSyncService.ID_REQUEST)
                }
                // The result, or why it stopped
                val result = snap.result
                if (result != null && result.time != resultTime) {
                    resultTime = result.time
                    context.notify(PairSyncService.ID_RESULT, resultNotification(context, result))
                }
                // KMK (r93): each error once, also one from before this loop started (a sync that failed at once);
                // not while the pop-up shows it, nor for the other user's Decline / Cancel
                val newError = snap.status.lastError
                if (newError != null && snap.status.errorTime > announcedError) {
                    announcedError = snap.status.errorTime
                    if (!snap.visible && !snap.status.errorByUser) {
                        context.notify(PairSyncService.ID_RESULT, errorNotification(context, snap, newError))
                    }
                }
            }
            if (active || snap.incoming != null) {
                idleSince = 0L
            } else {
                if (idleSince == 0L) idleSince = now
                if (foregroundTried) {
                    foregroundTried = false
                    // KMK (r93): a service that hasn't reached startForeground yet stops itself there (it sees Idle)
                    if (PairSyncService.running) PairSyncService.stopService(context)
                }
            }
            if (idleSince > 0L && now - idleSince >= IDLE_FOR) {
                val end = synchronized(lock) {
                    (now - lastStart >= GRACE).also { if (it) job = null }
                }
                if (end) break
            }
            delay(EVERY)
        }
        context.cancelNotification(PairSyncService.ID_PROGRESS)
        if (requestShown != null) context.cancelNotification(PairSyncService.ID_REQUEST)
    }

    /** The two channels (made before any notification; startForeground with a missing channel crashes the app) */
    fun createChannels(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).createNotificationChannelsCompat(
                listOf(
                    buildNotificationChannel(
                        PairSyncService.CHANNEL_PROGRESS,
                        NotificationManagerCompat.IMPORTANCE_LOW,
                    ) {
                        setName("Sync devices")
                        setShowBadge(false)
                    },
                    buildNotificationChannel(
                        PairSyncService.CHANNEL_REQUEST,
                        NotificationManagerCompat.IMPORTANCE_HIGH,
                    ) {
                        setName("Sync requests")
                    },
                ),
            )
        }
    }

    private fun peerName(snap: Snapshot): String {
        val address = snap.status.peerAddress
        val known = address?.let { a ->
            names[a] ?: PairSyncPreferences.instance.peers().firstOrNull { it.address == a }?.name?.also {
                names[a] = it
            }
        }
        return known
            ?: snap.incoming?.takeIf { it.peerAddress == address }?.peerName
            ?: snap.pending?.peerName
            ?: "the other device"
    }

    /** The ongoing notification for the phase, or null when Idle */
    private fun progressNotification(context: Context, snap: Snapshot): Notification? {
        val status = snap.status
        val peer = peerName(snap)
        val builder = context.notificationBuilder(PairSyncService.CHANNEL_PROGRESS) {
            setSmallIcon(R.drawable.ic_sync_24dp)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            setCategory(NotificationCompat.CATEGORY_PROGRESS)
            setContentIntent(openIntent(context))
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        when (status.phase) {
            SyncPhase.Asking, SyncPhase.Waiting -> {
                // KMK (r93): this device asked ("Waiting for X to accept…"), or X asked this one
                val title = when {
                    status.phase == SyncPhase.Asking -> "Waiting for $peer to accept…"
                    snap.incoming != null -> "$peer wants to sync"
                    else -> "Connecting to $peer…"
                }
                builder.setContentTitle(title)
                builder.setProgress(0, 0, true)
                builder.addAction(
                    R.drawable.ic_close_24dp,
                    "Cancel",
                    actionIntent(context, PairSyncService.ACTION_STOP),
                )
            }
            SyncPhase.Transferring -> {
                val detail = transferText(status)
                builder.setContentTitle("Syncing with $peer")
                builder.setContentText(detail)
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                val fraction = transferFraction(status)
                if (fraction == null) {
                    builder.setProgress(0, 0, true)
                } else {
                    builder.setProgress(1000, (fraction * 1000).toInt().coerceIn(0, 1000), false)
                }
                builder.addAction(
                    R.drawable.ic_close_24dp,
                    "Stop",
                    actionIntent(context, PairSyncService.ACTION_STOP),
                )
            }
            SyncPhase.Applying -> {
                val name = snap.pending?.peerName ?: peer
                val detail = status.applyText ?: "Applying…"
                builder.setContentTitle("Applying sync from $name")
                builder.setContentText(detail)
                if (status.applyTotal > 0) {
                    builder.setProgress(status.applyTotal, status.applyDone.coerceIn(0, status.applyTotal), false)
                } else {
                    builder.setProgress(0, 0, true)
                }
                // KMK (r93): the rest is applied the next time the app opens
                builder.addAction(
                    R.drawable.ic_close_24dp,
                    "Stop",
                    actionIntent(context, PairSyncService.ACTION_STOP),
                )
            }
            // Idle
            else -> return null
        }
        return builder.build()
    }

    /** "Covers 812 of 1,520 · 22 MB/s", or "Receiving 12.3 of 40.0 MB · 2.1 MB/s", or the step */
    private fun transferText(status: PairSync.Status): String {
        val speed = status.bytesPerSecond.takeIf { it > 0L }?.let { " · ${PairSyncData.sizeText(it)}/s" }.orEmpty()
        val part = status.parts.firstOrNull { !it.done } ?: status.parts.lastOrNull()
        return when {
            part != null && status.parts.size > 1 -> "${part.name} ${part.detail}$speed"
            status.total > 0L -> "${if (status.sending) "Sending" else "Receiving"} " +
                "${mb(status.done)} of ${mb(status.total)} MB$speed"
            else -> status.step ?: "Syncing…"
        }
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f)

    /** Done so far (0..1): the parts on average while covers and downloads move, else the bytes; null = unknown */
    private fun transferFraction(status: PairSync.Status): Float? = when {
        status.parts.size > 1 -> status.parts.map { if (it.done) 1f else it.fraction.coerceIn(0f, 1f) }.average()
            .toFloat()
        status.total > 0L -> (status.done.toFloat() / status.total.toFloat()).coerceIn(0f, 1f)
        else -> null
    }

    private fun requestNotification(context: Context, request: IncomingRequest): Notification {
        val text = buildString {
            if (request.youGet.isNotEmpty()) {
                append(request.youGet.joinToString("\n") { "${it.title}: ${it.detail}" })
            }
            if (request.youSend.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("You send: ")
                append(request.youSend.joinToString(", ") { it.title })
            }
            request.sizeText?.let {
                if (isNotEmpty()) append("\n")
                append(it)
            }
        }
        val left = request.expiresAt - System.currentTimeMillis()
        return context.notificationBuilder(PairSyncService.CHANNEL_REQUEST) {
            setSmallIcon(R.drawable.ic_sync_24dp)
            setContentTitle("${request.peerName} wants to send you")
            setContentText(text.substringBefore('\n'))
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            setAutoCancel(true)
            setOnlyAlertOnce(true)
            if (left > 0L) setTimeoutAfter(left)
            setContentIntent(openIntent(context))
            addAction(
                R.drawable.ic_close_24dp,
                "Decline",
                actionIntent(context, PairSyncService.ACTION_DECLINE, request.id),
            )
            addAction(
                R.drawable.ic_sync_24dp,
                "Accept",
                actionIntent(context, PairSyncService.ACTION_ACCEPT, request.id),
            )
        }.build()
    }

    private fun resultNotification(context: Context, result: PairSync.SyncResult): Notification {
        val text = "Sent: ${result.summary.sentText()}\nGot: ${result.summary.receivedText()}"
        return context.notificationBuilder(PairSyncService.CHANNEL_PROGRESS) {
            setSmallIcon(R.drawable.ic_sync_24dp)
            setContentTitle("Synced with ${result.peerName}")
            setContentText(text.substringBefore('\n'))
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setAutoCancel(true)
            setContentIntent(openIntent(context))
        }.build()
    }

    private fun errorNotification(context: Context, snap: Snapshot, error: String): Notification =
        context.notificationBuilder(PairSyncService.CHANNEL_PROGRESS) {
            setSmallIcon(R.drawable.ic_sync_24dp)
            setContentTitle("Sync stopped: $error")
            setContentText(peerName(snap))
            setStyle(NotificationCompat.BigTextStyle().bigText("Sync stopped: $error"))
            setAutoCancel(true)
            setContentIntent(openIntent(context))
        }.build()

    /** Opens the app with the Sync pop-up showing */
    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = PairSyncService.ACTION_OPEN
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, requestId: Long = -1L): PendingIntent {
        val intent = Intent(context, PairSyncService.ActionReceiver::class.java).apply {
            this.action = action
            putExtra(PairSyncService.EXTRA_REQUEST, requestId)
        }
        val code = when (action) {
            PairSyncService.ACTION_ACCEPT -> REQUEST_ACCEPT
            PairSyncService.ACTION_DECLINE -> REQUEST_DECLINE
            else -> REQUEST_STOP
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_OPEN = 1901
    private const val REQUEST_STOP = 1902
    private const val REQUEST_ACCEPT = 1903
    private const val REQUEST_DECLINE = 1904
}
