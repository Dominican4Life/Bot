package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.RadioSelectorSearchable
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.tachiyomi.data.anime.Anime
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: One anime row of the Browse feed, drawn like the manga rows */
@Composable
fun AnimeFeedRow(
    row: AnimeFeedScreenModel.Row,
    onClickHeader: () -> Unit,
    onLongClickHeader: () -> Unit,
    onClickAnime: (Anime) -> Unit,
    onLongClickAnime: (Anime) -> Unit,
) {
    GlobalSearchResultItem(
        title = row.title,
        subtitle = stringResource(KMR.strings.feed_tab_anime) + " · " +
            stringResource(if (row.entry.latest) MR.strings.latest else MR.strings.popular),
        onClick = onClickHeader,
        onLongClick = onLongClickHeader,
    ) {
        val items = row.items
        when {
            items == null -> GlobalSearchLoadingResultItem()
            items.isEmpty() -> GlobalSearchErrorResultItem(
                message = row.error ?: stringResource(MR.strings.no_results_found),
            )
            else -> LazyRow(
                contentPadding = PaddingValues(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                items(items, key = { it.id }) { anime ->
                    MangaItem(
                        title = anime.title,
                        cover = anime.asMangaCover(),
                        isFavorite = anime.favorite,
                        onClick = { onClickAnime(anime) },
                        onLongClick = { onLongClickAnime(anime) },
                    )
                }
            }
        }
    }
}

/** KMK: Second step of adding an anime source to the feed: its Latest or its Popular list */
@Composable
fun AnimeFeedAddDialog(
    option: AnimeFeedScreenModel.Option,
    onDismiss: () -> Unit,
    onAdd: (latest: Boolean) -> Unit,
) {
    val choices = remember(option) {
        buildList {
            if (option.supportsLatest) add(true)
            add(false)
        }.toImmutableList()
    }
    val labels = choices.map { stringResource(if (it) MR.strings.latest else MR.strings.popular) }.toImmutableList()
    var selected by remember { mutableStateOf<Int?>(0) }
    AlertDialog(
        title = { Text(text = option.name) },
        text = {
            RadioSelectorSearchable(
                options = choices,
                optionStrings = labels,
                selected = selected,
            ) { selected = it }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onAdd(choices[it]) } },
                enabled = selected != null,
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

/** KMK: One tag mix row of the Browse feed: titles from several sources for some genres or tags */
@Composable
fun TagMixFeedRow(
    row: TagMixFeedScreenModel.Row,
    onClickHeader: () -> Unit,
    onLongClickHeader: () -> Unit,
    onClickManga: (Manga) -> Unit,
    onLongClickManga: (Manga) -> Unit,
    onClickAnime: (Anime) -> Unit,
    onLongClickAnime: (Anime) -> Unit,
) {
    GlobalSearchResultItem(
        title = row.mix.title,
        subtitle = stringResource(KMR.strings.tag_mix_subtitle, row.mix.sourceIds.size) + " · " +
            stringResource(if (row.mix.anime) KMR.strings.feed_tab_anime else KMR.strings.feed_tab_manga),
        onClick = onClickHeader,
        onLongClick = onLongClickHeader,
    ) {
        when {
            !row.loaded -> GlobalSearchLoadingResultItem()
            row.count == 0 -> GlobalSearchErrorResultItem(
                message = row.error ?: stringResource(KMR.strings.tag_mix_empty),
            )
            else -> LazyRow(
                contentPadding = PaddingValues(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                items(row.manga.orEmpty(), key = { "m${it.id}" }) { manga ->
                    MangaItem(
                        title = manga.title,
                        cover = manga.asMangaCover(),
                        isFavorite = manga.favorite,
                        onClick = { onClickManga(manga) },
                        onLongClick = { onLongClickManga(manga) },
                    )
                }
                items(row.anime.orEmpty(), key = { "a${it.id}" }) { anime ->
                    MangaItem(
                        title = anime.title,
                        cover = anime.asMangaCover(),
                        isFavorite = anime.favorite,
                        onClick = { onClickAnime(anime) },
                        onLongClick = { onLongClickAnime(anime) },
                    )
                }
            }
        }
    }
}
