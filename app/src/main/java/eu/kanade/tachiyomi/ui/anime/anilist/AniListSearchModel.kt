package eu.kanade.tachiyomi.ui.anime.anilist

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** KMK: The ways AniList results can be ordered. [value] is AniList's sort name without "_DESC". */
enum class AniListSort(val value: String?, val label: StringResource) {
    BEST_MATCH(null, KMR.strings.anilist_sort_best_match),
    TRENDING("TRENDING", KMR.strings.anilist_sort_trending),
    POPULARITY("POPULARITY", KMR.strings.anilist_sort_popularity),
    TITLE("TITLE_ROMAJI", KMR.strings.anilist_sort_title),
    SCORE("SCORE", KMR.strings.anilist_sort_score),
    RELEASE_DATE("START_DATE", KMR.strings.anilist_sort_release),
}

/** KMK: Everything picked in the globe tab's Genres, Season, Format, Status and Sort lists */
@Immutable
data class AniListFilters(
    val genresIncluded: List<String> = emptyList(),
    val genresExcluded: List<String> = emptyList(),
    val tagsIncluded: List<String> = emptyList(),
    val tagsExcluded: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val season: String? = null,
    val year: Int? = null,
    val sort: AniListSort = AniListSort.BEST_MATCH,
    val ascending: Boolean = false,
) {
    /** Best match like Hayase when something was typed; trending when nothing was */
    fun sortValues(hasQuery: Boolean, hasIds: Boolean): List<String> {
        val value = sort.value
            ?: return if (hasQuery || hasIds) {
                listOf("SEARCH_MATCH", "POPULARITY_DESC")
            } else {
                listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
        return if (ascending) listOf(value) else listOf("${value}_DESC")
    }

    /** Tap: off → included → off. Hold: excluded (or off when already excluded). */
    fun toggleGenre(name: String, isTag: Boolean, exclude: Boolean): AniListFilters {
        val included = if (isTag) tagsIncluded else genresIncluded
        val excluded = if (isTag) tagsExcluded else genresExcluded
        val newIncluded: List<String>
        val newExcluded: List<String>
        when {
            exclude && name in excluded -> {
                newIncluded = included
                newExcluded = excluded - name
            }
            exclude -> {
                newIncluded = included - name
                newExcluded = excluded + name
            }
            name in included || name in excluded -> {
                newIncluded = included - name
                newExcluded = excluded - name
            }
            else -> {
                newIncluded = included + name
                newExcluded = excluded
            }
        }
        return if (isTag) {
            copy(tagsIncluded = newIncluded, tagsExcluded = newExcluded)
        } else {
            copy(genresIncluded = newIncluded, genresExcluded = newExcluded)
        }
    }

    /** Like the library's sort menu: a new choice starts ascending, the same one again flips it */
    fun pickSort(choice: AniListSort): AniListFilters = when {
        choice == AniListSort.BEST_MATCH -> copy(sort = choice, ascending = false)
        choice == sort -> copy(ascending = !ascending)
        else -> copy(sort = choice, ascending = true)
    }

    val isEmpty: Boolean
        get() = genresIncluded.isEmpty() && genresExcluded.isEmpty() && tagsIncluded.isEmpty() &&
            tagsExcluded.isEmpty() && formats.isEmpty() && statuses.isEmpty() && season == null && year == null &&
            sort == AniListSort.BEST_MATCH
}

/** KMK: A small note shown at the top right for a moment, like Shiru's */
@Immutable
data class AniListNotice(
    val title: String,
    val description: String? = null,
    val loading: Boolean = false,
    val isError: Boolean = false,
    val id: Long = System.nanoTime(),
)

/**
 * KMK: The globe tab of anime global search: AniList search with genre, tag, season, format and status filters,
 * a sort and screenshot search (trace.moe). Kept by the screen model so it stays when coming back from a title.
 */
class AniListSearchModel(
    private val scope: CoroutineScope,
    /** Manga search (manga global search) instead of anime */
    val manga: Boolean = false,
) {

    private val store = Injekt.get<PreferenceStore>()
    private val app = Injekt.get<Application>()
    val largeCardsPref = store.getBoolean("anilist_search_large_cards", false)

    /** The Format list: anime and manga have different ones */
    val formats: List<String> get() = if (manga) MANGA_FORMATS else FORMATS
    private val genresPref = store.getString("anilist_search_genres", "")
    private val tagsPref = store.getString("anilist_search_tags", "")

    var filters by mutableStateOf(AniListFilters())
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<AniListMedia>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasNext by mutableStateOf(false)
        private set

    /** Screenshot search: the matches, best first, while the results show them */
    var traceMatches by mutableStateOf<List<TraceMoeMatch>?>(null)
        private set

    var genres by mutableStateOf(loadGenres())
        private set
    var tags by mutableStateOf(loadTags())
        private set
    var notice by mutableStateOf<AniListNotice?>(null)
        private set

    private var started = false
    private var page = 1
    private var searchJob: Job? = null

    /** The first time the tab is shown: trending (or the query), and the tag list if it was never fetched */
    fun start(initialQuery: String) {
        if (started) return
        started = true
        query = initialQuery
        reload()
        if (tags.isEmpty()) refreshGenres(silent = true)
    }

    fun search(newQuery: String) {
        started = true
        query = newQuery
        traceMatches = null
        reload()
    }

    fun updateFilters(transform: (AniListFilters) -> AniListFilters) {
        val updated = transform(filters)
        if (updated == filters) return
        filters = updated
        reload(debounceMs = 400)
    }

    fun clearScreenshot() {
        traceMatches = null
        reload()
    }

    private fun reload(debounceMs: Long = 0) {
        searchJob?.cancel()
        page = 1
        loading = true
        error = null
        searchJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            try {
                val ids = traceMatches?.map { it.anilistId }?.distinct()
                val result = AniListApi.search(filters, if (ids != null) "" else query, 1, ids, manga)
                results = if (ids != null) result.media.sortedBy { ids.indexOf(it.id) } else result.media
                hasNext = result.hasNextPage && ids == null
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                error = e.message ?: e.javaClass.simpleName
                results = emptyList()
                hasNext = false
            } finally {
                if (coroutineContext.isActive) loading = false
            }
        }
    }

    fun loadMore() {
        if (loading || !hasNext) return
        loading = true
        val next = page + 1
        searchJob = scope.launch {
            try {
                val result = AniListApi.search(filters, query, next, manga = manga)
                page = next
                results = (results + result.media).distinctBy { it.id }
                hasNext = result.hasNextPage
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                hasNext = false
            } finally {
                if (coroutineContext.isActive) loading = false
            }
        }
    }

    /** Holding the # button: fetches AniList's genres and tags again */
    fun refreshGenres(silent: Boolean = false) {
        if (!silent) {
            notice = AniListNotice(
                title = app.stringResource(KMR.strings.anilist_genres_updating),
                description = app.stringResource(KMR.strings.anilist_genres_updating_desc),
                loading = true,
            )
        }
        scope.launch {
            try {
                val (newGenres, newTags) = AniListApi.genresAndTags()
                if (newGenres.isNotEmpty()) {
                    genres = newGenres
                    genresPref.set(newGenres.joinToString("\n"))
                }
                if (newTags.isNotEmpty()) {
                    tags = newTags
                    tagsPref.set(newTags.joinToString("\n") { "${it.name}\t${it.category}\t${it.isAdult}" })
                }
                if (!silent) {
                    notice = AniListNotice(
                        title = app.stringResource(KMR.strings.anilist_genres_updated_title),
                        description = app.stringResource(KMR.strings.anilist_genres_updated, genres.size, tags.size),
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (!silent) {
                    notice = AniListNotice(
                        title = app.stringResource(KMR.strings.anilist_genres_failed_title),
                        description = e.message ?: e.javaClass.simpleName,
                        isError = true,
                    )
                }
            }
        }
    }

    /** Screenshot search: finds where a picture comes from and shows those anime, best match first */
    fun searchScreenshot(image: ByteArray, mime: String?) {
        notice = AniListNotice(
            title = app.stringResource(KMR.strings.anilist_screenshot_searching),
            description = app.stringResource(KMR.strings.anilist_screenshot_hint),
            loading = true,
        )
        scope.launch {
            try {
                val matches = AniListApi.traceMoe(image, mime)
                val good = matches.filter { it.similarity >= 0.8 }.ifEmpty { matches.take(3) }
                    .distinctBy { it.anilistId }
                    .take(10)
                if (good.isEmpty()) {
                    notice = AniListNotice(
                        title = app.stringResource(KMR.strings.anilist_screenshot_none),
                        description = app.stringResource(KMR.strings.anilist_screenshot_none_desc),
                        isError = true,
                    )
                    return@launch
                }
                val best = good.first()
                traceMatches = good
                reload()
                notice = AniListNotice(
                    title = app.stringResource(KMR.strings.anilist_screenshot_found_title),
                    description = app.stringResource(
                        KMR.strings.anilist_screenshot_found,
                        best.title ?: "?",
                        best.episode ?: "?",
                        "${(best.similarity * 100).roundToInt()}%",
                    ),
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                notice = AniListNotice(
                    title = app.stringResource(KMR.strings.anilist_screenshot_none),
                    description = e.message ?: e.javaClass.simpleName,
                    isError = true,
                )
            }
        }
    }

    fun dismissNotice(id: Long) {
        if (notice?.id == id) notice = null
    }

    private fun loadGenres(): List<String> =
        genresPref.get().split("\n").filter { it.isNotBlank() }.ifEmpty { DEFAULT_GENRES }

    private fun loadTags(): List<AniListTag> = tagsPref.get().split("\n").mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.firstOrNull().isNullOrBlank()) return@mapNotNull null
        AniListTag(parts[0], parts.getOrNull(1).orEmpty(), parts.getOrNull(2) == "true")
    }

    companion object {
        /** AniList's genres, until they're fetched */
        val DEFAULT_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Hentai", "Horror", "Mahou Shoujo",
            "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports",
            "Supernatural", "Thriller",
        )

        val FORMATS = listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
        val MANGA_FORMATS = listOf("MANGA", "NOVEL", "ONE_SHOT")
        val STATUSES = listOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "HIATUS", "CANCELLED")
        val SEASONS = listOf(
            "WINTER" to KMR.strings.anilist_season_winter,
            "SPRING" to KMR.strings.anilist_season_spring,
            "SUMMER" to KMR.strings.anilist_season_summer,
            "FALL" to KMR.strings.anilist_season_fall,
        )
    }
}
