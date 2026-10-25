package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
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
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseChapterPanel
import eu.kanade.presentation.browse.components.BrowseOverlayPanel
import eu.kanade.presentation.browse.components.BrowsePagePanel
import eu.kanade.presentation.browse.components.BrowsePreviewInfoHeader
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.BrowseTabChip
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.PreviewPanelButtons
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
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.jssource.JsTabs
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.SourceSettingsContent
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.Manga
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
        // Which panels open on the first tap of a cover (set with the Left Panel / Right Panel buttons)
        val previewPanelsPreference = remember { Injekt.get<SourcePreferences>().browsePreviewPanels() }
        val previewPanels by previewPanelsPreference.collectAsState()
        // Filter (left) and Settings (right) slide in over the covers
        val filterPanelOpen = state.dialog is BrowseSourceScreenModel.Dialog.Filter
        var settingsPanelOpen by rememberSaveable { mutableStateOf(false) }
        val closeSidePanels = {
            settingsPanelOpen = false
            if (filterPanelOpen) screenModel.setDialog(null)
        }
        // Without close buttons, back closes the panels
        BackHandler(enabled = previewState.manga != null && !bulkFavoriteState.selectionMode) {
            previewScreenModel.clear()
        }
        // Registered after, so Back closes Filter or Settings first
        BackHandler(enabled = settingsPanelOpen) { settingsPanelOpen = false }
        BackHandler(enabled = filterPanelOpen) { screenModel.setDialog(null) }
        // Same as long-pressing a cover: add to or remove from the library
        val toggleFavorite: (Manga) -> Unit = { manga ->
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

        // Each kept tab (Popular, Latest, a JS source's tab, a saved search) remembers where it was scrolled to, so
        // switching back puts it there again; anything else starts at the top
        val tabCacheKey = screenModel.tabCacheKey(state.listing)
        val scrollKey: Any = tabCacheKey ?: state.listing
        val savedScroll = remember(scrollKey, state.reloadToken) { tabCacheKey?.let { BrowseTabCache.scroll(it) } }
        val gridState = key(scrollKey, state.reloadToken) {
            rememberLazyGridState(savedScroll?.first ?: 0, savedScroll?.second ?: 0)
        }
        val listState = key(scrollKey, state.reloadToken) {
            rememberLazyListState(savedScroll?.first ?: 0, savedScroll?.second ?: 0)
        }
        LaunchedEffect(gridState, listState, tabCacheKey) {
            val cacheKey = tabCacheKey ?: return@LaunchedEffect
            merge(
                snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset },
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset },
            ).collect { (index, offset) -> BrowseTabCache.saveScroll(cacheKey, index, offset) }
        }

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
                                incognito = screenModel.incognitoMode.value,
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

                        // KMK: a JS / JSON source's tab catalogs, as buttons
                        val sourceTabs = screenModel.source as? JsTabs
                        val selectedCatalog = (state.listing as? Listing.Search)
                            ?.takeIf { it.savedSearchId == null && it.query.isNullOrBlank() }
                            ?.let { listing ->
                                sourceTabs?.let { runCatching { it.selectedCatalog(listing.filters) }.getOrNull() }
                            }
                        // KMK -->
                        // The selected button shows its name, the others only their icon. Filter and Settings
                        // open panels over the covers; the Left / Right Panel buttons sit at the far end.
                        val searchListing = state.listing as? Listing.Search
                        val filterSelected = filterPanelOpen ||
                            (
                                !settingsPanelOpen &&
                                    searchListing != null &&
                                    searchListing.savedSearchId == null &&
                                    selectedCatalog == null
                                )
                        val panelOpen = filterPanelOpen || settingsPanelOpen
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = MaterialTheme.padding.small),
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Tab catalogs replace Popular and Latest
                                if (sourceTabs?.hasCatalogTabs != true) {
                                    BrowseTabChip(
                                        selected = !panelOpen && state.listing == Listing.Popular,
                                        label = sourceTabs?.popularName ?: stringResource(MR.strings.popular),
                                        icon = Icons.Outlined.FavoriteBorder,
                                        filledIcon = Icons.Outlined.Favorite,
                                        onClick = {
                                            if (!panelOpen && state.listing == Listing.Popular) {
                                                // Tapping the selected tab loads it again from page 1
                                                screenModel.reloadListing()
                                            } else {
                                                closeSidePanels()
                                                screenModel.setListing(Listing.Popular)
                                            }
                                        },
                                    )
                                    if (screenModel.source.supportsLatest) {
                                        BrowseTabChip(
                                            selected = !panelOpen && state.listing == Listing.Latest,
                                            label = sourceTabs?.latestName ?: stringResource(MR.strings.latest),
                                            icon = Icons.Outlined.NewReleases,
                                            animatedIcon = AnimatedImageVector.animatedVectorResource(
                                                R.drawable.anim_updates_enter,
                                            ),
                                            onClick = {
                                                if (!panelOpen && state.listing == Listing.Latest) {
                                                    screenModel.reloadListing()
                                                } else {
                                                    closeSidePanels()
                                                    screenModel.setListing(Listing.Latest)
                                                }
                                            },
                                        )
                                    }
                                }
                                sourceTabs?.catalogTabs.orEmpty().forEach { catalog ->
                                    BrowseTabChip(
                                        selected = !panelOpen && selectedCatalog == catalog.id,
                                        label = catalog.name,
                                        icon = null,
                                        onClick = {
                                            if (!panelOpen && selectedCatalog == catalog.id) {
                                                screenModel.reloadListing()
                                            } else {
                                                closeSidePanels()
                                                screenModel.showCatalog(catalog.id)
                                            }
                                        },
                                    )
                                }
                                // Always there: a source without filters can still save its list for Feed
                                BrowseTabChip(
                                    selected = filterSelected,
                                    label = stringResource(MR.strings.action_filter),
                                    icon = Icons.Outlined.FilterList,
                                    onClick = {
                                        if (filterPanelOpen) {
                                            screenModel.setDialog(null)
                                        } else {
                                            settingsPanelOpen = false
                                            // KMK: back to the Filter tab's results (if any) behind the panel
                                            screenModel.showFilterTab()
                                            screenModel.openFilterSheet()
                                        }
                                    },
                                )
                                if (isConfigurableSource) {
                                    BrowseTabChip(
                                        selected = settingsPanelOpen,
                                        label = stringResource(MR.strings.action_settings),
                                        icon = Icons.Outlined.Settings,
                                        onClick = {
                                            when {
                                                // E-Hentai's settings are a whole section of the app's settings
                                                screenModel.source.isEhBasedSource() && isHentaiEnabled ->
                                                    navigator.push(SettingsEhScreen)
                                                settingsPanelOpen -> settingsPanelOpen = false
                                                else -> {
                                                    if (filterPanelOpen) screenModel.setDialog(null)
                                                    settingsPanelOpen = true
                                                }
                                            }
                                        },
                                    )
                                }
                                state.savedSearches.forEach { savedSearch ->
                                    BrowseTabChip(
                                        selected = !panelOpen &&
                                            searchListing?.savedSearchId == savedSearch.id,
                                        label = savedSearch.name,
                                        icon = null,
                                        onClick = {
                                            if (!panelOpen && searchListing?.savedSearchId == savedSearch.id) {
                                                screenModel.reloadListing()
                                                return@BrowseTabChip
                                            }
                                            closeSidePanels()
                                            screenModel.onSavedSearch(savedSearch) {
                                                context.toast(it)
                                            }
                                        },
                                    )
                                }
                            }
                            if (previewEnabled) {
                                Row(
                                    modifier = Modifier.padding(end = MaterialTheme.padding.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PreviewPanelButtons(
                                        leftOn = SourcePreferences.previewLeftOn(previewPanels),
                                        rightOn = SourcePreferences.previewRightOn(previewPanels),
                                        onLeftChange = { on ->
                                            previewPanelsPreference.set(
                                                SourcePreferences.previewPanels(
                                                    left = on,
                                                    right = SourcePreferences.previewRightOn(previewPanels),
                                                ),
                                            )
                                            if (on) {
                                                previewScreenModel.openChapterPanel()
                                            } else {
                                                previewScreenModel.closeChapterPanel()
                                            }
                                        },
                                        onRightChange = { on ->
                                            previewPanelsPreference.set(
                                                SourcePreferences.previewPanels(
                                                    left = SourcePreferences.previewLeftOn(previewPanels),
                                                    right = on,
                                                ),
                                            )
                                            if (on) {
                                                previewScreenModel.openPagePanel()
                                            } else {
                                                previewScreenModel.closePagePanel()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // KMK <--

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
                    // KMK: swipe left / right over the covers for the next / previous button of the row (Popular,
                    // Latest, a JS source's tabs, saved searches)
                    val tabsSource = screenModel.source as? JsTabs
                    val currentCatalog = (state.listing as? Listing.Search)
                        ?.takeIf { it.savedSearchId == null && it.query.isNullOrBlank() }
                        ?.let { l -> tabsSource?.let { runCatching { it.selectedCatalog(l.filters) }.getOrNull() } }
                    val swipeTabs = buildList<Pair<Boolean, () -> Unit>> {
                        if (tabsSource?.hasCatalogTabs != true) {
                            add(
                                (state.listing == Listing.Popular) to {
                                    screenModel.setListing(Listing.Popular)
                                },
                            )
                            if (screenModel.source.supportsLatest) {
                                add(
                                    (state.listing == Listing.Latest) to {
                                        screenModel.setListing(Listing.Latest)
                                    },
                                )
                            }
                        }
                        // The Filter tab, once there's a filter or typed search (until Clear)
                        if (state.filterListing != null) {
                            add(
                                ((state.listing as? Listing.Search)?.tabKey == Listing.FILTER_TAB) to {
                                    screenModel.showFilterTab()
                                },
                            )
                        }
                        tabsSource?.catalogTabs.orEmpty().forEach { catalog ->
                            add((currentCatalog == catalog.id) to { screenModel.showCatalog(catalog.id) })
                        }
                        state.savedSearches.forEach { savedSearch ->
                            add(
                                ((state.listing as? Listing.Search)?.savedSearchId == savedSearch.id) to {
                                    screenModel.onSavedSearch(savedSearch) { context.toast(it) }
                                },
                            )
                        }
                    }
                    val currentTab = swipeTabs.indexOfFirst { it.first }
                    val latestSwipeTabs by rememberUpdatedState(swipeTabs)
                    val swipeModifier = if (currentTab >= 0 && !filterPanelOpen && !settingsPanelOpen) {
                        Modifier.pointerInput(currentTab, swipeTabs.size) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    val threshold = 96.dp.toPx()
                                    val target = when {
                                        total <= -threshold -> currentTab + 1
                                        total >= threshold -> currentTab - 1
                                        else -> -1
                                    }
                                    latestSwipeTabs.getOrNull(target)?.second?.invoke()
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    total += dragAmount
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                    Box(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
                        BrowseSourceContent(
                            source = screenModel.source,
                            mangaList = mangaList,
                            // KMK -->
                            gridState = gridState,
                            listState = listState,
                            // KMK <--
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
                                } else if (
                                    previewEnabled &&
                                    previewPanels != SourcePreferences.PREVIEW_PANELS_NONE &&
                                    previewScreenModel.state.value.manga?.id != manga.id
                                ) {
                                    // First tap: highlight and preview. Second tap on the same cover: open it.
                                    previewScreenModel.select(
                                        manga,
                                        showChapters = SourcePreferences.previewLeftOn(previewPanels),
                                        showPages = SourcePreferences.previewRightOn(previewPanels),
                                    )
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
                                    toggleFavorite(manga)
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
                    }

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
                            onRefresh = previewScreenModel::refresh,
                            infoHeader = { manga ->
                                BrowsePreviewInfoHeader(
                                    manga = manga,
                                    source = screenModel.source,
                                    onAddToLibrary = { toggleFavorite(manga) },
                                    onWebView = {
                                        val url = (screenModel.source as? HttpSource)
                                            ?.let { runCatching { it.getMangaUrl(manga.toSManga()) }.getOrNull() }
                                        if (url != null) {
                                            navigator.push(
                                                WebViewScreen(url = url, initialTitle = manga.title, sourceId = manga.source),
                                            )
                                        }
                                    },
                                    onOpenDetails = { navigator.push(MangaScreen(manga.id, fromSource = true)) },
                                    onSearch = { query, global ->
                                        if (global) {
                                            navigator.push(GlobalSearchScreen(query))
                                        } else {
                                            screenModel.search(query)
                                        }
                                    },
                                )
                            },
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
                        var filterVersion by remember { mutableIntStateOf(0) }
                        BrowseOverlayPanel(
                            visible = filterPanelOpen,
                            fromStart = true,
                            title = stringResource(MR.strings.action_filter),
                            onDismiss = { screenModel.setDialog(null) },
                        ) {
                            SourceFilterContent(
                                onDismissRequest = { screenModel.setDialog(null) },
                                filters = state.filters,
                                // KMK: Clear empties the panel and forgets the Filter tab
                                onReset = {
                                    screenModel.clearFilterTab()
                                    filterVersion++
                                },
                                onFilter = { screenModel.search(filters = state.filters) },
                                onUpdate = {
                                    screenModel.setFilters(it)
                                    filterVersion++
                                },
                                clearHighlighted = remember(filterVersion, state.filters, filterPanelOpen) {
                                    screenModel.filtersChanged()
                                },
                                startExpanded = screenModel.startExpanded,
                                savedSearches = state.savedSearches,
                                onSave = screenModel::onSaveSearch,
                                onSavedSearch = { search ->
                                    screenModel.onSavedSearch(search) {
                                        context.toast(it)
                                    }
                                },
                                onSavedSearchPress = screenModel::onSavedSearchPress,
                                onSavedSearchPressDesc = stringResource(KMR.strings.saved_searches_delete),
                                shouldShowSavingButton = true,
                                openMangaDexRandom = if (screenModel.source.isMdBasedSource()) {
                                    {
                                        screenModel.onMangaDexRandom {
                                            navigator.replace(BrowseSourceScreen(sourceId, "id:$it"))
                                        }
                                    }
                                } else {
                                    null
                                },
                                openMangaDexFollows = if (screenModel.source.isMdBasedSource()) {
                                    { navigator.push(MangaDexFollowsScreen(sourceId)) }
                                } else {
                                    null
                                },
                            )
                        }
                        BrowseOverlayPanel(
                            visible = settingsPanelOpen,
                            fromStart = false,
                            title = stringResource(MR.strings.source_settings),
                            onDismiss = { settingsPanelOpen = false },
                        ) {
                            if (settingsPanelOpen) {
                                SourceSettingsContent(
                                    sourceId = sourceId,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
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
            // KMK: Filter is a panel over the covers (above)
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
