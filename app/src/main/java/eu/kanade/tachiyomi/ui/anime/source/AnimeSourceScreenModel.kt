package eu.kanade.tachiyomi.ui.anime.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.presentation.anime.toMangaFilterList
import eu.kanade.presentation.anime.writeBackToAnime
import eu.kanade.tachiyomi.sumizuri.SumizuriTabs
import eu.kanade.tachiyomi.ui.anime.AnimeIncognito
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.FilterSerializer

/**
 * KMK: Browsing an anime source: popular, latest and search with filters. Loads one page at a time.
 */
class AnimeSourceScreenModel(
    private val sourceId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    sourceManager: AnimeSourceManager = Injekt.get(),
    // KMK -->
    private val getSavedSearches: GetSavedSearchBySourceId = Injekt.get(),
    private val insertSavedSearch: InsertSavedSearch = Injekt.get(),
    private val deleteSavedSearchById: DeleteSavedSearchById = Injekt.get(),
    // KMK <--
) : StateScreenModel<AnimeSourceScreenModel.State>(State()) {

    val source: AnimeCatalogueSource? = sourceManager.get(sourceId) as? AnimeCatalogueSource

    private var loadJob: Job? = null

    init {
        mutableState.update {
            it.copy(
                filters = source?.getFilterList() ?: AnimeFilterList(),
                // KMK: the Filter tab is kept until the app closes
                filterListing = keptFilterTabs[sourceId]?.first,
            )
        }
        // KMK: a source whose tab catalogs replace Popular and Latest opens on one of them
        if (!showHomeTab()) loadNextPage()
        // KMK -->
        getSavedSearches.subscribe(sourceId)
            .onEach { list ->
                val sorted = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SavedSearch::name))
                mutableState.update { it.copy(savedSearches = sorted.toImmutableList()) }
            }
            .launchIn(screenModelScope)
        AnimeIncognito.subscribe(sourceId)
            .onEach { on -> mutableState.update { it.copy(incognito = on) } }
            .launchIn(screenModelScope)
        // KMK <--
    }

    // KMK -->
    private val filterSerializer = FilterSerializer()

    fun toggleIncognito() = AnimeIncognito.toggle(sourceId)

    /** Saves the current search words and filters under [name] */
    fun saveSearch(name: String) {
        val current = state.value
        val query = (current.listing as? Listing.Search)?.query?.trim()?.takeIf { it.isNotBlank() }
        val json = runCatching {
            Json.encodeToString(JsonArray.serializer(), filterSerializer.serialize(current.filters.toMangaFilterList()))
        }.getOrNull()
        screenModelScope.launchIO {
            insertSavedSearch.await(
                SavedSearch(id = -1, source = sourceId, name = name.trim(), query = query, filtersJson = json),
            )
        }
    }

    /** Opens a saved search: its words and filters */
    fun loadSavedSearch(saved: SavedSearch) {
        val source = source ?: return
        val filters = source.getFilterList()
        saved.filtersJson?.let { text ->
            runCatching {
                val mirror = filters.toMangaFilterList()
                filterSerializer.deserialize(mirror, Json.decodeFromString(JsonArray.serializer(), text))
                mirror.writeBackToAnime()
            }.onFailure { logcat(LogPriority.ERROR, it) }
        }
        switchTo(Listing.Search(saved.query.orEmpty(), savedSearchId = saved.id), filters)
    }

    fun deleteSavedSearch(id: Long) {
        screenModelScope.launchIO { deleteSavedSearchById.await(id) }
    }

    // Selecting several titles to add to the library at once
    fun toggleSelectionMode() {
        mutableState.update { it.copy(selectionMode = !it.selectionMode, selection = emptySet()) }
    }

    fun toggleSelection(anime: Anime) {
        mutableState.update {
            val next = if (anime.id in it.selection) it.selection - anime.id else it.selection + anime.id
            it.copy(selectionMode = true, selection = next)
        }
    }

    fun selectAll() {
        mutableState.update { it.copy(selection = it.selection + it.items.map { anime -> anime.id }) }
    }

    fun reverseSelection() {
        mutableState.update { it.copy(selection = it.items.map { anime -> anime.id }.toSet() - it.selection) }
    }

    /** After the selection was added: leaves selection mode and shows the titles as in the library */
    fun finishSelection() {
        val ids = state.value.selection
        mutableState.update { it.copy(selectionMode = false, selection = emptySet()) }
        screenModelScope.launchIO {
            // The adding runs on its own; give it a moment before reading the titles back
            kotlinx.coroutines.delay(500)
            val updated = ids.mapNotNull { repository.getAnime(it) }.associateBy { it.id }
            mutableState.update { state -> state.copy(items = state.items.map { updated[it.id] ?: it }) }
        }
    }
    // KMK <--

    fun setListing(listing: Listing) {
        if (listing == state.value.listing && state.value.items.isNotEmpty()) return
        // KMK: Popular and Latest use the source's plain filters
        switchTo(listing, source?.getFilterList() ?: AnimeFilterList())
    }

    /** A typed search or the Filter panel's Filter button: the Filter tab, loaded fresh */
    fun search(query: String) {
        // KMK -->
        val listing = Listing.Search(query.trim())
        val filters = state.value.filters
        tabCache.remove(cacheKey(listing))
        keptFilterTabs[sourceId] = listing to filters
        mutableState.update { it.copy(filterListing = listing) }
        switchTo(listing, filters, force = true)
        // KMK <--
    }

    fun applyFilters() {
        search((state.value.listing as? Listing.Search)?.query.orEmpty())
    }

    // KMK -->
    /** Saves the shown tab's list, then shows [listing]: from its kept list when there is one, else loads it */
    private fun switchTo(listing: Listing, filters: AnimeFilterList, force: Boolean = false) {
        val current = state.value
        if (current.items.isNotEmpty()) {
            tabCache[cacheKey(current.listing)] =
                TabEntry(current.listing, current.filters, current.items, current.page, current.hasNextPage)
        }
        val kept = tabCache[cacheKey(listing)].takeUnless { force }
        loadJob?.cancel()
        if (kept != null) {
            mutableState.update {
                it.copy(
                    listing = kept.listing,
                    filters = kept.filters,
                    items = kept.items,
                    page = kept.page,
                    hasNextPage = kept.hasNextPage,
                    error = null,
                    isLoading = false,
                )
            }
            return
        }
        mutableState.update { it.copy(filters = filters) }
        setListingForce(listing)
    }

    private fun cacheKey(listing: Listing): String = "$sourceId:" + tabKey(listing)

    /** Tapping the selected tab: forget its kept list and load it again from page 1 */
    fun reloadListing() {
        val listing = state.value.listing
        tabCache.remove(cacheKey(listing))
        setListingForce(listing)
    }

    /** The Filter button or a swipe: back to the last filter / typed search's results, if there is one */
    fun showFilterTab(): Boolean {
        val listing = state.value.filterListing ?: return false
        if (state.value.listing == listing) return true
        val filters = keptFilterTabs[sourceId]?.second ?: state.value.filters
        switchTo(listing, filters)
        return true
    }

    /** True when the Filter panel has something selected (anything not at its default) */
    fun filtersChanged(): Boolean = runCatching {
        val default = source?.getFilterList() ?: return@runCatching false
        filterSerializer.serialize(state.value.filters.toMangaFilterList()) !=
            filterSerializer.serialize(default.toMangaFilterList())
    }.getOrDefault(false)

    /**
     * Clear in the Filter panel: empties the panel and forgets the Filter tab (its results and its place in the
     * swipe order). When it's the tab being shown, Browse goes back to its first tab.
     */
    fun clearFilterTab() {
        val filterListing = state.value.filterListing
        filterListing?.let { tabCache.remove(cacheKey(it)) }
        keptFilterTabs.remove(sourceId)
        val showing = state.value.listing.let { it is Listing.Search && tabKey(it) == FILTER_TAB }
        mutableState.update { it.copy(filterListing = null) }
        resetFilters()
        if (showing && !showHomeTab()) setListing(Listing.Popular)
    }
    // KMK <--

    fun resetFilters() {
        mutableState.update { it.copy(filters = source?.getFilterList() ?: AnimeFilterList()) }
    }

    // KMK -->
    /** Lists one of a JS / JSON source's catalogs, the way picking it in the filter sheet with no search does */
    fun showCatalog(id: String) {
        val source = source ?: return
        val tabs = source as? SumizuriTabs ?: return
        val filters = source.getFilterList()
        if (runCatching { tabs.selectCatalog(filters, id) }.getOrNull() != true) return
        runCatching { tabs.rememberTab(id) }
        switchTo(Listing.Search("", catalogId = id), filters)
    }

    /** For a source with tab catalogs: opens the one used last (or the first). False when it has none */
    fun showHomeTab(): Boolean {
        val id = runCatching { (source as? SumizuriTabs)?.homeTab() }.getOrNull() ?: return false
        showCatalog(id)
        return true
    }
    // KMK <--

    private fun setListingForce(listing: Listing) {
        loadJob?.cancel()
        mutableState.update {
            it.copy(listing = listing, items = emptyList(), page = 0, hasNextPage = true, error = null, isLoading = false)
        }
        loadNextPage()
    }

    fun retry() {
        mutableState.update { it.copy(error = null, hasNextPage = true) }
        loadNextPage()
    }

    fun loadNextPage() {
        val source = source ?: return
        val current = state.value
        if (current.isLoading || !current.hasNextPage || current.error != null) return
        val nextPage = current.page + 1
        mutableState.update { it.copy(isLoading = true) }
        loadJob = screenModelScope.launchIO {
            try {
                val page = when (val listing = current.listing) {
                    Listing.Popular -> source.getPopularAnime(nextPage)
                    Listing.Latest -> source.getLatestUpdates(nextPage)
                    is Listing.Search -> source.getSearchAnime(nextPage, listing.query, current.filters)
                }
                val items = page.animes.map { repository.networkToLocal(it, source.id) }
                mutableState.update {
                    it.copy(
                        items = (it.items + items).distinctBy { anime -> anime.id },
                        page = nextPage,
                        hasNextPage = page.hasNextPage && items.isNotEmpty(),
                        isLoading = false,
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                mutableState.update { it.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    // KMK --> preview panel: first tap on a cover shows its episodes on the left, like manga browsing
    private val _preview = kotlinx.coroutines.flow.MutableStateFlow(Preview())
    val preview: kotlinx.coroutines.flow.StateFlow<Preview> = _preview
    private var previewJob: Job? = null
    private var previewRefreshJob: Job? = null

    fun selectPreview(anime: Anime, showEpisodes: Boolean = true, showPlayer: Boolean = true) {
        previewJob?.cancel()
        previewRefreshJob?.cancel()
        _preview.value = Preview(anime = anime, panelOpen = showEpisodes, playerPanelOpen = showPlayer, loading = true)
        eu.kanade.presentation.anime.loadAnimeSeedColor(anime) { color ->
            _preview.update { if (it.anime?.id == anime.id) it.copy(seedColor = color) else it }
        }
        previewJob = screenModelScope.launchIO {
            kotlinx.coroutines.flow.combine(
                repository.subscribeAnime(anime.id),
                repository.subscribeEpisodes(anime.id),
            ) { a, eps -> a to eps }.collect { (a, eps) ->
                _preview.update {
                    it.copy(
                        anime = a ?: it.anime,
                        episodes = if (a?.sortAscending == true) {
                            eps.sortedByDescending { e -> e.sourceOrder }
                        } else {
                            eps.sortedBy { e -> e.sourceOrder }
                        },
                    )
                }
            }
        }
        val source = source ?: return
        previewRefreshJob = screenModelScope.launchIO {
            try {
                val current = repository.getAnime(anime.id) ?: anime
                if (!current.initialized || repository.getEpisodes(anime.id).isEmpty()) {
                    repository.refreshFromSource(current, source)
                }
                _preview.update { if (it.anime?.id == anime.id) it.copy(loading = false) else it }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                _preview.update {
                    if (it.anime?.id == anime.id) it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) else it
                }
            }
        }
    }

    fun clearPreview() {
        previewJob?.cancel()
        previewRefreshJob?.cancel()
        _preview.value = Preview()
    }

    fun closePreviewPanel() {
        _preview.update { it.copy(panelOpen = false) }
        if (!_preview.value.playerPanelOpen) clearPreview()
    }

    /** The right panel with the video player */
    fun closePlayerPanel() {
        _preview.update { it.copy(playerPanelOpen = false) }
        if (!_preview.value.panelOpen) clearPreview()
    }

    fun openPlayerPanel() {
        if (_preview.value.anime == null) return
        _preview.update { it.copy(playerPanelOpen = true) }
    }

    // KMK: the Left Panel button opens the episode panel for the cover that is already selected
    fun openPreviewPanel() {
        if (_preview.value.anime == null) return
        _preview.update { it.copy(panelOpen = true) }
    }

    fun toggleFavorite(anime: Anime) {
        screenModelScope.launchIO {
            val current = repository.getAnime(anime.id) ?: anime
            repository.setFavorite(current, !current.favorite)
            val updated = repository.getAnime(anime.id) ?: return@launchIO
            mutableState.update { state -> state.copy(items = state.items.map { if (it.id == updated.id) updated else it }) }
        }
    }

    fun toggleSeen(episode: eu.kanade.tachiyomi.data.anime.Episode) {
        screenModelScope.launchIO { repository.setSeen(listOf(episode), !episode.seen) }
    }

    fun toggleBookmark(episode: eu.kanade.tachiyomi.data.anime.Episode) {
        screenModelScope.launchIO { repository.setBookmark(episode, !episode.bookmark) }
    }

    /** Remembers where the preview stopped so the full player picks up from there */
    fun savePreviewPosition(episodeId: Long, positionMs: Long, then: () -> Unit) {
        screenModelScope.launchIO {
            if (positionMs > 0) repository.updateEpisode(episodeId, lastSecondSeen = positionMs)
            tachiyomi.core.common.util.lang.withUIContext { then() }
        }
    }

    fun toggleEpisodeSort() {
        val anime = _preview.value.anime ?: return
        screenModelScope.launchIO {
            val current = repository.getAnime(anime.id) ?: return@launchIO
            repository.setEpisodeFlags(current, current.episodeFlags xor Anime.SORT_ASCENDING)
        }
    }

    @Immutable
    data class Preview(
        val anime: Anime? = null,
        val panelOpen: Boolean = false,
        val playerPanelOpen: Boolean = false,
        val episodes: List<eu.kanade.tachiyomi.data.anime.Episode> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val seedColor: androidx.compose.ui.graphics.Color? = null,
    )
    // KMK <--

    sealed interface Listing {
        data object Popular : Listing
        data object Latest : Listing
        data class Search(
            val query: String,
            // KMK: the saved search it came from
            val savedSearchId: Long? = null,
            // KMK: the JS / JSON source's tab catalog it lists
            val catalogId: String? = null,
        ) : Listing
    }

    @Immutable
    data class State(
        val listing: Listing = Listing.Popular,
        val items: List<Anime> = emptyList(),
        val page: Int = 0,
        val hasNextPage: Boolean = true,
        val isLoading: Boolean = false,
        val error: String? = null,
        val filters: AnimeFilterList = AnimeFilterList(),
        // KMK -->
        val savedSearches: ImmutableList<SavedSearch> = persistentListOf(),
        val incognito: Boolean = false,
        val selectionMode: Boolean = false,
        val selection: Set<Long> = emptySet(),
        /** The Filter tab: the last filter / typed search, until Clear */
        val filterListing: Listing.Search? = null,
        // KMK <--
    )

    // KMK -->
    /** A tab's list as it was left */
    private class TabEntry(
        val listing: Listing,
        val filters: AnimeFilterList,
        val items: List<Anime>,
        val page: Int,
        val hasNextPage: Boolean,
    )

    companion object {
        const val FILTER_TAB = "filter"

        /** Which tab a list belongs to: popular, latest, filter, catalog:<id> or saved:<id> */
        fun tabKey(listing: Listing): String = when (listing) {
            Listing.Popular -> "popular"
            Listing.Latest -> "latest"
            is Listing.Search -> when {
                listing.savedSearchId != null -> "saved:${listing.savedSearchId}"
                listing.catalogId != null -> "catalog:${listing.catalogId}"
                else -> FILTER_TAB
            }
        }

        // Kept until the app closes, like the manga Browse tabs
        private val tabCache = java.util.concurrent.ConcurrentHashMap<String, TabEntry>()
        private val keptFilterTabs = java.util.concurrent.ConcurrentHashMap<Long, Pair<Listing.Search, AnimeFilterList>>()
    }
    // KMK <--
}
