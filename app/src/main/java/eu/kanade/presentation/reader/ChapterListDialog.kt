package eu.kanade.presentation.reader

import eu.kanade.presentation.components.GlassIcons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.lang.toRelativeString
import exh.metadata.MetadataUtil
import exh.source.isEhBasedManga
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ChapterListDialog(
    onDismissRequest: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
    chapters: ImmutableList<ReaderChapterItem>,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
    dateRelativeTime: Boolean,
    // KMK -->
    onDownloadAction: ((Chapter, ChapterDownloadAction) -> Unit)? = null,
    // KMK <--
) {
    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        // KMK -->
        ChapterListContent(
            screenModel = screenModel,
            chapters = chapters,
            onClickChapter = onClickChapter,
            onBookmark = onBookmark,
            dateRelativeTime = dateRelativeTime,
            onDownloadAction = onDownloadAction,
            modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
        )
        // KMK <--
    }
}

// KMK -->
/**
 * The scrollable list of chapters, shared by [ChapterListDialog] and the reader's side sheet.
 */
@Composable
fun ChapterListContent(
    screenModel: ReaderSettingsScreenModel,
    chapters: ImmutableList<ReaderChapterItem>,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter) -> Unit,
    dateRelativeTime: Boolean,
    onDownloadAction: ((Chapter, ChapterDownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
    // KMK (r125): the eye / bookmark buttons on every row, swipe right = seen, swipe left = bookmark
    onToggleRead: ((Chapter) -> Unit)? = null,
) {
    val manga by screenModel.mangaFlow.collectAsState()
    val context = LocalContext.current
    val state = rememberLazyListState(chapters.indexOfFirst { it.isCurrent }.coerceAtLeast(0))
    val downloadManager: DownloadManager = remember { Injekt.get() }
    val downloadQueueState by downloadManager.queueState.collectAsState()

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        items(
            items = chapters,
            key = { "chapter-list-${it.chapter.id}" },
        ) { chapterItem ->
            val activeDownload = downloadQueueState.find { it.chapter.id == chapterItem.chapter.id }
            val progress = activeDownload?.let {
                downloadManager.progressFlow()
                    .filter { it.chapter.id == chapterItem.chapter.id }
                    .map { it.progress }
                    .collectAsState(0).value
            } ?: 0
            val downloaded = if (chapterItem.manga.isLocal()) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapterItem.chapter.name,
                    chapterItem.chapter.scanlator,
                    chapterItem.chapter.url,
                    chapterItem.manga.ogTitle,
                    chapterItem.manga.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }
            MangaChapterListItem(
                title = chapterItem.chapter.name,
                date = chapterItem.chapter.dateUpload
                    .takeIf { it > 0L }
                    ?.let {
                        // SY -->
                        if (manga?.isEhBasedManga() == true) {
                            MetadataUtil.EX_DATE_FORMAT
                                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                        } else {
                            LocalDate.ofInstant(
                                Instant.ofEpochMilli(it),
                                ZoneId.systemDefault(),
                            ).toRelativeString(context, dateRelativeTime, chapterItem.dateFormat)
                        }
                        // SY <--
                    },
                readProgress = null,
                scanlator = chapterItem.chapter.scanlator,
                sourceName = null,
                read = chapterItem.chapter.read,
                bookmark = chapterItem.chapter.bookmark,
                selected = false,
                // KMK -->
                downloadIndicatorEnabled = onDownloadAction != null,
                // KMK <--
                downloadStateProvider = { downloadState },
                downloadProgressProvider = { progress },
                chapterSwipeStartAction = if (onToggleRead != null) {
                    LibraryPreferences.ChapterSwipeAction.ToggleRead
                } else {
                    LibraryPreferences.ChapterSwipeAction.ToggleBookmark
                },
                chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.ToggleBookmark,
                onLongClick = { /*TODO*/ },
                onClick = { onClickChapter(chapterItem.chapter) },
                // KMK -->
                onDownloadClick = if (onDownloadAction != null) {
                    { action -> onDownloadAction(chapterItem.chapter, action) }
                } else {
                    null
                },
                // KMK <--
                onChapterSwipe = { action ->
                    if (action == LibraryPreferences.ChapterSwipeAction.ToggleRead && onToggleRead != null) {
                        onToggleRead(chapterItem.chapter)
                    } else {
                        onBookmark(chapterItem.chapter)
                    }
                },
                trailing = if (onToggleRead != null) {
                    {
                        IconButton(onClick = { onToggleRead(chapterItem.chapter) }) {
                            Icon(
                                // KMK (r133): the glass eye, lit in the cover colour when read
                                imageVector = if (chapterItem.chapter.read) GlassIcons.Read else GlassIcons.ReadOff,
                                contentDescription = stringResource(
                                    if (chapterItem.chapter.read) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                                ),
                                tint = if (chapterItem.chapter.read) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { onBookmark(chapterItem.chapter) }) {
                            Icon(
                                imageVector = if (chapterItem.chapter.bookmark) {
                                    GlassIcons.Bookmark
                                } else {
                                    GlassIcons.BookmarkOff
                                },
                                contentDescription = stringResource(MR.strings.action_bookmark),
                                tint = if (chapterItem.chapter.bookmark) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}
// KMK <--
