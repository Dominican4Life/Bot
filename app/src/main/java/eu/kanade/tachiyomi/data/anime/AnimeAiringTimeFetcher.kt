package eu.kanade.tachiyomi.data.anime

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Finds when the next episode of each anime airs, for the library's "Airing time" sorting.
 *
 * Anikku asks AniList by the id of the anime's tracker entry. Anime aren't tracked in this app yet, so the anime
 * is looked up on AniList by its title instead; a title AniList doesn't know simply gets no airing time.
 */
class AnimeAiringTimeFetcher(
    private val repository: AnimeRepository = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
) {

    /** Refreshes the airing time of every anime that can still get new episodes */
    suspend fun refresh(animes: List<Anime>) {
        for (anime in animes) {
            if (anime.status.toInt() == SAnime.COMPLETED) {
                if (anime.nextEpisodeAiringAt != 0L) repository.setNextEpisodeAiringAt(anime.id, 0L)
                continue
            }
            val airingAt = try {
                fetch(anime.title)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e, tag = "anime") { "${anime.title}: airing time lookup failed" }
                // Most likely offline or rate limited: the rest would fail the same way
                return
            }
            if (airingAt != anime.nextEpisodeAiringAt) repository.setNextEpisodeAiringAt(anime.id, airingAt)
            // AniList allows about 30 requests a minute
            delay(REQUEST_INTERVAL_MS)
        }
    }

    /** Seconds since epoch of the next episode, or 0 when AniList doesn't know the anime or nothing is scheduled */
    private suspend fun fetch(title: String): Long {
        val body = buildJsonObject {
            put("query", QUERY)
            put("variables", buildJsonObject { put("search", title) })
        }.toString().toRequestBody("application/json".toMediaType())
        return network.client.newCall(POST(ANILIST_URL, body = body)).await().use { response ->
            when {
                // AniList answers "not found" with a 404
                response.code == 404 -> 0L
                !response.isSuccessful -> throw IllegalStateException("HTTP ${response.code}")
                else -> {
                    val data = Json.parseToJsonElement(response.body.string()) as? JsonObject
                    val media = (data?.get("data") as? JsonObject)?.get("Media") as? JsonObject
                    val next = media?.get("nextAiringEpisode") as? JsonObject
                    next?.get("airingAt")?.jsonPrimitive?.longOrNull ?: 0L
                }
            }
        }
    }

    private companion object {
        const val ANILIST_URL = "https://graphql.anilist.co"
        const val QUERY = "query(\$search:String){Media(search:\$search,type:ANIME){nextAiringEpisode{airingAt}}}"
        const val REQUEST_INTERVAL_MS = 2_100L
    }
}
