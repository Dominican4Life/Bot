package eu.kanade.tachiyomi.util.system

import android.app.Application
import android.os.Debug
import coil3.imageLoader
import eu.kanade.tachiyomi.data.cache.CoverFolders
import eu.kanade.tachiyomi.diagnostics.Health
import eu.kanade.tachiyomi.diagnostics.HealthKind
import eu.kanade.tachiyomi.diagnostics.LiveSampler
import eu.kanade.tachiyomi.diagnostics.LogCapture
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
            var detailAt = 0L
            var lastImagesMb = 0L
            var lastJsMb = 0f
            // KMK (r103): while memory is high but steady, the 10 s line is skipped (see HIGH_STEP_MB); counted here
            // and reported in one D line at most every SKIP_REPORT_MS
            var lastNativeLogged = 0L
            var skippedHigh = 0
            var skipReportAt = 0L
            while (true) {
                delay(SAMPLE_MS)
                runCatching {
                    val (used, max) = usage()
                    val now = System.currentTimeMillis()
                    // KMK (r97): the coarse memory rings of the Diagnostics Live tab (24 h / 7 d) are fed from here, so
                    // they exist without the screen open: heap, images, JS (tryLock) and native, one float each.
                    // The Coil size and the JS runtimes' memory are read every minute unless a Diagnostics strip or
                    // the Live page is open (then every tick; the full sampler usually covers it anyway).
                    runCatching {
                        if (LiveSampler.wantsMemoryDetail() || now - detailAt >= 60_000L) {
                            detailAt = now
                            lastImagesMb = (Injekt.get<Application>().imageLoader.memoryCache?.size ?: 0L) / MB
                            lastJsMb = Injekt.get<JsSourceManager>().liveStats().sumOf { it.mb.toDouble() }.toFloat()
                        }
                        LiveSampler.memoryTick(used, lastImagesMb, lastJsMb, nativeMb())
                    }
                    // KMK (r97): cadence from the Capture tab's sampling slider (0.25 s → a line every 10 s, 30 s →
                    // every 2 min) and every 10 s while a recording runs; Normal keeps the minute
                    val everyMs = when {
                        LogCapture.recording.value != null -> SAMPLE_MS
                        LogCapture.sampleMs <= 250L -> SAMPLE_MS
                        LogCapture.sampleMs >= 30_000L -> 2 * EVERY_MS
                        else -> EVERY_MS
                    }
                    // Every minute, and every 10 seconds while memory climbs fast or is high
                    val climbing = used - lastLogged >= CLIMB_MB
                    val high = used * 100 / max.coerceAtLeast(1) >= HIGH_PERCENT
                    // KMK (r103): a high reading writes a line only when it moved since the last line (heap or
                    // native by HIGH_STEP_MB or more); a steady high heap falls back to the minute (most of a
                    // reading session sits above 60 %, which wrote a near-identical line every 10 s)
                    val native = if (high) nativeMb() else lastNativeLogged
                    val highMoved = high && (
                        kotlin.math.abs(used - lastLogged) >= HIGH_STEP_MB ||
                            kotlin.math.abs(native - lastNativeLogged) >= HIGH_STEP_MB
                        )
                    if (now - lastLineAt >= everyMs || climbing || highMoved) {
                        line(if (climbing) "climbing +${used - lastLogged} MB" else null)
                        lastLineAt = now
                        lastLogged = used
                        lastNativeLogged = nativeMb()
                    } else if (high) {
                        skippedHigh++
                    }
                    if (skippedHigh > 0 && now - skipReportAt >= SKIP_REPORT_MS) {
                        if (skipReportAt > 0L) {
                            AppLog.add(
                                'D',
                                "Memory",
                                "$skippedHigh high-memory lines skipped in the last ${SKIP_REPORT_MS / 60_000} min: " +
                                    "use moved less than $HIGH_STEP_MB MB (a line is still written every minute)",
                            )
                            skippedHigh = 0
                        }
                        skipReportAt = now
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
    internal fun usage(): Pair<Long, Long> {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB to runtime.maxMemory() / MB
    }

    /** KMK (r95): the native heap (bitmap pixels, QuickJS, decoders), which the Java figures leave out */
    internal fun nativeMb(): Long = Debug.getNativeHeapAllocatedSize() / MB

    private fun line(event: String?) {
        val (used, max) = usage()
        val level = if (used * 100 / max.coerceAtLeast(1) >= 75) 'W' else 'I'
        val native = nativeMb()
        // KMK (r97): the text is unchanged; the structured tail (used_mb= max_mb= native_mb= screen=) is new
        AppLog.add(
            level,
            "Memory",
            "$used / $max MB in use (screen: $currentScreen${event?.let { ", $it" } ?: ""})" +
                " · native $native MB · " + caches(),
            "used_mb" to used,
            "max_mb" to max,
            "native_mb" to native,
            "screen" to currentScreen,
        )
    }

    // KMK (r97) -->
    /** What the app's own caches hold (the figures behind the Memory line, read by the Diagnostics Memory page too) */
    data class CacheStats(
        val imagesBytes: Long = 0L,
        val imagesMaxBytes: Long = 0L,
        val browseTabs: Int = 0,
        val folderListings: Int = 0,
        val jsRunning: Int = 0,
        val jsPages: Int = 0,
        val jsPageTextBytes: Long = 0L,
        val jsDomBytes: Long = 0L,
        val animatedTiles: Int = 0,
        /** Which figures could be read (a missing one is left out of the line, as before) */
        val hasImages: Boolean = false,
        val hasBrowseTabs: Boolean = false,
        val hasListings: Boolean = false,
        val hasJsRunning: Boolean = false,
        val hasJsPages: Boolean = false,
        val hasTiles: Boolean = false,
    )

    fun cacheStats(): CacheStats {
        var s = CacheStats()
        runCatching {
            val cache = Injekt.get<Application>().imageLoader.memoryCache
            if (cache != null) s = s.copy(imagesBytes = cache.size, imagesMaxBytes = cache.maxSize, hasImages = true)
        }
        runCatching { s = s.copy(browseTabs = BrowseTabCache.size, hasBrowseTabs = true) }
        runCatching { s = s.copy(folderListings = CoverFolders.listingCount, hasListings = true) }
        runCatching { s = s.copy(jsRunning = Injekt.get<JsSourceManager>().runningCount, hasJsRunning = true) }
        runCatching {
            val (pages, bytes, dom) = eu.kanade.tachiyomi.jssource.JsHtmlPages.totalsWithDom()
            s = s.copy(jsPages = pages, jsPageTextBytes = bytes, jsDomBytes = dom, hasJsPages = true)
        }
        runCatching {
            s = s.copy(animatedTiles = eu.kanade.presentation.components.AnimatedTiles.count.get(), hasTiles = true)
        }
        return s
    }
    // KMK <--

    /** The same string as before r97, built on top of [cacheStats] */
    private fun caches(): String = cacheStats().let { s ->
        buildList {
            if (s.hasImages) add("images ${s.imagesBytes / MB}/${s.imagesMaxBytes / MB} MB")
            if (s.hasBrowseTabs) add("Browse tabs ${s.browseTabs}")
            if (s.hasListings) add("folder listings ${s.folderListings}")
            if (s.hasJsRunning) add("JS sources running ${s.jsRunning}")
            if (s.hasJsPages) {
                add("JS parsed pages ${s.jsPages} (page text ${s.jsPageTextBytes / MB} MB, DOM about ${s.jsDomBytes / MB} MB)")
            }
            if (s.hasTiles) add("animated tiles ${s.animatedTiles}")
        }.joinToString(", ")
    }

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
        val text = "Memory low ($used / $max MB, native ${nativeMb()} MB): cleared images in memory, older Browse tabs, " +
            "folder listings and $jsPages JS parsed pages"
        AppLog.add('W', "Memory", text)
        // KMK (r97): a Health event (TRIM dot on the 7-day strip) and the Memory page's "last trim"
        runCatching { Health.event(HealthKind.TRIM, "Memory low, caches cleared", text) }
        runCatching { LiveSampler.noteTrim(text) }
    }

    // KMK (r97) -->
    /** "Trim now" / "free memory" on the Diagnostics screen: the same trim, with its result as text */
    fun trimNow(): String {
        val (used, max) = usage()
        val before = used
        trim(used, max)
        val (after, _) = usage()
        return "Freed caches: $before → $after MB in use (ART collects the rest on its own)"
    }
    // KMK <--

    /** A readable name from a screen's key ("Screen#…#AnimeSourceScreen", or a class name) */
    fun nameOf(key: String): String = key.substringAfterLast('#').substringAfterLast('.')

    private const val EVERY_MS = 60_000L
    private const val SAMPLE_MS = 10_000L
    private const val CLIMB_MB = 40L
    private const val HIGH_PERCENT = 60L

    /** KMK (r103): a high reading moved this much (heap or native) since the last line: write it at once */
    private const val HIGH_STEP_MB = 16L

    /** KMK (r103): at most one "lines skipped" D line this often */
    private const val SKIP_REPORT_MS = 10 * 60_000L
    private const val MB = 1024L * 1024L
    private const val TRIM_AT = 0.75
    /** Samples in a row (10 s apart) past [TRIM_AT] before a trim */
    private const val TRIM_AFTER_SAMPLES = 2
    /** No second trim within this time of the last one */
    private const val TRIM_COOLDOWN_MS = 120_000L
}
