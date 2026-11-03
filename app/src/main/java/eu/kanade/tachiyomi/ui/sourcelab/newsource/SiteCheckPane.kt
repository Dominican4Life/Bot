package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.sitecheck.SiteCheck
import eu.kanade.tachiyomi.ui.sourcelab.StripColor
import eu.kanade.tachiyomi.ui.sourcelab.tint

/**
 * KMK (r115, mockup ESiteCheck): the Site check as it runs and its result: "Checking <host>" (one row per check, ✓ ! ✕
 * … ○ with a figure), "The plan" (every way of reading the site, the suggested one first and outlined, one action
 * each) and "What's left for you" (the checks that came out ! or ✕). [wide]: the checklist and the plan side by side.
 */
@Composable
internal fun SiteCheckPane(
    result: SiteCheck.Result?,
    wide: Boolean,
    onPlan: (SiteCheck.Plan) -> Unit,
    onMarkers: (() -> Unit)?,
    onTemplate: () -> Unit,
    onRecord: () -> Unit,
    onPageSource: () -> Unit,
    onAgain: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Remembered first, whatever shows below (Compose rule)
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(leftScroll)) {
                CheckingCard(result, onAgain, onSkip)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rightScroll),
            ) {
                PlanCard(result, onPlan, onMarkers, onTemplate, onRecord, onPageSource)
                LeftCard(result)
            }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier.verticalScroll(leftScroll).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CheckingCard(result, onAgain, onSkip)
            PlanCard(result, onPlan, onMarkers, onTemplate, onRecord, onPageSource)
            LeftCard(result)
        }
    }
}

@Composable
private fun CheckCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CheckingCard(result: SiteCheck.Result?, onAgain: () -> Unit, onSkip: () -> Unit) {
    val host = result?.url?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/').orEmpty()
    CheckCard(if (result?.running == false) "Checked $host" else "Checking ${host.ifBlank { "the site" }}") {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { result?.progress ?: 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(6.dp))
        result?.items.orEmpty().forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                MarkDot(item.mark)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    if (item.note.isNotBlank()) {
                        Text(
                            item.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.mark == SiteCheck.Mark.Fail) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (item.figure.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.figure,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
        if (result?.running == false) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                TextButton(onClick = onSkip) { Text("Skip") }
                TextButton(onClick = onAgain) { Text("Check again") }
            }
        }
    }
}

/** ✓ green, ! amber, ✕ red, a spinner while running, ○ skipped */
@Composable
private fun MarkDot(mark: SiteCheck.Mark) {
    val color = when (mark) {
        SiteCheck.Mark.Ok -> StripColor.Green
        SiteCheck.Mark.Warn -> StripColor.Amber
        SiteCheck.Mark.Fail -> StripColor.Red
        SiteCheck.Mark.Running -> StripColor.Blue
        SiteCheck.Mark.Skipped -> StripColor.Neutral
    }.tint()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
    ) {
        if (mark == SiteCheck.Mark.Running) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Text(
                when (mark) {
                    SiteCheck.Mark.Ok -> "✓"
                    SiteCheck.Mark.Warn -> "!"
                    SiteCheck.Mark.Fail -> "✕"
                    else -> "○"
                },
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PlanCard(
    result: SiteCheck.Result?,
    onPlan: (SiteCheck.Plan) -> Unit,
    onMarkers: (() -> Unit)?,
    onTemplate: () -> Unit,
    onRecord: () -> Unit,
    onPageSource: () -> Unit,
) {
    CheckCard("The plan") {
        if (result == null || result.running) {
            Text(
                "Picked from what the check finds. About 10 s.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@CheckCard
        }
        Text(
            "Picked for you from what the check found. You can still change any part.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        result.plans.forEachIndexed { index, plan ->
            PlanRow(index + 1, plan)
            if (plan.works) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 10.dp),
                ) {
                    val main = when (plan.kind) {
                        SiteCheck.PlanKind.Template -> "Write the source"
                        SiteCheck.PlanKind.Data -> "Page source"
                        SiteCheck.PlanKind.Browser, SiteCheck.PlanKind.Html -> "Build by tapping"
                    }
                    if (plan.recommended) {
                        Button(onClick = { onPlan(plan) }) { Text("Use this plan · $main") }
                    } else {
                        OutlinedButton(onClick = { onPlan(plan) }) { Text(main) }
                    }
                    when (plan.kind) {
                        SiteCheck.PlanKind.Template -> if (onMarkers != null) TextButton(onClick = onMarkers) { Text("See the markers") }
                        SiteCheck.PlanKind.Data -> {
                            TextButton(onClick = onTemplate) { Text("A template") }
                            TextButton(onClick = onRecord) { Text("Record and convert") }
                        }
                        SiteCheck.PlanKind.Browser, SiteCheck.PlanKind.Html -> TextButton(onClick = onPageSource) { Text("Page source") }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PlanRow(number: Int, plan: SiteCheck.Plan) {
    val colors = MaterialTheme.colorScheme
    val green = StripColor.Green.tint()
    val red = StripColor.Red.tint()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (plan.recommended) colors.primaryContainer.copy(alpha = 0.35f) else colors.surfaceContainerHigh,
        border = if (plan.recommended) BorderStroke(1.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background((if (plan.works) green else red).copy(alpha = 0.18f)),
            ) {
                Text(
                    if (plan.works) number.toString() else "✕",
                    color = if (plan.works) green else red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    plan.title + if (plan.recommended) " (recommended)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (plan.works) colors.onSurface else colors.onSurfaceVariant,
                )
                Text(plan.body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                if (plan.chips.isNotEmpty() && plan.works) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        plan.chips.forEach { chip ->
                            Surface(shape = RoundedCornerShape(8.dp), color = green.copy(alpha = 0.16f)) {
                                Text(chip, fontSize = 12.sp, color = green, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftCard(result: SiteCheck.Result?) {
    if (result == null || result.running) return
    val left = result.left
    CheckCard("What's left for you") {
        if (left.isEmpty()) {
            Text(
                "Nothing: every check passed. Build by tapping still asks for the picks a machine can't guess.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        left.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                MarkDot(item.mark)
                Spacer(Modifier.width(12.dp))
                Text(
                    item.title + if (item.note.isNotBlank()) ": ${item.note}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.mark == SiteCheck.Mark.Fail) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
