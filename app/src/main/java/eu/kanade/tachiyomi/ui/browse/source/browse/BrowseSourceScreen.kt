package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseChapterPanel
import eu.kanade.presentation.browse.components.BrowsePagePanel
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.browse.components.SavedSearchCreateDialog
import eu.kanade.presentation.browse.components.SavedSearchDeleteDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.more.settings.screen.SettingsEhScreen
import eu.kanade.presentation.reader.sidePanelWidth
import eu.kanade.presentation.theme.CoverSeedTheme
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import exh.md.follows.MangaDexFollowsScreen
import exh.source.ExhPreferences
import exh.source.anyIs
import exh.source.isEhBasedSource
import exh.source.isMdBasedSource
import exh.ui.smartsearch.SmartSearchScreen
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    // SY -->
    private val filtersJson: String? = null,
    private val savedSearch: Long? = null,
    /** being set when called from [SmartSearchScreen] or when click on a manga from this screen
     * which was previously opened from `SmartSearchScreen` */
    private val smartSearchConfig: SourcesScreen.SmartSearchConfig? = null,
    // SY <--
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel {
            BrowseSourceScreenModel(
                sourceId = sourceId,
                listingQuery = listingQuery,
                // SY -->
                filtersJson = filtersJson,
                savedSearch = savedSearch,
                // SY <--
            )
        }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        // SY -->
        val context = LocalContext.current
        // SY <--

        // KMK -->
        screenModel.source.let {
            // KMK <--
            if (it is StubSource) {
                MissingSourceScreen(
                    source = it,
                    navigateUp = navigateUp,
                )
                return
            }
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.getHomeUrl(),
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        // KMK -->
        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteScreenModel() }
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

        BackHandler(enabled = bulkFavoriteState.selectionMode) {
            bulkFavoriteScreenModel.backHandler()
        }

        // Tapping a cover highlights it and opens the chapter (left) and page (right) preview panels
        val previewScreenModel = rememberScreenModel { BrowsePreviewScreenModel(screenModel.source) }
        val previewState by previewScreenModel.state.collectAsState()
        val previewEnabled = smartSearchConfig == null
        var showChapterSettings by remember { mutableStateOf(false) }
        var showScanlatorFilter by remember { mutableStateOf(false) }

        // Covers per row, adjustable from the display mode menu
        val orientation = LocalConfiguration.current.orientation
        val columnsPreference = remember(orientation) { screenModel.browseColumnsPreference(orientation) }
        val columnsValue by columnsPreference.collectAsState()
        val columnCount = screenModel.resolveBrowseColumns(columnsValue, orientation)
        // KMK <--

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.getHomeUrl()
        }

        // KMK -->
        val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()

        val isHentaiEnabled: Boolean = Injekt.get<ExhPreferences>().isHentaiEnabled().get()
        val isConfigurableSource = screenModel.source.anyIs<ConfigurableSource>() ||
            (screenModel.source.isEhBasedSource() && isHentaiEnabled)
        // KMK <--

        // KMK -->
        // The whole screen takes on the selected cover's colors, like the details page
        CoverSeedTheme(seedColor = previewState.seedColor) {
        // KMK <--
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) {},
                    ) {
                        // KMK -->
                        if (bulkFavoriteState.selectionMode) {
                            BulkSelectionToolbar(
                                selectedCount = bulkFavoriteState.selection.size,
                                isRunning = bulkFavoriteState.isRunning,
                                onClickClearSelection = bulkFavoriteScreenModel::toggleSelectionMode,
                                onChangeCategoryClick = bulkFavoriteScreenModel::addFavorite,
                                onSelectAll = {
                                    mangaList.itemSnapshotList.items
                                        .map { it.value.first }
                                        .forEach { bulkFavoriteScreenModel.select(it) }
                                },
                                onReverseSelection = {
                                    mangaList.itemSnapshotList.items
                                        .map { it.value.first }
                                        .let { bulkFavoriteScreenModel.reverseSelection(it) }
                                },
                            )
                        } else {
                            // KMK <--
                            BrowseSourceToolbar(
                                searchQuery = state.toolbarQuery,
                                onSearchQueryChange = screenModel::setToolbarQuery,
                                source = screenModel.source,
                                displayMode = screenModel.displayMode
                                    // KMK -->
                                    .takeIf {
                                        !screenModel.source.isEhBasedSource() || !screenModel.ehentaiBrowseDisplayMode
                                    },
                                // KMK <--
                                onDisplayModeChange = { screenModel.displayMode = it },
                                navigateUp = navigateUp,
                                onWebViewClick = onWebViewClick,
                                onHelpClick = onHelpClick,
                                // KMK -->
                                onToggleIncognito = screenModel::toggleIncognitoMode,
                                onSettingsClick = {
                                    when {
                                        screenModel.source.isEhBasedSource() && isHentaiEnabled ->
                                            navigator.push(SettingsEhScreen)
                                        screenModel.source.anyIs<ConfigurableSource>() ->
                                            navigator.push(SourcePreferencesScreen(sourceId))
                                        else -> {}
                                    }
                                }.takeIf { isConfigurableSource },
                                // KMK <--
                                onSearch = screenModel::search,
                                // KMK -->
                                toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                                isRunning = bulkFavoriteState.isRunning,
                                columns = columnCount,
                                onColumnsChange = columnsPreference::set,
                                // KMK <--
                            )
                        }

                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            FilterChip(
                                selected = state.listing == Listing.Popular,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Popular)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.popular))
                                },
                            )
                            if (screenModel.source.supportsLatest) {
                                FilterChip(
                                    selected = state.listing == Listing.Latest,
                                    onClick = {
                                        screenModel.resetFilters()
                                        screenModel.setListing(Listing.Latest)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.NewReleases,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        Text(text = stringResource(MR.strings.latest))
                                    },
                                )
                            }
                            if (/* SY --> */ state.filterable /* SY <-- */) {
                                FilterChip(
                                    selected = state.listing is Listing.Search &&
                                        // KMK -->
                                        (state.listing as Listing.Search).savedSearchId == null,
                                    // KMK <--
                                    onClick = screenModel::openFilterSheet,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = {
                                        // SY -->
                                        Text(
                                            text = if (state.filters.isNotEmpty()) {
                                                stringResource(MR.strings.action_filter)
                                            } else {
                                                stringResource(MR.strings.action_search)
                                            },
                                        )
                                        // SY <--
                                    },
                                )
                            }
                            // KMK -->
                            state.savedSearches.forEach { savedSearch ->
                                FilterChip(
                                    selected = state.listing is Listing.Search &&
                                        (state.listing as Listing.Search).savedSearchId == savedSearch.id,
                                    onClick = {
                                        screenModel.onSavedSearch(savedSearch) {
                                            context.toast(it)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = savedSearch.name,
                                        )
                                    },
                                )
                            }
                            // KMK <--
                        }

                        HorizontalDivider()
                    }
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { paddingValues ->
                // KMK -->
                val layoutDirection = LocalLayoutDirection.current
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Shrink the grid to the space between the open panels so no cover is hidden behind them
                    val panelWidth = sidePanelWidth(maxWidth)
                    val startPadding by animateDpAsState(
                        if (previewState.chapterPanelOpen) panelWidth else 0.dp,
                        label = "browseStartPadding",
                    )
                    val endPadding by animateDpAsState(
                        if (previewState.pagePanelOpen) panelWidth else 0.dp,
                        label = "browseEndPadding",
                    )
                    val gridPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding(),
                        start = paddingValues.calculateStartPadding(layoutDirection) + startPadding,
                        end = paddingValues.calculateEndPadding(layoutDirection) + endPadding,
                    )
                    // KMK <--
                    BrowseSourceContent(
                        source = screenModel.source,
                        mangaList = mangaList,
                        columns = screenModel.columnsToGridCells(columnCount),
                        // SY -->
                        ehentaiBrowseDisplayMode = screenModel.ehentaiBrowseDisplayMode,
                        // SY <--
                        displayMode = screenModel.displayMode,
                        snackbarHostState = snackbarHostState,
                        contentPadding = gridPadding,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                        onLocalSourceHelpClick = onHelpClick,
                        onMangaClick = { manga ->
                            // KMK -->
                            if (bulkFavoriteState.selectionMode) {
                                bulkFavoriteScreenModel.toggleSelection(manga)
                            } else if (previewEnabled && previewScreenModel.state.value.manga?.id != manga.id) {
                                // First tap: highlight and preview. Second tap on the same cover: open it.
                                previewScreenModel.select(manga)
                            } else {
                                // KMK <--
                                navigator.push(
                                    MangaScreen(
                                        mangaId = manga.id,
                                        // KMK -->
                                        // Finding the entry to be merged to, so we don't want to expand description
                                        // so that user can see the `Merge to another` button
                                        fromSource = smartSearchConfig == null,
                                        // KMK <--
                                        smartSearchConfig = smartSearchConfig,
                                    ),
                                )
                            }
                        },
                        onMangaLongClick = { manga ->
                            // KMK -->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (bulkFavoriteState.selectionMode) {
                                navigator.push(MangaScreen(manga.id, true))
                            } else {
                                // KMK <--
                                scope.launchIO {
                                    val duplicates = screenModel.getDuplicateLibraryManga(manga)
                                    when {
                                        manga.favorite -> screenModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                                        duplicates.isNotEmpty() -> screenModel.setDialog(
                                            BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                                        )
                                        else -> screenModel.addFavorite(manga)
                                    }
                                }
                            }
                        },
                        // KMK -->
                        selection = if (bulkFavoriteState.selectionMode) {
                            bulkFavoriteState.selection
                        } else {
                            listOfNotNull(previewState.manga)
                        },
                        // KMK <--
                    )

                    // KMK -->
                    // The panels start below the toolbar and the Popular / Latest / Filter row
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding()),
                    ) {
                        BrowseChapterPanel(
                            state = previewState,
                            onClose = previewScreenModel::closeChapterPanel,
                            onSelectChapter = previewScreenModel::selectChapter,
                            onOpenChapter = { chapter ->
                                context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
                            },
                            onToggleRead = previewScreenModel::toggleRead,
                            onToggleBookmark = previewScreenModel::toggleBookmark,
                            onChapterDownloadAction = previewScreenModel::runChapterDownloadAction,
                            onDownloadAction = previewScreenModel::runDownloadAction,
                            onOpenFilters = { showChapterSettings = true },
                        )
                        BrowsePagePanel(
                            state = previewState,
                            source = previewScreenModel.httpSource,
                            onClose = previewScreenModel::closePagePanel,
                            onRetry = { previewState.selectedChapter?.let(previewScreenModel::selectChapter) },
                            onOpenPage = { chapter, pageIndex ->
                                context.startActivity(
                                    ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, pageIndex),
                                )
                            },
                        )
                    }

                    if (showChapterSettings) {
                        ChapterSettingsDialog(
                            onDismissRequest = { showChapterSettings = false },
                            manga = previewState.manga,
                            onDownloadFilterChanged = previewScreenModel::setDownloadedFilter,
                            onUnreadFilterChanged = previewScreenModel::setUnreadFilter,
                            onBookmarkedFilterChanged = previewScreenModel::setBookmarkedFilter,
                            scanlatorFilterActive = previewState.scanlatorFilterActive,
                            onScanlatorFilterClicked = { showScanlatorFilter = true },
                            onSortModeChanged = previewScreenModel::setSorting,
                            onDisplayModeChanged = previewScreenModel::setDisplayMode,
                            onSetAsDefault = previewScreenModel::setCurrentSettingsAsDefault,
                            onResetToDefault = previewScreenModel::resetToDefaultSettings,
                        )
                    }
                    if (showScanlatorFilter) {
                        ScanlatorFilterDialog(
                            availableScanlators = previewState.availableScanlators.toImmutableSet(),
                            excludedScanlators = previewState.excludedScanlators.toImmutableSet(),
                            onDismissRequest = { showScanlatorFilter = false },
                            onConfirm = previewScreenModel::setExcludedScanlators,
                        )
                    }
                }
                // KMK <--
            }
        // KMK -->
        }
        // KMK <--

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                    // SY -->
                    startExpanded = screenModel.startExpanded,
                    onSave = screenModel::onSaveSearch,
                    savedSearches = state.savedSearches,
                    onSavedSearch = { search ->
                        screenModel.onSavedSearch(search) {
                            context.toast(it)
                        }
                    },
                    onSavedSearchPress = screenModel::onSavedSearchPress,
                    // KMK -->
                    onSavedSearchPressDesc = stringResource(KMR.strings.saved_searches_delete),
                    // KMK <--
                    openMangaDexRandom = if (screenModel.source.isMdBasedSource()) {
                        {
                            screenModel.onMangaDexRandom {
                                navigator.replace(
                                    BrowseSourceScreen(
                                        sourceId,
                                        "id:$it",
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    openMangaDexFollows = if (screenModel.source.isMdBasedSource()) {
                        {
                            // KMK -->
                            // navigator.replace(MangaDexFollowsScreen(sourceId))
                            navigator.push(MangaDexFollowsScreen(sourceId))
                            // KMK <--
                        }
                    } else {
                        null
                    },
                    // SY <--
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                    // KMK -->
                    targetManga = dialog.manga,
                    // KMK <--
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            is BrowseSourceScreenModel.Dialog.CreateSavedSearch -> SavedSearchCreateDialog(
                onDismissRequest = onDismissRequest,
                currentSavedSearches = dialog.currentSavedSearches,
                saveSearch = screenModel::saveSearch,
            )
            is BrowseSourceScreenModel.Dialog.DeleteSavedSearch -> SavedSearchDeleteDialog(
                onDismissRequest = onDismissRequest,
                name = dialog.name,
                deleteSavedSearch = {
                    screenModel.deleteSearch(dialog.idToDelete)
                },
            )
            else -> {}
        }

        // KMK -->
        // Bulk-favorite actions only
        BulkFavoriteDialogs(
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            dialog = bulkFavoriteState.dialog,
        )
        // KMK <--

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
