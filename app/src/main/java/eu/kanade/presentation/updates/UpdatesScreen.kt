package eu.kanade.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.anime.AnimeUpdateRow
import eu.kanade.presentation.anime.rememberPlayAnimeEpisode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.MediaGridItem
import eu.kanade.presentation.components.MediaTab
import eu.kanade.presentation.components.MediaTabRow
import eu.kanade.presentation.components.animeCover
import eu.kanade.presentation.components.mediaDisplayAction
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.components.rememberMediaGridCells
import eu.kanade.presentation.components.rememberWideCovers
import eu.kanade.presentation.components.wideSpan
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeUpdateItem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Composable
fun UpdateScreen(
    state: UpdatesScreenModel.State,
    snackbarHostState: SnackbarHostState,
    lastUpdated: Long,
    // SY -->
    preserveReadingPosition: Boolean,
    // SY <--
    onClickCover: (UpdatesItem) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onCalendarClicked: () -> Unit,
    onUpdateLibrary: () -> Boolean,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
    // KMK -->
    updateSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    updateSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onUpdateSwipe: (UpdatesItem, LibraryPreferences.ChapterSwipeAction) -> Unit,
    // KMK <--
    onUpdateSelected: (UpdatesItem, /* KMK --> */ UpdatesScreenModel.UpdateSelectionOptions /* KMK <-- */) -> Unit,
    onOpenChapter: (UpdatesItem) -> Unit,
    onDisplayClicked: () -> Unit,
    // KMK -->
    usePanoramaCover: Boolean,
    collapseToggle: (key: String) -> Unit,
    mediaTab: Int = MediaTab.ALL,
    onMediaTabChange: (Int) -> Unit = {},
    displayMode: LibraryDisplayMode = LibraryDisplayMode.List,
    columns: Int = 0,
    // KMK <--
) {
    BackHandler(enabled = state.selectionMode, onBack = { onSelectAll(false) })
    // KMK -->
    // Anime updates next to the manga ones: All | Manga | Anime
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val animeRepository = remember { Injekt.get<AnimeRepository>() }
    val animeSourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val after = remember { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90) }
    val animeUpdates by remember { animeRepository.subscribeUpdates(after) }.collectAsState(initial = emptyList())
    val playEpisode = rememberPlayAnimeEpisode()
    val showsManga = MediaTab.showsManga(mediaTab)
    val showsAnime = MediaTab.showsAnime(mediaTab)
    val updateLibrary: () -> Boolean = {
        var started = false
        if (showsManga) started = onUpdateLibrary()
        if (showsAnime) {
            AnimeLibraryUpdateJob.startNow(context)
            context.toast(KMR.strings.anime_updating_library)
            started = true
        }
        started
    }
    // KMK <--

    Scaffold(
        topBar = { scrollBehavior ->
            // KMK -->
            Column {
                // KMK <--
                UpdatesAppBar(
                    onCalendarClicked = { onCalendarClicked() },
                    onUpdateLibrary = { updateLibrary() },
                    // KMK -->
                    displayAction = mediaDisplayAction(displayMode, onDisplayClicked),
                    // KMK <--
                    actionModeCounter = state.selected.size,
                    onSelectAll = { onSelectAll(true) },
                    onInvertSelection = { onInvertSelection() },
                    onCancelActionMode = { onSelectAll(false) },
                    scrollBehavior = scrollBehavior,
                )
                // KMK -->
                if (!state.selectionMode) {
                    MediaTabRow(selected = mediaTab, onSelect = onMediaTabChange)
                }
            }
            // KMK <--
        },
        bottomBar = {
            UpdatesBottomBar(
                selected = state.selected,
                onDownloadChapter = onDownloadChapter,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMultiDeleteClicked = onMultiDeleteClicked,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        // KMK -->
        val uiModels = remember(state.items, animeUpdates, mediaTab) {
            mixUpdates(
                manga = if (showsManga) state.getUiModel() else emptyList(),
                anime = if (showsAnime) animeUpdates else emptyList(),
            )
        }
        // KMK <--
        when {
            /* KMK --> */showsManga && /* KMK <-- */state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            uiModels.isEmpty() -> EmptyScreen(
                // KMK
                stringRes = if (mediaTab == MediaTab.ANIME) KMR.strings.anime_no_updates else MR.strings.information_no_recent,
                modifier = Modifier.padding(contentPadding),
            )
            else -> {
                val scope = rememberCoroutineScope()
                var isRefreshing by remember { mutableStateOf(false) }

                PullRefresh(
                    refreshing = isRefreshing,
                    onRefresh = {
                        val started = updateLibrary()
                        if (!started) return@PullRefresh
                        scope.launch {
                            // Fake refresh status but hide it after a second as it's a long running task
                            isRefreshing = true
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                    enabled = !state.selectionMode,
                    indicatorPadding = contentPadding,
                ) {
                    // KMK --> the library's grids
                    if (displayMode != LibraryDisplayMode.List) {
                        UpdatesGrid(
                            uiModels = uiModels,
                            displayMode = displayMode,
                            columns = columns,
                            contentPadding = contentPadding,
                            selectionMode = state.selectionMode,
                            onUpdateSelected = onUpdateSelected,
                            onClickCover = onClickCover,
                            onClickAnime = { playEpisode(it.animeId, it.episodeId) },
                            onLongClickAnime = { navigator?.push(AnimeScreen(it.animeId)) },
                        )
                        return@PullRefresh
                    }
                    // KMK <--
                    FastScrollLazyColumn(
                        contentPadding = contentPadding,
                    ) {
                        // KMK
                        if (showsManga) updatesLastUpdatedItem(lastUpdated)

                        updatesUiItems(
                            // KMK -->
                            uiModels = uiModels,
                            expandedState = state.expandedState,
                            collapseToggle = collapseToggle,
                            usePanoramaCover = usePanoramaCover,
                            // KMK <--
                            selectionMode = state.selectionMode,
                            // SY -->
                            preserveReadingPosition = preserveReadingPosition,
                            // SY <--
                            onUpdateSelected = onUpdateSelected,
                            onClickCover = onClickCover,
                            onClickUpdate = onOpenChapter,
                            onDownloadChapter = onDownloadChapter,
                            // KMK -->
                            updateSwipeStartAction = updateSwipeStartAction,
                            updateSwipeEndAction = updateSwipeEndAction,
                            onUpdateSwipe = onUpdateSwipe,
                            animeContent = { item ->
                                AnimeUpdateRow(
                                    item = item,
                                    sourceManager = animeSourceManager,
                                    onClick = { playEpisode(item.animeId, item.episodeId) },
                                    onClickCover = { navigator?.push(AnimeScreen(item.animeId)) },
                                    onToggleSeen = {
                                        scope.launchIO {
                                            animeRepository.updateEpisode(id = item.episodeId, seen = !item.seen)
                                        }
                                    },
                                )
                            },
                            // KMK <--
                        )
                    }
                }
            }
        }
    }
}

// KMK -->
/**
 * Manga and anime updates together: one header per day (newest first), the day's manga chapters, then its anime
 * episodes. The header counts both.
 */
private fun mixUpdates(manga: List<UpdatesUiModel>, anime: List<AnimeUpdateItem>): List<UpdatesUiModel> {
    if (anime.isEmpty()) return manga
    val zone = ZoneId.systemDefault()
    val days = linkedMapOf<LocalDate, Pair<Int, MutableList<UpdatesUiModel>>>()
    var current: LocalDate? = null
    manga.forEach { model ->
        if (model is UpdatesUiModel.Header) {
            current = model.date
            days[model.date] = model.mangaCount to mutableListOf()
        } else {
            current?.let { days[it]?.second?.add(model) }
        }
    }
    val animeDays = anime.groupBy { Instant.ofEpochMilli(it.dateFetch).atZone(zone).toLocalDate() }
    return (days.keys + animeDays.keys)
        .distinct()
        .sortedDescending()
        .flatMap { date ->
            val (mangaCount, mangaItems) = days[date] ?: (0 to mutableListOf<UpdatesUiModel>())
            val animeItems = animeDays[date].orEmpty()
            listOf(UpdatesUiModel.Header(date, mangaCount + animeItems.distinctBy { it.animeId }.size)) +
                mangaItems +
                animeItems.map { UpdatesUiModel.Anime(it) }
        }
}

/** Updates in one of the library's grids: one cover per title per day */
@Composable
private fun UpdatesGrid(
    uiModels: List<UpdatesUiModel>,
    displayMode: LibraryDisplayMode,
    columns: Int,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, UpdatesScreenModel.UpdateSelectionOptions) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickAnime: (AnimeUpdateItem) -> Unit,
    onLongClickAnime: (AnimeUpdateItem) -> Unit,
) {
    val cells = remember(uiModels) {
        val seen = HashSet<String>()
        uiModels.filter { model ->
            when (model) {
                is UpdatesUiModel.Header -> {
                    seen.clear()
                    true
                }
                is UpdatesUiModel.Item -> seen.add("m${model.item.update.mangaId}")
                is UpdatesUiModel.Anime -> seen.add("a${model.item.animeId}")
            }
        }
    }
    // "Panorama: wide": wide covers take two places; read here, not inside the span lambda
    val panoramaStyle by remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }.collectAsState()
    val doubleWide = displayMode == LibraryDisplayMode.ComfortableGridPanorama && panoramaStyle == PanoramaStyle.WIDE
    val wideCovers = rememberWideCovers()
    val wide = wideCovers.ids
    FastScrollLazyVerticalGrid(
        columns = rememberMediaGridCells(columns),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(
            items = cells,
            key = {
                when (it) {
                    is UpdatesUiModel.Header -> "header-${it.date}"
                    is UpdatesUiModel.Item -> "manga-${it.item.update.chapterId}"
                    is UpdatesUiModel.Anime -> "anime-${it.item.episodeId}"
                }
            },
            span = {
                when (it) {
                    is UpdatesUiModel.Header -> GridItemSpan(maxLineSpan)
                    is UpdatesUiModel.Item -> wideSpan(doubleWide, wide, it.item.update.coverData.mangaId)
                    is UpdatesUiModel.Anime -> wideSpan(doubleWide, wide, -it.item.animeId)
                }
            },
        ) { model ->
            when (model) {
                is UpdatesUiModel.Header -> ListGroupHeader(text = relativeDateText(model.date))
                is UpdatesUiModel.Item -> {
                    val updatesItem = model.item
                    val select = {
                        onUpdateSelected(
                            updatesItem,
                            UpdatesScreenModel.UpdateSelectionOptions(
                                selected = !updatesItem.selected,
                                fromLongPress = true,
                                isGroup = model is UpdatesUiModel.Leader && model.isExpandable,
                                isExpanded = false,
                            ),
                        )
                    }
                    MediaGridItem(
                        displayMode = displayMode,
                        cover = updatesItem.update.coverData,
                        title = updatesItem.update.mangaTitle,
                        isSelected = updatesItem.selected,
                        coverAlpha = if (updatesItem.update.read) 0.5f else 1f,
                        onClick = { if (selectionMode) select() else onClickCover(updatesItem) },
                        onLongClick = select,
                        onCoverRatio = {
                            if (doubleWide) wideCovers.report(updatesItem.update.coverData.mangaId, it)
                        },
                    )
                }
                is UpdatesUiModel.Anime -> {
                    val item = model.item
                    MediaGridItem(
                        displayMode = displayMode,
                        cover = animeCover(item.animeId, item.source, true, item.thumbnailUrl),
                        title = item.title,
                        coverAlpha = if (item.seen) 0.5f else 1f,
                        onClick = { onClickAnime(item) },
                        onLongClick = { onLongClickAnime(item) },
                        onCoverRatio = { if (doubleWide) wideCovers.report(-item.animeId, it) },
                    )
                }
            }
        }
    }
}
// KMK <--

@Composable
private fun UpdatesAppBar(
    onCalendarClicked: () -> Unit,
    onUpdateLibrary: () -> Unit,
    // KMK -->
    displayAction: AppBar.Action,
    // KMK <--
    // For action mode
    actionModeCounter: Int,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onCancelActionMode: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        title = stringResource(MR.strings.label_recent_updates),
        actions = {
            AppBarActions(
                persistentListOf(
                    // KMK: the library's display modes, like a source's button
                    displayAction,
                    AppBar.Action(
                        title = stringResource(MR.strings.action_view_upcoming),
                        icon = Icons.Outlined.CalendarMonth,
                        onClick = onCalendarClicked,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_update_library),
                        icon = Icons.Outlined.Refresh,
                        onClick = onUpdateLibrary,
                    ),
                ),
            )
        },
        actionModeCounter = actionModeCounter,
        onCancelActionMode = onCancelActionMode,
        actionModeActions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = Icons.Outlined.SelectAll,
                        onClick = onSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = Icons.Outlined.FlipToBack,
                        onClick = onInvertSelection,
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun UpdatesBottomBar(
    selected: List<UpdatesItem>,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected, true)
        }.takeIf { selected.fastAny { !it.update.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected, false)
        }.takeIf { selected.fastAll { it.update.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected, true)
        }.takeIf { selected.fastAny { !it.update.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected, false)
        }.takeIf { selected.fastAny { it.update.read || it.update.lastPageRead > 0L } },
        onDownloadClicked = {
            onDownloadChapter(selected, ChapterDownloadAction.START)
        }.takeIf {
            selected.fastAny { it.downloadStateProvider() != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected)
        }.takeIf { selected.fastAny { it.downloadStateProvider() == Download.State.DOWNLOADED } },
    )
}

sealed interface UpdatesUiModel {
    data class Header(val date: LocalDate, val mangaCount: Int) : UpdatesUiModel
    open class Item(open val item: UpdatesItem, open val isExpandable: Boolean = false) : UpdatesUiModel

    // KMK -->
    /** The first [Item] in a group of chapters from same manga */
    data class Leader(override val item: UpdatesItem, override val isExpandable: Boolean) : Item(item)

    /** A new episode of an anime, in the All and Anime tabs */
    data class Anime(val item: AnimeUpdateItem) : UpdatesUiModel
    // KMK <--
}
