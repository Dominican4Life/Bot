package eu.kanade.presentation.browse.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.PanoramaIcons
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    // KMK -->
    onToggleIncognito: () -> Unit,
    /** Incognito is on for this source: its switch in the menu shows it */
    incognito: Boolean = false,
    // KMK <--
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // KMK -->
    toggleSelectionMode: () -> Unit,
    isRunning: Boolean,
    /** Covers per row shown in the display menu slider (0 = auto), or null to hide the slider */
    columns: Int? = null,
    onColumnsChange: (Int) -> Unit = {},
    /** Title when there is no manga [source] (anime sources) */
    titleOverride: String? = null,
    /** The source whose icon shows next to the title (anime sources, which aren't a manga [source]) */
    iconSourceId: Long? = null,
    /** The line under the title: the source's language */
    subtitleOverride: String? = null,
    showBulkSelection: Boolean = true,
    showIncognito: Boolean = true,
    // KMK <--
) {
    // Avoid capturing unstable source in actions lambda
    val title = titleOverride ?: source?.name
    val isLocalSource = source is LocalSource

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = {
            // KMK: the source's icon and language next to its name
            SourceTitle(
                title = title,
                subtitle = subtitleOverride ?: source?.lang?.let { LocaleHelper.getLocalizedDisplayName(it) },
                sourceId = iconSourceId ?: source?.id,
            )
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        if (displayMode != null) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_display_mode),
                                    icon = if (displayMode == LibraryDisplayMode.List) {
                                        Icons.AutoMirrored.Filled.ViewList
                                    } else {
                                        Icons.Filled.ViewModule
                                    },
                                    onClick = { selectingDisplayMode = true },
                                ),
                            )
                        }
                        // KMK -->
                        // A bulk add that is still running keeps its spinner in the bar
                        if (showBulkSelection && isRunning) add(bulkSelectionButton(isRunning, toggleSelectionMode))
                        // The menu: Incognito, WebView, Bulk Selection. Settings is a button next to Filter.
                        if (showIncognito) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(KMR.strings.browse_menu_incognito),
                                    onClick = onToggleIncognito,
                                    icon = ImageVector.vectorResource(R.drawable.ic_glasses_with_hat_24dp),
                                    checked = incognito,
                                ),
                            )
                        }
                        // KMK <--
                        if (isLocalSource) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.label_help),
                                    onClick = onHelpClick,
                                    // KMK -->
                                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                    // KMK <--
                                ),
                            )
                        } else {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_web_view),
                                    onClick = onWebViewClick,
                                    // KMK -->
                                    icon = Icons.Outlined.Public,
                                    // KMK <--
                                ),
                            )
                        }
                        // KMK -->
                        if (showBulkSelection && !isRunning) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(KMR.strings.browse_menu_bulk_selection),
                                    onClick = toggleSelectionMode,
                                    icon = Icons.Outlined.Checklist,
                                ),
                            )
                        }
                        // KMK <--
                    }
                    .build(),
            )

            // KMK: Back closes the menu
            BackHandler(enabled = selectingDisplayMode) { selectingDisplayMode = false }
            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
                // KMK: not focusable, so closing it doesn't hand focus back to the screen and flash the keyboard
                properties = PopupProperties(focusable = false),
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                // KMK -->
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_cover_only_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CoverOnlyGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CoverOnlyGrid)
                }
                // KMK <--
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
                // KMK: the Panorama styles, as a row of icons
                PanoramaStyleSection(
                    displayMode = displayMode,
                    onDisplayModeChange = onDisplayModeChange,
                )
                // KMK -->
                if (columns != null && displayMode != LibraryDisplayMode.List) {
                    HorizontalDivider()
                    Box(modifier = Modifier.width(300.dp)) {
                        SliderItem(
                            value = columns,
                            valueRange = 0..15,
                            label = stringResource(MR.strings.pref_library_columns),
                            valueString = if (columns > 0) {
                                columns.toString()
                            } else {
                                stringResource(MR.strings.label_auto)
                            },
                            onChange = onColumnsChange,
                            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
                // KMK <--
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/** KMK: A source's icon, name and language, for the top of a source's pages */
@Composable
fun SourceTitle(title: String?, subtitle: String?, sourceId: Long?) {
    val icon = remember(sourceId) { sourceId?.let(::sourceIconBitmap) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        AppBarTitle(title, subtitle = subtitle?.takeIf { it.isNotBlank() })
    }
}

/** The icon of any source: a manga or anime extension's, a built-in one's, or a Code editor source's */
fun sourceIconBitmap(sourceId: Long): ImageBitmap? = runCatching {
    (
        Injekt.get<ExtensionManager>().getAppIconForSource(sourceId)
            ?: Injekt.get<AnimeExtensionManager>().getAppIconForSource(sourceId)
        )?.toBitmap()?.asImageBitmap()
}.getOrNull()

// KMK -->
/** "Panorama comfortable grid" in its three styles (color fill, fit width, wide), as a row of icons */
@Composable
internal fun PanoramaStyleSection(
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
) {
    val stylePref = remember { Injekt.get<UiPreferences>().browsePanoramaStyle() }
    val style by stylePref.collectAsState()
    HorizontalDivider()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(300.dp).padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(KMR.strings.browse_panorama),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            listOf(
                KMR.strings.panorama_style_fill to PanoramaStyle.FILL,
                KMR.strings.panorama_style_fit to PanoramaStyle.FIT,
                KMR.strings.panorama_style_wide to PanoramaStyle.WIDE,
            ).forEach { (label, value) ->
                FilledTonalIconToggleButton(
                    checked = displayMode == LibraryDisplayMode.ComfortableGridPanorama && style == value,
                    onCheckedChange = {
                        stylePref.set(value)
                        onDisplayModeChange(LibraryDisplayMode.ComfortableGridPanorama)
                    },
                ) {
                    Icon(imageVector = PanoramaIcons.of(value), contentDescription = stringResource(label))
                }
            }
        }
    }
}
// KMK <--
