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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.AnimeGridItem
import eu.kanade.presentation.anime.asDisplayManga
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.RelatedMangaTitle
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.ScanlatorFilterItem
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.OutlinedButtonWithArrow
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.theme.CoverSeedTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.lang.toRelativeString
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
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
 * (toolbar, cover backdrop, action buttons, description and tags, related row, chapter-style rows,
 * Start button, cover colors, filter/sort/display sheet).
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
        val animeUrl: String? = (screenModel.source as? AnimeHttpSource)?.let { source ->
            runCatching { source.getAnimeUrl(anime.toSAnime()) }.getOrElse { source.baseUrl + anime.url }
        }
        val openWebView: (() -> Unit)? = animeUrl?.let { url -> { navigator.push(WebViewScreen(url, anime.title)) } }

        var dialog by remember { mutableStateOf<AnimeDialog?>(null) }
        val actions = AnimeActions(
            navigateUp = navigator::pop,
            onRefresh = screenModel::refresh,
            onToggleFavorite = screenModel::toggleFavorite,
            onFilter = { dialog = AnimeDialog.Settings },
            onWebView = openWebView,
            onShare = animeUrl?.let { url ->
                {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(intent, context.stringResource(MR.strings.action_share)))
                }
            },
            onDownload = screenModel::downloadEpisodes,
            onEpisodeDownload = screenModel::episodeDownloadAction,
            onEditNotes = { dialog = AnimeDialog.Notes },
            onClearAnime = { dialog = AnimeDialog.Clear },
            onSourceSettings = {
                val source = screenModel.source
                if (source is ConfigurableAnimeSource) {
                    navigator.push(SourcePreferencesScreen(source.id, anime = true))
                } else {
                    context.toast(KMR.strings.anime_no_source_settings)
                }
            },
            onRelatedScreen = { navigator.push(AnimeRelatedScreen(anime.id)) },
            onSearch = { query -> navigator.push(AnimeSourceScreen(anime.source, query)) },
            onSourceClick = { navigator.push(AnimeSourceScreen(anime.source)) },
            onOpenAnime = { navigator.push(AnimeScreen(it.id)) },
            onPlay = playEpisode,
            onToggleSeen = screenModel::toggleSeen,
            onToggleBookmark = screenModel::toggleBookmark,
        )

        CoverSeedTheme(seedColor = state.seedColor) {
            when (dialog) {
                null -> {}
                AnimeDialog.Settings -> AnimeEpisodeSettingsDialog(
                    anime = anime,
                    scanlatorFilterActive = state.excludedScanlators.isNotEmpty(),
                    onDismissRequest = { dialog = null },
                    onFilterChanged = screenModel::setEpisodeFilter,
                    onScanlatorFilterClicked = { dialog = AnimeDialog.Scanlators },
                    onSortModeChanged = screenModel::setSortMode,
                    onDisplayNumberChanged = screenModel::setDisplayNumber,
                    onReset = screenModel::resetEpisodeSettings,
                )
                AnimeDialog.Scanlators -> ScanlatorFilterDialog(
                    availableScanlators = state.scanlators.toImmutableSet(),
                    excludedScanlators = state.excludedScanlators.toImmutableSet(),
                    onDismissRequest = { dialog = AnimeDialog.Settings },
                    onConfirm = screenModel::setExcludedScanlators,
                )
                AnimeDialog.Notes -> AnimeNotesDialog(
                    notes = state.notes,
                    onDismissRequest = { dialog = null },
                    onSave = screenModel::setNotes,
                )
                AnimeDialog.Clear -> ClearAnimeDialog(
                    onDismissRequest = { dialog = null },
                    onConfirm = screenModel::clearAnime,
                )
            }
            if (isTabletUi()) {
                AnimeScreenLarge(state, anime, actions, screenModel.source)
            } else {
                AnimeScreenSmall(state, anime, actions, screenModel.source)
            }

            VideoDialogs(
                state = state,
                screenModel = screenModel,
                context = context,
            )
        }
    }
}

private enum class AnimeDialog { Settings, Scanlators, Notes, Clear }

/** Everything the details page can do, passed down to both layouts */
private class AnimeActions(
    val navigateUp: () -> Unit,
    val onRefresh: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onFilter: () -> Unit,
    val onWebView: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onDownload: (DownloadAction) -> Unit,
    val onEpisodeDownload: (Episode, ChapterDownloadAction) -> Unit,
    val onEditNotes: () -> Unit,
    val onClearAnime: () -> Unit,
    val onSourceSettings: () -> Unit,
    val onRelatedScreen: () -> Unit,
    val onSearch: (String) -> Unit,
    val onSourceClick: () -> Unit,
    val onOpenAnime: (Anime) -> Unit,
    val onPlay: (Episode) -> Unit,
    val onToggleSeen: (Episode) -> Unit,
    val onToggleBookmark: (Episode) -> Unit,
)

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

/** The manga details toolbar, with the options anime supports */
@Composable
private fun AnimeToolbar(
    title: String,
    filterActive: Boolean,
    actions: AnimeActions,
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val expandRelated by uiPreferences.expandRelatedMangas().collectAsState()
    val relatedInOverflow by uiPreferences.relatedMangasInOverflow().collectAsState()
    MangaToolbar(
        title = title,
        hasFilters = filterActive,
        navigateUp = actions.navigateUp,
        onClickFilter = actions.onFilter,
        onClickShare = actions.onShare,
        onClickDownload = actions.onDownload,
        onClickEditCategory = null,
        onClickRefresh = actions.onRefresh,
        onClickMigrate = null,
        onClickEditNotes = actions.onEditNotes,
        onClickEditInfo = null,
        onClickRelatedMangas = actions.onRelatedScreen.takeIf { !expandRelated && relatedInOverflow },
        onClickSourceSettings = actions.onSourceSettings,
        onClearManga = actions.onClearAnime,
        onOpenMangaFolder = null,
        onClickRecommend = null,
        onClickMerge = null,
        onClickMergedSettings = null,
        actionModeCounter = 0,
        onCancelActionMode = {},
        onSelectAll = {},
        onInvertSelection = {},
        titleAlphaProvider = titleAlphaProvider,
        backgroundAlphaProvider = backgroundAlphaProvider,
        onPaletteScreenClick = {},
    )
}

/** Filter / Sort / Display sheet, same as Anikku's episode settings */
@Composable
private fun AnimeEpisodeSettingsDialog(
    anime: Anime,
    scanlatorFilterActive: Boolean,
    onDismissRequest: () -> Unit,
    onFilterChanged: (include: Long, exclude: Long, TriState) -> Unit,
    onScanlatorFilterClicked: () -> Unit,
    onSortModeChanged: (Long) -> Unit,
    onDisplayNumberChanged: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onReset()
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    TriStateItem(
                        label = stringResource(MR.strings.label_downloaded),
                        state = anime.downloadedFilter,
                        onClick = { onFilterChanged(Anime.FILTER_DOWNLOADED, Anime.FILTER_DOWNLOADED_EXCLUDE, it) },
                    )
                    TriStateItem(
                        label = stringResource(KMR.strings.anime_filter_unseen),
                        state = anime.unseenFilter,
                        onClick = { onFilterChanged(Anime.FILTER_UNSEEN, Anime.FILTER_UNSEEN_EXCLUDE, it) },
                    )
                    TriStateItem(
                        label = stringResource(MR.strings.action_filter_bookmarked),
                        state = anime.bookmarkedFilter,
                        onClick = { onFilterChanged(Anime.FILTER_BOOKMARKED, Anime.FILTER_BOOKMARKED_EXCLUDE, it) },
                    )
                    TriStateItem(
                        label = stringResource(AMR.strings.action_filter_fillermarked),
                        state = anime.fillermarkedFilter,
                        onClick = { onFilterChanged(Anime.FILTER_FILLER_INCLUDE, Anime.FILTER_HIDE_FILLER, it) },
                    )
                    ScanlatorFilterItem(active = scanlatorFilterActive, onClick = onScanlatorFilterClicked)
                }
                1 -> {
                    listOf(
                        MR.strings.sort_by_source to Anime.SORT_BY_SOURCE,
                        KMR.strings.anime_sort_by_episode_number to Anime.SORT_BY_NUMBER,
                        MR.strings.sort_by_upload_date to Anime.SORT_BY_UPLOAD_DATE,
                        MR.strings.action_sort_alpha to Anime.SORT_BY_ALPHABET,
                    ).forEach { (label, mode) ->
                        SortItem(
                            label = stringResource(label),
                            sortDescending = (!anime.sortAscending).takeIf { anime.sortMode == mode },
                            onClick = { onSortModeChanged(mode) },
                        )
                    }
                }
                2 -> {
                    RadioItem(
                        label = stringResource(MR.strings.show_title),
                        selected = !anime.displayNumber,
                        onClick = { onDisplayNumberChanged(false) },
                    )
                    RadioItem(
                        label = stringResource(KMR.strings.anime_show_episode_number),
                        selected = anime.displayNumber,
                        onClick = { onDisplayNumberChanged(true) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimeNotesDialog(notes: String, onDismissRequest: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(notes) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(text.trim())
                    onDismissRequest()
                },
            ) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
        title = { Text(stringResource(MR.strings.action_edit_notes)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
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
    actions: AnimeActions,
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
            isStubSource = state.sourceMissing,
            isSourceIncognito = false,
            onCoverClick = {},
            doSearch = { query, _ -> actions.onSearch(query) },
            librarySearch = { actions.onSearch(it) },
            onSourceClick = actions.onSourceClick,
            onCoverLoaded = {},
            coverRatio = coverRatio,
            forcePanoramaCover = true,
        )
        MangaActionRow(
            favorite = anime.favorite,
            trackingCount = 0,
            nextUpdate = null,
            isUserIntervalMode = false,
            onAddToLibraryClicked = actions.onToggleFavorite,
            onWebViewClicked = actions.onWebView,
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
            notes = state.notes,
            onTagSearch = actions.onSearch,
            onCopyTagToClipboard = { tag ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(tag, tag))
                context.toast(MR.strings.copied_to_clipboard_plain)
            },
            onEditNotes = actions.onEditNotes,
            searchMetadataChips = null,
            doSearch = { query, _ -> actions.onSearch(query) },
        )
        if (state.error != null) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.sourceMissing) {
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
    actions: AnimeActions,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
) {
    val listState = rememberLazyListState()
    val related = rememberRelatedSettings()
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
                filterActive = state.filterActive,
                actions = actions,
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        floatingActionButton = { StartFab(state, listState.shouldExpandFAB(), actions.onPlay) },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
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
                            actions = actions,
                        )
                    }
                    relatedItems(state, related, uppercaseTitle = false, dividerAbove = true, actions)
                    episodeItems(state, anime, source, actions)
                }
            }
        }
    }
}

@Composable
private fun AnimeScreenLarge(
    state: AnimeScreenModel.State,
    anime: Anime,
    actions: AnimeActions,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val related = rememberRelatedSettings()
    Scaffold(
        topBar = {
            AnimeToolbar(
                title = anime.title,
                filterActive = state.filterActive,
                actions = actions,
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        floatingActionButton = { StartFab(state, listState.shouldExpandFAB(), actions.onPlay) },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
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
                            actions = actions,
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
                            // Same spot as the manga page: related titles above the episode list
                            relatedItems(state, related, uppercaseTitle = true, dividerAbove = false, actions)
                            episodeItems(state, anime, source, actions)
                        }
                    }
                },
            )
        }
    }
}

private class RelatedSettings(val enabled: Boolean, val expand: Boolean, val inOverflow: Boolean)

@Composable
private fun rememberRelatedSettings(): RelatedSettings {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val enabled by sourcePreferences.relatedMangas().collectAsState()
    val expand by uiPreferences.expandRelatedMangas().collectAsState()
    val inOverflow by uiPreferences.relatedMangasInOverflow().collectAsState()
    return RelatedSettings(enabled, expand, inOverflow)
}

/** Related titles row, or the "Related titles" button, following the same settings as manga */
private fun LazyListScope.relatedItems(
    state: AnimeScreenModel.State,
    settings: RelatedSettings,
    uppercaseTitle: Boolean,
    dividerAbove: Boolean,
    actions: AnimeActions,
) {
    if (!settings.enabled || state.sourceMissing) return
    if (settings.expand) {
        if (state.suggestions.isEmpty() && !state.suggestionsLoading) return
        if (dividerAbove) item(key = "related-divider-top") { HorizontalDivider() }
        item(key = "related") {
            Column {
                val title = stringResource(KMR.strings.pref_source_related_mangas)
                RelatedMangaTitle(
                    title = if (uppercaseTitle) title.uppercase() else title,
                    subtitle = null,
                    onClick = actions.onRelatedScreen,
                    onLongClick = null,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                )
                if (state.suggestions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    AnimeSuggestionsRow(suggestions = state.suggestions, onClick = actions.onOpenAnime)
                }
            }
        }
        item(key = "related-divider") { HorizontalDivider() }
    } else if (!settings.inOverflow) {
        item(key = "related") {
            OutlinedButtonWithArrow(
                text = stringResource(KMR.strings.pref_source_related_mangas),
                onClick = actions.onRelatedScreen,
            )
        }
    }
}

@Composable
private fun AnimeSuggestionsRow(suggestions: List<Anime>, onClick: (Anime) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(suggestions, key = { it.id }) { anime ->
            Box(Modifier.width(110.dp)) {
                MangaComfortableGridItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title,
                    onClick = { onClick(anime) },
                    onLongClick = { onClick(anime) },
                    usePanoramaCover = false,
                )
            }
        }
    }
}

private fun LazyListScope.episodeItems(
    state: AnimeScreenModel.State,
    anime: Anime,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
    actions: AnimeActions,
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
                            AnimeGridItem(anime = season, source = source, onClick = { actions.onOpenAnime(season) })
                        }
                    }
                }
            }
        }
        return
    }
    item(key = "episodes_header") {
        // Like the manga chapter header: tap it to open the filter sheet
        Text(
            text = if (state.isLoaded && state.episodes.isEmpty() && !state.isRefreshing) {
                stringResource(KMR.strings.anime_no_episodes)
            } else {
                stringResource(KMR.strings.anime_episodes_count, state.episodes.size)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onFilter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    items(state.episodes, key = { "ep-${it.id}" }, contentType = { "episode" }) { episode ->
        EpisodeItem(
            episode = episode,
            displayNumber = anime.displayNumber,
            downloadState = state.downloadState(episode),
            downloadProgress = state.downloadProgress(episode),
            onDownload = { action -> actions.onEpisodeDownload(episode, action) },
            onPlay = { actions.onPlay(episode) },
            onToggleSeen = { actions.onToggleSeen(episode) },
            onToggleBookmark = { actions.onToggleBookmark(episode) },
        )
    }
}

@Composable
private fun EpisodeItem(
    episode: Episode,
    displayNumber: Boolean,
    downloadState: Download.State,
    downloadProgress: Int,
    onDownload: (ChapterDownloadAction) -> Unit,
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
    val title = if (displayNumber && episode.episodeNumber >= 0) {
        val number = episode.episodeNumber
        val text = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
        stringResource(KMR.strings.anime_episode_number, text)
    } else {
        episode.name
    }
    MangaChapterListItem(
        title = title,
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
        downloadIndicatorEnabled = true,
        downloadStateProvider = { downloadState },
        downloadProgressProvider = { downloadProgress },
        chapterSwipeStartAction = swipeStart,
        chapterSwipeEndAction = swipeEnd,
        onLongClick = onToggleSeen,
        onClick = onPlay,
        onDownloadClick = onDownload,
        onChapterSwipe = { action ->
            when (action) {
                LibraryPreferences.ChapterSwipeAction.ToggleRead -> onToggleSeen()
                LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> onToggleBookmark()
                LibraryPreferences.ChapterSwipeAction.Download -> onDownload(
                    when (downloadState) {
                        Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START_NOW
                        Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
                        Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                    },
                )
                else -> {}
            }
        },
    )
}

/** Same choices as "Clear manga": the downloaded files and/or the saved episode list */
@Composable
private fun ClearAnimeDialog(onDismissRequest: () -> Unit, onConfirm: (Boolean, Boolean) -> Unit) {
    var downloads by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = downloads || episodes,
                onClick = {
                    onDismissRequest()
                    onConfirm(downloads, episodes)
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
        title = { Text(stringResource(AMR.strings.action_clear_anime)) },
        text = {
            Column {
                LabeledCheckbox(
                    label = stringResource(KMR.strings.anime_delete_downloads),
                    checked = downloads,
                    onCheckedChange = { downloads = it },
                )
                LabeledCheckbox(
                    label = stringResource(KMR.strings.anime_episodes_from_database),
                    checked = episodes,
                    onCheckedChange = { episodes = it },
                )
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
