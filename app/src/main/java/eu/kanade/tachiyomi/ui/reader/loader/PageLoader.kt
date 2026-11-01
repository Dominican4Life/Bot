package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.diagnostics.ReaderTrace
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    // KMK (r97) -->
    /**
     * Diagnostics reader trace: runs [block] (one page's fetch) with a timer and writes one record when the trace or
     * a probe wants it (one volatile read otherwise). [loader] names the loader (Http, Download, Archive…);
     * [cacheHit] says the page came from the reader cache.
     */
    protected suspend fun traceLoad(
        page: ReaderPage,
        loader: String,
        cacheHit: () -> Boolean,
        block: suspend () -> Unit,
    ) {
        if (!ReaderTrace.wanted()) return block()
        val started = System.nanoTime()
        var error: String? = null
        try {
            block()
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val chapter = runCatching { page.chapter.chapter.name }.getOrDefault("")
            ReaderTrace.page(
                chapter, page.index, loader, (System.nanoTime() - started) / 1_000_000, cacheHit(),
                0L, error,
            )
        }
    }
    // KMK <--

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
