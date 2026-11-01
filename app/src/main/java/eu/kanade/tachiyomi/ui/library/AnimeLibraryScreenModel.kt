package eu.kanade.tachiyomi.ui.library

import android.app.Application
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.anime.AnimeLibraryFilters
import eu.kanade.presentation.anime.AnimeLibraryPage
import eu.kanade.presentation.anime.AnimeLibraryPrefs
import eu.kanade.presentation.anime.AnimeLibrarySort
import eu.kanade.presentation.anime.AnimeLibraryState
import eu.kanade.presentation.anime.filterAndSortAnime
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeAiring
import eu.kanade.tachiyomi.data.anime.AnimeAiringRepository
import eu.kanade.tachiyomi.data.anime.AnimeCategory
import eu.kanade.tachiyomi.data.anime.AnimeLibraryStats
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackStatus
import eu.kanade.tachiyomi.data.anime.track.AnimeTracking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.debounceAfterFirst
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96): the anime library's pipeline, like [LibraryScreenModel] for manga. Up to r95 the grouping,
 * filtering, sorting and the per-title downloaded counts ran inside Compose `remember` blocks on the main thread
 * (`rememberAnimeLibraryState`), once per database write, badge tick or search keystroke. Here the same work runs
 * on [Dispatchers.Default], the search is debounced, and the composables get the same [AnimeLibraryState].
 *
 * The flows that read the library are wrapped in [LibraryRefresh.pausedWhileRestoring], as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeLibraryScreenModel(
    private val repository: AnimeRepository = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val trackRepository: AnimeTrackRepository = Injekt.get(),
    private val context: Application = Injekt.get(),
) : ScreenModel {

    private val searchQuery = MutableStateFlow<String?>(null)

    fun search(query: String?) {
        searchQuery.value = query
    }

    /** What the database holds: null favourites until the first read */
    private data class Data(
        val favorites: List<Anime>?,
        val stats: Map<Long, AnimeLibraryStats>,
        val categories: List<AnimeCategory>,
        val links: Map<Long, Set<Long>>,
    )

    /** Every setting the pages depend on */
    private data class Settings(
        val filters: AnimeLibraryFilters,
        val sort: AnimeLibrarySort,
        val ascending: Boolean,
        val randomSeed: Long,
        val groupType: Int,
        val showDownloadBadge: Boolean,
        val showCategoryTabs: Boolean,
        val showItemCount: Boolean,
        val hiddenCategoryIds: Set<String>,
        val showHidden: Boolean,
    ) {
        val needDownloads: Boolean get() = showDownloadBadge || filters.downloaded != TriState.DISABLED
        val needTracks: Boolean
            get() = filters.usesTracks || sort == AnimeLibrarySort.TRACKER_SCORE ||
                groupType == LibraryGroup.BY_TRACK_STATUS
    }

    private data class Inputs(
        val data: Data,
        val settings: Settings,
        val searchQuery: String?,
        val downloadChanges: Int,
        val refreshTick: Int,
        val restoring: Boolean,
    )

    /** Keeps a value until its key changes, like a `remember(keys)` block */
    private class Memo<K, V> {
        private var key: K? = null
        private var value: V? = null
        private var set = false

        // suspend: the tracks memo reads the database inside `compute`
        @Suppress("UNCHECKED_CAST")
        suspend fun get(k: K, compute: suspend () -> V): V {
            if (!set || key != k) {
                value = compute()
                key = k
                set = true
            }
            return value as V
        }
    }

    private val downloadedMemo = Memo<List<Any?>, Map<Long, Int>>()
    private val tracksMemo = Memo<Pair<List<Anime>?, Boolean>, Map<Long, List<AnimeTrack>>>()
    private val groupsMemo = Memo<List<Any?>, List<Pair<Category, List<Anime>>>>()

    // Next airing episodes from AniList, only while sorting by them; a failed load keeps the last answer
    private var airingKey: Pair<List<Anime>?, AnimeLibrarySort>? = null
    private var airing: Map<Long, AnimeAiring> = emptyMap()

    // The download folder scan runs again when these change (what the old LaunchedEffect keyed on)
    private var lastScanKey: List<Any?>? = null

    private val dataFlow = combine(
        LibraryRefresh.pausedWhileRestoring(repository.subscribeFavorites())
            .map<List<Anime>, List<Anime>?> { it }
            .onStart { emit(null) },
        LibraryRefresh.pausedWhileRestoring(repository.subscribeFavoritesLibraryStats())
            .onStart { emit(emptyMap()) },
        repository.subscribeCategories().onStart { emit(emptyList()) },
        LibraryRefresh.pausedWhileRestoring(repository.subscribeCategoryLinks())
            .onStart { emit(emptyMap()) },
    ) { favorites, stats, categories, links -> Data(favorites, stats, categories, links) }

    @Suppress("UNCHECKED_CAST")
    private val settingsFlow = combine(
        filtersFlow(),
        AnimeLibraryPrefs.sort().changes(),
        AnimeLibraryPrefs.sortAscending().changes(),
        AnimeLibraryPrefs.randomSeed().changes(),
        AnimeLibraryPrefs.groupType().changes(),
        // Read again after a restore (LibraryRefresh), like the old `remember(restoreTick)`
        combine(AnimeLibraryPrefs.downloadBadge().changes(), LibraryRefresh.tick) { _, _ ->
            AnimeLibraryPrefs.downloadBadge().get()
        },
        libraryPreferences.categoryTabs().changes(),
        libraryPreferences.categoryNumberOfItems().changes(),
        AnimeLibraryPrefs.hiddenCategories().changes(),
        AnimeLibraryPrefs.showHiddenCategories().changes(),
    ) {
        Settings(
            filters = it[0] as AnimeLibraryFilters,
            sort = it[1] as AnimeLibrarySort,
            ascending = it[2] as Boolean,
            randomSeed = it[3] as Long,
            groupType = it[4] as Int,
            showDownloadBadge = it[5] as Boolean,
            showCategoryTabs = it[6] as Boolean,
            showItemCount = it[7] as Boolean,
            hiddenCategoryIds = it[8] as Set<String>,
            showHidden = it[9] as Boolean,
        )
    }

    val state: StateFlow<AnimeLibraryState> = combine(
        dataFlow,
        settingsFlow,
        searchQuery.debounceAfterFirst(SEARCH_DEBOUNCE_MS),
        downloadManager.changes,
        combine(LibraryRefresh.tick, LibraryRefresh.restoring) { tick, restoring -> tick to restoring },
    ) { data, settings, query, downloadChanges, (tick, restoring) ->
        Inputs(data, settings, query, downloadChanges, tick, restoring)
    }
        .mapLatest { build(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            screenModelScope,
            SharingStarted.Lazily,
            AnimeLibraryState(
                favorites = null,
                pages = emptyList(),
                stats = emptyMap(),
                downloaded = emptyMap(),
                filters = AnimeLibraryFilters(),
                groupType = AnimeLibraryPrefs.groupType().get(),
                showCategoryTabs = libraryPreferences.categoryTabs().get(),
                showItemCount = libraryPreferences.categoryNumberOfItems().get(),
            ),
        )

    /** The filters as they are now; the same values `rememberAnimeLibraryFilters` composes */
    @Suppress("UNCHECKED_CAST")
    private fun filtersFlow(): Flow<AnimeLibraryFilters> = combine(
        AnimeLibraryPrefs.filterDownloaded().changes(),
        AnimeLibraryPrefs.filterUnseen().changes(),
        AnimeLibraryPrefs.filterStarted().changes(),
        AnimeLibraryPrefs.filterBookmarked().changes(),
        AnimeLibraryPrefs.filterCompleted().changes(),
        AnimeLibraryPrefs.filterTrackedAny().changes(),
        AnimeLibraryPrefs.filterLewd().changes(),
        AnimeLibraryPrefs.filterCategories().changes(),
        AnimeLibraryPrefs.filterCategoriesInclude().changes(),
        AnimeLibraryPrefs.filterCategoriesExclude().changes(),
    ) {
        // KMK: only "Tracked" (any tracker), like Anikku's anime library
        val anyTrackerLoggedIn = AnimeTracking.services.any { service -> service.isLoggedIn }
        AnimeLibraryFilters(
            downloaded = it[0] as TriState,
            unseen = it[1] as TriState,
            started = it[2] as TriState,
            bookmarked = it[3] as TriState,
            completed = it[4] as TriState,
            tracked = emptyMap(),
            trackedAny = if (anyTrackerLoggedIn) it[5] as TriState else TriState.DISABLED,
            lewd = it[6] as TriState,
            categories = it[7] as Boolean,
            includedCategories = (it[8] as Set<String>).mapNotNull(String::toLongOrNull).toSet(),
            excludedCategories = (it[9] as Set<String>).mapNotNull(String::toLongOrNull).toSet(),
        )
    }

    /** Makes the pages from the inputs. Runs on [Dispatchers.Default], cancelled when newer inputs arrive. */
    private suspend fun build(inputs: Inputs): AnimeLibraryState {
        val (data, settings, query, downloadChanges, refreshTick, restoring) = inputs
        val favorites = data.favorites
        val list = favorites.orEmpty()

        // Look at each anime's download folder once, when downloads matter.
        // KMK: not while restoring: it looked at every anime's folder again for each anime added.
        // KMK (r94): one bounded scan, not one coroutine per title (that parked the IO pool with 64+ titles)
        val scanKey = listOf(favorites?.size, settings.needDownloads, refreshTick, restoring)
        if (scanKey != lastScanKey) {
            lastScanKey = scanKey
            if (settings.needDownloads && !restoring) downloadManager.refreshAll(list)
        }
        val downloaded = downloadedMemo.get(listOf(favorites, downloadChanges, settings.needDownloads, refreshTick)) {
            if (!settings.needDownloads) {
                emptyMap()
            } else {
                list.associate { it.id to downloadManager.downloadedCount(it) }
            }
        }
        val tracks = tracksMemo.get(favorites to settings.needTracks) {
            if (settings.needTracks) {
                runCatching { trackRepository.getAll().groupBy { it.animeId } }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
        }
        val airingNow = favorites to settings.sort
        if (airingNow != airingKey) {
            if (settings.sort == AnimeLibrarySort.NEXT_AIRING && favorites != null) {
                // The key is set only once the fetch finished: a build cancelled by newer inputs (mapLatest)
                // while the request was in flight tries again on the next build instead of never
                val fetched = runCatching { AnimeAiringRepository.forLibrary(favorites) }
                fetched.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                airing = fetched.getOrDefault(airing)
            }
            airingKey = airingNow
        }

        // The pages: categories, or groups of the whole library
        val groups = groupsMemo.get(
            listOf(
                favorites, data.categories, data.links, settings.groupType, tracks, settings.hiddenCategoryIds,
                settings.showHidden,
            ),
        ) {
            makeGroups(list, data.categories, data.links, settings, tracks)
        }

        val pages = groups.map { (category, animes) ->
            val searched = if (query.isNullOrBlank()) {
                animes
            } else {
                animes.filter { it.title.contains(query, ignoreCase = true) }
            }
            val shown = searched.filterAndSortAnime(
                data.stats, downloaded, tracks, settings.filters, settings.sort, settings.ascending,
                settings.randomSeed,
                airing = airing,
                links = data.links,
            )
            AnimeLibraryPage(category, animes, shown)
        }

        return AnimeLibraryState(
            favorites = favorites,
            pages = pages,
            stats = data.stats,
            downloaded = downloaded,
            filters = settings.filters,
            groupType = settings.groupType,
            showCategoryTabs = settings.showCategoryTabs,
            showItemCount = settings.showItemCount,
        )
    }

    private fun makeGroups(
        list: List<Anime>,
        categories: List<AnimeCategory>,
        links: Map<Long, Set<Long>>,
        settings: Settings,
        tracks: Map<Long, List<AnimeTrack>>,
    ): List<Pair<Category, List<Anime>>> = when (settings.groupType) {
        LibraryGroup.BY_SOURCE -> list.groupBy { it.source }
            .map { (sourceId, animes) ->
                val name = sourceManager.get(sourceId)?.name ?: sourceId.toString()
                name to (sourceId to animes)
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { pair: Pair<String, Pair<Long, List<Anime>>> -> pair.first },
            )
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
                    if (!settings.showHidden && category.id.toString() in settings.hiddenCategoryIds) return@forEach
                    add(
                        pageCategory(category.id, category.name, category.order) to
                            list.filter { category.id in links[it.id].orEmpty() },
                    )
                }
            }
        }
    }

    private fun pageCategory(id: Long, name: String, order: Long) =
        Category(id = id, name = name, order = order, flags = 0, hidden = false)

    private fun trackStatusName(status: Long) = when (status) {
        AnimeTrackStatus.WATCHING -> KMR.strings.anime_track_status_watching
        AnimeTrackStatus.COMPLETED -> KMR.strings.anime_track_status_completed
        AnimeTrackStatus.ON_HOLD -> KMR.strings.anime_track_status_on_hold
        AnimeTrackStatus.DROPPED -> KMR.strings.anime_track_status_dropped
        AnimeTrackStatus.REWATCHING -> KMR.strings.anime_track_status_rewatching
        AnimeTrackStatus.PLAN_TO_WATCH -> KMR.strings.anime_track_status_plan
        else -> SYMR.strings.not_tracked
    }

    private companion object {
        /** Like the manga library's search (SEARCH_DEBOUNCE_MILLIS); the first value is not delayed */
        const val SEARCH_DEBOUNCE_MS = 200L

        const val NOT_TRACKED_GROUP = 7L

        /** Tracking statuses in the order the manga library shows its own */
        val trackStatusOrder = listOf(
            AnimeTrackStatus.WATCHING,
            AnimeTrackStatus.REWATCHING,
            AnimeTrackStatus.PLAN_TO_WATCH,
            AnimeTrackStatus.ON_HOLD,
            AnimeTrackStatus.COMPLETED,
            AnimeTrackStatus.DROPPED,
        )

        /** Anime statuses with their name and place, like the manga library's Status groups */
        val statusGroups = mapOf(
            SAnime.ONGOING.toLong() to (MR.strings.ongoing to 1L),
            SAnime.COMPLETED.toLong() to (MR.strings.completed to 2L),
            SAnime.PUBLISHING_FINISHED.toLong() to (MR.strings.publishing_finished to 3L),
            SAnime.LICENSED.toLong() to (MR.strings.licensed to 4L),
            SAnime.ON_HIATUS.toLong() to (MR.strings.on_hiatus to 5L),
            SAnime.CANCELLED.toLong() to (MR.strings.cancelled to 6L),
            SAnime.UPCOMING.toLong() to (MR.strings.label_upcoming to 7L),
        )
    }
}
