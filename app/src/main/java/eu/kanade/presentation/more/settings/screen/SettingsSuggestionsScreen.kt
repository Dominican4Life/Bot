package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.suggestions.SuggestionsPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.suggestions.GenreChipsItem
import eu.kanade.presentation.more.settings.screen.suggestions.SuggestionSourcesScreen
import eu.kanade.tachiyomi.data.suggestions.SuggestionsJob
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState

/**
 * KMK: Settings of the Suggestions tab. Started from Kototoro's options, then reworked:
 * - turning suggestions off also hides the Suggestions tab;
 * - "Suggest from" picks what suggestions are based on: reading history, the whole library or some categories;
 * - genres are added one at a time with + and removed with a long press;
 * - one Sources screen marks each source as preferred or excluded, like Mihon's library filters.
 */
object SettingsSuggestionsScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.label_suggestions

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<SuggestionsPreferences>() }
        val getCategories = remember { Injekt.get<GetCategories>() }
        val categories by getCategories.subscribe().collectAsState(initial = emptyList())
        val suggestFrom by prefs.suggestFrom().collectPrefAsState()
        val preferred by prefs.preferredSources().collectPrefAsState()
        val excluded by prefs.excludedSources().collectPrefAsState()
        val animePreferred by prefs.animePreferredSources().collectPrefAsState()
        val animeExcluded by prefs.animeExcludedSources().collectPrefAsState()

        val refresh: suspend (Any) -> Boolean = {
            SuggestionsJob.startNow(context)
            true
        }
        val categoryEntries = categories
            .filterNot { it.isSystemCategory }
            .associate { it.id.toString() to it.name }
            .toImmutableMap()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.label_suggestions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled(),
                        title = stringResource(KMR.strings.suggestions_enable),
                        subtitle = stringResource(KMR.strings.suggestions_enable_summary),
                        onValueChanged = { enabled ->
                            prefs.enabled().set(enabled)
                            SuggestionsJob.setupTask(context)
                            if (enabled) SuggestionsJob.startNow(context)
                            true
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.onlyOnWifi(),
                        title = stringResource(KMR.strings.suggestions_wifi),
                        subtitle = stringResource(KMR.strings.suggestions_wifi_summary),
                        onValueChanged = {
                            prefs.onlyOnWifi().set(it)
                            SuggestionsJob.setupTask(context)
                            true
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.suggestions_group_based_on),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.suggestFrom(),
                        entries = persistentMapOf(
                            SuggestionsPreferences.FROM_HISTORY to stringResource(KMR.strings.suggestions_from_history),
                            SuggestionsPreferences.FROM_LIBRARY to stringResource(KMR.strings.suggestions_from_library),
                            SuggestionsPreferences.FROM_CATEGORIES to
                                stringResource(KMR.strings.suggestions_from_categories),
                        ),
                        title = stringResource(KMR.strings.suggestions_from),
                        onValueChanged = refresh,
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.suggestCategories(),
                        entries = categoryEntries,
                        title = stringResource(KMR.strings.suggestions_from_categories_pick),
                        subtitleProvider = { value, entries ->
                            val names = value.mapNotNull { entries[it] }
                            if (names.isEmpty()) {
                                stringResource(KMR.strings.suggestions_from_categories_none)
                            } else {
                                names.sorted().joinToString()
                            }
                        },
                        enabled = suggestFrom == SuggestionsPreferences.FROM_CATEGORIES && categoryEntries.isNotEmpty(),
                        onValueChanged = refresh,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.suggestions_group_sources),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.suggestions_sources),
                        subtitle = stringResource(
                            KMR.strings.suggestions_sources_summary,
                            preferred.size + animePreferred.size,
                            excluded.size + animeExcluded.size,
                        ),
                        onClick = { navigator.push(SuggestionSourcesScreen()) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.includeDisabledSources(),
                        title = stringResource(KMR.strings.suggestions_disabled_sources),
                        subtitle = stringResource(KMR.strings.suggestions_disabled_sources_summary),
                        onValueChanged = refresh,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.suggestions_group_genres),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(KMR.strings.suggestions_preferred_genres),
                    ) {
                        GenreChipsItem(
                            preference = prefs.preferredGenres(),
                            title = stringResource(KMR.strings.suggestions_preferred_genres),
                            summary = stringResource(KMR.strings.suggestions_preferred_genres_summary),
                            onChanged = { SuggestionsJob.startNow(context) },
                        )
                    },
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(KMR.strings.suggestions_exclude_genres),
                    ) {
                        GenreChipsItem(
                            preference = prefs.excludedGenres(),
                            title = stringResource(KMR.strings.suggestions_exclude_genres),
                            summary = stringResource(KMR.strings.suggestions_exclude_genres_summary),
                            onChanged = { SuggestionsJob.startNow(context) },
                        )
                    },
                    Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.suggestions_local_note)),
                ),
            ),
        )
    }
}
