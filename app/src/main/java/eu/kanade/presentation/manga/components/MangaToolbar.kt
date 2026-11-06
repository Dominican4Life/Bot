package eu.kanade.presentation.manga.components

import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.DeleteOutline
import eu.kanade.presentation.components.GlassIcons
import eu.kanade.presentation.browse.components.liquidGlass
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MangaToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickFilter: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickMigrate: (() -> Unit)?,
    onClickEditNotes: () -> Unit,
    // SY -->
    onClickEditInfo: (() -> Unit)?,
    // KMK -->
    onClickRelatedMangas: (() -> Unit)?,
    onClearManga: () -> Unit,
    onOpenMangaFolder: (() -> Unit)?,
    // KMK <--
    onClickRecommend: (() -> Unit)?,
    onClickMerge: (() -> Unit)?,
    onClickMergedSettings: (() -> Unit)?,
    // SY <--

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    // KMK -->
    onPaletteScreenClick: () -> Unit,
    // KMK (r129): glass buttons over the cover (phone title page with the glass sheet)
    glass: Boolean = false,
    // KMK (r130): the title sheet shows download and filter itself
    sheetHasActions: Boolean = false,
    // KMK (r132): the counts shown in the download menu
    downloadCounts: DownloadMenuCounts? = null,
    // KMK (r132): tablet title page: the Preview and Suggestions toggles at the start of the glass capsule
    previewToggle: Pair<Boolean, () -> Unit>? = null,
    suggestionsToggle: Pair<Boolean, () -> Unit>? = null,
    // KMK <--
    modifier: Modifier = Modifier,
) {
    // KMK -->
    val navigator = LocalNavigator.current
    fun onHomeClicked() = navigator?.popUntil { screen ->
        screen is SourceFeedScreen || screen is BrowseSourceScreen
    }
    val isHomeEnabled = Injekt.get<UiPreferences>().showHomeOnRelatedMangas().get()
    // KMK <--

    val isActionMode = actionModeCounter > 0
    AppBar(
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else if (glass) {
                // KMK (r131): no title over the page, only the floating buttons
            } else {
                AppBarTitle(title, modifier = Modifier.alpha(titleAlphaProvider()))
            }
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            // KMK (r133): with glass, no header strip even when scrolled (the buttons just float)
            .copy(alpha = if (isActionMode) 1f else if (glass) 0f else backgroundAlphaProvider()),
        // KMK -->
        goHome = { onHomeClicked() }.takeIf {
            isHomeEnabled &&
                navigator != null &&
                (
                    navigator.size >= 5 ||
                        (navigator.size >= 2 && navigator.items[navigator.size - 2] is MangaScreen)
                    )
        },
        // KMK <--
        navigateUp = navigateUp,
        glassButtons = glass && !isActionMode,
        actions = {
            var downloadExpanded by remember { mutableStateOf(false) }
            if (onClickDownload != null) {
                // KMK: the same three choices as the Browse chapter panel's download button
                DropdownMenu(
                    expanded = downloadExpanded,
                    onDismissRequest = { downloadExpanded = false },
                ) {
                    // KMK (r132): icons and how many chapters each choice would download
                    GlassDownloadMenuItems(
                        counts = downloadCounts,
                        onDismiss = { downloadExpanded = false },
                        onAction = onClickDownload,
                    )
                }
            }

            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder().apply {
                    if (isActionMode) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = GlassIcons.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = GlassIcons.InvertSelection,
                                onClick = onInvertSelection,
                            ),
                        )
                        return@apply
                    }
                    // KMK (r132): Preview / Suggestions toggles (the accent when on)
                    // KMK (r133): Suggestions first, then the Preview panel
                    suggestionsToggle?.let { (on, toggle) ->
                        add(
                            AppBar.Action(
                                title = stringResource(KMR.strings.pref_source_related_mangas),
                                icon = GlassIcons.Suggestions,
                                iconTint = if (on) tachiyomi.presentation.core.components.LocalGlassAccent.current else null,
                                onClick = toggle,
                            ),
                        )
                    }
                    previewToggle?.let { (on, toggle) ->
                        add(
                            AppBar.Action(
                                title = stringResource(KMR.strings.title_sheet_preview),
                                icon = GlassIcons.Preview,
                                iconTint = if (on) tachiyomi.presentation.core.components.LocalGlassAccent.current else null,
                                onClick = toggle,
                            ),
                        )
                    }
                    // KMK (r130): with the title sheet, download and filter are on the sheet only
                    if (!sheetHasActions) {
                        if (onClickDownload != null) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.manga_download),
                                    icon = GlassIcons.Download,
                                    onClick = { downloadExpanded = !downloadExpanded },
                                ),
                            )
                        }
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_filter),
                                icon = GlassIcons.Filter,
                                iconTint = filterTint,
                                onClick = onClickFilter,
                            ),
                        )
                    }
                    // KMK: no Refresh here; pull down on the page to refresh
                    // KMK (r130): Edit, Files, Share, Notes, Merge, Migrate … Clear Data last in red. Edit categories
                    // is gone from here (hold the heart)
                    if (onClickEditInfo != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(KMR.strings.action_edit_short),
                                icon = Icons.Outlined.Edit,
                                onClick = onClickEditInfo,
                            ),
                        )
                    }
                    if (onOpenMangaFolder != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(KMR.strings.action_files),
                                icon = Icons.Outlined.Folder,
                                onClick = onOpenMangaFolder,
                            ),
                        )
                    }
                    if (onClickShare != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                icon = Icons.Outlined.Share,
                                onClick = onClickShare,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_notes),
                            icon = Icons.Outlined.EditNote,
                            onClick = onClickEditNotes,
                        ),
                    )
                    // SY -->
                    if (onClickMerge != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(SYMR.strings.merge),
                                icon = Icons.AutoMirrored.Outlined.CallMerge,
                                onClick = onClickMerge,
                            ),
                        )
                    }
                    // SY <--
                    if (onClickMigrate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_migrate),
                                icon = Icons.Outlined.SwapHoriz,
                                onClick = onClickMigrate,
                            ),
                        )
                    }
                    // KMK -->
                    if (onClickRelatedMangas != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(KMR.strings.pref_source_related_mangas),
                                icon = GlassIcons.Suggestions,
                                onClick = onClickRelatedMangas,
                            ),
                        )
                    }
                    // KMK <--
                    if (onClickRecommend != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(SYMR.strings.az_recommends),
                                icon = Icons.Outlined.Explore,
                                onClick = onClickRecommend,
                            ),
                        )
                    }
                    if (onClickMergedSettings != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(SYMR.strings.merge_settings),
                                icon = Icons.Outlined.Tune,
                                onClick = onClickMergedSettings,
                            ),
                        )
                    }
                    // SY <--
                    // KMK -->
                    if (isDebugBuildType) {
                        add(
                            AppBar.OverflowAction(
                                title = "Colors Palette",
                                onClick = onPaletteScreenClick,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(KMR.strings.action_clear_data),
                            icon = Icons.Outlined.DeleteOutline,
                            onClick = onClearManga,
                            destructive = true,
                        ),
                    )
                    // KMK <--
                }
                    .build(),
            )
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )
}
