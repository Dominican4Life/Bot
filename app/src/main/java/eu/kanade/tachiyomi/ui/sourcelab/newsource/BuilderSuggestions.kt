package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.jssource.lab.Derive

/*
 * KMK (r106, Source Lab, mockups Suggestions / TSuggest): the Suggestions sections of Build by tapping as cards, for the
 * builder's "Title page" tab (W1's sub-view calls [SuggestionsSection]). One card per section tapped with Suggestions
 * armed: in the page itself → ✓; filled in later by the site's script → "How should the source get it?": from the
 * address the site loads it from (found by BuilderLateSections, with what the check saw) or "Read with the browser"
 * (generic-list v9 opens the title page in the hidden browser), plus "Look for the address again". A section's "Load
 * more" button (found by picker.js under it, or tapped with Suggestions armed) gets "Also press it Off / 1× / 2× / 3×"
 * and "Scan it now" (one press in the page; the request that brought the new cards is kept). Each section is its own
 * named row on the title screen, for anime and manga.
 */

/** The mockups' green tick and amber warning */
private val SuggestOk = Color(0xFF7FD99A)
private val SuggestWarn = Color(0xFFF2B33D)

@Composable
internal fun SuggestionsSection(model: BuilderScreenModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val groups = state.suggestionGroups
    val armed = state.armed == "suggestions"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxWidth()) {
        // (the sub-view's head and arm button are BuilderScreen's: this is the cards only)
        if (groups.isEmpty()) {
            SuggestHint(
                if (armed) {
                    "Tap one card of a \u201cYou may like\u201d / \u201cRelated\u201d section on the page."
                } else {
                    "No section yet: arm Suggestions and tap one card of a \u201cYou may like\u201d / \u201cRelated\u201d section."
                },
            )
        }
        groups.forEachIndexed { index, group ->
            key(index, group.item) {
                SectionCard(index, group, busy = state.suggestionBusy == group.item, model = model)
            }
        }
        SuggestHint(
            "Picking the button yourself: not found on its own? With Suggestions armed, tap the section's “Load more” / " +
                "“Show more” button. The card above shows it.",
        )
        SuggestHint("On the title screen each section shows as its own named row, for anime and manga.")
    }
}

@Composable
private fun SuggestHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One section tapped as Suggestions */
@Composable
private fun SectionCard(index: Int, group: Derive.SuggestionGroup, busy: Boolean, model: BuilderScreenModel) {
    val late = group.late.isNotBlank() || group.lateWarning.isNotBlank() || group.read == "browser"
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (late && group.late.isBlank() && group.read != "browser") BorderStroke(1.dp, SuggestWarn.copy(alpha = 0.6f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (late) "!" else "✓",
                    color = if (late) SuggestWarn else SuggestOk,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${group.name} · ${group.count} cards",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (late) {
                            "The site adds this after the page loads, so it isn't in the page the source reads."
                        } else {
                            "In the page itself, so it's read straight from there"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (group.values.isNotEmpty()) {
                        Text(
                            group.values.joinToString(", "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { model.removeSuggestionGroup(index) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                }
            }
            if (late) LateChoice(index, group, busy, model)
            val more = group.more
            if (more != null) LoadMoreBox(index, group, more, busy, model)
        }
    }
}

/** "How should the source get it?": its own address (found or not) / Read with the browser / Look for the address again */
@Composable
private fun LateChoice(index: Int, group: Derive.SuggestionGroup, busy: Boolean, model: BuilderScreenModel) {
    val found = group.late.isNotBlank()
    val browser = group.read == "browser"
    Text("How should the source get it?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    ChoiceRow(
        selected = found && !browser,
        enabled = found,
        title = "From the address the site loads it from · " + if (found) "found" else "not found",
        onClick = { model.setSuggestionRead(index, "") },
    ) {
        if (found) {
            Text(
                group.late,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Fast." + if (group.lateNote.isNotBlank()) " ${group.lateNote}." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                group.lateWarning.ifBlank { "No request of the page had its cards." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ChoiceRow(
        selected = browser,
        enabled = true,
        title = "Read with the browser",
        onClick = { model.setSuggestionRead(index, "browser") },
    ) {
        Text(
            "Opens the title page in a hidden browser, waits for the section, then reads it. Slower (2–4 s), but works " +
                "when no address is found.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { model.findLateAgain(index) }, enabled = !busy) { Text("Look for the address again") }
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(end = 12.dp, top = 2.dp, bottom = 8.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                content()
            }
        }
    }
}

/** A section's Load more: Also press it Off / 1× / 2× / 3×, ≈ cards, Scan it now, what the scan found */
@Composable
private fun LoadMoreBox(index: Int, group: Derive.SuggestionGroup, more: Derive.LoadMore, busy: Boolean, model: BuilderScreenModel) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "“${more.text.ifBlank { "Load more" }}” button found" +
                            if (more.perPress > 0) " · +${more.perPress} per press" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (more.perPress > 0) "Under this section. Each press added ${more.perPress} cards while you watched." else "Under this section.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { model.forgetLoadMore(index) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Not this button", modifier = Modifier.size(14.dp))
                }
            }
            Text("Also press it, up to", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 1, 2, 3).forEach { n ->
                    FilterChip(
                        selected = more.presses == n,
                        onClick = { model.setLoadMorePresses(index, n) },
                        label = { Text(if (n == 0) "Off" else "$n×") },
                    )
                }
            }
            if (more.presses > 0 && more.perPress > 0) {
                Text(
                    "≈ ${group.count + more.presses * more.perPress} cards",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { model.scanLoadMore(index) }, enabled = !busy) { Text(if (busy) "Scanning…" else "Scan it now") }
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    SuggestHint("presses it once here to check")
                }
            }
            if (more.scanned.isNotBlank()) {
                Text(
                    more.scanned,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (more.scanned.startsWith("Scanned")) SuggestOk else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (more.presses > 0 && more.request.isBlank()) {
                SuggestHint("Without an address, the source opens the title page in the hidden browser and presses it there (slower).")
            }
        }
    }
}
