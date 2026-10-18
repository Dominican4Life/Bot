package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * KMK: A Sumizuri source's settings (getSourcePreferences), kept in the same SharedPreferences a Mihon source uses
 * ("source_<id>"), so they show in the usual source settings screen and the editor's Source settings.
 */
object SumizuriPreferences {

    // KMK: Category (section header), Info (text that can't be tapped) and Slider (one value or a range)
    enum class Type { Text, Switch, List, MultiList, Category, Info, Slider }

    data class Option(val label: String, val value: String)

    data class Definition(
        val key: String,
        val title: String,
        val summary: String?,
        val type: Type,
        val options: List<Option>,
        val defaultValue: JsonElement?,
        // KMK: for sliders
        val slider: Slider? = null,
    )

    /**
     * KMK: a slider's settings. Saved as text: "3" for one value, "1-5" for a range ([range]).
     * [unit] is shown after the value, such as "s".
     */
    data class Slider(val min: Int, val max: Int, val step: Int, val range: Boolean, val unit: String)

    fun of(context: Context, sourceId: Long): SharedPreferences =
        context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)

    /** Sumizuri's shape ({key, title, type, options:[{label,value}]}) and Mangayomi's (entries / entryValues) */
    fun parse(element: JsonElement): List<Definition> {
        val list = element as? JsonArray ?: return emptyList()
        return list.withIndex().mapNotNull { (index, item) ->
            var obj = item as? JsonObject ?: return@mapNotNull null
            // Mangayomi: { key, listPreference: { title, entries, entryValues, valueIndex } }
            var mangayomiType: Type? = null
            for ((name, type) in MANGAYOMI) {
                val inner = obj.obj(name) ?: continue
                obj = JsonObject(obj + inner)
                mangayomiType = type
            }
            val type = mangayomiType ?: when (obj.str("type")?.lowercase()) {
                "switch", "checkbox", "switchpreferencecompat", "checkboxpreference" -> Type.Switch
                "list", "select", "listpreference" -> Type.List
                "multiselectlist", "multiselect", "multiselectlistpreference" -> Type.MultiList
                // KMK -->
                "category", "section", "header", "preferencecategory" -> Type.Category
                "info", "details", "note" -> Type.Info
                "slider", "range", "seekbar", "seekbarpreference" -> Type.Slider
                // KMK <--
                else -> Type.Text
            }
            // KMK: sections and text blocks need no key
            val keyless = type == Type.Category || type == Type.Info
            val key = obj.str("key")
                ?: if (keyless) "__${type.name.lowercase()}_$index" else return@mapNotNull null
            val options = obj.arr("options")?.mapNotNull { option ->
                when (option) {
                    is JsonObject -> {
                        val value = option.str("value") ?: option.str("label") ?: return@mapNotNull null
                        Option(option.str("label") ?: value, value)
                    }
                    is JsonPrimitive -> Option(option.content, option.content)
                    else -> null
                }
            } ?: run {
                val entries = obj.arr("entries")?.map { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
                val values = obj.arr("entryValues")?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: entries
                entries.zip(values) { label, value -> Option(label, value) }
            }
            val default = obj["defaultValue"]?.takeUnless { it is JsonNull }
                ?: obj["value"]?.takeUnless { it is JsonNull }
                ?: obj.int("valueIndex")?.let { i -> options.getOrNull(i)?.let { JsonPrimitive(it.value) } }
                ?: obj.arr("values")
                ?: obj["text"]?.takeUnless { it is JsonNull }
            // KMK -->
            val slider = if (type == Type.Slider) {
                val min = obj.int("min") ?: 0
                val max = (obj.int("max") ?: 10).coerceAtLeast(min + 1)
                Slider(
                    min = min,
                    max = max,
                    step = (obj.int("step") ?: 1).coerceIn(1, max - min),
                    range = obj.bool("range") ?: (default as? JsonPrimitive)?.contentOrNull.orEmpty().contains('-'),
                    unit = obj.str("unit").orEmpty(),
                )
            } else {
                null
            }
            // KMK <--
            Definition(
                key = key,
                title = obj.str("title") ?: if (keyless) "" else key,
                summary = obj.str("summary") ?: obj.str("text").takeIf { type == Type.Info },
                type = type,
                options = options,
                defaultValue = default?.takeUnless { keyless },
                slider = slider,
            )
        }
    }

    private val MANGAYOMI = listOf(
        "editTextPreference" to Type.Text,
        "checkBoxPreference" to Type.Switch,
        "switchPreferenceCompat" to Type.Switch,
        "listPreference" to Type.List,
        "multiSelectListPreference" to Type.MultiList,
    )

    fun write(preferences: SharedPreferences, key: String, value: JsonElement?) {
        preferences.edit {
            when {
                value == null || value is JsonNull -> remove(key)
                value is JsonArray -> putStringSet(key, value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet())
                value is JsonPrimitive && !value.isString && value.booleanOrNull != null -> putBoolean(key, value.booleanOrNull!!)
                value is JsonPrimitive -> putString(key, value.content)
                else -> putString(key, value.toString())
            }
        }
    }

    /** The value now in effect: the saved one, or the source's default */
    fun current(preferences: SharedPreferences, def: Definition): JsonElement? =
        if (preferences.contains(def.key)) preferences.all[def.key].toJsonElement() else def.defaultValue

    /** Builds the source settings screen from the definitions */
    fun setup(screen: PreferenceScreen, definitions: List<Definition>) {
        val context = screen.context
        // KMK: settings after a category go inside it
        var group: PreferenceGroup = screen
        definitions.forEach { def ->
            val preference = when (def.type) {
                Type.Switch -> SwitchPreferenceCompat(context).apply {
                    setDefaultValue((def.defaultValue as? JsonPrimitive)?.booleanOrNull ?: false)
                    summary = def.summary
                }
                Type.List -> ListPreference(context).apply {
                    entries = def.options.map { it.label }.toTypedArray()
                    entryValues = def.options.map { it.value }.toTypedArray()
                    (def.defaultValue as? JsonPrimitive)?.contentOrNull?.let { setDefaultValue(it) }
                    summary = def.summary?.let { "$it\n%s" } ?: "%s"
                    dialogTitle = def.title
                }
                Type.MultiList -> MultiSelectListPreference(context).apply {
                    entries = def.options.map { it.label }.toTypedArray()
                    entryValues = def.options.map { it.value }.toTypedArray()
                    (def.defaultValue as? JsonArray)?.let { array ->
                        setDefaultValue(array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet())
                    }
                    summary = def.summary
                    dialogTitle = def.title
                }
                Type.Text -> EditTextPreference(context).apply {
                    (def.defaultValue as? JsonPrimitive)?.contentOrNull?.let { setDefaultValue(it) }
                    summary = def.summary
                    dialogTitle = def.title
                }
                // KMK -->
                Type.Category -> {
                    val category = PreferenceCategory(context).apply {
                        key = def.key
                        title = def.title
                        summary = def.summary
                        isIconSpaceReserved = false
                    }
                    screen.addPreference(category)
                    group = category
                    return@forEach
                }
                Type.Info -> Preference(context).apply {
                    summary = def.summary
                    isSelectable = false
                    isPersistent = false
                }
                Type.Slider -> SumizuriSliderPreference(context, def.slider ?: Slider(0, 10, 1, false, "")).apply {
                    (def.defaultValue as? JsonPrimitive)?.contentOrNull?.let { setDefaultValue(it) }
                    summary = def.summary
                }
                // KMK <--
            }
            preference.key = def.key
            preference.title = def.title.takeIf { it.isNotEmpty() }
            group.addPreference(preference)
        }
    }

    /** KMK: the numbers of a slider's saved text, "3" or "1-5", kept inside its bounds */
    fun sliderValues(text: String?, slider: Slider): Pair<Int, Int> {
        val numbers = text.orEmpty().split('-', '–').mapNotNull { it.trim().toIntOrNull() }
        val first = (numbers.firstOrNull() ?: slider.min).coerceIn(slider.min, slider.max)
        val second = (numbers.getOrNull(1) ?: first).coerceIn(first, slider.max)
        return first to second
    }

    /** KMK: the text a slider saves */
    fun sliderText(from: Int, to: Int, slider: Slider): String =
        if (slider.range) "$from-${to.coerceAtLeast(from)}" else "$from"

    /** KMK: the value as shown next to a slider, such as "1–5 s" */
    fun sliderLabel(from: Int, to: Int, slider: Slider): String {
        val unit = slider.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return if (slider.range) "$from–$to$unit" else "$from$unit"
    }
}
