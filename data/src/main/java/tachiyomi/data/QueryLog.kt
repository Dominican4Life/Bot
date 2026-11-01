package tachiyomi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import logcat.LogPriority

/**
 * KMK (r104): writes `D db <name> query: N rows in X ms` for the first list each collection of this flow emits,
 * so once per screen open (and per filter or search change, which starts a new collection). The re-queries
 * that follow database writes aren't logged. X is from the start of the collection to the first list.
 */
internal fun <T> Flow<List<T>>.logFirstQuery(name: String): Flow<List<T>> {
    val upstream = this
    return flow {
        val start = System.nanoTime()
        var first = true
        upstream.collect { list ->
            if (first) {
                first = false
                val ms = (System.nanoTime() - start) / 1_000_000
                logcat.logcat("db", LogPriority.DEBUG) { "$name query: ${list.size} rows in $ms ms" }
            }
            emit(list)
        }
    }
}
