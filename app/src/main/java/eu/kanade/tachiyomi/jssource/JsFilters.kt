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
    enum class Type { Select, MultiSelect, Checkbox, AutoComplete, Header, Separator, Text, TriState, Sort, Group }

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
    ) {
        /** The option picked at first, kept inside the list */
        val startIndex: Int get() = defaultIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    }

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
