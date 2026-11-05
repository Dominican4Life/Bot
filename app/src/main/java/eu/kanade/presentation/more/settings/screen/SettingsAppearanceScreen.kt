package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.materialkolor.PaletteStyle
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.WideCoverStyle
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.WideCoverPlace
import eu.kanade.presentation.more.settings.WideCoverSync
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.widget.AppThemeModePreferenceWidget
import eu.kanade.presentation.more.settings.widget.AppThemePreferenceWidget
import eu.kanade.presentation.more.settings.widget.WideCoverPlaceRow
import eu.kanade.presentation.more.settings.widget.WideCoverStylePicker
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

object SettingsAppearanceScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsAppearanceScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_appearance

    @Composable
    override fun getPreferences(): List<Preference> {
        val uiPreferences = remember { Injekt.get<UiPreferences>() }

        return listOf(
            getThemeGroup(uiPreferences = uiPreferences),
            // KMK -->
            // KMK (r115): one card for every wide-cover setting (they were spread over this page and the view menus)
            getWideCoversGroup(uiPreferences = uiPreferences),
            getMangaInfoThemeGroup(uiPreferences = uiPreferences),
            // KMK <--
            getDisplayGroup(uiPreferences = uiPreferences),
            // SY -->
            getNavbarGroup(uiPreferences = uiPreferences),
            getForkGroup(uiPreferences = uiPreferences),
            // SY <--
        )
    }

    @Composable
    private fun getThemeGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val themeModePref = uiPreferences.themeMode()
        val themeMode by themeModePref.collectAsState()

        val appThemePref = uiPreferences.appTheme()
        val appTheme by appThemePref.collectAsState()

        val amoledPref = uiPreferences.themeDarkAmoled()
        val amoled by amoledPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_theme),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_app_theme),
                ) {
                    Column {
                        AppThemeModePreferenceWidget(
                            value = themeMode,
                            onItemClick = {
                                themeModePref.set(it)
                                setAppCompatDelegateThemeMode(it)
                            },
                        )

                        AppThemePreferenceWidget(
                            value = appTheme,
                            amoled = amoled,
                            onItemClick = { appThemePref.set(it) },
                        )
                    }
                },
                // KMK -->
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_custom_color),
                    subtitle = stringResource(KMR.strings.custom_color_description),
                    enabled = appTheme == AppTheme.CUSTOM,
                    onClick = { navigator.push(AppCustomThemeColorPickerScreen()) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.customThemeStyle(),
                    entries = PaletteStyle.entries
                        .associateWith {
                            when (it) {
                                PaletteStyle.TonalSpot ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_tonalspot)
                                PaletteStyle.Neutral ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_neutral)
                                PaletteStyle.Vibrant ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_vibrant)
                                PaletteStyle.Expressive ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_expressive)
                                PaletteStyle.Rainbow ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_rainbow)
                                PaletteStyle.FruitSalad ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fruitsalad)
                                PaletteStyle.Monochrome ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_monochrome)
                                PaletteStyle.Fidelity ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fidelity)
                                PaletteStyle.Content ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_content)
                            }
                        }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.pref_custom_theme_style),
                    enabled = appTheme == AppTheme.CUSTOM,
                    onValueChanged = {
                        (context as? Activity)?.let { ActivityCompat.recreate(it) }
                        true
                    },
                ),
                // KMK <--
                Preference.PreferenceItem.SwitchPreference(
                    preference = amoledPref,
                    title = stringResource(MR.strings.pref_dark_theme_pure_black),
                    enabled = themeMode != ThemeMode.LIGHT,
                    onValueChanged = {
                        (context as? Activity)?.let { ActivityCompat.recreate(it) }
                        true
                    },
                ),
            ),
        )
    }

    // KMK -->
    @Composable
    private fun getMangaInfoThemeGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow
        val mangaInfoThemeCoverBased by remember {
            Injekt.get<UiPreferences>().themeCoverBased().asState(scope)
        }
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_manga_info),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.themeCoverBased(),
                    title = stringResource(KMR.strings.pref_theme_cover_based),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.themeCoverBasedStyle(),
                    entries = PaletteStyle.entries
                        .associateWith {
                            when (it) {
                                PaletteStyle.TonalSpot ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_tonalspot)
                                PaletteStyle.Neutral ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_neutral)
                                PaletteStyle.Vibrant ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_vibrant)
                                PaletteStyle.Expressive ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_expressive)
                                PaletteStyle.Rainbow ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_rainbow)
                                PaletteStyle.FruitSalad ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fruitsalad)
                                PaletteStyle.Monochrome ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_monochrome)
                                PaletteStyle.Fidelity ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fidelity)
                                PaletteStyle.Content ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_content)
                            }
                        }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.pref_theme_cover_based_style),
                    enabled = mangaInfoThemeCoverBased,
                ),
                // KMK (r124): the phone title page's sheet (chapters / episodes sheet, ▦ Preview, Play dock)
                Preference.PreferenceItem.SwitchPreference(
                    preference = eu.kanade.presentation.components.TitleSheet.enabled(),
                    title = stringResource(KMR.strings.title_sheet_pref),
                    subtitle = stringResource(KMR.strings.title_sheet_pref_summary),
                ),
                // KMK (r125): the tablet glass preview panel
                Preference.PreferenceItem.SwitchPreference(
                    preference = eu.kanade.presentation.browse.components.PreviewGlass.enabled(),
                    title = stringResource(KMR.strings.preview_glass_pref),
                    subtitle = stringResource(KMR.strings.preview_glass_pref_summary),
                ),
                // KMK (r126): Glass look (material, see-through, corners, knob, accessibility) with a live preview
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.glass_title),
                    subtitle = stringResource(KMR.strings.glass_title_summary),
                    onClick = { navigator.push(eu.kanade.presentation.more.settings.screen.appearance.GlassSettingsScreen()) },
                ),
                // KMK (r115): the title-page panorama switch and "Align cover to top" moved to the Wide covers card
            ),
        )
    }

    // KMK (r115): Wide covers (mockup SCovers). The picker and the per-place list write the old prefs through
    // WideCoverSync; "Same everywhere" (default on) makes a change anywhere (here or a view menu) apply to all places.
    @Composable
    private fun getWideCoversGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val samePref = remember { uiPreferences.wideCoverSameEverywhere() }
        val same by samePref.collectAsState()
        val shared by remember { uiPreferences.wideCoverStyle() }.collectAsState()
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val historyPreferences = remember { Injekt.get<HistoryPreferences>() }
        val libraryMode by remember { libraryPreferences.displayMode() }.collectAsState()
        val libraryStyle by remember { uiPreferences.libraryPanoramaStyle() }.collectAsState()
        val browseMode by remember { sourcePreferences.sourceDisplayMode() }.collectAsState()
        val browseStyle by remember { uiPreferences.browsePanoramaStyle() }.collectAsState()
        val historyOn by remember { historyPreferences.usePanoramaCover() }.collectAsState()
        val flowOn by remember { uiPreferences.usePanoramaCoverFlow() }.collectAsState()
        val headerOn by remember { uiPreferences.usePanoramaCoverMangaInfo() }.collectAsState()
        val dialogsOn by remember { uiPreferences.usePanoramaCoverAlways() }.collectAsState()

        val onStyle = if (shared != WideCoverStyle.OFF) shared else WideCoverStyle.of(libraryStyle)
        fun switchValue(on: Boolean) = if (on) onStyle else WideCoverStyle.OFF
        val values = mapOf(
            WideCoverPlace.LIBRARY to WideCoverSync.styleOf(libraryMode, libraryStyle),
            WideCoverPlace.BROWSE to WideCoverSync.styleOf(browseMode, browseStyle),
            WideCoverPlace.HISTORY_UPDATES to switchValue(historyOn),
            WideCoverPlace.SEARCH to switchValue(flowOn),
            WideCoverPlace.TITLE_HEADER to switchValue(headerOn),
            WideCoverPlace.DIALOGS to switchValue(dialogsOn),
        )
        // A Library / Browse grid other than Comfortable or Wide covers (Compact, List…) only uses the style later
        val otherGrid = mapOf(
            WideCoverPlace.LIBRARY to libraryMode.isOtherGrid(),
            WideCoverPlace.BROWSE to browseMode.isOtherGrid(),
        )
        val differs = WideCoverPlace.entries.any { place ->
            val value = values.getValue(place)
            when {
                !place.hasStyles -> (value == WideCoverStyle.OFF) != (shared == WideCoverStyle.OFF)
                otherGrid[place] == true && shared == WideCoverStyle.OFF -> false
                else -> value != shared
            }
        }
        val note = when {
            same && differs -> "Some places still have their own look. Tap a style to use it everywhere."
            !same && differs -> "Places differ: tapping a style sets all of them."
            else -> null
        }

        return Preference.PreferenceGroup(
            title = "Wide covers",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "Wide covers",
                ) {
                    WideCoverStylePicker(
                        selected = shared.takeUnless { differs },
                        note = note,
                        onPick = { WideCoverSync.setEverywhere(it) },
                    )
                },
                Preference.PreferenceItem.SwitchPreference(
                    preference = samePref,
                    title = "Same everywhere",
                    subtitle = "A change in any view menu changes all of them",
                    onValueChanged = { on ->
                        if (on) WideCoverSync.setEverywhere(uiPreferences.wideCoverStyle().get())
                        true
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Where it applies",
                ) {
                    Column {
                        Text(
                            text = if (same) {
                                "Where it applies (turn off Same everywhere to choose per place)"
                            } else {
                                "Where it applies"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        WideCoverPlace.entries.forEach { place ->
                            WideCoverPlaceRow(
                                place = place,
                                value = values.getValue(place),
                                note = when {
                                    otherGrid[place] == true -> "Used when this screen shows the Wide covers grid"
                                    place == WideCoverPlace.HISTORY_UPDATES ->
                                        "Lists; their grids use the Library's style"
                                    else -> null
                                },
                                enabled = !same,
                                onPick = { WideCoverSync.apply(place, it) },
                            )
                        }
                    }
                },
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.topAlignCover(),
                    title = stringResource(KMR.strings.pref_top_align_cover),
                    subtitle = stringResource(KMR.strings.pref_top_align_cover_summary),
                ),
            ),
        )
    }

    private fun LibraryDisplayMode.isOtherGrid() =
        this != LibraryDisplayMode.ComfortableGrid && this != LibraryDisplayMode.ComfortableGridPanorama
    // KMK <--

    @Composable
    private fun getDisplayGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val now = remember { LocalDate.now() }

        val dateFormat by uiPreferences.dateFormat().collectAsState()
        val formattedNow = remember(dateFormat) {
            UiPreferences.dateFormat(dateFormat).format(now)
        }

        val currentLanguage = remember {
            AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: ""
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_app_language) +
                        if (currentLanguage.isNotEmpty() && !currentLanguage.startsWith("en")) " (App Language)" else "",
                    onClick = { navigator.push(AppLanguageScreen()) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.tabletUiMode(),
                    entries = TabletUiMode.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_tablet_ui_mode),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.dateFormat(),
                    entries = DateFormats
                        .associateWith {
                            val formattedDate = UiPreferences.dateFormat(it).format(now)
                            "${it.ifEmpty { stringResource(MR.strings.label_default) }} ($formattedDate)"
                        }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_date_format),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.relativeTime(),
                    title = stringResource(MR.strings.pref_relative_format),
                    subtitle = stringResource(
                        MR.strings.pref_relative_format_summary,
                        stringResource(MR.strings.relative_time_today),
                        formattedNow,
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.imagesInDescription(),
                    title = stringResource(MR.strings.pref_display_images_description),
                ),
            ),
        )
    }

    // SY -->
    @Composable
    fun getForkGroup(uiPreferences: UiPreferences): Preference.PreferenceGroup {
        val previewsRowCount by uiPreferences.previewsRowCount().collectAsState()

        return Preference.PreferenceGroup(
            stringResource(SYMR.strings.pref_category_fork),
            preferenceItems = persistentListOf(
                // KMK (r115): Panorama cover flow and the library / browse Panorama styles moved to the
                // Wide covers card
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.expandFilters(),
                    title = stringResource(SYMR.strings.toggle_expand_search_filters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.recommendsInOverflow(),
                    title = stringResource(SYMR.strings.put_recommends_in_overflow),
                    subtitle = stringResource(SYMR.strings.put_recommends_in_overflow_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.mergeInOverflow(),
                    title = stringResource(SYMR.strings.put_merge_in_overflow),
                    subtitle = stringResource(SYMR.strings.put_merge_in_overflow_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = previewsRowCount,
                    valueRange = 0..10,
                    title = stringResource(SYMR.strings.pref_previews_row_count),
                    valueString = if (previewsRowCount > 0) {
                        pluralStringResource(
                            SYMR.plurals.row_count,
                            previewsRowCount,
                            previewsRowCount,
                        )
                    } else {
                        stringResource(MR.strings.disabled)
                    },
                    onValueChanged = { uiPreferences.previewsRowCount().set(it) },
                ),
            ),
        )
    }

    @Composable
    fun getNavbarGroup(uiPreferences: UiPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            stringResource(SYMR.strings.pref_category_navbar),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.showNavUpdates(),
                    title = stringResource(SYMR.strings.pref_hide_updates_button),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.showNavHistory(),
                    title = stringResource(SYMR.strings.pref_hide_history_button),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.bottomBarLabels(),
                    title = stringResource(SYMR.strings.pref_show_bottom_bar_labels),
                ),
            ),
        )
    }
    // SY <--
}

private val DateFormats = listOf(
    "", // Default
    "MM/dd/yy",
    "dd/MM/yy",
    "yyyy-MM-dd",
    "dd MMM yyyy",
    "MMM dd, yyyy",
)
