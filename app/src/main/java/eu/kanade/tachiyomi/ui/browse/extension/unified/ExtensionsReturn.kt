package eu.kanade.tachiyomi.ui.browse.extension.unified

import android.app.Application
import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r85): brings the Extensions screen back after Android restarts the app. Turning on "Install unknown apps" for
 * the app (asked for by the first install) restarts it, and it used to open on the library, so the extension being
 * installed and the search were lost. An install remembers the search here; if the app starts again within a few
 * minutes, before the install finished, the home screen opens Browse › Extensions with that search.
 *
 * Kept in its own preferences file, so it isn't in backups.
 */
object ExtensionsReturn {

    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private val prefs by lazy {
        Injekt.get<Application>().getSharedPreferences("extensions_return", Context.MODE_PRIVATE)
    }

    /** The search for the Extensions screen to show once it opens (set by [take]) */
    @Volatile
    var pendingQuery: String? = null
        private set

    /** An install started with [query] in the search box */
    fun remember(query: String) {
        runCatching { prefs.edit().putString("query", query).putLong("at", System.currentTimeMillis()).apply() }
    }

    /** The install finished (or the screen closed normally) */
    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    // Checked once per run of the app, so a screen rotation mid-install doesn't count as a restart
    @Volatile
    private var checked = false

    /** True when the app was restarted during an install; the search is then kept for [consumeQuery] */
    fun take(): Boolean {
        if (checked) return false
        checked = true
        val at = runCatching { prefs.getLong("at", 0L) }.getOrDefault(0L)
        val query = runCatching { prefs.getString("query", null) }.getOrNull()
        clear()
        if (at <= 0L || System.currentTimeMillis() - at > MAX_AGE_MS) return false
        pendingQuery = query.orEmpty()
        return true
    }

    fun consumeQuery(): String? = pendingQuery.also { pendingQuery = null }
}
