package eu.kanade.tachiyomi.sumizuri

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * KMK: A Sumizuri source's search filters (getFilters) and catalogs (getCatalogs), read once and shown with the
 * app's own filter widgets. What the user picks goes back to search(query, page, { key: value }).
 */
internal object SumizuriFilters {

    /**
     * AutoComplete is a box the user types tags into, with suggestions from the filter's options and from the
     * tag lists the source's getTags() gave (see [SumizuriTags]). The picked tags go to search() as an array.
     */
    enum class Type { Select, MultiSelect, Checkbox, AutoComplete }

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
    )

    /** [tab]: shown as a button at the top of Browse ("tab": true), not only in the filter sheet */
    data class Catalog(val id: String, val name: String, val tab: Boolean = false)

    fun groups(element: JsonElement?): List<Group> = SumizuriMapping.list(element ?: return emptyList()).mapNotNull { obj ->
        val key = obj.str("key") ?: return@mapNotNull null
        val type = when (obj.str("type")?.lowercase()) {
            "multiselect", "multi", "tags" -> Type.MultiSelect
            "checkbox", "switch", "toggle" -> Type.Checkbox
            "autocomplete" -> Type.AutoComplete
            else -> Type.Select
        }
        val options = obj.arr("options")?.mapNotNull { option ->
            when (option) {
                is JsonObject -> Option(option.str("label") ?: option.str("name") ?: option["value"].toString(), option["value"] ?: JsonPrimitive(""))
                is JsonPrimitive -> Option(option.content, option)
                else -> null
            }
        }.orEmpty()
        Group(
            key = key,
            name = obj.str("name") ?: obj.str("title") ?: key,
            type = type,
            options = options,
            defaultIndex = obj.int("defaultIndex") ?: 0,
            defaultChecked = (obj["defaultValue"] as? JsonPrimitive)?.booleanOrNull ?: false,
            hint = obj.str("hint") ?: obj.str("placeholder") ?: "",
            prefixes = strings(obj["prefixes"]) ?: listOf("-"),
            // "tags": "female" or ["female", "male"]; without it, a list named like the filter's key
            tagLists = strings(obj["tags"]) ?: listOf(key),
        )
    }

    private fun strings(element: JsonElement?): List<String>? = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> element.contentOrNull?.let(::listOf)
        else -> null
    }

    fun catalogs(element: JsonElement?): List<Catalog> = SumizuriMapping.list(element ?: return emptyList()).mapNotNull { obj ->
        val id = obj.str("id") ?: return@mapNotNull null
        Catalog(id, obj.str("name") ?: id, tab = obj.bool("tab") == true)
    }

    /**
     * KMK: The names a source gives its Popular and Latest buttons, from `tabNames: { popular, latest }`. Anything
     * missing, blank or of the wrong kind keeps the app's own name, so a source (or a copy of one) can't break Browse.
     */
    fun tabNames(element: JsonElement?): SumizuriTabNames {
        val obj = element as? JsonObject ?: return SumizuriTabNames()
        fun name(key: String) = (obj[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TAB_NAME)
        return SumizuriTabNames(popular = name("popular"), latest = name("latest"))
    }

    private const val MAX_TAB_NAME = 30
}
