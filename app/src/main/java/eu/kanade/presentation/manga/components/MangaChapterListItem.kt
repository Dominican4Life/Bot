package eu.kanade.presentation.manga.components

import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import eu.kanade.presentation.components.GlassIcons
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.download.model.Download
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

@Composable
fun MangaChapterListItem(
    title: String,
    date: String?,
    readProgress: String?,
    scanlator: String?,
    // SY -->
    sourceName: String?,
    // SY <--
    read: Boolean,
    bookmark: Boolean,
    selected: Boolean,
    downloadIndicatorEnabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDownloadClick: ((ChapterDownloadAction) -> Unit)?,
    onChapterSwipe: (LibraryPreferences.ChapterSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    // KMK (r125): extra buttons before the download one (the reader's eye / bookmark)
    trailing: (@Composable () -> Unit)? = null,
) {
    // KMK -->
    // KMK (r132): with glass on, the liquid-glass swipe draws itself (the library's coloured blocks are hidden)
    val glassSwipe = remember { eu.kanade.presentation.components.TitleSheet.glass().get() }
    val swipeBackground = if (glassSwipe) Color.Transparent else MaterialTheme.colorScheme.primaryContainer
    val swipeStart = remember(chapterSwipeStartAction, read, bookmark, downloadStateProvider()) {
        // KMK <--
        getSwipeAction(
            action = chapterSwipeStartAction,
            read = read,
            bookmark = bookmark,
            downloadState = downloadStateProvider(),
            background = swipeBackground,
            onSwipe = { onChapterSwipe(chapterSwipeStartAction) },
            hideIcon = glassSwipe,
        )
    }
    // KMK -->
    val swipeEnd = remember(chapterSwipeEndAction, read, bookmark, downloadStateProvider()) {
        // KMK <--
        getSwipeAction(
            action = chapterSwipeEndAction,
            read = read,
            bookmark = bookmark,
            downloadState = downloadStateProvider(),
            background = swipeBackground,
            onSwipe = { onChapterSwipe(chapterSwipeEndAction) },
            hideIcon = glassSwipe,
        )
    }

    var rootX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // KMK (r133): the glass swipe triggers about a third of the way across, so the stretch can be seen
    var rootW by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var rowDx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned {
                rootX = it.positionInWindow().x
                rootW = it.size.width
            },
    ) {
    val glassThreshold = if (glassSwipe && rootW > 0) {
        maxOf(swipeActionThreshold, with(density) { (rootW / 3f).toDp() })
    } else {
        swipeActionThreshold
    }
    if (glassSwipe) {
        GlassSwipeLayer(
            dx = { rowDx },
            thresholdPx = with(density) { glassThreshold.toPx() },
            startIcon = swipeIconFor(chapterSwipeStartAction, read, bookmark, downloadStateProvider()),
            startLabel = swipeLabelFor(chapterSwipeStartAction, read, bookmark, downloadStateProvider()),
            endIcon = swipeIconFor(chapterSwipeEndAction, read, bookmark, downloadStateProvider()),
            endLabel = swipeLabelFor(chapterSwipeEndAction, read, bookmark, downloadStateProvider()),
        )
    }
    SwipeableActionsBox(
        startActions = listOfNotNull(swipeStart),
        endActions = listOfNotNull(swipeEnd),
        swipeThreshold = glassThreshold,
        backgroundUntilSwipeThreshold = if (glassSwipe) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .onGloballyPositioned { rowDx = it.positionInWindow().x - rootX }
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            // KMK (r134): the eye, bookmark and download buttons line up on the row's middle
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var textHeight by remember { mutableIntStateOf(0) }
                    if (!read) {
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = stringResource(MR.strings.unread),
                            modifier = Modifier
                                .height(8.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // KMK (r133): read chapters show the eye next to the bookmark, in the cover colour.
                    // KMK (r134): not when the row has its own eye / bookmark buttons (the reader's list)
                    if (read && trailing == null) {
                        Icon(
                            imageVector = GlassIcons.Read,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 2.dp)
                                .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        )
                    }
                    if (bookmark && trailing == null) {
                        Icon(
                            imageVector = GlassIcons.Bookmark,
                            contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                            modifier = Modifier
                                .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textHeight = it.size.height },
                        color = LocalContentColor.current.copy(alpha = if (read) DISABLED_ALPHA else 1f),
                    )
                }

                Row {
                    val subtitleStyle = MaterialTheme.typography.bodySmall
                        .merge(
                            color = LocalContentColor.current
                                .copy(alpha = if (read) DISABLED_ALPHA else SECONDARY_ALPHA),
                        )
                    ProvideTextStyle(value = subtitleStyle) {
                        if (date != null) {
                            Text(
                                text = date,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (readProgress != null ||
                                scanlator != null/* SY --> */ ||
                                sourceName != null/* SY <-- */
                            ) {
                                DotSeparatorText()
                            }
                        }
                        if (readProgress != null) {
                            Text(
                                text = readProgress,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                            )
                            if (scanlator != null/* SY --> */ || sourceName != null/* SY <-- */) DotSeparatorText()
                        }
                        // SY -->
                        if (sourceName != null) {
                            Text(
                                text = sourceName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (scanlator != null) DotSeparatorText()
                        }
                        // SY <--
                        if (scanlator != null) {
                            Text(
                                text = scanlator,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            trailing?.invoke()
            ChapterDownloadIndicator(
                enabled = downloadIndicatorEnabled,
                modifier = Modifier.padding(start = 4.dp),
                downloadStateProvider = downloadStateProvider,
                downloadProgressProvider = downloadProgressProvider,
                onClick = { onDownloadClick?.invoke(it) },
            )
        }
    }
    }
}

internal fun getSwipeAction(
    action: LibraryPreferences.ChapterSwipeAction,
    read: Boolean,
    bookmark: Boolean,
    downloadState: Download.State,
    background: Color,
    hideIcon: Boolean = false,
    onSwipe: () -> Unit,
): me.saket.swipe.SwipeAction? {
    return when (action) {
        LibraryPreferences.ChapterSwipeAction.ToggleRead -> swipeAction(
            icon = if (!read) GlassIcons.Read else GlassIcons.Unread,
            background = background,
            isUndo = read,
            onSwipe = onSwipe,
            hideIcon = hideIcon,
        )
        LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> swipeAction(
            icon = if (!bookmark) GlassIcons.Bookmark else GlassIcons.BookmarkRemove,
            background = background,
            isUndo = bookmark,
            onSwipe = onSwipe,
            hideIcon = hideIcon,
        )
        LibraryPreferences.ChapterSwipeAction.Download -> swipeAction(
            icon = when (downloadState) {
                Download.State.NOT_DOWNLOADED, Download.State.ERROR -> GlassIcons.Download
                Download.State.QUEUE, Download.State.DOWNLOADING -> Icons.Outlined.FileDownloadOff
                Download.State.DOWNLOADED -> Icons.Outlined.Delete
            },
            background = background,
            onSwipe = onSwipe,
            hideIcon = hideIcon,
        )
        LibraryPreferences.ChapterSwipeAction.Disabled -> null
    }
}

internal fun swipeAction(
    onSwipe: () -> Unit,
    icon: ImageVector,
    background: Color,
    isUndo: Boolean = false,
    hideIcon: Boolean = false,
): me.saket.swipe.SwipeAction {
    return me.saket.swipe.SwipeAction(
        icon = {
            Icon(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .size(IndicatorSize),
                imageVector = icon,
                tint = if (hideIcon) Color.Transparent else contentColorFor(background),
                contentDescription = null,
            )
        },
        background = background,
        onSwipe = onSwipe,
        isUndo = isUndo,
    )
}

internal val swipeActionThreshold = 56.dp

/** KMK (r132): the icon of a swipe action, for the glass swipe's pill */
internal fun swipeIconFor(
    action: LibraryPreferences.ChapterSwipeAction,
    read: Boolean,
    bookmark: Boolean,
    downloadState: Download.State,
): ImageVector? = when (action) {
    LibraryPreferences.ChapterSwipeAction.ToggleRead -> if (!read) GlassIcons.Read else GlassIcons.Unread
    LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> if (!bookmark) GlassIcons.Bookmark else GlassIcons.BookmarkRemove
    LibraryPreferences.ChapterSwipeAction.Download -> when (downloadState) {
        Download.State.NOT_DOWNLOADED, Download.State.ERROR -> GlassIcons.Download
        Download.State.QUEUE, Download.State.DOWNLOADING -> Icons.Outlined.FileDownloadOff
        Download.State.DOWNLOADED -> Icons.Outlined.Delete
    }
    LibraryPreferences.ChapterSwipeAction.Disabled -> null
}

/** KMK (r132): the name of a swipe action, for the glass swipe's pill */
@Composable
internal fun swipeLabelFor(
    action: LibraryPreferences.ChapterSwipeAction,
    read: Boolean,
    bookmark: Boolean,
    downloadState: Download.State,
): String? = when (action) {
    LibraryPreferences.ChapterSwipeAction.ToggleRead ->
        stringResource(if (!read) MR.strings.action_mark_as_read else MR.strings.action_mark_as_unread)
    LibraryPreferences.ChapterSwipeAction.ToggleBookmark ->
        stringResource(if (!bookmark) MR.strings.action_bookmark else MR.strings.action_remove_bookmark)
    LibraryPreferences.ChapterSwipeAction.Download -> when (downloadState) {
        Download.State.NOT_DOWNLOADED, Download.State.ERROR -> stringResource(MR.strings.manga_download)
        Download.State.QUEUE, Download.State.DOWNLOADING -> stringResource(MR.strings.action_cancel)
        Download.State.DOWNLOADED -> stringResource(MR.strings.action_delete)
    }
    LibraryPreferences.ChapterSwipeAction.Disabled -> null
}
