package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.CallSuper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.AppLog
import uy.kohesive.injekt.injectLazy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Base implementation class for extension installer. To be used inside a foreground [Service].
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class Installer(private val service: Service) {

    private val extensionManager: ExtensionManager by injectLazy()

    private var waitingInstall = AtomicReference<Entry?>(null)

    // KMK -->
    // r105: insertion order kept explicitly (installs run in the order the downloads finished; "n of N" follows it)
    private val queue: MutableSet<Entry> = Collections.synchronizedSet(LinkedHashSet<Entry>())

    // r105: "n of N" for this batch (started so far, and started + still queued); reset when the queue runs dry
    @Volatile private var batchStarted = 0

    @Volatile private var activePosition = ""

    @Volatile private var activeSince = 0L

    // r105: one watchdog for the entry being installed. Android may never send a final status for a session
    // (a prompt that never showed, a system dialog nobody answered); before r105 the whole queue then waited forever.
    private val watchdogHandler = Handler(Looper.getMainLooper())

    @Volatile private var watchdog: Runnable? = null
    // KMK <--

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1).takeIf { it >= 0 } ?: return
            cancelQueue(downloadId)
        }
    }

    /**
     * Installer readiness. If false, queue check will not run.
     *
     * @see checkQueue
     */
    abstract var ready: Boolean

    /**
     * Add an item to install queue.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     * @param uri Uri of APK to install
     */
    fun addToQueue(downloadId: Long, uri: Uri) {
        queue.add(Entry(downloadId, uri))
        checkQueue()
    }

    /**
     * Proceeds to install the APK of this entry inside this method. Call [continueQueue]
     * when the install process for this entry is finished to continue the queue.
     *
     * @param entry The [Entry] of item to process
     * @see continueQueue
     */
    @CallSuper
    open fun processEntry(entry: Entry) {
        extensionManager.setInstalling(entry.downloadId)
    }

    /**
     * Called before queue continues. Override this to handle when the removed entry is
     * currently being processed.
     *
     * @return true if this entry can be removed from queue.
     */
    open fun cancelEntry(entry: Entry): Boolean {
        return true
    }

    // KMK -->
    /** r105: the Android install session of the entry being installed, for the log lines (null when none) */
    protected open fun activeSessionId(): Int? = null

    /** r105: the watchdog gave up on [entry]; drop its session so a late status for it is ignored */
    protected open fun onWatchdogTimeout(entry: Entry) {}

    /** r105: [entry] is finished (any result, cancel or timeout): clear what was shown for it */
    protected open fun onEntryDone(entry: Entry) {}

    /**
     * r105 fix: the service stops while [entry] is still being installed: drop its Android session (a late status
     * for it would otherwise land on nothing, or a prompt for it stay open). Called before the entry is cleared.
     */
    protected open fun onStoppedWhileInstalling(entry: Entry) {}
    // KMK <--

    /**
     * Tells the queue to continue processing the next entry and updates the install step
     * of the completed entry ([waitingInstall]) to [ExtensionManager].
     *
     * @param resultStep new install step for the processed entry.
     * @see waitingInstall
     */
    fun continueQueue(resultStep: InstallStep) {
        // KMK: r105 the session id is read before the next entry replaces it
        val sessionId = activeSessionId()
        val completedEntry = waitingInstall.exchange(null)
        if (completedEntry != null) {
            // KMK -->
            cancelWatchdog()
            logResult(completedEntry, resultStep, sessionId)
            runCatching { onEntryDone(completedEntry) }
            // KMK <--
            extensionManager.updateInstallStep(completedEntry.downloadId, resultStep)
            checkQueue()
        }
    }

    // KMK: the same, with why the install failed (when known), for the message the extension installers show
    fun continueQueue(resultStep: InstallStep, reason: String?) {
        val entry = waitingInstall.load()
        if (entry != null && reason != null) failureReasons[entry.downloadId] = reason
        continueQueue(resultStep)
    }

    /**
     * Checks the queue. The provided service will be stopped if the queue is empty.
     * Will not be run when not ready.
     *
     * @see ready
     */
    fun checkQueue() {
        if (!ready) {
            return
        }
        if (queue.isEmpty()) {
            // KMK: r105 never stop the service under an install that is still waiting for Android's answer
            if (waitingInstall.load() != null) return
            batchStarted = 0
            service.stopSelf()
            return
        }
        // KMK: r105 null when another thread emptied the queue in between
        val nextEntry = synchronized(queue) { queue.firstOrNull() } ?: return
        if (waitingInstall.compareAndSet(null, nextEntry)) {
            // KMK -->
            queue.remove(nextEntry)
            batchStarted += 1
            // downloads still running count too: the first install starts before the rest of an "Update all" lands
            val total = batchStarted + queue.size + downloadsInFlight.get().coerceAtLeast(0)
            activePosition = "$batchStarted of $total"
            activeSince = SystemClock.elapsedRealtime()
            // r105: the subclass re-arms it once the APK is handed to Android (PackageInstallerInstaller: after commit)
            armWatchdog(nextEntry, NO_ANSWER_MS, NO_ANSWER_REASON)
            // KMK <--
            processEntry(nextEntry)
        }
    }

    /**
     * Call this method when the provided service is destroyed.
     */
    @CallSuper
    open fun onDestroy() {
        LocalBroadcastManager.getInstance(service).unregisterReceiver(cancelReceiver)
        // KMK -->
        cancelWatchdog()
        // r105 fix: the entry being installed is taken here, so it ends as Error too (it stayed on "Installing")
        val sessionId = activeSessionId()
        val active = waitingInstall.exchange(null)
        if (active != null || queue.isNotEmpty()) {
            AppLog.add(
                'W',
                "extensions",
                "Install service stopped with ${queue.size} queued" +
                    (active?.let { ", ${labelOf(it)} still waiting for Android" } ?: ""),
                "session" to sessionId,
            )
        }
        if (active != null) {
            failureReasons[active.downloadId] = STOPPED_REASON
            runCatching { onStoppedWhileInstalling(active) }
            runCatching { onEntryDone(active) }
            logResult(active, InstallStep.Error, sessionId)
            extensionManager.updateInstallStep(active.downloadId, InstallStep.Error)
        }
        // KMK <--
        queue.forEach {
            // KMK: said in the "Couldn't install" message
            failureReasons[it.downloadId] = STOPPED_REASON
            extensionManager.updateInstallStep(it.downloadId, InstallStep.Error)
        }
        queue.clear()
        waitingInstall.store(null)
    }

    protected fun getActiveEntry(): Entry? = waitingInstall.load()

    /**
     * Cancels queue for the provided download ID if exists.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     */
    private fun cancelQueue(downloadId: Long) {
        val waitingInstall = this.waitingInstall.load()
        // KMK -->
        val toCancel = synchronized(queue) { queue.find { it.downloadId == downloadId } }
            ?: waitingInstall?.takeIf { it.downloadId == downloadId }
            // KMK <--
            ?: return
        if (cancelEntry(toCancel)) {
            queue.remove(toCancel)
            if (waitingInstall == toCancel) {
                // Currently processing removed entry, continue queue
                // KMK -->
                cancelWatchdog()
                AppLog.add(
                    'I',
                    "extensions",
                    "Install of ${labelOf(toCancel)} cancelled",
                    "session" to activeSessionId(),
                )
                runCatching { onEntryDone(toCancel) }
                // KMK <--
                this.waitingInstall.store(null)
                checkQueue()
            }
            extensionManager.updateInstallStep(downloadId, InstallStep.Idle)
        }
    }

    // KMK -->
    /** r105: `I extensions Installing <name> (<pkg>) (n of N) · session=S`, called once the session exists */
    protected fun logInstalling(entry: Entry, sessionId: Int?) {
        AppLog.add('I', "extensions", "Installing ${labelOf(entry)} ($activePosition)", "session" to sessionId)
    }

    /**
     * r105: (re)starts the watchdog for [entry]. With no final status within [timeoutMs] the session is dropped,
     * the row shows [reason] and the queue goes on with the next entry.
     */
    protected fun armWatchdog(entry: Entry, timeoutMs: Long, reason: String) {
        // an entry that already finished must not replace the next entry's watchdog
        if (waitingInstall.load() != entry) return
        cancelWatchdog()
        val task = Runnable {
            watchdog = null
            if (waitingInstall.load() != entry) return@Runnable
            AppLog.add(
                'E',
                "extensions",
                "No answer from Android for ${labelOf(entry)} after ${timeoutMs / 1000} s, moving on",
                "session" to activeSessionId(),
            )
            runCatching { onWatchdogTimeout(entry) }
            continueQueue(InstallStep.Error, reason)
        }
        watchdog = task
        watchdogHandler.postDelayed(task, timeoutMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { watchdogHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun logResult(entry: Entry, step: InstallStep, sessionId: Int?) {
        val label = labelOf(entry)
        when (step) {
            InstallStep.Installed -> {
                val seconds = (SystemClock.elapsedRealtime() - activeSince) / 1000.0
                AppLog.add('I', "extensions", "Installed $label in ${"%.1f".format(seconds)} s", "session" to sessionId)
            }
            InstallStep.Error -> {
                val reason = failureReasons[entry.downloadId] ?: "no reason given"
                AppLog.add('E', "extensions", "Couldn't install $label: $reason", "session" to sessionId)
            }
            InstallStep.Idle -> AppLog.add('I', "extensions", "Install of $label stopped", "session" to sessionId)
            else -> AppLog.add('D', "extensions", "Install of $label ended as $step", "session" to sessionId)
        }
    }
    // KMK <--

    /**
     * Install item to queue.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     * @param uri Uri of APK to install
     */
    data class Entry(val downloadId: Long, val uri: Uri)

    init {
        val filter = IntentFilter(ACTION_CANCEL_QUEUE)
        LocalBroadcastManager.getInstance(service).registerReceiver(cancelReceiver, filter)
    }

    companion object {
        private const val ACTION_CANCEL_QUEUE = "Installer.action.CANCEL_QUEUE"
        private const val EXTRA_DOWNLOAD_ID = "Installer.extra.DOWNLOAD_ID"

        // KMK: why an install failed, by download id; taken by the extension installer that owns the id
        val failureReasons = ConcurrentHashMap<Long, String>()

        // KMK -->
        /** r105: the extension's name by download id (set by the manga and anime installers), for the log lines */
        val extensionNames = ConcurrentHashMap<Long, String>()

        /** r105: extension APK downloads not yet handed on (both installers), for the N in "n of N" */
        val downloadsInFlight = AtomicInteger(0)

        /** r105: no final status this long after the APK was handed to Android → give up on it */
        const val NO_ANSWER_MS = 60_000L
        const val NO_ANSWER_REASON = "Android didn't answer for 60 s — tap Update to retry"

        /** r105: Android's "confirm this install" prompt left unanswered this long → give up on it */
        const val USER_OK_MS = 180_000L
        const val USER_OK_REASON = "waiting for your OK timed out"

        /** r105: the install service was stopped before the entry finished */
        const val STOPPED_REASON = "the install service stopped"

        /**
         * r105: the package from the APK's name (`extension_<pkg>_<signature>.apk` /
         * `anime_extension_<pkg>_<signature>.apk`, see ExtensionInstaller / AnimeExtensionInstaller); null if not
         * in that form.
         */
        fun packageOf(uri: Uri): String? {
            val file = uri.lastPathSegment?.substringAfterLast('/') ?: return null
            if (!file.endsWith(".apk")) return null
            return packageOfFileName(file)
        }

        internal fun packageOfFileName(file: String): String? {
            val base = file.removeSuffix(".apk")
            val withSig = when {
                base.startsWith("anime_extension_") -> base.removePrefix("anime_extension_")
                base.startsWith("extension_") -> base.removePrefix("extension_")
                else -> return null
            }
            return (if ('_' in withSig) withSig.substringBeforeLast('_') else withSig).takeIf { it.isNotBlank() }
        }

        /** r105: "Anivix (eu.kanade.tachiyomi.animeextension.all.anivix)", or whichever half is known */
        fun labelOf(entry: Entry): String {
            val name = extensionNames[entry.downloadId]
            val pkg = runCatching { packageOf(entry.uri) }.getOrNull()
            return when {
                name != null && pkg != null -> "$name ($pkg)"
                name != null -> name
                pkg != null -> pkg
                else -> "download ${entry.downloadId}"
            }
        }

        /** r105: the extension's name for messages shown to the user */
        fun nameOf(entry: Entry): String =
            extensionNames[entry.downloadId] ?: runCatching { packageOf(entry.uri) }.getOrNull() ?: "an extension"
        // KMK <--

        /**
         * Attempts to cancel the installation entry for the provided download ID.
         *
         * @param downloadId Download ID as known by [ExtensionManager]
         */
        fun cancelInstallQueue(context: Context, downloadId: Long) {
            val intent = Intent(ACTION_CANCEL_QUEUE)
            intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}
