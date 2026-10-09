package eu.kanade.tachiyomi.sumizuri

import android.app.Application
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A Sumizuri JS / JSON anime source: browse, search with filters and catalogs, details, episodes (with their
 * thumbnails, summaries and seasons) and videos (headers, subtitles, dubs, intro / outro) from the source's code.
 */
class SumizuriAnimeSource(
    val info: SumizuriSourceInfo,
    private val manager: SumizuriManager,
) : AnimeCatalogueSource, ConfigurableAnimeSource {

    override val name: String = info.name
    override val lang: String = info.displayLang
    override val id: Long = info.id
    override val supportsLatest: Boolean = true

    // Related titles by searching each word of the title would fire a search per word at the site at once,
    // which sites answer with 429 (too many requests) and which blocks this source's other calls meanwhile
    override val disableRelatedAnimesBySearch: Boolean = true

    val baseUrl: String = info.baseUrl.trimEnd('/')

    /**
     * Covers and video thumbnails load through this, like an Aniyomi extension's: the app's connection (shared
     * cookies, Cloudflare handling) and the source's own headers, with the source's site as the Referer rather than
     * the image host. A source's `headers` replace these.
     */
    val client: OkHttpClient get() = Injekt.get<NetworkHelper>().client

    val headers: Headers by lazy {
        Headers.Builder().apply {
            set("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider())
            if (baseUrl.isNotBlank()) set("Referer", "$baseUrl/")
            info.headers.forEach { (k, v) -> runCatching { set(k, v) } }
        }.build()
    }

    @Volatile
    private var described = false

    private val webUrls by lazy { SumizuriWebUrls(Injekt.get<Application>(), id, baseUrl) }

    /** The title's page on the site, for WebView and sharing */
    fun getAnimeUrl(anime: SAnime): String = webUrls.pageFor(anime.url)

    fun getEpisodeUrl(episode: SEpisode): String = webUrls.pageFor(episode.url)

    private suspend fun runtime(): SumizuriRuntime = manager.runtime(info).also {
        if (!described) {
            described = true
            manager.refreshDescription(info)
        }
    }

    private suspend fun listing(method: String, vararg args: Any?): AnimesPage {
        val animes = SumizuriMapping.list(runtime().call(method, *args))
            .also(webUrls::remember)
            .mapNotNull { SumizuriMapping.toSAnime(it) }
        return AnimesPage(animes, hasNextPage = animes.isNotEmpty())
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = listing("getPopular", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = listing("getLatest", page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val catalog = filters.filterIsInstance<CatalogFilter>().firstOrNull()?.selected()
        if (catalog != null && query.isBlank()) return listing("getCatalog", catalog.id, page)
        return listing("search", query, page, filterValues(filters))
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val fresh = if (info.hasDetails) {
            (runtime().call("getDetails", anime.url) as? JsonObject)?.let {
                webUrls.remember(listOf(JsonObject(it + ("url" to JsonPrimitive(anime.url)))))
                SumizuriMapping.toSAnime(it, anime)
            }
        } else {
            null
        }
        return (fresh ?: anime).apply { initialized = true }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        SumizuriMapping.list(runtime().call("getChapterList", anime.url))
            .also(webUrls::remember)
            .let(SumizuriMapping::toSEpisodes)

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        SumizuriMapping.list(runtime().call("getVideoList", episode.url)).mapNotNull { video ->
            var url = video.str("url")?.trim() ?: return@mapNotNull null
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http")) return@mapNotNull null
            val headers = Headers.Builder().apply {
                if (baseUrl.isNotBlank()) set("Referer", "$baseUrl/")
                info.headers.forEach { (k, v) -> runCatching { set(k, v) } }
                video.obj("headers")?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { runCatching { set(k, it) } } }
            }.build()
            val timestamps = listOfNotNull(
                video.obj("intro")?.let { stamp(it, "Intro", ChapterType.Opening) },
                video.obj("outro")?.let { stamp(it, "Outro", ChapterType.Ending) },
            )
            val quality = video.str("quality").orEmpty()
            val isHls = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || "m3u8" in it }
            Video(
                // HLS goes through the relay, which removes fake picture headers some hosts put on each piece
                videoUrl = if (isHls) HlsRelay.wrap(url, headers) else url,
                videoTitle = quality.ifBlank { "Video" },
                resolution = Regex("""(\d{3,4})p""").find(quality)?.groupValues?.get(1)?.toIntOrNull(),
                headers = headers,
                subtitleTracks = tracks(video.arr("subtitles")),
                audioTracks = tracks(video.arr("audioTracks")),
                timestamps = timestamps,
                initialized = true,
            ).apply {
                val path = url.substringBefore('?').lowercase()
                type = when {
                    path.endsWith(".m3u8") || "m3u8" in path -> VideoType.HLS
                    path.endsWith(".mpd") -> VideoType.DASH
                    else -> VideoType.VIDEO
                }
            }
        }

    private fun stamp(obj: JsonObject, name: String, type: ChapterType): TimeStamp? {
        val start = obj.double("start") ?: return null
        val end = obj.double("end") ?: return null
        return TimeStamp(start, end, name, type)
    }

    private fun tracks(array: JsonArray?): List<Track> = array.orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = obj.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Track(url, obj.str("label") ?: obj.str("language") ?: "Track")
    }

    // Filters and catalogs

    private val groups by lazy { SumizuriFilters.groups(info.filters) }
    private val catalogs by lazy { SumizuriFilters.catalogs(info.catalogs) }

    override fun getFilterList(): AnimeFilterList {
        val filters = mutableListOf<AnimeFilter<*>>()
        if (catalogs.isNotEmpty()) {
            filters += CatalogFilter(catalogs)
            filters += AnimeFilter.Header("A catalog is listed when the search is empty")
            filters += AnimeFilter.Separator()
        }
        groups.forEach { group ->
            filters += when (group.type) {
                SumizuriFilters.Type.Select -> SelectFilter(group)
                SumizuriFilters.Type.MultiSelect -> MultiFilter(group)
                SumizuriFilters.Type.Checkbox -> CheckFilter(group)
            }
        }
        return AnimeFilterList(filters)
    }

    private fun filterValues(filters: AnimeFilterList): Map<String, JsonElement> = buildMap {
        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> filter.group.options.getOrNull(filter.state)?.let { put(filter.group.key, it.value) }
                is MultiFilter -> put(filter.group.key, JsonArray(filter.state.filter { it.state }.map { it.option.value }))
                is CheckFilter -> put(filter.group.key, JsonPrimitive(filter.state))
                else -> Unit
            }
        }
    }

    private class CatalogFilter(val catalogs: List<SumizuriFilters.Catalog>) :
        AnimeFilter.Select<String>("Catalog", (listOf("None") + catalogs.map { it.name }).toTypedArray()) {
        fun selected(): SumizuriFilters.Catalog? = if (state == 0) null else catalogs.getOrNull(state - 1)
    }

    private class SelectFilter(val group: SumizuriFilters.Group) :
        AnimeFilter.Select<String>(
            group.name,
            group.options.map { it.label }.toTypedArray(),
            group.defaultIndex.coerceIn(0, (group.options.size - 1).coerceAtLeast(0)),
        )

    private class OptionBox(val option: SumizuriFilters.Option) : AnimeFilter.CheckBox(option.label)

    private class MultiFilter(val group: SumizuriFilters.Group) :
        AnimeFilter.Group<OptionBox>(group.name, group.options.map { OptionBox(it) })

    private class CheckFilter(val group: SumizuriFilters.Group) : AnimeFilter.CheckBox(group.name, group.defaultChecked)

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SumizuriPreferences.setup(screen, SumizuriPreferences.parse(info.preferences ?: return))
    }

    // RxJava entry points are not used by this source
    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularAnime"))
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

    override fun toString(): String = "$name (${if (info.json) "JSON" else "JS"})"
}
