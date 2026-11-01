package eu.kanade.tachiyomi.diagnostics

import android.os.Build
import android.os.Trace

/**
 * KMK (r97): Perfetto / systrace sections (design §9). `Trace.beginSection` checks an atomic tag mask first, so a
 * section costs nanoseconds when nothing records. Names are prefixed `komikku:`, cut to 127 chars, and never carry
 * a URL.
 */
internal object Perf {

    private const val MAX = 127
    private const val PREFIX = "komikku:"

    fun name(name: String): String = (PREFIX + name).let { if (it.length > MAX) it.take(MAX) else it }

    /** True while something records a trace (API 29+; assumed on below, where the check doesn't exist) */
    fun isEnabled(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Trace.isEnabled()

    /** Runs [block] inside a synchronous section */
    inline fun <T> traced(name: String, block: () -> T): T = traced({ name }, block)

    /** The same with a lazily built name: nothing is concatenated unless a trace records */
    inline fun <T> traced(lazyName: () -> String, block: () -> T): T {
        val on = isEnabled()
        if (on) Trace.beginSection(name(lazyName()))
        try {
            return block()
        } finally {
            if (on) Trace.endSection()
        }
    }

    /** Starts an async section (start and end on different threads); API 29+, a no-op below */
    fun beginAsync(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(name(name), cookie)
    }

    fun endAsync(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection(name(name), cookie)
    }

    /** Begin/end pair as one object, for call sites that keep it in a field */
    class Span(private val name: String, private val cookie: Int) {
        init {
            beginAsync(name, cookie)
        }

        fun end() = endAsync(name, cookie)
    }

    fun tracedAsync(name: String, cookie: Int): Span = Span(name, cookie)
}
