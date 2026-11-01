package eu.kanade.tachiyomi.ui.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.diagnostics.DiagKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KMK (r97): Small building blocks shared by the Diagnostics tabs: the card every block sits in (plain M3 theme,
 * cards on the screen background), section headers, mono figures, dots, and the number / time formatters, so the
 * same value reads the same on every tab.
 */

/** The `diagnostics` preferences file (contract §3.9); F only touches LAST_TAB, SEEN_VERSION, LIVE_*, FLOAT_* */
internal fun diagPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(DiagKeys.PREFS_FILE, Context.MODE_PRIVATE)

@Composable
internal fun DiagCard(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(padding.dp), content = content)
    }
}

/** "Title   right-hand caption" row at the top of a card. */
@Composable
internal fun CardHeader(
    title: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (trailing != null) trailing()
    }
}

@Composable
internal fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        lineHeight = (size.value * 1.3f).sp,
        color = color,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun Dot(color: Color, size: Int = 10, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
internal fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
}

/** The small coloured badge of an event kind ("STALL", "LMK", "PROBE"). */
@Composable
internal fun KindBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A key / value row of a details table ("Ended    today 13:41 · low memory"). */
@Composable
internal fun KeyValueRow(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Mono(value, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

/** A line of legend entries that wraps: swatch · label · mono value. */
@Composable
internal fun LegendItem(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Swatch(color)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Mono(value, weight = FontWeight.Medium)
    }
}

// --- formatters ---------------------------------------------------------------------------------------------------

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "…"
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    bytes < 1_024L * 1_024L * 1_024L -> String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1_024.0 * 1_024.0 * 1_024.0))
}

internal fun formatCount(n: Int): String = formatCount(n.toLong())

internal fun formatCount(n: Long): String = String.format(Locale.US, "%,d", n)

/** m:ss (or h:mm:ss past an hour) from a duration in ms. */
internal fun formatClock(ms: Long): String {
    val s = (ms / 1_000L).coerceAtLeast(0L)
    val h = s / 3_600L
    val m = (s % 3_600L) / 60L
    val sec = s % 60L
    return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(
        Locale.US,
        "%d:%02d",
        m,
        sec,
    )
}

/** "2 h 14 min", "6 min 12 s", "14 s" */
internal fun formatDuration(ms: Long): String {
    val s = (ms / 1_000L).coerceAtLeast(0L)
    return when {
        s >= 3_600L -> "${s / 3_600L} h ${(s % 3_600L) / 60L} min"
        s >= 60L -> "${s / 60L} min ${s % 60L} s"
        else -> "$s s"
    }
}

/** "14 s ago", "2 min ago", "3 h ago", "2 d ago" */
internal fun formatAgo(timeMs: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - timeMs) / 1_000L).coerceAtLeast(0L)
    return when {
        s < 60L -> "$s s ago"
        s < 3_600L -> "${s / 60L} min ago"
        s < 86_400L -> "${s / 3_600L} h ago"
        else -> "${s / 86_400L} d ago"
    }
}

/** "23 h" / "4 h" / "50 min" until [atMs] */
internal fun formatUntil(atMs: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((atMs - now) / 60_000L).coerceAtLeast(0L)
    return if (m >= 60L) "${m / 60L} h" else "$m min"
}

private val hms = ThreadLocal<SimpleDateFormat>()
private val hm = ThreadLocal<SimpleDateFormat>()
private val hmsMillis = ThreadLocal<SimpleDateFormat>()
private val dayOfYear = ThreadLocal<SimpleDateFormat>()

private fun fmt(holder: ThreadLocal<SimpleDateFormat>, pattern: String): SimpleDateFormat =
    holder.get() ?: SimpleDateFormat(pattern, Locale.US).also(holder::set)

internal fun formatHms(timeMs: Long): String = fmt(hms, "HH:mm:ss").format(Date(timeMs))

internal fun formatHm(timeMs: Long): String = fmt(hm, "HH:mm").format(Date(timeMs))

internal fun formatHmsMillis(timeMs: Long): String = fmt(hmsMillis, "HH:mm:ss.SSS").format(Date(timeMs))

/** "Fri 13:38", or "today 13:41" when the day is today */
internal fun formatDayHm(timeMs: Long, now: Long = System.currentTimeMillis()): String {
    val day = fmt(dayOfYear, "yyyyDDD")
    val sameDay = day.format(Date(timeMs)) == day.format(Date(now))
    return if (sameDay) "today ${formatHm(timeMs)}" else SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(timeMs))
}

internal fun formatMb(mb: Float): String =
    if (mb >= 100f) String.format(Locale.US, "%.0f", mb) else String.format(Locale.US, "%.1f", mb)

/** "0.3 ms/event", "< 0.1 ms/event" */
internal fun formatMsPerEvent(ms: Float): String =
    if (ms > 0f && ms < 0.1f) "< 0.1 ms/event" else String.format(Locale.US, "%.1f ms/event", ms)
