package eu.kanade.tachiyomi.ui.anime.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Browsing an anime source: popular, latest and search with filters. Loads one page at a time.
 */
class AnimeSourceScreenModel(
    sourceId: Long,
    private val repository: AnimeRepository = Injekt.get(),
    sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeSourceScreenModel.State>(State()) {

    val source: AnimeCatalogueSource? = sourceManager.get(sourceId) as? AnimeCatalogueSource

    private var loadJob: Job? = null

    init {
        mutableState.update { it.copy(filters = source?.getFilterList() ?: AnimeFilterList()) }
        loadNextPage()
    }

    fun setListing(listing: Listing) {
        if (listing == state.value.listing && state.value.items.isNotEmpty()) return
        loadJob?.cancel()
        mutableState.update {
            it.copy(listing = listing, items = emptyList(), page = 0, hasNextPage = true, error = null, isLoading = false)
        }
        loadNextPage()
    }

    fun search(query: String) {
        setListingForce(Listing.Search(query.trim()))
    }

    fun applyFilters() {
        setListingForce(Listing.Search((state.value.listing as? Listing.Search)?.query.orEmpty()))
    }

    fun resetFilters() {
        mutableState.update { it.copy(filters = source?.getFilterList() ?: AnimeFilterList()) }
    }

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

    fun selectPreview(anime: Anime) {
        previewJob?.cancel()
        previewRefreshJob?.cancel()
        _preview.value = Preview(anime = anime, panelOpen = true, loading = true)
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
        val episodes: List<eu.kanade.tachiyomi.data.anime.Episode> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val seedColor: androidx.compose.ui.graphics.Color? = null,
    )
    // KMK <--

    sealed interface Listing {
        data object Popular : Listing
        data object Latest : Listing
        data class Search(val query: String) : Listing
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
    )
}
