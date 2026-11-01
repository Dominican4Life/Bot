package eu.kanade.tachiyomi.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.util.system.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KMK (r97): Freeze detection (design §7). One daemon thread, `diag-main-watchdog`, does both checks: every 3 s it
 * posts a token to the main thread and waits for it (a stall is a token that didn't run); every 5 s it enumerates
 * the threads into a reused array and counts the parked `DefaultDispatcher-worker-*` threads; when ≥ 56 are parked it
 * reads their stacks, and starvation is ≥ 56 workers parked outside the scheduler's own idle park on two checks in a
 * row (r98). Both flag, never kill. Stack traces only when a rule
 * fires, one grouped dump per minute.
 */
internal object Watchdogs {

    private const val IO_PREFIX = "DefaultDispatcher-worker-"

    private val started = AtomicBoolean(false)
    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The main-thread token: set true when it ran */
    private val tokenRan = AtomicBoolean(true)
    private val token = Runnable { tokenRan.set(true) }

    @Volatile
    private var lastDumpAt = 0L

    @Volatile
    private var lastStallAt = 0L

    /** Epoch ms of the last `App.onStop` (a stall right after it is the system freezing us, not our code) */
    @Volatile
    var lastBackgroundAt = 0L

    /**
     * KMK (r103): true while an activity is started (ProcessLifecycleOwner). In the background both checks pause:
     * the loop waits on [pauseLock] until this turns true again (before, it woke the main thread every 3 s and
     * enumerated every thread every 5 s for the life of the process). r103 fix pass: its first value is set by
     * DiagnosticsEngine from the process state before [start] (true here only until then).
     */
    @Volatile
    var inForeground = true
        set(value) {
            field = value
            if (value) pauseLock.withLock { resumed.signalAll() }
        }

    private val pauseLock = ReentrantLock()
    private val resumed = pauseLock.newCondition()

    /** Reused thread array (contract §9: re-grown when the count equals its length) */
    private var threads = arrayOfNulls<Thread>(192)

    @Volatile
    var ioStarved = false
        private set

    @Volatile
    var lastDump: ThreadDump? = null
        private set

    @Volatile
    private var lastIoWarnAt = 0L

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        this.context = context.applicationContext
        val t = Thread({ loop() }, "diag-main-watchdog")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    private fun loop() {
        var lastIoCheck = 0L
        var stalledSince = 0L
        var stallTopFrame = ""
        var lastFollowUp = 0L
        while (true) {
            try {
                if (!inForeground) {
                    val pausedAt = System.currentTimeMillis()
                    pauseLock.withLock {
                        while (!inForeground) resumed.await()
                    }
                    val seconds = (System.currentTimeMillis() - pausedAt) / 1000
                    AppLog.add('D', "diag", "Watchdogs resumed: paused $seconds s while the app was in the background")
                    // A starvation streak from before the pause doesn't carry over
                    stuckStreak = 0
                    lastIoCheck = 0L
                }
                tokenRan.set(false)
                mainHandler.post(token)
                Thread.sleep(DiagBudget.MAIN_WATCHDOG_MS)
                val now = System.currentTimeMillis()
                if (!tokenRan.get() && !inForeground) {
                    // In the background the system may be freezing us: a 1 s grace before anything is flagged
                    Thread.sleep(1_000L)
                }
                if (!tokenRan.get()) {
                    if (stalledSince == 0L) {
                        stalledSince = now - DiagBudget.MAIN_WATCHDOG_MS
                        if (!isFalsePositive(now)) {
                            val stack = Looper.getMainLooper().thread.stackTrace
                            stallTopFrame = topFrame(stack)
                            onMainStall(now, stallTopFrame, stack)
                        } else {
                            stallTopFrame = ""
                        }
                        lastFollowUp = now
                    } else if (now - lastFollowUp >= 10_000L && stallTopFrame.isNotEmpty()) {
                        lastFollowUp = now
                        AppLog.add(
                            'W',
                            "app",
                            "Main thread still stalled after ${(now - stalledSince) / 1000} s at $stallTopFrame",
                        )
                    }
                    // Keep waiting for the same token: don't post another
                    while (!tokenRan.get()) {
                        Thread.sleep(500L)
                        val t = System.currentTimeMillis()
                        if (t - lastFollowUp >= 10_000L && stallTopFrame.isNotEmpty()) {
                            lastFollowUp = t
                            AppLog.add(
                                'W',
                                "app",
                                "Main thread still stalled after ${(t - stalledSince) / 1000} s at $stallTopFrame",
                            )
                        }
                    }
                    if (stallTopFrame.isNotEmpty()) {
                        val took = (System.currentTimeMillis() - stalledSince) / 1000.0
                        AppLog.add('I', "app", "Main thread recovered after ${"%.1f".format(took)} s")
                    }
                    stalledSince = 0L
                    stallTopFrame = ""
                }
                if (now - lastIoCheck >= DiagBudget.IO_WATCHDOG_MS) {
                    lastIoCheck = now
                    checkIoPool(now)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                // The watchdog never takes the app down
            }
        }
    }

    private fun isFalsePositive(now: Long): Boolean {
        if (now - lastBackgroundAt in 0..2_000L) return true
        if (Debug.isDebuggerConnected()) return true
        val ctx = context ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am?.isBackgroundRestricted == true && !inForeground) return true
        }
        return false
    }

    private fun onMainStall(now: Long, top: String, mainStack: Array<StackTraceElement>) {
        lastStallAt = now
        val seconds = DiagBudget.MAIN_WATCHDOG_MS / 1000
        val where = if (inForeground) "" else " (in the background: possibly the system pausing the process)"
        // A background stall is information, not an error: the process may simply have been paused
        AppLog.add(if (inForeground) 'E' else 'I', "app", "MAIN THREAD STALLED $seconds s at $top$where")
        val text = "Main thread stalled $seconds s at $top"
        Health.lastStallText = text
        val dump = if (now - lastDumpAt >= 60_000L) {
            lastDumpAt = now
            runCatching { dumpAll(now) }.getOrNull()
        } else {
            null
        }
        val detail = buildString {
            append("Top frame: ").append(top)
            mainStack.take(6).forEach { append('\n').append("  at ").append(frame(it)) }
            if (!inForeground) append("\nIn the background: Doze, the app switcher or the cached-app freezer can pause the process")
        }
        Health.event(HealthKind.STALL_MAIN, "Main thread stalled $seconds s", detail, dump)
        if (dump != null) DumpTrace.event("main-stall", text, dump)
        RecordingNotification.onStall(context, now)
    }

    // KMK (r98) -->
    // The r97 rule fired "IO pool starved: 72/72 workers parked · 72 in run" on an idle app. Two reasons: the total
    // counted every DefaultDispatcher-worker (IO and Default share the name, so 72 > the IO limit of 64), and the idle
    // test looked for a `CoroutineScheduler$Worker.park/tryPark` frame that a release build doesn't have: R8 inlines
    // park → tryPark → runWorker into `Worker.run` (and may rename the class), so the idle stack read
    // `LockSupport.parkNanos ← Worker.run` and every idle worker counted as stuck. Now:
    //  - a parked worker is idle when the first frame below the park internals belongs to the worker thread's own
    //    class (`thread.javaClass.name`, which is CoroutineScheduler$Worker under any R8 name) or to
    //    kotlinx.coroutines.scheduling; idle parks never count, whatever their state;
    //  - the rule fires only when ≥ IO_STARVED_PARKED (56) workers are parked in other frames (runBlocking, Object.wait,
    //    a SQLite lock, a socket read…) on two checks in a row; the total shown is the IO limit (64).
    private var stuckStreak = 0

    /** When the last stack read found the parked workers idle (stacks are then not read again for 30 s) */
    private var lastIdleVerdictAt = 0L

    private fun checkIoPool(now: Long) {
        var count = Thread.currentThread().threadGroup?.let { rootGroup(it).enumerate(threads, true) } ?: 0
        while (count >= threads.size && threads.size < 2048) {
            threads = arrayOfNulls(threads.size * 2)
            count = rootGroup(Thread.currentThread().threadGroup!!).enumerate(threads, true)
        }
        try {
            var parked = 0
            for (i in 0 until count) {
                val t = threads[i] ?: continue
                if (!t.name.startsWith(IO_PREFIX)) continue
                when (t.state) {
                    Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED -> parked++
                    else -> Unit
                }
            }
            val limit = DiagBudget.IO_POOL_DEFAULT
            // Cheap pre-check without stacks: fewer than 56 parked threads of any kind can't be 56 stuck ones
            if (parked < DiagBudget.IO_STARVED_PARKED) {
                stuckStreak = 0
                if (ioStarved) recovered("$parked parked, none stuck")
                return
            }
            // Many parked threads: after an idle verdict, an idle pool (workers live 60 s after a burst) is not re-read
            // for 30 s; while a streak or an incident is running, stacks are read on every check (5 s)
            if (!ioStarved && stuckStreak == 0 && now - lastIdleVerdictAt < 30_000L) return
            val stuck = runCatching { stuckWorkers(count) }.getOrDefault(emptyList())
            if (stuck.size < DiagBudget.IO_STARVED_PARKED) {
                stuckStreak = 0
                lastIdleVerdictAt = now
                if (ioStarved) recovered("${stuck.size} stuck")
                return
            }
            stuckStreak++
            if (stuckStreak < 2) return
            if (ioStarved && now - lastIoWarnAt < 60_000L) return
            lastIoWarnAt = now
            val dump = runCatching { group(now, stuck) }.getOrNull()
            val top = dump?.groups?.maxByOrNull { it.count }
            val suffix = top?.let { " · ${it.count} in ${it.title.removePrefix("parked in ").removePrefix("blocked in ")}" } ?: ""
            val text = "IO pool starved: ${stuck.size} workers blocked outside the scheduler (IO limit $limit)$suffix"
            AppLog.add('W', "app", text)
            Health.lastStallText = text
            Health.event(HealthKind.STALL_IO, "IO pool starved", "${stuck.size} workers blocked (IO limit $limit)$suffix", dump)
            if (dump != null) {
                lastDump = dump
                DumpTrace.event("io-starved", text, dump)
            }
            ioStarved = true
        } finally {
            // Arrays are reused; drop the references so threads can be collected
            java.util.Arrays.fill(threads, 0, count, null)
        }
    }

    private fun recovered(detail: String) {
        ioStarved = false
        AppLog.add('I', "app", "IO pool recovered: $detail")
        DumpTrace.event("io-recovered", "IO pool recovered", null)
    }
    // KMK (r98) <--

    private fun rootGroup(g: ThreadGroup): ThreadGroup {
        var root = g
        while (true) root = root.parent ?: return root
    }

    // --- dumps -----------------------------------------------------------------------------------------------------

    /** A grouped dump of every thread ("Thread dump now", the main stall) */
    fun dumpAll(now: Long = System.currentTimeMillis()): ThreadDump {
        val all = Thread.getAllStackTraces()
        return group(now, all.entries.map { it.key to it.value })
    }

    /**
     * The parked workers that are stuck somewhere of ours or a library's (KMK r98): a worker whose first frame below
     * the park internals is the scheduler's own (the worker thread's class, or kotlinx.coroutines.scheduling) is idle
     * and left out, whatever R8 named or inlined.
     */
    private fun stuckWorkers(count: Int): List<Pair<Thread, Array<StackTraceElement>>> {
        val list = ArrayList<Pair<Thread, Array<StackTraceElement>>>()
        for (i in 0 until count) {
            val t = threads[i] ?: continue
            if (!t.name.startsWith(IO_PREFIX)) continue
            if (t.state == Thread.State.RUNNABLE) continue
            val stack = t.stackTrace
            if (isIdleWorkerStack(t, stack)) continue
            list.add(t to stack)
        }
        return list
    }

    private fun isIdleWorkerStack(thread: Thread, stack: Array<StackTraceElement>): Boolean {
        if (stack.isEmpty()) return true
        val top = stack.firstOrNull { f -> !isParkInternal(f) } ?: return true
        val cls = top.className
        return cls == thread.javaClass.name ||
            cls.startsWith("kotlinx.coroutines.scheduling.") ||
            cls.endsWith("CoroutineScheduler\$Worker")
    }

    /** The park/wait machinery at the top of a parked stack (not where the thread is held) */
    private fun isParkInternal(f: StackTraceElement): Boolean {
        val c = f.className
        return c.startsWith("sun.misc.Unsafe") ||
            c.startsWith("jdk.internal.misc.Unsafe") ||
            c.startsWith("java.util.concurrent.locks.LockSupport") ||
            c == "java.lang.Thread" && (f.methodName == "parkWith" || f.methodName == "park")
    }

    private fun group(now: Long, stacks: List<Pair<Thread, Array<StackTraceElement>>>): ThreadDump {
        val groups = LinkedHashMap<String, Triple<Int, String, List<String>>>()
        for ((thread, stack) in stacks) {
            val frames = meaningful(stack).take(6).map { frame(it) }
            val key = thread.state.name + "|" + frames.take(3).joinToString("|")
            val current = groups[key]
            groups[key] = Triple((current?.first ?: 0) + 1, thread.state.name, frames)
        }
        val list = groups.values.map { (count, state, frames) ->
            val verb = when (state) {
                "RUNNABLE" -> "running in"
                "BLOCKED" -> "blocked in"
                else -> "parked in"
            }
            val names = frames.take(3).map { it.substringBefore('(').substringAfterLast('.') }.filter { it.isNotEmpty() }
            ThreadDumpGroup(count, state, frames, "$verb ${names.joinToString(" → ")}")
        }.sortedByDescending { it.count }
        val parked = stacks.count { it.first.state != Thread.State.RUNNABLE }
        val top = list.firstOrNull { it.state != "RUNNABLE" }
        val summary = "${stacks.size} threads, $parked parked" + (top?.let { " in ${it.title.substringAfter(" in ")}" } ?: "")
        return ThreadDump(now, stacks.size, list, summary)
    }

    /** Frames worth grouping by: the park/wait internals at the top of every parked stack are skipped */
    private fun meaningful(stack: Array<StackTraceElement>): List<StackTraceElement> {
        val skip = listOf(
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe",
            "java.util.concurrent.locks.LockSupport",
            "java.lang.Object.wait",
        )
        val filtered = stack.filter { f -> skip.none { f.className.startsWith(it) || "${f.className}.${f.methodName}".startsWith(it) } }
        return if (filtered.isEmpty()) stack.toList() else filtered
    }

    private fun frame(f: StackTraceElement): String {
        val cls = f.className.substringAfterLast('.')
        return "$cls.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})"
    }

    private fun topFrame(stack: Array<StackTraceElement>): String =
        meaningful(stack).firstOrNull()?.let { frame(it) } ?: stack.firstOrNull()?.let { frame(it) } ?: "?"

    /** "Thread dump now": the dump, the EVENTS trace record and the `I app Thread dump: …` line */
    fun threadDumpNow(): ThreadDump {
        val dump = dumpAll()
        lastDump = dump
        AppLog.add('I', "app", "Thread dump: ${dump.summary}")
        DumpTrace.event("manual", "Thread dump", dump)
        return dump
    }

    fun lastStallAt(): Long? = lastStallAt.takeIf { it > 0L }
}
