package eu.kanade.presentation.anime

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BrowsePreviewInfoHeader
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.reader.SlidingSidePanel
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreenModel
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.launch
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
            // KMK (r123): episode thumbnails / summaries here too, the same per-title switches as the title page's
            // Display tab (episodeFlags), with "Just this title / All titles from <source>"
            var showThumbs by remember { mutableStateOf(anime.showPreviews) }
            var showSummaries by remember { mutableStateOf(anime.showSummaries) }
            var askScope by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
            // KMK (r124): episode pictures / summaries from AniList, like the title page, when this source has
            // AniList switched on; the source's own otherwise. No cover stand-in for missing pictures.
            val episodeInfo by produceState<eu.kanade.tachiyomi.ui.anime.anilist.AniListEpisodeInfo?>(
                null,
                anime.id,
                showThumbs || showSummaries,
                preview.episodes.size,
            ) {
                value = if ((showThumbs || showSummaries) && preview.episodes.isNotEmpty() &&
                    !eu.kanade.tachiyomi.data.anime.SourceInfoOnly.isOn(anime.source)
                ) {
                    runCatching {
                        eu.kanade.tachiyomi.ui.anime.anilist.AniListEpisodeMatch.load(
                            anime,
                            preview.episodes.map { it.episodeNumber },
                        )
                    }.getOrNull()
                } else {
                    null
                }
            }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val applyDisplay: (Boolean, Boolean, Boolean) -> Unit = { previews, show, wholeSource ->
                if (previews) showThumbs = show else showSummaries = show
                val bit = if (previews) Anime.HIDE_PREVIEWS else Anime.HIDE_SUMMARIES
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val repo = Injekt.get<eu.kanade.tachiyomi.data.anime.AnimeRepository>()
                    var flags = anime.episodeFlags
                    flags = if (showThumbs) flags and Anime.HIDE_PREVIEWS.inv() else flags or Anime.HIDE_PREVIEWS
                    flags = if (showSummaries) flags and Anime.HIDE_SUMMARIES.inv() else flags or Anime.HIDE_SUMMARIES
                    repo.setEpisodeFlags(anime, flags)
                    if (wholeSource) {
                        eu.kanade.tachiyomi.data.anime.SourceInfoOnly.setSourceDefaults(
                            anime.source,
                            previews = show.takeIf { previews },
                            summaries = show.takeIf { !previews },
                        )
                        repo.setEpisodeFlagBitsForSource(anime.source, bit, hide = !show)
                    }
                }
            }
            askScope?.let { (previews, show) ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { askScope = null },
                    title = { Text(stringResource(KMR.strings.anime_display_scope_title)) },
                    text = {
                        Column {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    askScope = null
                                    applyDisplay(previews, show, false)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(KMR.strings.anime_display_scope_this)) }
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    askScope = null
                                    applyDisplay(previews, show, true)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (sourceName.isBlank()) {
                                        stringResource(KMR.strings.anime_display_scope_source_fallback)
                                    } else {
                                        stringResource(KMR.strings.anime_display_scope_source, sourceName)
                                    },
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { askScope = null }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            // KMK (r88): download buttons like the manga preview's, fed by AnimeDownloadManager's queue and files
            val downloadManager = remember { Injekt.get<AnimeDownloadManager>() }
            val downloadQueue by downloadManager.queue.collectAsState()
            val downloadChanges by downloadManager.changes.collectAsState()
            val queuedById = remember(downloadQueue) { downloadQueue.associateBy { it.episode.id } }
            LaunchedEffect(Unit) { downloadManager.refresh(anime) }
            val downloadedIds by produceState(emptySet<Long>(), preview.episodes, downloadChanges) {
                val episodes = preview.episodes
                value = withIOContext {
                    episodes.filter { downloadManager.isDownloaded(anime, it) }.mapTo(HashSet()) { it.id }
                }
            }
            val onDownloadMenu: (DownloadAction) -> Unit = { action ->
                // Watching order (oldest first), without the ones already downloaded or waiting in line
                val open = preview.episodes
                    .sortedByDescending { it.sourceOrder }
                    .filter { ep ->
                        ep.id !in downloadedIds &&
                            queuedById[ep.id]?.state?.value.let { it == null || it == Download.State.ERROR }
                    }
                val unseen = open.filter { !it.seen }
                val picked = when (action) {
                    DownloadAction.NEXT_1_CHAPTER -> unseen.take(1)
                    DownloadAction.NEXT_5_CHAPTERS -> unseen.take(5)
                    DownloadAction.NEXT_10_CHAPTERS -> unseen.take(10)
                    DownloadAction.NEXT_25_CHAPTERS -> unseen.take(25)
                    DownloadAction.UNREAD_CHAPTERS -> unseen
                    DownloadAction.BOOKMARKED_CHAPTERS -> open.filter { it.bookmark }
                    DownloadAction.ALL_CHAPTERS -> open
                }
                if (picked.isNotEmpty()) downloadManager.enqueue(anime, picked)
            }
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
                        // KMK (r123): thumbnails / summaries for this title's episodes
                        var showDisplayMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showDisplayMenu = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.ViewAgenda,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            DropdownMenu(expanded = showDisplayMenu, onDismissRequest = { showDisplayMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(KMR.strings.anime_show_episode_thumbnail)) },
                                    trailingIcon = {
                                        androidx.compose.material3.Checkbox(checked = showThumbs, onCheckedChange = null)
                                    },
                                    onClick = {
                                        showDisplayMenu = false
                                        askScope = true to !showThumbs
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(KMR.strings.anime_show_episode_summary)) },
                                    trailingIcon = {
                                        androidx.compose.material3.Checkbox(checked = showSummaries, onCheckedChange = null)
                                    },
                                    onClick = {
                                        showDisplayMenu = false
                                        askScope = false to !showSummaries
                                    },
                                )
                            }
                        }
                        // KMK (r88): the download menu, like the manga preview's
                        var showDownloadMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showDownloadMenu = true }, enabled = preview.episodes.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = stringResource(MR.strings.manga_download),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            DropdownMenu(
                                expanded = showDownloadMenu,
                                onDismissRequest = { showDownloadMenu = false },
                            ) {
                                // KMK (r123): only Unseen / All episodes / Bookmarked here (the owner's choice), like the
                                // manga preview panel
                                listOf(
                                    DownloadAction.UNREAD_CHAPTERS to stringResource(KMR.strings.anime_filter_unseen),
                                    DownloadAction.ALL_CHAPTERS to
                                        stringResource(KMR.strings.anime_download_all_episodes),
                                    DownloadAction.BOOKMARKED_CHAPTERS to
                                        stringResource(MR.strings.download_bookmarked),
                                ).forEach { (action, label) ->
                                    DropdownMenuItem(
                                        text = { Text(text = label) },
                                        onClick = {
                                            showDownloadMenu = false
                                            onDownloadMenu(action)
                                        },
                                    )
                                }
                            }
                        }
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
                            showThumb = showThumbs,
                            showSummary = showSummaries,
                            aniList = episodeInfo?.of(episode.episodeNumber),
                            inLibrary = anime.favorite,
                            downloaded = episode.id in downloadedIds,
                            queued = queuedById[episode.id],
                            onDownload = { action ->
                                when (action) {
                                    ChapterDownloadAction.START -> downloadManager.enqueue(anime, listOf(episode))
                                    ChapterDownloadAction.START_NOW ->
                                        downloadManager.enqueue(anime, listOf(episode), startNow = true)
                                    ChapterDownloadAction.CANCEL -> downloadManager.cancel(listOf(episode.id))
                                    ChapterDownloadAction.DELETE -> downloadManager.delete(anime, listOf(episode))
                                }
                            },
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
    /** KMK (r123): the episode's picture on the left / its summary under the title (the title's Display switches) */
    showThumb: Boolean,
    showSummary: Boolean,
    /** KMK (r124): this episode's AniList entry (picture / summary) when the source has AniList on */
    aniList: eu.kanade.tachiyomi.ui.anime.anilist.AniListEpisode?,
    inLibrary: Boolean,
    downloaded: Boolean,
    queued: AnimeDownloadManager.Item?,
    onDownload: (ChapterDownloadAction) -> Unit,
) {
    val subtitle = remember(episode.dateUpload, episode.scanlator) {
        listOfNotNull(
            episode.dateUpload.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) },
            episode.scanlator?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
    }
    val summaryText = if (showSummary) {
        aniList?.summary?.takeIf { it.isNotBlank() } ?: episode.summary?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    val rich = showThumb || summaryText != null
    // KMK (r123): hide / bookmark / download; with a picture or summary shown they sit on the date line, right-aligned
    // (the title and summary keep the full width beside the picture), otherwise at the row's end as before
    val icons: @Composable () -> Unit = {
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
        // KMK (r88): download / queued / progress / downloaded, as on the manga preview's chapter rows
        AnimeEpisodeDownloadIndicator(
            enabled = true,
            downloaded = downloaded,
            queued = queued,
            onClick = onDownload,
            modifier = Modifier.size(36.dp),
        )
    }
    Row(
        verticalAlignment = if (rich) Alignment.Top else Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (showThumb) {
            // KMK (r124): the same picture the title page shows (AniList, else the source's preview through the
            // app's preview loader); none -> an empty tile, never the cover repeated on every row
            val image: Any? = aniList?.image ?: episode.previewUrl?.takeIf { it.isNotBlank() }?.let { url ->
                eu.kanade.tachiyomi.data.coil.EpisodePreview(
                    animeId = episode.animeId,
                    key = eu.kanade.tachiyomi.data.coil.episodePreviewKey(episode.episodeNumber, episode.url),
                    url = url,
                    inLibrary = inLibrary,
                )
            }
            coil3.compose.AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = if (episode.seen) 0.5f else 1f,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(width = 112.dp, height = 63.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
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
            if (summaryText != null) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(
                        alpha = if (episode.seen) DISABLED_ALPHA else SECONDARY_ALPHA,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (rich) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(
                            alpha = if (episode.seen) DISABLED_ALPHA else SECONDARY_ALPHA,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    icons()
                }
            } else if (subtitle.isNotEmpty()) {
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
        if (!rich) icons()
    }
}
