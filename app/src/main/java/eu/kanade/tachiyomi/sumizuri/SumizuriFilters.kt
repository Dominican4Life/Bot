package eu.kanade.tachiyomi.sumizuri

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * KMK: A Sumizuri source's search filters (getFilters) and catalogs (getCatalogs), read once and shown with the
 * app's own filter widgets. What the user picks goes back to search(query, page, { key: value }).
 */
internal object SumizuriFilters {

    enum class Type { Select, MultiSelect, Checkbox }

    data class Option(val label: String, val value: JsonElement)

    data class Group(
        val key: String,
        val name: String,
        val type: Type,
        val options: List<Option>,
        val defaultIndex: Int,
        val defaultChecked: Boolean,
    )

    data class Catalog(val id: String, val name: String)

    fun groups(element: JsonElement?): List<Group> = SumizuriMapping.list(element ?: return emptyList()).mapNotNull { obj ->
        val key = obj.str("key") ?: return@mapNotNull null
        val type = when (obj.str("type")?.lowercase()) {
            "multiselect", "multi", "tags" -> Type.MultiSelect
            "checkbox", "switch", "toggle" -> Type.Checkbox
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
        )
    }

    fun catalogs(element: JsonElement?): List<Catalog> = SumizuriMapping.list(element ?: return emptyList()).mapNotNull { obj ->
        val id = obj.str("id") ?: return@mapNotNull null
        Catalog(id, obj.str("name") ?: id)
    }
}
