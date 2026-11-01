package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Think twice before using this. This is a delicate API. It is easy to accidentally create resource or memory leaks when GlobalScope is used.
 *
 * **Possible replacements**
 * - suspend function
 * - custom scope like view or presenter scope
 */
@DelicateCoroutinesApi
fun launchUI(block: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT, block)

/**
 * Think twice before using this. This is a delicate API. It is easy to accidentally create resource or memory leaks when GlobalScope is used.
 *
 * **Possible replacements**
 * - suspend function
 * - custom scope like view or presenter scope
 */
@DelicateCoroutinesApi
fun launchIO(block: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.IO, CoroutineStart.DEFAULT, block)

/**
 * Think twice before using this. This is a delicate API. It is easy to accidentally create resource or memory leaks when GlobalScope is used.
 *
 * **Possible replacements**
 * - suspend function
 * - custom scope like view or presenter scope
 */
@DelicateCoroutinesApi
fun launchNow(block: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED, block)

fun CoroutineScope.launchUI(block: suspend CoroutineScope.() -> Unit): Job =
    launch(Dispatchers.Main, block = block)

fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit): Job =
    launch(Dispatchers.IO, block = block)

fun CoroutineScope.launchNonCancellable(block: suspend CoroutineScope.() -> Unit): Job =
    launchIO { withContext(NonCancellable, block) }

suspend fun <T> withUIContext(block: suspend CoroutineScope.() -> T) = withContext(
    Dispatchers.Main,
    block,
)

suspend fun <T> withIOContext(block: suspend CoroutineScope.() -> T) = withContext(
    Dispatchers.IO,
    block,
)

suspend fun <T> withNonCancellableContext(block: suspend CoroutineScope.() -> T) =
    withContext(NonCancellable, block)

// KMK -->
/**
 * [debounce] that lets the first value through at once and only debounces the ones after it,
 * so a flow with an initial/replayed value (a database subscription, a shared `changes` flow)
 * still shows its first result without waiting [timeoutMillis].
 */
fun <T> Flow<T>.debounceAfterFirst(timeoutMillis: Long): Flow<T> = flow {
    var first = true
    emitAll(
        debounce {
            if (first) {
                first = false
                0L
            } else {
                timeoutMillis
            }
        },
    )
}
// KMK <--
