package eu.kanade.tachiyomi.ui.anime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.AnimeGridItem
import eu.kanade.presentation.anime.asDisplayManga
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.theme.CoverSeedTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.lang.toRelativeString
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.shouldExpandFAB
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * KMK: Anime details with its episode list. Built from the same pieces as the manga details page
 * (cover backdrop, action buttons, description and tags, chapter-style rows, Start button, cover colors).
 */
class AnimeScreen(private val animeId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { AnimeScreenModel(animeId) }
        val state by screenModel.state.collectAsState()
        val anime = state.anime
        val playerPreferences = remember { Injekt.get<PlayerPreferences>() }

        if (anime == null) {
            Scaffold { LoadingScreen(Modifier.padding(it)) }
            return
        }

        val playEpisode: (Episode) -> Unit = { episode ->
            // Built-in player unless the outside player is chosen in settings
            if (playerPreferences.alwaysUseExternalPlayer().get()) {
                screenModel.loadVideos(episode)
            } else {
                context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id))
            }
        }
        val openWebView: (() -> Unit)? = (screenModel.source as? AnimeHttpSource)?.let { source ->
            {
                val url = runCatching { source.getAnimeUrl(anime.toSAnime()) }
                    .getOrElse { source.baseUrl + anime.url }
                navigator.push(WebViewScreen(url, anime.title))
            }
        }
        val searchSource: (String) -> Unit = { query -> navigator.push(AnimeSourceScreen(anime.source, query)) }

        CoverSeedTheme(seedColor = state.seedColor) {
            if (isTabletUi()) {
                AnimeScreenLarge(
                    state = state,
                    anime = anime,
                    navigateUp = navigator::pop,
                    onRefresh = screenModel::refresh,
                    onToggleFavorite = screenModel::toggleFavorite,
                    onToggleSort = screenModel::toggleSort,
                    onWebView = openWebView,
                    onSearch = searchSource,
                    onSourceClick = { navigator.push(AnimeSourceScreen(anime.source)) },
                    onOpenSeason = { navigator.push(AnimeScreen(it.id)) },
                    onPlay = playEpisode,
                    onToggleSeen = screenModel::toggleSeen,
                    onToggleBookmark = screenModel::toggleBookmark,
                    sourceMissing = state.sourceMissing,
                    source = screenModel.source,
                )
            } else {
                AnimeScreenSmall(
                    state = state,
                    anime = anime,
                    navigateUp = navigator::pop,
                    onRefresh = screenModel::refresh,
                    onToggleFavorite = screenModel::toggleFavorite,
                    onToggleSort = screenModel::toggleSort,
                    onWebView = openWebView,
                    onSearch = searchSource,
                    onSourceClick = { navigator.push(AnimeSourceScreen(anime.source)) },
                    onOpenSeason = { navigator.push(AnimeScreen(it.id)) },
                    onPlay = playEpisode,
                    onToggleSeen = screenModel::toggleSeen,
                    onToggleBookmark = screenModel::toggleBookmark,
                    sourceMissing = state.sourceMissing,
                    source = screenModel.source,
                )
            }

            VideoDialogs(
                state = state,
                screenModel = screenModel,
                context = context,
            )
        }
    }
}

@Composable
private fun VideoDialogs(state: AnimeScreenModel.State, screenModel: AnimeScreenModel, context: Context) {
    val anime = state.anime
        when (val dialog = state.videoDialog) {
            null -> {}
            is AnimeScreenModel.VideoDialog.Loading -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(dialog.episode.name) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(KMR.strings.anime_loading_videos))
                    }
                },
            )
            is AnimeScreenModel.VideoDialog.Pick -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(stringResource(KMR.strings.anime_choose_video)) },
                text = {
                    ScrollbarLazyColumn {
                        items(dialog.videos) { video ->
                            Text(
                                text = video.videoTitle.ifBlank { video.videoUrl },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        screenModel.resolveAndPlay(dialog.episode, video) { resolved ->
                                            openExternalPlayer(context, resolved, anime?.title, dialog.episode)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }
                },
            )
            is AnimeScreenModel.VideoDialog.Empty -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) { Text(stringResource(MR.strings.action_ok)) }
                },
                title = { Text(dialog.episode.name) },
                text = { Text(stringResource(KMR.strings.anime_no_videos)) },
            )
            is AnimeScreenModel.VideoDialog.Failed -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {
                    TextButton(
                        onClick = { screenModel.loadVideos(dialog.episode) },
                    ) { Text(stringResource(MR.strings.action_retry)) }
                },
                dismissButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(dialog.episode.name) },
                text = { Text(dialog.message) },
            )
        }
}

@Composable
private fun AnimeToolbar(
    title: String,
    titleAlpha: Float,
    backgroundAlpha: Float,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onWebView: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AppBar(
        titleContent = { AppBarTitle(title, modifier = Modifier.alpha(titleAlpha)) },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp).copy(alpha = backgroundAlpha),
        navigateUp = navigateUp,
        actions = {
            if (onWebView != null) {
                IconButton(onClick = onWebView) {
                    Icon(Icons.Outlined.Public, contentDescription = stringResource(MR.strings.action_web_view))
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_webview_refresh))
            }
        },
    )
}

@Composable
private fun StartFab(state: AnimeScreenModel.State, expanded: Boolean, onPlay: (Episode) -> Unit) {
    val next = state.nextEpisode
    SmallExtendedFloatingActionButton(
        text = { Text(stringResource(if (state.hasStarted) MR.strings.action_resume else MR.strings.action_start)) },
        icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
        onClick = { next?.let(onPlay) },
        expanded = expanded,
        modifier = Modifier.animateFloatingActionButton(visible = next != null, alignment = Alignment.BottomEnd),
    )
}

@Composable
private fun AnimeInfo(
    anime: Anime,
    state: AnimeScreenModel.State,
    isTablet: Boolean,
    appBarPadding: androidx.compose.ui.unit.Dp,
    sourceMissing: Boolean,
    onToggleFavorite: () -> Unit,
    onWebView: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onSourceClick: () -> Unit,
) {
    val context = LocalContext.current
    val displayManga = remember(anime) { anime.asDisplayManga() }
    val coverRatio = remember { mutableFloatStateOf(1f) }
    Column {
        MangaInfoBox(
            isTabletUi = isTablet,
            appBarPadding = appBarPadding,
            manga = displayManga,
            sourceName = state.sourceName ?: anime.source.toString(),
            isStubSource = sourceMissing,
            isSourceIncognito = false,
            onCoverClick = {},
            doSearch = { query, _ -> onSearch(query) },
            librarySearch = { onSearch(it) },
            onSourceClick = onSourceClick,
            onCoverLoaded = {},
            coverRatio = coverRatio,
        )
        MangaActionRow(
            favorite = anime.favorite,
            trackingCount = 0,
            nextUpdate = null,
            isUserIntervalMode = false,
            onAddToLibraryClicked = onToggleFavorite,
            onWebViewClicked = onWebView,
            onWebViewLongClicked = null,
            onTrackingClicked = { context.toast(KMR.strings.anime_tracking_coming) },
            onEditIntervalClicked = null,
            onEditCategory = null,
            onMergeClicked = null,
            status = anime.status,
            interval = 0,
        )
        ExpandableMangaDescription(
            defaultExpandState = isTablet,
            description = anime.description,
            tagsProvider = { anime.genre?.filter { it.isNotBlank() } },
            notes = "",
            onTagSearch = onSearch,
            onCopyTagToClipboard = { tag ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(tag, tag))
                context.toast(MR.strings.copied_to_clipboard_plain)
            },
            onEditNotes = {},
            searchMetadataChips = null,
            doSearch = { query, _ -> onSearch(query) },
        )
        if (state.error != null) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (sourceMissing) {
            Text(
                text = stringResource(KMR.strings.anime_source_not_loaded),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AnimeScreenSmall(
    state: AnimeScreenModel.State,
    anime: Anime,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSort: () -> Unit,
    onWebView: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onSourceClick: () -> Unit,
    onOpenSeason: (Anime) -> Unit,
    onPlay: (Episode) -> Unit,
    onToggleSeen: (Episode) -> Unit,
    onToggleBookmark: (Episode) -> Unit,
    sourceMissing: Boolean,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
) {
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            val isFirstItemVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
            val isFirstItemScrolled by remember { derivedStateOf { listState.firstVisibleItemScrollOffset > 0 } }
            val titleAlpha by animateFloatAsState(if (!isFirstItemVisible) 1f else 0f, label = "title")
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "background",
            )
            AnimeToolbar(
                title = anime.title,
                titleAlpha = titleAlpha,
                backgroundAlpha = backgroundAlpha,
                navigateUp = navigateUp,
                onRefresh = onRefresh,
                onWebView = onWebView,
            )
        },
        floatingActionButton = { StartFab(state, listState.shouldExpandFAB(), onPlay) },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = onRefresh,
            enabled = true,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            VerticalFastScroller(listState = listState, topContentPadding = topPadding) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 72.dp),
                ) {
                    item(key = "info") {
                        AnimeInfo(
                            anime = anime,
                            state = state,
                            isTablet = false,
                            appBarPadding = topPadding,
                            sourceMissing = sourceMissing,
                            onToggleFavorite = onToggleFavorite,
                            onWebView = onWebView,
                            onSearch = onSearch,
                            onSourceClick = onSourceClick,
                        )
                    }
                    episodeItems(state, source, onToggleSort, onOpenSeason, onPlay, onToggleSeen, onToggleBookmark)
                }
            }
        }
    }
}

@Composable
private fun AnimeScreenLarge(
    state: AnimeScreenModel.State,
    anime: Anime,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSort: () -> Unit,
    onWebView: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onSourceClick: () -> Unit,
    onOpenSeason: (Anime) -> Unit,
    onPlay: (Episode) -> Unit,
    onToggleSeen: (Episode) -> Unit,
    onToggleBookmark: (Episode) -> Unit,
    sourceMissing: Boolean,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        topBar = {
            AnimeToolbar(
                title = anime.title,
                titleAlpha = 1f,
                backgroundAlpha = 1f,
                navigateUp = navigateUp,
                onRefresh = onRefresh,
                onWebView = onWebView,
            )
        },
        floatingActionButton = { StartFab(state, listState.shouldExpandFAB(), onPlay) },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = onRefresh,
            enabled = true,
            indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        ) {
            TwoPanelBox(
                modifier = Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
                startContent = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                    ) {
                        AnimeInfo(
                            anime = anime,
                            state = state,
                            isTablet = true,
                            appBarPadding = contentPadding.calculateTopPadding(),
                            sourceMissing = sourceMissing,
                            onToggleFavorite = onToggleFavorite,
                            onWebView = onWebView,
                            onSearch = onSearch,
                            onSourceClick = onSourceClick,
                        )
                    }
                },
                endContent = {
                    VerticalFastScroller(
                        listState = listState,
                        topContentPadding = contentPadding.calculateTopPadding(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = listState,
                            contentPadding = PaddingValues(
                                top = contentPadding.calculateTopPadding(),
                                bottom = contentPadding.calculateBottomPadding() + 72.dp,
                            ),
                        ) {
                            episodeItems(state, source, onToggleSort, onOpenSeason, onPlay, onToggleSeen, onToggleBookmark)
                        }
                    }
                },
            )
        }
    }
}

private fun LazyListScope.episodeItems(
    state: AnimeScreenModel.State,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
    onToggleSort: () -> Unit,
    onOpenSeason: (Anime) -> Unit,
    onPlay: (Episode) -> Unit,
    onToggleSeen: (Episode) -> Unit,
    onToggleBookmark: (Episode) -> Unit,
) {
    if (state.seasons.isNotEmpty()) {
        item(key = "seasons") {
            Column {
                Text(
                    text = stringResource(KMR.strings.anime_seasons),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(state.seasons, key = { it.id }) { season ->
                        Box(Modifier.width(120.dp)) {
                            AnimeGridItem(anime = season, source = source, onClick = { onOpenSeason(season) })
                        }
                    }
                }
            }
        }
        return
    }
    item(key = "episodes_header") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = if (state.isLoaded && state.episodes.isEmpty() && !state.isRefreshing) {
                    stringResource(KMR.strings.anime_no_episodes)
                } else {
                    stringResource(KMR.strings.anime_episodes_count, state.episodes.size)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleSort) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(MR.strings.action_sort))
            }
        }
    }
    items(state.episodes, key = { "ep-${it.id}" }, contentType = { "episode" }) { episode ->
        EpisodeItem(
            episode = episode,
            onPlay = { onPlay(episode) },
            onToggleSeen = { onToggleSeen(episode) },
            onToggleBookmark = { onToggleBookmark(episode) },
        )
    }
}

@Composable
private fun EpisodeItem(
    episode: Episode,
    onPlay: () -> Unit,
    onToggleSeen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val context = LocalContext.current
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val relativeTime by uiPreferences.relativeTime().collectAsState()
    val dateFormat by uiPreferences.dateFormat().collectAsState()
    val swipeStart by libraryPreferences.swipeToStartAction().collectAsState()
    val swipeEnd by libraryPreferences.swipeToEndAction().collectAsState()
    val date = remember(episode.dateUpload, relativeTime, dateFormat) {
        episode.dateUpload.takeIf { it > 0L }?.let {
            LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                .toRelativeString(context, relativeTime, UiPreferences.dateFormat(dateFormat))
        }
    }
    val progress = if (!episode.seen && episode.lastSecondSeen > 0) {
        stringResource(KMR.strings.anime_watched_until, formatTime(episode.lastSecondSeen))
    } else {
        null
    }
    MangaChapterListItem(
        title = episode.name,
        date = date,
        readProgress = progress,
        scanlator = listOfNotNull(
            episode.scanlator?.takeIf { it.isNotBlank() },
            stringResource(KMR.strings.anime_filler).takeIf { episode.fillermark },
        ).joinToString(" • ").ifEmpty { null },
        sourceName = null,
        read = episode.seen,
        bookmark = episode.bookmark,
        selected = false,
        downloadIndicatorEnabled = false,
        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
        downloadProgressProvider = { 0 },
        chapterSwipeStartAction = swipeStart,
        chapterSwipeEndAction = swipeEnd,
        onLongClick = onToggleSeen,
        onClick = onPlay,
        onDownloadClick = null,
        onChapterSwipe = { action ->
            when (action) {
                LibraryPreferences.ChapterSwipeAction.ToggleRead -> onToggleSeen()
                LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> onToggleBookmark()
                else -> {}
            }
        },
    )
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Plays a video in an outside player app until the built-in player arrives. Passes headers the way MX/mpv/VLC read them. */
private fun openExternalPlayer(context: Context, video: Video, animeTitle: String?, episode: Episode) {
    val headers = video.headers
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(video.videoUrl.toUri(), "video/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val title = listOfNotNull(animeTitle, episode.name).joinToString(" - ")
        putExtra("title", title)
        putExtra(Intent.EXTRA_TITLE, title)
        if (headers != null && headers.size > 0) {
            val pairs = ArrayList<String>()
            headers.forEach { (name, value) ->
                pairs += name
                pairs += value
            }
            putExtra("headers", pairs.toTypedArray())
        }
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.toast(KMR.strings.anime_no_player)
    }
}
