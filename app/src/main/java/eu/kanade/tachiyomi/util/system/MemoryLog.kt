package eu.kanade.tachiyomi.util.system

import android.app.Application
import coil3.imageLoader
import eu.kanade.tachiyomi.data.cache.CoverFolders
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseTabCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KMK: Writes how much of the app's memory is in use to Logs ("Memory") every minute, and when the reader opens or
 * closes, with the screen shown and what the app's own caches hold, so a session that runs out of memory shows what
 * kept growing.
 *
 * Past [TRIM_AT] of the limit it lets go of what can be loaded again (images kept in memory, older Browse tabs,
 * folder listings) instead of running out, and says so in Logs.
 */
object MemoryLog {

    /** The screen on top, set by the screen transitions and the reader */
    @Volatile
    var currentScreen: String = "-"

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                delay(EVERY_MS)
                check()
            }
        }
    }

    /** The reader opened (true) or closed: a line right away, labelled Reader while it's open */
    private var beforeReader = "-"

    fun reader(open: Boolean) {
        if (open) {
            if (currentScreen != "Reader") beforeReader = currentScreen
            currentScreen = "Reader"
        } else if (currentScreen == "Reader") {
            currentScreen = beforeReader
        }
        runCatching { line(if (open) "reader opened" else "reader closed") }
    }

    private fun check() {
        runCatching {
            val (used, max) = usage()
            line(null)
            if (used >= max * TRIM_AT) trim(used, max)
        }
    }

    private fun usage(): Pair<Long, Long> {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB to runtime.maxMemory() / MB
    }

    private fun line(event: String?) {
        val (used, max) = usage()
        val level = if (used * 100 / max.coerceAtLeast(1) >= 75) 'W' else 'I'
        AppLog.add(level, "Memory", "$used / $max MB in use (screen: $currentScreen${event?.let { ", $it" } ?: ""})" + " · " + caches())
    }

    private fun caches(): String = buildList {
        runCatching {
            val cache = Injekt.get<Application>().imageLoader.memoryCache
            if (cache != null) add("images ${cache.size / MB}/${cache.maxSize / MB} MB")
        }
        runCatching { add("Browse tabs ${BrowseTabCache.size}") }
        runCatching { add("folder listings ${CoverFolders.listingCount}") }
        runCatching { add("JS sources running ${Injekt.get<JsSourceManager>().runningCount}") }
    }.joinToString(", ")

    /** Memory running low: let go of what can be loaded again */
    private fun trim(used: Long, max: Long) {
        runCatching { Injekt.get<Application>().imageLoader.memoryCache?.clear() }
        runCatching { BrowseTabCache.trim(keep = 2) }
        runCatching { CoverFolders.dropListings() }
        System.gc()
        val (after, _) = usage()
        AppLog.add('W', "Memory", "Memory low ($used / $max MB): cleared images in memory, older Browse tabs and folder listings; now $after MB")
    }

    /** A readable name from a screen's key ("Screen#…#AnimeSourceScreen", or a class name) */
    fun nameOf(key: String): String = key.substringAfterLast('#').substringAfterLast('.')

    private const val EVERY_MS = 60_000L
    private const val MB = 1024L * 1024L
    private const val TRIM_AT = 0.75
}
