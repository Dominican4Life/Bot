package eu.kanade.tachiyomi.jssource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

/**
 * KMK: Videos inside a manga chapter. A JS manga source can return pages with a `videoUrl`; the reader shows
 * them as a picture (the page's `imageUrl` or `thumbnailUrl`, or a plain dark tile) with a play button, and tapping
 * it plays the video in the app's own player. The chapter's videos become the episodes of a hidden anime, so the
 * player's next / previous go through the chapter's videos.
 */
object ReaderVideos {

    /** The hidden anime source the chapter videos are played through */
    const val SOURCE_ID = 7_301_844_225_610_480_103L

    private const val MARK = "jsvideo:"

    /** The picture of a video page that has no thumbnail; never fetched, see [tileResponse] */
    const val TILE_URL = "https://jssource.invalid/video-tile.png"

    fun isVideo(pageUrl: String?): Boolean =
        pageUrl != null && (pageUrl.startsWith(MARK) || pageUrl.startsWith(JsLegacy.OLD_VIDEO_MARK))

    /** The page url that carries a video: its address, headers, subtitles, thumbnail and quality */
    fun encode(data: JsonObject): String =
        MARK + Base64.encodeToString(data.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decode(pageUrl: String): JsonObject? = runCatching {
        val raw = String(Base64.decode(pageUrl.removePrefix(MARK).removePrefix(JsLegacy.OLD_VIDEO_MARK), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        jsJson.parseToJsonElement(raw) as JsonObject
    }.getOrNull()

    /**
     * Turns one page of a JS source's `getPageList` into a video page, or null when it has neither a
     * `videoUrl` (the stream itself) nor a `videoListUrl` (handed to the source's own `getVideoList` when played,
     * for embeds that are slow to resolve). [defaults] are the source's own headers (its Referer first); the page's
     * `headers` win over them.
     */
    fun pageOf(index: Int, page: JsonObject, defaults: Map<String, String>, sourceId: Long): Page? {
        val video = absolute(page.str("videoUrl"))
        val listUrl = page.str("videoListUrl")?.trim()?.takeIf { it.isNotEmpty() }
        if (video == null && listUrl == null) return null
        val thumbnail = absolute(page.str("imageUrl") ?: page.str("thumbnailUrl"))
        val data = buildJsonObject {
            video?.let { put("url", it) }
            listUrl?.let {
                put("listUrl", it)
                put("sourceId", sourceId.toString())
            }
            thumbnail?.let { put("thumbnailUrl", it) }
            page.str("quality")?.let { put("quality", it) }
            page.str("title")?.let { put("title", it) }
            put(
                "headers",
                buildJsonObject {
                    defaults.forEach { (k, v) -> put(k, v) }
                    page.obj("headers")?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { put(k, it) } }
                },
            )
            page.arr("subtitles")?.let { put("subtitles", it) }
            page.arr("audioTracks")?.let { put("audioTracks", it) }
        }
        return Page(index, url = encode(data), imageUrl = thumbnail ?: TILE_URL)
    }

    private fun absolute(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> u
            else -> null
        }
    }

    /** True when a video page shows the plain tile rather than a thumbnail from the site */
    fun usesTile(page: Page): Boolean = isVideo(page.url) && decode(page.url)?.str("thumbnailUrl") == null

    private val tileBytes: ByteArray by lazy {
        val w = 1280
        val h = 720
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(28, 28, 30))
        // Plain: the reader and the gallery put their own play button over it
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** A made-up reply holding the dark tile, so the reader and downloads treat it like any other picture */
    fun tileResponse(): Response = Response.Builder()
        .request(GET(TILE_URL))
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(tileBytes.toResponseBody("image/png".toMediaType()))
        .build()

    /** The episodes for the video pages of a chapter, in page order, newest (last) first like sources list them */
    fun episodesOf(pageUrls: List<Pair<Int, String>>): List<SEpisode> =
        pageUrls.filter { isVideo(it.second) }
            .mapIndexed { i, (pageIndex, url) ->
                val data = decode(url)
                SEpisode.create().apply {
                    this.url = url
                    name = data?.str("title") ?: "Video ${i + 1} · page ${pageIndex + 1}"
                    episode_number = (i + 1).toFloat()
                }
            }
            .reversed()

    /**
     * The player's videos for a video page: its own stream, or what the source's `getVideoList` gives for its
     * `videoListUrl`, with the page's headers under the video's own
     */
    suspend fun videosOf(pageUrl: String): List<Video> {
        val data = decode(pageUrl) ?: return emptyList()
        // KMK: a video saved in a downloaded chapter archive is copied out, then played from the file
        data.str("archive")?.let { archive ->
            val entry = data.str("entry") ?: return emptyList()
            val path = withIOContext { ChapterVideoDownloads.extract(archive, entry) }
            return listOf(Video(videoUrl = path, videoTitle = "Downloaded", initialized = true))
        }
        val listUrl = data.str("listUrl")
        if (listUrl != null) {
            val source = data.str("sourceId")?.toLongOrNull()
                ?.let { Injekt.get<SourceManager>().get(it) } as? JsMangaSource
                ?: error("The source of this video isn't installed any more")
            val pageHeaders = data.obj("headers")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                ?.toMap().orEmpty()
            return source.videoList(listUrl, pageHeaders).ifEmpty { error("The source found no video to play here") }
        }
        return listOfNotNull(videoOf(data))
    }

    private fun videoOf(data: JsonObject): Video? {
        val url = data.str("url") ?: return null
        val headers = Headers.Builder().apply {
            data.obj("headers")?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { runCatching { set(k, it) } } }
        }.build()
        // KMK: "m3u8" anywhere in the address, the query too (`…/get?file=a.m3u8`), as downloads check it (rule 6)
        val isHls = url.contains("m3u8", ignoreCase = true)
        val path = url.substringBefore('?').lowercase()
        val quality = data.str("quality").orEmpty()
        return Video(
            videoUrl = if (isHls) HlsRelay.wrap(url, headers) else url,
            videoTitle = quality.ifBlank { "Video" },
            headers = headers,
            subtitleTracks = JsVideos.tracks(data.arr("subtitles")),
            audioTracks = JsVideos.tracks(data.arr("audioTracks")),
            initialized = true,
        ).apply {
            type = when {
                isHls -> VideoType.HLS
                path.endsWith(".mpd") -> VideoType.DASH
                else -> VideoType.VIDEO
            }
        }
    }

    /**
     * Plays the video of [page] in the app's player, with the chapter's other videos as next / previous.
     * False when the player couldn't be started.
     */
    suspend fun play(context: Context, page: ReaderPage): Boolean {
        val chapter = page.chapter.chapter
        val (animeId, episodeId) = withIOContext {
            val manga = chapter.manga_id?.let { Injekt.get<GetManga>().await(it) }
            val pages = page.chapter.pages.orEmpty().ifEmpty { listOf(page) }
            val sAnime = SAnime.create().apply {
                url = "${manga?.source ?: 0}\n${chapter.url}"
                title = listOfNotNull(manga?.title, chapter.name.takeIf { it.isNotBlank() }).joinToString(" · ")
                thumbnail_url = manga?.thumbnailUrl
                initialized = true
            }
            val repository = Injekt.get<AnimeRepository>()
            val anime = repository.networkToLocal(sAnime, SOURCE_ID)
            repository.syncEpisodes(anime, episodesOf(pages.map { it.index to it.url }))
            anime.id to repository.getEpisodes(anime.id).firstOrNull { it.url == page.url }?.id
        }
        if (episodeId == null) return false
        withUIContext { context.startActivity(PlayerActivity.newIntent(context, animeId, episodeId)) }
        return true
    }
}

/**
 * KMK: Plays plain video links (the AniList page's openings and endings) in the app's player, one after another,
 * through the same hidden source. [index] is the one to start with; [pip] starts the player in a small window.
 */
suspend fun ReaderVideos.playLinks(
    context: Context,
    title: String,
    thumbnail: String?,
    videos: List<Pair<String, String>>,
    index: Int,
    pip: Boolean,
): Boolean {
    if (videos.isEmpty()) return false
    val (animeId, episodeId) = withIOContext {
        val sAnime = SAnime.create().apply {
            url = "-1\nanilist-videos:$title"
            this.title = title
            thumbnail_url = thumbnail
            initialized = true
        }
        val repository = Injekt.get<AnimeRepository>()
        val anime = repository.networkToLocal(sAnime, ReaderVideos.SOURCE_ID)
        val episodes = videos.mapIndexed { i, (name, link) ->
            SEpisode.create().apply {
                url = encode(
                    buildJsonObject {
                        put("url", link)
                        put("title", name)
                    },
                )
                this.name = name
                episode_number = (i + 1).toFloat()
            }
        }
        repository.syncEpisodes(anime, episodes.reversed())
        val wanted = episodes.getOrNull(index)?.url
        anime.id to repository.getEpisodes(anime.id).firstOrNull { it.url == wanted }?.id
    }
    if (episodeId == null) return false
    withUIContext {
        context.startActivity(
            PlayerActivity.newIntent(context, animeId, episodeId).putExtra(PlayerActivity.EXTRA_START_PIP, pip),
        )
    }
    return true
}

/** KMK: The hidden anime source that plays the videos found in manga chapters. Not listed with the other sources. */
class ReaderVideosSource : AnimeSource {
    override val id: Long = ReaderVideos.SOURCE_ID
    override val name: String = "Videos in chapters"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply { initialized = true }

    /** Reads the chapter again from its manga source, so the list stays right if the chapter changed */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val sourceId = anime.url.substringBefore('\n').toLongOrNull() ?: return emptyList()
        val chapterUrl = anime.url.substringAfter('\n')
        val source = Injekt.get<SourceManager>().get(sourceId) ?: return emptyList()
        val pages = source.getPageList(SChapter.create().apply { url = chapterUrl })
        return ReaderVideos.episodesOf(pages.map { it.index to it.url })
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = ReaderVideos.videosOf(episode.url)

    override fun toString(): String = name
}
