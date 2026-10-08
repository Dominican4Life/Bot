package eu.kanade.tachiyomi.data.anime

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode

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
        it.initialized = initialized
    }

    /** Newest episodes first unless the user flipped it */
    val sortAscending: Boolean
        get() = episodeFlags and SORT_ASCENDING != 0L

    companion object {
        const val SORT_ASCENDING = 1L
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
