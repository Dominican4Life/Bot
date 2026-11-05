package eu.kanade.tachiyomi.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KMK (r97): The 7-day health timeline (design §2.5, §7.3). Producers call [event] where they already log a line;
 * the store is `cacheDir/diag/health.json`, rewritten on the IO worker (temp + rename), 500 events / 7 days.
 * [readExitInfo] turns the previous process's exit reason into an event and the `Previous run ended` line.
 */
internal object Health {

    private val lock = Any()
    private val events = ArrayList<HealthEvent>()
    private var nextId = 1L

    @Volatile
    private var previousRun: PreviousRun? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    private var file: File? = null
    private var loaded = false

    /** A save asked for before the store was read: done right after the merge in [init] */
    private var pendingSave = false

    /** Only the newest this many events keep their thread dump (the file stays under HEALTH_BYTES) */
    private const val DUMPS_KEPT = 20

    private val _snapshot = MutableStateFlow(HealthSnapshot())
    val snapshot: StateFlow<HealthSnapshot> = _snapshot.asStateFlow()

    private val _markers = MutableStateFlow<List<Marker>>(emptyList())

    /** Health events + marks + probe markers of the last 7 days, newest last */
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()

    private val extraMarkers = ArrayList<Marker>()

    /** Watchdog state the snapshot shows */
    @Volatile
    var watchdogsOn = true

    @Volatile
    var lastStallText: String? = null

    /** Knows where the store is (synchronously at start, so a crash before [init] can still merge and write) */
    fun attach(context: Context) {
        this.context = context.applicationContext
        file = File(context.cacheDir, DiagPaths.HEALTH_JSON)
    }

    /**
     * Loads the store (IO worker) and merges it with the events recorded before this ran (a crash, a stall or a
     * producer that fired early): the stored events keep their ids, the early ones get fresh ones after them.
     */
    fun init(context: Context) {
        attach(context)
        load()
    }

    private fun load() {
        val f = file ?: return
        val read = runCatching {
            if (f.isFile) jsJson.decodeFromString(ListSerializer(HealthEvent.serializer()), f.readText()) else emptyList()
        }.getOrDefault(emptyList())
        val cutoff = System.currentTimeMillis() - DiagBudget.HEALTH_DAYS * 86_400_000L
        var saveNow = false
        synchronized(lock) {
            if (loaded) return
            val early = events.toList()
            events.clear()
            events.addAll(read.filter { it.time >= cutoff }.sortedBy { it.time }.takeLast(DiagBudget.HEALTH_EVENTS_MAX))
            nextId = (events.maxOfOrNull { it.id } ?: 0L) + 1
            for (e in early) events.add(e.copy(id = nextId++))
            events.sortBy { it.time }
            trimLocked(System.currentTimeMillis())
            loaded = true
            saveNow = early.isNotEmpty() || pendingSave
            pendingSave = false
        }
        publish()
        if (saveNow) save()
    }

    /** Lock held: the 7-day / 500-event caps, dumps only on the newest [DUMPS_KEPT], and the byte budget */
    private fun trimLocked(now: Long) {
        val cutoff = now - DiagBudget.HEALTH_DAYS * 86_400_000L
        events.removeAll { it.time < cutoff }
        while (events.size > DiagBudget.HEALTH_EVENTS_MAX) events.removeAt(0)
        val firstWithDump = (events.size - DUMPS_KEPT).coerceAtLeast(0)
        for (i in 0 until firstWithDump) {
            val e = events[i]
            if (e.dump != null) events[i] = e.copy(dump = null)
        }
    }

    /** Lock held: drops the oldest events until the JSON fits HEALTH_BYTES (dumps go first) */
    private fun fitBudgetLocked(): List<HealthEvent> {
        var json = jsJson.encodeToString(ListSerializer(HealthEvent.serializer()), events)
        if (json.length <= DiagBudget.HEALTH_BYTES) return events.toList()
        // Dumps are the bulk: drop them from old to new before dropping events
        for (i in events.indices) {
            if (json.length <= DiagBudget.HEALTH_BYTES) break
            val e = events[i]
            if (e.dump != null) {
                events[i] = e.copy(dump = null)
                json = jsJson.encodeToString(ListSerializer(HealthEvent.serializer()), events)
            }
        }
        while (json.length > DiagBudget.HEALTH_BYTES && events.size > 1) {
            events.removeAt(0)
            json = jsJson.encodeToString(ListSerializer(HealthEvent.serializer()), events)
        }
        return events.toList()
    }

    /**
     * Records an event. Safe from any thread; the file rewrite goes to the IO worker. [dump] only for stalls.
     * Returns the event (its id is stable for the UI).
     */
    fun event(
        kind: HealthKind,
        title: String,
        detail: String,
        dump: ThreadDump? = null,
        traceFile: String? = null,
    ): HealthEvent {
        val now = System.currentTimeMillis()
        val e = synchronized(lock) {
            HealthEvent(
                id = nextId++,
                time = now,
                kind = kind,
                title = title,
                detail = detail,
                excerptOffset = AppLog.ring?.writeOffset?.toLong() ?: -1L,
                dump = dump,
                traceFile = traceFile,
                session = AppLog.sessionStart,
            ).also { ev ->
                events.add(ev)
                trimLocked(now)
            }
        }
        publish()
        // A crash ends the process before the worker would run: written on the calling thread then (merging the
        // stored events first when the store wasn't read yet)
        if (kind == HealthKind.CRASH) {
            runCatching {
                if (!synchronized(lock) { loaded }) load()
                save()
            }
        } else {
            DiagIo.post { save() }
        }
        return e
    }

    /** A marker that isn't a health event (probe output, mark lines are events already) */
    fun marker(marker: Marker) {
        synchronized(lock) {
            extraMarkers.add(marker)
            val cutoff = System.currentTimeMillis() - DiagBudget.HEALTH_DAYS * 86_400_000L
            extraMarkers.removeAll { it.timeMs < cutoff }
            while (extraMarkers.size > 500) extraMarkers.removeAt(0)
        }
        publishMarkers()
    }

    private fun publish() {
        val list = synchronized(lock) { events.toList() }
        val counts = HashMap<HealthKind, Int>()
        for (e in list) counts[e.kind] = (counts[e.kind] ?: 0) + 1
        _snapshot.value = HealthSnapshot(
            events = list.asReversed(),
            previousRun = previousRun,
            sinceMs = System.currentTimeMillis() - DiagBudget.HEALTH_DAYS * 86_400_000L,
            counts = counts,
            watchdogsOn = watchdogsOn,
            lastStallText = lastStallText,
        )
        publishMarkers()
    }

    private fun publishMarkers() {
        val all = synchronized(lock) {
            val fromEvents = events.map { e ->
                Marker(
                    e.time, e.kind.marker,
                    if (e.kind == HealthKind.MARK) e.detail.ifEmpty { "MARK" } else e.kind.badge,
                    when (e.kind) {
                        HealthKind.SYNC_OK, HealthKind.SYNC_FAILED -> "sync"
                        HealthKind.UPDATE_OK, HealthKind.UPDATE_FAILED -> "LibraryUpdateJob"
                        else -> "app"
                    },
                )
            }
            (fromEvents + extraMarkers).sortedBy { it.timeMs }
        }
        _markers.value = all
    }

    /** IO worker only (and the crashing thread). Before the store is read the save is queued, not dropped */
    private fun save() {
        val f = file ?: return
        val list = synchronized(lock) {
            if (!loaded) {
                pendingSave = true
                return
            }
            fitBudgetLocked()
        }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(jsJson.encodeToString(ListSerializer(HealthEvent.serializer()), list))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun events(): List<HealthEvent> = synchronized(lock) { events.toList() }

    fun previousRun(): PreviousRun? = previousRun

    /** The health.json text as stored (for the report and the export) */
    fun json(): String = jsJson.encodeToString(ListSerializer(HealthEvent.serializer()), events())

    // --- previous run ---------------------------------------------------------------------------------------------

    /**
     * Reads `ApplicationExitInfo` (API 30+) once per start, on IO. Each exit newer than `LAST_EXIT_SEEN` becomes an
     * event; the newest fills [PreviousRun] and the `I app Previous run ended: …` line.
     */
    fun readExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences(DiagKeys.PREFS_FILE, Context.MODE_PRIVATE)
            val seen = prefs.getLong(DiagKeys.LAST_EXIT_SEEN, 0L)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(null, 0, 5)
                .filter { it.processName == context.packageName && it.timestamp > seen }
                .sortedBy { it.timestamp }
            if (exits.isEmpty()) return
            val tail = previousTail()
            var newest: PreviousRun? = null
            for (exit in exits) {
                val kind = kindOf(exit.reason)
                val reason = reasonText(exit)
                var traceFile: String? = null
                // KMK (r127): native crashes keep their crash report too (the readable part of Android's tombstone)
                if (exit.reason == ApplicationExitInfo.REASON_ANR ||
                    exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                ) {
                    traceFile = runCatching { saveTrace(context, exit) }.getOrNull()
                }
                val rssMb = exit.rss / 1024
                val detail = "$reason · ${rssMb} MB RSS" + (exit.description?.let { " · $it" } ?: "")
                // The owner swiping the app away or force-stopping it isn't a health event (it still fills the
                // Previous run card)
                val userEnded = exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                    exit.reason == ApplicationExitInfo.REASON_USER_STOPPED
                if (!userEnded) {
                    synchronized(lock) {
                        val ev = HealthEvent(
                            id = nextId++,
                            time = exit.timestamp,
                            kind = kind,
                            title = kind.label,
                            detail = detail,
                            traceFile = traceFile,
                            session = 0L,
                        )
                        events.add(ev)
                        events.sortBy { it.time }
                    }
                }
                newest = PreviousRun(
                    endedAt = exit.timestamp,
                    reason = reason,
                    reasonCode = exit.reason,
                    description = exit.description,
                    pssKb = exit.pss,
                    rssKb = exit.rss,
                    importance = exit.importance,
                    screen = tail.first,
                    lastMemoryLine = tail.second,
                    uptimeMs = 0L,
                    linesKept = tail.third,
                    traceFile = traceFile,
                )
            }
            prefs.edit().putLong(DiagKeys.LAST_EXIT_SEEN, exits.last().timestamp).apply()
            previousRun = newest
            newest?.let { run ->
                val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(run.endedAt))
                AppLog.add('I', "app", "Previous run ended: ${run.reason} at $time, ${run.rssKb / 1024} MB")
            }
            publish()
            DiagIo.post { save() }
        }
    }

    /** screen, last Memory line and line count of the previous run, from the ring's tail */
    private fun previousTail(): Triple<String?, String?, Int> {
        val ring = AppLog.ring ?: return Triple(null, null, 0)
        var screen: String? = null
        var memory: String? = null
        var count = 0
        runCatching {
            ring.forEachLine { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) return@forEachLine
                val time = parts[0].toLongOrNull() ?: return@forEachLine
                if (time >= AppLog.sessionStart) return@forEachLine
                count++
                if (parts[2] == "Memory" && parts[3].contains("MB in use")) {
                    memory = parts[3]
                    screen = parts[3].substringAfter("(screen: ", "").substringBefore(')').substringBefore(',').ifEmpty { null }
                }
            }
        }
        return Triple(screen, memory, count)
    }

    private fun saveTrace(context: Context, exit: ApplicationExitInfo): String? {
        val input = exit.traceInputStream ?: return null
        val dir = File(context.cacheDir, DiagPaths.HEALTH_DIR).apply { mkdirs() }
        val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        // Kept under the anr- name so the report bundles it like the freeze traces
        val name = "anr-${exit.timestamp}${if (native) "-native" else ""}.txt"
        val out = File(dir, name)
        input.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                // A native tombstone is binary (protobuf): keep its readable runs (signal, abort message, the
                // backtrace's library and function names), one per line
                val run = java.io.ByteArrayOutputStream()
                fun flushRun() {
                    if (run.size() >= 4 && total < DiagBudget.ANR_TRACE_MAX_BYTES) {
                        val bytes = run.toByteArray()
                        os.write(bytes)
                        os.write('\n'.code)
                        total += bytes.size + 1
                    }
                    run.reset()
                }
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (native) {
                        for (i in 0 until n) {
                            val b = buf[i].toInt() and 0xFF
                            if (b in 0x20..0x7E) run.write(b) else flushRun()
                        }
                        if (total >= DiagBudget.ANR_TRACE_MAX_BYTES) break
                    } else {
                        val keep = minOf(n.toLong(), DiagBudget.ANR_TRACE_MAX_BYTES - total).toInt()
                        if (keep <= 0) break
                        os.write(buf, 0, keep)
                        total += keep
                    }
                }
                if (native) flushRun()
            }
        }
        // Five kept
        dir.listFiles { f -> f.name.startsWith("anr-") }?.sortedByDescending { it.lastModified() }?.drop(5)
            ?.forEach { it.delete() }
        return "health/$name"
    }

    private fun kindOf(reason: Int): HealthKind = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> HealthKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> HealthKind.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> HealthKind.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY -> HealthKind.LMK
        else -> HealthKind.EXIT_OTHER
    }

    private fun reasonText(exit: ApplicationExitInfo): String = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory (LMK)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown (${exit.reason})"
    }

    // --- score ----------------------------------------------------------------------------------------------------

    class Score(val state: HealthState, val sentence: String, val action: StripAction?)

    /**
     * The strip's pill, sentence and action (design §2.1 item 1). [heapPercent], [ioStarved] and [trimRecently]
     * come from the sampler's latest tick.
     */
    fun score(heapPercent: Int, ioStarved: Boolean, trimRecently: Boolean, idleJs: Int): Score {
        val now = System.currentTimeMillis()
        val day = now - 86_400_000L
        val list = events()
        val stalls24h = list.count { it.time >= day && (it.kind == HealthKind.STALL_MAIN || it.kind == HealthKind.STALL_IO) }
        val crashes7d = list.count {
            it.kind == HealthKind.CRASH || it.kind == HealthKind.ANR || it.kind == HealthKind.LMK || it.kind == HealthKind.NATIVE_CRASH
        }
        val failed24h = list.count { it.time >= day && (it.kind == HealthKind.UPDATE_FAILED || it.kind == HealthKind.SYNC_FAILED) }
        val problems = ArrayList<Pair<HealthState, Pair<String, StripAction?>>>()
        if (ioStarved) problems.add(HealthState.PROBLEM to ("IO pool starved now" to StripAction.THREAD_DUMP))
        if (heapPercent >= 75 || trimRecently) {
            problems.add(HealthState.PROBLEM to ("memory $heapPercent %" to StripAction.FREE_MEMORY))
        } else if (heapPercent >= 60) {
            val action = if (idleJs > 0) StripAction.CLOSE_IDLE_JS else StripAction.FREE_MEMORY
            problems.add(HealthState.ATTENTION to ("memory $heapPercent %" to action))
        }
        if (stalls24h >= 3) {
            problems.add(HealthState.PROBLEM to ("$stalls24h stalls in 24 h" to StripAction.OPEN_HEALTH))
        } else if (stalls24h > 0) {
            problems.add(HealthState.ATTENTION to ("$stalls24h stall${if (stalls24h > 1) "s" else ""} in 24 h" to StripAction.OPEN_HEALTH))
        }
        if (crashes7d > 0) {
            problems.add(HealthState.ATTENTION to ("$crashes7d abnormal exit${if (crashes7d > 1) "s" else ""} in 7 days" to StripAction.OPEN_HEALTH))
        }
        if (failed24h > 0) problems.add(HealthState.ATTENTION to ("$failed24h failed job${if (failed24h > 1) "s" else ""} in 24 h" to StripAction.OPEN_HEALTH))
        if (problems.isEmpty()) {
            val ended = previousRun?.let { " · last run ended: ${it.reason}" } ?: " · last run ended normally"
            return Score(HealthState.GOOD, "No stalls or crashes in 7 days · memory $heapPercent %$ended", null)
        }
        val worst = if (problems.any { it.first == HealthState.PROBLEM }) HealthState.PROBLEM else HealthState.ATTENTION
        val first = problems.first { it.first == worst }
        val text = problems.joinToString(" · ") { it.second.first }
        return Score(worst, text, first.second.second)
    }
}
