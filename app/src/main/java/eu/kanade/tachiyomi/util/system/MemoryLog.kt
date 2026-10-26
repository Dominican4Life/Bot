package eu.kanade.tachiyomi.util.system

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KMK: Writes how much of the app's memory is in use to Logs ("Memory") every few minutes, with the screen shown at
 * the time, so a session that runs out of memory shows what it was doing while memory filled up.
 */
object MemoryLog {

    /** The screen on top, set by the screen transitions */
    @Volatile
    var currentScreen: String = "-"

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                delay(EVERY_MS)
                val runtime = Runtime.getRuntime()
                val used = (runtime.totalMemory() - runtime.freeMemory()) / MB
                val max = runtime.maxMemory() / MB
                val level = if (used * 100 / max.coerceAtLeast(1) >= 80) 'W' else 'I'
                AppLog.add(level, "Memory", "$used / $max MB in use (screen: $currentScreen)")
            }
        }
    }

    /** A readable name from a screen's key ("Screen#…#AnimeSourceScreen", or a class name) */
    fun nameOf(key: String): String = key.substringAfterLast('#').substringAfterLast('.')

    private const val EVERY_MS = 5 * 60_000L
    private const val MB = 1024L * 1024L
}
