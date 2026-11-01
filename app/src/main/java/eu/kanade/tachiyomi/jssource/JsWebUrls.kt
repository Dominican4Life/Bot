package eu.kanade.tachiyomi.jssource

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonObject

/**
 * KMK: The site page of each title and chapter a JS source gave a `webUrl` for (its `url` may be an API
 * address or just an id), kept so WebView, sharing and "Open in browser" open the real page, also after a restart.
 */
internal class JsWebUrls(context: Context, private val sourceId: Long, baseUrl: String) {

    private val base = baseUrl.trimEnd('/')
    private val preferences = context.getSharedPreferences("js_weburls_$sourceId", Context.MODE_PRIVATE)

    /** KMK (r103): entries in the file, counted once (preferences.all copied the whole map on every page) */
    private var count = -1

    private var lastLogAt = 0L

    @Synchronized
    fun remember(items: List<JsonObject>) {
        // KMK (r103): only what is new or changed is written (a page seen again used to rewrite the whole file)
        val found = items.mapNotNull { item ->
            val url = item.str("url") ?: return@mapNotNull null
            val web = item.str("webUrl")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            url to web
        }
        val changed = found.filter { (url, web) -> preferences.getString(url, null) != web }
        if (changed.isEmpty()) return
        if (count < 0) count = runCatching { preferences.all.size }.getOrDefault(0)
        val added = changed.count { (url, _) -> !preferences.contains(url) }
        val clearFirst = count > 5000
        // KMK (r103): a clear writes back every pair of this page, not only the changed ones (the unchanged ones
        // would be lost with the rest of the file)
        val written = if (clearFirst) found else changed
        preferences.edit {
            if (clearFirst) clear()
            written.forEach { (url, web) -> putString(url, web) }
        }
        count = if (clearFirst) written.distinctBy { it.first }.size else count + added
        val now = System.currentTimeMillis()
        if (now - lastLogAt >= 60_000L) {
            lastLogAt = now
            AppLog.add(
                'D',
                "js",
                "webUrls $sourceId: wrote ${written.size} of ${items.size} (unchanged ones skipped" +
                    "${if (clearFirst) "; file cleared first, page kept" else ""}), $count kept",
            )
        }
    }

    /** The page to open for [url]: its webUrl, the url itself when it is an address, or one built on baseUrl */
    fun pageFor(url: String): String {
        preferences.getString(url, null)?.let { return it }
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            base.isBlank() -> url
            url.startsWith("/") -> "$base$url"
            url.isBlank() -> base
            // An id or other key that isn't a path: the site itself is the best that can be opened
            url.none { it == '/' || it == '.' } -> base
            else -> "$base/$url"
        }
    }
}
