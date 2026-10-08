package eu.kanade.tachiyomi.ui.player.compat

import eu.kanade.tachiyomi.data.anime.Anime
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.anime.Episode as DomainEpisode

/**
 * KMK: The AniZen player keeps a mutable copy of each episode and writes its progress back as it plays.
 * This is that copy, built from Komikku's anime episode.
 */
class Episode(
    var id: Long?,
    var anime_id: Long?,
    var url: String,
    var name: String,
    var scanlator: String?,
    var summary: String?,
    var preview_url: String?,
    var seen: Boolean,
    var bookmark: Boolean,
    var fillermark: Boolean,
    var last_second_seen: Long,
    var total_seconds: Long,
    var episode_number: Float,
    var source_order: Long,
    var date_fetch: Long,
    var date_upload: Long,
)

fun DomainEpisode.toDbEpisode(): Episode = Episode(
    id = id,
    anime_id = animeId,
    url = url,
    name = name,
    scanlator = scanlator,
    summary = summary,
    preview_url = previewUrl,
    seen = seen,
    bookmark = bookmark,
    fillermark = fillermark,
    last_second_seen = lastSecondSeen,
    total_seconds = totalSeconds,
    episode_number = episodeNumber.toFloat(),
    source_order = sourceOrder,
    date_fetch = dateFetch,
    date_upload = dateUpload,
)

fun Episode.toDomainEpisode(): DomainEpisode? {
    val id = id ?: return null
    return DomainEpisode(
        id = id,
        animeId = anime_id ?: -1L,
        url = url,
        name = name,
        scanlator = scanlator,
        summary = summary,
        previewUrl = preview_url,
        seen = seen,
        bookmark = bookmark,
        fillermark = fillermark,
        lastSecondSeen = last_second_seen,
        totalSeconds = total_seconds,
        episodeNumber = episode_number.toDouble(),
        sourceOrder = source_order,
        dateFetch = date_fetch,
        dateUpload = date_upload,
    )
}

/** Per-anime skip intro length, kept as a preference until anime viewer flags are merged */
private fun skipIntroPref(animeId: Long) =
    Injekt.get<PreferenceStore>().getInt("anime_skip_intro_length_$animeId", 0)

val Anime.skipIntroLength: Int
    get() = skipIntroPref(id).get()

val Anime.skipIntroDisable: Boolean
    get() = false

fun setAnimeSkipIntroLength(animeId: Long, length: Long) {
    skipIntroPref(animeId).set(length.toInt())
}
