package eu.kanade.tachiyomi.ui.anime.source

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.AnimeEpisodePanel
import eu.kanade.presentation.anime.AnimePlayerPanel
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.anime.toMangaFilterList
import eu.kanade.presentation.anime.writeBackToAnime
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.components.wideSpan
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.reader.sidePanelWidth
import eu.kanade.presentation.theme.CoverSeedTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.sumizuri.SumizuriTabs
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Browse one anime source. Laid out like manga browsing: same toolbar and display modes, Popular / Latest /
 * Filter chips, the manga filter sheet, and the cover preview panel with the cover's colors.
 */
class AnimeSourceScreen(
    private val sourceId: Long,
    private val initialQuery: String? = null,
    // KMK: opened from the source home page
    private val initialLatest: Boolean = false,
    private val openFilters: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { AnimeSourceScreenModel(sourceId) }
        val state by screenModel.state.collectAsState()
        val preview by screenModel.preview.collectAsState()
        val source = screenModel.source

        var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }
        var showFilters by rememberSaveable { mutableStateOf(openFilters) }

        LaunchedEffect(Unit) {
            when {
                !initialQuery.isNullOrBlank() -> screenModel.search(initialQuery)
                // KMK: a source with tab catalogs already opened on one
                initialLatest && (source as? SumizuriTabs)?.hasCatalogTabs != true ->
                    screenModel.setListing(AnimeSourceScreenModel.Listing.Latest)
            }
        }

        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
        val displayModePref = remember { sourcePreferences.sourceDisplayMode() }
        val displayMode by displayModePref.collectAsState()
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columnsPref = remember(isLandscape) {
            if (isLandscape) sourcePreferences.browseLandscapeColumns() else sourcePreferences.browsePortraitColumns()
        }
        val columnsValue by columnsPref.collectAsState()
        val columnCount = if (columnsValue >= 0) {
            columnsValue
        } else {
            (if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()).get()
        }
        val previewPanelsPref = remember { sourcePreferences.browsePreviewPanels() }
        val previewPanels by previewPanelsPref.collectAsState()

        val openWebView: (String, String) -> Unit = { url, title -> navigator.push(WebViewScreen(url, title)) }
        val playEpisode: (eu.kanade.tachiyomi.data.anime.Episode) -> Unit = { episode ->
            if (playerPreferences.alwaysUseExternalPlayer().get()) {
                navigator.push(AnimeScreen(episode.animeId))
            } else {
                context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id))
            }
        }

        CoverSeedTheme(seedColor = preview.seedColor) {
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) {},
                    ) {
                        BrowseSourceToolbar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            source = null,
                            iconSourceId = sourceId,
                            subtitleOverride = source?.lang?.let {
                                eu.kanade.tachiyomi.util.system.LocaleHelper.getLocalizedDisplayName(it)
                            },
                            displayMode = displayMode,
                            onDisplayModeChange = { displayModePref.set(it) },
                            navigateUp = {
                                if (searchQuery != null) {
                                    searchQuery = null
                                    // KMK: back to the tab catalog, for a source that has them
                                    if (!screenModel.showHomeTab()) {
                                        screenModel.setListing(AnimeSourceScreenModel.Listing.Popular)
                                    }
                                } else {
                                    navigator.pop()
                                }
                            },
                            onWebViewClick = {
                                (source as? AnimeHttpSource)?.let { openWebView(it.baseUrl, it.name) }
                                    ?: (source as? eu.kanade.tachiyomi.sumizuri.SumizuriAnimeSource)
                                        ?.let { openWebView(it.baseUrl, it.name) }
                            },
                            onHelpClick = {},
                            onToggleIncognito = {},
                            onSettingsClick = {
                                navigator.push(SourcePreferencesScreen(sourceId, anime = true))
                            }.takeIf { source is ConfigurableAnimeSource },
                            onSearch = { screenModel.search(it) },
                            toggleSelectionMode = {},
                            isRunning = false,
                            columns = columnCount,
                            onColumnsChange = columnsPref::set,
                            previewPanels = previewPanels,
                            onPreviewPanelsChange = { mode ->
                                previewPanelsPref.set(mode)
                                if (mode == SourcePreferences.PREVIEW_PANELS_NONE) screenModel.clearPreview()
                            },
                            titleOverride = source?.name ?: sourceId.toString(),
                            showBulkSelection = false,
                            showIncognito = false,
                        )
                        ListingChips(
                            state = state,
                            supportsLatest = source?.supportsLatest == true,
                            onPopular = {
                                searchQuery = null
                                screenModel.resetFilters()
                                screenModel.setListing(AnimeSourceScreenModel.Listing.Popular)
                            },
                            onLatest = {
                                searchQuery = null
                                screenModel.resetFilters()
                                screenModel.setListing(AnimeSourceScreenModel.Listing.Latest)
                            },
                            onFilter = { showFilters = true },
                            // KMK: a JS / JSON source's own names for Popular / Latest, and its catalogs as buttons
                            tabs = source as? SumizuriTabs,
                            onCatalog = { id ->
                                searchQuery = null
                                screenModel.showCatalog(id)
                            },
                        )
                        HorizontalDivider()
                    }
                },
            ) { paddingValues ->
                val layoutDirection = LocalLayoutDirection.current
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val panelWidth = sidePanelWidth(maxWidth)
                    val startPadding by animateDpAsState(
                        if (preview.panelOpen) panelWidth else 0.dp,
                        label = "animeBrowseStartPadding",
                    )
                    val endPadding by animateDpAsState(
                        if (preview.playerPanelOpen) panelWidth else 0.dp,
                        label = "animeBrowseEndPadding",
                    )
                    val gridPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding(),
                        start = paddingValues.calculateStartPadding(layoutDirection) + startPadding,
                        end = paddingValues.calculateEndPadding(layoutDirection) + endPadding,
                    )
                    // The right panel plays whichever episode was tapped last (the first one until then)
                    var chosenEpisode by remember(preview.anime?.id) {
                        mutableStateOf<eu.kanade.tachiyomi.data.anime.Episode?>(null)
                    }
                    var playToken by remember(preview.anime?.id) { mutableIntStateOf(0) }
                    when {
                        source == null -> EmptyScreen(
                            message = stringResource(MR.strings.source_not_installed, sourceId.toString()),
                            modifier = Modifier.padding(paddingValues),
                        )
                        state.items.isEmpty() && state.isLoading -> LoadingScreen(Modifier.padding(paddingValues))
                        state.items.isEmpty() && state.error != null -> ErrorWithRetry(
                            message = state.error!!,
                            onRetry = screenModel::retry,
                            contentPadding = paddingValues,
                        )
                        state.items.isEmpty() && !state.hasNextPage -> EmptyScreen(
                            message = stringResource(MR.strings.no_results_found),
                            modifier = Modifier.padding(paddingValues),
                        )
                        else -> AnimeBrowseContent(
                            state = state,
                            displayMode = displayMode,
                            columns = if (columnCount > 0) GridCells.Fixed(columnCount) else GridCells.Adaptive(128.dp),
                            contentPadding = gridPadding,
                            selectedId = preview.anime?.id,
                            onLoadMore = screenModel::loadNextPage,
                            onRetry = screenModel::retry,
                            onClick = { anime ->
                                if (previewPanels != SourcePreferences.PREVIEW_PANELS_NONE &&
                                    preview.anime?.id != anime.id
                                ) {
                                    // First tap: highlight and preview. Second tap on the same cover: open it.
                                    screenModel.selectPreview(
                                        anime,
                                        showPlayer = previewPanels == SourcePreferences.PREVIEW_PANELS_BOTH,
                                    )
                                } else {
                                    navigator.push(AnimeScreen(anime.id))
                                }
                            },
                            onLongClick = { anime ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                screenModel.toggleFavorite(anime)
                            },
                        )
                    }

                    // The panel starts below the toolbar and the Popular / Latest / Filter row
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding()),
                    ) {
                        AnimeEpisodePanel(
                            preview = preview,
                            sourceName = source?.name.orEmpty(),
                            onClose = screenModel::closePreviewPanel,
                            onAddToLibrary = screenModel::toggleFavorite,
                            onWebView = { anime ->
                                (source as? AnimeHttpSource)?.let { httpSource ->
                                    val url = runCatching { httpSource.getAnimeUrl(anime.toSAnime()) }
                                        .getOrElse { httpSource.baseUrl + anime.url }
                                    openWebView(url, anime.title)
                                } ?: (source as? eu.kanade.tachiyomi.sumizuri.SumizuriAnimeSource)?.let { js ->
                                    openWebView(js.getAnimeUrl(anime.toSAnime()), anime.title)
                                }
                            },
                            onOpenDetails = { navigator.push(AnimeScreen(it.id)) },
                            onSearch = { query ->
                                searchQuery = query
                                screenModel.search(query)
                            },
                            onToggleSort = screenModel::toggleEpisodeSort,
                            onPlay = playEpisode,
                            onToggleSeen = screenModel::toggleSeen,
                            onToggleBookmark = screenModel::toggleBookmark,
                            selectedEpisodeId = chosenEpisode?.id,
                            onSelectEpisode = { episode ->
                                chosenEpisode = episode
                                playToken++
                                screenModel.openPlayerPanel()
                            },
                        )
                        AnimePlayerPanel(
                            preview = preview,
                            source = source,
                            episode = chosenEpisode ?: preview.episodes.firstOrNull(),
                            playToken = playToken,
                            onClose = screenModel::closePlayerPanel,
                            onOpenPlayer = { episode, positionMs ->
                                screenModel.savePreviewPosition(episode.id, positionMs) {
                                    context.startActivity(
                                        PlayerActivity.newIntent(context, episode.animeId, episode.id),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            if (showFilters) {
                // Rebuilt after every change so the sheet redraws with the new choices
                var mangaFilters by remember(state.filters) {
                    mutableStateOf(state.filters.toMangaFilterList(), neverEqualPolicy())
                }
                SourceFilterDialog(
                    onDismissRequest = { showFilters = false },
                    filters = mangaFilters,
                    onReset = {
                        screenModel.resetFilters()
                    },
                    onFilter = {
                        mangaFilters.writeBackToAnime()
                        showFilters = false
                        screenModel.search(searchQuery.orEmpty())
                    },
                    onUpdate = {
                        mangaFilters.writeBackToAnime()
                        mangaFilters = state.filters.toMangaFilterList()
                    },
                    startExpanded = false,
                    savedSearches = persistentListOf(),
                    onSave = {},
                    onSavedSearch = {},
                    onSavedSearchPress = {},
                    onSavedSearchPressDesc = "",
                    shouldShowSavingButton = false,
                    openMangaDexRandom = null,
                    openMangaDexFollows = null,
                )
            }
        }
    }
}

@Composable
private fun ListingChips(
    state: AnimeSourceScreenModel.State,
    supportsLatest: Boolean,
    onPopular: () -> Unit,
    onLatest: () -> Unit,
    onFilter: () -> Unit,
    tabs: SumizuriTabs? = null,
    onCatalog: (String) -> Unit = {},
) {
    val selectedCatalog = (state.listing as? AnimeSourceScreenModel.Listing.Search)
        ?.takeIf { it.query.isBlank() }
        ?.let { tabs?.let { t -> runCatching { t.selectedCatalog(state.filters) }.getOrNull() } }
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        // KMK: tab catalogs replace Popular and Latest
        if (tabs?.hasCatalogTabs != true) {
            FilterChip(
                selected = state.listing == AnimeSourceScreenModel.Listing.Popular,
                onClick = onPopular,
                leadingIcon = {
                    Icon(Icons.Outlined.Favorite, null, Modifier.size(FilterChipDefaults.IconSize))
                },
                label = { Text(text = tabs?.tabNames?.popular ?: stringResource(MR.strings.popular)) },
            )
            if (supportsLatest) {
                FilterChip(
                    selected = state.listing == AnimeSourceScreenModel.Listing.Latest,
                    onClick = onLatest,
                    leadingIcon = {
                        Icon(Icons.Outlined.NewReleases, null, Modifier.size(FilterChipDefaults.IconSize))
                    },
                    label = { Text(text = tabs?.tabNames?.latest ?: stringResource(MR.strings.latest)) },
                )
            }
        }
        tabs?.catalogTabs.orEmpty().forEach { catalog ->
            FilterChip(
                selected = selectedCatalog == catalog.id,
                onClick = { onCatalog(catalog.id) },
                label = { Text(text = catalog.name) },
            )
        }
        FilterChip(
            selected = state.listing is AnimeSourceScreenModel.Listing.Search && selectedCatalog == null,
            onClick = onFilter,
            leadingIcon = {
                Icon(Icons.Outlined.FilterList, null, Modifier.size(FilterChipDefaults.IconSize))
            },
            label = {
                Text(
                    text = if (state.filters.list.isNotEmpty()) {
                        stringResource(MR.strings.action_filter)
                    } else {
                        stringResource(MR.strings.action_search)
                    },
                )
            },
        )
    }
}

@Composable
private fun AnimeBrowseContent(
    state: AnimeSourceScreenModel.State,
    displayMode: LibraryDisplayMode,
    columns: GridCells,
    contentPadding: PaddingValues,
    selectedId: Long?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClick: (Anime) -> Unit,
    onLongClick: (Anime) -> Unit,
) {
    if (displayMode == LibraryDisplayMode.List) {
        val listState = rememberLazyListState()
        LoadMoreEffect(listState, state.items.size, onLoadMore)
        LazyColumn(state = listState, contentPadding = contentPadding) {
            items(state.items, key = { it.id }) { anime ->
                MangaListItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title,
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                    badge = { InLibraryBadge(enabled = anime.favorite) },
                    isSelected = anime.id == selectedId,
                    coverAlpha = if (anime.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                )
            }
            item(key = "footer") { Footer(state, onRetry) }
        }
        return
    }
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState, state.items.size, onLoadMore)
    // KMK: the source's Panorama style; wide covers take two places in the "Wide" style
    val panoramaStyle by remember {
        Injekt.get<eu.kanade.domain.ui.UiPreferences>().browsePanoramaStyle()
    }.collectAsState()
    val usePanorama = displayMode == LibraryDisplayMode.ComfortableGridPanorama
    val doubleWide = usePanorama && panoramaStyle == eu.kanade.domain.ui.model.PanoramaStyle.WIDE
    // Read here, not inside the span lambda
    val wideCovers = eu.kanade.presentation.components.rememberWideCovers()
    val wide = wideCovers.ids
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current) + 8.dp,
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current) + 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(
            state.items,
            key = { it.id },
            span = { anime -> wideSpan(doubleWide, wide, anime.id) },
        ) { anime ->
            val coverAlpha = if (anime.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f
            when (displayMode) {
                LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                    isSelected = anime.id == selectedId,
                    coverAlpha = coverAlpha,
                    coverBadgeStart = { InLibraryBadge(enabled = anime.favorite) },
                )
                else -> MangaComfortableGridItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title,
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                    isSelected = anime.id == selectedId,
                    coverAlpha = coverAlpha,
                    coverBadgeStart = { InLibraryBadge(enabled = anime.favorite) },
                    usePanoramaCover = usePanorama,
                    panoramaStyle = panoramaStyle,
                    onCoverRatio = { if (doubleWide) wideCovers.report(anime.id, it) },
                )
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { Footer(state, onRetry) }
    }
}

@Composable
private fun LoadMoreEffect(gridState: LazyGridState, itemCount: Int, onLoadMore: () -> Unit) {
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, itemCount) {
        if (shouldLoadMore) onLoadMore()
    }
}

@Composable
private fun LoadMoreEffect(listState: LazyListState, itemCount: Int, onLoadMore: () -> Unit) {
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, itemCount) {
        if (shouldLoadMore) onLoadMore()
    }
}

@Composable
private fun Footer(state: AnimeSourceScreenModel.State, onRetry: () -> Unit) {
    when {
        state.isLoading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(state.error)
            TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
        }
    }
}

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit, contentPadding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
    ) {
        Text(message)
        TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
    }
}
