package eu.kanade.tachiyomi.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers.Companion.toHeaders
import rx.Observable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: A Cloudstream provider as an anime source. Its home page sections are "popular" (one section can be picked
 * in the filters), search is the provider's search, details come from `load`, episodes from the load response
 * (dub / sub kept apart), and videos from `loadLinks` with their subtitles.
 */
class CloudstreamAnimeSource(
    val api: MainAPI,
    /** The installed plugin this provider comes from */
    val pluginName: String,
) : AnimeCatalogueSource {

    override val name: String = api.name
    override val lang: String = api.lang.ifBlank { "all" }.let { if (it == "multi" || it == "mul") "all" else it }
    override val id: Long = generateId("cloudstream/${api.name}", lang)
    override val supportsLatest: Boolean = false

    val baseUrl: String get() = api.mainUrl

    private val loads = ConcurrentHashMap<String, LoadResponse>()

    // Browse

    override suspend fun getPopularAnime(page: Int): AnimesPage = mainPage(page, section = null)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.state?.takeIf { it > 0 }?.minus(1)
        if (query.isBlank()) return mainPage(page, section)
        return withContext(Dispatchers.IO) {
            val result = runCatching { api.search(query, page) }.getOrNull()
            if (result != null) {
                AnimesPage(result.items.map { it.toSAnime() }, result.hasNext)
            } else {
                val list = if (page == 1) api.search(query).orEmpty() else emptyList()
                AnimesPage(list.map { it.toSAnime() }, false)
            }
        }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = mainPage(page, section = null)

    private suspend fun mainPage(page: Int, section: Int?): AnimesPage = withContext(Dispatchers.IO) {
        val sections = api.mainPage
        if (!api.hasMainPage || sections.isEmpty()) return@withContext AnimesPage(emptyList(), false)
        val picked = section?.let { listOfNotNull(sections.getOrNull(it)) } ?: sections
        val responses = coroutineScope {
            picked.map { data ->
                async {
                    runCatching {
                        withTimeoutOrNull(60_000) {
                            api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val items = responses.flatMap { response -> response.items.flatMap { it.list } }
            .distinctBy { it.url }
            .map { it.toSAnime() }
        AnimesPage(items, responses.any { it.hasNext })
    }

    override fun getFilterList(): AnimeFilterList {
        val sections = runCatching { api.mainPage.map { it.name } }.getOrDefault(emptyList())
        return if (sections.isEmpty()) AnimeFilterList() else AnimeFilterList(SectionFilter(sections))
    }

    private class SectionFilter(sections: List<String>) :
        AnimeFilter.Select<String>("Section (with an empty search)", (listOf("All") + sections).toTypedArray())

    // Details and episodes

    private suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        loads[url] ?: (api.load(url) ?: error("Nothing found at $url")).also {
            if (loads.size > 500) loads.clear()
            loads[url] = it
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = load(anime.url)
        return SAnime.create().apply {
            url = anime.url
            title = response.name
            thumbnail_url = response.posterUrl ?: anime.thumbnail_url
            background_url = response.backgroundPosterUrl
            description = buildString {
                response.plot?.let { append(it) }
                response.year?.let { if (isNotEmpty()) append("\n\n"); append("Year: ").append(it) }
            }.ifBlank { null }
            genre = response.tags?.filter { it.isNotBlank() }?.joinToString()
            status = when ((response as? EpisodeResponse)?.showStatus) {
                ShowStatus.Ongoing -> SAnime.ONGOING
                ShowStatus.Completed -> SAnime.COMPLETED
                else -> if (response is MovieLoadResponse) SAnime.COMPLETED else SAnime.UNKNOWN
            }
            author = response.actors?.take(4)?.joinToString { it.actor.name }?.ifBlank { null }
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = load(anime.url)
        return when (response) {
            is MovieLoadResponse -> listOf(
                SEpisode.create().apply {
                    url = response.dataUrl
                    name = response.name
                    episode_number = 1f
                },
            )
            is TvSeriesLoadResponse -> response.episodes.toEpisodes(DubStatus.None)
            is AnimeLoadResponse -> response.episodes.flatMap { (dub, list) -> list.toEpisodes(dub) }
            else -> emptyList()
        }.asReversed()
    }

    private fun List<com.lagradost.cloudstream3.Episode>.toEpisodes(dub: DubStatus): List<SEpisode> =
        filter { it.data.isNotBlank() }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .mapIndexed { index, episode ->
                val number = episode.episode ?: (index + 1)
                SEpisode.create().apply {
                    url = episode.data
                    name = buildString {
                        episode.season?.takeIf { it > 0 }?.let { append("S").append(it).append(" ") }
                        append("Ep. ").append(number)
                        episode.name?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
                    }
                    episode_number = number.toFloat()
                    date_upload = episode.date ?: 0L
                    scanlator = dub.takeUnless { it == DubStatus.None }?.name
                    summary = episode.description
                    preview_url = episode.posterUrl
                }
            }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    // Videos

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        withTimeoutOrNull(90_000) {
            runCatching {
                api.loadLinks(
                    episode.url,
                    false,
                    { synchronized(subtitles) { subtitles += it } },
                    { synchronized(links) { links += it } },
                )
            }
        }
        val tracks = synchronized(subtitles) { subtitles.map { Track(it.url, it.lang) } }
        synchronized(links) { links.toList() }
            .filter { it.type != ExtractorLinkType.TORRENT && it.type != ExtractorLinkType.MAGNET }
            .distinctBy { it.url }
            .sortedByDescending { it.quality }
            .map { link ->
                val quality = link.quality.takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
                Video(
                    videoUrl = link.url,
                    videoTitle = listOf(link.name, quality).filter { it.isNotBlank() }.joinToString(" · "),
                    resolution = link.quality.takeIf { it > 0 },
                    headers = link.getAllHeaders().toHeaders(),
                    subtitleTracks = tracks,
                    initialized = true,
                ).apply {
                    type = when (link.type) {
                        ExtractorLinkType.M3U8 -> VideoType.HLS
                        ExtractorLinkType.DASH -> VideoType.DASH
                        else -> VideoType.VIDEO
                    }
                }
            }
    }

    // RxJava entry points are not used by this source
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        throw UnsupportedOperationException()
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

    private fun SearchResponse.toSAnime() = SAnime.create().apply {
        url = this@toSAnime.url
        title = this@toSAnime.name
        thumbnail_url = posterUrl
    }

    override fun toString(): String = "$name (Cloudstream)"

    private companion object {
        fun generateId(name: String, lang: String): Long {
            val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/$lang/1".toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}
