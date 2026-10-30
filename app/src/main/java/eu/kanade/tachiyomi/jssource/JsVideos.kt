package eu.kanade.tachiyomi.jssource

import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers

/** KMK: Turns what a JS source's `getVideoList` returned into the player's videos */
internal object JsVideos {

    /**
     * Each video with the server it names (`server`, or `hoster`), if any. [baseUrl] is the Referer and
     * [sourceHeaders] the source's own headers; a video's `headers` win over both.
     */
    fun map(videos: List<JsonObject>, baseUrl: String, sourceHeaders: Map<String, String>): List<Pair<String?, Video>> =
        videos.mapNotNull { video ->
            var url = video.str("url")?.trim() ?: return@mapNotNull null
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http")) return@mapNotNull null
            val headers = Headers.Builder().apply {
                if (baseUrl.isNotBlank()) set("Referer", "$baseUrl/")
                sourceHeaders.forEach { (k, v) -> runCatching { set(k, v) } }
                video.obj("headers")?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { runCatching { set(k, it) } } }
            }.build()
            val timestamps = listOfNotNull(
                video.obj("intro")?.let { stamp(it, "Intro", ChapterType.Opening) },
                video.obj("outro")?.let { stamp(it, "Outro", ChapterType.Ending) },
            )
            val quality = video.str("quality").orEmpty()
            val server = (video.str("server") ?: video.str("hoster"))?.trim()?.takeIf { it.isNotEmpty() }
            // KMK: "m3u8" anywhere in the address, the query too (`…/get?file=a.m3u8`), as downloads check it
            // KMK: a DASH manifest (`.mpd`) is checked first, so `a.mpd?alt=m3u8` isn't taken for HLS
            val path = url.substringBefore('?').lowercase()
            val isDash = path.endsWith(".mpd")
            val isHls = !isDash && url.contains("m3u8", ignoreCase = true)
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
                type = when {
                    isHls -> VideoType.HLS
                    isDash -> VideoType.DASH
                    else -> VideoType.VIDEO
                }
            }.let { server to it }
        }

    private fun stamp(obj: JsonObject, name: String, type: ChapterType): TimeStamp? {
        val start = obj.double("start") ?: return null
        val end = obj.double("end") ?: return null
        return TimeStamp(start, end, name, type)
    }

    fun tracks(array: JsonArray?): List<Track> = array.orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        var url = obj.str("url")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (url.startsWith("//")) url = "https:$url"
        Track(url, obj.str("label") ?: obj.str("language") ?: "Track")
    }
}
