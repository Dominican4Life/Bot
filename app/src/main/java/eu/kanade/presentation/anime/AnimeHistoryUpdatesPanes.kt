package eu.kanade.presentation.anime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.AnimeHistoryItem
import eu.kanade.tachiyomi.data.anime.AnimeUpdateItem
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** KMK: Plays an episode in the built-in player, or opens the anime when the outside player is chosen. */
@Composable
internal fun rememberPlayAnimeEpisode(): (animeId: Long, episodeId: Long) -> Unit {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    return { animeId, episodeId ->
        if (playerPreferences.alwaysUseExternalPlayer().get()) {
            navigator.push(AnimeScreen(animeId))
        } else {
            context.startActivity(PlayerActivity.newIntent(context, animeId, episodeId))
        }
    }
}

/** KMK: One anime of the History tab */
@Composable
internal fun AnimeHistoryRow(
    item: AnimeHistoryItem,
    sourceManager: AnimeSourceManager,
    onClick: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AnimeCoverImage(
            url = item.thumbnailUrl,
            source = sourceManager.get(item.source),
            modifier = Modifier
                .width(48.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = item.episodeName + " • " + formatWhen(item.lastSeen, dateFormat, timeFormat),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
            if (item.totalSeconds > 0 && item.lastSecondSeen > 0) {
                LinearProgressIndicator(
                    progress = { (item.lastSecondSeen.toFloat() / item.totalSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
        }
        IconButton(onClick = onResume) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(KMR.strings.anime_resume_episode))
        }
    }
}

/** KMK: One new episode of the Updates tab; long-press selects it, and while selecting a tap does too */
@Composable
internal fun AnimeUpdateRow(
    item: AnimeUpdateItem,
    sourceManager: AnimeSourceManager,
    onClick: () -> Unit,
    onClickCover: () -> Unit,
    onToggleSeen: () -> Unit,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onSelect: (() -> Unit)? = null,
    downloaded: Boolean = false,
    queued: AnimeDownloadManager.Item? = null,
    onDownload: ((ChapterDownloadAction) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(selected)
            .combinedClickable(
                onClick = { if (selectionMode && onSelect != null) onSelect() else onClick() },
                onLongClick = onSelect?.let { select ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        select()
                    }
                },
            )
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AnimeCoverImage(
            url = item.thumbnailUrl,
            source = sourceManager.get(item.source),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = { if (selectionMode && onSelect != null) onSelect() else onClickCover() }),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .alpha(if (item.seen) 0.45f else 1f),
        ) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.seen) {
                    val dotColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(6.dp)) { drawCircle(color = dotColor) }
                    Spacer(Modifier.width(6.dp))
                }
                if (item.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(KMR.strings.anime_library_filter_bookmarked),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = item.episodeName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleSeen, enabled = !selectionMode) {
            Icon(
                imageVector = if (item.seen) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(
                    if (item.seen) KMR.strings.anime_action_mark_unseen else KMR.strings.anime_action_mark_seen,
                ),
            )
        }
        if (onDownload != null) {
            AnimeEpisodeDownloadIndicator(
                enabled = !selectionMode,
                downloaded = downloaded,
                queued = queued,
                onClick = onDownload,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** KMK: The manga download button for an episode, fed by [AnimeDownloadManager]'s queue */
@Composable
internal fun AnimeEpisodeDownloadIndicator(
    enabled: Boolean,
    downloaded: Boolean,
    queued: AnimeDownloadManager.Item?,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queued != null) {
        val state by queued.state.collectAsState()
        val progress by queued.progress.collectAsState()
        ChapterDownloadIndicator(
            enabled = enabled,
            downloadStateProvider = { if (downloaded && state == Download.State.NOT_DOWNLOADED) Download.State.DOWNLOADED else state },
            downloadProgressProvider = { progress },
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        ChapterDownloadIndicator(
            enabled = enabled,
            downloadStateProvider = { if (downloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED },
            downloadProgressProvider = { 0 },
            onClick = onClick,
            modifier = modifier,
        )
    }
}

/** KMK: The actions for the anime episodes picked in the Updates tab, like the manga ones */
@Composable
internal fun AnimeUpdatesActionBar(
    selected: List<AnimeUpdateItem>,
    downloaded: Set<Long>,
    onMarkSeen: (Boolean) -> Unit,
    onBookmark: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = selected.isNotEmpty(),
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (selected.any { !it.bookmark }) {
                    ActionBarButton(Icons.Outlined.BookmarkAdd, stringResource(KMR.strings.anime_action_bookmark_episode)) {
                        onBookmark(true)
                    }
                } else {
                    ActionBarButton(Icons.Outlined.BookmarkRemove, stringResource(KMR.strings.anime_action_remove_bookmark_episode)) {
                        onBookmark(false)
                    }
                }
                if (selected.any { !it.seen }) {
                    ActionBarButton(Icons.Outlined.DoneAll, stringResource(KMR.strings.anime_action_mark_seen)) {
                        onMarkSeen(true)
                    }
                }
                if (selected.any { it.seen || it.lastSecondSeen > 0L }) {
                    ActionBarButton(Icons.Outlined.RemoveDone, stringResource(KMR.strings.anime_action_mark_unseen)) {
                        onMarkSeen(false)
                    }
                }
                if (selected.any { it.episodeId !in downloaded }) {
                    ActionBarButton(Icons.Outlined.Download, stringResource(MR.strings.action_download), onDownload)
                }
                if (selected.any { it.episodeId in downloaded }) {
                    ActionBarButton(Icons.Outlined.Delete, stringResource(MR.strings.action_delete), onDelete)
                }
            }
        }
    }
}

@Composable
private fun RowScope.ActionBarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun AnimeConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
        text = { Text(text) },
    )
}

private fun formatWhen(time: Long, dateFormat: DateFormat, timeFormat: DateFormat): String {
    val date = Date(time)
    return if (startOfDay(time) == startOfDay(System.currentTimeMillis())) {
        timeFormat.format(date)
    } else {
        dateFormat.format(date)
    }
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
