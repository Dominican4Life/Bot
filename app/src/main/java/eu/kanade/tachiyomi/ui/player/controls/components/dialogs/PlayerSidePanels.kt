package eu.kanade.tachiyomi.ui.player.controls.components.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.reader.sidePanelWidth
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.compat.Episode
import eu.kanade.tachiyomi.ui.player.compat.toDomainEpisode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/**
 * KMK: The player's episode list as a panel on the left over the video, the same width and look as the episode
 * panel when browsing a source: each episode with watched, bookmark and download buttons. Tapping the video beside
 * it closes it.
 */
@Composable
fun EpisodesSidePanel(
    viewModel: PlayerViewModel,
    episodeList: List<Episode>,
    currentEpisodeIndex: Int,
    onEpisodeClicked: (Long?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val downloads = remember { Injekt.get<AnimeDownloadManager>() }
    val queue by downloads.queue.collectAsState()
    val anime by viewModel.currentAnime.collectAsState()
    // What changed here, shown at once (the list itself updates when the player reloads it)
    val seenOverride = remember { mutableStateMapOf<Long, Boolean>() }
    val bookmarkOverride = remember { mutableStateMapOf<Long, Boolean>() }
    // Oldest first, like a season's running order
    val ordered = remember(episodeList) { episodeList.sortedBy { it.episode_number } }
    val current = episodeList.getOrNull(currentEpisodeIndex)
    val startIndex = remember(ordered, current) { ordered.indexOfFirst { it.id == current?.id }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val panelWidth = sidePanelWidth(maxWidth)
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(panelWidth)
                        // Taps inside the panel stay inside
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Episodes",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${episodeList.size} episodes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = onDismissRequest) {
                                Icon(Icons.Outlined.Close, contentDescription = null)
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(ordered, key = { index, it -> "ep-${it.id}-$index" }) { _, episode ->
                                val id = episode.id ?: return@itemsIndexed
                                val seen = seenOverride[id] ?: episode.seen
                                val bookmarked = bookmarkOverride[id] ?: episode.bookmark
                                val domain = remember(episode) { episode.toDomainEpisode() }
                                val state = remember(queue, anime, domain) {
                                    val a = anime
                                    if (a == null || domain == null) {
                                        Download.State.NOT_DOWNLOADED
                                    } else {
                                        downloads.stateOf(a, domain)
                                    }
                                }
                                EpisodeRow(
                                    episode = episode,
                                    current = id == current?.id,
                                    seen = seen,
                                    bookmarked = bookmarked,
                                    downloadState = state,
                                    onClick = { onEpisodeClicked(id) },
                                    onToggleSeen = {
                                        seenOverride[id] = !seen
                                        viewModel.setEpisodeSeen(id, !seen)
                                    },
                                    onToggleBookmark = {
                                        bookmarkOverride[id] = !bookmarked
                                        episode.bookmark = !bookmarked
                                        viewModel.bookmarkEpisode(id, !bookmarked)
                                    },
                                    onDownload = {
                                        val a = anime ?: return@EpisodeRow
                                        val d = domain ?: return@EpisodeRow
                                        when (state) {
                                            Download.State.NOT_DOWNLOADED, Download.State.ERROR ->
                                                downloads.enqueue(a, listOf(d), startNow = true)
                                            Download.State.DOWNLOADED -> downloads.delete(a, listOf(d))
                                            else -> Unit
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    current: Boolean,
    seen: Boolean,
    bookmarked: Boolean,
    downloadState: Download.State,
    onClick: () -> Unit,
    onToggleSeen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
) {
    val subtitle = remember(episode.date_upload, episode.scanlator, current) {
        listOfNotNull(
            "Playing now".takeIf { current },
            episode.date_upload.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) },
            episode.scanlator?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
    }
    val dim = if (seen && !current) 0.5f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!seen) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = null,
                        modifier = Modifier.height(8.dp).padding(end = 6.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = dim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleSeen, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (seen) Icons.Filled.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = if (seen) "Mark as unwatched" else "Mark as watched",
                tint = if (seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onToggleBookmark, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
                tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
            when (downloadState) {
                Download.State.QUEUE, Download.State.DOWNLOADING -> Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Download.State.DOWNLOADED -> Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "Downloaded (tap to delete)",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Download.State.ERROR -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = "Download failed (tap to retry)",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    Icons.Outlined.Download,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
