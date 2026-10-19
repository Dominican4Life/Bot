package eu.kanade.tachiyomi.data.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import java.time.Instant

/**
 * KMK: Reads and writes anime and episodes (merged from Anikku).
 */
class AnimeRepository(
    private val handler: DatabaseHandler,
) {

    // KMK: Edit info. What the user changed is laid over the source's values whenever an anime is read.
    private val customInfo = java.util.concurrent.ConcurrentHashMap<Long, AnimeCustomInfo>()

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            .launch {
                try {
                    val all = handler.awaitList { anime_custom_infoQueries.getAll(::mapCustomInfo) }
                    if (all.isEmpty()) return@launch
                    all.forEach { customInfo[it.animeId] = it }
                    // Read the anime again so open screens show the edits
                    all.forEach { touchAnime(it.animeId) }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Couldn't load anime Edit info" }
                }
            }
    }

    /** The user's edits for this anime, or null */
    fun getCustomInfo(animeId: Long): AnimeCustomInfo? = customInfo[animeId]

    suspend fun setCustomInfo(info: AnimeCustomInfo) {
        if (info.isEmpty()) {
            customInfo.remove(info.animeId)
            handler.await { anime_custom_infoQueries.delete(info.animeId) }
        } else {
            customInfo[info.animeId] = info
            handler.await {
                anime_custom_infoQueries.upsert(
                    animeId = info.animeId,
                    title = info.title,
                    author = info.author,
                    artist = info.artist,
                    description = info.description,
                    genre = info.genre,
                    status = info.status,
                    thumbnailUrl = info.thumbnailUrl,
                )
            }
        }
        touchAnime(info.animeId)
    }

    /** Makes screens watching this anime read it again */
    private suspend fun touchAnime(animeId: Long) {
        handler.await { animesQueries.touch(animeId) }
    }

    private fun mapCustomInfo(
        animeId: Long,
        title: String?,
        author: String?,
        artist: String?,
        description: String?,
        genre: String?,
        status: Long?,
        thumbnailUrl: String?,
    ) = AnimeCustomInfo(animeId, title, author, artist, description, genre, status, thumbnailUrl)

    private fun Anime.withCustomInfo(): Anime {
        val info = customInfo[id] ?: return this
        return copy(
            title = info.title?.takeIf { it.isNotBlank() } ?: title,
            author = info.author ?: author,
            artist = info.artist ?: artist,
            description = info.description ?: description,
            genre = info.genre?.let { it.toGenreList() } ?: genre,
            status = info.status ?: status,
            thumbnailUrl = info.thumbnailUrl?.takeIf { it.isNotBlank() } ?: thumbnailUrl,
        )
    }

    /** Number values for the anime library's filters, sorts and badges, per anime id */
    fun subscribeLibraryStats(): Flow<Map<Long, AnimeLibraryStats>> =
        handler.subscribeToList { episodesQueries.animeLibraryStats() }.map { rows ->
            rows.associate { row ->
                row.animeId to AnimeLibraryStats(
                    total = row.total,
                    seen = (row.seenCount as Number?)?.toLong() ?: 0L,
                    bookmarked = (row.bookmarkCount as Number?)?.toLong() ?: 0L,
                    inProgress = (row.inProgressCount as Number?)?.toLong() ?: 0L,
                    latestUpload = (row.latestUpload as Number?)?.toLong() ?: 0L,
                    latestFetch = (row.latestFetch as Number?)?.toLong() ?: 0L,
                    lastSeen = (row.lastSeen as Number?)?.toLong() ?: 0L,
                )
            }
        }

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
            val newId = animesQueries.selectLastInsertedRowId().executeAsOne()
            // KMK: episode sort / filters / display chosen with "Set as default"
            val defaults = AnimeEpisodeDefaults.flags().get()
            if (defaults != 0L) animesQueries.setEpisodeFlags(flags = defaults, id = newId)
            newId
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
        // KMK: the anime's cover is saved to (or cleaned from) its download folder
        if (favorite && !anime.favorite) {
            eu.kanade.tachiyomi.data.cache.CoverFolders.onAddedToLibrary(anime.id, anime = true)
        } else if (!favorite && anime.favorite) {
            eu.kanade.tachiyomi.data.cache.CoverFolders.onRemovedFromLibrary(anime.id, anime = true)
        }
    }

    suspend fun setEpisodeFlags(anime: Anime, flags: Long) {
        handler.await { animesQueries.setEpisodeFlags(flags, anime.id) }
    }

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

    // Categories (KMK)

    fun subscribeCategories(): Flow<List<AnimeCategory>> =
        handler.subscribeToList { anime_categoriesQueries.getAll(::AnimeCategory) }

    suspend fun getCategories(): List<AnimeCategory> =
        handler.awaitList { anime_categoriesQueries.getAll(::AnimeCategory) }

    /** anime id to the ids of its categories */
    fun subscribeCategoryLinks(): Flow<Map<Long, Set<Long>>> =
        handler.subscribeToList { anime_categoriesQueries.getLinks { animeId, categoryId -> animeId to categoryId } }
            .map { links -> links.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() } }

    suspend fun getCategoryIds(animeId: Long): Set<Long> =
        handler.awaitList { anime_categoriesQueries.getCategoryIdsForAnime(animeId) }.toSet()

    suspend fun createCategory(name: String) {
        handler.await {
            val next = (anime_categoriesQueries.getAll(::AnimeCategory).executeAsList().maxOfOrNull { it.order } ?: -1) + 1
            anime_categoriesQueries.insert(name, next)
        }
    }

    suspend fun renameCategory(id: Long, name: String) {
        handler.await { anime_categoriesQueries.rename(name, id) }
    }

    suspend fun deleteCategory(id: Long) {
        handler.await(inTransaction = true) {
            anime_categoriesQueries.delete(id)
            anime_categoriesQueries.getAll(::AnimeCategory).executeAsList().forEachIndexed { index, category ->
                anime_categoriesQueries.setSort(index.toLong(), category.id)
            }
        }
    }

    /** Moves [category] to [newIndex] and renumbers the rest */
    suspend fun moveCategory(category: AnimeCategory, newIndex: Int) {
        handler.await(inTransaction = true) {
            val list = anime_categoriesQueries.getAll(::AnimeCategory).executeAsList().toMutableList()
            val from = list.indexOfFirst { it.id == category.id }
            if (from < 0) return@await
            list.add(newIndex.coerceIn(0, list.size - 1), list.removeAt(from))
            list.forEachIndexed { index, c -> anime_categoriesQueries.setSort(index.toLong(), c.id) }
        }
    }

    /** Adds [animeId] to the categories called [names], creating the missing ones (backup restore) */
    suspend fun restoreCategories(animeId: Long, names: List<String>) {
        handler.await(inTransaction = true) {
            names.forEach { name ->
                val existing = anime_categoriesQueries.getAll(::AnimeCategory).executeAsList()
                val id = existing.find { it.name.equals(name, ignoreCase = true) }?.id ?: run {
                    anime_categoriesQueries.insert(name, (existing.maxOfOrNull { it.order } ?: -1) + 1)
                    animesQueries.selectLastInsertedRowId().executeAsOne()
                }
                anime_categoriesQueries.link(animeId, id)
            }
        }
    }

    suspend fun setCategories(animeId: Long, categoryIds: Collection<Long>) {
        handler.await(inTransaction = true) {
            anime_categoriesQueries.unlinkAll(animeId)
            categoryIds.forEach { anime_categoriesQueries.link(animeId, it) }
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
        // KMK: anime tracking
        if (seen) {
            episodes.groupBy { it.animeId }.forEach { (animeId, list) ->
                eu.kanade.tachiyomi.data.anime.track.AnimeTracking.onEpisodeSeen(
                    animeId,
                    list.maxOf { it.episodeNumber },
                )
            }
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
        // KMK: anime tracking
        if (seen == true) {
            handler.awaitOneOrNull { episodesQueries.getById(id, ::mapEpisode) }?.let {
                eu.kanade.tachiyomi.data.anime.track.AnimeTracking.onEpisodeSeen(it.animeId, it.episodeNumber)
            }
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

    /** The library as the sources gave it, without Edit info (for backups; the edits are backed up apart) */
    suspend fun getFavoritesRaw(): List<Anime> = handler.awaitList { animesQueries.getFavorites(::mapAnimeRaw) }

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

    private fun mapAnimeRaw(
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
    ) = mapAnimeRaw(id, source, url, artist, author, description, genre, title, status, thumbnailUrl, backgroundUrl, favorite, initialized, dateAdded, lastUpdate, fetchType, episodeFlags).withCustomInfo()

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
