package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

/**
 * KMK: Episode pictures, titles and summaries for an anime's page from the app's own AniList data (the same
 * ani.zip list the AniList page shows), instead of each source looking them up itself.
 *
 * Which AniList entry an anime is: the one picked with "Change" (remembered per anime), else its AniList track,
 * else its MyAnimeList track, else an earlier title match ("auto:"), else a title search (an exact title first, the
 * closest episode count winning).
 */
@Immutable
data class AniListEpisodeInfo(
    val anilistId: Int,
    val title: String,
    /** Each episode of the source by its number */
    private val episodes: Map<Int, AniListEpisode>,
    /** The source counts on from an earlier season: its episode 13 is this entry's 1 */
    val offset: Int,
) {
    fun of(sourceNumber: Double): AniListEpisode? {
        if (sourceNumber <= 0 || sourceNumber % 1.0 != 0.0) return null
        return episodes[sourceNumber.toInt() - offset]
    }
}

object AniListEpisodeMatch {

    private val store get() = Injekt.get<PreferenceStore>()

    /** "" not looked up yet, "none" for no AniList entry, else the id; "auto:" when found by title */
    private fun pref(animeId: Long) = store.getString("anime_anilist_episodes_$animeId", "")

    /** [anilistId] for this anime from now on; null for none */
    fun set(animeId: Long, anilistId: Int?) {
        pref(animeId).set(anilistId?.toString() ?: NONE)
    }

    fun clear(animeId: Long) = pref(animeId).delete()

    /** The episode info of [anime], whose source numbers its episodes [numbers]; null when it has no match */
    suspend fun load(anime: Anime, numbers: List<Double>): AniListEpisodeInfo? = withIOContext {
        try {
            withTimeoutOrNull(TIMEOUT_MS) { find(anime, numbers) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.add('W', "anilist", "${anime.title}: no episode info (${e.message})")
            null
        }
    }

    private suspend fun find(anime: Anime, numbers: List<Double>): AniListEpisodeInfo? {
        val saved = pref(anime.id).get()
        if (saved == NONE) return null
        // KMK (r87): a manual pick first, then the trackers, then a saved title match, then a new title search.
        // A saved "auto:" match used to come before the tracks, so a wrong title match stuck even after tracking.
        val manual = saved.takeUnless { it.startsWith(AUTO) }?.toIntOrNull()
        val auto = saved.takeIf { it.startsWith(AUTO) }?.removePrefix(AUTO)?.toIntOrNull()
        val id = manual
            ?: fromTracks(anime.id)
            ?: auto
            ?: fromTitle(anime, numbers)?.also { pref(anime.id).set("$AUTO$it") }
            ?: run {
                AppLog.add('I', "anilist", "${anime.title}: no AniList entry found for the episode info")
                return null
            }
        val details = AniListApi.details(id)
        val list = AniListApi.episodes(details)
        if (list.isEmpty()) return null
        val whole = numbers.filter { it > 0 && it % 1.0 == 0.0 }.map { it.toInt() }
        val count = list.size
        // A later season numbered on from the first (Solo Leveling S2 as 13–25 where AniList has 1–13)
        val offset = if (whole.isNotEmpty() && whole.min() > 1 && whole.max() > count &&
            whole.max() - (whole.min() - 1) <= count
        ) {
            whole.min() - 1
        } else {
            0
        }
        AppLog.add(
            'I',
            "anilist",
            "${anime.title}: episode info from AniList \"${details.card.title}\" ($count episodes" +
                (if (offset > 0) ", source numbers start at ${offset + 1}" else "") + ")",
        )
        return AniListEpisodeInfo(
            anilistId = id,
            title = details.card.title,
            episodes = list.associateBy { it.number },
            offset = offset,
        )
    }

    private suspend fun fromTracks(animeId: Long): Int? {
        val tracks = runCatching { Injekt.get<AnimeTrackRepository>().getTracks(animeId) }.getOrNull().orEmpty()
        tracks.firstOrNull { it.trackerId == TrackerManager.ANILIST && it.remoteId > 0 }
            ?.let { return it.remoteId.toInt() }
        val mal = tracks.firstOrNull { it.trackerId == MYANIMELIST && it.remoteId > 0 }?.remoteId ?: return null
        return runCatching { AniListApi.idForMal(mal.toInt()) }.getOrNull()
    }

    private suspend fun fromTitle(anime: Anime, numbers: List<Double>): Int? {
        val wanted = anime.title.normalized().takeIf { it.length >= 2 } ?: return null
        val results = search(anime.title)
        if (results.isEmpty()) return null
        val count = numbers.count { it > 0 && it % 1.0 == 0.0 }
        val exact = results.filter { media -> names(media).any { it == wanted } }
        val pick = exact.minByOrNull { media -> media.episodes?.let { abs(it - count) } ?: Int.MAX_VALUE }
            ?: results.firstOrNull { media ->
                wanted.length >= 4 &&
                    names(media).any { it.length >= 4 && (it.startsWith(wanted) || wanted.startsWith(it)) }
            }
        return pick?.id
    }

    /** AniList's search, for "Change" and the title match */
    suspend fun search(query: String): List<AniListMedia> =
        AniListApi.search(AniListFilters(), query.trim(), 1).media

    private fun names(media: AniListMedia): List<String> =
        listOfNotNull(media.romaji, media.english, media.native, media.userPreferred).map { it.normalized() }

    private fun String.normalized(): String =
        lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private const val NONE = "none"
    private const val AUTO = "auto:"
    private const val TIMEOUT_MS = 20_000L

    // MyAnimeList's tracker id (TrackerManager.myAnimeList)
    private const val MYANIMELIST = 1L
}
