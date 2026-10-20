package eu.kanade.tachiyomi.data.anime.track

import android.app.Application
import androidx.compose.runtime.Immutable
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.anime.Anime
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

    // KMK -->
    /** Whether a list entry can be hidden from others on this tracker */
    val supportsPrivate: Boolean get() = false

    /** Whether the tracker keeps a score, and start/finish dates */
    val supportsScore: Boolean get() = true
    val supportsDates: Boolean get() = true
    // KMK <--

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
            // Aniyomi's order
            MyAnimeListAnime(manager.myAnimeList),
            AnilistAnime(manager.aniList),
            KitsuAnime(manager.kitsu),
            ShikimoriAnime(manager.shikimori),
            SimklAnime(manager.simkl),
            BangumiAnime(manager.bangumi),
        )
    }

    fun service(trackerId: Long): AnimeTrackService? = services.firstOrNull { it.id == trackerId }

    // ---- Changes that couldn't be sent yet ----

    private val pending by lazy { Injekt.get<TrackPreferences>().animeTrackPending() }
    private val pendingLock = Any()

    private fun pendingKey(animeId: Long, trackerId: Long) = "$animeId:$trackerId"

    fun isPending(animeId: Long, trackerId: Long): Boolean = pendingKey(animeId, trackerId) in pending.get()

    /** Remembers that the local entry has changes the tracker hasn't got, and sends them once online */
    fun markPending(animeId: Long, trackerId: Long) {
        synchronized(pendingLock) { pending.set(pending.get() + pendingKey(animeId, trackerId)) }
        try {
            AnimeDelayedTrackingJob.setupTask(Injekt.get<Application>())
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Anime tracking: couldn't schedule the delayed update" }
        }
    }

    private fun clearPending(animeId: Long, trackerId: Long) {
        synchronized(pendingLock) { pending.set(pending.get() - pendingKey(animeId, trackerId)) }
    }

    /**
     * Saves [track] here and sends it to [service]. When sending fails it's kept as pending and sent later
     * (AnimeDelayedTrackingJob). Returns whether the tracker got it.
     */
    suspend fun send(service: AnimeTrackService, track: AnimeTrack): Boolean {
        repository.upsert(track)
        return try {
            repository.upsert(service.save(track))
            clearPending(track.animeId, track.trackerId)
            true
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Anime tracking: ${service.name} update failed" }
            markPending(track.animeId, track.trackerId)
            false
        }
    }

    /** Sends every pending change again. Returns true when nothing is left pending. */
    suspend fun retryPending(): Boolean {
        val keys = pending.get()
        if (keys.isEmpty()) return true
        keys.forEach { key ->
            val animeId = key.substringBefore(':').toLongOrNull()
            val trackerId = key.substringAfter(':').toLongOrNull()
            if (animeId == null || trackerId == null) {
                synchronized(pendingLock) { pending.set(pending.get() - key) }
                return@forEach
            }
            val service = service(trackerId)
            val track = repository.getTracks(animeId).firstOrNull { it.trackerId == trackerId }
            // Unbound, or logged out since: nothing to send any more
            if (service == null || track == null || !service.isLoggedIn) {
                clearPending(animeId, trackerId)
                return@forEach
            }
            try {
                repository.upsert(service.save(track))
                clearPending(animeId, trackerId)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime tracking: ${service.name} delayed update failed" }
            }
        }
        return pending.get().isEmpty()
    }

    // ---- Pulling from the trackers ----

    /** [remote] as fetched, keeping what this tracker doesn't store (or didn't send) from [local] */
    fun mergeRemote(service: AnimeTrackService, local: AnimeTrack, remote: AnimeTrack): AnimeTrack = remote.copy(
        libraryId = remote.libraryId ?: local.libraryId,
        title = remote.title.ifBlank { local.title },
        totalEpisodes = remote.totalEpisodes.takeIf { it > 0 } ?: local.totalEpisodes,
        remoteUrl = remote.remoteUrl.ifBlank { local.remoteUrl },
        score = if (service.supportsScore) remote.score else local.score,
        startDate = if (service.supportsDates) remote.startDate else local.startDate,
        finishDate = if (service.supportsDates) remote.finishDate else local.finishDate,
        private = if (service.supportsPrivate) remote.private else local.private,
    )

    /**
     * Brings [anime]'s tracking up to date: sends pending changes, pulls each tracker's entry (progress, status,
     * title, episode count) into the local one.
     */
    suspend fun refresh(anime: Anime) {
        try {
            retryPending()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Anime tracking: retry failed" }
        }
        val tracks = repository.getTracks(anime.id)
        tracks.forEach { track ->
            val service = service(track.trackerId)?.takeIf { it.isLoggedIn } ?: return@forEach
            // Local changes the tracker hasn't got yet win
            if (isPending(anime.id, track.trackerId)) return@forEach
            try {
                service.find(anime.id, track.remoteId)?.let { repository.upsert(mergeRemote(service, track, it)) }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime tracking: refresh ${service.name}" }
            }
        }
    }

    /**
     * Episodes up to [episodeNumber] of [animeId] were just watched: moves each bound tracker's progress forward
     * (never back), starts it as Watching, and marks it Completed at the last episode. Skipped in incognito and
     * when "Update progress after reading" is off. Changes that can't be sent are sent later.
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
                val tracks = repository.getTracks(animeId)
                tracks.forEach { track ->
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
                    send(service, updated)
                }
                // Anything else still waiting
                retryPending()
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Anime tracking failed" }
            }
        }
    }
}
