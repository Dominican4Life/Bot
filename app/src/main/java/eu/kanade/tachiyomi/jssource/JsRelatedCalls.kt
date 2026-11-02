package eu.kanade.tachiyomi.jssource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * KMK (r107): one getRelated call per title, shared by everyone who asks for it.
 *
 * Why: the title page and the Suggestions screen both ask for a title's suggestions. A source's calls run one at a
 * time (JsRuntime's mutex) and a getRelated that reads a section with the browser can take 20–50 s, so the Suggestions
 * screen opened meanwhile waited behind the title page's call (and would then have started a second, equally slow
 * one). When that wait was cancelled (the screen left), the runCatching around the call caught the cancellation and
 * logged it as "getRelated failed" with no message.
 *
 * Now the call runs in its own scope (a caller that leaves doesn't cancel it for the others), every caller awaits the
 * same result, a result is kept for [KEEP_MS], and a failure is not kept (the next caller tries again). A call that
 * takes longer than [LIMIT_MS] fails with a message instead of spinning for ever.
 */
internal object JsRelatedCalls {

    private const val KEEP_MS = 5 * 60_000L
    private const val LIMIT_MS = 120_000L
    private const val MAX_KEPT = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Call(val startedAt: Long) {
        lateinit var result: Deferred<JsonElement>

        /** Not kept for the next caller: it failed, or (KMK (r107) fix) it found no suggestions */
        @Volatile
        var failed = false
    }

    private val calls = LinkedHashMap<String, Call>()

    /** The result of [load] for [key] (source key + title url): the call running or kept, or a new one */
    suspend fun get(key: String, load: suspend () -> JsonElement): JsonElement {
        val call = synchronized(calls) {
            val now = System.currentTimeMillis()
            calls.entries.removeAll { entry ->
                val c = entry.value
                c.failed || (c.result.isCompleted && now - c.startedAt > KEEP_MS)
            }
            calls[key] ?: run {
                // KMK (r107) fix: only a finished call makes room, never one still running (its callers await it)
                if (calls.size >= MAX_KEPT) {
                    calls.entries.firstOrNull { it.value.result.isCompleted }?.let { calls.remove(it.key) }
                }
                val fresh = Call(now)
                fresh.result = scope.async {
                    try {
                        // KMK (r107) fix: no suggestions isn't kept either (the next caller asks again)
                        limited(load).also { if (isEmpty(it)) fresh.failed = true }
                    } catch (e: Throwable) {
                        fresh.failed = true
                        throw e
                    }
                }
                calls[key] = fresh
                fresh
            }
        }
        return call.result.await()
    }

    /**
     * [load] with a time limit. The limit waits on the result rather than wrapping the call, so it fires on time even
     * if the engine doesn't stop at once when cancelled; the call is then cancelled (best effort). A time-out is a
     * failure with a reason, not a cancellation of the callers
     */
    private suspend fun limited(load: suspend () -> JsonElement): JsonElement {
        val running = scope.async { load() }
        val result = withTimeoutOrNull(LIMIT_MS) { running.await() }
        if (result == null) {
            running.cancel()
            throw JsRuntime.JsException("getRelated took longer than ${LIMIT_MS / 1000} s and was stopped")
        }
        return result
    }

    /** KMK (r107) fix: no suggestions: null, an empty list, or groups that are all empty */
    private fun isEmpty(e: JsonElement): Boolean = when (e) {
        is JsonNull -> true
        is JsonArray -> e.isEmpty()
        is JsonObject -> e.values.all { v -> v is JsonNull || (v is JsonArray && v.isEmpty()) }
        else -> false
    }

    /** True when [e] means the caller stopped waiting (it left the screen), not that the source failed */
    fun isCancellation(e: Throwable): Boolean = e is CancellationException && e !is TimeoutCancellationException
}
