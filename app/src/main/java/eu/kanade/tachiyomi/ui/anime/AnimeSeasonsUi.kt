package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.AnimeCoverImage
import eu.kanade.presentation.anime.AnimeGridItem
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeSeasonFlags
import eu.kanade.tachiyomi.data.anime.seasonCompletedFilter
import eu.kanade.tachiyomi.data.anime.seasonDownloadedFilter
import eu.kanade.tachiyomi.data.anime.seasonLayout
import eu.kanade.tachiyomi.data.anime.seasonShowTitles
import eu.kanade.tachiyomi.data.anime.seasonSortDescending
import eu.kanade.tachiyomi.data.anime.seasonSortMode
import eu.kanade.tachiyomi.data.anime.seasonStartedFilter
import eu.kanade.tachiyomi.data.anime.seasonUnseenFilter
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: Filter / Sort / Display sheet for an anime's seasons, like Anikku's SeasonSettingsDialog */
@Composable
internal fun AnimeSeasonSettingsDialog(
    anime: Anime,
    onDismissRequest: () -> Unit,
    onFilterChanged: (include: Long, exclude: Long, TriState) -> Unit,
    onSortChanged: (Long) -> Unit,
    onLayoutChanged: (Long) -> Unit,
    onShowTitlesChanged: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onReset()
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    TriStateItem(
                        label = stringResource(MR.strings.label_downloaded),
                        state = anime.seasonDownloadedFilter,
                        onClick = {
                            onFilterChanged(AnimeSeasonFlags.SHOW_DOWNLOADED, AnimeSeasonFlags.SHOW_NOT_DOWNLOADED, it)
                        },
                    )
                    TriStateItem(
                        label = stringResource(KMR.strings.anime_filter_unseen),
                        state = anime.seasonUnseenFilter,
                        onClick = { onFilterChanged(AnimeSeasonFlags.SHOW_UNSEEN, AnimeSeasonFlags.SHOW_SEEN, it) },
                    )
                    TriStateItem(
                        label = stringResource(MR.strings.label_started),
                        state = anime.seasonStartedFilter,
                        onClick = {
                            onFilterChanged(AnimeSeasonFlags.SHOW_STARTED, AnimeSeasonFlags.SHOW_NOT_STARTED, it)
                        },
                    )
                    TriStateItem(
                        label = stringResource(MR.strings.completed),
                        state = anime.seasonCompletedFilter,
                        onClick = {
                            onFilterChanged(AnimeSeasonFlags.SHOW_COMPLETED, AnimeSeasonFlags.SHOW_NOT_COMPLETED, it)
                        },
                    )
                }
                1 -> {
                    listOf(
                        MR.strings.sort_by_source to AnimeSeasonFlags.SORT_SOURCE,
                        KMR.strings.anime_season_sort_by_number to AnimeSeasonFlags.SORT_NUMBER,
                        MR.strings.action_sort_alpha to AnimeSeasonFlags.SORT_ALPHABET,
                    ).forEach { (label, mode) ->
                        SortItem(
                            label = stringResource(label),
                            sortDescending = anime.seasonSortDescending.takeIf { anime.seasonSortMode == mode },
                            onClick = { onSortChanged(mode) },
                        )
                    }
                }
                2 -> {
                    listOf(
                        KMR.strings.anime_season_layout_row to AnimeSeasonFlags.LAYOUT_ROW,
                        KMR.strings.anime_season_layout_grid to AnimeSeasonFlags.LAYOUT_GRID,
                        MR.strings.action_display_list to AnimeSeasonFlags.LAYOUT_LIST,
                    ).forEach { (label, layout) ->
                        RadioItem(
                            label = stringResource(label),
                            selected = anime.seasonLayout == layout,
                            onClick = { onLayoutChanged(layout) },
                        )
                    }
                    CheckboxItem(
                        label = stringResource(KMR.strings.anime_season_show_titles),
                        checked = anime.seasonShowTitles,
                        onClick = { onShowTitlesChanged(!anime.seasonShowTitles) },
                    )
                }
            }
        }
    }
}

/** "Season 2", or null when the number isn't known */
@Composable
private fun seasonNumberLabel(season: Anime): String? {
    val number = season.seasonNumber.takeIf { it >= 0 } ?: return null
    val text = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    return stringResource(KMR.strings.anime_season_number, text)
}

/** One season cover: the usual anime grid item, or the cover with only its season number when titles are hidden */
@Composable
internal fun AnimeSeasonCover(
    season: Anime,
    source: AnimeSource?,
    showTitle: Boolean,
    onClick: () -> Unit,
) {
    if (showTitle) {
        AnimeGridItem(anime = season, source = source, onClick = onClick)
        return
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        AnimeCoverImage(url = season.thumbnailUrl, source = source, modifier = Modifier.fillMaxSize())
        seasonNumberLabel(season)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** The horizontal row of seasons (the default look) */
@Composable
internal fun AnimeSeasonRow(
    seasons: List<Anime>,
    source: AnimeSource?,
    showTitles: Boolean,
    onClick: (Anime) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(seasons, key = { it.id }) { season ->
            Box(Modifier.width(120.dp)) {
                AnimeSeasonCover(season = season, source = source, showTitle = showTitles, onClick = { onClick(season) })
            }
        }
    }
}

/** One line of the season grid; the last line is padded with empty cells */
@Composable
internal fun AnimeSeasonGridLine(
    seasons: List<Anime>,
    columns: Int,
    source: AnimeSource?,
    showTitles: Boolean,
    onClick: (Anime) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        seasons.forEach { season ->
            Box(Modifier.weight(1f)) {
                AnimeSeasonCover(season = season, source = source, showTitle = showTitles, onClick = { onClick(season) })
            }
        }
        repeat(columns - seasons.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A season as a list row: small cover, title and season number */
@Composable
internal fun AnimeSeasonListItem(
    season: Anime,
    source: AnimeSource?,
    showTitle: Boolean,
    onClick: () -> Unit,
) {
    val number = seasonNumberLabel(season)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimeCoverImage(
            url = season.thumbnailUrl,
            source = source,
            modifier = Modifier
                .width(48.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            val title = if (showTitle) season.title else number ?: season.title
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showTitle && number != null) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (season.favorite) {
                Text(
                    text = stringResource(MR.strings.in_library),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
