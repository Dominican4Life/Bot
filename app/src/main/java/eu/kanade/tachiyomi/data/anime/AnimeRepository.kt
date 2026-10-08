package eu.kanade.tachiyomi.data.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import java.time.Instant

/**
 * KMK: Reads and writes anime and episodes (merged from Anikku).
 */
class AnimeRepository(
    private val handler: DatabaseHandler,
) {

    // Anime

    suspend fun getAnime(id: Long): Anime? = handler.awaitOneOrNull { animesQueries.getById(id, ::mapAnime) }

    fun subscribeAnime(id: Long): Flow<Anime?> = handler.subscribeToOneOrNull { animesQueries.getById(id, ::mapAnime) }

    fun subscribeFavorites(): Flow<List<Anime>> = handler.subscribeToList { animesQueries.getFavorites(::mapAnime) }

    /**
     * Returns the local copy of an anime from a source, inserting it the first time it's seen.
     */
    suspend fun networkToLocal(sAnime: SAnime, sourceId: Long): Anime {
        val existing = handler.awaitOneOrNull { animesQueries.getByUrlAndSource(sAnime.url, sourceId, ::mapAnime) }
        if (existing != null) {
            if (!existing.favorite) {
                handler.await {
                    animesQueries.updateThumbnailIfNotFavorite(
                        title = sAnime.title,
                        thumbnailUrl = sAnime.thumbnail_url,
                        id = existing.id,
                    )
                }
                return existing.copy(title = sAnime.title, thumbnailUrl = sAnime.thumbnail_url ?: existing.thumbnailUrl)
            }
            return existing
        }
        val id = handler.await(inTransaction = true) {
            animesQueries.insert(
                source = sourceId,
                url = sAnime.url,
                artist = sAnime.artist,
                author = sAnime.author,
                description = sAnime.description,
                genre = sAnime.genre,
                title = sAnime.title,
                status = sAnime.status.toLong(),
                thumbnailUrl = sAnime.thumbnail_url,
                backgroundUrl = sAnime.background_url,
                initialized = false,
                fetchType = sAnime.fetch_type.toLong(),
            )
            animesQueries.selectLastInsertedRowId().executeAsOne()
        }
        return getAnime(id)!!
    }

    suspend fun updateFromSource(anime: Anime, sAnime: SAnime) {
        handler.await {
            animesQueries.updateDetails(
                title = sAnime.title.ifBlank { anime.title },
                artist = sAnime.artist,
                author = sAnime.author,
                description = sAnime.description,
                genre = sAnime.genre,
                status = sAnime.status.toLong(),
                thumbnailUrl = sAnime.thumbnail_url,
                backgroundUrl = sAnime.background_url,
                initialized = true,
                fetchType = sAnime.fetch_type.toLong(),
                lastUpdate = Instant.now().toEpochMilli(),
                id = anime.id,
            )
        }
    }

    suspend fun setFavorite(anime: Anime, favorite: Boolean) {
        handler.await {
            animesQueries.setFavorite(
                favorite = favorite,
                dateAdded = if (favorite) Instant.now().toEpochMilli() else 0,
                id = anime.id,
            )
        }
    }

    suspend fun setEpisodeFlags(anime: Anime, flags: Long) {
        handler.await { animesQueries.setEpisodeFlags(flags, anime.id) }
    }

    // Episodes

    suspend fun getEpisodes(animeId: Long): List<Episode> =
        handler.awaitList { episodesQueries.getByAnimeId(animeId, ::mapEpisode) }

    fun subscribeEpisodes(animeId: Long): Flow<List<Episode>> =
        handler.subscribeToList { episodesQueries.getByAnimeId(animeId, ::mapEpisode) }

    suspend fun setSeen(episodes: List<Episode>, seen: Boolean) {
        handler.await(inTransaction = true) {
            episodes.forEach { episodesQueries.setSeen(seen, it.id) }
        }
    }

    suspend fun setBookmark(episode: Episode, bookmark: Boolean) {
        handler.await { episodesQueries.setBookmark(bookmark, episode.id) }
    }

    /**
     * Saves the episodes from the source: adds new ones, updates changed ones and removes the ones the source
     * no longer has. Keeps seen/bookmark/progress of existing episodes.
     */
    suspend fun syncEpisodes(anime: Anime, sourceEpisodes: List<SEpisode>) {
        val now = Instant.now().toEpochMilli()
        val existing = getEpisodes(anime.id).associateBy { it.url }
        val seenUrls = HashSet<String>()
        handler.await(inTransaction = true) {
            sourceEpisodes.distinctBy { it.url }.forEachIndexed { index, ep ->
                seenUrls += ep.url
                val old = existing[ep.url]
                val number = ep.episode_number.toDouble().takeIf { it >= 0 } ?: -1.0
                if (old == null) {
                    episodesQueries.insert(
                        animeId = anime.id,
                        url = ep.url,
                        name = ep.name,
                        scanlator = ep.scanlator,
                        summary = ep.summary,
                        previewUrl = ep.preview_url,
                        fillermark = ep.fillermark,
                        episodeNumber = number,
                        sourceOrder = index.toLong(),
                        dateFetch = now,
                        dateUpload = ep.date_upload,
                    )
                } else {
                    episodesQueries.updateFromSource(
                        name = ep.name,
                        scanlator = ep.scanlator,
                        summary = ep.summary,
                        previewUrl = ep.preview_url,
                        fillermark = ep.fillermark,
                        episodeNumber = number,
                        sourceOrder = index.toLong(),
                        dateUpload = ep.date_upload,
                        id = old.id,
                    )
                }
            }
            existing.values.filter { it.url !in seenUrls }.forEach { episodesQueries.deleteById(it.id) }
        }
    }

    /** Fetches details and episodes from the source and saves them */
    suspend fun refreshFromSource(anime: Anime, source: AnimeSource) {
        val details = source.getAnimeDetails(anime.toSAnime())
        details.url = anime.url
        if (details.title.isBlank()) details.title = anime.title
        updateFromSource(anime, details)
        if (anime.fetchType == FetchType.Episodes && details.fetch_type == FetchType.Episodes) {
            syncEpisodes(anime, source.getEpisodeList(details))
        }
    }

    private fun mapAnime(
        id: Long,
        source: Long,
        url: String,
        artist: String?,
        author: String?,
        description: String?,
        genre: String?,
        title: String,
        status: Long,
        thumbnailUrl: String?,
        backgroundUrl: String?,
        favorite: Boolean,
        initialized: Boolean,
        dateAdded: Long,
        lastUpdate: Long,
        fetchType: Long,
        episodeFlags: Long,
    ) = Anime(
        id = id,
        source = source,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre.toGenreList(),
        status = status,
        thumbnailUrl = thumbnailUrl,
        backgroundUrl = backgroundUrl,
        favorite = favorite,
        initialized = initialized,
        dateAdded = dateAdded,
        lastUpdate = lastUpdate,
        fetchType = FetchType.entries.getOrElse(fetchType.toInt()) { FetchType.Episodes },
        episodeFlags = episodeFlags,
    )

    private fun mapEpisode(
        id: Long,
        animeId: Long,
        url: String,
        name: String,
        scanlator: String?,
        summary: String?,
        previewUrl: String?,
        seen: Boolean,
        bookmark: Boolean,
        fillermark: Boolean,
        lastSecondSeen: Long,
        totalSeconds: Long,
        episodeNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
    ) = Episode(
        id = id,
        animeId = animeId,
        url = url,
        name = name,
        scanlator = scanlator,
        summary = summary,
        previewUrl = previewUrl,
        seen = seen,
        bookmark = bookmark,
        fillermark = fillermark,
        lastSecondSeen = lastSecondSeen,
        totalSeconds = totalSeconds,
        episodeNumber = episodeNumber,
        sourceOrder = sourceOrder,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
    )
}

private fun FetchType.toLong(): Long = ordinal.toLong()
