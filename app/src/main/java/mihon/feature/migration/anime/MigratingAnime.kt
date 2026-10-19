package mihon.feature.migration.anime

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.Anime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.feature.migration.list.search.BaseSmartSearchEngine
import mihon.feature.migration.list.search.SearchAction
import kotlin.coroutines.CoroutineContext

/** KMK: One anime on the anime matching screen, like MigratingManga */
class MigratingAnime(
    val anime: Anime,
    val episodeCount: Int,
    val latestEpisode: Double?,
    val source: String,
    parentContext: CoroutineContext,
) {
    val migrationScope = CoroutineScope(parentContext + SupervisorJob() + Dispatchers.Default)

    var searchingJob: Deferred<Pair<Anime, AnimeMigrationListScreenModel.EpisodeInfo>?>? = null

    val searchResult = MutableStateFlow<SearchResult>(SearchResult.Searching)

    /** The match and the other close results found, to pick a different one; best first */
    val candidates = MutableStateFlow<List<Candidate>>(emptyList())

    /** A title found while matching; [episodeCount] is null until its episodes are fetched */
    data class Candidate(val anime: Anime, val source: String, val episodeCount: Int?)

    sealed interface SearchResult {
        data object Searching : SearchResult
        data object NotFound : SearchResult
        data class Success(
            val anime: Anime,
            val episodeCount: Int,
            val latestEpisode: Double?,
            val source: String,
        ) : SearchResult
    }
}

/** KMK: The manga smart search (close titles, deep search by keywords) for anime sources */
class AnimeSmartSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<SAnime>(extraSearchParams) {

    override fun getTitle(result: SAnime) = result.title

    /** Every close enough result, best first */
    suspend fun searchAll(source: AnimeCatalogueSource, title: String, deep: Boolean): List<SAnime> {
        val action: SearchAction<SAnime> = { query ->
            source.getSearchAnime(1, query, source.getFilterList()).animes
        }
        return if (deep) deepSearchAll(action, title) else regularSearchAll(action, title)
    }
}
