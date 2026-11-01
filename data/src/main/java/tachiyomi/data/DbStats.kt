package tachiyomi.data

import java.util.concurrent.atomic.AtomicLong

/**
 * KMK (r97): Database counters for the Diagnostics Live tab (queries per second). Bumped in
 * [AndroidDatabaseHandler.dispatch]; the sampler differentiates them. Nothing here depends on the app module.
 */
object DbStats {
    val queries = AtomicLong()
    val transactions = AtomicLong()
}
