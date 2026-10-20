package eu.kanade.tachiyomi.ui.stats

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeLibraryStats
import eu.kanade.tachiyomi.data.anime.AnimePrefs
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeWatchTime
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.devicefiles.DeviceFilesIds
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The numbers of the Stats screen's Anime tab (laid out like Aniyomi's) */
data class AnimeStatsData(
    val libraryCount: Int,
    /** Anime the anime library update includes (its categories and skip rules) */
    val globalUpdateCount: Int,
    val startedCount: Int,
    /** Anime from the device's storage (Device files), the anime side's local source */
    val localCount: Int,
    val completedCount: Int,
    val totalWatchTime: Long,
    val totalEpisodes: Long,
    val seenEpisodes: Long,
    val downloadedEpisodes: Int,
    val trackedCount: Int,
    val meanScore: Double,
    val trackerCount: Int,
)

object AnimeStats {

    suspend fun load(
        repository: AnimeRepository = Injekt.get(),
        trackRepository: AnimeTrackRepository = Injekt.get(),
        downloadManager: AnimeDownloadManager = Injekt.get(),
    ): AnimeStatsData = withIOContext {
        val library = repository.getFavorites().distinctBy { it.id }
        val ids = library.mapTo(HashSet()) { it.id }
        val stats = repository.subscribeLibraryStats().first().filterKeys { it in ids }

        // Downloads: count the episode files in each anime's folder
        val downloaded = library.sumOf { anime ->
            runCatching {
                downloadManager.animeDir(anime, create = false)
                    ?.listFiles()
                    ?.count { file -> file.isFile && file.name?.endsWith(".tmp") == false }
                    ?: 0
            }.getOrDefault(0)
        }

        val tracks = runCatching { trackRepository.getAll() }.getOrDefault(emptyList())
            .filter { it.animeId in ids }
        val meanScore = tracks
            .filter { it.score > 0.0 }
            .groupBy { it.animeId }
            .map { (_, scored) -> scored.map { it.score }.average() }
            .filter { !it.isNaN() }
            .average()

        AnimeStatsData(
            libraryCount = library.size,
            globalUpdateCount = globalUpdateCount(repository, library, stats),
            startedCount = library.count { stats[it.id]?.started == true },
            localCount = library.count { it.source == DeviceFilesIds.ANIME },
            completedCount = library.count { anime ->
                anime.status.toInt() == SAnime.COMPLETED &&
                    stats[anime.id]?.let { it.total > 0 && it.unseen == 0L } == true
            },
            totalWatchTime = runCatching { AnimeWatchTime.total() }.getOrDefault(0L),
            totalEpisodes = stats.values.sumOf { it.total },
            seenEpisodes = stats.values.sumOf { it.seen },
            downloadedEpisodes = downloaded,
            trackedCount = tracks.distinctBy { it.animeId }.size,
            meanScore = meanScore,
            trackerCount = tracks.distinctBy { it.trackerId }.size,
        )
    }

    /**
     * The anime the anime library update would check, from its category and skip settings (like
     * AnimeLibraryUpdateJob). As with the manga count, the "smart update" release window isn't applied.
     */
    private suspend fun globalUpdateCount(
        repository: AnimeRepository,
        library: List<Anime>,
        stats: Map<Long, AnimeLibraryStats>,
    ): Int {
        val skip = AnimePrefs.updateSkip().get()
        val included = AnimePrefs.updateCategories().get()
        val excluded = AnimePrefs.updateCategoriesExclude().get()
        val links = if (included.isNotEmpty() || excluded.isNotEmpty()) {
            repository.subscribeCategoryLinks().first()
        } else {
            emptyMap()
        }
        return library.count { anime ->
            if (!AnimePrefs.matchesCategories(links[anime.id].orEmpty(), included, excluded)) return@count false
            if (AnimePrefs.SKIP_COMPLETED in skip && anime.status.toInt() == SAnime.COMPLETED) return@count false
            val stat = stats[anime.id]
            if (AnimePrefs.SKIP_HAS_UNSEEN in skip && stat != null && stat.unseen > 0) return@count false
            if (AnimePrefs.SKIP_NOT_STARTED in skip && stat != null && stat.total > 0 && !stat.started) {
                return@count false
            }
            true
        }
    }
}
