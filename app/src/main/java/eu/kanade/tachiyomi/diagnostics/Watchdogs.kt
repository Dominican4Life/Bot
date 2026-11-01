package eu.kanade.tachiyomi.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.util.system.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KMK (r97): Freeze detection (design §7). One daemon thread, `diag-main-watchdog`, does both checks: every 3 s it
 * posts a token to the main thread and waits for it (a stall is a token that didn't run); every 5 s it enumerates
 * the threads into a reused array and counts the parked `DefaultDispatcher-worker-*` threads (starvation is ≥ 56 of
 * 64 parked across two checks while the pool was in use). Both flag, never kill. Stack traces only when a rule
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

    @Volatile
    var inForeground = true

    /** Reused thread array (contract §9: re-grown when the count equals its length) */
    private var threads = arrayOfNulls<Thread>(192)

    /** Busy IO workers seen by [LiveSampler] (or by this thread) in the last minute, for the "pool was in use" rule */
    @Volatile
    private var busyPeakAt = 0L

    @Volatile
    private var parkedStreak = 0

    @Volatile
    var ioStarved = false
        private set

    @Volatile
    var lastDump: ThreadDump? = null
        private set

    @Volatile
    private var lastIoWarnAt = 0L

    @Volatile
    private var lastStuckCheckAt = 0L

    /** Called by the sampler with the busy IO count of a tick (so a burst that parked 60 idle workers isn't a stall) */
    fun noteIoBusy(busy: Int) {
        if (busy >= 32) busyPeakAt = System.currentTimeMillis()
    }

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

    private fun checkIoPool(now: Long) {
        var count = Thread.currentThread().threadGroup?.let { rootGroup(it).enumerate(threads, true) } ?: 0
        while (count >= threads.size && threads.size < 2048) {
            threads = arrayOfNulls(threads.size * 2)
            count = rootGroup(Thread.currentThread().threadGroup!!).enumerate(threads, true)
        }
        var workers = 0
        var parked = 0
        var busy = 0
        for (i in 0 until count) {
            val t = threads[i] ?: continue
            if (!t.name.startsWith(IO_PREFIX)) continue
            workers++
            when (t.state) {
                Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED -> parked++
                Thread.State.RUNNABLE -> busy++
                else -> Unit
            }
        }
        if (busy >= 32) busyPeakAt = now
        val total = DiagBudget.IO_POOL_DEFAULT.coerceAtLeast(workers)
        val starvedNow = workers > 0 && (parked >= DiagBudget.IO_STARVED_PARKED || parked * 100 / total >= 87)
        parkedStreak = if (starvedNow) parkedStreak + 1 else 0
        val inUse = now - busyPeakAt <= 60_000L || now - lastStallAt <= 60_000L
        if (parkedStreak >= 2 && inUse) {
            if ((!ioStarved && now - lastStuckCheckAt >= 30_000L) || (ioStarved && now - lastIoWarnAt >= 60_000L)) {
                // Idle workers park too (CoroutineScheduler.Worker.park): only the ones stuck elsewhere count, so
                // the stacks of the parked workers are read now (the rule fired; at most every 30 s while it holds)
                lastStuckCheckAt = now
                val stuck = runCatching { stuckWorkers(count) }.getOrDefault(emptyList())
                val stuckNow = stuck.size >= DiagBudget.IO_STARVED_PARKED || stuck.size * 100 / total >= 87
                if (stuckNow) {
                    lastIoWarnAt = now
                    val dump = runCatching { group(now, stuck) }.getOrNull()
                    val top = dump?.groups?.maxByOrNull { it.count }
                    val suffix = top?.let { " · ${it.count} in ${it.title.removePrefix("parked in ")}" } ?: ""
                    val text = "IO pool starved: ${stuck.size}/$total workers parked$suffix"
                    AppLog.add('W', "app", text)
                    Health.lastStallText = text
                    Health.event(HealthKind.STALL_IO, "IO pool starved", "${stuck.size} of $total workers parked$suffix", dump)
                    if (dump != null) {
                        lastDump = dump
                        DumpTrace.event("io-starved", text, dump)
                    }
                    ioStarved = true
                } else if (ioStarved) {
                    ioStarved = false
                    AppLog.add('I', "app", "IO pool recovered: ${stuck.size}/$total workers stuck")
                    DumpTrace.event("io-recovered", "IO pool recovered", null)
                }
            }
        } else if (ioStarved && !starvedNow) {
            ioStarved = false
            AppLog.add('I', "app", "IO pool recovered: $parked/$total workers parked")
            DumpTrace.event("io-recovered", "IO pool recovered", null)
        }
        // Arrays are reused; drop the references so threads can be collected
        java.util.Arrays.fill(threads, 0, count, null)
    }

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
     * The parked IO workers that are stuck somewhere of ours: a worker whose top meaningful frame is the scheduler's
     * own `CoroutineScheduler.Worker.park` / `tryPark` / `parkNanos` is idle, not starved, and is left out.
     */
    private fun stuckWorkers(count: Int): List<Pair<Thread, Array<StackTraceElement>>> {
        val list = ArrayList<Pair<Thread, Array<StackTraceElement>>>()
        for (i in 0 until count) {
            val t = threads[i] ?: continue
            if (!t.name.startsWith(IO_PREFIX)) continue
            if (t.state == Thread.State.RUNNABLE) continue
            val stack = t.stackTrace
            if (isIdleWorkerStack(stack)) continue
            list.add(t to stack)
        }
        return list
    }

    private fun isIdleWorkerStack(stack: Array<StackTraceElement>): Boolean {
        val top = meaningful(stack).firstOrNull() ?: return stack.isEmpty()
        return top.className.endsWith("CoroutineScheduler\$Worker") &&
            (top.methodName == "park" || top.methodName == "tryPark" || top.methodName == "parkNanos")
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
