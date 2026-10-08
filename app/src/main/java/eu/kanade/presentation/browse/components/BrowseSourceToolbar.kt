package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

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
    // KMK <--
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
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
                        add(bulkSelectionButton(isRunning, toggleSelectionMode))
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.pref_incognito_mode),
                                onClick = onToggleIncognito,
                            ),
                        )
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

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                // KMK -->
                RadioMenuItem(
                    text = { Text(text = stringResource(KMR.strings.action_display_comfortable_grid_panorama)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGridPanorama,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGridPanorama)
                }
                // KMK <--
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
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
