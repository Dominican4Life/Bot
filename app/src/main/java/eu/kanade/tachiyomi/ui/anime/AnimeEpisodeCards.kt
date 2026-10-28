package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.anime.anilist.AniListEpisodeInfo
import eu.kanade.tachiyomi.ui.anime.anilist.AniListEpisodeMatch
import eu.kanade.tachiyomi.ui.anime.anilist.AniListMedia
import kotlinx.coroutines.delay

private val FillerGold = Color(0xFFFACC15)

/**
 * KMK: An episode as a card like the AniList page's (Hayase): the picture with the minutes and ★ rating on it, the
 * number and title, the summary, then the date / progress line with the download button. Filler episodes get a gold
 * frame. Seen episodes are dimmed; a selected one is framed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeEpisodeCard(
    title: String,
    image: Any?,
    minutes: Int?,
    rating: String?,
    summary: String?,
    info: String?,
    filler: Boolean,
    seen: Boolean,
    bookmark: Boolean,
    selected: Boolean,
    downloadState: Download.State,
    downloadProgress: Int,
    onDownload: (ChapterDownloadAction) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (seen) 0.5f else 1f
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier
            .height(112.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = when {
            selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            filler -> BorderStroke(1.5.dp, FillerGold)
            else -> null
        },
    ) {
        Row(modifier = Modifier.alpha(alpha)) {
            if (image != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(16f / 10f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    minutes?.let { Badge("${it}m", modifier = Modifier.align(Alignment.BottomStart)) }
                    rating?.let { Badge(it, star = true, modifier = Modifier.align(Alignment.BottomEnd)) }
                    if (filler) {
                        Text(
                            text = "Filler",
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(FillerGold, RoundedCornerShape(bottomEnd = 6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = summary.orEmpty(),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bookmark) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = info.orEmpty(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ChapterDownloadIndicator(
                        enabled = true,
                        downloadStateProvider = { downloadState },
                        downloadProgressProvider = { downloadProgress },
                        onClick = onDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, star: Boolean = false) {
    Row(
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (star) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = FillerGold, modifier = Modifier.size(10.dp))
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(text = text, color = Color.White, fontSize = 9.sp)
    }
}

/** KMK: "AniList: Title · Change" above the episode cards, or what's happening with the lookup */
@Composable
internal fun AniListEpisodeInfoLine(
    info: AniListEpisodeInfo?,
    loading: Boolean,
    checked: Boolean,
    onChange: () -> Unit,
) {
    val text = when {
        info != null -> "Episode info: AniList · ${info.title}" +
            (if (info.offset > 0) " (from episode ${info.offset + 1})" else "")
        loading -> "Looking for this anime on AniList…"
        checked -> "No AniList episode info. The source's own is shown"
        else -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!loading) {
            TextButton(onClick = onChange) { Text("Change") }
        }
    }
}

/**
 * KMK: Picks the AniList entry that gives this anime's episode pictures and summaries: a search (starting with the
 * anime's title), "Find it again" to go back to the automatic match, or "Don't use AniList".
 */
@Composable
internal fun EpisodeInfoDialog(
    animeTitle: String,
    current: AniListEpisodeInfo?,
    onDismissRequest: () -> Unit,
    onPick: (Int?) -> Unit,
    onAuto: () -> Unit,
) {
    var query by remember { mutableStateOf(animeTitle) }
    var results by remember { mutableStateOf<List<AniListMedia>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(400)
        results = null
        error = null
        try {
            results = AniListEpisodeMatch.search(query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            results = emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Episode info from AniList") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                current?.let {
                    Text(
                        text = "Now: ${it.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search AniList") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = results
                when {
                    list == null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    list.isEmpty() -> Text(
                        text = error?.let { "Couldn't search AniList: $it" } ?: "Nothing found",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { media ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(media.id) }
                                    .padding(vertical = 6.dp),
                            ) {
                                AsyncImage(
                                    model = media.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(40.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                ) {
                                    Text(
                                        text = media.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (media.id == current?.anilistId) FontWeight.Bold else null,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            media.format?.replace('_', ' '),
                                            media.seasonYear?.toString() ?: media.startYear?.toString(),
                                            media.episodes?.let { "$it episodes" },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Don't use AniList") }
                TextButton(onClick = onAuto) { Text("Find it again") }
            }
        },
    )
}
