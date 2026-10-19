package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.airingCountdown
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeAiring
import eu.kanade.tachiyomi.data.anime.AnimeAiringRepository
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import kotlinx.coroutines.delay
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Immutable
private data class ComingUpItem(val anime: Anime, val airing: AnimeAiring)

/**
 * KMK: Library anime with their next airing episode (AniList), soonest first and grouped by day.
 * Opened from the anime library's overflow menu.
 */
class AnimeComingUpScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val items by produceState<List<ComingUpItem>?>(initialValue = null) {
            val favorites = runCatching { Injekt.get<AnimeRepository>().getFavorites() }.getOrDefault(emptyList())
            val airing = AnimeAiringRepository.forLibrary(favorites)
            value = favorites.mapNotNull { anime -> airing[anime.id]?.let { ComingUpItem(anime, it) } }
                .sortedBy { it.airing.airingAt }
        }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000)
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.anime_coming_up),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val list = items
            when {
                list == null -> LoadingScreen(Modifier.padding(contentPadding))
                list.isEmpty() -> EmptyScreen(
                    message = stringResource(KMR.strings.anime_coming_up_empty),
                    modifier = Modifier.padding(contentPadding),
                )
                else -> {
                    val zone = remember { ZoneId.systemDefault() }
                    val byDay = remember(list) {
                        list.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.airing.airingAt), zone) }
                    }
                    LazyColumn(contentPadding = contentPadding) {
                        byDay.forEach { (day, dayItems) ->
                            item(key = "day-$day") {
                                val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                                ListGroupHeader(text = "$weekday · ${relativeDateText(day)}")
                            }
                            items(dayItems, key = { "anime-${it.anime.id}" }) { item ->
                                ComingUpRow(
                                    item = item,
                                    now = now,
                                    onClick = { navigator.push(AnimeScreen(item.anime.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComingUpRow(item: ComingUpItem, now: Long, onClick: () -> Unit) {
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val time = remember(item.airing.airingAt) {
        timeFormat.format(Instant.ofEpochMilli(item.airing.airingAt).atZone(ZoneId.systemDefault()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MangaCover.Book(
            data = item.anime.asMangaCover(),
            modifier = Modifier.height(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.anime.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(KMR.strings.anime_coming_up_episode, item.airing.episode, time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val left = item.airing.airingAt - now
        if (left > 0) {
            Text(
                text = airingCountdown(left),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
