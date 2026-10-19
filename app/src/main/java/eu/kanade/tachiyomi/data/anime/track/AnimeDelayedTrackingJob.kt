package eu.kanade.tachiyomi.data.anime.track

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * KMK: sends anime tracking changes that failed (e.g. while offline) once there's a connection again, like
 * DelayedTrackingUpdateJob does for manga.
 */
class AnimeDelayedTrackingJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > 3) {
            return Result.failure()
        }
        val done = withIOContext {
            try {
                AnimeTracking.retryPending()
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime tracking: delayed update failed" }
                false
            }
        }
        return if (done) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "AnimeDelayedTracking"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<AnimeDelayedTrackingJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
