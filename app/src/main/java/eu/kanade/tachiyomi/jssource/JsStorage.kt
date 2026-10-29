package eu.kanade.tachiyomi.jssource

import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: A source's `host.storage` (`js_storage/<key>/storage.json`), one shared copy per folder for the whole app.
 * Every runtime of the same source (Browse, a tag download, the editor) used to read the file into a cache of its
 * own and write that back whole, so one runtime overwrote what another had just written. Now they all read and
 * write the same in-memory object, and the file is written from it.
 */
internal object JsStorage {

    private val stores = ConcurrentHashMap<String, Store>()

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

    class Store(private val dir: File) {

        private val file get() = File(dir, "storage.json")
        private var cache: MutableMap<String, JsonElement>? = null

        // KMK: set by forget(); a closed copy is never written to disk again
        private var closed = false

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
            write(map)
        }

        /** Replaces everything with [values] (the editor's test copy, filled from the saved source's) */
        @Synchronized
        fun replaceAll(values: Map<String, JsonElement>) {
            val map = values.toMutableMap()
            cache = map
            write(map)
        }

        @Synchronized
        internal fun close() {
            closed = true
            cache = mutableMapOf()
        }

        private fun write(map: Map<String, JsonElement>) {
            if (closed) return
            runCatching {
                dir.mkdirs()
                file.writeText(JsonObject(map).toString())
            }.onFailure { AppLog.add('W', "js", "Couldn't write ${dir.name}/storage.json: ${it.message}") }
        }
    }
}
