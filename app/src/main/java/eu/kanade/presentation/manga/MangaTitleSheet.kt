package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.presentation.components.TitleSheet
import eu.kanade.presentation.components.TitleSheetBox
import eu.kanade.presentation.components.TitleSheetPlayMenuItem
import eu.kanade.presentation.components.TitleSheetState
import eu.kanade.presentation.components.TitleSheetTab
import eu.kanade.presentation.manga.components.TitlePagePreview
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowsePreviewScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource

/**
 * KMK (r124): what the phone sheet layout needs from the title page's screen: the ▦ Preview (a
 * [BrowsePreviewScreenModel] that reads only the saved chapters), the chapter to continue with, and the filters the
 * chips set.
 */
class MangaSheetHooks(
    val previewState: BrowsePreviewScreenModel.State,
    val previewSource: HttpSource?,
    val nextChapter: Chapter?,
    /** ▦ opened: start the preview on the next chapter to read, unless it's already showing this title */
    val onOpenPreview: () -> Unit,
    val onSelectPreviewChapter: (Chapter) -> Unit,
    val onRetryPreview: () -> Unit,
    /** The chapter, the page, and whether it's a video (it plays straight away) */
    val onOpenPage: (Chapter, Int, Boolean) -> Unit,
    val onUnreadFilter: (TriState) -> Unit,
    val onDownloadedFilter: (TriState) -> Unit,
)

/**
 * KMK (r124): the phone title page as a sheet: the cover, buttons and description under it ([infoItems]), the
 * chapters ([chapterItems], the same rows and swipes as the list) or ▦ Preview in the sheet, and the dock.
 */
@Composable
internal fun MangaTitleSheet(
    state: MangaScreenModel.State.Success,
    hooks: MangaSheetHooks,
    sheetState: TitleSheetState,
    topPadding: Dp,
    isAnySelected: Boolean,
    chapterListState: LazyListState,
    onRefresh: () -> Unit,
    onFilterClicked: () -> Unit,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onChapterClicked: (Chapter) -> Unit,
    onContinueReading: () -> Unit,
    infoItems: LazyListScope.() -> Unit,
    chapterItems: LazyListScope.() -> Unit,
) {
    LaunchedEffect(sheetState.tab) {
        if (sheetState.tab == TitleSheetTab.PREVIEW) hooks.onOpenPreview()
    }
    val sheetListState = rememberLazyListState()
    val isReading = remember(state.chapters) { state.chapters.fastAny { it.chapter.read } }
    val previewChapter = hooks.previewState.selectedChapter
    val playLabel = when (sheetState.tab) {
        TitleSheetTab.LIST -> hooks.nextChapter?.let {
            stringResource(if (isReading) MR.strings.action_resume else MR.strings.action_start) + " · " + it.name
        }
        TitleSheetTab.PREVIEW -> previewChapter?.let { stringResource(KMR.strings.title_sheet_read) + " · " + it.name }
    }
    TitleSheetBox(
        state = sheetState,
        topInset = topPadding,
        showDock = !isAnySelected,
        playLabel = playLabel,
        onPlay = {
            when (sheetState.tab) {
                TitleSheetTab.LIST -> onContinueReading()
                TitleSheetTab.PREVIEW -> previewChapter?.let { hooks.onOpenPage(it, 0, false) }
            }
        },
        playMenu = { dismiss ->
            TitleSheetPlayMenuItem(stringResource(KMR.strings.title_sheet_start_first)) {
                dismiss()
                state.chapters.maxByOrNull { it.chapter.sourceOrder }?.let { onChapterClicked(it.chapter) }
            }
            TitleSheetPlayMenuItem(stringResource(KMR.strings.title_sheet_pick)) {
                dismiss()
                sheetState.tab = TitleSheetTab.LIST
                sheetState.moveTo(TitleSheet.FULL)
            }
        },
        background = { bottomSpace ->
            PullRefresh(
                refreshing = state.isRefreshingData,
                enabled = !isAnySelected,
                onRefresh = onRefresh,
                indicatorPadding = PaddingValues(top = topPadding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    state = chapterListState,
                    contentPadding = PaddingValues(bottom = bottomSpace + 8.dp),
                ) {
                    infoItems()
                }
            }
        },
        sheetHeader = {
            if (sheetState.tab == TitleSheetTab.LIST) {
                ChapterSheetHeader(
                    state = state,
                    hooks = hooks,
                    onFilterClicked = onFilterClicked,
                    onDownloadActionClicked = onDownloadActionClicked,
                )
            }
        },
        sheetContent = { bottomSpace ->
            when (sheetState.tab) {
                TitleSheetTab.LIST -> VerticalFastScroller(listState = sheetListState) {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(),
                        state = sheetListState,
                        contentPadding = PaddingValues(bottom = bottomSpace),
                    ) {
                        chapterItems()
                    }
                }
                TitleSheetTab.PREVIEW -> TitlePagePreview(
                    state = hooks.previewState,
                    source = hooks.previewSource,
                    onSelectChapter = hooks.onSelectPreviewChapter,
                    onOpenPage = hooks.onOpenPage,
                    onRetry = hooks.onRetryPreview,
                    bottomSpace = bottomSpace,
                )
            }
        },
    )
}

@Composable
private fun ChapterSheetHeader(
    state: MangaScreenModel.State.Success,
    hooks: MangaSheetHooks,
    onFilterClicked: () -> Unit,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(KMR.strings.title_sheet_chapters, state.chapters.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onFilterClicked)
                .padding(vertical = 8.dp),
        )
        if (onDownloadActionClicked != null) {
            Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(MR.strings.manga_download))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    listOf(
                        DownloadAction.UNREAD_CHAPTERS to stringResource(MR.strings.download_unread),
                        DownloadAction.ALL_CHAPTERS to stringResource(KMR.strings.title_sheet_download_all),
                        DownloadAction.BOOKMARKED_CHAPTERS to stringResource(MR.strings.download_bookmarked),
                    ).forEach { (action, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                open = false
                                onDownloadActionClicked(action)
                            },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onFilterClicked) {
            Icon(
                Icons.Outlined.FilterList,
                contentDescription = stringResource(MR.strings.action_filter),
                tint = if (state.filterActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
    val unread = state.manga.unreadFilter
    val downloaded = state.manga.downloadedFilter
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = unread == TriState.DISABLED && downloaded == TriState.DISABLED,
            onClick = {
                hooks.onUnreadFilter(TriState.DISABLED)
                hooks.onDownloadedFilter(TriState.DISABLED)
            },
            label = { Text(stringResource(MR.strings.all)) },
        )
        FilterChip(
            selected = unread == TriState.ENABLED_IS,
            onClick = {
                hooks.onUnreadFilter(if (unread == TriState.ENABLED_IS) TriState.DISABLED else TriState.ENABLED_IS)
            },
            label = { Text(stringResource(MR.strings.action_filter_unread)) },
        )
        FilterChip(
            selected = downloaded == TriState.ENABLED_IS,
            onClick = {
                hooks.onDownloadedFilter(
                    if (downloaded == TriState.ENABLED_IS) TriState.DISABLED else TriState.ENABLED_IS,
                )
            },
            label = { Text(stringResource(MR.strings.label_downloaded)) },
        )
    }
}
