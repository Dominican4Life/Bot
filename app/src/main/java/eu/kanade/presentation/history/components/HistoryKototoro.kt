package eu.kanade.presentation.history.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.CoverProgressBar
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.util.lang.toTimestampString
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

private fun HistoryWithRelations.readDate(): LocalDate? =
    readAt?.let { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate() }

/** "3h 25m" / "12m" */
private fun formatDuration(millis: Long): String {
    val duration = millis.milliseconds
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/**
 * KMK: Reading summary at the top of History (Kototoro's stats card): total reading time over the most recent
 * cover, a 7-day bar chart of reading time, the current streak, and counts.
 */
@Composable
fun HistorySummaryCard(
    list: List<HistoryWithRelations>,
    totalReadDuration: Long,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val days = remember(list) {
        val byDay = list.groupBy { it.readDate() }
        (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            day to (byDay[day]?.sumOf { it.readDuration.coerceAtLeast(60_000L) } ?: 0L)
        }
    }
    val streak = remember(list) {
        val active = list.mapNotNull { it.readDate() }.toSet()
        var count = 0
        var day = if (today in active) today else today.minusDays(1)
        while (day in active) {
            count++
            day = day.minusDays(1)
        }
        count
    }
    val activeDays = remember(list) { list.mapNotNull { it.readDate() }.toSet().size }
    val works = remember(list) { list.map { it.mangaId }.toSet().size }
    val top = list.firstOrNull()
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        if (top != null) {
            AsyncImage(
                model = top.coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(22.dp) else Modifier),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.74f)),
                        ),
                    ),
            )
        }
        val onCard = if (top != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(KMR.strings.history_summary_reading_time),
                style = MaterialTheme.typography.labelLarge,
                color = onCard.copy(alpha = 0.8f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatDuration(totalReadDuration),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = onCard,
                    modifier = Modifier.weight(1f),
                )
                if (streak > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(onCard.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.LocalFireDepartment,
                            contentDescription = null,
                            tint = Color(0xFFFF9F0A),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(KMR.strings.history_summary_streak, streak),
                            style = MaterialTheme.typography.labelMedium,
                            color = onCard,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 7-day sparkline
            val max = days.maxOf { it.second }.coerceAtLeast(1L)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
            ) {
                days.forEach { (day, millis) ->
                    val fraction = if (millis == 0L) 0.12f else (millis.toFloat() / max).coerceIn(0.2f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (day == today) {
                                    onCard
                                } else {
                                    onCard.copy(alpha = if (millis == 0L) 0.18f else 0.55f)
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(KMR.strings.history_summary_counts, activeDays, list.size, works),
                style = MaterialTheme.typography.bodySmall,
                color = onCard.copy(alpha = 0.85f),
            )
        }
    }
}

/** One title in the History grid: cover with the reading-progress bar, then title and last chapter */
@Composable
fun HistoryGridItem(
    history: HistoryWithRelations,
    progress: Float?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val alpha = if (history.read) DISABLED_ALPHA else 1f
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(4.dp),
    ) {
        val coverShape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(coverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, coverShape) else Modifier,
                ),
        ) {
            AsyncImage(
                model = history.coverData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
            if (history.unreadCount > 0) {
                Text(
                    text = history.unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            CoverProgressBar(percent = progress, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Text(
            text = history.title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        val readAt = remember(history.readAt) { history.readAt?.toTimestampString() ?: "" }
        Text(
            text = if (history.chapterNumber > -1) {
                stringResource(MR.strings.recent_manga_time, formatChapterNumber(history.chapterNumber), readAt)
            } else {
                readAt
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Kototoro's "Clear history" dialog: last 2 hours, today, titles not in the library, or everything */
@Composable
fun HistoryClearDialog(
    onDismissRequest: () -> Unit,
    onClear: (HistoryScreenModel.ClearRange) -> Unit,
) {
    var choice by remember { mutableStateOf(HistoryScreenModel.ClearRange.LAST_2_HOURS) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_clear_history)) },
        text = {
            Column {
                HistoryScreenModel.ClearRange.entries.forEach { range ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == range, onClick = { choice = range })
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = choice == range, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(
                                when (range) {
                                    HistoryScreenModel.ClearRange.LAST_2_HOURS -> KMR.strings.history_clear_2_hours
                                    HistoryScreenModel.ClearRange.TODAY -> KMR.strings.history_clear_today
                                    HistoryScreenModel.ClearRange.NOT_IN_LIBRARY -> KMR.strings.history_clear_not_in_library
                                    HistoryScreenModel.ClearRange.EVERYTHING -> KMR.strings.history_clear_everything
                                },
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClear(choice)
                    onDismissRequest()
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
