package eu.kanade.tachiyomi.data.anime

import androidx.compose.runtime.Immutable
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.applyFilter

/** KMK: The anime library's filters, the set Anikku's library has. */
@Immutable
data class AnimeLibraryFilters(
    val downloaded: TriState = TriState.DISABLED,
    val unseen: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val completed: TriState = TriState.DISABLED,
    val lewd: TriState = TriState.DISABLED,
    val categories: Boolean = false,
    val includedCategories: Set<Long> = emptySet(),
    val excludedCategories: Set<Long> = emptySet(),
) {
    val isActive: Boolean
        get() = categories ||
            listOf(downloaded, unseen, started, bookmarked, completed, lewd).any { it != TriState.DISABLED }

    /**
     * [stats] is null for an anime with no saved episodes; [downloadCount] is only asked when that filter is on.
     */
    fun matches(
        anime: Anime,
        stats: AnimeLibraryStats?,
        categoryIds: List<Long>,
        sourceName: String?,
        downloadCount: (Anime) -> Int,
    ): Boolean =
        applyFilter(downloaded) { downloadCount(anime) > 0 } &&
            applyFilter(unseen) { (stats?.unseenCount ?: 0L) > 0L } &&
            applyFilter(started) { (stats?.seenCount ?: 0L) > 0L } &&
            applyFilter(bookmarked) { (stats?.bookmarkCount ?: 0L) > 0L } &&
            applyFilter(completed) { anime.status.toInt() == SAnime.COMPLETED } &&
            applyFilter(lewd) { anime.isLewd(sourceName) } &&
            matchesCategories(categoryIds)

    /** Same rule as the manga library: in every included category and in none of the excluded ones */
    private fun matchesCategories(categoryIds: List<Long>): Boolean {
        if (!categories) return true
        if (categoryIds.isEmpty()) return includedCategories.isEmpty()
        return excludedCategories.none { it in categoryIds } && includedCategories.all { it in categoryIds }
    }
}

/** Same idea as the manga library's lewd check: an adult source, or adult tags */
private fun Anime.isLewd(sourceName: String?): Boolean =
    sourceName?.contains("hentai", ignoreCase = true) == true ||
        genre.orEmpty().any { tag -> LEWD_TAGS.any { tag.contains(it, ignoreCase = true) } }

private val LEWD_TAGS = listOf("hentai", "adult", "smut", "lewd", "nsfw", "erotica", "pornographic", "mature", "18+")

/** The filters as set in the library's filter sheet; "Downloaded only" mode forces the downloaded one on */
fun LibraryPreferences.animeLibraryFilters(basePreferences: BasePreferences): Flow<AnimeLibraryFilters> =
    combine(
        combine(
            animeFilterDownloaded().changes(),
            animeFilterUnseen().changes(),
            animeFilterStarted().changes(),
            ::Triple,
        ),
        combine(
            animeFilterBookmarked().changes(),
            animeFilterCompleted().changes(),
            animeFilterLewd().changes(),
            ::Triple,
        ),
        combine(
            animeFilterCategories().changes(),
            animeFilterCategoriesInclude().changes(),
            animeFilterCategoriesExclude().changes(),
            ::Triple,
        ),
        basePreferences.downloadedOnly().changes(),
    ) { (downloaded, unseen, started), (bookmarked, completed, lewd), (categories, included, excluded), downloadedOnly ->
        AnimeLibraryFilters(
            downloaded = if (downloadedOnly) TriState.ENABLED_IS else downloaded,
            unseen = unseen,
            started = started,
            bookmarked = bookmarked,
            completed = completed,
            lewd = lewd,
            categories = categories,
            includedCategories = included.mapNotNullTo(HashSet()) { it.toLongOrNull() },
            excludedCategories = excluded.mapNotNullTo(HashSet()) { it.toLongOrNull() },
        )
    }.distinctUntilChanged()
