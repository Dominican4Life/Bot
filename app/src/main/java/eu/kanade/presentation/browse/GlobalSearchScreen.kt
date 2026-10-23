package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.anime.anilist.AniListChipBar
import eu.kanade.tachiyomi.ui.anime.anilist.AniListFilterTab
import eu.kanade.tachiyomi.ui.anime.anilist.AniListMedia
import eu.kanade.tachiyomi.ui.anime.anilist.AniListNoticeHost
import eu.kanade.tachiyomi.ui.anime.anilist.AniListSearchModel
import eu.kanade.tachiyomi.ui.anime.anilist.AniListSearchPane
import eu.kanade.tachiyomi.ui.anime.anilist.GlobalSearchMode
import eu.kanade.tachiyomi.ui.anime.anilist.GlobalSearchModeRow
import eu.kanade.tachiyomi.ui.anime.anilist.swipeBetweenModes
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.ui.Modifier
import eu.kanade.domain.source.model.installedExtension
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableMap
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.domain.source.model.Source as DomainSource

@Composable
fun GlobalSearchScreen(
    state: SearchScreenModel.State,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onToggleResults: () -> Unit,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    // KMK -->
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
    hasPinnedSources: Boolean,
    /** The AniList tab (manga), like anime global search */
    aniList: AniListSearchModel? = null,
    onClickAniList: (AniListMedia) -> Unit = {},
    // KMK <--
) {
    // KMK -->
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
    val modePref = remember {
        Injekt.get<PreferenceStore>().getString("manga_global_search_mode", GlobalSearchMode.RESULTS.name)
    }
    val savedMode by modePref.collectAsState()
    val mode = if (aniList == null) {
        GlobalSearchMode.RESULTS
    } else {
        runCatching { GlobalSearchMode.valueOf(savedMode) }.getOrDefault(GlobalSearchMode.RESULTS)
    }
    var openFilter by rememberSaveable { mutableStateOf<AniListFilterTab?>(null) }
    val selectMode: (GlobalSearchMode) -> Unit = { newMode ->
        modePref.set(newMode.name)
        val typed = state.searchQuery.orEmpty()
        if (newMode == GlobalSearchMode.ANILIST && aniList != null) {
            if (aniList.query != typed && aniList.traceMatches == null) aniList.search(typed)
        } else if (typed.isNotBlank() && state.items.isEmpty()) {
            onSearch(typed)
        }
    }
    LaunchedEffect(mode) {
        if (mode == GlobalSearchMode.ANILIST) aniList?.start(state.searchQuery.orEmpty())
    }
    // KMK <--

    Scaffold(
        topBar = { scrollBehavior ->
            // KMK -->
            if (bulkFavoriteState.selectionMode) {
                BulkSelectionToolbar(
                    selectedCount = bulkFavoriteState.selection.size,
                    isRunning = bulkFavoriteState.isRunning,
                    onClickClearSelection = bulkFavoriteScreenModel::toggleSelectionMode,
                    onChangeCategoryClick = bulkFavoriteScreenModel::addFavorite,
                    onSelectAll = {
                        state.filteredItems.values
                            .filterIsInstance<SearchItemResult.Success>()
                            .flatMap { it.result }
                            .forEach { bulkFavoriteScreenModel.select(it) }
                    },
                    onReverseSelection = {
                        state.filteredItems.values
                            .filterIsInstance<SearchItemResult.Success>()
                            .flatMap { it.result }
                            .let { bulkFavoriteScreenModel.reverseSelection(it) }
                    },
                )
            } else {
                // KMK <--
                GlobalSearchToolbar(
                    searchQuery = state.searchQuery,
                    progress = state.progress,
                    total = state.total,
                    navigateUp = navigateUp,
                    onChangeSearchQuery = onChangeSearchQuery,
                    // KMK: the AniList tab searches AniList
                    onSearch = { if (mode == GlobalSearchMode.ANILIST) aniList?.search(it) else onSearch(it) },
                    hideSourceFilter = false,
                    sourceFilter = state.sourceFilter,
                    onChangeSearchFilter = onChangeSearchFilter,
                    onlyShowHasResults = state.onlyShowHasResults,
                    onToggleResults = onToggleResults,
                    scrollBehavior = scrollBehavior,
                    // KMK -->
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                    isRunning = bulkFavoriteState.isRunning,
                    hasPinnedSources = hasPinnedSources,
                    modeRow = aniList?.let { model ->
                        {
                            Column {
                                GlobalSearchModeRow(
                                    mode = mode,
                                    onMode = selectMode,
                                    pinnedOnly = (state.sourceFilter == SourceFilter.PinnedOnly).takeIf { hasPinnedSources },
                                    onPinnedOnly = {
                                        onChangeSearchFilter(if (it) SourceFilter.PinnedOnly else SourceFilter.All)
                                    },
                                    onlyShowHasResults = state.onlyShowHasResults,
                                    onToggleHasResults = onToggleResults,
                                    openFilter = openFilter,
                                    onOpenFilter = { openFilter = it },
                                    onRefreshGenres = { model.refreshGenres() },
                                    // trace.moe only knows anime
                                    onScreenshot = null,
                                )
                                if (mode == GlobalSearchMode.ANILIST) AniListChipBar(model = model)
                            }
                        }
                    },
                    // KMK <--
                )
            }
        },
    ) { paddingValues ->
        // KMK -->
        if (aniList != null && mode == GlobalSearchMode.ANILIST) {
            Box(modifier = Modifier.fillMaxSize().swipeBetweenModes(mode, selectMode)) {
                AniListSearchPane(
                    model = aniList,
                    openFilter = openFilter,
                    onOpenFilter = { openFilter = it },
                    onClick = onClickAniList,
                    modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
                    bottomPadding = paddingValues.calculateBottomPadding(),
                )
                AniListNoticeHost(notice = aniList.notice, onDismiss = aniList::dismissNotice)
            }
            return@Scaffold
        }
        // KMK <--
        GlobalSearchContent(
            items = state.filteredItems,
            contentPadding = paddingValues,
            getManga = getManga,
            onClickSource = onClickSource,
            onClickItem = onClickItem,
            onLongClickItem = onLongClickItem,
            // KMK -->
            selection = bulkFavoriteState.selection,
            modifier = if (aniList != null) Modifier.swipeBetweenModes(mode, selectMode) else Modifier,
            // KMK <--
        )
    }
}

@Composable
internal fun GlobalSearchContent(
    items: ImmutableMap<Source, SearchItemResult>,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (Source) -> Unit,
    onClickItem: (Manga) -> Unit,
    onLongClickItem: (Manga) -> Unit,
    fromSourceId: Long? = null,
    // KMK -->
    selection: List<Manga>,
    modifier: Modifier = Modifier,
    // KMK <--
) {
    LazyColumn(
        contentPadding = contentPadding,
        // KMK
        modifier = modifier,
    ) {
        items.forEach { (source, result) ->
            item(key = "global-search-${source.id}") {
                // KMK -->
                val domainSource = DomainSource(
                    source.id,
                    "",
                    "",
                    supportsLatest = false,
                    isStub = false,
                )
                // KMK <--

                GlobalSearchResultItem(
                    title = (
                        fromSourceId?.let {
                            "▶ ${source.name}".takeIf { source.id == fromSourceId }
                        } ?: source.name
                        ) +
                        // KMK -->
                        (
                            domainSource.installedExtension?.let { extension ->
                                " (${extension.name})".takeIf { extension.name != source.name }
                            } ?: ""
                            ),
                    // KMK <--
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { onClickSource(source) },
                    modifier = Modifier.animateItem(),
                ) {
                    when (result) {
                        SearchItemResult.Loading -> {
                            GlobalSearchLoadingResultItem()
                        }
                        is SearchItemResult.Success -> {
                            GlobalSearchCardRow(
                                titles = result.result,
                                getManga = getManga,
                                onClick = onClickItem,
                                onLongClick = onLongClickItem,
                                // KMK -->
                                selection = selection,
                                // KMK <--
                            )
                        }
                        is SearchItemResult.Error -> {
                            GlobalSearchErrorResultItem(message = result.throwable.message)
                        }
                    }
                }
            }
        }
    }
}
