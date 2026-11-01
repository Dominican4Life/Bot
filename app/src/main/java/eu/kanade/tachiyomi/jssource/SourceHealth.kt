package eu.kanade.tachiyomi.jssource

import android.content.Context
import eu.kanade.tachiyomi.jssource.lab.HealthDay
import eu.kanade.tachiyomi.jssource.lab.HealthLastError
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabPaths
import eu.kanade.tachiyomi.jssource.lab.LintResult
import eu.kanade.tachiyomi.jssource.lab.LintSeverity
import eu.kanade.tachiyomi.jssource.lab.SourceHealthFile
import eu.kanade.tachiyomi.jssource.lab.SourceHealthSnapshot
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK (r96): per-source request and error counters for the Source Lab's home rows, in
 * `js_sources/<key>/health.json` as rolling day buckets (UTC days, 14 kept). Fed by [JsRuntime]: one request per
 * `fetchUrl`, one error per failed method call, only from the real runtimes (not the editor's test copy or the
 * probe). Written at most once a minute per source and flushed when the runtime closes, so the hot path never
 * waits on a file.
 */
internal class SourceHealth(private val filesDir: File) {

    private val sinks = ConcurrentHashMap<String, Sink>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sink(key: String): Sink = sinks.getOrPut(key) { Sink(key) }

    /** The counters of [key] as the home row shows them; [lastRun] and [lastLint] decide the state (C§8.2) */
    fun snapshot(key: String, lastRun: TestRun? = null, lastLint: LintResult? = null): SourceHealthSnapshot {
        val file = sinks[key]?.current() ?: read(key)
        val today = today()
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
        val todayBucket = file.days.firstOrNull { it.day == today }
        val yesterdayBucket = file.days.firstOrNull { it.day == yesterday }
        val requestsPerDay = when {
            yesterdayBucket != null -> (yesterdayBucket.requests + (todayBucket?.requests ?: 0)) / 2
            else -> todayBucket?.requests ?: 0
        }
        val errorsToday = todayBucket?.errors ?: 0
        val sparkline = (6 downTo 0).map { back ->
            val day = LocalDate.now(ZoneOffset.UTC).minusDays(back.toLong()).toString()
            file.days.firstOrNull { it.day == day }?.requests ?: 0
        }
        val lintBad = lastLint?.findings.orEmpty().any {
            !it.ignored && (it.severity == LintSeverity.Error || it.severity == LintSeverity.Warn)
        }
        val state = when {
            lastRun?.worst == StepStatus.Red || errorsToday >= 3 -> HealthState.Failing
            lintBad || lastRun?.worst == StepStatus.Amber || errorsToday >= 1 -> HealthState.Attention
            lastRun == null && file.days.isEmpty() -> HealthState.Unknown
            else -> HealthState.Good
        }
        val note = file.lastError?.takeIf { errorsToday > 0 }?.let { error ->
            val what = error.message.lineSequence().firstOrNull().orEmpty().take(80)
            "${error.method} failed ${if (errorsToday == 1) "once" else "$errorsToday times"} today · $what"
        }
        return SourceHealthSnapshot(
            state = state,
            requestsPerDay = requestsPerDay,
            errorsToday = errorsToday,
            lastError = file.lastError,
            sparkline = sparkline,
            note = note,
        )
    }

    private fun file(key: String): File = LabPaths.health(filesDir, key)

    private fun read(key: String): SourceHealthFile = runCatching {
        val f = file(key)
        if (f.exists()) jsJson.decodeFromString(SourceHealthFile.serializer(), f.readText()) else null
    }.getOrNull() ?: SourceHealthFile()

    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** One source's counters, shared by every runtime of that source */
    inner class Sink(private val key: String) {

        private var data: SourceHealthFile? = null
        private var dirty = false
        private var lastWrite = 0L
        private var pending: Job? = null

        @Synchronized
        fun current(): SourceHealthFile = data ?: read(key).also { data = it }

        @Synchronized
        fun request() {
            bump(requests = 1, errors = 0, error = null)
        }

        @Synchronized
        fun error(method: String, message: String) {
            bump(requests = 0, errors = 1, error = HealthLastError(System.currentTimeMillis(), method, message.take(300)))
        }

        private fun bump(requests: Int, errors: Int, error: HealthLastError?) {
            val now = current()
            val today = today()
            val days = now.days.toMutableList()
            val index = days.indexOfFirst { it.day == today }
            if (index >= 0) {
                val d = days[index]
                days[index] = d.copy(requests = d.requests + requests, errors = d.errors + errors)
            } else {
                days.add(HealthDay(today, requests, errors))
            }
            days.sortBy { it.day }
            while (days.size > 14) days.removeAt(0)
            data = SourceHealthFile(days = days, lastError = error ?: now.lastError)
            dirty = true
            scheduleWrite()
        }

        /** Writes now when the last write is a minute old, else once a minute has passed since it */
        private fun scheduleWrite() {
            val wait = lastWrite + WRITE_EVERY_MS - System.currentTimeMillis()
            if (wait <= 0) {
                write()
                return
            }
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(wait)
                flush()
            }
        }

        @Synchronized
        fun flush() {
            if (dirty) write()
        }

        /** Encodes under the lock, writes on IO: a fetch or a failed call never waits on the file */
        private fun write() {
            val now = data ?: return
            dirty = false
            lastWrite = System.currentTimeMillis()
            val text = jsJson.encodeToString(SourceHealthFile.serializer(), now)
            val f = file(key)
            scope.launch {
                // KMK: only next to an existing source (a sidecar never makes a folder of its own, C§4)
                if (f.parentFile?.isDirectory != true) return@launch
                runCatching { f.writeText(text) }
                    .onFailure { AppLog.add('W', "lab", "Couldn't write health.json of $key: ${it.message}") }
            }
        }
    }

    companion object {
        private const val WRITE_EVERY_MS = 60_000L

        @Volatile
        private var instance: SourceHealth? = null

        fun of(context: Context): SourceHealth = instance ?: synchronized(this) {
            instance ?: SourceHealth(context.applicationContext.filesDir).also { instance = it }
        }
    }
}
