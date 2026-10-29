package eu.kanade.tachiyomi.data.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** KMK: Gets the playable videos of an episode, for both newer (hoster) and older (video list) extensions. */
object AnimeVideoFetcher {

    suspend fun fetchVideos(source: AnimeSource, episode: SEpisode): List<Video> {
        val hosters: List<Hoster> = if (source is AnimeHttpSource && source.usesHosters()) {
            try {
                source.getHosterList(episode)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Hoster list failed, trying the video list" }
                source.getVideoList(episode).toHosterList()
            }
        } else {
            source.getVideoList(episode).toHosterList()
        }
        val sortedHosters = if (source is AnimeHttpSource) with(source) { hosters.sortHosters() } else hosters
        val videos = sortedHosters.flatMap { hoster ->
            try {
                (hoster.videoList ?: source.getVideoList(hoster)).map { video ->
                    if (hoster.hosterName.isNotBlank() && hoster.hosterName != Hoster.NO_HOSTER_LIST) {
                        video.copy(videoTitle = "${hoster.hosterName} • ${video.videoTitle}")
                    } else {
                        video
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Couldn't load videos of ${hoster.hosterName}" }
                emptyList()
            }
        }
        return if (source is AnimeHttpSource) with(source) { videos.sortVideos() } else videos
    }

    /**
     * KMK (r88): An episode's videos with the server (hoster) each one came from, for the preview player's Qualities
     * menu, which groups them by server like the full player's. [servers] lines up with [videos] (null = the source
     * names no server); [start] is the index to play first (the source's sorted pick).
     */
    class ServerVideos(val videos: List<Video>, val servers: List<String?>, val start: Int)

    /** Like [fetchVideos], but keeps each video's server instead of folding it into the title */
    suspend fun fetchServerVideos(source: AnimeSource, episode: SEpisode): ServerVideos {
        val hosters: List<Hoster> = when {
            // Code editor / JSON sources group their videos by `server` here (as the full player shows them)
            source is eu.kanade.tachiyomi.jssource.JsAnimeSource -> source.getHosterList(episode)
            source is AnimeHttpSource && source.usesHosters() -> try {
                source.getHosterList(episode)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Hoster list failed, trying the video list" }
                source.getVideoList(episode).toHosterList()
            }
            else -> source.getVideoList(episode).toHosterList()
        }
        val sortedHosters = if (source is AnimeHttpSource) with(source) { hosters.sortHosters() } else hosters
        val pairs = sortedHosters.flatMap { hoster ->
            val server = hoster.hosterName.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST }
            try {
                (hoster.videoList ?: source.getVideoList(hoster)).map { video -> server to video }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Couldn't load videos of ${hoster.hosterName}" }
                emptyList()
            }
        }
        val videos = pairs.map { it.second }
        // The source's own sort picks the first video, as with fetchVideos; the list keeps the server order
        val first = if (source is AnimeHttpSource && videos.size > 1) {
            runCatching { with(source) { videos.sortVideos() }.firstOrNull() }.getOrNull()
        } else {
            null
        }
        val start = first?.let { pick -> videos.indexOfFirst { it === pick } }?.takeIf { it >= 0 } ?: 0
        return ServerVideos(videos = videos, servers = pairs.map { it.first }, start = start)
    }

    /** Extensions built for the newer API override the hoster methods; older ones only have video lists. */
    private fun AnimeHttpSource.usesHosters(): Boolean {
        var cls: Class<*>? = javaClass
        while (cls != null && cls != AnimeHttpSource::class.java) {
            if (cls.declaredMethods.any { it.name == "hosterListParse" || it.name == "getHosterList" }) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    /** Some videos only get their real link when picked */
    suspend fun resolve(source: AnimeSource?, video: Video): Video =
        if (!video.initialized && source is AnimeHttpSource) source.resolveVideo(video) ?: video else video
}
