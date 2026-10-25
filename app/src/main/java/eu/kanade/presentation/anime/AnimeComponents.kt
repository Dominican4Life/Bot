package eu.kanade.presentation.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Anime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: Cover image of an anime, loaded with the source's headers (some sites need a referer). */
@Composable
fun AnimeCoverImage(
    url: String?,
    source: AnimeSource?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(url, source?.id) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .apply {
                val headers = (source as? AnimeHttpSource)?.headers
                    ?: (source as? eu.kanade.tachiyomi.jssource.JsAnimeSource)?.headers
                if (headers != null) {
                    httpHeaders(
                        NetworkHeaders.Builder().apply {
                            headers.forEach { (name, value) -> add(name, value) }
                        }.build(),
                    )
                }
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** KMK: A cover with the title over a dark gradient, like the manga browse grid. */
@Composable
fun AnimeGridItem(
    anime: Anime,
    source: AnimeSource?,
    onClick: () -> Unit,
    isSelected: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(shape)
            .then(
                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
            )
            .clickable(onClick = onClick),
    ) {
        AnimeCoverImage(url = anime.thumbnailUrl, source = source, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        ) {
            Text(
                text = anime.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (anime.favorite) {
            Text(
                text = stringResource(MR.strings.in_library),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * KMK: Filters of an anime source. The filters are changed in place; [onFilter] runs the search.
 */
@Composable
fun AnimeFilterDialog(
    filters: AnimeFilterList,
    onDismissRequest: () -> Unit,
    onReset: () -> Unit,
    onFilter: () -> Unit,
) {
    // Filters are plain objects, so bump this to redraw after changing one
    var revision by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onFilter()
                onDismissRequest()
            }) { Text(stringResource(MR.strings.action_filter)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onReset()
                revision++
            }) { Text(stringResource(MR.strings.action_reset)) }
        },
        text = {
            @Suppress("UNUSED_EXPRESSION")
            revision
            LazyColumn {
                items(filters.list) { filter ->
                    FilterItem(filter = filter, onChanged = { revision++ })
                }
            }
        },
    )
}

@Composable
private fun FilterItem(filter: AnimeFilter<*>, onChanged: () -> Unit) {
    when (filter) {
        // KMK: a blank header is a hidden holder (a JS source's selected tab), not a title
        is AnimeFilter.Header -> if (filter.name.isNotBlank()) Text(
            text = filter.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        is AnimeFilter.Separator -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        is AnimeFilter.CheckBox -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                filter.state = !filter.state
                onChanged()
            },
        ) {
            Checkbox(checked = filter.state, onCheckedChange = null)
            Text(filter.name, modifier = Modifier.padding(start = 8.dp))
        }
        is AnimeFilter.TriState -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                filter.state = (filter.state + 1) % 3
                onChanged()
            },
        ) {
            TriStateCheckbox(
                state = when (filter.state) {
                    AnimeFilter.TriState.STATE_INCLUDE -> ToggleableState.On
                    AnimeFilter.TriState.STATE_EXCLUDE -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                },
                onClick = null,
            )
            Text(filter.name, modifier = Modifier.padding(start = 8.dp))
        }
        is AnimeFilter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = {
                filter.state = it
                onChanged()
            },
            label = { Text(filter.name) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        is AnimeFilter.Select<*> -> Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(filter.name, style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Values can be many; show them wrapped as chips
            }
            FlowChips(
                labels = filter.values.map { it.toString() },
                selected = filter.state,
                onSelect = {
                    filter.state = it
                    onChanged()
                },
            )
        }
        is AnimeFilter.Sort -> Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(filter.name, style = MaterialTheme.typography.bodyMedium)
            filter.values.forEachIndexed { index, value ->
                val selection = filter.state
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        filter.state = AnimeFilter.Sort.Selection(
                            index,
                            if (selection?.index == index) !selection.ascending else false,
                        )
                        onChanged()
                    },
                ) {
                    RadioButton(selected = selection?.index == index, onClick = null)
                    Text(
                        text = value + when {
                            selection?.index != index -> ""
                            selection.ascending -> "  ↑"
                            else -> "  ↓"
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        // KMK: a group opens and closes like in the manga filter sheet, closed at first
        is AnimeFilter.Group<*> -> Column(modifier = Modifier.padding(vertical = 4.dp)) {
            var expanded by rememberSaveable(filter.name) {
                mutableStateOf(false)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            ) {
                Text(
                    filter.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    filter.state.filterIsInstance<AnimeFilter<*>>().forEach { child ->
                        FilterItem(filter = child, onChanged = onChanged)
                    }
                }
            }
        }
        else -> {}
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}
