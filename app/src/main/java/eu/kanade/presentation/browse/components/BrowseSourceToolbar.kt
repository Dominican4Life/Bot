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
import androidx.compose.material.icons.filled.ViewModule
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.PanoramaIcons
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
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
    onSettingsClick: (() -> Unit)?,
    // KMK <--
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // KMK -->
    toggleSelectionMode: () -> Unit,
    isRunning: Boolean,
    /** Covers per row shown in the display menu slider (0 = auto), or null to hide the slider */
    columns: Int? = null,
    onColumnsChange: (Int) -> Unit = {},
    /** Which preview panels open on the first tap of a cover (SourcePreferences.PREVIEW_PANELS_*), null to hide */
    previewPanels: Int? = null,
    onPreviewPanelsChange: (Int) -> Unit = {},
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
                        if (showBulkSelection) add(bulkSelectionButton(isRunning, toggleSelectionMode))
                        if (showIncognito) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.pref_incognito_mode),
                                    onClick = onToggleIncognito,
                                ),
                            )
                        }
                        // KMK <--
                        if (isLocalSource) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.label_help),
                                    onClick = onHelpClick,
                                ),
                            )
                        } else {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_web_view),
                                    onClick = onWebViewClick,
                                ),
                            )
                        }
                        // SY <--
                        // KMK -->
                        onSettingsClick?.let {
                            // KMK <--
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_settings),
                                    onClick = onSettingsClick,
                                ),
                            )
                        }
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
                // KMK: the Panorama styles, as a row of icons like Panels
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
                if (previewPanels != null) {
                    HorizontalDivider()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(300.dp).padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(KMR.strings.browse_panels),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            listOf(
                                Triple(SourcePreferences.PREVIEW_PANELS_LEFT, PanelIcons.LeftOnly, KMR.strings.browse_panels_left),
                                Triple(SourcePreferences.PREVIEW_PANELS_BOTH, PanelIcons.Both, KMR.strings.browse_panels_both),
                                Triple(SourcePreferences.PREVIEW_PANELS_NONE, PanelIcons.None, KMR.strings.browse_panels_none),
                            ).forEach { (mode, icon, label) ->
                                FilledTonalIconToggleButton(
                                    checked = previewPanels == mode,
                                    onCheckedChange = { onPreviewPanelsChange(mode) },
                                ) {
                                    Icon(imageVector = icon, contentDescription = stringResource(label))
                                }
                            }
                        }
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
/** "Panorama comfortable grid" in its three styles (color fill, fit width, wide), as a row of icons like Panels */
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
