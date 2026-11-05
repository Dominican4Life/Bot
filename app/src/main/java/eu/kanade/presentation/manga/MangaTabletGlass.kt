package eu.kanade.presentation.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.RelatedMangaTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.GlassIcons
import eu.kanade.presentation.manga.components.DownloadMenuCounts
import eu.kanade.presentation.manga.components.GlassDownloadMenuItems
import eu.kanade.presentation.manga.components.RelatedMangasRow
import eu.kanade.presentation.manga.components.TitlePagePreview
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.i18n.stringResource

/** KMK (r132): the frosted panels of the tablet title page (chapters, Preview) */
internal val GlassPanelShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

internal fun Modifier.frostedPanel(shape: androidx.compose.ui.graphics.Shape = GlassPanelShape): Modifier = this
    .clip(shape)
    .background(Color(0xFF181A22).copy(alpha = 0.62f))
    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)

/**
 * KMK (r132): the right side of the glass tablet title page: Suggestions across the top (toggle), then the chapters in
 * their own frosted panel (with Download and Filter in its header) and the Preview card beside them (toggle).
 */
@Composable
internal fun GlassTabletEnd(
    state: MangaScreenModel.State.Success,
    chapters: List<ChapterList.Item>,
    listItem: List<ChapterList>,
    isAnySelected: Boolean,
    hooks: MangaSheetHooks?,
    showPreview: Boolean,
    showSuggestions: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    chapterListState: LazyListState,
    getMangaState: @Composable ((Manga) -> State<Manga>),
    onRelatedMangasScreenClick: () -> Unit,
    onRelatedMangaClick: (Manga) -> Unit,
    onRelatedMangaLongClick: (Manga) -> Unit,
    onFilterButtonClicked: () -> Unit,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    chapterItems: LazyListScope.() -> Unit,
) {
    if (showPreview && hooks != null) {
        LaunchedEffect(Unit) { hooks.onOpenPreview() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding),
    ) {
        if (showSuggestions) {
            RelatedMangaTitle(
                title = stringResource(KMR.strings.pref_source_related_mangas),
                subtitle = null,
                onClick = onRelatedMangasScreenClick,
                onLongClick = null,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            RelatedMangasRow(
                relatedMangas = state.relatedMangasSorted,
                getMangaState = getMangaState,
                onMangaClick = onRelatedMangaClick,
                onMangaLongClick = onRelatedMangaLongClick,
            )
            Spacer(Modifier.padding(top = 8.dp))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .frostedPanel(),
            ) {
                GlassChaptersHeader(
                    state = state,
                    count = chapters.size,
                    enabled = !isAnySelected,
                    onFilterButtonClicked = onFilterButtonClicked,
                    onDownloadActionClicked = onDownloadActionClicked,
                )
                VerticalFastScroller(listState = chapterListState) {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(),
                        state = chapterListState,
                        contentPadding = PaddingValues(bottom = bottomPadding + 72.dp),
                    ) {
                        chapterItems()
                    }
                }
            }
            if (showPreview && hooks != null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .frostedPanel(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(GlassIcons.Preview, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(KMR.strings.title_sheet_preview),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TitlePagePreview(
                            state = hooks.previewState,
                            source = hooks.previewSource,
                            onSelectChapter = hooks.onSelectPreviewChapter,
                            onOpenPage = hooks.onOpenPage,
                            onRetry = hooks.onRetryPreview,
                            bottomSpace = bottomPadding + 72.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassChaptersHeader(
    state: MangaScreenModel.State.Success,
    count: Int,
    enabled: Boolean,
    onFilterButtonClicked: () -> Unit,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GlassIcons.Chapters, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(KMR.strings.title_sheet_chapters, count),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (onDownloadActionClicked != null) {
            Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }, enabled = enabled) {
                    Icon(GlassIcons.Download, contentDescription = stringResource(MR.strings.manga_download))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    GlassDownloadMenuItems(
                        counts = remember(state.chapters) { DownloadMenuCounts.of(state.chapters) },
                        onDismiss = { open = false },
                        onAction = onDownloadActionClicked,
                    )
                }
            }
        }
        IconButton(onClick = onFilterButtonClicked, enabled = enabled) {
            Icon(
                GlassIcons.Filter,
                contentDescription = stringResource(MR.strings.action_filter),
                tint = if (state.filterActive) Color(0xFFF2C94C) else LocalContentColor.current,
            )
        }
    }
}
