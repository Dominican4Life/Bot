package eu.kanade.tachiyomi.jssource

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * KMK: A source's `host.storage` (`js_storage/<key>/storage.json`), one shared copy per folder for the whole app.
 * Every runtime of the same source (Browse, a tag download, the editor) used to read the file into a cache of its
 * own and write that back whole, so one runtime overwrote what another had just written. Now they all read and
 * write the same in-memory object, and the file is written from it. KMK (r103): written about 1 s after a change
 * (write-behind), on its own thread, and at once when the app goes to the background.
 */
internal object JsStorage {

    private val stores = ConcurrentHashMap<String, Store>()

    /** KMK (r103): a change is written this long after the first unsaved one (sets in between share the write) */
    private const val WRITE_DELAY_MS = 1_000L

    /** KMK (r103): at most one "N changes in one write" D line per store this often */
    private const val LOG_EVERY_MS = 60_000L

    /** KMK (r103): the one thread that writes storage.json files, off the JS callers' threads */
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "js-storage-writer").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    init {
        // KMK (r103): pending writes go out when the app goes to the background (it may be killed there). The
        // observer is added on the main thread, which ProcessLifecycleOwner requires
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(
                        object : DefaultLifecycleObserver {
                            override fun onStop(owner: LifecycleOwner) {
                                flushAllSoon()
                            }
                        },
                    )
                }
            }
        }
    }

    /** The shared storage kept in [dir] */
    fun of(dir: File): Store = stores.computeIfAbsent(dir.absolutePath) { Store(dir) }

    /**
     * Lets go of the copy in memory for [dir] (its folder is being deleted), so nothing old is written back. KMK: the
     * old copy is closed too: a runtime still holding it (Browse, a tag download) used to keep writing, and each
     * write made the deleted folder again. Its later writes are now ignored; [of] gives a new copy.
     */
    fun forget(dir: File) {
        stores.remove(dir.absolutePath)?.close()
    }

    /** KMK (r103): writes every store with unsaved changes now, on the writer thread (the app went to the background) */
    fun flushAllSoon() {
        runCatching { writer.execute { stores.values.forEach { it.flush() } } }
    }

    /**
     * KMK (r103 fix pass): writes every store with unsaved changes on the calling thread, before returning: for the
     * crash handler (the process ends right after) and a runtime's close off the main thread. Not for the main thread
     * in normal use (file writes).
     */
    fun flushAllNow() {
        stores.values.forEach { store -> runCatching { store.flush() } }
    }

    class Store(private val dir: File) {

        private val file get() = File(dir, "storage.json")
        private var cache: MutableMap<String, JsonElement>? = null

        // KMK: set by forget(); a closed copy is never written to disk again
        @Volatile
        private var closed = false

        // KMK (r103) -->
        /** Changes since the last write (0 = nothing to write) */
        private var unsaved = 0

        /** A write is scheduled on [writer] */
        private var scheduled = false

        /** Keeps two writes of the same file in order (the text is built under the store's lock, written outside) */
        private val fileLock = Any()

        private var lastLogAt = 0L
        // KMK <--

        private fun map(): MutableMap<String, JsonElement> = cache ?: runCatching {
            (jsJson.parseToJsonElement(file.readText()) as JsonObject).toMutableMap()
        }.getOrElse { mutableMapOf() }.also { cache = it }

        @Synchronized
        fun get(key: String): JsonElement? = map()[key]

        /** A copy of everything stored */
        @Synchronized
        fun all(): Map<String, JsonElement> = LinkedHashMap(map())

        @Synchronized
        fun edit(change: (MutableMap<String, JsonElement>) -> Unit) {
            val map = map()
            change(map)
            markChanged()
        }

        /** Replaces everything with [values] (the editor's test copy, filled from the saved source's) */
        @Synchronized
        fun replaceAll(values: Map<String, JsonElement>) {
            val map = values.toMutableMap()
            cache = map
            markChanged()
        }

        /** Waits for a write in progress (the folder is about to be deleted), then drops everything unsaved */
        internal fun close() {
            synchronized(fileLock) {
                synchronized(this) {
                    closed = true
                    cache = mutableMapOf()
                    unsaved = 0
                }
            }
        }

        /**
         * KMK (r103): write-behind. Every runtime reads and writes the copy in memory, so a later write is only
         * about keeping the file for the next start: it goes out [WRITE_DELAY_MS] after the first unsaved change
         * (every set used to rewrite the whole JSON on the caller's thread).
         */
        private fun markChanged() {
            if (closed) return
            unsaved++
            if (scheduled) return
            scheduled = true
            // A failed schedule (never expected: the writer is never shut down) leaves the change for the next one
            runCatching { JsStorage.writer.schedule(Runnable { flush() }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS) }
                .onFailure { scheduled = false }
        }

        /** Writes the unsaved changes, if any */
        fun flush() {
            val snapshot: Pair<Int, String> = synchronized(this) {
                scheduled = false
                if (closed || unsaved == 0) return
                val changes = unsaved
                unsaved = 0
                changes to JsonObject(map()).toString()
            }
            val (changes, text) = snapshot
            val started = System.nanoTime()
            // Lock order: fileLock is never taken while holding the store's own lock here; close() takes fileLock
            // first, then the store's lock
            synchronized(fileLock) {
                // KMK: closed while the text was being built: the folder is going away, don't make it again
                if (closed) return
                runCatching {
                    dir.mkdirs()
                    // Written to a temp file and renamed, so a kill during the write can't leave half a file
                    val tmp = File(dir, "storage.json.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text)
                        tmp.delete()
                    }
                }.onFailure { AppLog.add('W', "js", "Couldn't write ${dir.name}/storage.json: ${it.message}") }
            }
            val now = System.currentTimeMillis()
            if (changes > 1 && now - lastLogAt >= LOG_EVERY_MS) {
                lastLogAt = now
                val ms = (System.nanoTime() - started) / 1_000_000
                AppLog.add(
                    'D',
                    "js",
                    "storage ${dir.name}: $changes changes saved in one write (${text.length / 1024} KB, $ms ms)",
                )
            }
        }
    }
}
