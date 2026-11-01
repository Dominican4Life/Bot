package eu.kanade.domain.manga.interactor

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.Instant
import java.time.ZonedDateTime

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    // KMK -->
    /**
     * KMK (r96): the fetch interval from chapters already in memory (see [FetchInterval.toMangaUpdate]), and,
     * with [touchLastUpdate], the `lastUpdate` stamp in the same UPDATE. The chapter sync used to run two UPDATEs
     * and one more chapter read per title here.
     */
    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        chapters: List<Chapter>,
        excludedScanlators: Set<String>,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
        touchLastUpdate: Boolean = false,
    ): Boolean {
        val update = fetchInterval.toMangaUpdate(manga, chapters, excludedScanlators, dateTime, window)
        return mangaRepository.update(
            if (touchLastUpdate) update.copy(lastUpdate = Instant.now().toEpochMilli()) else update,
        )
    }
    // KMK <--

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        return mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
            // KMK: the title's cover is saved to (or cleaned from) its download folder
            .also { updated ->
                if (!updated) return@also
                if (favorite) {
                    eu.kanade.tachiyomi.data.cache.CoverFolders.onAddedToLibrary(mangaId, anime = false)
                } else {
                    eu.kanade.tachiyomi.data.cache.CoverFolders.onRemovedFromLibrary(mangaId, anime = false)
                }
            }
    }
}
