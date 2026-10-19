package eu.kanade.presentation.anime

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.wideSpan
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The anime half of the library: a grid of anime added from Browse. */
@Composable
fun AnimeLibraryContent(
    contentPadding: PaddingValues,
    searchQuery: String?,
    onClickAnime: (Anime) -> Unit,
    // KMK: Migrate from the selection bar
    onMigrate: ((List<Long>) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val repository = remember { Injekt.get<AnimeRepository>() }
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val favorites by remember { repository.subscribeFavorites() }.collectAsState(initial = null)
    // Follow the library's columns setting
    val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columnsPref = remember(isLandscape) {
        if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()
    }
    val columns by columnsPref.collectAsState()

    // KMK: the anime library's own filters, sort and badges
    val stats by remember { repository.subscribeLibraryStats() }.collectAsState(initial = emptyMap())
    val filters = rememberAnimeLibraryFilters()
    val sortMode by remember { AnimeLibraryPrefs.sort() }.collectAsState()
    val sortAscending by remember { AnimeLibraryPrefs.sortAscending() }.collectAsState()
    val randomSeed by remember { AnimeLibraryPrefs.randomSeed() }.collectAsState()
    val showUnseenBadge by remember { AnimeLibraryPrefs.unseenBadge() }.collectAsState()
    val showDownloadBadge by remember { AnimeLibraryPrefs.downloadBadge() }.collectAsState()
    val showContinue by remember { AnimeLibraryPrefs.continueButton() }.collectAsState()
    val downloadManager = remember { Injekt.get<eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager>() }
    val downloadChanges by downloadManager.changes.collectAsState()
    val needDownloads = showDownloadBadge || filters.downloaded != tachiyomi.core.common.preference.TriState.DISABLED
    // Look at each anime's download folder once, when downloads matter
    androidx.compose.runtime.LaunchedEffect(favorites?.size, needDownloads) {
        if (needDownloads) favorites?.forEach { downloadManager.refresh(it) }
    }
    val downloaded = remember(favorites, downloadChanges, needDownloads) {
        if (!needDownloads) emptyMap() else favorites.orEmpty().associate { it.id to downloadManager.downloadedCount(it) }
    }
    val needTracks = filters.tracked.values.any { it != tachiyomi.core.common.preference.TriState.DISABLED } ||
        sortMode == AnimeLibrarySort.TRACKER_SCORE
    val tracks by androidx.compose.runtime.produceState(
        initialValue = emptyMap<Long, List<eu.kanade.tachiyomi.data.anime.track.AnimeTrack>>(),
        favorites,
        needTracks,
    ) {
        value = if (needTracks) {
            runCatching {
                Injekt.get<eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository>().getAll().groupBy { it.animeId }
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    val list = favorites ?: return
    // KMK: category tabs, like the manga library. "Default" holds the anime in no category.
    val categories by remember { repository.subscribeCategories() }.collectAsState(initial = emptyList())
    val links by remember { repository.subscribeCategoryLinks() }.collectAsState(initial = emptyMap())
    val defaultName = stringResource(MR.strings.label_default)
    val pages = remember(categories, links, list) {
        val uncategorized = list.filter { links[it.id].isNullOrEmpty() }
        buildList {
            if (categories.isEmpty() || uncategorized.isNotEmpty()) add(Triple(-1L, defaultName, uncategorized))
            categories.forEach { category ->
                add(Triple(category.id, category.name, list.filter { category.id in links[it.id].orEmpty() }))
            }
        }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val current = pages.getOrNull(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    // KMK: long-press selects, like the manga library; tapping then adds or removes from the selection
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var showCategories by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val toggle: (Anime) -> Unit = { anime ->
        selected = if (anime.id in selected) selected - anime.id else selected + anime.id
    }
    val onLongClickAnime: (Anime) -> Unit = toggle
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

    val filtered = remember(current, searchQuery, stats, downloaded, tracks, filters, sortMode, sortAscending, randomSeed) {
        val inPage = current?.third.orEmpty()
        val searched = if (searchQuery.isNullOrBlank()) {
            inPage
        } else {
            inPage.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
        searched.filterAndSortAnime(stats, downloaded, tracks, filters, sortMode, sortAscending, randomSeed)
    }
    val continueWatching: (Anime) -> Unit = { anime ->
        scope.launch {
            val next = repository.getEpisodes(anime.id)
                .filter { !it.seen }
                .minWithOrNull(compareBy<eu.kanade.tachiyomi.data.anime.Episode> { it.episodeNumber }.thenByDescending { it.sourceOrder })
            if (next != null) {
                context.startActivity(eu.kanade.tachiyomi.ui.player.PlayerActivity.newIntent(context, anime.id, next.id))
            } else {
                context.toast(KMR.strings.anime_library_no_next_episode)
            }
        }
    }
    val unseenBadge: @Composable androidx.compose.foundation.layout.RowScope.(Anime) -> Unit = { anime ->
        if (showUnseenBadge) eu.kanade.presentation.library.components.UnreadBadge(stats[anime.id]?.unseen ?: 0L)
        if (showDownloadBadge) eu.kanade.presentation.library.components.DownloadsBadge((downloaded[anime.id] ?: 0).toLong())
    }
    val showTabs = pages.size > 1
    val tabRow: @Composable () -> Unit = {
        if (showTabs) {
            PrimaryScrollableTabRow(
                selectedTabIndex = page.coerceIn(0, pages.size - 1),
                edgePadding = 0.dp,
                modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
            ) {
                pages.forEachIndexed { index, (_, name, items) ->
                    Tab(
                        selected = page == index,
                        onClick = { page = index },
                        text = { Text("$name (${items.size})") },
                    )
                }
            }
        }
    }
    val topPadding = if (showTabs) 0.dp else contentPadding.calculateTopPadding()
    if (filtered.isEmpty()) {
        Column {
            tabRow()
            EmptyScreen(
                message = stringResource(
                    if (filters.isActive && current?.third.orEmpty().isNotEmpty()) {
                        KMR.strings.anime_library_no_match
                    } else {
                        KMR.strings.anime_library_empty
                    },
                ),
                modifier = Modifier.padding(top = topPadding, bottom = contentPadding.calculateBottomPadding()),
            )
        }
        return
    }
    // Same items and display mode as the manga library
    val displayMode by remember { libraryPreferences.displayMode() }.collectAsState()
    // KMK: Panorama styles like the manga library (Color fill / Fit width / Wide)
    val panoramaStyle by remember { Injekt.get<eu.kanade.domain.ui.UiPreferences>().libraryPanoramaStyle() }
        .collectAsState()
    val usePanorama = displayMode == LibraryDisplayMode.ComfortableGridPanorama
    val doubleWide = usePanorama && panoramaStyle == eu.kanade.domain.ui.model.PanoramaStyle.WIDE
    val wideCovers = eu.kanade.presentation.components.rememberWideCovers()
    val knownWide = remember(filtered, doubleWide) {
        if (!doubleWide) {
            emptySet<Long>()
        } else {
            filtered.mapNotNullTo(HashSet()) { anime ->
                // The saved shape is width / height, keyed by the cover's id
                val widthByHeight = tachiyomi.domain.manga.model.MangaCover.coverRatioMap[anime.coverId()]
                    ?: return@mapNotNullTo null
                anime.id.takeIf {
                    widthByHeight > 0f && 1f / widthByHeight <= eu.kanade.presentation.components.WideCovers.WIDE_RATIO
                }
            }
        }
    }
    val foundWide = wideCovers.ids
    val wide = remember(knownWide, foundWide) { if (foundWide.isEmpty()) knownWide else knownWide + foundWide }
    Box(modifier = Modifier.fillMaxSize()) {
        eu.kanade.presentation.components.ProvideCoverFillSettings {
        Column {
            tabRow()
            FastScrollLazyVerticalGrid(
                columns = when {
                    displayMode == LibraryDisplayMode.List -> GridCells.Fixed(1)
                    columns > 0 -> GridCells.Fixed(columns)
                    else -> GridCells.Adaptive(128.dp)
                },
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = topPadding + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
            ) {
                items(
                    filtered,
                    key = { it.id },
                    span = { anime -> wideSpan(doubleWide, wide, anime.id) },
                ) { anime ->
                    val onContinue: (() -> Unit)? = if (showContinue && (stats[anime.id]?.unseen ?: 0L) > 0) {
                        { continueWatching(anime) }
                    } else {
                        null
                    }
                    when (displayMode) {
                        LibraryDisplayMode.List -> MangaListItem(
                            coverData = anime.asMangaCover(),
                            title = anime.title,
                            onClick = { onClick(anime) },
                            onLongClick = { onLongClickAnime(anime) },
                            badge = { unseenBadge(anime) },
                            isSelected = anime.id in selected,
                            onClickContinueReading = onContinue,
                        )
                        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
                            coverData = anime.asMangaCover(),
                            title = anime.title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
                            onClick = { onClick(anime) },
                            onLongClick = { onLongClickAnime(anime) },
                            isSelected = anime.id in selected,
                            coverBadgeStart = { unseenBadge(anime) },
                            onClickContinueReading = onContinue,
                        )
                        else -> MangaComfortableGridItem(
                            coverData = anime.asMangaCover(),
                            title = anime.title,
                            onClick = { onClick(anime) },
                            onLongClick = { onLongClickAnime(anime) },
                            usePanoramaCover = usePanorama,
                            panoramaStyle = panoramaStyle,
                            onCoverRatio = { if (doubleWide && anime.id !in knownWide) wideCovers.report(anime.id, it) },
                            isSelected = anime.id in selected,
                            coverBadgeStart = { unseenBadge(anime) },
                            onClickContinueReading = onContinue,
                        )
                    }
                }
            }
        }
        }
        if (selected.isNotEmpty()) {
            AnimeSelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onSelectAll = { selected = selected + filtered.map { it.id } },
                onInvert = { selected = filtered.map { it.id }.toSet() - selected },
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
