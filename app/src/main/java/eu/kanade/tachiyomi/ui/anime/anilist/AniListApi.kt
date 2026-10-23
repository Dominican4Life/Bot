package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: The AniList side of anime global search (the globe tab), like Shiru and Hayase: search and details from
 * AniList, episode titles, pictures, summaries and ★ ratings from ani.zip (AniDB), filler marks from tenrai
 * (MyAnimeList), openings and endings from AnimeThemes and screenshot search with trace.moe.
 */
object AniListApi {

    private const val ANILIST_URL = "https://graphql.anilist.co"
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient get() = Injekt.get<NetworkHelper>().client

    private val detailsCache = ConcurrentHashMap<Int, AniListDetails>()
    private val episodesCache = ConcurrentHashMap<Int, List<AniListEpisode>>()

    // ============================== AniList ==============================

    /** Runs a GraphQL query, waiting and trying again when AniList says to slow down (about 30 a minute) */
    private suspend fun query(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject =
        withIOContext {
            val body = buildJsonObject {
                put("query", query)
                put("variables", variables)
            }.toString()
            var attempt = 0
            while (true) {
                val response = client.newCall(POST(ANILIST_URL, body = body.toRequestBody(jsonMime))).await()
                if (response.code == 429 && attempt < 2) {
                    val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 10L
                    response.close()
                    attempt++
                    delay(wait * 1000)
                    continue
                }
                val text = response.body.string()
                val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: throw Exception("AniList answered ${response.code}")
                root.alArr("errors")?.firstOrNull()?.let { error ->
                    throw Exception(error.alStr("message") ?: "AniList error")
                }
                if (!response.isSuccessful) throw Exception("AniList answered ${response.code}")
                return@withIOContext root.alObj("data") ?: JsonObject(emptyMap())
            }
            @Suppress("UNREACHABLE_CODE")
            JsonObject(emptyMap())
        }

    private const val CARD_FIELDS = """
        id idMal type title { romaji english native userPreferred } coverImage { extraLarge large color } bannerImage
        format status episodes chapters volumes duration season seasonYear averageScore genres description(asHtml: false) isAdult
        nextAiringEpisode { episode airingAt } studios(isMain: true) { nodes { name } }
    """

    private val SEARCH_QUERY = """
        query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}genre_in: [String],
            ${'$'}genre_not_in: [String], ${'$'}tag_in: [String], ${'$'}tag_not_in: [String],
            ${'$'}format_in: [MediaFormat], ${'$'}status_in: [MediaStatus], ${'$'}season: MediaSeason,
            ${'$'}seasonYear: Int, ${'$'}sort: [MediaSort], ${'$'}id_in: [Int], ${'$'}type: MediaType,
            ${'$'}startDate_like: String) {
          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            pageInfo { hasNextPage }
            media(type: ${'$'}type, search: ${'$'}search, startDate_like: ${'$'}startDate_like, genre_in: ${'$'}genre_in, genre_not_in: ${'$'}genre_not_in,
                tag_in: ${'$'}tag_in, tag_not_in: ${'$'}tag_not_in, format_in: ${'$'}format_in,
                status_in: ${'$'}status_in, season: ${'$'}season, seasonYear: ${'$'}seasonYear, sort: ${'$'}sort,
                id_in: ${'$'}id_in) {
              $CARD_FIELDS
            }
          }
        }
    """.trimIndent()

    /** One page of search results. Adult titles are always included. */
    suspend fun search(
        filters: AniListFilters,
        query: String,
        page: Int,
        idIn: List<Int>? = null,
        manga: Boolean = false,
    ): AniListPage {
        val variables = buildJsonObject {
            put("type", if (manga) "MANGA" else "ANIME")
            put("page", page)
            put("perPage", 50)
            if (query.isNotBlank()) put("search", query)
            if (filters.genresIncluded.isNotEmpty()) put("genre_in", filters.genresIncluded.toJsonArray())
            if (filters.genresExcluded.isNotEmpty()) put("genre_not_in", filters.genresExcluded.toJsonArray())
            if (filters.tagsIncluded.isNotEmpty()) put("tag_in", filters.tagsIncluded.toJsonArray())
            if (filters.tagsExcluded.isNotEmpty()) put("tag_not_in", filters.tagsExcluded.toJsonArray())
            if (filters.formats.isNotEmpty()) put("format_in", filters.formats.toJsonArray())
            if (filters.statuses.isNotEmpty()) put("status_in", filters.statuses.toJsonArray())
            if (manga) {
                // Manga have no seasons: the year they started
                filters.year?.let { put("startDate_like", "$it%") }
            } else {
                filters.season?.let { put("season", it) }
                filters.year?.let { put("seasonYear", it) }
            }
            if (idIn != null) put("id_in", JsonArray(idIn.map { JsonPrimitive(it) }))
            put("sort", filters.sortValues(hasQuery = query.isNotBlank(), hasIds = idIn != null).toJsonArray())
        }
        val data = query(SEARCH_QUERY, variables)
        val pageObj = data.alObj("Page")
        val media = pageObj?.alArr("media").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseCard) }
        val hasNext = pageObj?.alObj("pageInfo")?.alBool("hasNextPage") == true
        return AniListPage(media, hasNext)
    }

    /** Every genre and tag AniList has, for the # list */
    suspend fun genresAndTags(): Pair<List<String>, List<AniListTag>> {
        val data = query("{ GenreCollection MediaTagCollection { name category isAdult } }")
        val genres = data.alArr("GenreCollection").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val tags = data.alArr("MediaTagCollection").orEmpty().mapNotNull { element ->
            val tag = element as? JsonObject ?: return@mapNotNull null
            AniListTag(
                name = tag.alStr("name") ?: return@mapNotNull null,
                category = tag.alStr("category").orEmpty(),
                isAdult = tag.alBool("isAdult") == true,
            )
        }
        return genres to tags
    }

    private const val RELATION_NODE = "id type format status episodes title { userPreferred romaji english } " +
        "coverImage { large }"

    private val DETAILS_QUERY = """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id) {
            $CARD_FIELDS
            meanScore popularity favourites source countryOfOrigin synonyms hashtag
            startDate { year month day } endDate { year month day }
            nextAiringEpisode { episode airingAt timeUntilAiring }
            trailer { id site thumbnail }
            tags { name rank isMediaSpoiler }
            streamingEpisodes { title thumbnail url site }
            relations { edges { relationType(version: 2) node { $RELATION_NODE } } }
            recommendations(perPage: 25, sort: RATING_DESC) {
              nodes { rating mediaRecommendation { $CARD_FIELDS } }
            }
            stats { scoreDistribution { score amount } }
          }
        }
    """.trimIndent()

    suspend fun details(id: Int, refresh: Boolean = false): AniListDetails {
        if (!refresh) detailsCache[id]?.let { return it }
        val media = query(DETAILS_QUERY, buildJsonObject { put("id", id) }).alObj("Media")
            ?: throw Exception("AniList has no anime $id")
        val card = parseCard(media) ?: throw Exception("AniList has no anime $id")
        val details = AniListDetails(
            card = card,
            meanScore = media.alInt("meanScore"),
            popularity = media.alInt("popularity"),
            favourites = media.alInt("favourites"),
            source = media.alStr("source"),
            synonyms = media.alArr("synonyms").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            startDate = media.alObj("startDate")?.let(::parseDate),
            endDate = media.alObj("endDate")?.let(::parseDate),
            trailer = media.alObj("trailer")?.let { trailer ->
                val trailerId = trailer.alStr("id") ?: return@let null
                AniListTrailer(trailerId, trailer.alStr("site").orEmpty(), trailer.alStr("thumbnail"))
            },
            tags = media.alArr("tags").orEmpty().mapNotNull { element ->
                val tag = element as? JsonObject ?: return@mapNotNull null
                AniListMediaTag(
                    name = tag.alStr("name") ?: return@mapNotNull null,
                    rank = tag.alInt("rank") ?: 0,
                    spoiler = tag.alBool("isMediaSpoiler") == true,
                )
            },
            streamingEpisodes = media.alArr("streamingEpisodes").orEmpty().mapNotNull { element ->
                val episode = element as? JsonObject ?: return@mapNotNull null
                AniListStreamingEpisode(
                    title = episode.alStr("title").orEmpty(),
                    thumbnail = episode.alStr("thumbnail"),
                )
            },
            relations = media.alObj("relations")?.alArr("edges").orEmpty().mapNotNull { element ->
                val edge = element as? JsonObject ?: return@mapNotNull null
                val node = edge.alObj("node") ?: return@mapNotNull null
                AniListRelation(
                    type = edge.alStr("relationType").orEmpty(),
                    node = parseRelationNode(node) ?: return@mapNotNull null,
                )
            },
            recommendations = media.alObj("recommendations")?.alArr("nodes").orEmpty().mapNotNull { element ->
                ((element as? JsonObject)?.alObj("mediaRecommendation"))?.let(::parseCard)
            },
            reviews = media.alObj("stats")?.alArr("scoreDistribution").orEmpty().sumOf {
                (it as? JsonObject)?.alInt("amount") ?: 0
            },
        )
        detailsCache[id] = details
        return details
    }

    private val RELATIONS_QUERY = """
        query (${'$'}ids: [Int]) {
          Page(perPage: 50) {
            media(id_in: ${'$'}ids) {
              $RELATION_NODE
              relations { edges { relationType(version: 2) node { $RELATION_NODE } } }
            }
          }
        }
    """.trimIndent()

    /**
     * The whole franchise around an anime, like Hayase's relations diagram: every anime linked to it, then the ones
     * linked to those, until nothing new turns up (or about a hundred are found).
     */
    suspend fun relationsGraph(details: AniListDetails, onUpdate: (AniListGraph) -> Unit = {}): AniListGraph {
        val nodes = LinkedHashMap<Int, AniListRelationNode>()
        val edges = LinkedHashMap<String, AniListGraphEdge>()
        val pending = LinkedHashSet<Int>()
        val crossovers = HashSet<Int>()

        // Anime pages link anime, manga pages link manga
        val mediaType = details.card.type ?: "ANIME"

        fun process(node: AniListRelationNode, relations: List<AniListRelation>, depth: Int) {
            if (node.type.isNotEmpty() && node.type != mediaType) return
            nodes.putIfAbsent(node.id, node)
            for (relation in relations) {
                val other = relation.node
                if (other.type != mediaType || relation.type == "CHARACTER") continue
                val key = listOf(node.id, other.id).sorted().joinToString("-")
                val existing = edges[key]
                // "Parent" is very broad: a more exact link wins
                if (existing != null && existing.label != "PARENT") continue
                val prequel = relation.type == "PREQUEL"
                edges[key] = AniListGraphEdge(
                    from = if (prequel) other.id else node.id,
                    to = if (prequel) node.id else other.id,
                    label = if (prequel) "SEQUEL" else relation.type,
                )
                if (other.id !in nodes) {
                    nodes[other.id] = other
                    // A crossover ("Other": e.g. a Dragon Ball × One Piece special) shows, but its own links aren't
                    // followed, or another whole franchise would come in
                    if (relation.type == "OTHER") crossovers += other.id
                    if (depth >= 1 && other.id !in crossovers) pending += other.id
                }
            }
        }

        process(details.asRelationNode(), details.relations, 0)
        // The direct relations' own relations come from the search below
        pending += details.relations.map { it.node.id }
            .filter { nodes[it]?.type == mediaType && it !in crossovers }
        onUpdate(AniListGraph(nodes.values.toList(), edges.values.toList()))
        var rounds = 0
        val done = HashSet<Int>().apply { add(details.card.id) }
        while (pending.isNotEmpty() && rounds < 12 && nodes.size < 200) {
            val ids = pending.filter { it !in done }.take(50)
            pending.removeAll(ids.toSet())
            if (ids.isEmpty()) break
            done += ids
            val data = runCatching { query(RELATIONS_QUERY, buildJsonObject { put("ids", ids.toIntJsonArray()) }) }
                .getOrNull() ?: break
            data.alObj("Page")?.alArr("media").orEmpty().forEach { element ->
                val media = element as? JsonObject ?: return@forEach
                val node = parseRelationNode(media) ?: return@forEach
                val relations = media.alObj("relations")?.alArr("edges").orEmpty().mapNotNull { edgeElement ->
                    val edge = edgeElement as? JsonObject ?: return@mapNotNull null
                    AniListRelation(
                        type = edge.alStr("relationType").orEmpty(),
                        node = edge.alObj("node")?.let(::parseRelationNode) ?: return@mapNotNull null,
                    )
                }
                process(node, relations, 1)
            }
            pending.removeAll(done)
            onUpdate(AniListGraph(nodes.values.toList(), edges.values.toList()))
            rounds++
        }
        return AniListGraph(nodes.values.toList(), edges.values.toList())
    }

    // ============================== Episodes ==============================

    /**
     * The episode list like Shiru: ani.zip (titles, pictures, summaries, ★ from AniDB, minutes), filler marks from
     * tenrai and AniList's own episode list when ani.zip has nothing.
     */
    suspend fun episodes(details: AniListDetails, refresh: Boolean = false): List<AniListEpisode> = withIOContext {
        val card = details.card
        if (!refresh) episodesCache[card.id]?.let { return@withIOContext it }
        val aniZip = runCatching { aniZip(card.id, card.idMal) }.getOrNull()
        val zipEpisodes = aniZip?.alObj("episodes")
        val malId = card.idMal ?: aniZip?.alObj("mappings")?.alInt("mal_id")
        val fillers = malId?.let { runCatching { fillerEpisodes(it) }.getOrNull() }.orEmpty()

        val count = card.episodes
            ?: card.nextAiring?.episode?.minus(1)?.takeIf { it > 0 }
            ?: aniZip?.alInt("episodeCount")
            ?: details.streamingEpisodes.size.takeIf { it > 0 }
            ?: zipEpisodes?.keys?.mapNotNull { it.toIntOrNull() }?.maxOrNull()
            ?: 0

        val streaming = details.streamingEpisodes.associateBy { streamingNumber(it.title) }
        val list = (1..count).map { number ->
            val zip = zipEpisodes?.alObj(number.toString())
            val titles = zip?.alObj("title")
            val stream = streaming[number]
            AniListEpisode(
                number = number,
                title = titles?.alStr("en")?.takeIf { it.isNotBlank() }
                    ?: titles?.alStr("x-jat")?.takeIf { it.isNotBlank() }
                    ?: stream?.title?.substringAfter(" - ", "")?.takeIf { it.isNotBlank() },
                image = zip?.alStr("image") ?: stream?.thumbnail,
                summary = (zip?.alStr("summary") ?: zip?.alStr("overview"))?.takeIf { it.isNotBlank() },
                rating = zip?.alStr("rating") ?: zip?.alDouble("rating")?.toString(),
                minutes = zip?.alInt("length") ?: zip?.alInt("runtime") ?: card.duration,
                airDate = zip?.alStr("airdate") ?: zip?.alStr("airDate"),
                filler = number in fillers,
            )
        }
        episodesCache[card.id] = list
        list
    }

    /** "Episode 12 - Title" → 12 */
    private fun streamingNumber(title: String): Int? =
        Regex("""(?i)episode\s+(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun aniZip(anilistId: Int, malId: Int?): JsonObject? {
        var response = client.newCall(GET("https://api.ani.zip/v1/episodes?anilist_id=$anilistId")).await()
        if (response.code == 404 && malId != null) {
            response.close()
            response = client.newCall(GET("https://api.ani.zip/v1/episodes?mal_id=$malId")).await()
        }
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return json.parseToJsonElement(response.body.string()) as? JsonObject
    }

    /** Filler episode numbers from tenrai (MyAnimeList data), 100 a page */
    private suspend fun fillerEpisodes(malId: Int): Set<Int> {
        val fillers = HashSet<Int>()
        var page = 1
        while (page <= 20) {
            val response = client.newCall(GET("https://api.tenrai.org/v1/anime/$malId/episodes?page=$page")).await()
            if (!response.isSuccessful) {
                response.close()
                break
            }
            val root = json.parseToJsonElement(response.body.string()) as? JsonObject ?: break
            root.alArr("data").orEmpty().forEach { element ->
                val episode = element as? JsonObject ?: return@forEach
                if (episode.alBool("filler") == true) episode.alInt("mal_id")?.let(fillers::add)
            }
            if (root.alObj("pagination")?.alBool("has_next_page") != true) break
            page++
        }
        return fillers
    }

    /** Shiru's fallback: the YouTube trailer MyAnimeList has (through tenrai) when AniList has none */
    suspend fun malTrailer(malId: Int): String? = withIOContext {
        runCatching {
            val response = client.newCall(GET("https://api.tenrai.org/v1/anime/$malId")).await()
            if (!response.isSuccessful) {
                response.close()
                return@runCatching null
            }
            val root = json.parseToJsonElement(response.body.string()) as? JsonObject
            val trailer = root?.alObj("data")?.alObj("trailer")
            trailer?.alStr("youtube_id")
                ?: trailer?.alStr("embed_url")?.let { YOUTUBE_ID.find(it)?.groupValues?.get(1) }
        }.getOrNull()
    }

    private val YOUTUBE_ID = Regex("embed/([-_0-9A-Za-z]{10,15})")

    // ============================== Themes ==============================

    /** Openings and endings from AnimeThemes, with a video for each version */
    suspend fun themes(anilistId: Int): List<AniListTheme> = withIOContext {
        val url = "https://api.animethemes.moe/anime/?fields%5Baudio%5D=id,basename,link,size" +
            "&fields%5Bvideo%5D=id,basename,link,tags&filter%5Bexternal_id%5D=$anilistId&filter%5Bhas%5D=resources" +
            "&filter%5Bsite%5D=AniList&include=animethemes.animethemeentries.videos,animethemes.song," +
            "animethemes.song.artists"
        val response = client.newCall(GET(url)).await()
        if (!response.isSuccessful) {
            response.close()
            return@withIOContext emptyList()
        }
        val root = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return@withIOContext emptyList()
        val anime = root.alArr("anime")?.firstOrNull() as? JsonObject ?: return@withIOContext emptyList()
        anime.alArr("animethemes").orEmpty().mapNotNull { element ->
            val theme = element as? JsonObject ?: return@mapNotNull null
            val song = theme.alObj("song")
            AniListTheme(
                id = theme.alLong("id") ?: 0L,
                type = theme.alStr("slug") ?: theme.alStr("type").orEmpty(),
                title = song?.alStr("title").orEmpty(),
                artists = song?.alArr("artists").orEmpty().mapNotNull { (it as? JsonObject)?.alStr("name") },
                entries = theme.alArr("animethemeentries").orEmpty().mapNotNull { entryElement ->
                    val entry = entryElement as? JsonObject ?: return@mapNotNull null
                    AniListThemeEntry(
                        version = entry.alInt("version") ?: 1,
                        episodes = entry.alStr("episodes").orEmpty(),
                        videoUrl = entry.alArr("videos")?.lastOrNull()?.let { (it as? JsonObject)?.alStr("link") },
                    )
                },
            )
        }
    }

    // ============================== trace.moe ==============================

    /** Finds the anime (and the episode and moment) a screenshot comes from */
    suspend fun traceMoe(image: ByteArray, mime: String?): List<TraceMoeMatch> = withIOContext {
        val body = image.toRequestBody((mime ?: "image/jpeg").toMediaType())
        val response = client.newCall(POST("https://api.trace.moe/search?cutBorders&anilistInfo", body = body)).await()
        val text = response.body.string()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw Exception("trace.moe answered ${response.code}")
        root.alStr("error")?.takeIf { it.isNotBlank() }?.let { throw Exception(it) }
        root.alArr("result").orEmpty().mapNotNull { element ->
            val result = element as? JsonObject ?: return@mapNotNull null
            val anilist = result["anilist"]
            val id = (anilist as? JsonObject)?.alInt("id") ?: (anilist as? JsonPrimitive)?.intOrNull
                ?: return@mapNotNull null
            val titles = (anilist as? JsonObject)?.alObj("title")
            TraceMoeMatch(
                anilistId = id,
                title = titles?.alStr("english") ?: titles?.alStr("romaji") ?: titles?.alStr("native"),
                episode = result["episode"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                from = result.alDouble("from") ?: 0.0,
                similarity = result.alDouble("similarity") ?: 0.0,
                image = result.alStr("image"),
            )
        }
    }

    // ============================== Parsing ==============================

    private fun parseCard(media: JsonObject): AniListMedia? {
        val id = media.alInt("id") ?: return null
        val title = media.alObj("title")
        val cover = media.alObj("coverImage")
        return AniListMedia(
            id = id,
            idMal = media.alInt("idMal"),
            type = media.alStr("type"),
            chapters = media.alInt("chapters"),
            volumes = media.alInt("volumes"),
            romaji = title?.alStr("romaji"),
            english = title?.alStr("english"),
            native = title?.alStr("native"),
            userPreferred = title?.alStr("userPreferred"),
            cover = cover?.alStr("extraLarge") ?: cover?.alStr("large"),
            coverColor = cover?.alStr("color"),
            banner = media.alStr("bannerImage"),
            format = media.alStr("format"),
            status = media.alStr("status"),
            episodes = media.alInt("episodes"),
            duration = media.alInt("duration"),
            season = media.alStr("season"),
            seasonYear = media.alInt("seasonYear"),
            averageScore = media.alInt("averageScore"),
            genres = media.alArr("genres").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            description = media.alStr("description")?.let(::cleanDescription),
            isAdult = media.alBool("isAdult") == true,
            nextAiring = media.alObj("nextAiringEpisode")?.let { next ->
                AniListAiring(next.alInt("episode") ?: 0, next.alLong("airingAt") ?: 0L)
            },
            studios = media.alObj("studios")?.alArr("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.alStr("name") },
        )
    }

    private fun parseRelationNode(node: JsonObject): AniListRelationNode? {
        val title = node.alObj("title")
        return AniListRelationNode(
            id = node.alInt("id") ?: return null,
            type = node.alStr("type").orEmpty(),
            format = node.alStr("format"),
            status = node.alStr("status"),
            episodes = node.alInt("episodes"),
            title = title?.alStr("userPreferred") ?: title?.alStr("english") ?: title?.alStr("romaji") ?: "TBA",
            cover = node.alObj("coverImage")?.alStr("large"),
        )
    }

    private fun parseDate(date: JsonObject): String? {
        val year = date.alInt("year") ?: return null
        val month = date.alInt("month")
        val day = date.alInt("day")
        return buildString {
            append(year)
            if (month != null) append("-").append(month.toString().padStart(2, '0'))
            if (month != null && day != null) append("-").append(day.toString().padStart(2, '0'))
        }
    }

    /** AniList descriptions keep a few HTML tags even as plain text */
    fun cleanDescription(text: String): String = text
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun Collection<String>.toJsonArray() = JsonArray(map { JsonPrimitive(it) })
    private fun List<Int>.toIntJsonArray() = JsonArray(map { JsonPrimitive(it) })
}

// ============================== JSON helpers ==============================

internal fun JsonElement?.alObj(key: String): JsonObject? = ((this as? JsonObject)?.get(key) as? JsonObject)
internal fun JsonElement?.alArr(key: String): JsonArray? = ((this as? JsonObject)?.get(key) as? JsonArray)
internal fun JsonElement?.alStr(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonElement?.alInt(key: String): Int? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonElement?.alLong(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonElement?.alDouble(key: String): Double? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement?.alBool(key: String): Boolean? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull

// ============================== Models ==============================

@Immutable
data class AniListMedia(
    val id: Int,
    val idMal: Int?,
    val type: String?,
    val chapters: Int?,
    val volumes: Int?,
    val romaji: String?,
    val english: String?,
    val native: String?,
    val userPreferred: String?,
    val cover: String?,
    val coverColor: String?,
    val banner: String?,
    val format: String?,
    val status: String?,
    val episodes: Int?,
    val duration: Int?,
    val season: String?,
    val seasonYear: Int?,
    val averageScore: Int?,
    val genres: List<String>,
    val description: String?,
    val isAdult: Boolean,
    val nextAiring: AniListAiring?,
    val studios: List<String>,
) {
    /** The main title: English when there is one, like Shiru */
    val title: String get() = english?.takeIf { it.isNotBlank() } ?: userPreferred ?: romaji ?: native ?: "?"
}

@Immutable
data class AniListAiring(val episode: Int, val airingAt: Long)

@Immutable
data class AniListPage(val media: List<AniListMedia>, val hasNextPage: Boolean)

@Immutable
data class AniListTag(val name: String, val category: String, val isAdult: Boolean)

@Immutable
data class AniListMediaTag(val name: String, val rank: Int, val spoiler: Boolean)

@Immutable
data class AniListTrailer(val id: String, val site: String, val thumbnail: String?) {
    val url: String
        get() = when (site.lowercase()) {
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> "https://www.youtube.com/watch?v=$id"
        }
}

@Immutable
data class AniListStreamingEpisode(val title: String, val thumbnail: String?)

@Immutable
data class AniListRelationNode(
    val id: Int,
    val type: String,
    val format: String?,
    val status: String?,
    val episodes: Int?,
    val title: String,
    val cover: String?,
)

@Immutable
data class AniListRelation(val type: String, val node: AniListRelationNode)

@Immutable
data class AniListGraphEdge(val from: Int, val to: Int, val label: String)

@Immutable
data class AniListGraph(val nodes: List<AniListRelationNode>, val edges: List<AniListGraphEdge>)

@Immutable
data class AniListDetails(
    val card: AniListMedia,
    val meanScore: Int?,
    val popularity: Int?,
    val favourites: Int?,
    val source: String?,
    val synonyms: List<String>,
    val startDate: String?,
    val endDate: String?,
    val trailer: AniListTrailer?,
    val tags: List<AniListMediaTag>,
    val streamingEpisodes: List<AniListStreamingEpisode>,
    val relations: List<AniListRelation>,
    val recommendations: List<AniListMedia>,
    /** Everyone who scored it, like Shiru's "Reviews" */
    val reviews: Int,
) {
    fun asRelationNode() = AniListRelationNode(
        id = card.id,
        type = card.type ?: "ANIME",
        format = card.format,
        status = card.status,
        episodes = card.episodes,
        title = card.userPreferred ?: card.title,
        cover = card.cover,
    )
}

@Immutable
data class AniListEpisode(
    val number: Int,
    val title: String?,
    val image: String?,
    val summary: String?,
    val rating: String?,
    val minutes: Int?,
    val airDate: String?,
    val filler: Boolean,
)

@Immutable
data class AniListThemeEntry(val version: Int, val episodes: String, val videoUrl: String?)

@Immutable
data class AniListTheme(
    val id: Long,
    val type: String,
    val title: String,
    val artists: List<String>,
    val entries: List<AniListThemeEntry>,
)

@Immutable
data class TraceMoeMatch(
    val anilistId: Int,
    val title: String?,
    val episode: String?,
    val from: Double,
    val similarity: Double,
    val image: String?,
)

/** Human words for AniList's format, status and season values */
object AniListLabels {
    fun format(value: String?): String = when (value) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV Short"
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> "Music"
        null -> ""
        else -> value.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    fun status(value: String?): String = when (value) {
        "RELEASING" -> "Releasing"
        "FINISHED" -> "Finished"
        "NOT_YET_RELEASED" -> "Not yet released"
        "CANCELLED" -> "Cancelled"
        "HIATUS" -> "Hiatus"
        null -> ""
        else -> value.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    fun season(value: String?): String = value?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()

    fun relation(value: String): String = value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
