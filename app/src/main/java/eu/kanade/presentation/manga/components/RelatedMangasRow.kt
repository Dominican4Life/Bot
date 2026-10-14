package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.EmptyResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.tachiyomi.ui.manga.RelatedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.presentation.core.components.material.padding

@Composable
fun RelatedMangasRow(
    relatedMangas: List<RelatedManga>?,
    getMangaState: @Composable (Manga) -> State<Manga>,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    when {
        relatedMangas == null -> {
            GlobalSearchLoadingResultItem()
        }

        relatedMangas.isNotEmpty() -> {
            RelatedMangaCardRow(
                relatedMangas = relatedMangas,
                getManga = { getMangaState(it) },
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }

        else -> {
            EmptyResultItem()
        }
    }
}

@Composable
fun RelatedMangaCardRow(
    relatedMangas: List<RelatedManga>,
    getManga: @Composable (Manga) -> State<Manga>,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val groups = relatedMangas.filterIsInstance<RelatedManga.Success>()
    val loading = relatedMangas.filterIsInstance<RelatedManga.Loading>().firstOrNull()

    LazyRow(
        contentPadding = PaddingValues(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        groups.forEachIndexed { index, group ->
            // KMK: a source's named group (JS getRelated) starts with its name, so the groups can be told apart
            if (group.label != null && group.mangaList.isNotEmpty()) {
                item(key = "related-row-label-$index") {
                    RelatedMangaGroupLabel(group.label)
                }
            }
            items(group.mangaList, key = { "related-row-${it.id}" }) {
                val manga by getManga(it)
                MangaItem(
                    title = manga.title,
                    cover = manga.asMangaCover(),
                    isFavorite = manga.favorite,
                    onClick = { onMangaClick(manga) },
                    onLongClick = { onMangaLongClick(manga) },
                    isSelected = false,
                )
            }
        }
        if (loading != null) {
            item {
                RelatedMangaLoadingItem()
            }
        }
    }
}

@Composable
private fun RelatedMangaGroupLabel(label: String) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .aspectRatio(MangaCover.Book.ratio)
            .padding(horizontal = MaterialTheme.padding.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
    }
}

@Composable
fun RelatedMangaLoadingItem() {
    Box(
        modifier = Modifier
            .width(96.dp)
            .aspectRatio(MangaCover.Book.ratio)
            .padding(vertical = MaterialTheme.padding.medium),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .align(Alignment.Center),
            strokeWidth = 2.dp,
        )
    }
}
