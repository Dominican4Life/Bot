package eu.kanade.tachiyomi.data.anime

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** KMK: when the next episode of an anime airs (from AniList, like Anikku's AniChartApi) */
@Immutable
data class AnimeAiring(
    /** The episode that airs next */
    val episode: Int,
    /** When it airs, in epoch milliseconds */
    val airingAt: Long,
)

/**
 * KMK: Next-episode airing times from AniList's public GraphQL API (no login needed).
 *
 * An anime is looked up by its AniList track, else its MyAnimeList track (AniList knows MAL ids). Anime with no
 * track are only searched by title when their page is opened ([forAnime]), never from the library. Results are
 * kept in memory and in a small preference for [TTL], and asked for again once the episode has aired.
 */
object AnimeAiringRepository {

    private const val ANILIST_URL = "https://graphql.anilist.co/"
    private const val TTL = 6L * 60 * 60 * 1000
    private const val MIN_REFETCH = 10L * 60 * 1000
    private const val KEEP = 3L * 24 * 60 * 60 * 1000
    private const val CHUNK = 50

    // MyAnimeList's tracker id (TrackerManager.myAnimeList)
    private const val MYANIMELIST = 1L

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var loaded = false

    private val pref by lazy { Injekt.get<PreferenceStore>().getString("anime_airing_cache", "") }

    @Serializable
    private data class CacheEntry(
        @SerialName("e") val episode: Int? = null,
        @SerialName("a") val airingAt: Long? = null,
        @SerialName("t") val fetchedAt: Long = 0,
        /** The AniList id a title search found */
        @SerialName("i") val anilistId: Long? = null,
    ) {
        fun airing(): AnimeAiring? = if (episode != null && airingAt != null) AnimeAiring(episode, airingAt) else null

        fun isFresh(now: Long): Boolean {
            val age = now - fetchedAt
            if (age < 0 || age > TTL) return false
            // Once the episode has aired, ask again for the one after (not more than every few minutes)
            return airingAt == null || airingAt > now || age < MIN_REFETCH
        }
    }

    /** What's cached for [animeId] right now, without asking AniList */
    fun cached(animeId: Long, tracks: List<AnimeTrack>): AnimeAiring? {
        ensureLoaded()
        val key = keyFor(tracks) ?: titleKey(animeId)
        return cache[key]?.airing()?.takeIf { it.airingAt > System.currentTimeMillis() }
    }

    /**
     * The next episode of [anime], or null when nothing is airing or AniList doesn't know it.
     * With [searchByTitle], an anime with no AniList / MyAnimeList track is searched by its title.
     */
    suspend fun forAnime(anime: Anime, searchByTitle: Boolean = true, force: Boolean = false): AnimeAiring? =
        withIOContext {
            val tracks = runCatching { Injekt.get<AnimeTrackRepository>().getTracks(anime.id) }.getOrDefault(emptyList())
            mutex.withLock {
                ensureLoaded()
                val now = System.currentTimeMillis()
                val key = keyFor(tracks)
                if (key != null) {
                    val entry = cache[key]
                    if (!force && entry != null && entry.isFresh(now)) return@withLock entry.airing()
                    fetch(listOf(key))
                    return@withLock cache[key]?.airing()
                }
                if (!searchByTitle) return@withLock null
                val titleKey = titleKey(anime.id)
                val entry = cache[titleKey]
                if (!force && entry != null && entry.isFresh(now)) return@withLock entry.airing()
                // A title already matched once: look it up by id from now on
                val found = entry?.anilistId?.let { id ->
                    fetch(listOf("al:$id"))
                    cache["al:$id"]?.copy(anilistId = id)
                } ?: searchTitle(anime.title)
                if (found != null) {
                    cache[titleKey] = found
                    save()
                }
                found?.airing()
            }
        }?.takeIf { it.airingAt > System.currentTimeMillis() }

    /**
     * The next episodes of the [animes] that have an AniList or MyAnimeList track (asked in batches), plus any
     * found earlier by title on their page. Anime with nothing airing are left out.
     */
    suspend fun forLibrary(animes: List<Anime>): Map<Long, AnimeAiring> = withIOContext {
        if (animes.isEmpty()) return@withIOContext emptyMap()
        val tracks = runCatching { Injekt.get<AnimeTrackRepository>().getAll().groupBy { it.animeId } }
            .getOrDefault(emptyMap())
        mutex.withLock {
            ensureLoaded()
            val now = System.currentTimeMillis()
            val keys = animes.associate { anime -> anime.id to (keyFor(tracks[anime.id].orEmpty()) ?: titleKey(anime.id)) }
            val stale = keys.values.distinct()
                .filter { !it.startsWith("title:") && cache[it]?.isFresh(now) != true }
            if (stale.isNotEmpty()) fetch(stale)
            keys.mapNotNull { (animeId, key) ->
                cache[key]?.airing()?.takeIf { it.airingAt > now }?.let { animeId to it }
            }.toMap()
        }
    }

    private fun keyFor(tracks: List<AnimeTrack>): String? =
        tracks.firstOrNull { it.trackerId == TrackerManager.ANILIST && it.remoteId > 0 }?.let { "al:${it.remoteId}" }
            ?: tracks.firstOrNull { it.trackerId == MYANIMELIST && it.remoteId > 0 }?.let { "mal:${it.remoteId}" }

    private fun titleKey(animeId: Long) = "title:$animeId"

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val text = pref.get()
            if (text.isNotBlank()) cache.putAll(json.decodeFromString<Map<String, CacheEntry>>(text))
        }
    }

    private fun save() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.fetchedAt > KEEP && !it.key.startsWith("title:") }
        runCatching { pref.set(json.encodeToString<Map<String, CacheEntry>>(HashMap(cache))) }
    }

    /** Asks AniList for the "al:<id>" / "mal:<id>" [keys], 50 at a time, and caches what it answers */
    private suspend fun fetch(keys: List<String>) {
        val anilistIds = keys.filter { it.startsWith("al:") }.mapNotNull { it.substringAfter(':').toLongOrNull() }
        val malIds = keys.filter { it.startsWith("mal:") }.mapNotNull { it.substringAfter(':').toLongOrNull() }
        var changed = false
        suspend fun ask(ids: List<Long>, byMal: Boolean) {
            ids.chunked(CHUNK).forEach { chunk ->
                val filter = if (byMal) "idMal_in" else "id_in"
                val query = "query { Page(perPage: $CHUNK) { media($filter: ${chunk.joinToString(",", "[", "]")}, " +
                    "type: ANIME) { id idMal nextAiringEpisode { episode airingAt } } } }"
                val data = post(query) ?: return@forEach
                val now = System.currentTimeMillis()
                val media = data.obj("Page")?.arr("media").orEmpty()
                val answered = HashSet<Long>()
                media.forEach { item ->
                    val id = (if (byMal) item.long("idMal") else item.long("id")) ?: return@forEach
                    answered += id
                    cache[(if (byMal) "mal:" else "al:") + id] = item.toEntry(now)
                }
                // Not on AniList (or nothing airing): remember that too, so it isn't asked again for a while
                chunk.filter { it !in answered }.forEach { id ->
                    cache[(if (byMal) "mal:" else "al:") + id] = CacheEntry(fetchedAt = now)
                }
                changed = true
            }
        }
        ask(anilistIds, byMal = false)
        ask(malIds, byMal = true)
        if (changed) save()
    }

    /** The airing anime whose title matches [title] exactly (ignoring case and punctuation) */
    private suspend fun searchTitle(title: String): CacheEntry? {
        val wanted = title.normalized().takeIf { it.isNotEmpty() } ?: return null
        val query = """
            query (${'$'}q: String) {
              Page(perPage: 10) {
                media(search: ${'$'}q, type: ANIME, status_in: [RELEASING, NOT_YET_RELEASED], sort: SEARCH_MATCH) {
                  id title { romaji english native userPreferred } synonyms nextAiringEpisode { episode airingAt }
                }
              }
            }
        """.trimIndent()
        val data = post(query, buildJsonObject { put("q", title) }) ?: return null
        val now = System.currentTimeMillis()
        val match = data.obj("Page")?.arr("media").orEmpty().firstOrNull { item ->
            val titles = item.obj("title")
            val names = listOf("romaji", "english", "native", "userPreferred").mapNotNull { titles?.str(it) } +
                item.arr("synonyms").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            names.any { it.normalized() == wanted }
        }
        // No match is remembered as an empty entry, so the page doesn't search again for a while
        return match?.toEntry(now)?.copy(anilistId = match.long("id")) ?: CacheEntry(fetchedAt = now)
    }

    private fun JsonElement.toEntry(now: Long): CacheEntry {
        val next = obj("nextAiringEpisode")
        return CacheEntry(
            episode = next?.long("episode")?.toInt(),
            // AniList gives seconds
            airingAt = next?.long("airingAt")?.let { it * 1000 },
            fetchedAt = now,
        )
    }

    private suspend fun post(query: String, variables: JsonObject? = null): JsonObject? = try {
        val body = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }.toString().toRequestBody(jsonMime)
        val client = Injekt.get<NetworkHelper>().client
        client.newCall(POST(ANILIST_URL, body = body)).await().use { response ->
            if (!response.isSuccessful) {
                logcat(LogPriority.WARN) { "AniList airing: HTTP ${response.code}" }
                null
            } else {
                (json.parseToJsonElement(response.body.string()) as? JsonObject)?.obj("data")
            }
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "AniList airing failed" }
        null
    }

    private fun String.normalized() = lowercase().filter { it.isLetterOrDigit() }

    private fun JsonElement?.obj(key: String): JsonObject? = ((this as? JsonObject)?.get(key) as? JsonObject)
    private fun JsonElement?.arr(key: String): JsonArray? = ((this as? JsonObject)?.get(key) as? JsonArray)
    private fun JsonElement?.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonElement?.long(key: String): Long? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull
}
