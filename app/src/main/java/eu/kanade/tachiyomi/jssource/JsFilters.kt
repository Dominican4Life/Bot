package eu.kanade.tachiyomi.jssource

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * KMK: A JS source's search filters (getFilters) and catalogs (getCatalogs), read once and shown with the
 * app's own filter widgets. What the user picks goes back to search(query, page, { key: value }).
 */
internal object JsFilters {

    /**
     * AutoComplete is a box the user types tags into, with suggestions from the filter's options and from the
     * tag lists the source's getTags() gave (see [JsTags]). The picked tags go to search() as an array.
     */
    /**
     * The same kinds of filter an APK source has. [Header]: a line of text, no value. [Separator]: a dividing line.
     * [Text]: a box to type in, its text goes to search(). [TriState]: a checkbox that cycles off / include /
     * exclude (0, 1, 2). [Sort]: a list with an up or down arrow ({ index, value, ascending }). [Group]: a section
     * that opens and closes, holding any of these; each one inside sends its own key, as if it were outside.
     */
    /**
     * KMK (r107): [Collection] is a site's directory (genres, makers, series, people…) picked from a drop-down list:
     * each entry with its number / videos / views / likes / picture as the site shows them, the site's own sort
     * options and menus, read page by page with the source's getDirectory when `live` (see [JsDirectory]). The
     * picked entry's value goes to search() under the filter's key, like a page autocomplete's.
     */
    enum class Type {
        Select, MultiSelect, Checkbox, AutoComplete, Header, Separator, Text, TriState, Sort, Group, Collection,
    }

    data class Option(val label: String, val value: JsonElement)

    data class Group(
        val key: String,
        val name: String,
        val type: Type,
        val options: List<Option>,
        val defaultIndex: Int,
        val defaultChecked: Boolean,
        /** AutoComplete: the text shown in the empty box */
        val hint: String = "",
        /** AutoComplete: marks the user may type before a tag, like "-" to leave it out */
        val prefixes: List<String> = emptyList(),
        /** AutoComplete: which of getTags()'s lists suggest tags here */
        val tagLists: List<String> = emptyList(),
        /** Text: what the box starts with */
        val defaultText: String = "",
        /** TriState: 0 off, 1 include, 2 exclude */
        val defaultState: Int = 0,
        /** Sort: whether the picked entry starts going up */
        val defaultAscending: Boolean = false,
        /** Group: what it holds */
        val children: List<Group> = emptyList(),
        // KMK (r107) -->
        /** Collection: the directory's entries, sorts, menus and how it is read */
        val collection: CollectionInfo? = null,
        /** A Source Lab list source's filter role ("page" / "extra"); blank for other sources */
        val role: String = "",
        // KMK <--
    ) {
        /** The option picked at first, kept inside the list */
        val startIndex: Int get() = defaultIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    }

    // KMK (r107) -->
    /**
     * One entry of a collection. [videos], [views], [likes] are the numbers as the site shows them ("4,476", "6.2M"),
     * the units are added when shown. [picture] is an absolute image address
     */
    data class Entry(
        val label: String,
        val value: String,
        val number: String? = null,
        val videos: String? = null,
        val views: String? = null,
        val likes: String? = null,
        val picture: String? = null,
    )

    /** A sort option or a menu option of a collection: what is shown and what getDirectory gets */
    data class Choice(val label: String, val value: String)

    /** One of the site's extra menus on a directory page (Height, Cup, Age…) */
    data class Menu(val key: String, val name: String, val options: List<Choice>)

    data class CollectionInfo(
        val path: String,
        /** The parts the source kept: number, title, videos, views, likes, picture */
        val fields: List<String>,
        val entries: List<Entry>,
        val sorts: List<Choice>,
        /** The site itself can list in ascending order (getDirectory gets `ascending`) */
        val reverse: Boolean,
        val menus: List<Menu>,
        /** Entries are read page by page with getDirectory instead of [entries] */
        val live: Boolean,
        /** How many entries the site said it has (0 = unknown) */
        val total: Int,
    )

    /**
     * The source's built-in "List" filter (generic-list v8–v9): not shown since r107, the tabs pick the list. A blank
     * header takes its place in the filter list, so saved searches (restored by position) still line up
     */
    const val BUILT_IN_LIST = "__list"

    /**
     * KMK (r107): a Source Lab list source (generic-list v8+), which reads the list Browse showed as `__from`: up to
     * v9 its sheet had the built-in "List"; v10 leaves that out, but its filters still carry a role
     */
    fun readsFrom(groups: List<Group>): Boolean = flat(groups).any { it.key == BUILT_IN_LIST || it.role.isNotEmpty() }

    /** KMK (r107): a group filter that is a section of the sheet (its own card), not a list of check boxes */
    interface SectionGroup

    fun entry(obj: JsonObject): Entry? {
        val value = obj.str("value") ?: obj.str("id") ?: obj.str("slug")
        // an "Any" option (empty value) is the drop-down's own empty pick, not an entry
        if (value != null && value.isBlank()) return null
        val label = obj.str("label") ?: obj.str("name") ?: obj.str("title") ?: value ?: return null
        fun part(key: String): String? = obj.str(key)?.trim()?.takeIf { it.isNotEmpty() }
        return Entry(
            label = label.trim(),
            value = value ?: label,
            number = part("number"),
            videos = part("videos"),
            views = part("views"),
            likes = part("likes"),
            picture = part("picture")?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        )
    }

    private fun choices(element: JsonElement?): List<Choice> = (element as? JsonArray)?.mapNotNull { option ->
        when (option) {
            is JsonObject -> {
                val value = option.str("value") ?: ""
                Choice(option.str("label") ?: option.str("name") ?: value, value)
            }
            is JsonPrimitive -> option.contentOrNull?.let { Choice(it, it) }
            else -> null
        }
    }.orEmpty()

    private fun collection(obj: JsonObject): CollectionInfo = CollectionInfo(
        path = obj.str("path").orEmpty(),
        fields = strings(obj["fields"])?.map { it.lowercase() } ?: listOf("title"),
        entries = obj.arr("options")?.mapNotNull { (it as? JsonObject)?.let(::entry) }.orEmpty(),
        sorts = choices(obj["sorts"]),
        reverse = obj.bool("reverse") == true,
        menus = obj.arr("menus")?.mapNotNull { menu ->
            val m = menu as? JsonObject ?: return@mapNotNull null
            val key = m.str("key") ?: return@mapNotNull null
            Menu(key, m.str("name") ?: key, choices(m["options"]))
        }.orEmpty(),
        live = obj.bool("live") == true,
        total = obj.int("total") ?: 0,
    )
    // KMK <--

    /** [tab]: shown as a button at the top of Browse ("tab": true), not only in the filter sheet */
    data class Catalog(val id: String, val name: String, val tab: Boolean = false)

    fun groups(element: JsonElement?): List<Group> =
        JsMapping.list(element ?: return emptyList()).mapNotNull(::group)

    private fun group(obj: JsonObject): Group? {
        val options = obj.arr("options")?.mapNotNull { option ->
            when (option) {
                is JsonObject -> Option(option.str("label") ?: option.str("name") ?: option["value"].toString(), option["value"] ?: JsonPrimitive(""))
                is JsonPrimitive -> Option(option.content, option)
                else -> null
            }
        }.orEmpty()
        val type = when (obj.str("type")?.lowercase()) {
            "header", "heading", "section" -> Type.Header
            "separator", "divider", "line" -> Type.Separator
            "multiselect", "multi", "tags" -> Type.MultiSelect
            "checkbox", "switch", "toggle" -> Type.Checkbox
            "autocomplete" -> Type.AutoComplete
            "text", "textbox", "input" -> Type.Text
            "tristate", "tri-state", "tri_state" -> Type.TriState
            "sort" -> Type.Sort
            "group" -> Type.Group
            "collection" -> Type.Collection // KMK (r107)
            // KMK: a list with no options (or an unknown type with none, like "number") crashed the Filter
            // panel; it's a text box instead
            else -> if (options.isEmpty()) Type.Text else Type.Select
        }
        val name = obj.str("name") ?: obj.str("title")
        // Titles, lines and groups send no value of their own, so they need no key
        val key = obj.str("key") ?: when (type) {
            Type.Header, Type.Separator, Type.Group -> name ?: type.name.lowercase()
            else -> return null
        }
        val default = obj["defaultValue"] as? JsonPrimitive
        return Group(
            key = key,
            name = name ?: if (type == Type.Separator) "" else key,
            type = type,
            options = options,
            defaultIndex = obj.int("defaultIndex") ?: 0,
            defaultChecked = default?.booleanOrNull ?: false,
            hint = obj.str("hint") ?: obj.str("placeholder") ?: "",
            prefixes = strings(obj["prefixes"]) ?: listOf("-"),
            // "tags": "female" or ["female", "male"]; without it, a list named like the filter's key
            tagLists = strings(obj["tags"]) ?: listOf(key),
            defaultText = if (type == Type.Text) default?.contentOrNull.orEmpty() else "",
            defaultState = when (default?.contentOrNull?.lowercase()) {
                "1", "include", "included" -> 1
                "2", "exclude", "excluded" -> 2
                else -> 0
            },
            defaultAscending = obj.bool("ascending") == true,
            children = if (type == Type.Group) {
                (obj.arr("filters") ?: obj.arr("children") ?: obj.arr("items"))
                    ?.mapNotNull { (it as? JsonObject)?.let(::group) }.orEmpty()
            } else {
                emptyList()
            },
            collection = if (type == Type.Collection) collection(obj) else null,
            role = obj.str("role").orEmpty(),
        )
    }

    /** Every filter, the ones inside groups too */
    fun flat(groups: List<Group>): List<Group> = groups.flatMap { listOf(it) + flat(it.children) }

    private fun strings(element: JsonElement?): List<String>? = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> element.contentOrNull?.let(::listOf)
        else -> null
    }

    fun catalogs(element: JsonElement?): List<Catalog> = JsMapping.list(element ?: return emptyList()).mapNotNull { obj ->
        val id = obj.str("id") ?: return@mapNotNull null
        Catalog(id, obj.str("name") ?: id, tab = obj.bool("tab") == true)
    }
}
