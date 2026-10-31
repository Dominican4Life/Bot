package eu.kanade.tachiyomi.util.system

import android.app.Application
import android.os.Debug
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
 * KMK: Writes how much of the app's memory is in use to Logs ("Memory") every minute (every 10 s while it climbs
 * fast or is high), at screen changes and heavy jobs ([event]), and when the reader opens or
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
            var lastLineAt = 0L
            var lastLogged = 0L
            // KMK (r95): one high sample is often just garbage waiting for the next collection (ART lets the heap
            // reach about 75 % before it collects), so a trim needs two high samples in a row and a cooldown
            var highSamples = 0
            var lastTrimAt = 0L
            while (true) {
                delay(SAMPLE_MS)
                runCatching {
                    val (used, max) = usage()
                    val now = System.currentTimeMillis()
                    // Every minute, and every 10 seconds while memory climbs fast or is high
                    val climbing = used - lastLogged >= CLIMB_MB
                    val high = used * 100 / max.coerceAtLeast(1) >= HIGH_PERCENT
                    if (now - lastLineAt >= EVERY_MS || climbing || high) {
                        line(if (climbing) "climbing +${used - lastLogged} MB" else null)
                        lastLineAt = now
                        lastLogged = used
                    }
                    highSamples = if (used >= max * TRIM_AT) highSamples + 1 else 0
                    if (highSamples >= TRIM_AFTER_SAMPLES && now - lastTrimAt >= TRIM_COOLDOWN_MS) {
                        lastTrimAt = now
                        highSamples = 0
                        trim(used, max)
                    }
                }
            }
        }
    }

    /**
     * A heavy job starting or ending (gallery previews, a tag download, a page opened…), with the memory in use at
     * that moment, so a jump in the memory lines can be matched to what was running
     */
    fun event(what: String) {
        runCatching {
            val (used, max) = usage()
            AppLog.add('I', "Memory", "$what · $used / $max MB in use")
        }
    }

    /** The screen changed: one short line, so the memory lines can be matched to screens */
    fun screen(name: String) {
        if (name == currentScreen) return
        currentScreen = name
        event("screen: $name")
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

    /** Java heap in use and its limit, in MB. Bitmaps and the JS engines live in the native heap, see [nativeMb] */
    private fun usage(): Pair<Long, Long> {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB to runtime.maxMemory() / MB
    }

    /** KMK (r95): the native heap (bitmap pixels, QuickJS, decoders), which the Java figures leave out */
    private fun nativeMb(): Long = Debug.getNativeHeapAllocatedSize() / MB

    private fun line(event: String?) {
        val (used, max) = usage()
        val level = if (used * 100 / max.coerceAtLeast(1) >= 75) 'W' else 'I'
        AppLog.add(
            level,
            "Memory",
            "$used / $max MB in use (screen: $currentScreen${event?.let { ", $it" } ?: ""})" +
                " · native ${nativeMb()} MB · " + caches(),
        )
    }

    private fun caches(): String = buildList {
        runCatching {
            val cache = Injekt.get<Application>().imageLoader.memoryCache
            if (cache != null) add("images ${cache.size / MB}/${cache.maxSize / MB} MB")
        }
        runCatching { add("Browse tabs ${BrowseTabCache.size}") }
        runCatching { add("folder listings ${CoverFolders.listingCount}") }
        runCatching { add("JS sources running ${Injekt.get<JsSourceManager>().runningCount}") }
        runCatching {
            val (pages, bytes, dom) = eu.kanade.tachiyomi.jssource.JsHtmlPages.totalsWithDom()
            add("JS parsed pages $pages (page text ${bytes / MB} MB, DOM about ${dom / MB} MB)")
        }
        runCatching { add("animated tiles ${eu.kanade.presentation.components.AnimatedTiles.count.get()}") }
    }.joinToString(", ")

    /**
     * Memory running low: let go of what can be loaded again. KMK (r95): no forced System.gc() any more (ART
     * collects on its own once the caches are released; a forced full collection only added a pause), and the JS
     * sources' parsed pages are released too.
     */
    private fun trim(used: Long, max: Long) {
        runCatching { Injekt.get<Application>().imageLoader.memoryCache?.clear() }
        runCatching { BrowseTabCache.trim(keep = 2) }
        runCatching { CoverFolders.dropListings() }
        val jsPages = runCatching { eu.kanade.tachiyomi.jssource.JsHtmlPages.clearAll() }.getOrDefault(0)
        AppLog.add(
            'W',
            "Memory",
            "Memory low ($used / $max MB, native ${nativeMb()} MB): cleared images in memory, older Browse tabs, " +
                "folder listings and $jsPages JS parsed pages",
        )
    }

    /** A readable name from a screen's key ("Screen#…#AnimeSourceScreen", or a class name) */
    fun nameOf(key: String): String = key.substringAfterLast('#').substringAfterLast('.')

    private const val EVERY_MS = 60_000L
    private const val SAMPLE_MS = 10_000L
    private const val CLIMB_MB = 40L
    private const val HIGH_PERCENT = 60L
    private const val MB = 1024L * 1024L
    private const val TRIM_AT = 0.75
    /** Samples in a row (10 s apart) past [TRIM_AT] before a trim */
    private const val TRIM_AFTER_SAMPLES = 2
    /** No second trim within this time of the last one */
    private const val TRIM_COOLDOWN_MS = 120_000L
}
