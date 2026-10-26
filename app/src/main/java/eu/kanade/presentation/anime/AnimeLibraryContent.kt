package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.Folder
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import eu.kanade.domain.ui.BadgeCorner
import eu.kanade.domain.ui.CoverBadge
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.components.ProvideCoverFillSettings
import eu.kanade.presentation.components.WideCovers
import eu.kanade.presentation.components.readingProgress
import eu.kanade.presentation.components.rememberCoverBadgeCorners
import eu.kanade.presentation.components.rememberCoverProgressBarEnabled
import eu.kanade.presentation.components.rememberWideCovers
import eu.kanade.presentation.components.wideSpan
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.LibraryTabs
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.SourceIconBadge
import eu.kanade.presentation.library.components.UnreadBadge
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeAiring
import eu.kanade.tachiyomi.data.anime.AnimeAiringRepository
import eu.kanade.tachiyomi.data.anime.AnimeLibraryStats
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackStatus
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.feature.migration.anime.AnimeMigrateSources
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * KMK: One page of the anime library: an anime category, or a group (source, status, tracking status) when the
 * library is grouped. [category] is the manga model, so the page tabs are the manga library's own.
 */
@Immutable
data class AnimeLibraryPage(
    val category: Category,
    /** Every anime of the page */
    val anime: List<Anime>,
    /** What the page shows: filtered, searched and sorted */
    val shown: List<Anime>,
)

/** KMK: everything the Anime library tab and its pages show */
@Immutable
class AnimeLibraryState(
    /** Null while the library loads */
    val favorites: List<Anime>?,
    val pages: List<AnimeLibraryPage>,
    val stats: Map<Long, AnimeLibraryStats>,
    val downloaded: Map<Long, Int>,
    val filters: AnimeLibraryFilters,
    val groupType: Int,
    val showCategoryTabs: Boolean,
    val showItemCount: Boolean,
) {
    fun page(index: Int): AnimeLibraryPage? = pages.getOrNull(index.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
}

private const val NOT_TRACKED_GROUP = 7L

/** Tracking statuses in the order the manga library shows its own */
private val trackStatusOrder = listOf(
    AnimeTrackStatus.WATCHING,
    AnimeTrackStatus.REWATCHING,
    AnimeTrackStatus.PLAN_TO_WATCH,
    AnimeTrackStatus.ON_HOLD,
    AnimeTrackStatus.COMPLETED,
    AnimeTrackStatus.DROPPED,
)

private fun trackStatusName(status: Long) = when (status) {
    AnimeTrackStatus.WATCHING -> KMR.strings.anime_track_status_watching
    AnimeTrackStatus.COMPLETED -> KMR.strings.anime_track_status_completed
    AnimeTrackStatus.ON_HOLD -> KMR.strings.anime_track_status_on_hold
    AnimeTrackStatus.DROPPED -> KMR.strings.anime_track_status_dropped
    AnimeTrackStatus.REWATCHING -> KMR.strings.anime_track_status_rewatching
    AnimeTrackStatus.PLAN_TO_WATCH -> KMR.strings.anime_track_status_plan
    else -> SYMR.strings.not_tracked
}

/** Anime statuses with their name and place, like the manga library's Status groups */
private val statusGroups = mapOf(
    SAnime.ONGOING.toLong() to (MR.strings.ongoing to 1L),
    SAnime.COMPLETED.toLong() to (MR.strings.completed to 2L),
    SAnime.PUBLISHING_FINISHED.toLong() to (MR.strings.publishing_finished to 3L),
    SAnime.LICENSED.toLong() to (MR.strings.licensed to 4L),
    SAnime.ON_HIATUS.toLong() to (MR.strings.on_hiatus to 5L),
    SAnime.CANCELLED.toLong() to (MR.strings.cancelled to 6L),
    SAnime.UPCOMING.toLong() to (MR.strings.label_upcoming to 7L),
)

private fun pageCategory(id: Long, name: String, order: Long) =
    Category(id = id, name = name, order = order, flags = 0, hidden = false)

/** KMK: loads the anime library and makes its pages, following every setting */
@Composable
fun rememberAnimeLibraryState(searchQuery: String?): AnimeLibraryState {
    val context = LocalContext.current
    val repository = remember { Injekt.get<AnimeRepository>() }
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val favorites by remember { eu.kanade.tachiyomi.ui.library.LibraryRefresh.pausedWhileRestoring(repository.subscribeFavorites()) }.collectAsState(initial = null)
    val stats by remember { eu.kanade.tachiyomi.ui.library.LibraryRefresh.pausedWhileRestoring(repository.subscribeLibraryStats()) }.collectAsState(initial = emptyMap())
    val categories by remember { repository.subscribeCategories() }.collectAsState(initial = emptyList())
    val links by remember { eu.kanade.tachiyomi.ui.library.LibraryRefresh.pausedWhileRestoring(repository.subscribeCategoryLinks()) }.collectAsState(initial = emptyMap())

    val filters = rememberAnimeLibraryFilters()
    val sortMode by remember { AnimeLibraryPrefs.sort() }.collectAsState()
    val sortAscending by remember { AnimeLibraryPrefs.sortAscending() }.collectAsState()
    val randomSeed by remember { AnimeLibraryPrefs.randomSeed() }.collectAsState()
    val groupType by remember { AnimeLibraryPrefs.groupType() }.collectAsState()
    val restoreTick by eu.kanade.tachiyomi.ui.library.LibraryRefresh.tick.collectAsState()
    val showDownloadBadge by remember(restoreTick) { AnimeLibraryPrefs.downloadBadge() }.collectAsState()
    val showCategoryTabs by remember { libraryPreferences.categoryTabs() }.collectAsState()
    val showItemCount by remember { libraryPreferences.categoryNumberOfItems() }.collectAsState()

    val downloadManager = remember { Injekt.get<AnimeDownloadManager>() }
    val downloadChanges by downloadManager.changes.collectAsState()
    val needDownloads = showDownloadBadge || filters.downloaded != tachiyomi.core.common.preference.TriState.DISABLED
    // KMK: asked again after a restore (see LibraryRefresh), and when anime sources finish loading
    val refreshTick by eu.kanade.tachiyomi.ui.library.LibraryRefresh.tick.collectAsState()
    // Look at each anime's download folder once, when downloads matter
    // KMK: not while restoring: it looked at every anime's folder again for each anime added
    val restoring by eu.kanade.tachiyomi.ui.library.LibraryRefresh.restoring.collectAsState()
    LaunchedEffect(favorites?.size, needDownloads, refreshTick, restoring) {
        if (needDownloads && !restoring) favorites?.forEach { downloadManager.refresh(it) }
    }
    val downloaded = remember(favorites, downloadChanges, needDownloads, refreshTick) {
        if (!needDownloads) {
            emptyMap()
        } else {
            favorites.orEmpty().associate { it.id to downloadManager.downloadedCount(it) }
        }
    }
    val needTracks = filters.usesTracks || sortMode == AnimeLibrarySort.TRACKER_SCORE ||
        groupType == LibraryGroup.BY_TRACK_STATUS
    val tracks by produceState(initialValue = emptyMap<Long, List<AnimeTrack>>(), favorites, needTracks) {
        value = if (needTracks) {
            runCatching { Injekt.get<AnimeTrackRepository>().getAll().groupBy { it.animeId } }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    // Next airing episodes from AniList, only while sorting by them (one batched request per 50 tracked anime)
    val airing by produceState(initialValue = emptyMap<Long, AnimeAiring>(), favorites, sortMode) {
        if (sortMode == AnimeLibrarySort.NEXT_AIRING) {
            val animes = favorites ?: return@produceState
            value = runCatching { AnimeAiringRepository.forLibrary(animes) }.getOrDefault(value)
        }
    }

    // The pages: categories, or groups of the whole library
    // Hidden anime categories (More › Categories › Anime), unless "Show hidden categories" is on
    val hiddenCategoryIds by remember { AnimeLibraryPrefs.hiddenCategories() }.collectAsState()
    val showHidden by remember { AnimeLibraryPrefs.showHiddenCategories() }.collectAsState()
    val groups = remember(favorites, categories, links, groupType, tracks, hiddenCategoryIds, showHidden) {
        val list = favorites.orEmpty()
        when (groupType) {
            LibraryGroup.BY_SOURCE -> list.groupBy { it.source }
                .map { (sourceId, animes) ->
                    val name = sourceManager.get(sourceId)?.name ?: sourceId.toString()
                    name to (sourceId to animes)
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { pair: Pair<String, Pair<Long, List<Anime>>> -> pair.first })
                .mapIndexed { index, (name, group) -> pageCategory(group.first, name, index.toLong()) to group.second }
            LibraryGroup.BY_STATUS -> list.groupBy { it.status }
                .map { (status, animes) ->
                    val (nameRes, order) = statusGroups[status] ?: (MR.strings.unknown to 8L)
                    // + 1: the "unknown" status (0) isn't the system category
                    pageCategory(status + 1, context.stringResource(nameRes), order) to animes
                }
                .sortedBy { it.first.order }
            LibraryGroup.BY_TRACK_STATUS -> {
                val byStatus = mutableMapOf<Long, MutableList<Anime>>()
                list.forEach { anime ->
                    val statuses = tracks[anime.id].orEmpty().map { it.status }.filter { it in trackStatusOrder }
                        .distinct()
                        .ifEmpty { listOf(NOT_TRACKED_GROUP) }
                    statuses.forEach { byStatus.getOrPut(it) { mutableListOf() }.add(anime) }
                }
                byStatus.map { (status, animes) ->
                    val order = trackStatusOrder.indexOf(status).takeIf { it >= 0 } ?: trackStatusOrder.size
                    pageCategory(status, context.stringResource(trackStatusName(status)), order.toLong()) to
                        animes.toList()
                }
                    .sortedBy { it.first.order }
            }
            LibraryGroup.UNGROUPED -> listOf(pageCategory(Category.UNCATEGORIZED_ID, "", 0) to list)
            else -> {
                // Categories, like the manga library: "Default" holds the anime in no category
                val uncategorized = list.filter { links[it.id].isNullOrEmpty() }
                buildList {
                    if (categories.isEmpty() || uncategorized.isNotEmpty()) {
                        add(pageCategory(Category.UNCATEGORIZED_ID, "", -1) to uncategorized)
                    }
                    categories.forEach { category ->
                        if (!showHidden && category.id.toString() in hiddenCategoryIds) return@forEach
                        add(
                            pageCategory(category.id, category.name, category.order) to
                                list.filter { category.id in links[it.id].orEmpty() },
                        )
                    }
                }
            }
        }
    }

    val pages = remember(
        groups, searchQuery, stats, downloaded, tracks, filters, sortMode, sortAscending, randomSeed, airing, links,
    ) {
        groups.map { (category, animes) ->
            val searched = if (searchQuery.isNullOrBlank()) {
                animes
            } else {
                animes.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
            val shown = searched.filterAndSortAnime(
                stats, downloaded, tracks, filters, sortMode, sortAscending, randomSeed,
                airing = airing,
                links = links,
            )
            AnimeLibraryPage(category, animes, shown)
        }
    }

    return AnimeLibraryState(
        favorites = favorites,
        pages = pages,
        stats = stats,
        downloaded = downloaded,
        filters = filters,
        groupType = groupType,
        showCategoryTabs = showCategoryTabs,
        showItemCount = showItemCount,
    )
}

/** KMK: The anime half of the library: its pages (categories or groups), swiped like the manga library's. */
@Composable
fun AnimeLibraryContent(
    state: AnimeLibraryState,
    contentPadding: PaddingValues,
    searchQuery: String?,
    currentPage: Int,
    onChangeCurrentPage: (Int) -> Unit,
    onClickAnime: (Anime) -> Unit,
    onRefresh: () -> Boolean,
    // KMK: Migrate from the selection bar
    onMigrate: ((List<Long>) -> Unit)? = null,
    // KMK --> preview panels, like Browse (see LibraryContent)
    highlighted: Long? = null,
    panelButtons: (@Composable RowScope.() -> Unit)? = null,
    panelStart: androidx.compose.ui.unit.Dp = 0.dp,
    panelEnd: androidx.compose.ui.unit.Dp = 0.dp,
    panels: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    // KMK <--
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { Injekt.get<AnimeRepository>() }
    val pages = state.pages

    // Long-press selects, like the manga library; tapping then adds or removes from the selection
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var showCategories by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val toggle: (Anime) -> Unit = { anime ->
        selected = if (anime.id in selected) selected - anime.id else selected + anime.id
    }
    val onClick: (Anime) -> Unit = { anime -> if (selected.isEmpty()) onClickAnime(anime) else toggle(anime) }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    if (showCategories) {
        AnimeBulkCategoryDialog(
            animeIds = selected.toList(),
            addToLibrary = false,
            onDismissRequest = { showCategories = false },
            onDone = { selected = emptySet() },
        )
    }
    if (showRemove) {
        AnimeRemoveDialog(
            count = selected.size,
            onDismissRequest = { showRemove = false },
            onConfirm = {
                removeAnimeFromLibrary(selected)
                selected = emptySet()
            },
        )
    }

    if (state.favorites == null) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }
    if (state.favorites.isEmpty() || pages.isEmpty()) {
        EmptyScreen(
            message = stringResource(KMR.strings.anime_library_empty),
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    val continueWatching: (Anime) -> Unit = { anime ->
        scope.launch {
            val next = repository.getEpisodes(anime.id)
                .filter { !it.seen }
                .minWithOrNull(compareBy<Episode> { it.episodeNumber }.thenByDescending { it.sourceOrder })
            if (next != null) {
                context.startActivity(PlayerActivity.newIntent(context, anime.id, next.id))
            } else {
                context.toast(KMR.strings.anime_library_no_next_episode)
            }
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val wideCovers = rememberWideCovers()
    Box(modifier = Modifier.fillMaxSize()) {
        ProvideCoverFillSettings {
            Column(
                modifier = Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
            ) {
                val pagerState = rememberPagerState(currentPage.coerceIn(0, pages.size - 1)) { pages.size }
                var isRefreshing by remember(pagerState.currentPage) { mutableStateOf(false) }

                val showPageTabs = (state.showCategoryTabs || !searchQuery.isNullOrEmpty()) &&
                    (pages.size > 1 || !pages.first().category.isSystemCategory)
                if (showPageTabs) {
                    val counts = remember(pages) { pages.associate { it.category to it.shown.size } }
                    val showCount = state.showItemCount || !searchQuery.isNullOrEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            LibraryTabs(
                                categories = pages.map { it.category },
                                pagerState = pagerState,
                                getItemCountForCategory = { category -> counts[category]?.takeIf { showCount } },
                                onTabItemClick = { scope.launch { pagerState.animateScrollToPage(it) } },
                            )
                        }
                        panelButtons?.let { buttons ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) { buttons() }
                        }
                    }
                } else if (panelButtons != null) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) { panelButtons() }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                PullRefresh(
                    refreshing = isRefreshing,
                    enabled = selected.isEmpty(),
                    onRefresh = {
                        if (!onRefresh()) return@PullRefresh
                        scope.launch {
                            // Fake refresh status but hide it after a second as it's a long running task
                            isRefreshing = true
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                ) {
                    HorizontalPager(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = panelStart, end = panelEnd),
                        state = pagerState,
                        verticalAlignment = Alignment.Top,
                    ) { index ->
                        if (index !in ((pagerState.currentPage - 1)..(pagerState.currentPage + 1))) {
                            // Only one page each side is composed
                            return@HorizontalPager
                        }
                        val page = pages.getOrNull(index) ?: return@HorizontalPager
                        AnimeLibraryPageGrid(
                            page = page,
                            state = state,
                            searchQuery = searchQuery,
                            bottomPadding = contentPadding.calculateBottomPadding(),
                            // KMK: the previewed cover shows as picked
                            selected = if (highlighted != null) selected + highlighted else selected,
                            wideCovers = wideCovers,
                            onClick = onClick,
                            onLongClick = toggle,
                            onContinueWatching = continueWatching,
                        )
                    }
                }
                panels()
                }

                // Keep the page in range when the pages change (grouping, categories)
                LaunchedEffect(pages.size) {
                    if (pagerState.currentPage >= pages.size) pagerState.scrollToPage(pages.size - 1)
                }
                LaunchedEffect(pagerState.currentPage) {
                    onChangeCurrentPage(pagerState.currentPage)
                }
            }
        }
        if (selected.isNotEmpty()) {
            val shown = state.page(currentPage)?.shown.orEmpty()
            AnimeSelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onSelectAll = { selected = selected + shown.map { it.id } },
                onInvert = { selected = shown.map { it.id }.toSet() - selected },
                onSetCategories = { showCategories = true },
                onMarkSeen = {
                    markAnimeSeen(selected, true)
                    selected = emptySet()
                },
                onMarkUnseen = {
                    markAnimeSeen(selected, false)
                    selected = emptySet()
                },
                onRemove = { showRemove = true },
                onMigrate = onMigrate?.let { migrate ->
                    {
                        migrate(selected.toList())
                        selected = emptySet()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

@Composable
private fun AnimeLibraryPageGrid(
    page: AnimeLibraryPage,
    state: AnimeLibraryState,
    searchQuery: String?,
    bottomPadding: Dp,
    selected: Set<Long>,
    wideCovers: WideCovers,
    onClick: (Anime) -> Unit,
    onLongClick: (Anime) -> Unit,
    onContinueWatching: (Anime) -> Unit,
) {
    val loadedSources by remember { Injekt.get<AnimeSourceManager>().catalogueSources }.collectAsState()
    val shown = page.shown
    // KMK: "Search for … globally", like the manga library
    val navigator = cafe.adriel.voyager.navigator.LocalNavigator.current
    val globalSearch: @Composable (Modifier) -> Unit = { modifier ->
        androidx.compose.material3.TextButton(
            modifier = modifier,
            onClick = { navigator?.push(eu.kanade.tachiyomi.ui.anime.AnimeGlobalSearchScreen(searchQuery.orEmpty())) },
        ) {
            Text(text = stringResource(MR.strings.action_global_search_query, searchQuery.orEmpty()))
        }
    }
    if (shown.isEmpty()) {
        Column(
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (!searchQuery.isNullOrEmpty()) {
                globalSearch(Modifier.fillMaxWidth().padding(8.dp))
            }
            EmptyScreen(
                message = stringResource(
                    when {
                        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
                        state.filters.isActive && page.anime.isNotEmpty() -> KMR.strings.anime_library_no_match
                        else -> MR.strings.information_no_manga_category
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    // Same display mode and columns as the manga library
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columnsPref = remember(isLandscape) {
        if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()
    }
    val columns by columnsPref.collectAsState()
    val displayMode by remember { libraryPreferences.displayMode() }.collectAsState()
    // Panorama styles like the manga library (Color fill / Fit width / Wide)
    val panoramaStyle by remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }.collectAsState()
    val usePanorama = displayMode == LibraryDisplayMode.ComfortableGridPanorama
    val doubleWide = usePanorama && panoramaStyle == PanoramaStyle.WIDE
    val knownWide = remember(shown, doubleWide) {
        if (!doubleWide) {
            emptySet<Long>()
        } else {
            shown.mapNotNullTo(HashSet()) { anime ->
                // The saved shape is width / height, keyed by the cover's id
                val widthByHeight = MangaCover.coverRatioMap[anime.coverId()] ?: return@mapNotNullTo null
                anime.id.takeIf { widthByHeight > 0f && 1f / widthByHeight <= WideCovers.WIDE_RATIO }
            }
        }
    }
    val foundWide = wideCovers.ids
    val wide = remember(knownWide, foundWide) { if (foundWide.isEmpty()) knownWide else knownWide + foundWide }

    val badges = rememberAnimeBadgeSettings()
    val corners = rememberCoverBadgeCorners()
    val progressBar = rememberCoverProgressBarEnabled()

    FastScrollLazyVerticalGrid(
        columns = when {
            displayMode == LibraryDisplayMode.List -> GridCells.Fixed(1)
            columns > 0 -> GridCells.Fixed(columns)
            else -> GridCells.Adaptive(128.dp)
        },
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPadding + 8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(key = "anime-global-search", span = { GridItemSpan(maxLineSpan) }) {
                globalSearch(Modifier.fillMaxWidth())
            }
        }
        items(
            shown,
            key = { it.id },
            span = { anime -> wideSpan(doubleWide, wide, anime.id) },
        ) { anime ->
            // KMK: read so the cover's language and source badges update when the anime sources finish loading
            @Suppress("UNUSED_EXPRESSION")
            loadedSources.size
            val stat = state.stats[anime.id]
            val unseen = stat?.unseen ?: 0L
            val onContinue: (() -> Unit)? = if (badges.continueButton && unseen > 0) {
                { onContinueWatching(anime) }
            } else {
                null
            }
            val available = animeBadges(anime, unseen, state.downloaded[anime.id] ?: 0, badges)
            val progress = if (progressBar && stat != null) readingProgress(stat.seen, stat.total) else null
            when (displayMode) {
                LibraryDisplayMode.List -> MangaListItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title,
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                    badge = { available.values.forEach { it() } },
                    isSelected = anime.id in selected,
                    onClickContinueReading = onContinue,
                )
                LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                    isSelected = anime.id in selected,
                    coverBadgeStart = corners.slot(BadgeCorner.TopLeft, available),
                    coverBadgeEnd = corners.slot(BadgeCorner.TopRight, available),
                    coverBadgeBottomStart = corners.slot(BadgeCorner.BottomLeft, available),
                    coverBadgeBottomEnd = corners.slot(BadgeCorner.BottomRight, available),
                    onClickContinueReading = onContinue,
                    progress = progress,
                )
                else -> MangaComfortableGridItem(
                    coverData = anime.asMangaCover(),
                    title = anime.title,
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                    usePanoramaCover = usePanorama,
                    panoramaStyle = panoramaStyle,
                    onCoverRatio = { if (doubleWide && anime.id !in knownWide) wideCovers.report(anime.id, it) },
                    isSelected = anime.id in selected,
                    coverBadgeStart = corners.slot(BadgeCorner.TopLeft, available),
                    coverBadgeEnd = corners.slot(BadgeCorner.TopRight, available),
                    coverBadgeBottomStart = corners.slot(BadgeCorner.BottomLeft, available),
                    coverBadgeBottomEnd = corners.slot(BadgeCorner.BottomRight, available),
                    onClickContinueReading = onContinue,
                    progress = progress,
                )
            }
        }
    }
}

/** The anime library's Display › Overlay choices */
@Immutable
private data class AnimeBadgeSettings(
    val downloaded: Boolean,
    val unseen: Boolean,
    val language: Boolean,
    val useLangIcon: Boolean,
    val source: Boolean,
    val continueButton: Boolean,
    val local: Boolean,
)

@Composable
private fun rememberAnimeBadgeSettings(): AnimeBadgeSettings {
    // KMK: read again after a restore (see LibraryRefresh), so the covers match the restored settings
    val tick by eu.kanade.tachiyomi.ui.library.LibraryRefresh.tick.collectAsState()
    val downloaded by remember(tick) { AnimeLibraryPrefs.downloadBadge() }.collectAsState()
    val unseen by remember(tick) { AnimeLibraryPrefs.unseenBadge() }.collectAsState()
    val language by remember(tick) { AnimeLibraryPrefs.languageBadge() }.collectAsState()
    val useLangIcon by remember(tick) { AnimeLibraryPrefs.useLangIcon() }.collectAsState()
    val source by remember(tick) { AnimeLibraryPrefs.sourceBadge() }.collectAsState()
    val continueButton by remember(tick) { AnimeLibraryPrefs.continueButton() }.collectAsState()
    val local by remember(tick) { AnimeLibraryPrefs.localBadge() }.collectAsState()
    return AnimeBadgeSettings(downloaded, unseen, language, useLangIcon, source, continueButton, local)
}

/**
 * The badges an anime cover shows, keyed like [CoverBadge] so they go in the corners chosen in
 * Settings › Library › Badges, the same as the manga library's
 */
private fun animeBadges(
    anime: Anime,
    unseen: Long,
    downloaded: Int,
    settings: AnimeBadgeSettings,
): Map<String, @Composable RowScope.() -> Unit> = buildMap<String, @Composable RowScope.() -> Unit> {
    if (settings.downloaded) put(CoverBadge.DOWNLOADED) { DownloadsBadge(count = downloaded.toLong()) }
    if (settings.unseen) put(CoverBadge.UNREAD) { UnreadBadge(count = unseen) }
    // Local source: anime from Device files get a folder badge where the language goes
    val isLocal = anime.source == eu.kanade.tachiyomi.devicefiles.DeviceFilesIds.ANIME
    if (isLocal && settings.local) {
        put(CoverBadge.LANGUAGE) {
            tachiyomi.presentation.core.components.Badge(
                imageVector = androidx.compose.material.icons.Icons.Outlined.Folder,
                color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                iconColor = androidx.compose.material3.MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
    if (settings.language || settings.source) {
        val source = AnimeMigrateSources.asSource(anime.source)
        if (settings.language && !isLocal) {
            put(CoverBadge.LANGUAGE) {
                LanguageBadge(isLocal = false, sourceLanguage = source.lang, useLangIcon = settings.useLangIcon)
            }
        }
        if (settings.source) put(CoverBadge.SOURCE) { SourceIconBadge(source = source) }
    }
}
