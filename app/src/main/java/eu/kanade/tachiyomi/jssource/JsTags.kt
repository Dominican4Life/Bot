package eu.kanade.tachiyomi.jssource

import android.app.Application
import android.text.format.DateUtils
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: Tag lists for a source's autocomplete filters, from its `getTags()`.
 *
 * getTags() is run in the background, in a runtime of its own so Browse isn't held up while it reads the site, and
 * what it returns is kept on the device and read again after [MAX_AGE_MS]. The filter boxes read the lists through
 * [suggestions], which follows the lists as they arrive: a box made before the download finished still gets them.
 *
 * getTags() returns `{ listName: ["tag", ...], ... }`. A plain array is one list every box uses.
 */
class JsTags(
    private val context: Application,
    private val codeOf: (String) -> String?,
) {

    @Serializable
    data class Cached(
        val at: Long = 0L,
        val lists: Map<String, List<String>> = emptyMap(),
    ) {
        val count: Int get() = lists.values.sumOf { it.size }
    }

    private val dir = File(context.filesDir, "js_tags").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** When getTags() last failed, so a broken source isn't asked again every time its filters are opened */
    private val failedAt = ConcurrentHashMap<String, Long>()

    private fun file(key: String) = File(dir, "$key.json")

    /** The lists saved for [key], read from the device the first time */
    fun cached(key: String): Cached? = cache[key] ?: runCatching {
        file(key).takeIf { it.exists() }
            ?.let { jsJson.decodeFromString(Cached.serializer(), it.readText()) }
            ?.also { cache[key] = it }
    }.getOrNull()

    /** Gets the tag lists ready: reads them from the site in the background when they're missing or old */
    fun ensure(info: JsSourceInfo) {
        if (!info.hasTags) return
        val failed = failedAt[info.key]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_FAILURE_MS) return
        // Failures are logged and remembered in load(); nothing here may throw out of the scope
        scope.launch { runCatching { load(info, force = false) } }
    }

    /** Reads the tag lists from the site now, whatever their age. [onDone] gets the number of tags, on the main thread */
    fun refresh(info: JsSourceInfo, onDone: (Result<Int>) -> Unit) {
        scope.launch {
            val result = runCatching { load(info, force = true) }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun forget(key: String) {
        cache.remove(key)
        failedAt.remove(key)
        file(key).delete()
    }

    private suspend fun load(info: JsSourceInfo, force: Boolean): Int {
        val asked = System.currentTimeMillis()
        return locks.getOrPut(info.key) { Mutex() }.withLock {
            val saved = cached(info.key)
            // Fresh enough, or someone else read the site while this waited
            if (saved != null && (saved.at >= asked || (!force && asked - saved.at < MAX_AGE_MS))) {
                return@withLock saved.count
            }
            try {
                val fresh = Cached(System.currentTimeMillis(), fetch(info))
                cache[info.key] = fresh
                file(info.key).writeText(jsJson.encodeToString(Cached.serializer(), fresh))
                failedAt.remove(info.key)
                AppLog.add('I', "js", "${info.name}: got ${fresh.count} tags in ${fresh.lists.size} lists")
                fresh.count
            } catch (e: Throwable) {
                failedAt[info.key] = System.currentTimeMillis()
                AppLog.add('W', "js", "${info.name}: getTags failed: ${e.message}")
                throw e
            }
        }
    }

    private suspend fun fetch(info: JsSourceInfo): Map<String, List<String>> {
        val code = codeOf(info.key) ?: throw IllegalStateException("The code of ${info.name} is missing")
        val runtime = JsRuntime.create(context, info.key, info.id, info.name, code, info.json)
        val result = try {
            runtime.metadata()
            runtime.call("getTags")
        } finally {
            runtime.close()
        }
        return parse(result)
    }

    /** What a box suggests: [extra] (the filter's own options), then the lists named [names], then the shared list */
    fun suggestions(key: String, names: List<String>, extra: List<String>): List<String> =
        LiveList(source = { cache[key] }, build = { cached -> combine(cached, names, extra) })

    private class LiveList(
        private val source: () -> Cached?,
        private val build: (Cached?) -> List<String>,
    ) : AbstractList<String>() {

        @Volatile
        private var last: Pair<Cached?, List<String>>? = null

        private fun current(): List<String> {
            val now = source()
            last?.let { (from, list) -> if (from === now) return list }
            return build(now).also { last = now to it }
        }

        override val size: Int get() = current().size

        override fun get(index: Int): String = current()[index]

        override fun iterator(): Iterator<String> = current().iterator()
    }

    companion object {
        /** Tag lists are read from the site again after this long */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000
        private const val MAX_PER_LIST = 100_000

        /** getTags()'s answer as lists: `{ name: [...] }`, or a plain array as the shared list "*" */
        fun parse(element: JsonElement): Map<String, List<String>> = when (element) {
            is JsonObject -> element.mapValues { (_, value) -> tagList(value) }.filterValues { it.isNotEmpty() }
            is JsonArray -> mapOf(SHARED to tagList(element)).filterValues { it.isNotEmpty() }
            else -> emptyMap()
        }

        private fun tagList(element: JsonElement): List<String> = (element as? JsonArray).orEmpty()
            .mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject -> item.str("name") ?: item.str("label") ?: item.str("value")
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .take(MAX_PER_LIST)

        fun combine(cached: Cached?, names: List<String>, extra: List<String>): List<String> {
            val lists = cached?.lists.orEmpty()
            return (extra + names.flatMap { lists[it].orEmpty() } + lists[SHARED].orEmpty()).distinct()
        }

        const val SHARED = "*"
    }

    /** "Refresh tag list" in the source's settings */
    fun addRefreshButton(screen: PreferenceScreen, info: JsSourceInfo) {
        val button = Preference(screen.context).apply {
            key = "js_refresh_tags"
            title = "Refresh tag list"
            summary = summaryOf(cache[info.key])
            isPersistent = false
        }
        button.setOnPreferenceClickListener {
            button.summary = "Reading tags from the site…"
            button.isEnabled = false
            refresh(info) { result ->
                button.isEnabled = true
                button.summary = summaryOf(cache[info.key])
                result
                    .onSuccess { screen.context.toast("$it tags") }
                    .onFailure { screen.context.toast("Couldn't read the tags: ${it.message}") }
            }
            true
        }
        screen.addPreference(button)
    }

    private fun summaryOf(cached: Cached?): String =
        if (cached == null || cached.at == 0L) {
            "Suggestions for the tag boxes in Filter. Not read yet"
        } else {
            "${cached.count} tags, read ${DateUtils.getRelativeTimeSpanString(cached.at)}. Read again every week"
        }
}
