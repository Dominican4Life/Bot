package eu.kanade.tachiyomi.data.anime.track

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * KMK: the anime side of the AniList and MyAnimeList trackers (from Anikku's AnilistApi / MyAnimeListApi, which
 * use type ANIME and the /anime endpoints). They sign in with the same login as the manga trackers.
 */

private val json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj(key: String): JsonObject? = ((this as? JsonObject)?.get(key) as? JsonObject)
private fun JsonElement?.arr(key: String): JsonArray? = ((this as? JsonObject)?.get(key) as? JsonArray)
private fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonElement?.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
private fun JsonElement?.double(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull

private fun epochToDate(millis: Long): LocalDate? =
    millis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

private fun dateToEpoch(date: LocalDate?): Long =
    date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L

// ======================= AniList =======================

class AnilistAnime(override val tracker: Anilist) : AnimeTrackService {

    private val client get() = tracker.animeClient

    private suspend fun query(query: String, variables: JsonObject): JsonObject = withIOContext {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(jsonMime)
        val response = client.newCall(POST(API_URL, body = body)).await()
        val text = response.body.string()
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw Exception("AniList answered ${response.code}")
        root.arr("errors")?.firstOrNull()?.let { error ->
            val message = error.str("message") ?: "AniList error"
            if (error.long("status") == 401L || message.contains("Invalid token")) {
                throw Exception("AniList token expired, please login again")
            }
            throw Exception(message)
        }
        if (!response.isSuccessful) throw Exception("AniList answered ${response.code}")
        root.obj("data") ?: JsonObject(emptyMap())
    }

    override suspend fun search(query: String): List<AnimeTrackSearch> {
        val q = """
            query Search(${'$'}query: String) {
              Page(perPage: 50) {
                media(search: ${'$'}query, type: ANIME) {
                  id title { userPreferred } coverImage { large } format episodes description
                  startDate { year } siteUrl
                }
              }
            }
        """.trimIndent()
        val data = query(q, buildJsonObject { put("query", query) })
        return data.obj("Page").arr("media").orEmpty().map { media ->
            AnimeTrackSearch(
                remoteId = media.long("id") ?: 0L,
                title = media.obj("title").str("userPreferred").orEmpty(),
                coverUrl = media.obj("coverImage").str("large"),
                format = media.str("format")?.replace('_', ' '),
                totalEpisodes = media.long("episodes") ?: 0L,
                summary = media.str("description")?.replace(Regex("<[^>]*>"), ""),
                startYear = media.obj("startDate").long("year")?.toString(),
                remoteUrl = media.str("siteUrl") ?: "https://anilist.co/anime/${media.long("id")}",
            )
        }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? {
        val userId = tracker.getUsername().toIntOrNull() ?: return null
        val q = """
            query Find(${'$'}id: Int, ${'$'}userId: Int) {
              Page {
                mediaList(userId: ${'$'}userId, type: ANIME, mediaId: ${'$'}id) {
                  id status scoreRaw: score(format: POINT_100) progress private
                  startedAt { year month day } completedAt { year month day }
                  media { id title { userPreferred } episodes siteUrl }
                }
              }
            }
        """.trimIndent()
        val data = query(
            q,
            buildJsonObject {
                put("id", remoteId)
                put("userId", userId)
            },
        )
        val entry = data.obj("Page").arr("mediaList")?.firstOrNull() ?: return null
        val media = entry.obj("media")
        return AnimeTrack(
            animeId = animeId,
            trackerId = id,
            remoteId = remoteId,
            libraryId = entry.long("id"),
            title = media.obj("title").str("userPreferred").orEmpty(),
            lastEpisodeSeen = (entry.long("progress") ?: 0L).toDouble(),
            totalEpisodes = media.long("episodes") ?: 0L,
            status = fromApiStatus(entry.str("status")),
            score = (entry.double("scoreRaw") ?: 0.0) / 10.0,
            remoteUrl = media.str("siteUrl") ?: "https://anilist.co/anime/$remoteId",
            startDate = dateToEpoch(entry.obj("startedAt").toDate()),
            finishDate = dateToEpoch(entry.obj("completedAt").toDate()),
            private = ((entry as? JsonObject)?.get("private") as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack {
        val q = """
            mutation Save(
              ${'$'}mediaId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus, ${'$'}scoreRaw: Int,
              ${'$'}private: Boolean, ${'$'}startedAt: FuzzyDateInput, ${'$'}completedAt: FuzzyDateInput
            ) {
              SaveMediaListEntry(
                mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: ${'$'}status, scoreRaw: ${'$'}scoreRaw,
                private: ${'$'}private, startedAt: ${'$'}startedAt, completedAt: ${'$'}completedAt
              ) { id }
            }
        """.trimIndent()
        val data = query(
            q,
            buildJsonObject {
                put("mediaId", track.remoteId)
                put("progress", track.lastEpisodeSeen.toInt())
                put("status", toApiStatus(track.status))
                put("scoreRaw", (track.score * 10).toInt())
                put("private", track.private)
                putDate("startedAt", track.startDate)
                putDate("completedAt", track.finishDate)
            },
        )
        return track.copy(libraryId = data.obj("SaveMediaListEntry").long("id") ?: track.libraryId)
    }

    override suspend fun delete(track: AnimeTrack) {
        val libraryId = track.libraryId ?: find(track.animeId, track.remoteId)?.libraryId ?: return
        query(
            "mutation Delete(${'$'}id: Int) { DeleteMediaListEntry(id: ${'$'}id) { deleted } }",
            buildJsonObject { put("id", libraryId) },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDate(key: String, millis: Long) {
        val date = epochToDate(millis) ?: return
        putJsonObject(key) {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
        }
    }

    private fun JsonObject?.toDate(): LocalDate? {
        val year = this.long("year")?.toInt() ?: return null
        return runCatching { LocalDate.of(year, (this.long("month") ?: 1L).toInt(), (this.long("day") ?: 1L).toInt()) }
            .getOrNull()
    }

    private fun toApiStatus(status: Long) = when (status) {
        AnimeTrackStatus.WATCHING -> "CURRENT"
        AnimeTrackStatus.COMPLETED -> "COMPLETED"
        AnimeTrackStatus.ON_HOLD -> "PAUSED"
        AnimeTrackStatus.DROPPED -> "DROPPED"
        AnimeTrackStatus.REWATCHING -> "REPEATING"
        else -> "PLANNING"
    }

    private fun fromApiStatus(status: String?) = when (status) {
        "CURRENT" -> AnimeTrackStatus.WATCHING
        "COMPLETED" -> AnimeTrackStatus.COMPLETED
        "PAUSED" -> AnimeTrackStatus.ON_HOLD
        "DROPPED" -> AnimeTrackStatus.DROPPED
        "REPEATING" -> AnimeTrackStatus.REWATCHING
        else -> AnimeTrackStatus.PLAN_TO_WATCH
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co/"
    }
}

// ======================= MyAnimeList =======================

class MyAnimeListAnime(override val tracker: MyAnimeList) : AnimeTrackService {

    private val client get() = tracker.animeClient

    override suspend fun search(query: String): List<AnimeTrackSearch> = withIOContext {
        // MAL wants at least 3 letters, and up to 64
        val q = query.trim().take(64)
        if (q.length < 3) return@withIOContext emptyList()
        val url = "$API_URL/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("nsfw", "true")
            .addQueryParameter("limit", "25")
            .addQueryParameter("fields", "id,title,synopsis,num_episodes,main_picture,media_type,start_date")
            .build()
        val text = client.newCall(GET(url.toString())).awaitSuccess().body.string()
        json.parseToJsonElement(text).arr("data").orEmpty().mapNotNull { item ->
            val node = item.obj("node") ?: return@mapNotNull null
            val remoteId = node.long("id") ?: return@mapNotNull null
            AnimeTrackSearch(
                remoteId = remoteId,
                title = node.str("title").orEmpty(),
                coverUrl = node.obj("main_picture").str("large") ?: node.obj("main_picture").str("medium"),
                format = node.str("media_type")?.uppercase()?.replace('_', ' '),
                totalEpisodes = node.long("num_episodes") ?: 0L,
                summary = node.str("synopsis"),
                startYear = node.str("start_date")?.take(4),
                remoteUrl = "https://myanimelist.net/anime/$remoteId",
            )
        }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? = withIOContext {
        val url = "$API_URL/anime/$remoteId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,title,num_episodes,my_list_status{start_date,finish_date}")
            .build()
        val text = client.newCall(GET(url.toString())).awaitSuccess().body.string()
        val root = json.parseToJsonElement(text)
        val status = root.obj("my_list_status") ?: return@withIOContext null
        AnimeTrack(
            animeId = animeId,
            trackerId = id,
            remoteId = remoteId,
            libraryId = null,
            title = root.str("title").orEmpty(),
            lastEpisodeSeen = (status.long("num_episodes_watched") ?: 0L).toDouble(),
            totalEpisodes = root.long("num_episodes") ?: 0L,
            status = if (status.str("is_rewatching") == "true") {
                AnimeTrackStatus.REWATCHING
            } else {
                fromApiStatus(status.str("status"))
            },
            score = status.double("score") ?: 0.0,
            remoteUrl = "https://myanimelist.net/anime/$remoteId",
            startDate = dateToEpoch(status.str("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }),
            finishDate = dateToEpoch(status.str("finish_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }),
            private = false,
        )
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack = withIOContext {
        val form = FormBody.Builder()
            .add("status", toApiStatus(track.status))
            .add("is_rewatching", (track.status == AnimeTrackStatus.REWATCHING).toString())
            .add("score", track.score.toInt().coerceIn(0, 10).toString())
            .add("num_watched_episodes", track.lastEpisodeSeen.toInt().toString())
        epochToDate(track.startDate)?.let { form.add("start_date", it.toString()) }
        epochToDate(track.finishDate)?.let { form.add("finish_date", it.toString()) }
        client.newCall(PUT("$API_URL/anime/${track.remoteId}/my_list_status", body = form.build())).awaitSuccess()
            .close()
        track
    }

    override suspend fun delete(track: AnimeTrack) {
        withIOContext {
            client.newCall(DELETE("$API_URL/anime/${track.remoteId}/my_list_status")).await().close()
        }
    }

    private fun toApiStatus(status: Long) = when (status) {
        AnimeTrackStatus.WATCHING, AnimeTrackStatus.REWATCHING -> "watching"
        AnimeTrackStatus.COMPLETED -> "completed"
        AnimeTrackStatus.ON_HOLD -> "on_hold"
        AnimeTrackStatus.DROPPED -> "dropped"
        else -> "plan_to_watch"
    }

    private fun fromApiStatus(status: String?) = when (status) {
        "watching" -> AnimeTrackStatus.WATCHING
        "completed" -> AnimeTrackStatus.COMPLETED
        "on_hold" -> AnimeTrackStatus.ON_HOLD
        "dropped" -> AnimeTrackStatus.DROPPED
        else -> AnimeTrackStatus.PLAN_TO_WATCH
    }

    companion object {
        private const val API_URL = "https://api.myanimelist.net/v2"
    }
}
