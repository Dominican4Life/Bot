package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PlayerSettingsSubtitleScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AMR.strings.pref_player_subtitle

    @Composable
    override fun getPreferences(): List<Preference> {
        val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }

        val langPref = subtitlePreferences.preferredSubLanguages()
        val whitelist = subtitlePreferences.subtitleWhitelist()
        val blacklist = subtitlePreferences.subtitleBlacklist()
        val disableAutoSubtitles = subtitlePreferences.disableAutoSubtitles()

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = disableAutoSubtitles,
                title = stringResource(AMR.strings.pref_disable_auto_subtitles),
                subtitle = stringResource(AMR.strings.pref_disable_auto_subtitles_summary),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = langPref,
                title = stringResource(AMR.strings.pref_player_subtitle_lang),
                dialogSubtitle = stringResource(AMR.strings.pref_player_subtitle_lang_info),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = whitelist,
                title = stringResource(AMR.strings.pref_player_subtitle_whitelist),
                dialogSubtitle = stringResource(AMR.strings.pref_player_subtitle_whitelist_info),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = blacklist,
                title = stringResource(AMR.strings.pref_player_subtitle_blacklist),
                dialogSubtitle = stringResource(AMR.strings.pref_player_subtitle_blacklist_info),
            ),
        )
    }
}
