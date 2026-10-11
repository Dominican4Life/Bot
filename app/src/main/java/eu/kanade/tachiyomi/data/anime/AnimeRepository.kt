package eu.kanade.tachiyomi.data.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.model.Category
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

    /** Episode counts and dates of every anime in the library, by anime id (anime without episodes are left out) */
    fun subscribeLibraryStats(): Flow<Map<Long, AnimeLibraryStats>> =
        handler.subscribeToList { episodesQueries.getLibraryStats(::AnimeLibraryStats) }
            .map { list -> list.associateBy { it.animeId } }

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

    suspend fun setNextEpisodeAiringAt(animeId: Long, airingAt: Long) {
        handler.await { animesQueries.setNextEpisodeAiringAt(airingAt, animeId) }
    }

    // Categories (KMK): the anime library's own, apart from the manga ones. Anime in none of them are "Default".

    fun subscribeCategories(): Flow<List<Category>> =
        handler.subscribeToList { anime_categoriesQueries.getAll(::mapCategory) }

    suspend fun getCategories(): List<Category> =
        handler.awaitList { anime_categoriesQueries.getAll(::mapCategory) }

    suspend fun createCategory(name: String) {
        handler.await(inTransaction = true) {
            val last = anime_categoriesQueries.getAll().executeAsList().maxOfOrNull { it.sort } ?: -1L
            anime_categoriesQueries.insert(name, last + 1)
        }
    }

    suspend fun renameCategory(id: Long, name: String) {
        handler.await { anime_categoriesQueries.rename(name, id) }
    }

    suspend fun setCategoryHidden(id: Long, hidden: Boolean) {
        handler.await { anime_categoriesQueries.setHidden(hidden, id) }
    }

    suspend fun deleteCategory(id: Long) {
        handler.await { anime_categoriesQueries.delete(id) }
    }

    /** Saves the order the categories are listed in */
    suspend fun reorderCategories(ids: List<Long>) {
        handler.await(inTransaction = true) {
            ids.forEachIndexed { index, id -> anime_categoriesQueries.setSort(index.toLong(), id) }
        }
    }

    /** Category ids of every anime in the library, by anime id (anime in no category are left out) */
    fun subscribeLibraryCategoryIds(): Flow<Map<Long, List<Long>>> =
        handler.subscribeToList { anime_categoriesQueries.getLibraryLinks { animeId, categoryId -> animeId to categoryId } }
            .map { links -> links.groupBy({ it.first }, { it.second }) }

    suspend fun getCategoryIds(animeId: Long): List<Long> =
        handler.awaitList { anime_categoriesQueries.getCategoryIdsOfAnime(animeId) }

    suspend fun getLibraryCategoryIds(): Map<Long, List<Long>> =
        handler.awaitList { anime_categoriesQueries.getLibraryLinks { animeId, categoryId -> animeId to categoryId } }
            .groupBy({ it.first }, { it.second })

    suspend fun setCategories(animeId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            anime_categoriesQueries.removeAnimeFromCategories(animeId)
            categoryIds.forEach { anime_categoriesQueries.addAnimeToCategory(animeId, it) }
        }
    }

    private fun mapCategory(id: Long, name: String, sort: Long, hidden: Boolean) =
        Category(id = id, name = name, order = sort, flags = 0L, hidden = hidden)

    // Merging (KMK)

    /** Every anime merged into [mergeId], the root first; empty when it isn't merged */
    fun subscribeMergeMembers(mergeId: Long): Flow<List<Anime>> =
        handler.subscribeToList { anime_mergedQueries.getMembers(mergeId, ::mapAnime) }

    suspend fun getMergeMembers(mergeId: Long): List<Anime> =
        handler.awaitList { anime_mergedQueries.getMembers(mergeId, ::mapAnime) }

    /** Adds [others] to the page of [rootId] (they stay in the database as their own entries) */
    suspend fun mergeInto(rootId: Long, others: List<Long>) {
        handler.await(inTransaction = true) {
            val start = anime_mergedQueries.countMembers(rootId).executeAsOne()
            anime_mergedQueries.insert(rootId, rootId, 0)
            others.filter { it != rootId }.forEachIndexed { index, id ->
                anime_mergedQueries.insert(rootId, id, start + index + 1)
            }
        }
    }

    /** Takes one anime off a merged page; the merge ends when only the root is left */
    suspend fun removeFromMerge(rootId: Long, animeId: Long) {
        handler.await(inTransaction = true) {
            if (animeId == rootId) {
                anime_mergedQueries.removeMerge(rootId)
            } else {
                anime_mergedQueries.removeMember(rootId, animeId)
                if (anime_mergedQueries.countMembers(rootId).executeAsOne() <= 1) {
                    anime_mergedQueries.removeMerge(rootId)
                }
            }
        }
    }

    // Episodes

    suspend fun getEpisodes(animeId: Long): List<Episode> =
        handler.awaitList { episodesQueries.getByAnimeId(animeId, ::mapEpisode) }

    suspend fun getEpisode(id: Long): Episode? =
        handler.awaitOneOrNull { episodesQueries.getById(id, ::mapEpisode) }

    fun subscribeEpisodes(animeId: Long): Flow<List<Episode>> =
        handler.subscribeToList { episodesQueries.getByAnimeId(animeId, ::mapEpisode) }

    suspend fun setSeen(episodes: List<Episode>, seen: Boolean) {
        handler.await(inTransaction = true) {
            episodes.forEach { episodesQueries.setSeen(seen, it.id) }
        }
    }

    /** "Clear anime": drops the saved episode list so the next refresh fetches it fresh */
    suspend fun deleteEpisodes(animeId: Long) {
        handler.await { episodesQueries.deleteByAnimeId(animeId) }
    }

    suspend fun setBookmark(episode: Episode, bookmark: Boolean) {
        handler.await { episodesQueries.setBookmark(bookmark, episode.id) }
    }

    /** Updates only the given fields of an episode (used by the player) */
    suspend fun updateEpisode(
        id: Long,
        seen: Boolean? = null,
        bookmark: Boolean? = null,
        fillermark: Boolean? = null,
        lastSecondSeen: Long? = null,
        totalSeconds: Long? = null,
    ) {
        handler.await {
            episodesQueries.updatePartial(
                seen = seen,
                bookmark = bookmark,
                fillermark = fillermark,
                lastSecondSeen = lastSecondSeen,
                totalSeconds = totalSeconds,
                id = id,
            )
        }
    }

    /**
     * Saves the episodes from the source: adds new ones, updates changed ones and removes the ones the source
     * no longer has. Keeps seen/bookmark/progress of existing episodes.
     */
    suspend fun syncEpisodes(anime: Anime, sourceEpisodes: List<SEpisode>): Int {
        var newCount = 0
        val now = Instant.now().toEpochMilli()
        val existing = getEpisodes(anime.id).associateBy { it.url }
        val seenUrls = HashSet<String>()
        handler.await(inTransaction = true) {
            sourceEpisodes.distinctBy { it.url }.forEachIndexed { index, ep ->
                seenUrls += ep.url
                val old = existing[ep.url]
                val number = ep.episode_number.toDouble().takeIf { it >= 0 } ?: -1.0
                if (old == null) {
                    newCount++
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
        return newCount
    }

    /**
     * Fetches details and episodes from the source and saves them. Returns the number of new episodes.
     *
     * KMK: like AniZen, details and episodes are two separate calls through the extensions-lib 17 combined API, so
     * a failing details page still leaves the episodes loaded (with their thumbnails, summaries and Sub / Dub).
     */
    suspend fun refreshFromSource(anime: Anime, source: AnimeSource): Int {
        val base = anime.toSAnime()
        val detailsError = runCatching {
            val details = source.getAnimeEpisodeUpdate(base, emptyList(), fetchDetails = true, fetchEpisodes = false).anime
            details.url = anime.url
            if (details.title.isBlank()) details.title = anime.title
            updateFromSource(anime, details)
            logcat(LogPriority.INFO, tag = "anime") {
                "${anime.title}: details loaded, description ${details.description?.length ?: 0} chars"
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logcat(LogPriority.WARN, e, tag = "anime") { "${anime.title}: details failed" }
        }.exceptionOrNull()
        val fresh = getAnime(anime.id) ?: anime
        val count = if (fresh.fetchType == FetchType.Episodes) {
            val episodes = source.getAnimeEpisodeUpdate(
                fresh.toSAnime(),
                emptyList(),
                fetchDetails = false,
                fetchEpisodes = true,
            ).episodes
            logcat(LogPriority.INFO, tag = "anime") {
                "${anime.title}: ${episodes.size} episodes, " +
                    "${episodes.count { !it.preview_url.isNullOrBlank() }} with thumbnails, " +
                    "${episodes.count { !it.summary.isNullOrBlank() }} with summaries, " +
                    "${episodes.count { !it.scanlator.isNullOrBlank() }} with Sub/Dub"
            }
            syncEpisodes(fresh, episodes)
        } else {
            0
        }
        detailsError?.let { throw it }
        return count
    }

    suspend fun getFavorites(): List<Anime> = handler.awaitList { animesQueries.getFavorites(::mapAnime) }

    /** Anime that were watched but aren't in the library */
    suspend fun getWatchedNotInLibrary(): List<Anime> =
        handler.awaitList { animesQueries.getWatchedNotInLibrary(::mapAnime) }

    suspend fun getAnimeByUrl(url: String, sourceId: Long): Anime? =
        handler.awaitOneOrNull { animesQueries.getByUrlAndSource(url, sourceId, ::mapAnime) }

    // History

    fun subscribeHistory(): Flow<List<AnimeHistoryItem>> =
        handler.subscribeToList {
            anime_historyQueries.getHistory {
                    animeId,
                    source,
                    title,
                    thumbnailUrl,
                    favorite,
                    episodeId,
                    episodeName,
                    episodeNumber,
                    lastSecondSeen,
                    totalSeconds,
                    lastSeen,
                ->
                AnimeHistoryItem(
                    animeId = animeId,
                    source = source,
                    title = title,
                    thumbnailUrl = thumbnailUrl,
                    favorite = favorite,
                    episodeId = episodeId,
                    episodeName = episodeName,
                    episodeNumber = episodeNumber,
                    lastSecondSeen = lastSecondSeen,
                    totalSeconds = totalSeconds,
                    lastSeen = lastSeen,
                )
            }
        }.map { list -> list.distinctBy { it.animeId } }

    suspend fun upsertHistory(episodeId: Long, lastSeen: Long) {
        handler.await { anime_historyQueries.upsert(episodeId, lastSeen) }
    }

    suspend fun removeHistory(animeId: Long) {
        handler.await { anime_historyQueries.removeByAnimeId(animeId) }
    }

    suspend fun removeAllHistory() {
        handler.await { anime_historyQueries.removeAll() }
    }

    /** episode id to last seen time */
    suspend fun getAllHistory(): Map<Long, Long> =
        handler.awaitList { anime_historyQueries.getAll { episodeId, lastSeen -> episodeId to lastSeen } }.toMap()

    // Updates

    fun subscribeUpdates(after: Long, limit: Long = 500): Flow<List<AnimeUpdateItem>> =
        handler.subscribeToList {
            episodesQueries.getUpdates(after, limit) {
                    animeId,
                    source,
                    title,
                    thumbnailUrl,
                    episodeId,
                    episodeName,
                    episodeNumber,
                    seen,
                    bookmark,
                    lastSecondSeen,
                    totalSeconds,
                    dateFetch,
                ->
                AnimeUpdateItem(
                    animeId = animeId,
                    source = source,
                    title = title,
                    thumbnailUrl = thumbnailUrl,
                    episodeId = episodeId,
                    episodeName = episodeName,
                    episodeNumber = episodeNumber,
                    seen = seen,
                    bookmark = bookmark,
                    lastSecondSeen = lastSecondSeen,
                    totalSeconds = totalSeconds,
                    dateFetch = dateFetch,
                )
            }
        }

    // Backup

    /** Inserts or updates a backed up anime and returns it */
    suspend fun restoreAnime(backup: Anime): Anime {
        val existing = getAnimeByUrl(backup.url, backup.source)
        val id = if (existing == null) {
            handler.await(inTransaction = true) {
                animesQueries.insert(
                    source = backup.source,
                    url = backup.url,
                    artist = backup.artist,
                    author = backup.author,
                    description = backup.description,
                    genre = backup.genre?.joinToString(", "),
                    title = backup.title,
                    status = backup.status,
                    thumbnailUrl = backup.thumbnailUrl,
                    backgroundUrl = backup.backgroundUrl,
                    initialized = backup.initialized,
                    fetchType = backup.fetchType.toLong(),
                )
                animesQueries.selectLastInsertedRowId().executeAsOne()
            }
        } else {
            existing.id
        }
        handler.await {
            animesQueries.setFavorite(backup.favorite || existing?.favorite == true, backup.dateAdded, id)
            animesQueries.setEpisodeFlags(backup.episodeFlags, id)
        }
        return getAnime(id)!!
    }

    /** Adds backed up episodes, keeping whichever progress is further along */
    suspend fun restoreEpisodes(anime: Anime, episodes: List<Episode>, history: Map<String, Long>) {
        val existing = getEpisodes(anime.id).associateBy { it.url }
        handler.await(inTransaction = true) {
            episodes.forEach { ep ->
                val old = existing[ep.url]
                val episodeId = if (old == null) {
                    episodesQueries.insert(
                        animeId = anime.id,
                        url = ep.url,
                        name = ep.name,
                        scanlator = ep.scanlator,
                        summary = ep.summary,
                        previewUrl = ep.previewUrl,
                        fillermark = ep.fillermark,
                        episodeNumber = ep.episodeNumber,
                        sourceOrder = ep.sourceOrder,
                        dateFetch = ep.dateFetch,
                        dateUpload = ep.dateUpload,
                    )
                    animesQueries.selectLastInsertedRowId().executeAsOne()
                } else {
                    old.id
                }
                episodesQueries.updatePartial(
                    seen = ep.seen || old?.seen == true,
                    bookmark = ep.bookmark || old?.bookmark == true,
                    fillermark = ep.fillermark || old?.fillermark == true,
                    lastSecondSeen = maxOf(ep.lastSecondSeen, old?.lastSecondSeen ?: 0L),
                    totalSeconds = maxOf(ep.totalSeconds, old?.totalSeconds ?: 0L),
                    id = episodeId,
                )
                history[ep.url]?.let { anime_historyQueries.upsert(episodeId, it) }
            }
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
        nextEpisodeAiringAt: Long,
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
        nextEpisodeAiringAt = nextEpisodeAiringAt,
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
