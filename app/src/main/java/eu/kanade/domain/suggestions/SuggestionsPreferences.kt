package eu.kanade.domain.suggestions

import tachiyomi.core.common.preference.PreferenceStore

/** KMK: Settings for the Suggestions tab (started from Kototoro's options); manga and anime. */
class SuggestionsPreferences(private val preferenceStore: PreferenceStore) {

    fun enabled() = preferenceStore.getBoolean("suggestions", true)

    fun onlyOnWifi() = preferenceStore.getBoolean("suggestions_wifi", false)

    fun includeDisabledSources() = preferenceStore.getBoolean("suggestions_disabled_sources", false)

    /** Comma separated */
    fun excludedGenres() = preferenceStore.getString("suggestions_exclude_tags", "")

    /** Comma separated */
    fun preferredGenres() = preferenceStore.getString("suggestions_preferred_tags", "")

    /** Source ids */
    fun preferredSources() = preferenceStore.getStringSet("suggestions_preferred_sources", emptySet())

    /** Source ids */
    fun excludedSources() = preferenceStore.getStringSet("suggestions_excluded_sources", emptySet())

    /** What suggestions are based on: [FROM_HISTORY], [FROM_LIBRARY] or [FROM_CATEGORIES] */
    fun suggestFrom() = preferenceStore.getString("suggestions_from", FROM_HISTORY)

    /** Category ids, for [FROM_CATEGORIES] */
    fun suggestCategories() = preferenceStore.getStringSet("suggestions_from_categories", emptySet())

    /** The last results, as manga ids in ranked order */
    fun results() = preferenceStore.getString("suggestions_result_ids", "")

    /** Anime source ids */
    fun animePreferredSources() = preferenceStore.getStringSet("suggestions_anime_preferred_sources", emptySet())

    /** Anime source ids */
    fun animeExcludedSources() = preferenceStore.getStringSet("suggestions_anime_excluded_sources", emptySet())

    /** The last anime results, as anime ids in ranked order */
    fun animeResults() = preferenceStore.getString("suggestions_anime_result_ids", "")

    fun lastUpdated() = preferenceStore.getLong("suggestions_last_updated", 0L)

    companion object {
        /** What you read lately, in your library or not */
        const val FROM_HISTORY = "history"

        /** Everything in your library */
        const val FROM_LIBRARY = "library"

        /** The library titles in the chosen categories */
        const val FROM_CATEGORIES = "categories"
    }
}

fun String.splitGenres(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** The comma separated genre list with [genre] added (if it isn't there yet) or removed */
fun String.withGenre(genre: String): String =
    (splitGenres() + genre.trim()).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(", ")

fun String.withoutGenre(genre: String): String =
    splitGenres().filterNot { it.equals(genre.trim(), ignoreCase = true) }.joinToString(", ")
