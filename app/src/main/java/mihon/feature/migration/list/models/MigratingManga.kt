package mihon.feature.migration.list.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.feature.migration.list.MigrationListScreenModel.ChapterInfo
import tachiyomi.domain.manga.model.Manga
import kotlin.coroutines.CoroutineContext

class MigratingManga(
    val manga: Manga,
    val chapterCount: Int,
    val latestChapter: Double?,
    val source: String,
    parentContext: CoroutineContext,
) {
    val migrationScope = CoroutineScope(parentContext + SupervisorJob() + Dispatchers.Default)

    // KMK -->
    var searchingJob: Deferred<Pair<Manga, ChapterInfo>?>? = null
    // KMK <--

    val searchResult = MutableStateFlow<SearchResult>(SearchResult.Searching)

    // KMK -->
    /** The match and the other close results found, to pick a different one; best first */
    val candidates = MutableStateFlow<List<Candidate>>(emptyList())

    /** A title found while matching; [chapterCount] is null until its chapters are fetched */
    data class Candidate(val manga: Manga, val source: String, val chapterCount: Int?)
    // KMK <--

    sealed interface SearchResult {
        data object Searching : SearchResult
        data object NotFound : SearchResult
        data class Success(
            val manga: Manga,
            val chapterCount: Int,
            val latestChapter: Double?,
            val source: String,
        ) : SearchResult
    }
}
