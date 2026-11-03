package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.videodoctor.AskedKind
import eu.kanade.tachiyomi.jssource.lab.videodoctor.AskedRequest
import eu.kanade.tachiyomi.jssource.lab.videodoctor.AskedSide
import eu.kanade.tachiyomi.jssource.lab.videodoctor.CauseVerdict
import eu.kanade.tachiyomi.jssource.lab.videodoctor.ScrambleKind
import eu.kanade.tachiyomi.jssource.lab.videodoctor.Scrambled
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoCause
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoDiagnosis
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoDoctor
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoFix
import java.util.Locale

/**
 * KMK (r115): "Why won't it play" in the Test Run's Video step (mockup XPlay): the four causes (the ones found
 * lit), what the player asked for (status, short address, kind, which side sent it), the header difference, the
 * hidden address when one was read, and the fixes. It only shows [diagnosis] and hands a [VideoFix] to [onFix]
 * (the host screen writes it into the source); [onTryPlay] plays an address with headers (the host's player).
 * [onCheck] starts (or re-runs) the check; [running] with [progress] shows it working.
 */
@Composable
internal fun VideoDoctorPanel(
    diagnosis: VideoDiagnosis?,
    running: Boolean,
    onCheck: () -> Unit,
    onFix: (VideoFix) -> Unit,
    onTryPlay: (url: String, headers: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    progress: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DoctorCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Why won't it play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Box(modifier = Modifier.weight(1f))
                if (diagnosis != null && !running) {
                    Text(
                        metaLine(diagnosis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onCheck, enabled = !running) {
                    Text(if (diagnosis == null) "Check why" else "Check again")
                }
            }
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    progress ?: "Running the source's video part, then the hidden browser…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                diagnosis == null && !running -> Text(
                    "\"It doesn't play\" has four very different causes. This runs the source's video part, then the " +
                        "hidden browser, and names which one it is with the requests that prove it. DRM is only named, " +
                        "never worked around.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diagnosis != null -> CauseRow(diagnosis.causes)
            }
            if (diagnosis != null && diagnosis.sourcePlayed && !diagnosis.anyHit) {
                Text(
                    "The source's own video played: nothing to fix here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StripColor.Green.tint(),
                )
            }
        }
        if (diagnosis != null) {
            val scrambled = diagnosis.scrambled?.takeIf { diagnosis.hit(VideoCause.ScrambledLink) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DoctorCard(modifier = Modifier.weight(1.25f)) {
                    RequestsCard(diagnosis)
                }
                if (scrambled != null) {
                    DoctorCard(modifier = Modifier.weight(1f)) {
                        ScrambledCard(scrambled)
                    }
                }
            }
            FixCard(diagnosis, onFix, onTryPlay)
        }
    }
}

// ---------- the four causes ----------

@Composable
private fun CauseRow(causes: List<CauseVerdict>) {
    val amber = StripColor.Amber.tint()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        VideoCause.entries.forEach { cause ->
            val verdict = causes.firstOrNull { it.cause == cause }
            val hit = verdict?.hit == true
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (hit) amber.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, if (hit) amber else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        causeTitle(cause) + if (hit) " ✓ found" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (hit) amber else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        verdict?.line.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (hit) 1f else 0.7f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun causeTitle(cause: VideoCause): String = when (cause) {
    VideoCause.NoStream -> "No stream found"
    VideoCause.ScrambledLink -> "Scrambled link"
    VideoCause.BlockedRequest -> "Blocked request"
    VideoCause.Drm -> "DRM"
}

private fun metaLine(d: VideoDiagnosis): String {
    val where = if (d.browserUsed) "checked in the hidden browser · Play pressed" else "checked with the source's client"
    return "$where · ${String.format(Locale.US, "%.1f", d.tookMs / 1000.0)} s"
}

// ---------- what the player asked for ----------

private const val MAX_ROWS = 14

/** The rows worth showing: the source's and the replays first, the browser's pages and pieces kept short */
private fun shownRequests(all: List<AskedRequest>): List<AskedRequest> {
    var browserPieces = 0
    var browserPages = 0
    val kept = all.filter { r ->
        when {
            r.kind == AskedKind.Other -> false
            r.side == AskedSide.Browser && r.kind == AskedKind.Segment -> ++browserPieces <= 1
            r.side == AskedSide.Browser && r.kind == AskedKind.Page -> ++browserPages <= 2
            else -> true
        }
    }
    // The refused ones and their browser twins must stay when the list is cut
    val important = kept.filter { it.refused || it.side == AskedSide.ReplayBrowser || it.kind == AskedKind.License }
    return (important + kept).distinct().let { list -> kept.filter { it in list.take(MAX_ROWS) } }
}

@Composable
private fun ColumnScope.RequestsCard(d: VideoDiagnosis) {
    Text("What the player asked for", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    val rows = shownRequests(d.requests)
    if (rows.isEmpty()) {
        Text(
            d.browserReason ?: "No request for a player page or a video was seen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    rows.forEachIndexed { index, r ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 3.dp),
        ) {
            StatusChip(r)
            Text(
                VideoDoctor.shortUrl(r.url),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                listOfNotNull(r.kind.words, r.detail, r.side.words).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.width(220.dp),
            )
        }
    }
    d.headerLine?.let { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    d.drm?.let { drm ->
        Text(
            "DRM: ${drm.systems.joinToString(", ")} · ${drm.via}" +
                (drm.licenseUrl?.let { " · licence ${VideoDoctor.shortUrl(it)}" } ?: "") +
                ". Named only; the app doesn't play protected video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusChip(r: AskedRequest) {
    val green = StripColor.Green.tint()
    val red = StripColor.Red.tint()
    val (label, color) = when {
        r.error != null && r.status == null -> "err" to red
        r.status == null -> "ok" to MaterialTheme.colorScheme.onSurfaceVariant
        r.ok -> r.status.toString() to green
        else -> r.status.toString() to red
    }
    Text(
        label,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------- the hidden address ----------

@Composable
private fun ColumnScope.ScrambledCard(s: Scrambled) {
    Text("Scrambled link", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    MonoBox(s.blobShort, MaterialTheme.colorScheme.onSurface)
    val how = when (s.kind) {
        ScrambleKind.AesPassphrase, ScrambleKind.AesKey ->
            "↓ AES with the key found in ${if (s.where.endsWith(".js")) "the player script" else "the page"}" +
                (s.keyLine?.let { " (line $it)" } ?: "")
        ScrambleKind.Base64 -> if (s.key == "reversed") "↓ reversed, then base64" else "↓ base64"
        ScrambleKind.Packed -> "↓ unpacked (p.a.c.k.e.r)" + (s.keyLine?.let { " (line $it)" } ?: "")
    }
    Text(
        how,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    MonoBox(s.result, StripColor.Green.tint())
    Text(
        (if (s.confirmed) "The hidden browser asked for this same address. " else "") +
            "Reading it here means the source doesn't need the hidden browser for each episode.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MonoBox(text: String, color: Color) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = color,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

// ---------- the fixes ----------

private fun fixLabel(fix: VideoFix): String = when (fix) {
    is VideoFix.DecryptWithKey -> when (fix.method) {
        "aes-passphrase", "aes-key" -> "Decrypt with the key from the page"
        "packed" -> "Unpack the player script"
        else -> "Read the hidden link from the page"
    }
    is VideoFix.AddHeaders -> {
        val names = fix.headers.keys.map { if (it.equals("Cookie", ignoreCase = true)) "cookie" else it }
        "Add " + names.joinToString(" + ") + " to the video"
    }
    VideoFix.UseHiddenBrowser -> "Use the hidden browser for this source"
}

@Composable
private fun FixCard(
    d: VideoDiagnosis,
    onFix: (VideoFix) -> Unit,
    onTryPlay: (url: String, headers: Map<String, String>) -> Unit,
) {
    val drmOnly = d.hit(VideoCause.Drm) && d.causes.count { it.hit } == 1
    if (d.fixes.isEmpty() && (d.tryUrl == null || drmOnly)) return
    DoctorCard {
        Text("Fix it", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (d.fixes.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.fixes.forEachIndexed { index, fix ->
                    if (index == 0) {
                        Button(onClick = { onFix(fix) }) { Text(fixLabel(fix)) }
                    } else {
                        OutlinedButton(onClick = { onFix(fix) }) { Text(fixLabel(fix)) }
                    }
                }
            }
        }
        val tryUrl = d.tryUrl
        if (tryUrl != null && !drmOnly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val top = MaterialTheme.colorScheme.primaryContainer
                val bottom = MaterialTheme.colorScheme.surfaceContainerLowest
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(200.dp)
                        .height(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(top, bottom)))
                        .clickable { onTryPlay(tryUrl, d.tryHeaders) },
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Try play",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Try-play with the fixes applied, right here, before you save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        VideoDoctor.shortUrl(tryUrl) +
                            if (d.tryHeaders.isNotEmpty()) " · " + d.tryHeaders.keys.joinToString(", ") else "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------- the card ----------

@Composable
private fun DoctorCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
