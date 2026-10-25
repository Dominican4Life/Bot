package eu.kanade.tachiyomi.jssource

import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: A JS / JSON source's list entries may carry a `badge` (such as a post count, "272"). Browse shows it after
 * the name ("Byoru · 272"), and only there: it's kept in memory while the app runs, refreshed each time the list
 * loads, and never saved, so the library, downloads and backups only ever see the plain name.
 */
object JsListBadges {

    private val badges = ConcurrentHashMap<String, String>()

    private fun key(sourceId: Long, url: String) = "$sourceId|$url"

    fun put(sourceId: Long, url: String, badge: String?) {
        val text = badge?.trim().orEmpty()
        if (text.isEmpty()) badges.remove(key(sourceId, url)) else badges[key(sourceId, url)] = text
    }

    /** The name Browse shows: the title, then " · " and the badge when the list gave one */
    fun browseTitle(sourceId: Long, url: String, title: String): String {
        if (badges.isEmpty()) return title
        val badge = badges[key(sourceId, url)] ?: return title
        return "$title · $badge"
    }
}
