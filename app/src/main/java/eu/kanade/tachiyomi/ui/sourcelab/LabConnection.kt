package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.notification.BotChecks
import eu.kanade.tachiyomi.util.system.AppLog

// KMK --> (r116) bot checks in the Source Lab (mock XBot). The app never solves, clicks or bypasses a check: these
// screens only say one is waiting, open the source's check page for the owner (BotChecks.solve), and set the pace

/** "example.tv" from a check's address; the source's name when the address has no host */
internal fun botCheckHost(waiting: BotChecks.Waiting): String =
    runCatching { java.net.URI(waiting.url).host?.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: waiting.name

/**
 * KMK (r116, mock XBot): on the Test pane while [waiting] is registered for the open source: "example.tv is asking
 * for a bot check · N requests waiting" and Solve it (opens the check page in WebView; the owner passes it there and
 * the waiting requests carry on by themselves).
 */
@Composable
internal fun BotCheckCard(waiting: BotChecks.Waiting, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val amber = StripColor.Amber.tint()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = amber.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${botCheckHost(waiting)} is asking for a bot check",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${waiting.pending} ${if (waiting.pending == 1) "request" else "requests"} waiting · " +
                            "they continue by themselves · since ${SourceLabScreenModel.agoText(waiting.since)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        AppLog.add('I', "lab", "Bot check: Solve it for ${waiting.name} (id ${waiting.sourceId})")
                        BotChecks.solve(context, waiting.sourceId)
                    },
                ) { Text("Solve it") }
            }
            Text(
                "The site showed its check page even in the hidden browser, so it needs you. Solve it opens the page; " +
                    "it closes by itself once you're through. Nothing fails meanwhile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The pace choices of "Ask less often" (ms between requests after a check; 0 = no pacing) */
private val PACE_CHOICES = listOf(0L, 1_000L, 1_500L, 3_000L, 5_000L)

/** Covers loaded at a time on the site (0 = no limit) */
private val COVER_CHOICES = listOf(2, 4, 8, 0)

private fun paceWords(ms: Long): String = when {
    ms <= 0L -> "Off"
    ms % 1_000L == 0L -> "${ms / 1_000L} s"
    else -> "%.1f s".format(ms / 1_000.0)
}

/**
 * KMK (r116, mock XBot "This source's session" + "Ask less often"): ⋮ › Connection. What [BotChecks.session] knows
 * about the source [sourceId] (last passed, via the browser page, slowed down until) and the per-source pace
 * ([BotChecks.setPace]). A waiting check shows its Solve it card on top.
 */
@Composable
internal fun ConnectionSheet(sourceId: Long, sourceName: String, onDismiss: () -> Unit) {
    val waitingMap by BotChecks.waiting.collectAsState()
    val waiting = waitingMap[sourceId]
    // session() is a plain read: read again after each change here and when the waiting list changes
    var tick by remember { mutableStateOf(0) }
    val session = remember(sourceId, tick, waitingMap) { runCatching { BotChecks.session(sourceId) }.getOrNull() }
    val pace = session?.paceMs ?: 1_500L
    val covers = session?.coverLimit ?: 4
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                "Connection · ${sourceName.ifBlank { "this source" }}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (waiting != null) BotCheckCard(waiting)
            LabCaption("This source's session")
            val now = System.currentTimeMillis()
            SessionRow(
                "Last passed",
                session?.lastPassedAt?.let { SourceLabScreenModel.agoText(it) + ", by you" } ?: "no check passed yet",
            )
            SessionRow(
                "Requests",
                if (session?.viaBrowser == true) "go through a browser page on the site" else "go straight to the site",
            )
            SessionRow(
                "Kept",
                if (session?.lastPassedAt != null) "the site's cookie + the same browser identity" else "nothing yet",
            )
            SessionRow(
                "Slowed down",
                session?.slowUntil?.takeIf { it > now }?.let { "for ${((it - now) / 60_000L).coerceAtLeast(1L)} more min" } ?: "no",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            LabCaption("Ask less often")
            Text(
                "Pace after a check or a \"Too many requests\": one request every ${paceWords(pace).lowercase()} for 10 min. " +
                    "\"Too many requests\" waits the time the site asks, then retries once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PACE_CHOICES.forEach { ms ->
                    FilterChip(
                        selected = pace == ms,
                        onClick = {
                            BotChecks.setPace(sourceId, ms, covers)
                            AppLog.add('I', "lab", "Connection of $sourceName: pace ${paceWords(ms)}, covers $covers")
                            tick++
                        },
                        label = { Text(paceWords(ms)) },
                    )
                }
            }
            Text("Covers at a time on this site", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COVER_CHOICES.forEach { n ->
                    FilterChip(
                        selected = covers == n,
                        onClick = {
                            BotChecks.setPace(sourceId, pace, n)
                            AppLog.add('I', "lab", "Connection of $sourceName: pace ${paceWords(pace)}, covers $n")
                            tick++
                        },
                        label = { Text(if (n <= 0) "No limit" else n.toString()) },
                    )
                }
            }
            Text(
                "The app never solves a check for you: it asks you once, waits instead of failing, carries on with the " +
                    "session your pass gave, and slows down so the site asks less often. Shared with WebView, Browse " +
                    "and downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(name: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// KMK <--
