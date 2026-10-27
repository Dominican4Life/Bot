package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.paging.PagingData
import androidx.paging.cachedIn
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: The lists of a source's Browse tabs (Popular, Latest, a JS source's tabs, saved searches), kept until the app
 * closes so switching tabs, or leaving and coming back, doesn't load them from page 1 again. Each tab also keeps its
 * scroll position. Tapping the selected tab drops its list ([drop]) so it loads fresh.
 *
 * Only the titles are kept (name, link, cover address), not the covers, which the image cache holds as usual.
 */
object BrowseTabCache {

    private class Entry(
        val scope: CoroutineScope,
        val flow: Flow<PagingData<Pair<Manga, RaisedSearchMetadata?>>>,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Each source's Filter tab (its last filter or typed search), by screen and source, until Clear */
    val filterTabs = ConcurrentHashMap<String, Any>()
    private val scrolls = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** KMK: Browse tabs kept now (for the memory lines in Logs) */
    val size: Int get() = entries.size

    /** KMK: drops all but the [keep] most recently used tabs (memory running low) */
    fun trim(keep: Int) {
        entries.keys.sortedByDescending { used[it] ?: 0L }.drop(keep).forEach { drop(it) }
    }

    /** The kept list for [key], made with [create] the first time */
    fun pager(
        key: String,
        create: () -> Flow<PagingData<Pair<Manga, RaisedSearchMetadata?>>>,
    ): Flow<PagingData<Pair<Manga, RaisedSearchMetadata?>>> {
        val entry = entries.getOrPut(key) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            Entry(scope, create().cachedIn(scope))
        }
        // KMK: the MAX_TABS most recently used tabs are kept; older ones load from page 1 again. Every tab of every
        // source browsed was kept until the app closed, which added up over a long session
        used[key] = System.nanoTime()
        if (entries.size > MAX_TABS) {
            entries.keys.filter { it != key }.sortedBy { used[it] ?: 0L }.take(entries.size - MAX_TABS)
                .forEach { drop(it) }
        }
        return entry.flow
    }

    private val used = ConcurrentHashMap<String, Long>()
    private const val MAX_TABS = 10

    /** Forgets the list and scroll position of [key], so it loads from page 1 the next time */
    fun drop(key: String) {
        entries.remove(key)?.scope?.cancel()
        scrolls.remove(key)
        used.remove(key)
    }

    /** Where [key]'s list was scrolled to: first visible item and its offset */
    fun scroll(key: String): Pair<Int, Int>? = scrolls[key]

    fun saveScroll(key: String, index: Int, offset: Int) {
        scrolls[key] = index to offset
    }
}
