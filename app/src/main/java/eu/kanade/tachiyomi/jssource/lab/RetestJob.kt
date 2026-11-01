package eu.kanade.tachiyomi.jssource.lab

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * KMK (r96): "Re-test all": the Source Lab's Test Run for every enabled source, one at a time, each on its own
 * test-copy runtime (`testing = true, testCopy = true`, so the saved source's settings and storage are untouched
 * and its `rateLimitMs` is honoured by the runtime). Run now from Home, or nightly at 03:00 as WorkManager unique
 * periodic work `source_lab_retest` (off by default; Wi-Fi only by default → `NetworkType.UNMETERED`).
 */
class RetestJob(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val (passed, total) = runAll { _, _, _ -> if (isStopped) throw CancellationException("stopped") }
        AppLog.add('I', "lab", "Re-test all (scheduled): $passed of $total passed")
        return Result.success()
    }

    companion object {
        private const val WORK = "source_lab_retest"

        private val prefs: PreferenceStore by lazy { Injekt.get<PreferenceStore>() }

        /** "off" | "nightly" */
        fun schedule(): Preference<String> = prefs.getString(Preference.appStateKey("source_lab_retest_schedule"), "off")
        fun wifiOnly(): Preference<Boolean> = prefs.getBoolean(Preference.appStateKey("source_lab_retest_wifi_only"), true)
        fun lastQuery(): Preference<String> = prefs.getString(Preference.appStateKey("source_lab_last_query"), "")

        /** "<time>|<passed>|<total>" of the last Re-test all (run now or scheduled) */
        internal fun lastResult(): Preference<String> = prefs.getString(Preference.appStateKey("source_lab_retest_last"), "")

        /** Applies the saved schedule: enqueues the nightly work or cancels it */
        fun apply(context: Context) {
            val nightly = schedule().get() == "nightly"
            val manager = WorkManager.getInstance(context)
            if (!nightly) {
                manager.cancelUniqueWork(WORK)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly().get()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(3, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes().coerceAtLeast(1)
            val request = PeriodicWorkRequestBuilder<RetestJob>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .addTag(WORK)
                .build()
            manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            AppLog.add('I', "lab", "Re-test all scheduled nightly at 03:00 (${if (wifiOnly().get()) "Wi-Fi only" else "any network"})")
        }

        /**
         * Runs every enabled source in turn; [onProgress] gets (done, total, name) before each. Returns
         * (passed, total). A source whose run throws counts as failed and the next one still runs.
         */
        internal suspend fun runAll(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): Pair<Int, Int> {
            val manager = Injekt.get<JsSourceManager>()
            val sources = manager.savedFlow.value.filter { it.enabled }
            var passed = 0
            sources.forEachIndexed { index, info ->
                onProgress(index, sources.size, info.name)
                val run = try {
                    runOne(info)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.add('W', "lab", "Re-test of ${info.name} failed: ${e.message}")
                    null
                }
                if (run != null && run.worst != StepStatus.Red) passed++
            }
            lastResult().set("${System.currentTimeMillis()}|$passed|${sources.size}")
            LabStore.invalidate()
            return passed to sources.size
        }

        /** One source's Test Run on a fresh test-copy runtime, closed when done; Lint follows */
        internal suspend fun runOne(info: JsSourceInfo): TestRun {
            val manager = Injekt.get<JsSourceManager>()
            val context = Injekt.get<android.app.Application>()
            val code = manager.code(info.key) ?: throw IllegalStateException("The code of ${info.name} is missing")
            manager.prepareTestCopy(info.key)
            val rt = JsRuntime.create(
                context = context,
                key = info.key,
                sourceId = manager.idOf(info.key),
                name = info.name.ifBlank { "lab" },
                code = code,
                json = info.json,
                testing = true,
                testCopy = true,
            )
            val run = try {
                TestRuns.run(
                    key = info.key,
                    rt = rt,
                    anime = info.anime,
                    headers = info.headers,
                    baseUrl = info.baseUrl,
                    query = lastQuery().get(),
                )
            } finally {
                rt.close()
            }
            runCatching { Lint.run(info.key) }
            AppLog.add('I', "lab", "Re-test of ${info.name}: ${run.passed} of ${run.counted} passed")
            return run
        }
    }
}
