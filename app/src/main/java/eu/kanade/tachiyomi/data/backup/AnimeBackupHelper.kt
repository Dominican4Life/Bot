package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
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
) {

    suspend fun createAnime(): List<BackupAnime> {
        val history = repository.getAllHistory()
        return repository.getFavorites().map { anime ->
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
            )
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
