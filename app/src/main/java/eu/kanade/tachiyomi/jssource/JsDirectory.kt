package eu.kanade.tachiyomi.jssource

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.util.system.AppLog
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
 *
 * KMK (r109): a stored ("read pages now") collection offers ALL the site's sorts and menus again. The sorts the app can
 * apply itself (videos / views / likes / name) sort the stored entries; any other sort, or any menu value, makes the
 * drop-down read the site live through getDirectory (see [readsLive]). The site's default sort with no menu picked
 * shows the stored entries again. A source without getDirectory (or a collection the source can't read: "has no
 * directory to read") offers only the local sorts and no menus ([siteReadable]).
 */
internal class JsDirectory(
    val group: JsFilters.Group,
    private val sourceKey: String,
    /** What pictures load with (the source's Referer and headers) */
    val imageHeaders: Map<String, String>,
    /**
     * KMK (r109): whether the source defines getDirectory (null: not known until a read fails with "This source has no
     * getDirectory"). The sources pass `{ runtime().has("getDirectory") }`
     */
    private val hasDirectory: (suspend () -> Boolean)? = null,
    // the last parameter: the sources pass it as a trailing lambda
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
     * KMK (r109): whether the site can be read live for this collection: always for a live one; for a stored one until
     * the source turns out not to have getDirectory or can't read this collection ([checkSite], [load])
     */
    @Volatile
    var siteReadable: Boolean = true
        private set

    /** KMK (r109): asks the source once whether it has getDirectory (a stored list then hides the site-only options) */
    suspend fun checkSite() {
        if (info.live || !siteReadable || siteChecked) return
        siteChecked = true
        val has = hasDirectory ?: return
        val yes = try {
            has()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            siteChecked = false
            throw e
        } catch (_: Throwable) {
            true // unknown: a failed read says so later
        }
        if (!yes) notReadable("no getDirectory")
    }

    /**
     * KMK (r109): the site can't be read live for this stored list: only the local sorts stay, so the picked sort's
     * index and the menus start again ([resets] tells the drop-down)
     */
    private fun notReadable(why: String) {
        if (!siteReadable) return
        siteReadable = false
        AppLog.add('W', "js", "$sourceKey: ${group.name} can't be read live ($why): stored entries, local sorts only")
        resetOrder()
    }

    @Volatile
    private var siteChecked = false

    /** The orders a stored list's parts allow when the site has no sorts of its own */
    private val localSorts: List<JsFilters.Choice> = buildList {
        if (shows("videos")) add(JsFilters.Choice("Most videos", "videos"))
        if (shows("views")) add(JsFilters.Choice("Most viewed", "views"))
        if (shows("likes")) add(JsFilters.Choice("Most liked", "likes"))
        if (isNotEmpty()) add(JsFilters.Choice("Name", "name"))
    }

    /**
     * The sort menu's options: the site's own, or for a stored list without any, the orders its parts allow. A live
     * directory without sorts has no menu (only the site can order what it hasn't sent yet). KMK (r109): a stored list
     * offers all the site's sorts while the site can be read ([siteReadable]); else only those it can apply itself
     */
    val sortOptions: List<JsFilters.Choice>
        get() = siteSorts().ifEmpty { if (info.live) emptyList() else localSorts }

    /** KMK (r109): the options are the site's own (their values go to getDirectory), not the app's [localSorts] */
    private val fromSite: Boolean get() = siteSorts().isNotEmpty()

    /**
     * KMK (r107) fix: the site's sorts the list can use: all for a live one; for a stored one only those it can apply
     * from what its entries hold (videos / views / likes / name), not "Newest", "Recently added", "Today views"…
     * KMK (r109): a stored one gets all of them too while the site can be read live
     */
    private fun siteSorts(): List<JsFilters.Choice> = if (info.live || siteReadable) {
        info.sorts
    } else {
        info.sorts.filter { localKind(it).isNotEmpty() }
    }

    /**
     * KMK (r109) fix: what [option] orders the stored entries by: videos / views / likes only when the entries have
     * that count (the part is kept, or some entries hold it), the name always; "" = a site-only order (read live)
     */
    private fun localKind(option: JsFilters.Choice): String = when (val m = localMeaning(option.label.ifBlank { option.value })) {
        VIDEOS, VIEWS, LIKES -> if (holds(m)) m else ""
        else -> m
    }

    private val held = ConcurrentHashMap<String, Boolean>()

    /** KMK (r109) fix: whether the stored entries have the count [kind] */
    private fun holds(kind: String): Boolean = held.getOrPut(kind) {
        shows(kind) || info.entries.any { e ->
            when (kind) {
                VIDEOS -> e.videos != null
                VIEWS -> e.views != null
                else -> e.likes != null
            }
        }
    }

    /**
     * KMK (r107) fix: the site's extra menus, only for a live list (stored entries can't be narrowed by them).
     * KMK (r109): a stored list offers them too while the site can be read live (a picked value reads it live)
     */
    val menus: List<JsFilters.Menu> get() = if (info.live || siteReadable) info.menus else emptyList()

    /**
     * KMK (r109): whether the option at [index] is the site's default order (the stored list's own order): no pick, or
     * the option with an empty value (the builder names the site's default that way). KMK (r109) fix: not the first
     * option by its place: an older collection whose sorts have no "" option has no default but "no pick"
     */
    fun isDefaultSort(index: Int): Boolean {
        val option = sortOptions.getOrNull(index) ?: return true
        return option.value.isEmpty()
    }

    /**
     * KMK (r109): whether the drop-down reads the site page by page (getDirectory) with [sortIndex] and [menus] picked:
     * always for a live collection; for a stored one when a menu value is picked, or the sort is one the app can't
     * apply to the stored entries (not the site's default). The default sort with no menu shows the stored entries
     */
    fun readsLive(sortIndex: Int, menus: Map<String, String>): Boolean {
        if (info.live) return true
        if (!siteReadable) return false
        if (menus.values.any { it.isNotEmpty() }) return true
        val option = sortOptions.getOrNull(sortIndex) ?: return false
        if (isDefaultSort(sortIndex)) return false
        // KMK (r109) fix: a count the stored entries don't have is the site's to order
        return localKind(option).isEmpty()
    }

    /** KMK (r109): [readsLive] with the order and menus picked now */
    val readingLive: Boolean get() = readsLive(sortIndex, menuValues)

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

    /** A non-default order is set: the sort button is tinted. KMK (r109): the site's default isn't */
    val sorted: Boolean
        get() {
            if (sortOptions.getOrNull(sortIndex) == null) return false
            return !isDefaultSort(sortIndex) || ascending
        }

    /**
     * The direction an option starts in: names A→Z (up), counts most first (down). KMK (r109): with the menus picked
     * now (a stored list read live can't be flipped unless the site reverses)
     */
    fun startsAscending(index: Int, menus: Map<String, String> = menuValues): Boolean =
        canFlip(index, menus) && meaning(sortOptions.getOrNull(index)?.label.orEmpty()) == NAME

    /**
     * Whether the picked option's direction can be flipped. KMK (r107) fix: never for a live list the site can't
     * reverse. KMK (r109): the same for a stored list while it reads the site live
     */
    fun canFlip(index: Int = sortIndex, menus: Map<String, String> = menuValues): Boolean =
        !readsLive(index, menus) || info.reverse

    private val labels = ConcurrentHashMap<String, String>()

    /** The name of the entry with [value]: a stored one, or one a live page showed */
    fun labelOf(value: String): String? =
        labels[value] ?: info.entries.firstOrNull { it.value == value }?.label

    /**
     * One page of a live directory, with the sort and menus picked now. KMK (r109): also a stored list read live; the
     * app's own sorts ([localSorts]) aren't the site's, so the site's default ("") goes instead. A source without
     * getDirectory, or one that can't read this collection, turns [siteReadable] off (the error still shows)
     */
    suspend fun load(page: Int): Page {
        val sort = if (fromSite) sortOptions.getOrNull(sortIndex)?.value.orEmpty() else ""
        val result = try {
            loader(page, sort, ascending && info.reverse, menuValues.filterValues { it.isNotEmpty() })
        } catch (e: NotImplementedError) {
            if (!info.live) notReadable("no getDirectory")
            throw e
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (!info.live && NO_DIRECTORY.containsMatchIn(message)) notReadable(message.take(120))
            throw e
        }
        val obj = result as? JsonObject
        val items = JsMapping.list(obj?.get("items") ?: result).mapNotNull(JsFilters::entry)
        items.forEach { labels[it.value] = it.label }
        return Page(items, hasNext = obj?.bool("hasNext") ?: items.isNotEmpty())
    }

    /**
     * [entries] in the order picked. Stored: sorted by the option's meaning (unknown: kept as stored), reversed when
     * ascending. Live: as the site sent them (KMK (r107) fix: never reversed here). KMK (r109): [sortIndex] and
     * [ascending] as the drop-down shows them; a stored list read live is as the site sent it too
     */
    fun ordered(
        entries: List<JsFilters.Entry>,
        sortIndex: Int = this.sortIndex,
        ascending: Boolean = this.ascending,
        menus: Map<String, String> = menuValues,
    ): List<JsFilters.Entry> {
        val option = sortOptions.getOrNull(sortIndex) ?: return entries
        if (readsLive(sortIndex, menus)) return entries
        // KMK (r109): the site's default (or a time order a stored list can't know) keeps the stored order
        val byMeaning = when (localKind(option)) {
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

        /** KMK (r109): the template's error for a collection saved without the directory's page info */
        private val NO_DIRECTORY = Regex("has no directory to read|no getDirectory", RegexOption.IGNORE_CASE)

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
