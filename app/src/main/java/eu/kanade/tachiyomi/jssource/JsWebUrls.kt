package eu.kanade.tachiyomi.jssource

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.JsonObject

/**
 * KMK: The site page of each title and chapter a JS source gave a `webUrl` for (its `url` may be an API
 * address or just an id), kept so WebView, sharing and "Open in browser" open the real page, also after a restart.
 */
internal class JsWebUrls(context: Context, private val sourceId: Long, baseUrl: String) {

    private val base = baseUrl.trimEnd('/')
    private val preferences = context.getSharedPreferences("js_weburls_$sourceId", Context.MODE_PRIVATE)

    fun remember(items: List<JsonObject>) {
        val found = items.mapNotNull { item ->
            val url = item.str("url") ?: return@mapNotNull null
            val web = item.str("webUrl")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            url to web
        }
        if (found.isEmpty()) return
        preferences.edit {
            if (preferences.all.size > 5000) clear()
            found.forEach { (url, web) -> putString(url, web) }
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
