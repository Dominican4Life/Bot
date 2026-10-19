package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.anime.AnimeAiring
import eu.kanade.tachiyomi.data.anime.AnimeAiringRepository
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.coroutines.delay
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: "Episode N airs in 2d 4h" for the anime page, from AniList (see [AnimeAiringRepository]).
 * Shows nothing when no next episode is known. Anime with no AniList / MyAnimeList track are searched by title.
 */
@Composable
fun AnimeAiringInfo(animeId: Long, modifier: Modifier = Modifier) {
    val airing by produceState<AnimeAiring?>(initialValue = null, animeId) {
        val anime = runCatching { Injekt.get<AnimeRepository>().getAnime(animeId) }.getOrNull()
            ?: return@produceState
        value = AnimeAiringRepository.forAnime(anime, searchByTitle = true)
    }
    val next = airing ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(next) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = airingText(next, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** "Episode N airs in 2d 4h", or "Episode N is airing now" once its time has come */
@Composable
fun airingText(airing: AnimeAiring, now: Long): String {
    val left = airing.airingAt - now
    return if (left <= 0) {
        stringResource(KMR.strings.anime_airing_now, airing.episode)
    } else {
        stringResource(KMR.strings.anime_airing_in, airing.episode, airingCountdown(left))
    }
}

/** "2d 4h", "4h 12m" or "12m" */
@Composable
fun airingCountdown(millisLeft: Long): String {
    val minutes = (millisLeft / 60_000).coerceAtLeast(1)
    val days = (minutes / (24 * 60)).toInt()
    val hours = ((minutes / 60) % 24).toInt()
    val mins = (minutes % 60).toInt()
    return when {
        days > 0 -> stringResource(KMR.strings.anime_airing_duration_days, days, hours)
        minutes >= 60 -> stringResource(KMR.strings.anime_airing_duration_hours, hours, mins)
        else -> stringResource(KMR.strings.anime_airing_duration_minutes, mins)
    }
}
