package eu.kanade.tachiyomi.ui.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.AnimeUpdateItem
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: What the anime rows of the Updates tab need for downloads: the anime and episodes behind them, and which
 * episodes are on disk (read in the background, again whenever a download finishes or is deleted).
 */
class AnimeUpdateDownloads(
    val animes: Map<Long, Anime> = emptyMap(),
    val episodes: Map<Long, Episode> = emptyMap(),
    val downloaded: Set<Long> = emptySet(),
)

@Composable
fun rememberAnimeUpdateDownloads(updates: List<AnimeUpdateItem>): AnimeUpdateDownloads {
    val manager = remember { Injekt.get<AnimeDownloadManager>() }
    val repository = remember { Injekt.get<AnimeRepository>() }
    val changes by manager.changes.collectAsState()
    val state by produceState(AnimeUpdateDownloads(), updates, changes) {
        value = withIOContext {
            val animes = updates.map { it.animeId }.distinct()
                .mapNotNull { id -> repository.getAnime(id) }
                .associateBy { it.id }
            val episodes = updates.mapNotNull { item -> repository.getEpisode(item.episodeId) }
                .associateBy { it.id }
            val downloaded = episodes.values
                .filter { ep -> animes[ep.animeId]?.let { manager.isDownloaded(it, ep) } == true }
                .map { it.id }
                .toSet()
            AnimeUpdateDownloads(animes, episodes, downloaded)
        }
    }
    return state
}

/** KMK: The bulk actions of the anime rows of the Updates tab */
object AnimeUpdatesActions {

    private val repository: AnimeRepository by lazy { Injekt.get() }
    private val manager: AnimeDownloadManager by lazy { Injekt.get() }

    suspend fun setSeen(items: List<AnimeUpdateItem>, seen: Boolean) {
        items.forEach { item ->
            repository.updateEpisode(id = item.episodeId, seen = seen, lastSecondSeen = if (seen) null else 0L)
        }
    }

    suspend fun setBookmark(items: List<AnimeUpdateItem>, bookmark: Boolean) {
        items.forEach { repository.updateEpisode(id = it.episodeId, bookmark = bookmark) }
    }

    suspend fun download(items: List<AnimeUpdateItem>, startNow: Boolean = false) {
        forEachAnime(items) { anime, episodes -> manager.enqueue(anime, episodes, startNow) }
    }

    suspend fun deleteDownloads(items: List<AnimeUpdateItem>) {
        forEachAnime(items) { anime, episodes -> manager.delete(anime, episodes) }
    }

    fun cancel(items: List<AnimeUpdateItem>) {
        manager.cancel(items.map { it.episodeId })
    }

    private suspend fun forEachAnime(items: List<AnimeUpdateItem>, block: (Anime, List<Episode>) -> Unit) {
        withIOContext {
            items.groupBy { it.animeId }.forEach { (animeId, group) ->
                val anime = repository.getAnime(animeId) ?: return@forEach
                val episodes = group.mapNotNull { repository.getEpisode(it.episodeId) }
                if (episodes.isNotEmpty()) block(anime, episodes)
            }
        }
    }
}
