package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.domain.ui.CoverBadge
import eu.kanade.domain.ui.SourceBadge
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Choose the corner each badge goes in (like Kototoro's "Badges in lists").
 * [SettingsCoverBadgesScreen] is for manga/anime covers, [SettingsSourceBadgesScreen] for source tiles.
 */
abstract class SettingsBadgesScreen(private val forSources: Boolean) : SearchableSettings {

    @Composable
    override fun getPreferences(): List<Preference> {
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val entries = if (forSources) {
            mapOf(
                SourceBadge.TYPE to stringResource(KMR.strings.badge_source_type),
                SourceBadge.LANGUAGE to stringResource(KMR.strings.badge_language),
                SourceBadge.NSFW to stringResource(KMR.strings.badge_nsfw),
                SourceBadge.SETTINGS to stringResource(KMR.strings.badge_settings),
                SourceBadge.PIN to stringResource(KMR.strings.badge_pinned),
                SourceBadge.LATEST to stringResource(KMR.strings.badge_latest),
            )
        } else {
            mapOf(
                CoverBadge.DOWNLOADED to stringResource(KMR.strings.badge_downloaded),
                CoverBadge.UNREAD to stringResource(KMR.strings.badge_unread),
                CoverBadge.LANGUAGE to stringResource(KMR.strings.badge_language),
                CoverBadge.SOURCE to stringResource(KMR.strings.badge_source_icon),
                CoverBadge.FAVORITE to stringResource(KMR.strings.badge_in_library),
            )
        }.toImmutableMap()

        fun prefOf(corner: BadgeCorner) =
            if (forSources) uiPreferences.sourceBadges(corner) else uiPreferences.coverBadges(corner)

        val items = BadgeCorner.entries.map { corner ->
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = prefOf(corner),
                entries = entries,
                title = stringResource(corner.titleRes()),
                onValueChanged = { chosen ->
                    // One corner per badge: picking it here takes it out of the others
                    BadgeCorner.entries.filter { it != corner }.forEach { other ->
                        val pref = prefOf(other)
                        val current = pref.get()
                        if (current.any { it in chosen }) pref.set(current - chosen)
                    }
                    true
                },
            )
        }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(if (forSources) KMR.strings.pref_source_badges else KMR.strings.pref_cover_badges),
                preferenceItems = buildList<Preference.PreferenceItem<out Any, out Any>> {
                    addAll(items)
                    add(Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.badge_corner_note)))
                }.toImmutableList(),
            ),
        )
    }
}

object SettingsCoverBadgesScreen : SettingsBadgesScreen(forSources = false) {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.pref_cover_badges
}

object SettingsSourceBadgesScreen : SettingsBadgesScreen(forSources = true) {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.pref_source_badges
}

private fun BadgeCorner.titleRes(): StringResource = when (this) {
    BadgeCorner.TopLeft -> KMR.strings.badge_corner_top_left
    BadgeCorner.TopRight -> KMR.strings.badge_corner_top_right
    BadgeCorner.BottomLeft -> KMR.strings.badge_corner_bottom_left
    BadgeCorner.BottomRight -> KMR.strings.badge_corner_bottom_right
}
