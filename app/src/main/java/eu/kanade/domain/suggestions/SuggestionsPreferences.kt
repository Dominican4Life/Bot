package eu.kanade.domain.suggestions

import tachiyomi.core.common.preference.PreferenceStore

/** KMK: Settings for the Suggestions tab (same options as Kototoro's suggestions). */
class SuggestionsPreferences(private val preferenceStore: PreferenceStore) {

    fun enabled() = preferenceStore.getBoolean("suggestions", true)

    fun onlyOnWifi() = preferenceStore.getBoolean("suggestions_wifi", false)

    fun includeDisabledSources() = preferenceStore.getBoolean("suggestions_disabled_sources", false)

    fun notifications() = preferenceStore.getBoolean("suggestions_notifications", false)

    /** Comma separated */
    fun excludedGenres() = preferenceStore.getString("suggestions_exclude_tags", "")

    /** Comma separated */
    fun preferredGenres() = preferenceStore.getString("suggestions_preferred_tags", "")

    /** Source ids */
    fun preferredSources() = preferenceStore.getStringSet("suggestions_preferred_sources", emptySet())

    /** Source ids */
    fun excludedSources() = preferenceStore.getStringSet("suggestions_excluded_sources", emptySet())

    /** The last results, as manga ids in ranked order */
    fun results() = preferenceStore.getString("suggestions_result_ids", "")

    fun lastUpdated() = preferenceStore.getLong("suggestions_last_updated", 0L)
}

fun String.splitGenres(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }
