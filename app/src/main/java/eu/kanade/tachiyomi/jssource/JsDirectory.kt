package eu.kanade.tachiyomi.jssource

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK (r107): one collection filter of a JS source (a site's directory: genres, makers, series, people…), shared by
 * every copy of the source's filters, so the drop-down keeps its sort, menus and the entries' names while the app
 * runs. The filter itself only holds the picked entry's value (a text filter's state, so saved searches keep it).
 *
 * A stored collection lists [JsFilters.CollectionInfo.entries] and is sorted here by what its sort option's name means
 * (videos / views / likes / name). A live one is read page by page with the source's
 * `getDirectory(key, page, sort, ascending, menus)`; the site sorts it, and only a directory marked `reverse` gets
 * `ascending`. KMK (r107) fix: a live directory the site can't reverse never flips (the arrow stays down, as in the
 * mockup); a stored one offers only the sorts it can apply itself, and the site's menus only show for a live one.
 */
internal class JsDirectory(
    val group: JsFilters.Group,
    private val sourceKey: String,
    /** What pictures load with (the source's Referer and headers) */
    val imageHeaders: Map<String, String>,
    private val loader: suspend (page: Int, sort: String, ascending: Boolean, menus: Map<String, String>) -> JsonElement,
) {

    /** A filter that is a collection drop-down */
    interface Holder {
        val directory: JsDirectory
    }

    data class Page(val items: List<JsFilters.Entry>, val hasNext: Boolean)

    val info: JsFilters.CollectionInfo = group.collection ?: JsFilters.CollectionInfo(
        path = "",
        fields = listOf("title"),
        entries = emptyList(),
        sorts = emptyList(),
        reverse = false,
        menus = emptyList(),
        live = false,
        total = 0,
    )

    val key: String get() = group.key
    val name: String get() = group.name
    val live: Boolean get() = info.live

    fun shows(part: String): Boolean = part in info.fields

    /** Faces (a 4-column grid) suit a directory of people that kept its pictures */
    val people: Boolean = shows("picture") && PEOPLE.containsMatchIn("${group.key} ${group.name}")

    /**
     * The sort menu's options: the site's own, or for a stored list without any, the orders its parts allow. A live
     * directory without sorts has no menu (only the site can order what it hasn't sent yet)
     */
    val sortOptions: List<JsFilters.Choice> = siteSorts().ifEmpty {
        if (info.live) {
            emptyList()
        } else {
            buildList {
                if (shows("videos")) add(JsFilters.Choice("Most videos", "videos"))
                if (shows("views")) add(JsFilters.Choice("Most viewed", "views"))
                if (shows("likes")) add(JsFilters.Choice("Most liked", "likes"))
                if (isNotEmpty()) add(JsFilters.Choice("Name", "name"))
            }
        }
    }

    /**
     * KMK (r107) fix: the site's sorts the list can use: all for a live one; for a stored one only those it can apply
     * from what its entries hold (videos / views / likes / name), not "Newest", "Recently added", "Today views"…
     */
    private fun siteSorts(): List<JsFilters.Choice> =
        if (info.live) info.sorts else info.sorts.filter { localMeaning(it.label.ifBlank { it.value }).isNotEmpty() }

    /** KMK (r107) fix: the site's extra menus, only for a live list (stored entries can't be narrowed by them) */
    val menus: List<JsFilters.Menu> get() = if (info.live) info.menus else emptyList()

    /** KMK (r107) fix: Clear in the filter sheet: the site's / stored order and no menu picked; [resets] counts it */
    fun resetOrder() {
        sortIndex = -1
        ascending = false
        menuValues.clear()
        resets++
    }

    /** KMK (r107) fix: how many times [resetOrder] ran (the drop-down's own copies start again when it changes) */
    @Volatile
    var resets: Int = 0
        private set

    /** The picked sort option (-1: the site's / stored order) */
    @Volatile
    var sortIndex: Int = -1

    @Volatile
    var ascending: Boolean = false

    /** The extra menus' picked values, by menu key ("" = any) */
    val menuValues: MutableMap<String, String> = ConcurrentHashMap()

    /** A non-default order is set: the sort button is tinted */
    val sorted: Boolean
        get() {
            val option = sortOptions.getOrNull(sortIndex) ?: return false
            return option.value.isNotEmpty() || ascending
        }

    /** The direction an option starts in: names A→Z (up), counts most first (down) */
    fun startsAscending(index: Int): Boolean = canFlip() && meaning(sortOptions.getOrNull(index)?.label.orEmpty()) == NAME

    /** Whether the picked option's direction can be flipped. KMK (r107) fix: never for a live list the site can't reverse */
    fun canFlip(): Boolean = !info.live || info.reverse

    private val labels = ConcurrentHashMap<String, String>()

    /** The name of the entry with [value]: a stored one, or one a live page showed */
    fun labelOf(value: String): String? =
        labels[value] ?: info.entries.firstOrNull { it.value == value }?.label

    /** One page of a live directory, with the sort and menus picked now */
    suspend fun load(page: Int): Page {
        val sort = sortOptions.getOrNull(sortIndex)?.value.orEmpty()
        val result = loader(page, sort, ascending && info.reverse, menuValues.filterValues { it.isNotEmpty() })
        val obj = result as? JsonObject
        val items = JsMapping.list(obj?.get("items") ?: result).mapNotNull(JsFilters::entry)
        items.forEach { labels[it.value] = it.label }
        return Page(items, hasNext = obj?.bool("hasNext") ?: items.isNotEmpty())
    }

    /**
     * [entries] in the order picked. Stored: sorted by the option's meaning (unknown: kept as stored), reversed when
     * ascending. Live: as the site sent them (KMK (r107) fix: never reversed here)
     */
    fun ordered(entries: List<JsFilters.Entry>): List<JsFilters.Entry> {
        val option = sortOptions.getOrNull(sortIndex) ?: return entries
        if (info.live) return entries
        val byMeaning = when (meaning(option.label.ifBlank { option.value })) {
            VIDEOS -> entries.sortedByDescending { count(it.videos) ?: -1.0 }
            VIEWS -> entries.sortedByDescending { count(it.views) ?: -1.0 }
            LIKES -> entries.sortedByDescending { count(it.likes) ?: -1.0 }
            // names go A→Z when ascending, like the other options read most-first when descending
            NAME -> return if (ascending) {
                entries.sortedBy { it.label.lowercase() }
            } else {
                entries.sortedByDescending { it.label.lowercase() }
            }
            else -> entries
        }
        return if (ascending) byMeaning.asReversed() else byMeaning
    }

    /** Faces or list rows, remembered per filter */
    var faces: Boolean
        get() = runCatching { prefs().getBoolean(prefKey, true) }.getOrDefault(true)
        set(value) {
            runCatching { prefs().edit().putBoolean(prefKey, value).apply() }
        }

    private val prefKey: String get() = "$sourceKey:${group.key}:faces"

    private fun prefs() = Injekt.get<Application>().getSharedPreferences("js_collections", Context.MODE_PRIVATE)

    companion object {
        // KMK (r107) fix: the builder's people words too
        private val PEOPLE = Regex(
            "actress|actor|model|performer|cast|idol|star|people|person|creator|artist",
            RegexOption.IGNORE_CASE,
        )

        private const val VIDEOS = "videos"
        private const val VIEWS = "views"
        private const val LIKES = "likes"
        private const val NAME = "name"

        /** What a sort option's name orders by: videos, views (today / weekly / monthly too), likes, name, or "" */
        fun meaning(label: String): String {
            val l = label.lowercase()
            return when {
                "video" in l || "count" in l -> VIDEOS
                "like" in l || "follow" in l || "favo" in l || "subscri" in l || "♥" in l -> LIKES
                "view" in l || "today" in l || "week" in l || "month" in l || "popular" in l || "trend" in l -> VIEWS
                "name" in l || "a-z" in l || "z-a" in l || "alpha" in l || "title" in l -> NAME
                else -> ""
            }
        }

        /**
         * KMK (r107) fix: [meaning] for a stored list: "" for an order by time it can't know ("Newest", "Recently
         * added", "Today views", "Weekly views", "Monthly views")
         */
        fun localMeaning(label: String): String {
            val l = label.lowercase()
            if (TIMED.containsMatchIn(l)) return ""
            return meaning(l)
        }

        private val TIMED = Regex("today|daily|week|month|year|newest|\\bnew\\b|recent|latest|added|oldest|date|updated|release|trend")

        /** "4,476" → 4476, "6.2M" → 6 200 000, "17.2K" → 17 200; null when there is no number */
        fun count(text: String?): Double? {
            val match = COUNT.find(text ?: return null) ?: return null
            val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            return number * when (match.groupValues[2].uppercase()) {
                "K" -> 1e3
                "M" -> 1e6
                "B" -> 1e9
                else -> 1.0
            }
        }

        private val COUNT = Regex("""(\d[\d,]*(?:\.\d+)?)\s*([KMBkmb]?)""")
    }
}
