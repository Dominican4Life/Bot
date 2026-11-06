package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.GlassSliderRow
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

        // KMK (r133): glass version: floating back button, a Days Ahead slider, day names as glass pills and each
        // day's shows in a frosted panel with the countdown in an accent pill
        val daysPref = remember {
            Injekt.get<tachiyomi.core.common.preference.PreferenceStore>().getInt("coming_up_days_ahead", 7)
        }
        val daysAhead by daysPref.collectAsState()
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable { navigator.pop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                    Text(
                        text = stringResource(KMR.strings.lib_menu_coming_up),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            },
        ) { contentPadding ->
            val list = items
            when {
                list == null -> LoadingScreen(Modifier.padding(contentPadding))
                else -> {
                    val zone = remember { ZoneId.systemDefault() }
                    val today = remember(now) { LocalDate.ofInstant(Instant.ofEpochMilli(now), zone) }
                    val shown = remember(list, daysAhead, today) {
                        list.filter {
                            !LocalDate.ofInstant(Instant.ofEpochMilli(it.airing.airingAt), zone)
                                .isAfter(today.plusDays(daysAhead.toLong() - 1))
                        }
                    }
                    val byDay = remember(shown) {
                        shown.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.airing.airingAt), zone) }
                    }
                    LazyColumn(
                        contentPadding = contentPadding,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        item(key = "days") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .padding(vertical = 8.dp),
                            ) {
                                GlassSliderRow(
                                    label = stringResource(KMR.strings.coming_up_days_ahead),
                                    value = daysAhead,
                                    range = 1..14,
                                    onValueChange = { daysPref.set(it) },
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                )
                            }
                        }
                        if (byDay.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = stringResource(KMR.strings.anime_coming_up_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        byDay.forEach { (day, dayItems) ->
                            item(key = "day-$day") {
                                val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                                Text(
                                    text = "${relativeDateText(day)} · $weekday",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .padding(start = 4.dp, top = 14.dp, bottom = 8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.10f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                            item(key = "panel-$day") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color.White.copy(alpha = 0.07f)),
                                ) {
                                    dayItems.forEachIndexed { index, item ->
                                        if (index > 0) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(1.dp)
                                                    .background(Color.White.copy(alpha = 0.06f)),
                                            )
                                        }
                                        ComingUpRow(
                                            item = item,
                                            now = now,
                                            onClick = { navigator.push(AnimeScreen(item.anime.id)) },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "end") { Box(Modifier.height(24.dp)) }
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
            // KMK (r133): the countdown in an accent pill
            val accent = tachiyomi.presentation.core.components.LocalGlassAccent.current
            Text(
                text = airingCountdown(left),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}
