package eu.kanade.tachiyomi.ui.stats

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeWatchTime
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The numbers of the Stats screen's anime section (like Anikku's) */
data class AnimeStatsData(
    val libraryCount: Int,
    val startedCount: Int,
    val completedCount: Int,
    val totalWatchTime: Long,
    val totalEpisodes: Long,
    val seenEpisodes: Long,
    val downloadedEpisodes: Int,
    val trackedCount: Int,
    val meanScore: Double,
    val trackerCount: Int,
    val sourceCount: Int,
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
            startedCount = library.count { stats[it.id]?.started == true },
            completedCount = library.count { anime ->
                anime.status.toInt() == SAnime.COMPLETED && stats[anime.id]?.let { it.total > 0 && it.unseen == 0L } == true
            },
            totalWatchTime = runCatching { AnimeWatchTime.total() }.getOrDefault(0L),
            totalEpisodes = stats.values.sumOf { it.total },
            seenEpisodes = stats.values.sumOf { it.seen },
            downloadedEpisodes = downloaded,
            trackedCount = tracks.distinctBy { it.animeId }.size,
            meanScore = meanScore,
            trackerCount = tracks.distinctBy { it.trackerId }.size,
            sourceCount = library.distinctBy { it.source }.size,
        )
    }
}
