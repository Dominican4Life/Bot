package eu.kanade.tachiyomi.data.anime.track

import androidx.compose.runtime.Immutable
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.anime.AnimeIncognito
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: one anime bound to one tracker (anime_sync) */
@Immutable
data class AnimeTrack(
    val animeId: Long,
    val trackerId: Long,
    val remoteId: Long,
    val libraryId: Long?,
    val title: String,
    val lastEpisodeSeen: Double,
    val totalEpisodes: Long,
    /** One of [AnimeTrackStatus] */
    val status: Long,
    /** 0–10 */
    val score: Double,
    val remoteUrl: String,
    val startDate: Long,
    val finishDate: Long,
    val private: Boolean,
)

/** The statuses every anime tracker here understands */
object AnimeTrackStatus {
    const val WATCHING = 1L
    const val COMPLETED = 2L
    const val ON_HOLD = 3L
    const val DROPPED = 4L
    const val PLAN_TO_WATCH = 5L
    const val REWATCHING = 6L

    val all = listOf(WATCHING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_WATCH, REWATCHING)
}

/** A search result on a tracker */
@Immutable
data class AnimeTrackSearch(
    val remoteId: Long,
    val title: String,
    val coverUrl: String?,
    val format: String?,
    val totalEpisodes: Long,
    val summary: String?,
    val startYear: String?,
    val remoteUrl: String,
)

/** What a tracker has to do for anime. Logins are the manga trackers' own (Settings › Tracking). */
interface AnimeTrackService {
    val tracker: BaseTracker
    val id: Long get() = tracker.id
    val name: String get() = tracker.name
    val isLoggedIn: Boolean get() = tracker.isLoggedIn

    suspend fun search(query: String): List<AnimeTrackSearch>

    /** The user's list entry for [remoteId], or null when it isn't on their list */
    suspend fun find(animeId: Long, remoteId: Long): AnimeTrack?

    /** Adds or updates the list entry; returns it as the tracker now has it */
    suspend fun save(track: AnimeTrack): AnimeTrack

    suspend fun delete(track: AnimeTrack)
}

class AnimeTrackRepository(private val handler: DatabaseHandler) {

    suspend fun getTracks(animeId: Long): List<AnimeTrack> =
        handler.awaitList { anime_syncQueries.getByAnimeId(animeId, ::map) }

    fun subscribeTracks(animeId: Long): Flow<List<AnimeTrack>> =
        handler.subscribeToList { anime_syncQueries.getByAnimeId(animeId, ::map) }

    suspend fun getAll(): List<AnimeTrack> = handler.awaitList { anime_syncQueries.getAll(::map) }

    suspend fun upsert(track: AnimeTrack) {
        handler.await {
            anime_syncQueries.upsert(
                animeId = track.animeId,
                syncId = track.trackerId,
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
        }
    }

    suspend fun delete(animeId: Long, trackerId: Long) {
        handler.await { anime_syncQueries.delete(animeId, trackerId) }
    }

    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    private fun map(
        id: Long,
        animeId: Long,
        syncId: Long,
        remoteId: Long,
        libraryId: Long?,
        title: String,
        lastEpisodeSeen: Double,
        totalEpisodes: Long,
        status: Long,
        score: Double,
        remoteUrl: String,
        startDate: Long,
        finishDate: Long,
        private: Boolean,
    ) = AnimeTrack(
        animeId, syncId, remoteId, libraryId, title, lastEpisodeSeen, totalEpisodes, status, score, remoteUrl,
        startDate, finishDate, private,
    )
}

/**
 * KMK: anime tracking as a whole: which trackers can track anime, and keeping them up to date when episodes
 * are watched.
 */
object AnimeTracking {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repository get() = Injekt.get<AnimeTrackRepository>()

    private val lastReported = java.util.concurrent.ConcurrentHashMap<Long, Double>()

    /** Every tracker that can track anime, logged in or not */
    val services: List<AnimeTrackService> by lazy {
        val manager = Injekt.get<TrackerManager>()
        listOf(
            AnilistAnime(manager.aniList),
            MyAnimeListAnime(manager.myAnimeList),
        )
    }

    fun service(trackerId: Long): AnimeTrackService? = services.firstOrNull { it.id == trackerId }

    /**
     * Episodes up to [episodeNumber] of [animeId] were just watched: moves each bound tracker's progress forward
     * (never back), starts it as Watching, and marks it Completed at the last episode. Skipped in incognito and
     * when "Update progress after reading" is off.
     */
    fun onEpisodeSeen(animeId: Long, episodeNumber: Double) {
        if (episodeNumber <= 0) return
        // The player saves progress over and over once an episode counts as seen; report it once
        if (lastReported[animeId] == episodeNumber) return
        lastReported[animeId] = episodeNumber
        scope.launch {
            try {
                if (!Injekt.get<TrackPreferences>().autoUpdateTrack().get()) return@launch
                val anime = Injekt.get<AnimeRepository>().getAnime(animeId) ?: return@launch
                if (AnimeIncognito.isOn(anime.source)) return@launch
                repository.getTracks(animeId).forEach { track ->
                    val service = service(track.trackerId)?.takeIf { it.isLoggedIn } ?: return@forEach
                    if (episodeNumber <= track.lastEpisodeSeen) return@forEach
                    val now = System.currentTimeMillis()
                    val done = track.totalEpisodes > 0 && episodeNumber >= track.totalEpisodes
                    val updated = track.copy(
                        lastEpisodeSeen = episodeNumber,
                        status = when {
                            done -> AnimeTrackStatus.COMPLETED
                            track.status == AnimeTrackStatus.REWATCHING -> AnimeTrackStatus.REWATCHING
                            else -> AnimeTrackStatus.WATCHING
                        },
                        startDate = track.startDate.takeIf { it > 0 } ?: now,
                        finishDate = if (done && track.finishDate <= 0) now else track.finishDate,
                    )
                    try {
                        repository.upsert(service.save(updated))
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) { "Anime tracking: ${service.name} update failed" }
                        // Keep the progress here; it's sent the next time
                        repository.upsert(updated)
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Anime tracking failed" }
            }
        }
    }
}
