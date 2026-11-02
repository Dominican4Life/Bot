package eu.kanade.tachiyomi.extension.installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentSanitizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.lang.use
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.getUriSize
import eu.kanade.tachiyomi.util.system.notify
import exh.log.xLogE

class PackageInstallerInstaller(private val service: Service) : Installer(service) {

    private val packageInstaller = service.packageManager.packageInstaller

    // KMK -->
    // r105: posts the "Tap to approve" notification when Android's prompt was opened but nothing came back
    private val promptHandler = Handler(Looper.getMainLooper())

    @Volatile private var promptFallback: Runnable? = null

    // r105 fix: while Android's prompt is up, the session's progress at the time it asked; a later rise means the
    // user tapped Install and Android is installing, so the 3 min prompt timer gives way to the 60 s one
    @Volatile private var promptSession: Int? = null

    @Volatile private var promptProgress = 0f

    @Volatile private var callbackRegistered = false

    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {}

        override fun onBadgingChanged(sessionId: Int) {}

        override fun onActiveChanged(sessionId: Int, active: Boolean) {}

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            if (sessionId != promptSession) return
            val current = activeSession ?: return
            if (current.second != sessionId || progress <= promptProgress + 0.01f) return
            promptSession = null
            AppLog.add(
                'D',
                "extensions",
                "Android is installing ${Installer.labelOf(current.first)} after your OK",
                "session" to sessionId,
            )
            clearPrompt()
            armWatchdog(current.first, Installer.NO_ANSWER_MS, Installer.NO_ANSWER_REASON)
        }

        override fun onFinished(sessionId: Int, success: Boolean) {}
    }
    // KMK <--

    private val packageActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // KMK: only the session being installed now. A status from another (leftover) session used to open
            // Android's prompt for that one (another extension's update) and leave this row on "Installing".
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val active = activeSession
            if (active == null || sessionId != active.second) {
                AppLog.add('W', "extensions", "Ignored an install status for an old install attempt ($sessionId)")
                if (sessionId >= 0) runCatching { packageInstaller.abandonSession(sessionId) }
                return
            }
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val userAction = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                        ?.run {
                            // Doesn't actually needed as the receiver is actually not exported
                            // But the warnings can't be suppressed without this
                            IntentSanitizer.Builder()
                                .allowAction(this.action!!)
                                .allowExtra(PackageInstaller.EXTRA_SESSION_ID) { id -> id == activeSession?.second }
                                .allowAnyComponent()
                                .allowPackage {
                                    // There is no way to check the actual installer name so allow all.
                                    true
                                }
                                .build()
                                .sanitizeByFiltering(this)
                        }
                    if (userAction == null) {
                        xLogE("Fatal error for $intent")
                        // KMK: said in the "Couldn't install" message
                        continueQueue(InstallStep.Error, "Android's install prompt couldn't be opened")
                        return
                    }
                    userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // KMK -->
                    // r105: an update needs the user's OK when this app isn't the installer of record. Logged, given
                    // 3 min instead of 60 s, and asked by notification when the prompt can't be shown (app in the
                    // background: Android 14+ blocks activity starts from a background service without an error).
                    val entry = active.first
                    val label = Installer.labelOf(entry)
                    AppLog.add('W', "extensions", "Android asks to confirm $label", "session" to sessionId)
                    armWatchdog(entry, Installer.USER_OK_MS, Installer.USER_OK_REASON)
                    watchPrompt(sessionId)
                    askUser(entry, sessionId, userAction)
                    // KMK <--
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    continueQueue(InstallStep.Idle)
                }
                PackageInstaller.STATUS_SUCCESS -> continueQueue(InstallStep.Installed)
                // KMK: with Android's reason, for the "Couldn't install" message
                else -> continueQueue(
                    InstallStep.Error,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
            }
        }
    }

    private var activeSession: Pair<Entry, Int>? = null

    // KMK: r105 this entry's session, kept for the log lines after a cancel / timeout cleared activeSession
    @Volatile private var lastSessionId: Int? = null

    // Always ready
    override var ready = true

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        activeSession = null
        // KMK: r105
        lastSessionId = null
        try {
            val installParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            activeSession = entry to packageInstaller.createSession(installParams)
            // KMK: r105
            lastSessionId = activeSession!!.second
            logInstalling(entry, activeSession!!.second)
            val fileSize = service.getUriSize(entry.uri) ?: throw IllegalStateException()
            installParams.setSize(fileSize)

            val inputStream = service.contentResolver.openInputStream(entry.uri) ?: throw IllegalStateException()
            val session = packageInstaller.openSession(activeSession!!.second)
            val outputStream = session.openWrite(entry.downloadId.toString(), 0, fileSize)
            session.use {
                arrayOf(inputStream, outputStream).use {
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
                service.contentResolver.delete(entry.uri, null, null)

                val intentSender = PendingIntent.getBroadcast(
                    service,
                    activeSession!!.second,
                    Intent(INSTALL_ACTION).setPackage(service.packageName),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
                ).intentSender
                @SuppressLint("RequestInstallPackagesPolicy")
                session.commit(intentSender)
            }
            // KMK: r105 the 60 s for Android's answer start at the commit
            armWatchdog(entry, Installer.NO_ANSWER_MS, Installer.NO_ANSWER_REASON)
        } catch (e: Exception) {
            xLogE("Failed to install extension ${entry.downloadId} ${entry.uri}", e)
            activeSession?.let { (_, sessionId) ->
                // KMK: r105 runCatching: a throw here used to skip continueQueue and stall the queue
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            // KMK: said in the "Couldn't install" message
            continueQueue(InstallStep.Error, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun cancelEntry(entry: Entry): Boolean {
        activeSession?.let { (activeEntry, sessionId) ->
            if (activeEntry == entry) {
                return try {
                    packageInstaller.abandonSession(sessionId)
                    // KMK: Android sends nothing for an abandoned session, so the row is put back here and the
                    // queue goes on (it stayed on "Installing" before)
                    activeSession = null
                    continueQueue(InstallStep.Idle)
                    false
                } catch (_: SecurityException) {
                    // Highly likely the session has succeeded
                    true
                }
            }
        }
        return true
    }

    // KMK -->
    override fun activeSessionId(): Int? = activeSession?.second ?: lastSessionId

    // r105: the session is dropped, so a late status for it goes to the "Ignored … old install attempt" branch
    override fun onWatchdogTimeout(entry: Entry) {
        val current = activeSession ?: return
        if (current.first != entry) return
        activeSession = null
        runCatching { packageInstaller.abandonSession(current.second) }
    }

    override fun onEntryDone(entry: Entry) {
        clearPrompt()
        unwatchPrompt()
    }

    // r105 fix: the service stops mid-install: the session is dropped so Android can't finish or prompt for it later
    override fun onStoppedWhileInstalling(entry: Entry) {
        val current = activeSession ?: return
        if (current.first != entry) return
        activeSession = null
        runCatching { packageInstaller.abandonSession(current.second) }
    }

    /**
     * r105 fix: listens to [sessionId]'s progress while Android's prompt is up (registered only then). The progress
     * at the time Android asked is the baseline, so an update sent before the prompt can't count as the user's OK.
     */
    private fun watchPrompt(sessionId: Int) {
        promptProgress = runCatching { packageInstaller.getSessionInfo(sessionId)?.progress }.getOrNull() ?: 0f
        promptSession = sessionId
        if (!callbackRegistered) {
            runCatching {
                packageInstaller.registerSessionCallback(sessionCallback, Handler(Looper.getMainLooper()))
                callbackRegistered = true
            }.onFailure {
                AppLog.add(
                    'W',
                    "extensions",
                    "Couldn't watch the install session: ${it.message ?: it.javaClass.simpleName}",
                    "session" to sessionId,
                )
            }
        }
    }

    private fun unwatchPrompt() {
        promptSession = null
        if (callbackRegistered) {
            callbackRegistered = false
            runCatching { packageInstaller.unregisterSessionCallback(sessionCallback) }
        }
    }

    /**
     * r105: opens Android's confirm prompt when the app is on screen; otherwise, or when that fails, posts
     * "Tap to approve the update of <name>". An opened prompt that gets no answer in 15 s gets the notification
     * too (a blocked activity start doesn't throw).
     */
    private fun askUser(entry: Entry, sessionId: Int, userAction: Intent) {
        clearPrompt()
        val label = Installer.labelOf(entry)
        if (isAppVisible()) {
            try {
                service.startActivity(userAction)
                AppLog.add('D', "extensions", "Opened Android's prompt for $label", "session" to sessionId)
                val fallback = Runnable {
                    promptFallback = null
                    if (activeSession?.second == sessionId) postApproveNotification(entry, sessionId, userAction)
                }
                promptFallback = fallback
                promptHandler.postDelayed(fallback, PROMPT_FALLBACK_MS)
                return
            } catch (e: Exception) {
                AppLog.add(
                    'W',
                    "extensions",
                    "Couldn't open Android's prompt for $label: ${e.message ?: e.javaClass.simpleName}",
                    "session" to sessionId,
                )
            }
        }
        postApproveNotification(entry, sessionId, userAction)
    }

    private fun postApproveNotification(entry: Entry, sessionId: Int, userAction: Intent) {
        val label = Installer.labelOf(entry)
        try {
            // Immutable, so Android 14+ allows the implicit confirm intent; a tap on a notification may start it
            val pending = PendingIntent.getActivity(
                service,
                sessionId,
                userAction,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val name = Installer.nameOf(entry)
            service.notify(APPROVE_NOTIFICATION_ID, Notifications.CHANNEL_EXTENSIONS_UPDATE) {
                setSmallIcon(R.drawable.ic_komikku)
                setContentTitle("Tap to approve the update of $name")
                setContentText("Android needs your OK to install it")
                setContentIntent(pending)
                setAutoCancel(true)
                setOnlyAlertOnce(true)
                setPriority(NotificationCompat.PRIORITY_HIGH)
            }
            AppLog.add('I', "extensions", "Asked by notification to approve $label", "session" to sessionId)
        } catch (e: Exception) {
            AppLog.add(
                'E',
                "extensions",
                "Couldn't post the approve notification for $label: ${e.message ?: e.javaClass.simpleName}",
                "session" to sessionId,
            )
        }
    }

    private fun clearPrompt() {
        promptFallback?.let { promptHandler.removeCallbacks(it) }
        promptFallback = null
        runCatching { service.cancelNotification(APPROVE_NOTIFICATION_ID) }
    }

    /** Same check as BotCheckNotifier: an activity of this app is started */
    private fun isAppVisible(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(true)
    // KMK <--

    override fun onDestroy() {
        service.unregisterReceiver(packageActionReceiver)
        // KMK: r105
        clearPrompt()
        unwatchPrompt()
        super.onDestroy()
    }

    init {
        // KMK: install attempts left from before (the app was closed or restarted mid-install) are cleared, so
        // Android can't show their prompts in place of the new one
        runCatching {
            packageInstaller.mySessions.forEach { info ->
                runCatching { packageInstaller.abandonSession(info.sessionId) }
            }
        }
        ContextCompat.registerReceiver(
            service,
            packageActionReceiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

private const val INSTALL_ACTION = "PackageInstallerInstaller.INSTALL_ACTION"

// KMK: r105 (unused by Notifications; -402 is the install service's own notification)
private const val APPROVE_NOTIFICATION_ID = -403
private const val PROMPT_FALLBACK_MS = 15_000L
