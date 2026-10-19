package eu.kanade.tachiyomi.data.anime.track

import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.sourcePreferences
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.jellyfin.Jellyfin
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.KitsuDateHelper
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.data.track.simkl.Simkl
import eu.kanade.tachiyomi.data.track.simkl.SimklApi
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.PATCH
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/*
 * KMK: the anime side of the trackers. AniList, MyAnimeList, Kitsu, Shikimori and Bangumi share the manga
 * trackers' logins; Simkl and Jellyfin are anime-only (see TrackerManager.animeOnlyTrackers).
 *
 * The anime side of the AniList and MyAnimeList trackers (from Anikku's AnilistApi / MyAnimeListApi, which
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

    override val supportsPrivate: Boolean get() = true

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

// ======================= Kitsu =======================

/** From Anikku's KitsuApi: library entries of kind anime, searched through Kitsu's Algolia index */
class KitsuAnime(override val tracker: Kitsu) : AnimeTrackService {

    private val client get() = tracker.animeClient

    override val supportsPrivate: Boolean get() = true

    override suspend fun search(query: String): List<AnimeTrackSearch> = withIOContext {
        val keyText = client.newCall(GET(KITSU_ALGOLIA_KEY_URL)).awaitSuccess().body.string()
        val key = json.parseToJsonElement(keyText).obj("media").str("key")
            ?: throw Exception("Kitsu search is unavailable")
        val params = "query=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}$KITSU_ALGOLIA_FILTER"
        val body = buildJsonObject { put("params", params) }.toString().toRequestBody(jsonMime)
        val text = tracker.client.newCall(
            POST(
                KITSU_ALGOLIA_URL,
                headers = headersOf("X-Algolia-Application-Id", KITSU_ALGOLIA_APP_ID, "X-Algolia-API-Key", key),
                body = body,
            ),
        ).awaitSuccess().body.string()
        json.parseToJsonElement(text).arr("hits").orEmpty().mapNotNull { hit ->
            val remoteId = hit.long("id") ?: return@mapNotNull null
            AnimeTrackSearch(
                remoteId = remoteId,
                title = hit.str("canonicalTitle").orEmpty(),
                coverUrl = hit.obj("posterImage").str("original"),
                format = hit.str("subtype")?.uppercase(),
                totalEpisodes = hit.long("episodeCount") ?: 0L,
                summary = hit.str("synopsis"),
                startYear = hit.long("startDate")?.let { epochToDate(it * 1000)?.year?.toString() },
                remoteUrl = "$KITSU_ANIME_URL$remoteId",
            )
        }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? = withIOContext {
        val userId = tracker.getPassword()
        if (userId.isBlank()) return@withIOContext null
        val url = "${KITSU_API_URL}library-entries".toHttpUrl().newBuilder()
            .addQueryParameter("filter[anime_id]", remoteId.toString())
            .addQueryParameter("filter[user_id]", userId)
            .addQueryParameter("include", "anime")
            .build()
        val root = json.parseToJsonElement(client.newCall(GET(url)).awaitSuccess().body.string())
        val entry = root.arr("data")?.firstOrNull() ?: return@withIOContext null
        val attrs = entry.obj("attributes")
        val animeAttrs = root.arr("included")?.firstOrNull().obj("attributes")
        val status = attrs.str("status")
        AnimeTrack(
            animeId = animeId,
            trackerId = id,
            remoteId = remoteId,
            libraryId = entry.long("id"),
            title = animeAttrs.str("canonicalTitle").orEmpty(),
            lastEpisodeSeen = (attrs.long("progress") ?: 0L).toDouble(),
            totalEpisodes = animeAttrs.long("episodeCount") ?: 0L,
            status = if (status == "current" && attrs.str("reconsuming") == "true") {
                AnimeTrackStatus.REWATCHING
            } else {
                fromApiStatus(status)
            },
            score = (attrs.double("ratingTwenty") ?: 0.0) / 2.0,
            remoteUrl = "$KITSU_ANIME_URL$remoteId",
            startDate = runCatching { KitsuDateHelper.parse(attrs.str("startedAt")) }.getOrDefault(0L),
            finishDate = runCatching { KitsuDateHelper.parse(attrs.str("finishedAt")) }.getOrDefault(0L),
            private = attrs.str("private") == "true",
        )
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack = withIOContext {
        val libraryId = track.libraryId ?: find(track.animeId, track.remoteId)?.libraryId
        // Kitsu rates 2–20
        val rating: Int? = (track.score * 2).toInt().takeIf { it > 0 }?.coerceIn(2, 20)
        val attributes = buildJsonObject {
            put("status", toApiStatus(track.status))
            put("reconsuming", track.status == AnimeTrackStatus.REWATCHING)
            put("progress", track.lastEpisodeSeen.toInt())
            put("ratingTwenty", rating)
            put("startedAt", KitsuDateHelper.convert(track.startDate))
            put("finishedAt", KitsuDateHelper.convert(track.finishDate))
            put("private", track.private)
        }
        if (libraryId == null) {
            val payload = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    put("attributes", attributes)
                    putJsonObject("relationships") {
                        putJsonObject("user") {
                            putJsonObject("data") {
                                put("id", tracker.getPassword())
                                put("type", "users")
                            }
                        }
                        putJsonObject("media") {
                            putJsonObject("data") {
                                put("id", track.remoteId)
                                put("type", "anime")
                            }
                        }
                    }
                }
            }
            val text = client.newCall(
                POST(
                    "${KITSU_API_URL}library-entries",
                    headers = headersOf("Content-Type", KITSU_VND_API_JSON),
                    body = payload.toString().toRequestBody(KITSU_VND_API_JSON.toMediaType()),
                ),
            ).awaitSuccess().body.string()
            track.copy(libraryId = json.parseToJsonElement(text).obj("data").long("id"))
        } else {
            val payload = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    put("id", libraryId)
                    put("attributes", attributes)
                }
            }
            client.newCall(
                PATCH(
                    "${KITSU_API_URL}library-entries/$libraryId",
                    headers = headersOf("Content-Type", KITSU_VND_API_JSON),
                    body = payload.toString().toRequestBody(KITSU_VND_API_JSON.toMediaType()),
                ),
            ).awaitSuccess().close()
            track.copy(libraryId = libraryId)
        }
    }

    override suspend fun delete(track: AnimeTrack) {
        val libraryId = track.libraryId ?: find(track.animeId, track.remoteId)?.libraryId ?: return
        withIOContext {
            client.newCall(
                DELETE(
                    "${KITSU_API_URL}library-entries/$libraryId",
                    headers = headersOf("Content-Type", KITSU_VND_API_JSON),
                ),
            ).awaitSuccess().close()
        }
    }

    private fun toApiStatus(status: Long) = when (status) {
        AnimeTrackStatus.WATCHING, AnimeTrackStatus.REWATCHING -> "current"
        AnimeTrackStatus.COMPLETED -> "completed"
        AnimeTrackStatus.ON_HOLD -> "on_hold"
        AnimeTrackStatus.DROPPED -> "dropped"
        else -> "planned"
    }

    private fun fromApiStatus(status: String?) = when (status) {
        "current" -> AnimeTrackStatus.WATCHING
        "completed" -> AnimeTrackStatus.COMPLETED
        "on_hold" -> AnimeTrackStatus.ON_HOLD
        "dropped" -> AnimeTrackStatus.DROPPED
        else -> AnimeTrackStatus.PLAN_TO_WATCH
    }
}

private const val KITSU_API_URL = "https://kitsu.app/api/edge/"
private const val KITSU_ANIME_URL = "https://kitsu.app/anime/"
private const val KITSU_ALGOLIA_KEY_URL = "https://kitsu.app/api/edge/algolia-keys/media/"
private const val KITSU_ALGOLIA_APP_ID = "AWQO5J657S"
private const val KITSU_ALGOLIA_URL = "https://$KITSU_ALGOLIA_APP_ID-dsn.algolia.net/1/indexes/production_media/query/"
private const val KITSU_ALGOLIA_FILTER = "&facetFilters=%5B%22kind%3Aanime%22%5D&attributesToRetrieve=" +
    "%5B%22synopsis%22%2C%22averageRating%22%2C%22canonicalTitle%22%2C%22episodeCount%22%2C%22" +
    "posterImage%22%2C%22startDate%22%2C%22subtype%22%2C%22endDate%22%2C%20%22id%22%5D"
private const val KITSU_VND_API_JSON = "application/vnd.api+json"

// ======================= Shikimori =======================

/** From Anikku's ShikimoriApi: /api/animes and user_rates with target_type Anime */
class ShikimoriAnime(override val tracker: Shikimori) : AnimeTrackService {

    private val client get() = tracker.animeClient

    override val supportsDates: Boolean get() = false

    override suspend fun search(query: String): List<AnimeTrackSearch> = withIOContext {
        val url = "$SHIKIMORI_API_URL/animes".toHttpUrl().newBuilder()
            .addQueryParameter("order", "popularity")
            .addQueryParameter("search", query)
            .addQueryParameter("limit", "20")
            .build()
        val text = client.newCall(GET(url)).awaitSuccess().body.string()
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { item ->
            val remoteId = item.long("id") ?: return@mapNotNull null
            AnimeTrackSearch(
                remoteId = remoteId,
                title = item.str("name").orEmpty(),
                coverUrl = item.obj("image").str("preview")?.let { ShikimoriApi.BASE_URL + it },
                format = item.str("kind")?.uppercase()?.replace('_', ' '),
                totalEpisodes = item.long("episodes")?.takeIf { it > 0 } ?: item.long("episodes_aired") ?: 0L,
                summary = item.str("russian"),
                startYear = item.str("aired_on")?.take(4),
                remoteUrl = item.str("url")?.let { ShikimoriApi.BASE_URL + it }
                    ?: "${ShikimoriApi.BASE_URL}/animes/$remoteId",
            )
        }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? = withIOContext {
        val userId = tracker.getUsername()
        if (userId.isBlank()) return@withIOContext null
        val anime = json.parseToJsonElement(
            client.newCall(GET("$SHIKIMORI_API_URL/animes/$remoteId")).awaitSuccess().body.string(),
        )
        val ratesUrl = "$SHIKIMORI_API_URL/v2/user_rates".toHttpUrl().newBuilder()
            .addQueryParameter("user_id", userId)
            .addQueryParameter("target_id", remoteId.toString())
            .addQueryParameter("target_type", "Anime")
            .build()
        val rates = json.parseToJsonElement(client.newCall(GET(ratesUrl)).awaitSuccess().body.string())
        val entry = (rates as? JsonArray)?.firstOrNull() ?: return@withIOContext null
        AnimeTrack(
            animeId = animeId,
            trackerId = id,
            remoteId = remoteId,
            libraryId = entry.long("id"),
            title = anime.str("name").orEmpty(),
            lastEpisodeSeen = entry.double("episodes") ?: 0.0,
            totalEpisodes = anime.long("episodes")?.takeIf { it > 0 } ?: anime.long("episodes_aired") ?: 0L,
            status = fromApiStatus(entry.str("status")),
            score = entry.double("score") ?: 0.0,
            remoteUrl = anime.str("url")?.let { ShikimoriApi.BASE_URL + it }
                ?: "${ShikimoriApi.BASE_URL}/animes/$remoteId",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack = withIOContext {
        val payload = buildJsonObject {
            putJsonObject("user_rate") {
                put("user_id", tracker.getUsername())
                put("target_id", track.remoteId)
                put("target_type", "Anime")
                put("episodes", track.lastEpisodeSeen.toInt())
                put("score", track.score.toInt().coerceIn(0, 10))
                put("status", toApiStatus(track.status))
            }
        }.toString().toRequestBody(jsonMime)
        val libraryId = track.libraryId
        val request = if (libraryId != null) {
            PATCH("$SHIKIMORI_API_URL/v2/user_rates/$libraryId", body = payload)
        } else {
            POST("$SHIKIMORI_API_URL/v2/user_rates", body = payload)
        }
        val text = client.newCall(request).awaitSuccess().body.string()
        track.copy(libraryId = runCatching { json.parseToJsonElement(text).long("id") }.getOrNull() ?: libraryId)
    }

    override suspend fun delete(track: AnimeTrack) {
        val libraryId = track.libraryId ?: find(track.animeId, track.remoteId)?.libraryId ?: return
        withIOContext {
            client.newCall(DELETE("$SHIKIMORI_API_URL/v2/user_rates/$libraryId")).awaitSuccess().close()
        }
    }

    private fun toApiStatus(status: Long) = when (status) {
        AnimeTrackStatus.WATCHING -> "watching"
        AnimeTrackStatus.COMPLETED -> "completed"
        AnimeTrackStatus.ON_HOLD -> "on_hold"
        AnimeTrackStatus.DROPPED -> "dropped"
        AnimeTrackStatus.REWATCHING -> "rewatching"
        else -> "planned"
    }

    private fun fromApiStatus(status: String?) = when (status) {
        "watching" -> AnimeTrackStatus.WATCHING
        "completed" -> AnimeTrackStatus.COMPLETED
        "on_hold" -> AnimeTrackStatus.ON_HOLD
        "dropped" -> AnimeTrackStatus.DROPPED
        "rewatching" -> AnimeTrackStatus.REWATCHING
        else -> AnimeTrackStatus.PLAN_TO_WATCH
    }
}

private const val SHIKIMORI_API_URL = "${ShikimoriApi.BASE_URL}/api"

// ======================= Bangumi =======================

/**
 * Bangumi subjects of type 2 (anime). Status, score and privacy go through the v0 collections API; episode
 * progress through the older watched_eps call (as Anikku does), since v0 can only set it for books.
 */
class BangumiAnime(override val tracker: Bangumi) : AnimeTrackService {

    private val client get() = tracker.animeClient

    override val supportsPrivate: Boolean get() = true

    override val supportsDates: Boolean get() = false

    override suspend fun search(query: String): List<AnimeTrackSearch> = withIOContext {
        val body = buildJsonObject {
            put("keyword", query)
            put("sort", "match")
            putJsonObject("filter") {
                putJsonArray("type") { add(2) }
            }
        }.toString().toRequestBody(jsonMime)
        val text = client.newCall(POST("$BANGUMI_API_URL/v0/search/subjects?limit=20", body = body))
            .awaitSuccess().body.string()
        json.parseToJsonElement(text).arr("data").orEmpty().mapNotNull { item ->
            val remoteId = item.long("id") ?: return@mapNotNull null
            val name = item.str("name").orEmpty()
            AnimeTrackSearch(
                remoteId = remoteId,
                title = item.str("name_cn")?.takeIf { it.isNotBlank() } ?: name,
                coverUrl = item.obj("images").str("common") ?: item.obj("images").str("large"),
                format = item.str("platform"),
                totalEpisodes = item.long("eps")?.takeIf { it > 0 } ?: item.long("total_episodes") ?: 0L,
                summary = item.str("summary")?.trim(),
                startYear = item.str("date")?.take(4),
                remoteUrl = "$BANGUMI_SUBJECT_URL$remoteId",
            )
        }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? = withIOContext {
        val username = tracker.getUsername()
        if (username.isBlank()) return@withIOContext null
        val response = client.newCall(
            GET("$BANGUMI_API_URL/v0/users/$username/collections/$remoteId", cache = CacheControl.FORCE_NETWORK),
        ).await()
        if (response.code == 404) {
            // Not on the user's list
            response.close()
            return@withIOContext null
        }
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
        val entry = json.parseToJsonElement(response.body.string())
        val subject = entry.obj("subject")
        AnimeTrack(
            animeId = animeId,
            trackerId = id,
            remoteId = remoteId,
            libraryId = null,
            title = subject.str("name_cn")?.takeIf { it.isNotBlank() } ?: subject.str("name").orEmpty(),
            lastEpisodeSeen = (entry.long("ep_status") ?: 0L).toDouble(),
            totalEpisodes = subject.long("eps") ?: 0L,
            status = fromApiStatus(entry.long("type")),
            score = (entry.long("rate") ?: 0L).toDouble(),
            remoteUrl = "$BANGUMI_SUBJECT_URL$remoteId",
            startDate = 0L,
            finishDate = 0L,
            private = entry.str("private") == "true",
        )
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack = withIOContext {
        val body = buildJsonObject {
            put("type", toApiStatus(track.status))
            put("rate", track.score.toInt().coerceIn(0, 10))
            put("private", track.private)
        }.toString().toRequestBody(jsonMime)
        // Adds the subject to the list, or updates it
        client.newCall(POST("$BANGUMI_API_URL/v0/users/-/collections/${track.remoteId}", body = body))
            .awaitSuccess().close()
        val episodes = FormBody.Builder()
            .add("watched_eps", track.lastEpisodeSeen.toInt().toString())
            .build()
        client.newCall(POST("$BANGUMI_API_URL/subject/${track.remoteId}/update/watched_eps", body = episodes))
            .awaitSuccess().close()
        track
    }

    // Bangumi can't remove a subject from the list through its API; only the binding here is removed
    override suspend fun delete(track: AnimeTrack) = Unit

    private fun toApiStatus(status: Long) = when (status) {
        AnimeTrackStatus.PLAN_TO_WATCH -> 1
        AnimeTrackStatus.COMPLETED -> 2
        AnimeTrackStatus.ON_HOLD -> 4
        AnimeTrackStatus.DROPPED -> 5
        else -> 3
    }

    private fun fromApiStatus(type: Long?) = when (type) {
        1L -> AnimeTrackStatus.PLAN_TO_WATCH
        2L -> AnimeTrackStatus.COMPLETED
        4L -> AnimeTrackStatus.ON_HOLD
        5L -> AnimeTrackStatus.DROPPED
        else -> AnimeTrackStatus.WATCHING
    }
}

private const val BANGUMI_API_URL = "https://api.bgm.tv"
private const val BANGUMI_SUBJECT_URL = "https://bangumi.tv/subject/"

// ======================= Simkl =======================

/** From Anikku's SimklApi: anime, TV shows and movies, with watched episodes sent as season 1 history */
class SimklAnime(override val tracker: Simkl) : AnimeTrackService {

    private val client get() = tracker.animeClient

    override val supportsDates: Boolean get() = false

    override suspend fun search(query: String): List<AnimeTrackSearch> = withIOContext {
        listOf("anime", "tv", "movie").flatMap { type ->
            val url = "${SimklApi.API_URL}/search/$type".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("extended", "full")
                .addQueryParameter("client_id", SimklApi.CLIENT_ID)
                .build()
            val text = try {
                tracker.client.newCall(GET(url)).awaitSuccess().body.string()
            } catch (e: Exception) {
                // Anime results are the ones that matter; TV and movies are a bonus
                if (type == "anime") throw e
                ""
            }
            if (text.isBlank()) return@flatMap emptyList<AnimeTrackSearch>()
            (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { item ->
                val remoteId = item.obj("ids").long("simkl_id") ?: return@mapNotNull null
                val path = item.str("url") ?: "/${if (type == "movie") "movies" else type}/$remoteId"
                AnimeTrackSearch(
                    remoteId = remoteId,
                    title = item.str("title_romaji") ?: item.str("title").orEmpty(),
                    coverUrl = item.str("poster")?.let { "${SimklApi.POSTERS_URL}${it}_m.webp" },
                    format = (item.str("type") ?: type).uppercase(),
                    totalEpisodes = item.long("ep_count") ?: if (type == "movie") 1L else 0L,
                    summary = item.arr("all_titles")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.joinToString("\n"),
                    startYear = item.long("year")?.toString(),
                    remoteUrl = if (path.startsWith("http")) path else SimklApi.BASE_URL + path,
                )
            }
        }.distinctBy { it.remoteId }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? = withIOContext {
        val payload = buildJsonArray { addJsonObject { put("simkl", remoteId) } }
        val watchedText = client.newCall(
            POST("${SimklApi.API_URL}/sync/watched", body = payload.toString().toRequestBody(jsonMime)),
        ).awaitSuccess().body.string()
        val watched = (json.parseToJsonElement(watchedText) as? JsonArray)?.firstOrNull()
            ?: return@withIOContext null
        if (watched.str("result") != "true") return@withIOContext null
        val list = watched.str("list") ?: return@withIOContext null
        val lastWatched = watched.str("last_watched_at")
        for (type in listOf("anime", "shows", "movies")) {
            val url = "${SimklApi.API_URL}/sync/all-items/$type/$list".toHttpUrl().newBuilder()
                .apply { if (lastWatched != null) addQueryParameter("date_from", lastWatched) }
                .build()
            val text = client.newCall(GET(url, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().body.string()
            val root = json.parseToJsonElement(text.ifBlank { "null" }) as? JsonObject ?: continue
            val item = root.values.filterIsInstance<JsonArray>().flatten().firstOrNull {
                (it.obj("show") ?: it.obj("movie")).obj("ids").long("simkl") == remoteId
            } ?: continue
            val isMovie = item.obj("show") == null
            val media = item.obj("show") ?: item.obj("movie")
            val urlType = when (type) {
                "shows" -> "tv"
                else -> type
            }
            return@withIOContext AnimeTrack(
                animeId = animeId,
                trackerId = id,
                remoteId = remoteId,
                libraryId = null,
                title = media.str("title").orEmpty(),
                lastEpisodeSeen = if (isMovie) {
                    if (list == "completed") 1.0 else 0.0
                } else {
                    item.double("watched_episodes_count") ?: 0.0
                },
                totalEpisodes = if (isMovie) 1L else item.long("total_episodes_count") ?: 0L,
                status = fromApiStatus(list),
                score = item.double("user_rating") ?: 0.0,
                remoteUrl = "${SimklApi.BASE_URL}/$urlType/$remoteId",
                startDate = 0L,
                finishDate = 0L,
                private = false,
            )
        }
        null
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack = withIOContext {
        val mediaType = mediaType(track.remoteUrl)
        if (mediaType == "shows") {
            // Watched episodes: clear season 1, then add episodes 1..n again
            post(
                "sync/history/remove",
                entry(mediaType, track.remoteId) { putJsonArray("seasons") { addJsonObject { put("number", 1) } } },
            )
            val seen = track.lastEpisodeSeen.toInt()
            if (seen > 0) {
                post(
                    "sync/history",
                    entry(mediaType, track.remoteId) {
                        putJsonArray("seasons") {
                            addJsonObject {
                                put("number", 1)
                                putJsonArray("episodes") {
                                    for (episode in 1..seen) addJsonObject { put("number", episode) }
                                }
                            }
                        }
                    },
                )
            }
        }
        post("sync/add-to-list", entry(mediaType, track.remoteId) { put("to", toApiStatus(track.status)) })
        val score = track.score.toInt().coerceIn(0, 10)
        if (score > 0) {
            post("sync/ratings", entry(mediaType, track.remoteId) { put("rating", score) })
        } else {
            post("sync/ratings/remove", entry(mediaType, track.remoteId) {})
        }
        track
    }

    override suspend fun delete(track: AnimeTrack) {
        withIOContext {
            post("sync/history/remove", entry(mediaType(track.remoteUrl), track.remoteId) {})
        }
    }

    /** "movies" or "shows", from the item's simkl.com link (/movies/…, /tv/…, /anime/…) */
    private fun mediaType(remoteUrl: String): String {
        val type = remoteUrl.substringAfter("simkl.com/", remoteUrl.removePrefix("/")).substringBefore("/")
        return if (type == "movies" || type == "movie") "movies" else "shows"
    }

    private fun entry(
        mediaType: String,
        remoteId: Long,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        putJsonArray(mediaType) {
            addJsonObject {
                putJsonObject("ids") { put("simkl", remoteId) }
                extra()
            }
        }
    }

    private suspend fun post(path: String, body: JsonObject) {
        client.newCall(POST("${SimklApi.API_URL}/$path", body = body.toString().toRequestBody(jsonMime)))
            .awaitSuccess().close()
    }

    private fun toApiStatus(status: Long) = when (status) {
        AnimeTrackStatus.WATCHING, AnimeTrackStatus.REWATCHING -> "watching"
        AnimeTrackStatus.COMPLETED -> "completed"
        AnimeTrackStatus.ON_HOLD -> "hold"
        AnimeTrackStatus.DROPPED -> "dropped"
        else -> "plantowatch"
    }

    private fun fromApiStatus(status: String?) = when (status) {
        "watching" -> AnimeTrackStatus.WATCHING
        "completed" -> AnimeTrackStatus.COMPLETED
        "hold" -> AnimeTrackStatus.ON_HOLD
        "dropped", "notinteresting" -> AnimeTrackStatus.DROPPED
        else -> AnimeTrackStatus.PLAN_TO_WATCH
    }
}

// ======================= Jellyfin =======================

/**
 * From Anikku's JellyfinApi: no search; anime from the Jellyfin extension are bound by their own URL, and the
 * extension's server login (its api key) is used. Watching an episode marks it played on the server.
 */
class JellyfinAnime(override val tracker: Jellyfin) : AnimeTrackService {

    private val client get() = tracker.animeClient

    override val supportsScore: Boolean get() = false

    override val supportsDates: Boolean get() = false

    override val canSearch: Boolean get() = false

    override fun accepts(anime: Anime): Boolean {
        val source = Injekt.get<AnimeSourceManager>().get(anime.source) ?: return false
        return source::class.qualifiedName == Jellyfin.SOURCE_CLASS
    }

    override suspend fun search(query: String): List<AnimeTrackSearch> = emptyList()

    override suspend fun autoBind(anime: Anime, lastSeenEpisode: Double): AnimeTrack? {
        if (!accepts(anime)) return null
        return try {
            fetch(anime)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Jellyfin: couldn't bind ${anime.url}" }
            null
        }
    }

    override suspend fun find(animeId: Long, remoteId: Long): AnimeTrack? {
        val anime = Injekt.get<AnimeRepository>().getAnime(animeId) ?: return null
        if (!accepts(anime)) return null
        return fetch(anime)
    }

    override suspend fun save(track: AnimeTrack): AnimeTrack = withIOContext {
        val anime = Injekt.get<AnimeRepository>().getAnime(track.animeId) ?: return@withIOContext track
        val url = withApiKey(anime, track.remoteUrl.ifBlank { anime.url }.toHttpUrl())
        val fragment = url.fragment.orEmpty()
        val itemId = if (fragment.startsWith("movie")) {
            url.pathSegments.last().takeIf { track.lastEpisodeSeen > 0.0 }
        } else {
            episodes(anime, url).firstOrNull { episode ->
                val number = episode.long("IndexNumber") ?: return@firstOrNull false
                abs(number - track.lastEpisodeSeen) < 0.001
            }?.str("Id")
        }
        if (itemId != null) {
            val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
            val postUrl = url.newBuilder()
                .fragment(null)
                .removePathSegment(3)
                .removePathSegment(2)
                .addPathSegment("PlayedItems")
                .addPathSegment(itemId)
                .addQueryParameter("DatePlayed", time)
                .build()
            client.newCall(POST(postUrl.toString())).awaitSuccess().close()
        }
        val remote = fetch(anime)
        track.copy(
            lastEpisodeSeen = remote.lastEpisodeSeen,
            totalEpisodes = remote.totalEpisodes,
            status = remote.status,
        )
    }

    // Nothing to remove on the server
    override suspend fun delete(track: AnimeTrack) = Unit

    private suspend fun fetch(anime: Anime): AnimeTrack = withIOContext {
        val url = withApiKey(anime, anime.url.toHttpUrl())
        val fragment = url.fragment ?: throw IllegalArgumentException("Not a Jellyfin item: ${anime.url}")
        val item = json.parseToJsonElement(client.newCall(GET(url)).awaitSuccess().body.string())
        val total: Long
        val seen: Double
        val status: Long
        when {
            fragment.startsWith("season") -> {
                val episodes = episodes(anime, url)
                val firstUnseen = episodes.indexOfFirst { !it.played() }
                total = episodes.lastOrNull()?.long("IndexNumber") ?: episodes.size.toLong()
                when {
                    episodes.isEmpty() || firstUnseen == 0 -> {
                        seen = 0.0
                        status = AnimeTrackStatus.PLAN_TO_WATCH
                    }
                    firstUnseen == -1 -> {
                        seen = total.toDouble()
                        status = AnimeTrackStatus.COMPLETED
                    }
                    else -> {
                        seen = (episodes[firstUnseen - 1].long("IndexNumber") ?: firstUnseen.toLong()).toDouble()
                        status = AnimeTrackStatus.WATCHING
                    }
                }
            }
            fragment.startsWith("movie") -> {
                val played = item.played()
                total = 1L
                seen = if (played) 1.0 else 0.0
                status = if (played) AnimeTrackStatus.COMPLETED else AnimeTrackStatus.PLAN_TO_WATCH
            }
            else -> throw IllegalArgumentException("Unexpected Jellyfin item: $fragment")
        }
        AnimeTrack(
            animeId = anime.id,
            trackerId = id,
            remoteId = anime.id,
            libraryId = null,
            title = item.str("Name") ?: anime.title,
            lastEpisodeSeen = seen,
            totalEpisodes = total,
            status = status,
            score = 0.0,
            // Kept without the api key, so copying the link never shares it
            remoteUrl = anime.url,
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )
    }

    private fun JsonElement.played(): Boolean = obj("UserData").str("Played") == "true"

    /** The episodes of the season [url] points at (…/Users/{user}/Items/{season}#season,{series}) */
    private suspend fun episodes(anime: Anime, url: HttpUrl): List<JsonElement> {
        val fragment = url.fragment.orEmpty()
        val episodesUrl = url.newBuilder()
            .encodedPath("/")
            .fragment(null)
            .encodedQuery(null)
            .addPathSegment("Shows")
            .addPathSegment(fragment.split(",").last())
            .addPathSegment("Episodes")
            .addQueryParameter("seasonId", url.pathSegments.last())
            .addQueryParameter("userId", url.pathSegments[1])
            .addQueryParameter("Fields", "Overview,MediaSources")
            .build()
        val text = client.newCall(GET(withApiKey(anime, episodesUrl))).awaitSuccess().body.string()
        return json.parseToJsonElement(text).arr("Items").orEmpty()
    }

    /** Adds the Jellyfin extension's api key, unless the URL already carries one */
    private fun withApiKey(anime: Anime, url: HttpUrl): HttpUrl {
        if (url.queryParameter("api_key") != null) return url
        val source = Injekt.get<AnimeSourceManager>().get(anime.source) as? ConfigurableAnimeSource
        val key = source?.sourcePreferences()?.getString("api_key", null)
        if (key.isNullOrBlank()) throw Exception("Log in to the Jellyfin extension first")
        return url.newBuilder().addQueryParameter("api_key", key).build()
    }
}
