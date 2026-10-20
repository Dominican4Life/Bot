package eu.kanade.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The Anime | Manga tabs of History, Updates and Stats (anime on the left) */
object MediaTab {
    const val ANIME = 0
    const val MANGA = 1

    fun showsManga(tab: Int) = tab == MANGA
    fun showsAnime(tab: Int) = tab == ANIME

    /**
     * The tab a saved preference points to. Older builds saved All = 0, Manga = 1, Anime = 2:
     * Manga stays Manga, everything else opens on Anime.
     */
    fun fromPref(value: Int) = if (value == MANGA) MANGA else ANIME
}

@Composable
fun MediaTabRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = MediaTab.fromPref(selected), modifier = modifier) {
        listOf(
            MediaTab.ANIME to KMR.strings.feed_tab_anime,
            MediaTab.MANGA to KMR.strings.feed_tab_manga,
        ).forEach { (tab, title) ->
            Tab(
                selected = MediaTab.fromPref(selected) == tab,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(title)) },
                // KMK: like the library's tabs, only the chosen one is colored
                unselectedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** KMK: The toolbar button that opens [MediaDisplayDialog], with the same icon as a source's display mode button */
@Composable
fun mediaDisplayAction(displayMode: LibraryDisplayMode, onClick: () -> Unit) = AppBar.Action(
    title = stringResource(MR.strings.action_display_mode),
    icon = if (displayMode == LibraryDisplayMode.List) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewModule,
    onClick = onClick,
)

private val mediaDisplayModes = listOf(
    MR.strings.action_display_list to LibraryDisplayMode.List,
    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
)

/** KMK: The library's settings sheet with only its Display tab, for History and Updates */
@Composable
fun MediaDisplayDialog(
    onDismissRequest: () -> Unit,
    displayModePref: Preference<LibraryDisplayMode>,
    columnsPref: Preference<Int>,
) {
    // KMK: the Panorama styles, shared with the library
    val panoramaStylePref = remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }
    val panoramaStyle by panoramaStylePref.collectAsState()
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(stringResource(MR.strings.action_display)),
    ) { _ ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            val displayMode by displayModePref.collectAsState()
            SettingsChipRow(MR.strings.action_display_mode) {
                mediaDisplayModes.map { (titleRes, mode) ->
                    FilterChip(
                        selected = displayMode == mode,
                        onClick = { displayModePref.set(mode) },
                        label = { Text(stringResource(titleRes)) },
                    )
                }
                listOf(
                    KMR.strings.panorama_style_fill to PanoramaStyle.FILL,
                    KMR.strings.panorama_style_fit to PanoramaStyle.FIT,
                    KMR.strings.panorama_style_wide to PanoramaStyle.WIDE,
                ).map { (titleRes, style) ->
                    FilterChip(
                        selected = displayMode == LibraryDisplayMode.ComfortableGridPanorama && panoramaStyle == style,
                        onClick = {
                            panoramaStylePref.set(style)
                            displayModePref.set(LibraryDisplayMode.ComfortableGridPanorama)
                        },
                        label = { Text(stringResource(titleRes)) },
                        leadingIcon = {
                            Icon(
                                imageVector = PanoramaIcons.of(style),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
            if (displayMode != LibraryDisplayMode.List) {
                val columns by columnsPref.collectAsState()
                SliderItem(
                    value = columns,
                    valueRange = 0..15,
                    label = stringResource(MR.strings.pref_library_columns),
                    valueString = if (columns > 0) columns.toString() else stringResource(MR.strings.label_auto),
                    onChange = columnsPref::set,
                    pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

/** Grid columns for the grid display modes: a fixed number, or as many as fit */
@Composable
fun rememberMediaGridCells(columns: Int): GridCells {
    // Fewer covers per row fit when the phone is upright
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return when {
        columns > 0 -> GridCells.Fixed(columns)
        landscape -> GridCells.Adaptive(128.dp)
        else -> GridCells.Adaptive(112.dp)
    }
}

/** An anime cover in the manga cover model, for the shared grid items (negative id, like the anime library) */
fun animeCover(animeId: Long, sourceId: Long, favorite: Boolean, thumbnailUrl: String?) = MangaCover(
    mangaId = -animeId,
    sourceId = sourceId,
    isMangaFavorite = favorite,
    ogUrl = thumbnailUrl,
    lastModified = 0L,
)

/** KMK: One cover of History or Updates in the grid display modes, drawn like the library's */
@Composable
fun MediaGridItem(
    displayMode: LibraryDisplayMode,
    cover: MangaCover,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    coverAlpha: Float = 1f,
    /** Height / width of the cover once loaded, for the "Panorama: wide" spans */
    onCoverRatio: ((Float) -> Unit)? = null,
) {
    val panoramaStyle by remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }.collectAsState()
    when (displayMode) {
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
            coverData = cover,
            title = title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
            onClick = onClick,
            onLongClick = onLongClick,
            isSelected = isSelected,
            coverAlpha = coverAlpha,
        )
        else -> MangaComfortableGridItem(
            coverData = cover,
            title = title,
            onClick = onClick,
            onLongClick = onLongClick,
            isSelected = isSelected,
            coverAlpha = coverAlpha,
            usePanoramaCover = displayMode == LibraryDisplayMode.ComfortableGridPanorama,
            panoramaStyle = panoramaStyle,
            onCoverRatio = onCoverRatio,
        )
    }
}
