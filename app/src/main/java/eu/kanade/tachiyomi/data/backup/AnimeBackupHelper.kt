package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeCustomInfo
import eu.kanade.tachiyomi.data.anime.AnimeExtraPrefs
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeCategory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeTrack
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.backupExtensionStoreMapper
import mihon.domain.extension.repository.AnimeExtensionStoreRepository
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Backs up and restores the anime library, episode progress, watch history and anime repos.
 */
class AnimeBackupHelper(
    private val repository: AnimeRepository = Injekt.get(),
    private val storeRepository: AnimeExtensionStoreRepository = Injekt.get(),
    private val database: Database = Injekt.get(),
    private val trackRepository: AnimeTrackRepository = Injekt.get(),
) {

    suspend fun createAnime(): List<BackupAnime> = createAnime(repository.getFavoritesRaw(), includeHistory = true)

    /** KMK: [animes] with their episodes, and their watch history when [includeHistory] (device sync picks these) */
    suspend fun createAnime(animes: List<Anime>, includeHistory: Boolean): List<BackupAnime> {
        val history = if (includeHistory) repository.getAllHistory() else emptyMap()
        val categoryNames = repository.getCategories().associate { it.id to it.name }
        val tracks = trackRepository.getAll().groupBy { it.animeId }
        val customInfo = database.anime_custom_infoQueries
            .getAll { animeId, title, author, artist, description, genre, status, thumbnailUrl ->
                animeId to BackupAnimeCustomInfo(
                    title = title,
                    author = author,
                    artist = artist,
                    description = description,
                    genre = genre,
                    status = status,
                    // A local cover file can't come along
                    thumbnailUrl = thumbnailUrl?.takeUnless { it.startsWith("file:") },
                )
            }
            .executeAsList()
            .toMap()
        return animes.map { anime ->
            val episodes = repository.getEpisodes(anime.id)
            BackupAnime(
                source = anime.source,
                url = anime.url,
                title = anime.title,
                artist = anime.artist,
                author = anime.author,
                description = anime.description,
                genre = anime.genre.orEmpty(),
                status = anime.status.toInt(),
                thumbnailUrl = anime.thumbnailUrl,
                backgroundUrl = anime.backgroundUrl,
                dateAdded = anime.dateAdded,
                favorite = anime.favorite,
                initialized = anime.initialized,
                fetchType = anime.fetchType.ordinal,
                episodeFlags = anime.episodeFlags,
                episodes = episodes.map { ep ->
                    BackupEpisode(
                        url = ep.url,
                        name = ep.name,
                        scanlator = ep.scanlator,
                        summary = ep.summary,
                        previewUrl = ep.previewUrl,
                        seen = ep.seen,
                        bookmark = ep.bookmark,
                        fillermark = ep.fillermark,
                        lastSecondSeen = ep.lastSecondSeen,
                        totalSeconds = ep.totalSeconds,
                        episodeNumber = ep.episodeNumber.toFloat(),
                        sourceOrder = ep.sourceOrder,
                        dateFetch = ep.dateFetch,
                        dateUpload = ep.dateUpload,
                    )
                },
                history = episodes.mapNotNull { ep ->
                    history[ep.id]?.let { BackupAnimeHistory(episodeUrl = ep.url, lastSeen = it) }
                },
                categories = repository.getCategoryIds(anime.id).mapNotNull { categoryNames[it] },
                tracking = tracks[anime.id].orEmpty().map { track ->
                    BackupAnimeTrack(
                        trackerId = track.trackerId,
                        remoteId = track.remoteId,
                        libraryId = track.libraryId,
                        title = track.title,
                        lastEpisodeSeen = track.lastEpisodeSeen,
                        totalEpisodes = track.totalEpisodes,
                        status = track.status,
                        score = track.score,
                        remoteUrl = track.remoteUrl,
                        startDate = track.startDate,
                        finishDate = track.finishDate,
                        private = track.private,
                    )
                },
                notes = AnimeExtraPrefs.notes(anime.id).get(),
                excludedScanlators = AnimeExtraPrefs.excludedScanlators(anime.id).get().toList(),
                customInfo = customInfo[anime.id]?.takeIf { it != BackupAnimeCustomInfo() },
            )
        }
    }

    /** KMK: every anime category, with its place in the list */
    suspend fun createCategories(): List<BackupAnimeCategory> =
        repository.getCategories().map { BackupAnimeCategory(name = it.name, order = it.order) }

    /**
     * KMK: Adds the backed up anime categories missing here, in the backup's order, after the ones already here.
     * Run before [restoreAnime] so categories it links to are created in order rather than as anime come.
     */
    fun restoreCategories(categories: List<BackupAnimeCategory>) {
        if (categories.isEmpty()) return
        database.transaction {
            val existing = database.anime_categoriesQueries.getAll { _, name, sort -> name.lowercase() to sort }
                .executeAsList()
            val names = existing.map { it.first }.toMutableSet()
            var next = (existing.maxOfOrNull { it.second } ?: -1L) + 1
            categories.sortedBy { it.order }.forEach { category ->
                if (names.add(category.name.lowercase())) {
                    database.anime_categoriesQueries.insert(category.name, next++)
                }
            }
        }
    }

    suspend fun createStores(): List<BackupExtensionStore> =
        storeRepository.getAll().map(backupExtensionStoreMapper)

    suspend fun restoreAnime(backup: BackupAnime) {
        val anime = repository.restoreAnime(
            Anime(
                id = -1L,
                source = backup.source,
                url = backup.url,
                title = backup.title,
                artist = backup.artist,
                author = backup.author,
                description = backup.description,
                genre = backup.genre,
                status = backup.status.toLong(),
                thumbnailUrl = backup.thumbnailUrl,
                backgroundUrl = backup.backgroundUrl,
                favorite = backup.favorite,
                initialized = backup.initialized,
                dateAdded = backup.dateAdded,
                lastUpdate = 0L,
                fetchType = FetchType.entries.getOrElse(backup.fetchType) { FetchType.Episodes },
                episodeFlags = backup.episodeFlags,
            ),
        )
        repository.restoreEpisodes(
            anime = anime,
            episodes = backup.episodes.map { ep ->
                Episode(
                    id = -1L,
                    animeId = anime.id,
                    url = ep.url,
                    name = ep.name,
                    scanlator = ep.scanlator,
                    summary = ep.summary,
                    previewUrl = ep.previewUrl,
                    seen = ep.seen,
                    bookmark = ep.bookmark,
                    fillermark = ep.fillermark,
                    lastSecondSeen = ep.lastSecondSeen,
                    totalSeconds = ep.totalSeconds,
                    episodeNumber = ep.episodeNumber.toDouble(),
                    sourceOrder = ep.sourceOrder,
                    dateFetch = ep.dateFetch,
                    dateUpload = ep.dateUpload,
                )
            },
            history = backup.history.associate { it.episodeUrl to it.lastSeen },
        )
        if (backup.categories.isNotEmpty()) repository.restoreCategories(anime.id, backup.categories)
        restoreExtras(anime.id, backup)
    }

    /** KMK: tracks, notes, hidden scanlators and edited info, under the anime's id on this device */
    private suspend fun restoreExtras(animeId: Long, backup: BackupAnime) {
        if (backup.tracking.isNotEmpty()) {
            val existing = trackRepository.getTracks(animeId).associateBy { it.trackerId }
            backup.tracking.forEach { track ->
                val here = existing[track.trackerId]
                trackRepository.upsert(
                    AnimeTrack(
                        animeId = animeId,
                        trackerId = track.trackerId,
                        remoteId = track.remoteId,
                        libraryId = track.libraryId,
                        title = track.title,
                        // Never lose progress made on this device
                        lastEpisodeSeen = maxOf(track.lastEpisodeSeen, here?.lastEpisodeSeen ?: 0.0),
                        totalEpisodes = track.totalEpisodes,
                        status = track.status,
                        score = track.score,
                        remoteUrl = track.remoteUrl,
                        startDate = track.startDate,
                        finishDate = track.finishDate,
                        private = track.private,
                    ),
                )
            }
        }
        if (backup.notes.isNotBlank()) AnimeExtraPrefs.notes(animeId).set(backup.notes)
        if (backup.excludedScanlators.isNotEmpty()) {
            AnimeExtraPrefs.excludedScanlators(animeId).set(backup.excludedScanlators.toSet())
        }
        backup.customInfo?.let { info ->
            // Fields the backup doesn't have keep what's here (a local custom cover, say)
            val here = database.anime_custom_infoQueries
                .getByAnimeId(animeId) { _, title, author, artist, description, genre, status, thumb ->
                    BackupAnimeCustomInfo(title, author, artist, description, genre, status, thumb)
                }
                .executeAsOneOrNull()
            // Through the repository, so its in-memory Edit info stays in step
            repository.setCustomInfo(
                AnimeCustomInfo(
                    animeId = animeId,
                    title = info.title ?: here?.title,
                    author = info.author ?: here?.author,
                    artist = info.artist ?: here?.artist,
                    description = info.description ?: here?.description,
                    genre = info.genre ?: here?.genre,
                    status = info.status ?: here?.status,
                    thumbnailUrl = info.thumbnailUrl?.takeUnless { it.startsWith("file:") } ?: here?.thumbnailUrl,
                ),
            )
        }
    }

    fun restoreStore(backupStore: BackupExtensionStore) {
        database.anime_extension_storeQueries.upsert(
            indexUrl = backupStore.indexUrl,
            name = backupStore.name,
            badgeLabel = backupStore.badgeLabel ?: backupStore.name,
            signingKey = backupStore.signingKey,
            contactWebsite = backupStore.contactWebsite,
            contactDiscord = backupStore.contactDiscord,
            isLegacy = backupStore.isLegacy ?: true,
            extensionListUrl = backupStore.extensionListUrl,
        )
    }
}
