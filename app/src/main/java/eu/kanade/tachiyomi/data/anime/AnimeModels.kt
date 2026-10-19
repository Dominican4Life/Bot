package eu.kanade.tachiyomi.data.anime

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import tachiyomi.core.common.preference.TriState

/** KMK: An anime saved in the database (merged from Anikku). */
@Immutable
data class Anime(
    val id: Long,
    val source: Long,
    val url: String,
    val title: String,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val backgroundUrl: String?,
    val favorite: Boolean,
    val initialized: Boolean,
    val dateAdded: Long,
    val lastUpdate: Long,
    val fetchType: FetchType,
    val episodeFlags: Long,
    // KMK --> seasons, saved like Anikku's (migration 56)
    /** The anime this one is a season of, or null */
    val parentId: Long? = null,
    /** Sort, filters and display of this anime's seasons (see [AnimeSeasonFlags]) */
    val seasonFlags: Long = 0L,
    /** Season number from the source or read from the title, -1 when unknown */
    val seasonNumber: Double = -1.0,
    /** Position in the source's season list */
    val seasonSourceOrder: Long = 0L,
    // KMK <--
) {
    fun toSAnime(): SAnime = SAnime.create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre?.joinToString(", ")
        it.status = status.toInt()
        it.thumbnail_url = thumbnailUrl
        it.background_url = backgroundUrl
        it.fetch_type = fetchType
        it.season_number = seasonNumber
        it.initialized = initialized
    }

    // KMK --> seasons
    val isSeason: Boolean
        get() = parentId != null
    // KMK <--

    /** Newest episodes first unless the user flipped it */
    val sortAscending: Boolean
        get() = episodeFlags and SORT_ASCENDING != 0L

    /** One of [SORT_BY_SOURCE], [SORT_BY_NUMBER], [SORT_BY_UPLOAD_DATE], [SORT_BY_ALPHABET] */
    val sortMode: Long
        get() = episodeFlags and SORT_MODE_MASK

    val displayNumber: Boolean
        get() = episodeFlags and DISPLAY_NUMBER != 0L

    val unseenFilter: TriState
        get() = triState(FILTER_UNSEEN, FILTER_UNSEEN_EXCLUDE)

    val bookmarkedFilter: TriState
        get() = triState(FILTER_BOOKMARKED, FILTER_BOOKMARKED_EXCLUDE)

    val fillermarkedFilter: TriState
        get() = triState(FILTER_FILLER_INCLUDE, FILTER_HIDE_FILLER)

    val downloadedFilter: TriState
        get() = triState(FILTER_DOWNLOADED, FILTER_DOWNLOADED_EXCLUDE)

    private fun triState(include: Long, exclude: Long): TriState = when {
        episodeFlags and include != 0L -> TriState.ENABLED_IS
        episodeFlags and exclude != 0L -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

    companion object {
        const val SORT_ASCENDING = 1L

        // Each filter is a pair of bits: "only these" and "hide these" (older builds used the first set only)
        const val FILTER_UNSEEN = 2L
        const val FILTER_BOOKMARKED = 4L
        const val FILTER_HIDE_FILLER = 8L
        const val FILTER_UNSEEN_EXCLUDE = 16L
        const val FILTER_BOOKMARKED_EXCLUDE = 32L
        const val FILTER_FILLER_INCLUDE = 64L
        const val FILTER_DOWNLOADED = 128L
        const val FILTER_DOWNLOADED_EXCLUDE = 256L

        const val SORT_BY_SOURCE = 0L
        const val SORT_BY_NUMBER = 512L
        const val SORT_BY_UPLOAD_DATE = 1024L
        const val SORT_BY_ALPHABET = 1536L
        const val SORT_MODE_MASK = 1536L

        const val DISPLAY_NUMBER = 2048L

        const val ALL_FILTERS = FILTER_UNSEEN or FILTER_BOOKMARKED or FILTER_HIDE_FILLER or FILTER_UNSEEN_EXCLUDE or
            FILTER_BOOKMARKED_EXCLUDE or FILTER_FILLER_INCLUDE or FILTER_DOWNLOADED or FILTER_DOWNLOADED_EXCLUDE

        /** Sets one filter's pair of bits from a [TriState] */
        fun withTriState(flags: Long, include: Long, exclude: Long, state: TriState): Long {
            val cleared = flags and (include or exclude).inv()
            return when (state) {
                TriState.DISABLED -> cleared
                TriState.ENABLED_IS -> cleared or include
                TriState.ENABLED_NOT -> cleared or exclude
            }
        }
    }
}

/** KMK: An episode saved in the database. */
@Immutable
data class Episode(
    val id: Long,
    val animeId: Long,
    val url: String,
    val name: String,
    val scanlator: String?,
    val summary: String?,
    val previewUrl: String?,
    val seen: Boolean,
    val bookmark: Boolean,
    val fillermark: Boolean,
    val lastSecondSeen: Long,
    val totalSeconds: Long,
    val episodeNumber: Double,
    val sourceOrder: Long,
    val dateFetch: Long,
    val dateUpload: Long,
) {
    fun toSEpisode(): SEpisode = SEpisode.create().also {
        it.url = url
        it.name = name
        it.scanlator = scanlator
        it.summary = summary
        it.preview_url = previewUrl
        it.fillermark = fillermark
        it.episode_number = episodeNumber.toFloat()
        it.date_upload = dateUpload
    }
}

internal fun String?.toGenreList(): List<String>? =
    this?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }

/** KMK: One row of the anime watch history (latest episode watched per anime). */
@Immutable
data class AnimeHistoryItem(
    val animeId: Long,
    val source: Long,
    val title: String,
    val thumbnailUrl: String?,
    val favorite: Boolean,
    val episodeId: Long,
    val episodeName: String,
    val episodeNumber: Double,
    val lastSecondSeen: Long,
    val totalSeconds: Long,
    val lastSeen: Long,
)

/** KMK: A new episode of an anime in the library. */
@Immutable
data class AnimeUpdateItem(
    val animeId: Long,
    val source: Long,
    val title: String,
    val thumbnailUrl: String?,
    val episodeId: Long,
    val episodeName: String,
    val episodeNumber: Double,
    val seen: Boolean,
    val bookmark: Boolean,
    val lastSecondSeen: Long,
    val totalSeconds: Long,
    val dateFetch: Long,
)

/** KMK: a category of the anime library */
data class AnimeCategory(
    val id: Long,
    val name: String,
    val order: Long,
)

/** KMK: what the user changed with Edit info. Null keeps the source's value. */
@Immutable
data class AnimeCustomInfo(
    val animeId: Long,
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    /** Comma separated, like the source's */
    val genre: String? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
) {
    fun isEmpty() = title == null && author == null && artist == null && description == null && genre == null &&
        status == null && thumbnailUrl == null
}

/** KMK: per-anime numbers for the anime library */
@Immutable
data class AnimeLibraryStats(
    val total: Long,
    val seen: Long,
    val bookmarked: Long,
    val inProgress: Long,
    val latestUpload: Long,
    val latestFetch: Long,
    val lastSeen: Long,
) {
    val unseen: Long get() = (total - seen).coerceAtLeast(0)
    val started: Boolean get() = seen > 0 || inProgress > 0
}
