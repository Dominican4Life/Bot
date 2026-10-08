package eu.kanade.presentation.anime

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BrowsePreviewInfoHeader
import eu.kanade.presentation.reader.SlidingSidePanel
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreenModel
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import java.text.DateFormat
import java.util.Date

/**
 * KMK: Left panel on the anime browse screen, like the manga one: the anime's details on top, its episodes below.
 * Tap an episode to play it.
 */
@Composable
fun AnimeEpisodePanel(
    preview: AnimeSourceScreenModel.Preview,
    sourceName: String,
    onClose: () -> Unit,
    onAddToLibrary: (Anime) -> Unit,
    onWebView: (Anime) -> Unit,
    onOpenDetails: (Anime) -> Unit,
    onSearch: (String) -> Unit,
    onToggleSort: () -> Unit,
    onPlay: (Episode) -> Unit,
    onToggleSeen: (Episode) -> Unit,
    onToggleBookmark: (Episode) -> Unit,
    selectedEpisodeId: Long?,
    onSelectEpisode: (Episode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SlidingSidePanel(
        visible = preview.panelOpen,
        fromStart = true,
        title = stringResource(KMR.strings.anime_episodes_count, preview.episodes.size),
        onClose = onClose,
        modifier = modifier,
        applyTopInset = false,
        showClose = false,
        squareTop = true,
        showHeader = false,
    ) {
        val anime = preview.anime ?: return@SlidingSidePanel
        key(anime.id) {
            val listState = rememberLazyListState()
            val displayManga = remember(anime) { anime.asDisplayManga() }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "info") {
                    BrowsePreviewInfoHeader(
                        manga = displayManga,
                        sourceName = sourceName,
                        onAddToLibrary = { onAddToLibrary(anime) },
                        onWebView = { onWebView(anime) },
                        onOpenDetails = { onOpenDetails(anime) },
                        onSearch = { query, _ -> onSearch(query) },
                    )
                }
                item(key = "episodes-header") {
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    ) {
                        Text(
                            text = stringResource(KMR.strings.anime_episodes_count, preview.episodes.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onToggleSort) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(MR.strings.action_sort),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                when {
                    preview.episodes.isEmpty() && preview.loading -> item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    preview.episodes.isEmpty() -> item(key = "empty") {
                        Text(
                            text = preview.error ?: stringResource(KMR.strings.anime_no_episodes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    else -> items(preview.episodes, key = { it.id }) { episode ->
                        AnimePanelEpisodeRow(
                            episode = episode,
                            selected = episode.id == selectedEpisodeId,
                            onClick = { onSelectEpisode(episode) },
                            onLongClick = { onPlay(episode) },
                            onToggleSeen = { onToggleSeen(episode) },
                            onToggleBookmark = { onToggleBookmark(episode) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * KMK: Right panel on the anime browse screen, like the manga pages panel: the preview player with its
 * video settings. Plays the episode tapped in the left panel (the first one until then).
 */
@Composable
fun AnimePlayerPanel(
    preview: AnimeSourceScreenModel.Preview,
    source: AnimeSource?,
    episode: Episode?,
    playToken: Int,
    onClose: () -> Unit,
    onOpenPlayer: (Episode, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SlidingSidePanel(
        visible = preview.playerPanelOpen,
        fromStart = false,
        title = episode?.name.orEmpty(),
        onClose = onClose,
        modifier = modifier,
        applyTopInset = false,
        showClose = false,
        squareTop = true,
        showHeader = false,
    ) {
        val anime = preview.anime ?: return@SlidingSidePanel
        key(anime.id) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (episode != null) {
                    Text(
                        text = episode.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                    )
                }
                AnimePreviewSection(
                    anime = anime,
                    source = source,
                    episode = episode,
                    playToken = playToken,
                    onOpenPlayer = onOpenPlayer,
                )
            }
        }
    }
}

@Composable
private fun AnimePanelEpisodeRow(
    episode: Episode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSeen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val subtitle = remember(episode.dateUpload, episode.scanlator) {
        listOfNotNull(
            episode.dateUpload.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) },
            episode.scanlator?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!episode.seen) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 6.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = if (episode.seen) DISABLED_ALPHA else 1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(
                        alpha = if (episode.seen) DISABLED_ALPHA else SECONDARY_ALPHA,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleSeen, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (episode.seen) Icons.Filled.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(
                    if (episode.seen) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                ),
                tint = if (episode.seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onToggleBookmark, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (episode.bookmark) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = stringResource(
                    if (episode.bookmark) MR.strings.action_remove_bookmark else MR.strings.action_bookmark,
                ),
                tint = if (episode.bookmark) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
