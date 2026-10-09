package eu.kanade.presentation.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One title's new chapters on one day */
data class UpdatesFeedGroup(
    val key: String,
    val date: LocalDate,
    val items: List<UpdatesItem>,
) {
    val leader: UpdatesItem get() = items.first()
    val newCount: Int get() = items.count { !it.update.read }
    val selected: Boolean get() = items.all { it.selected }

    /** The chapter "Continue" opens: the oldest unread one, else the newest */
    val continueItem: UpdatesItem
        get() = items.lastOrNull { !it.update.read } ?: items.first()
}

sealed interface UpdatesFeedEntry {
    data class Header(val date: LocalDate, val count: Int) : UpdatesFeedEntry
    data class Group(val group: UpdatesFeedGroup, val isLastOfDay: Boolean) : UpdatesFeedEntry
}

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun buildUpdatesFeed(items: List<UpdatesItem>): Pair<List<UpdatesFeedEntry>, List<UpdatesFeedGroup>> {
    val groups = items
        .groupBy { it.update.dateFetch.toLocalDate() }
        .map { (date, dayItems) ->
            date to dayItems.groupBy { it.update.mangaId }.map { (mangaId, list) ->
                UpdatesFeedGroup("$date-$mangaId", date, list)
            }
        }
    val entries = groups.flatMap { (date, dayGroups) ->
        listOf(UpdatesFeedEntry.Header(date, dayGroups.size)) +
            dayGroups.mapIndexed { index, group -> UpdatesFeedEntry.Group(group, index == dayGroups.lastIndex) }
    }
    return entries to groups.flatMap { it.second }
}

/**
 * KMK: Kototoro-style Updates: a summary card ("N works · M chapters", check for updates, all read), an "Updated"
 * cover carousel, then a timeline with one row per title per day.
 */
internal fun LazyListScope.updatesTimeline(
    items: List<UpdatesItem>,
    showCarousel: Boolean,
    selectionMode: Boolean,
    onUpdateLibrary: () -> Unit,
    onAllRead: () -> Unit,
    onClickGroup: (UpdatesFeedGroup) -> Unit,
    onSelectGroup: (UpdatesFeedGroup) -> Unit,
    onContinue: (UpdatesFeedGroup) -> Unit,
) {
    val (entries, groups) = buildUpdatesFeed(items)
    val unread = items.count { !it.update.read }
    val unreadWorks = groups.filter { it.newCount > 0 }.map { it.leader.update.mangaId }.toSet().size

    item(key = "feed-header") {
        UpdatesHeaderCard(
            works = unreadWorks,
            chapters = unread,
            onUpdateLibrary = onUpdateLibrary,
            onAllRead = onAllRead,
        )
    }
    if (showCarousel) {
        val featured = groups.filter { it.newCount > 0 }
            .groupBy { it.leader.update.mangaId }
            .map { (_, list) -> list.first() to list.sumOf { it.newCount } }
            .sortedByDescending { it.second }
            .take(12)
        if (featured.isNotEmpty()) {
            item(key = "feed-carousel") {
                UpdatedCarousel(featured = featured, onClick = onClickGroup)
            }
        }
    }
    entries.forEach { entry ->
        when (entry) {
            is UpdatesFeedEntry.Header -> item(key = "feed-day-${entry.date}") {
                TimelineDateRow(date = entry.date, count = entry.count)
            }
            is UpdatesFeedEntry.Group -> item(key = "feed-${entry.group.key}") {
                TimelineRow(
                    group = entry.group,
                    isLast = entry.isLastOfDay,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) onSelectGroup(entry.group) else onClickGroup(entry.group)
                    },
                    onLongClick = { onSelectGroup(entry.group) },
                    onContinue = { onContinue(entry.group) },
                )
            }
        }
    }
}

@Composable
private fun UpdatesHeaderCard(
    works: Int,
    chapters: Int,
    onUpdateLibrary: () -> Unit,
    onAllRead: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.NewReleases,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = stringResource(if (chapters > 0) KMR.strings.updates_header_updated else KMR.strings.updates_header_up_to_date),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = if (chapters > 0) {
                    stringResource(KMR.strings.updates_header_counts, works, chapters)
                } else {
                    stringResource(KMR.strings.updates_header_nothing_new)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        if (chapters > 0) {
            IconButton(onClick = onUpdateLibrary) {
                Icon(
                    Icons.Outlined.Sync,
                    contentDescription = stringResource(MR.strings.action_update_library),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Button(onClick = onAllRead, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Icon(Icons.Outlined.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(KMR.strings.updates_all_read), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Button(onClick = onUpdateLibrary, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Text(stringResource(KMR.strings.updates_check_now), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun UpdatedCarousel(
    featured: List<Pair<UpdatesFeedGroup, Int>>,
    onClick: (UpdatesFeedGroup) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = stringResource(KMR.strings.updates_carousel_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(featured, key = { it.first.key }) { (group, count) ->
                val first = group == featured.first().first
                val width = if (first) 150.dp else 112.dp
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(if (first) 212.dp else 168.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = { onClick(group) }),
                ) {
                    AsyncImage(
                        model = group.leader.update.coverData,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0.45f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = if (first) 0.82f else 0.74f),
                                ),
                            ),
                    )
                    Text(
                        text = "+$count",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = group.leader.update.mangaTitle,
                            style = if (first) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = if (first) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pluralStringResource(KMR.plurals.updates_new_chapters, count, count),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

private val RailWidth = 28.dp
private val NodeSize = 10.dp

@Composable
private fun TimelineDateRow(date: LocalDate, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    ) {
        Text(
            text = relativeDateText(date),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Text(
            text = pluralStringResource(KMR.plurals.updates_day_count, count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun TimelineRow(
    group: UpdatesFeedGroup,
    isLast: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onContinue: () -> Unit,
) {
    val update = group.leader.update
    val isNew = group.newCount > 0
    val primary = MaterialTheme.colorScheme.primary
    val nodeColor = if (isNew) primary else primary.copy(alpha = 0.66f)
    val surface = MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(group.selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawBehind {
                // The rail: a line down the left with a ring on each title
                val x = 12.dp.toPx() + RailWidth.toPx() / 2
                val cy = size.height / 2
                drawLine(
                    color = nodeColor.copy(alpha = 0.28f),
                    start = Offset(x, 0f),
                    end = Offset(x, if (isLast) cy else size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                val r = NodeSize.toPx() / 2
                drawCircle(color = surface, radius = r, center = Offset(x, cy))
                drawCircle(color = nodeColor, radius = r - 1.5.dp.toPx(), center = Offset(x, cy), style = Stroke(3.dp.toPx()))
                if (isNew) drawCircle(color = nodeColor, radius = r / 3, center = Offset(x, cy))
            }
            .padding(start = 12.dp + RailWidth + 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 60.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = update.coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (group.selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White)
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                text = update.mangaTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (isNew) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = if (isNew) {
                        pluralStringResource(KMR.plurals.updates_new_chapters, group.newCount, group.newCount)
                    } else {
                        pluralStringResource(KMR.plurals.updates_chapters_total, group.items.size, group.items.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isNew) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = group.continueItem.update.chapterName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectionMode) {
            FilledTonalIconButton(onClick = onContinue, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = stringResource(MR.strings.action_resume),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
