package eu.kanade.tachiyomi.ui.player.loader

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
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

    suspend fun split(videos: List<Video>): List<Video> {
        if (videos.isEmpty() || videos.size > MAX_VIDEOS) return videos
        val preferred = Injekt.get<PlayerPreferences>().preferredQuality().get().filter { it.isDigit() }
            .toIntOrNull() ?: 1080
        return coroutineScope {
            videos.map { video -> async { expand(video, preferred) } }.awaitAll().flatten()
        }
    }

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
