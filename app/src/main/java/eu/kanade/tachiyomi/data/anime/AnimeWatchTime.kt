package eu.kanade.tachiyomi.data.anime

import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: total watch time per episode (anime_history.time_watch), shown in Stats.
 */
object AnimeWatchTime {

    private val handler: DatabaseHandler by lazy { Injekt.get() }

    /** Adds [millis] watched to [episodeId]'s history row, creating the row (last seen now) when missing */
    suspend fun add(episodeId: Long, millis: Long) {
        if (millis <= 0L) return
        handler.await {
            anime_historyQueries.addTimeWatch(
                episodeId = episodeId,
                now = System.currentTimeMillis(),
                millis = millis,
            )
        }
    }

    /** Total watched milliseconds over all episodes */
    suspend fun total(): Long = handler.awaitOne { anime_historyQueries.getTotalTimeWatch() }
}
