package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.util.system.toast
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

    /** [Button]: KMK, a row that runs the source's onSettingButton(key) when tapped and shows what it returned */
    enum class Type { Text, Switch, List, MultiList, Button }

    data class Option(val label: String, val value: String)

    data class Definition(
        val key: String,
        val title: String,
        val summary: String?,
        val type: Type,
        val options: List<Option>,
        val defaultValue: JsonElement?,
    )

    fun of(context: Context, sourceId: Long): SharedPreferences =
        context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)

    /** Sumizuri's shape ({key, title, type, options:[{label,value}]}) and Mangayomi's (entries / entryValues) */
    fun parse(element: JsonElement): List<Definition> {
        val list = element as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            var obj = item as? JsonObject ?: return@mapNotNull null
            // Mangayomi: { key, listPreference: { title, entries, entryValues, valueIndex } }
            var mangayomiType: Type? = null
            for ((name, type) in MANGAYOMI) {
                val inner = obj.obj(name) ?: continue
                obj = JsonObject(obj + inner)
                mangayomiType = type
            }
            val key = obj.str("key") ?: return@mapNotNull null
            val type = mangayomiType ?: when (obj.str("type")?.lowercase()) {
                "switch", "checkbox", "switchpreferencecompat", "checkboxpreference" -> Type.Switch
                "list", "select", "listpreference" -> Type.List
                "multiselectlist", "multiselect", "multiselectlistpreference" -> Type.MultiList
                "button", "action" -> Type.Button
                else -> Type.Text
            }
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
                ?: obj.int("valueIndex")?.let { index -> options.getOrNull(index)?.let { JsonPrimitive(it.value) } }
                ?: obj.arr("values")
                ?: obj["text"]?.takeUnless { it is JsonNull }
            Definition(
                key = key,
                title = obj.str("title") ?: key,
                summary = obj.str("summary"),
                type = type,
                options = options,
                defaultValue = default,
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

    /**
     * Builds the source settings screen from the definitions. [onButton] runs a button's action (its key) and calls
     * back with the text to show when it's done.
     */
    fun setup(
        screen: PreferenceScreen,
        definitions: List<Definition>,
        onButton: ((key: String, done: (String) -> Unit) -> Unit)? = null,
    ) {
        val context = screen.context
        definitions.forEach { def ->
            val preference = when (def.type) {
                Type.Button -> Preference(context).apply {
                    isPersistent = false
                    summary = def.summary
                    setOnPreferenceClickListener { pref ->
                        val run = onButton ?: return@setOnPreferenceClickListener true
                        pref.isEnabled = false
                        pref.summary = "Running…"
                        run(def.key) { message ->
                            pref.isEnabled = true
                            pref.summary = def.summary
                            context.toast(message, Toast.LENGTH_LONG)
                        }
                        true
                    }
                }
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
            }
            preference.key = def.key
            preference.title = def.title
            screen.addPreference(preference)
        }
    }
}
