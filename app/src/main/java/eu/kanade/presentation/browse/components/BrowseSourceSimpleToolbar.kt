package eu.kanade.presentation.browse.components

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.PopupProperties
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BrowseSourceSimpleToolbar(
    navigateUp: () -> Unit,
    title: String,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    // KMK -->
    toggleSelectionMode: (() -> Unit)?,
    isRunning: Boolean,
    // KMK <--
) {
    AppBar(
        navigateUp = navigateUp,
        title = title,
        actions = {
            var selectingDisplayMode by remember { mutableStateOf(false) }
            // KMK -->
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder().apply {
                    displayMode?.let { mode ->
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_display_mode),
                                icon = if (mode == LibraryDisplayMode.List) {
                                    Icons.AutoMirrored.Filled.ViewList
                                } else {
                                    Icons.Filled.ViewModule
                                },
                                onClick = { selectingDisplayMode = true },
                            ),
                        )
                    }
                    toggleSelectionMode?.let { mode ->
                        add(
                            bulkSelectionButton(isRunning, mode),
                        )
                    }
                }
                    .build(),
            )
            // KMK <--
            // KMK: Back closes the menu
            BackHandler(enabled = selectingDisplayMode) { selectingDisplayMode = false }
            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
                // KMK: not focusable, so closing it doesn't hand focus back to the screen and flash the keyboard
                properties = PopupProperties(focusable = false),
            ) {
                // KMK -->
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                PanoramaStyleMenuItems(
                    displayMode = displayMode,
                    onDisplayModeChange = onDisplayModeChange,
                    onDismiss = { selectingDisplayMode = false },
                )
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
                // KMK <--
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
