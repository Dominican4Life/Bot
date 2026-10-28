package eu.kanade.tachiyomi.ui.player.loader

import android.app.Application
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A stream that holds several qualities in one list (an HLS "master" playlist with 480p, 720p and 1080p, each
 * with its own sound) is turned into one video per quality, so:
 * - the player's quality menu lists them, like a source that gives each quality itself (Anikoto);
 * - the player starts on the preferred quality (Settings › Player) straight away. Given the whole list, mpv read
 *   two pieces of every quality one after another before starting (~12 s), then played the best picture with the
 *   lowest quality's sound, downloading two streams the whole time.
 *
 * Lists whose sound comes apart from the picture (EXT-X-MEDIA audio groups), and lists with one quality, are left
 * as they are. The whole list stays at the end as "Auto".
 */
object HlsVariants {

    private data class Variant(val url: String, val height: Int?, val bandwidth: Int?)

    private fun preferredHeight(): Int =
        Injekt.get<PlayerPreferences>().preferredQuality().get().filter { it.isDigit() }.toIntOrNull() ?: 1080

    suspend fun split(videos: List<Video>): List<Video> {
        if (videos.isEmpty() || videos.size > MAX_VIDEOS) return videos
        val preferred = preferredHeight()
        return coroutineScope {
            videos.map { video ->
                async { localQualities(video, preferred) ?: expand(video, preferred) }
            }.awaitAll().flatten()
        }
    }

    /**
     * For a download: the one quality to save (the preferred one, or the best below it) of a stream that holds
     * several, so the file has one picture and one sound instead of all of them; else [video] as it is.
     */
    suspend fun forDownload(video: Video): Video {
        val chosen = expand(video, preferredHeight()).first()
        return if (chosen.videoUrl != video.videoUrl) chosen else video
    }

    /**
     * A downloaded file that holds several qualities (saved before r83 took one): one video per picture in it, each
     * playing that picture ("vid") with its own sound (marked in internalData, picked by the player), the preferred
     * quality first. Null when [video] isn't such a file.
     */
    private suspend fun localQualities(video: Video, preferredHeight: Int): List<Video>? {
        val url = video.videoUrl
        val input = when {
            url.startsWith("content://") -> runCatching {
                FFmpegKitConfig.getSafParameterForRead(Injekt.get<Application>(), Uri.parse(url))
            }.getOrNull()
            url.startsWith("file://") -> Uri.parse(url).path
            url.startsWith("/") -> url
            else -> null
        } ?: return null
        val streams = withIOContext {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    val session = FFprobeKit.executeWithArguments(
                        arrayOf("-v", "quiet", "-show_entries", "stream=index,codec_type,height", "-of", "json", input),
                    )
                    val root = Json.parseToJsonElement(session.output.orEmpty()) as? JsonObject
                    (root?.get("streams") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                }.getOrNull()
            }
        } ?: return null
        val pictures = streams.filter { (it["codec_type"] as? JsonPrimitive)?.contentOrNull == "video" }
        val sounds = streams.count { (it["codec_type"] as? JsonPrimitive)?.contentOrNull == "audio" }
        val heights = pictures.map { (it["height"] as? JsonPrimitive)?.intOrNull ?: 0 }
        if (pictures.size < 2 || heights.toSet().size < 2) return null
        // mpv numbers each kind of track from 1, in the file's order
        val tracks = heights.mapIndexed { index, height -> (index + 1) to height }
        val sorted = tracks.sortedByDescending { it.second }
        val pick = sorted.firstOrNull { it.second == preferredHeight }
            ?: sorted.firstOrNull { it.second in 1..preferredHeight }
            ?: sorted.last()
        val ordered = listOf(pick) + (sorted - pick)
        AppLog.add(
            'I',
            "hls",
            "A downloaded video holds ${pictures.size} qualities (${ordered.joinToString { "${it.second}p" }}), " +
                "starting on ${pick.second}p",
        )
        return ordered.map { (track, height) ->
            video.copy(
                videoTitle = "${height}p",
                resolution = height,
                preferred = video.preferred && track == pick.first,
                mpvArgs = video.mpvArgs.filterNot { it.first == "vid" } + ("vid" to track.toString()),
                internalData = if (sounds == pictures.size) "$QUALITY_TRACK$track" else video.internalData,
                videoPageUrl = video.videoPageUrl,
            )
        }
    }

    /** The sound track that goes with a downloaded quality's picture (see [localQualities]), else null */
    fun soundTrackOf(video: Video?): Int? =
        video?.internalData?.takeIf { it.startsWith(QUALITY_TRACK) }?.removePrefix(QUALITY_TRACK)?.toIntOrNull()

    private const val QUALITY_TRACK = "kmk-quality-track:"

    private suspend fun expand(video: Video, preferredHeight: Int): List<Video> {
        val url = video.videoUrl
        if (!url.startsWith("http", ignoreCase = true) || !isPlaylist(video)) return listOf(video)
        val variants = try {
            withTimeoutOrNull(TIMEOUT_MS) { read(video) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return listOf(video)
        if (variants.size < 2) return listOf(video)

        // Best first, the preferred quality (or the best below it) at the top
        val sorted = variants.sortedByDescending { it.height ?: ((it.bandwidth ?: 0) / 10_000) }
        val pick = sorted.firstOrNull { it.height == preferredHeight }
            ?: sorted.firstOrNull { (it.height ?: 0) in 1..preferredHeight }
            ?: sorted.last()
        val ordered = listOf(pick) + (sorted - pick)
        val base = video.videoTitle.trim()
        val generic = base.isEmpty() || base.lowercase() in GENERIC_TITLES
        val out = ordered.map { variant ->
            val label = variant.height?.let { "${it}p" }
                ?: variant.bandwidth?.let { "%.1f Mbps".format(it / 1_000_000.0) }
                ?: "Quality"
            video.copy(
                videoUrl = variant.url,
                videoTitle = if (generic) label else "$base · $label",
                resolution = variant.height,
                bitrate = variant.bandwidth,
                preferred = video.preferred && variant === pick,
                videoPageUrl = video.videoPageUrl,
            )
        } + video.copy(
            videoTitle = if (generic) "Auto" else "$base · Auto",
            preferred = false,
            videoPageUrl = video.videoPageUrl,
        )
        AppLog.add(
            'I',
            "hls",
            "Split a stream into ${variants.size} qualities (${ordered.joinToString { heightLabel(it) }}), " +
                "starting on ${heightLabel(pick)}",
        )
        return out
    }

    private fun heightLabel(variant: Variant): String = variant.height?.let { "${it}p" } ?: "?"

    private fun isPlaylist(video: Video): Boolean {
        val path = video.videoUrl.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.contains(".m3u8") ||
            video.mimeType?.contains("mpegurl", ignoreCase = true) == true
    }

    private suspend fun read(video: Video): List<Variant>? {
        val request = Request.Builder().url(video.videoUrl).apply {
            video.headers?.let { headers(it) }
        }.build()
        val client = Injekt.get<NetworkHelper>().client
        val (text, finalUrl) = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            // A media playlist can be large; a master list is small, so only its start is needed
            response.body.source().let { source ->
                source.request(MAX_BYTES)
                source.buffer.clone().readUtf8()
            } to response.request.url
        }
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        // Separate sound: splitting would leave the picture without it
        val separateSound = text.lineSequence().any {
            it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") && it.contains("URI=")
        }
        if (separateSound) return null
        val lines = text.lines()
        val variants = mutableListOf<Variant>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                ?: return@forEachIndexed
            val absolute = resolve(finalUrl, next) ?: return@forEachIndexed
            val height = RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()
            val bandwidth = BANDWIDTH.find(line)?.groupValues?.get(1)?.toIntOrNull()
            variants += Variant(absolute, height, bandwidth)
        }
        // One entry per quality (some lists repeat a quality for another server)
        return variants.distinctBy { it.height ?: it.bandwidth ?: it.url }
    }

    private fun resolve(base: HttpUrl, ref: String): String? =
        ref.toHttpUrlOrNull()?.toString() ?: base.resolve(ref)?.toString()

    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val BANDWIDTH = Regex("""(?<![A-Z-])BANDWIDTH=(\d+)""")
    private val GENERIC_TITLES = setOf("video", "auto", "default", "stream", "hls", "source")
    private const val TIMEOUT_MS = 4_000L
    private const val MAX_BYTES = 256L * 1024
    private const val MAX_VIDEOS = 6
}
