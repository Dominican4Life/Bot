package mihon.feature.migration.anime

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Moves (or copies) an anime in the library to another source's entry, like MigrateMangaUseCase: seen
 * episodes, bookmarks, progress and history, categories, tracking, "Extra" (episode list settings, notes and edited
 * info), and deletes the old downloads when asked.
 */
class MigrateAnimeUseCase(
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
    private val trackRepository: AnimeTrackRepository = Injekt.get(),
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {

    suspend operator fun invoke(
        current: Anime,
        target: Anime,
        replace: Boolean,
    ) {
        val targetSource = sourceManager.get(target.source) ?: return
        val flags = sourcePreferences.animeMigrationFlags().get()

        try {
            // The newest details and episodes of the new entry
            try {
                repository.refreshFromSource(target, targetSource)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Anime migration: couldn't refresh ${target.title}" }
            }
            val freshTarget = repository.getAnime(target.id) ?: target

            if (MigrationFlag.CHAPTER in flags) copyEpisodes(current, freshTarget)

            if (MigrationFlag.CATEGORY in flags) {
                repository.setCategories(freshTarget.id, repository.getCategoryIds(current.id))
            }

            if (MigrationFlag.TRACK in flags) {
                trackRepository.getTracks(current.id).forEach { track ->
                    trackRepository.upsert(track.copy(animeId = freshTarget.id))
                }
            }

            if (MigrationFlag.EXTRA in flags) {
                repository.setEpisodeFlags(freshTarget, current.episodeFlags)
                val notes = preferenceStore.getString(notesKey(current.id), "").get()
                if (notes.isNotBlank()) preferenceStore.getString(notesKey(freshTarget.id), "").set(notes)
                repository.getCustomInfo(current.id)?.let { info ->
                    repository.setCustomInfo(info.copy(animeId = freshTarget.id))
                }
            }

            if (MigrationFlag.REMOVE_DOWNLOAD in flags) {
                downloadManager.deleteAnime(current)
            }

            if (!freshTarget.favorite) repository.setFavorite(freshTarget, true)
            if (replace && current.favorite) repository.setFavorite(current, false)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Anime migration failed for ${current.title}" }
        }
    }

    /**
     * Seen, bookmark and watch progress go to the episodes with the same number; episodes only the new entry has
     * are marked seen up to the furthest one seen. History follows the matched episodes.
     */
    private suspend fun copyEpisodes(current: Anime, target: Anime) {
        val oldEpisodes = repository.getEpisodes(current.id)
        val newEpisodes = repository.getEpisodes(target.id)
        if (oldEpisodes.isEmpty() || newEpisodes.isEmpty()) return

        val oldByNumber = oldEpisodes.filter { it.episodeNumber >= 0 }.associateBy { it.episodeNumber }
        val maxSeen = oldEpisodes.filter { it.seen && it.episodeNumber >= 0 }.maxOfOrNull { it.episodeNumber }
        val history = repository.getAllHistory()

        val toMarkSeen = mutableListOf<Episode>()
        newEpisodes.forEach { episode ->
            if (episode.episodeNumber < 0) return@forEach
            val old = oldByNumber[episode.episodeNumber]
            if (old != null) {
                if (old.seen && !episode.seen) toMarkSeen += episode
                if (old.bookmark != episode.bookmark || old.lastSecondSeen > episode.lastSecondSeen) {
                    repository.updateEpisode(
                        id = episode.id,
                        bookmark = old.bookmark,
                        lastSecondSeen = maxOf(old.lastSecondSeen, episode.lastSecondSeen),
                        totalSeconds = maxOf(old.totalSeconds, episode.totalSeconds),
                    )
                }
                history[old.id]?.let { lastSeen -> repository.upsertHistory(episode.id, lastSeen) }
            } else if (maxSeen != null && episode.episodeNumber <= maxSeen && !episode.seen) {
                toMarkSeen += episode
            }
        }
        if (toMarkSeen.isNotEmpty()) repository.setSeen(toMarkSeen, true)
    }

    private fun notesKey(animeId: Long) = "anime_notes_$animeId"
}
