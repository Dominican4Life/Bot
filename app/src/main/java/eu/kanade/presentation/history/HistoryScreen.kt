package eu.kanade.presentation.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.presentation.anime.AnimeConfirmDialog
import eu.kanade.presentation.anime.AnimeHistoryRow
import eu.kanade.presentation.anime.rememberPlayAnimeEpisode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.MediaGridItem
import eu.kanade.presentation.components.MediaTab
import eu.kanade.presentation.components.MediaTabPager
import eu.kanade.presentation.components.MediaTabRow
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.animeCover
import eu.kanade.presentation.components.mediaDisplayAction
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.components.rememberMediaGridCells
import eu.kanade.presentation.components.rememberWideCovers
import eu.kanade.presentation.components.wideSpan
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.AnimeHistoryItem
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel.HistorySelectionOptions
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState

@Composable
fun HistoryScreen(
    state: HistoryScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (mangaId: Long) -> Unit,
    onClickResume: (mangaId: Long, chapterId: Long) -> Unit,
    onClickFavorite: (mangaId: Long) -> Unit,
    onDialogChange: (HistoryScreenModel.Dialog?) -> Unit,
    // KMK -->
    toggleSelectionMode: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onHistorySelected: (HistoryWithRelations, HistorySelectionOptions) -> Unit,
    onDisplayClicked: () -> Unit,
    usePanoramaCover: Boolean,
    mediaTab: Int = MediaTab.ANIME,
    onMediaTabChange: (Int) -> Unit = {},
    displayMode: LibraryDisplayMode = LibraryDisplayMode.List,
    columns: Int = 0,
    // KMK <--
) {
    // KMK -->
    BackHandler(enabled = state.selectionMode, onBack = toggleSelectionMode)
    // Device files only: what was played or read from the device's storage
    val deviceOnlyPref = remember { eu.kanade.tachiyomi.devicefiles.DeviceFilesPreferences.instance.historyDeviceOnly }
    val deviceOnly by deviceOnlyPref.collectPrefAsState()
    val deviceAction = AppBar.Action(
        title = if (deviceOnly) "Back to history" else "Device files history",
        icon = if (deviceOnly) Icons.Filled.Folder else Icons.Outlined.Folder,
        iconTint = if (deviceOnly) MaterialTheme.colorScheme.active else LocalContentColor.current,
        onClick = { deviceOnlyPref.set(!deviceOnly) },
    )

    // Anime history, next to the manga one
    val animeRepository = remember { Injekt.get<AnimeRepository>() }
    val animeHistoryAll by remember { animeRepository.subscribeHistory() }.collectAsState(initial = null)
    val animeHistory = remember(animeHistoryAll, state.searchQuery) {
        animeHistoryAll.orEmpty()
            // Device files keep their own history, behind the folder button
            .filterNot { it.source == eu.kanade.tachiyomi.devicefiles.DeviceFilesIds.ANIME }
            // Videos played from inside a manga chapter aren't anime of their own
            .filterNot { it.source == eu.kanade.tachiyomi.sumizuri.ReaderVideos.SOURCE_ID }
            .filter { state.searchQuery.isNullOrBlank() || it.title.contains(state.searchQuery!!, ignoreCase = true) }
    }
    val scope = rememberCoroutineScope()
    var removeAnimeId by remember { mutableStateOf<Long?>(null) }
    var confirmClearAnime by remember { mutableStateOf(false) }
    // KMK <--

    Scaffold(
        topBar = { scrollBehavior ->
            // KMK -->
            Column {
                when {
                    state.selectionMode -> HistorySelectionToolbar(
                        selectedCount = state.selection.size,
                        onCancelActionMode = toggleSelectionMode,
                        onClickSelectAll = { onSelectAll(true) },
                        onClickInvertSelection = onInvertSelection,
                        onClickClearHistory = { onDialogChange(HistoryScreenModel.Dialog.Delete(state.selected)) },
                    )
                    // KMK <--
                    else -> SearchToolbar(
                        titleContent = {
                            AppBarTitle(
                                stringResource(MR.strings.history),
                                // KMK -->
                                subtitle = "Device files".takeIf { deviceOnly },
                                // KMK <--
                            )
                        },
                        searchQuery = state.searchQuery,
                        onChangeSearchQuery = onSearchQueryChange,
                        actions = {
                            // KMK: Device files history has only its own button (and the search)
                            if (deviceOnly) {
                                AppBarActions(persistentListOf(deviceAction))
                                return@SearchToolbar
                            }
                            AppBarActions(
                                persistentListOf<AppBar.AppBarAction>().builder().apply {
                                    // KMK -->
                                    add(mediaDisplayAction(displayMode, onDisplayClicked))
                                    add(deviceAction)
                                    if (MediaTab.showsManga(mediaTab)) {
                                        // KMK <--
                                        add(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.pref_clear_history),
                                                // KMK -->
                                                icon = Icons.Outlined.Checklist,
                                                onClick = toggleSelectionMode,
                                                // KMK <--
                                            ),
                                        )
                                    } else {
                                        // KMK: anime history is cleared all at once
                                        add(
                                            AppBar.Action(
                                                title = stringResource(KMR.strings.anime_clear_history),
                                                icon = Icons.Outlined.DeleteSweep,
                                                onClick = { confirmClearAnime = true },
                                            ),
                                        )
                                    }
                                }.build(),
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
                // KMK -->
                if (!state.selectionMode && !deviceOnly) {
                    MediaTabRow(selected = mediaTab, onSelect = onMediaTabChange)
                }
            }
            // KMK <--
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        // KMK --> Device files history on its own, from the folder button
        if (deviceOnly) {
            eu.kanade.tachiyomi.ui.devicefiles.DeviceHistoryPane(
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
            )
            return@Scaffold
        }
        // KMK: swipe left or right between Anime and Manga
        MediaTabPager(
            selected = mediaTab,
            onSelect = onMediaTabChange,
            userScrollEnabled = !state.selectionMode,
        ) { mediaTab ->
            val entries = remember(state.list, animeHistory, mediaTab) {
                mixHistory(
                    manga = if (MediaTab.showsManga(mediaTab)) state.list else emptyList(),
                    anime = if (MediaTab.showsAnime(mediaTab)) animeHistory else emptyList(),
                )
            }
            // KMK <--
            if (/* KMK --> */MediaTab.showsManga(mediaTab) && /* KMK <-- */state.isLoading) {
                LoadingScreen(Modifier.padding(contentPadding))
            } else if (entries.isEmpty()) {
                val msg = when {
                    !state.searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
                    // KMK
                    mediaTab == MediaTab.ANIME -> KMR.strings.anime_no_history
                    else -> MR.strings.information_no_recent_manga
                }
                EmptyScreen(
                    stringRes = msg,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                HistoryScreenContent(
                    // KMK -->
                    state = state,
                    entries = entries,
                    displayMode = displayMode,
                    columns = columns,
                    onAnimeDelete = { removeAnimeId = it.animeId },
                    // KMK <--
                    contentPadding = contentPadding,
                    onClickCover = { history -> onClickCover(history.mangaId) },
                    onClickResume = { history -> onClickResume(history.mangaId, history.chapterId) },
                    onClickDelete = { item -> onDialogChange(HistoryScreenModel.Dialog.Delete(item)) },
                    onClickFavorite = { history -> onClickFavorite(history.mangaId) },
                    // KMK -->
                    selectionMode = state.selectionMode,
                    onHistorySelected = onHistorySelected,
                    usePanoramaCover = usePanoramaCover,
                    // KMK <--
                )
            }
        }
    }

    // KMK -->
    removeAnimeId?.let { animeId ->
        AnimeConfirmDialog(
            text = stringResource(KMR.strings.anime_remove_history_confirm),
            onConfirm = { scope.launchIO { animeRepository.removeHistory(animeId) } },
            onDismiss = { removeAnimeId = null },
        )
    }
    if (confirmClearAnime) {
        AnimeConfirmDialog(
            text = stringResource(KMR.strings.anime_clear_history_confirm),
            // Device files history stays (it isn't shown here)
            onConfirm = {
                val ids = animeHistory.map { it.animeId }.distinct()
                scope.launchIO { ids.forEach { animeRepository.removeHistory(it) } }
            },
            onDismiss = { confirmClearAnime = false },
        )
    }
    // KMK <--
}

// KMK -->
/** One row of History: a day, a manga or an anime */
private sealed interface HistoryEntry {
    val key: String

    data class Header(val date: LocalDate) : HistoryEntry {
        override val key = "header-$date"
    }

    data class Manga(val item: HistoryWithRelations) : HistoryEntry {
        override val key = "manga-${item.id}"
    }

    data class Anime(val item: AnimeHistoryItem) : HistoryEntry {
        override val key = "anime-${item.animeId}"
    }
}

/** Manga and anime history together, newest first, under one header per day */
private fun mixHistory(manga: List<HistoryWithRelations>, anime: List<AnimeHistoryItem>): List<HistoryEntry> {
    val zone = ZoneId.systemDefault()
    val timed = manga.map { (it.readAt?.time ?: 0L) to HistoryEntry.Manga(it) } +
        anime.map { it.lastSeen to HistoryEntry.Anime(it) }
    return timed
        .sortedByDescending { it.first }
        .groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
        .flatMap { (date, items) -> listOf(HistoryEntry.Header(date)) + items.map { it.second } }
}
// KMK <--

@Composable
private fun HistoryScreenContent(
    // KMK -->
    state: HistoryScreenModel.State,
    entries: List<HistoryEntry>,
    displayMode: LibraryDisplayMode,
    columns: Int,
    onAnimeDelete: (AnimeHistoryItem) -> Unit,
    // KMK <--
    contentPadding: PaddingValues,
    onClickCover: (HistoryWithRelations) -> Unit,
    onClickResume: (HistoryWithRelations) -> Unit,
    onClickDelete: (HistoryWithRelations) -> Unit,
    onClickFavorite: (HistoryWithRelations) -> Unit,
    // KMK -->
    selectionMode: Boolean,
    onHistorySelected: (HistoryWithRelations, HistorySelectionOptions) -> Unit,
    usePanoramaCover: Boolean,
    // KMK <--
) {
    // KMK -->
    val navigator = LocalNavigator.current
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val playEpisode = rememberPlayAnimeEpisode()

    fun onMangaClick(value: HistoryWithRelations, isSelected: Boolean) {
        if (selectionMode) {
            onHistorySelected(value, HistorySelectionOptions(selected = !isSelected, fromLongPress = false))
        } else {
            onClickResume(value)
        }
    }

    fun onMangaLongClick(value: HistoryWithRelations, isSelected: Boolean) {
        onHistorySelected(value, HistorySelectionOptions(selected = !isSelected, fromLongPress = true))
    }

    // The library's grids
    if (displayMode != LibraryDisplayMode.List) {
        // "Panorama: wide": wide covers take two places; read here, not inside the span lambda
        val panoramaStyle by remember { Injekt.get<UiPreferences>().libraryPanoramaStyle() }.collectPrefAsState()
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
                items = entries,
                key = { it.key },
                span = {
                    when (it) {
                        is HistoryEntry.Header -> GridItemSpan(maxLineSpan)
                        is HistoryEntry.Manga -> wideSpan(doubleWide, wide, it.item.coverData.mangaId)
                        is HistoryEntry.Anime -> wideSpan(doubleWide, wide, -it.item.animeId)
                    }
                },
            ) { entry ->
                when (entry) {
                    is HistoryEntry.Header -> ListGroupHeader(text = relativeDateText(entry.date))
                    is HistoryEntry.Manga -> {
                        val value = entry.item
                        val isSelected = remember(state.selection) { value.chapterId in state.selection }
                        MediaGridItem(
                            displayMode = displayMode,
                            cover = value.coverData,
                            title = value.title,
                            isSelected = isSelected,
                            onClick = { onMangaClick(value, isSelected) },
                            onLongClick = { onMangaLongClick(value, isSelected) },
                            onCoverRatio = { if (doubleWide) wideCovers.report(value.coverData.mangaId, it) },
                        )
                    }
                    is HistoryEntry.Anime -> {
                        val item = entry.item
                        MediaGridItem(
                            displayMode = displayMode,
                            cover = animeCover(item.animeId, item.source, item.favorite, item.thumbnailUrl),
                            title = item.title,
                            onClick = { playEpisode(item.animeId, item.episodeId) },
                            onLongClick = { navigator?.push(AnimeScreen(item.animeId)) },
                            onCoverRatio = { if (doubleWide) wideCovers.report(-item.animeId, it) },
                        )
                    }
                }
            }
        }
        return
    }
    // KMK <--
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        items(
            items = entries,
            key = { it.key },
            contentType = {
                when (it) {
                    is HistoryEntry.Header -> "header"
                    is HistoryEntry.Manga -> "item"
                    is HistoryEntry.Anime -> "anime"
                }
            },
        ) { entry ->
            when (entry) {
                is HistoryEntry.Header -> {
                    ListGroupHeader(
                        modifier = Modifier.animateItemFastScroll(),
                        text = relativeDateText(entry.date),
                    )
                }
                is HistoryEntry.Manga -> {
                    val value = entry.item
                    // KMK -->
                    val isSelected = remember(state.selection) { value.chapterId in state.selection }
                    // KMK <--
                    HistoryItem(
                        modifier = Modifier.animateItemFastScroll(),
                        history = value,
                        onClickCover = { onClickCover(value) },
                        // KMK -->
                        onClick = { onMangaClick(value, isSelected) },
                        onLongClick = { onMangaLongClick(value, isSelected) },
                        // KMK <--
                        onClickDelete = { onClickDelete(value) },
                        onClickFavorite = { onClickFavorite(value) },
                        // KMK -->
                        selected = isSelected,
                        readProgress = value.lastPageRead
                            .takeIf { !value.read && it > 0L }
                            ?.let {
                                stringResource(
                                    MR.strings.chapter_progress,
                                    it + 1,
                                )
                            },
                        hasUnread = value.unreadCount > 0,
                        usePanoramaCover = usePanoramaCover,
                        // KMK <--
                    )
                }
                // KMK -->
                is HistoryEntry.Anime -> {
                    val item = entry.item
                    AnimeHistoryRow(
                        item = item,
                        sourceManager = sourceManager,
                        onClick = { navigator?.push(AnimeScreen(item.animeId)) },
                        onResume = { playEpisode(item.animeId, item.episodeId) },
                        onDelete = { onAnimeDelete(item) },
                    )
                }
                // KMK <--
            }
        }
    }
}

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    // KMK -->
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
    // KMK <--
}

// KMK -->
@Composable
private fun HistorySelectionToolbar(
    selectedCount: Int,
    onCancelActionMode: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    onClickClearHistory: () -> Unit,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = Icons.Outlined.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = Icons.Outlined.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.pref_clear_history),
                        icon = Icons.Outlined.DeleteSweep,
                        onClick = onClickClearHistory,
                        enabled = selectedCount > 0,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = onCancelActionMode,
    )
}
// KMK <--

@PreviewLightDark
@Composable
internal fun HistoryScreenPreviews(
    @PreviewParameter(HistoryScreenModelStateProvider::class)
    historyState: HistoryScreenModel.State,
) {
    TachiyomiPreviewTheme {
        HistoryScreen(
            state = historyState,
            snackbarHostState = SnackbarHostState(),
            onSearchQueryChange = {},
            onClickCover = {},
            onClickResume = { _, _ -> run {} },
            onDialogChange = {},
            onClickFavorite = {},
            // KMK -->
            toggleSelectionMode = {},
            onSelectAll = {},
            onInvertSelection = {},
            onHistorySelected = { _, _ -> },
            onDisplayClicked = {},
            usePanoramaCover = true,
            // KMK <--
        )
    }
}
