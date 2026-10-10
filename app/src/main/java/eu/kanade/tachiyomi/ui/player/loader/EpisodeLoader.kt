package eu.kanade.tachiyomi.ui.player.loader

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.util.subtitles.StremioSubtitleResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to retrieve the hosters for a given episode.
 */
class EpisodeLoader {
    companion object {

        /**
         * Returns a list of hosters of an [episode] based on the type of [source] used.
         *
         * @param episode the episode being parsed.
         * @param anime the anime of the episode.
         * @param source the source of the anime.
         * @param allowDownloaded whether an already-downloaded copy may be returned. Callers that
         * need a remote, fetchable stream (e.g. the downloader) must pass `false`; a downloaded
         * episode resolves to a local `content://`/`file://` URI, which cannot be fetched over HTTP.
         */
        suspend fun getHosters(
            episode: Episode,
            anime: Anime,
            source: AnimeSource,
            allowDownloaded: Boolean = true,
        ): List<Hoster> {
            // KMK: a downloaded episode plays from its file
            if (allowDownloaded) {
                val file = withIOContext {
                    Injekt.get<AnimeDownloadManager>().findEpisodeFile(anime, episode)
                }
                if (file != null) {
                    val video = Video(
                        videoUrl = file.uri.toString(),
                        videoTitle = "Downloaded",
                        initialized = true,
                    )
                    return listOf(Hoster(hosterName = "Downloaded", videoList = listOf(video)))
                }
            }
            return when (source) {
                is AnimeHttpSource -> getHostersOnHttp(episode, source)
                // KMK: any other anime source (Code editor sources) gives its videos directly
                else -> withIOContext { source.getVideoList(episode.toSEpisode()).toHosterList() }
            }
        }

        /** KMK: whether the episode has a downloaded file */
        fun isDownload(episode: Episode, anime: Anime): Boolean =
            Injekt.get<AnimeDownloadManager>().isDownloaded(anime, episode)

        private fun checkHasHosters(source: AnimeHttpSource): Boolean {
            var current: Class<in AnimeHttpSource> = source.javaClass
            while (true) {
                if (current == ParsedAnimeHttpSource::class.java ||
                    current == AnimeHttpSource::class.java ||
                    current == AnimeSource::class.java
                ) {
                    return false
                }
                if (current.declaredMethods.any {
                        it.name in
                            listOf("getHosterList", "hosterListRequest", "hosterListParse")
                    }
                ) {
                    return true
                }
                current = current.superclass ?: return false
            }
        }

        /**
         * Returns a list of hosters when the [episode] is online.
         *
         * @param episode the episode being parsed.
         * @param source the online source of the episode.
         */
        // ANZ -->
        private suspend fun getHostersOnHttp(episode: Episode, source: AnimeHttpSource): List<Hoster> {
            return withIOContext {
                if (checkHasHosters(source)) {
                    source.getHosterList(episode.toSEpisode())
                        .let { source.run { it.sortHosters() } }
                } else {
                    source.getVideoList(episode.toSEpisode())
                        .let { source.run { it.sortVideos() } }
                        .toHosterList()
                }
            }
        }
        // ANZ <--

        /**
         * Returns a list of videos of a [hoster] based on the type of [source] used.
         * Note that for every type of episode except non-downloaded online, `videoList`
         * will be set to null.
         *
         * @param source the source of the anime.
         * @param hoster the hoster.
         */
        private suspend fun getVideos(source: AnimeSource, hoster: Hoster): List<Video> {
            val videos = when {
                hoster.videoList != null && source is AnimeHttpSource -> hoster.videoList!!.parseVideoUrls(source)
                hoster.videoList != null -> hoster.videoList!!
                source is AnimeHttpSource -> getVideosOnHttp(source, hoster)
                // KMK: any other anime source answers per hoster too
                else -> withIOContext { source.getVideoList(hoster) }
            }

            val sortedVideos = if (source is AnimeHttpSource) {
                source.run { videos.sortVideos() }
            } else {
                videos
            }

            return sortedVideos.resolveSubtitles()
        }

        private suspend fun List<Video>.resolveSubtitles(): List<Video> {
            return this.map { video ->
                if (video.subtitleTracks.isEmpty()) return@map video

                val resolvedTracks = video.subtitleTracks.flatMap { StremioSubtitleResolver.resolve(it) }
                if (resolvedTracks == video.subtitleTracks) return@map video

                video.copy(subtitleTracks = resolvedTracks)
            }
        }

        /**
         * Returns a list of hosters when the [episode] is online.
         *
         * @param source the online source of the episode.
         * @param hoster the hoster.
         */
        // ANZ -->
        private suspend fun getVideosOnHttp(source: AnimeHttpSource, hoster: Hoster): List<Video> {
            return withIOContext {
                source.getVideoList(hoster)
                    .parseVideoUrls(source)
            }
        }
        // ANZ <--

        // TODO(1.6): Remove after ext lib bump
        private suspend fun List<Video>.parseVideoUrls(source: AnimeHttpSource): List<Video> {
            return this.map { video ->
                if (video.videoUrl != "null") return@map video

                val newVideoUrl = source.getVideoUrl(video)
                video.copy(videoUrl = newVideoUrl)
            }
        }

        suspend fun loadHosterVideos(source: AnimeSource, hoster: Hoster): HosterState {
            return try {
                val videos = getVideos(source, hoster)
                HosterState.Ready(hoster.hosterName, videos, List(videos.size) { Video.State.QUEUE })
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                HosterState.Error(hoster.hosterName)
            }
        }
    }
}
