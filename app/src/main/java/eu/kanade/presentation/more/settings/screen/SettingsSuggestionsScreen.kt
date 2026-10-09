package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.suggestions.SuggestionsPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.suggestions.SuggestionsJob
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: Settings of the Suggestions tab (same options as Kototoro). */
object SettingsSuggestionsScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.label_suggestions

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { Injekt.get<SuggestionsPreferences>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val sources = remember {
            sourceManager.getVisibleOnlineSources()
                .sortedBy { it.name.lowercase() }
                .associate { it.id.toString() to "${it.name} (${it.lang.uppercase()})" }
                .toImmutableMap()
        }
        val reschedule: suspend (Any) -> Boolean = {
            SuggestionsJob.setupTask(context)
            true
        }
        val refresh: suspend (Any) -> Boolean = {
            SuggestionsJob.startNow(context)
            true
        }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.label_suggestions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled(),
                        title = stringResource(KMR.strings.suggestions_enable),
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
                            reschedule(it)
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.includeDisabledSources(),
                        title = stringResource(KMR.strings.suggestions_disabled_sources),
                        subtitle = stringResource(KMR.strings.suggestions_disabled_sources_summary),
                        onValueChanged = { refresh(it) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.notifications(),
                        title = stringResource(KMR.strings.suggestions_notifications),
                        subtitle = stringResource(KMR.strings.suggestions_notifications_summary),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.excludedGenres(),
                        title = stringResource(KMR.strings.suggestions_exclude_genres),
                        subtitle = stringResource(KMR.strings.suggestions_exclude_genres_summary),
                        onValueChanged = { refresh(it) },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.preferredGenres(),
                        title = stringResource(KMR.strings.suggestions_preferred_genres),
                        subtitle = stringResource(KMR.strings.suggestions_preferred_genres_summary),
                        onValueChanged = { refresh(it) },
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.preferredSources(),
                        entries = sources,
                        title = stringResource(KMR.strings.suggestions_preferred_sources),
                        subtitle = stringResource(KMR.strings.suggestions_preferred_sources_summary),
                        onValueChanged = { refresh(it) },
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.excludedSources(),
                        entries = sources,
                        title = stringResource(KMR.strings.suggestions_excluded_sources),
                        subtitle = stringResource(KMR.strings.suggestions_excluded_sources_summary),
                        onValueChanged = { refresh(it) },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.suggestions_local_note)),
                ),
            ),
        )
    }
}
