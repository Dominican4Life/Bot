package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.LiveIcon
import eu.kanade.presentation.components.LiveIconKind
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestRun

// KMK --> (r106) the code editor revamp's frame (mocks EHome / ECode / ETest / ELint / EHistory): the labelled rail,
// the one-row top bar that replaced the status strip, and the status badge. Nothing here imports jssource.lab.History,
// so the History icon (Icons.Outlined.Schedule) can't clash with it.

/**
 * The rail. At the top always: Sources, New, Rules, Templates. With a source open ([sourceName] not null): a divider,
 * the source's short name, then its tabs Code, Explain, Test, Lint (with [lintCount] as a badge), History, Report.
 * Logs is pinned at the bottom.
 * KMK (r110): New and Rules are places like Sources and Templates (shown next to the rail, item lit), not pushed.
 * KMK (r115, mocks M3Code / L2Work): ONE rail. The Lab's four places never go away; the source's tabs sit under them
 * while a source is open (also while New / Rules / Templates is shown: a tab goes back to the source) and leave when
 * Sources takes you back to the list. While a source's tab is shown, Sources is tinted as its parent. The items are
 * compact (pill 56 × 30, 11 sp label) so all eleven fit a landscape tablet; the top group scrolls when they don't.
 */
@Composable
internal fun LabRail(
    // KMK (r115): the open source's name (null: no source open); its tabs show while it is
    sourceName: String?,
    tab: LabTab,
    // KMK (r110): the rail place shown (Sources, New, Rules, Templates): each lights its own item
    dest: RailDest,
    lintCount: Int,
    onSources: () -> Unit,
    onNewSource: () -> Unit,
    onRules: () -> Unit,
    onTemplates: () -> Unit,
    onTab: (LabTab) -> Unit,
    onLogs: () -> Unit,
) {
    val sourceOpen = sourceName != null
    // KMK (r115): Logs and History play their live icon (r114) on tap; never on first show
    var logsPlay by remember { mutableIntStateOf(0) }
    var historyPlay by remember { mutableIntStateOf(0) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            LabRailItem(
                label = "Sources",
                selected = dest == RailDest.Sources && !sourceOpen,
                parent = dest == RailDest.Sources && sourceOpen,
                onClick = onSources,
            ) { tint -> Icon(Icons.Outlined.GridView, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
            LabRailItem(label = "New", selected = dest == RailDest.New, onClick = onNewSource) { tint ->
                Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            LabRailItem(label = "Rules", selected = dest == RailDest.Rules, onClick = onRules) { tint ->
                Icon(Icons.Outlined.Checklist, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            LabRailItem(label = "Templates", selected = dest == RailDest.Templates, onClick = onTemplates) { tint ->
                Icon(Icons.Outlined.Dashboard, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            if (sourceOpen) {
                // A fixed width, never fillMaxWidth (r96: a fillMaxWidth divider in a wrap-content rail grew it to the
                // full width)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp).width(44.dp),
                )
                Text(
                    railShortName(sourceName.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                )
                LabTab.entries.forEach { t ->
                    LabRailItem(
                        label = t.label,
                        selected = dest == RailDest.Sources && tab == t,
                        badge = if (t == LabTab.Lint && lintCount > 0) lintCount else null,
                        onClick = {
                            if (t == LabTab.History) historyPlay++
                            onTab(t)
                        },
                    ) { tint ->
                        if (t == LabTab.History) {
                            LiveIcon(LiveIconKind.History, play = historyPlay, tint = tint, size = 22.dp)
                        } else {
                            Icon(t.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        // r98: Logs at the bottom of the rail
        LabRailItem(
            label = "Logs",
            selected = false,
            onClick = {
                logsPlay++
                onLogs()
            },
        ) { tint -> LiveIcon(LiveIconKind.Logs, play = logsPlay, tint = tint, size = 22.dp) }
    }
}

/**
 * KMK (r115): one rail item: a 56 × 30 pill (lit = the selected place; [parent] = tinted, the Sources item while one
 * of a source's tabs is shown) over an 11 sp label; [badge] = a count on the pill's corner.
 */
@Composable
private fun LabRailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    parent: Boolean = false,
    badge: Int? = null,
    // KMK (r115): the phone's bottom bar shares its width among its items
    width: Dp = 76.dp,
    icon: @Composable (Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pill = when {
        selected -> scheme.secondaryContainer
        parent -> scheme.secondaryContainer.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val tint = when {
        selected -> scheme.onSecondaryContainer
        parent -> scheme.onSecondaryContainer.copy(alpha = 0.85f)
        else -> scheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(pill),
        ) {
            if (badge != null) {
                BadgedBox(badge = { Badge { Text(badge.toString()) } }) { icon(tint) }
            } else {
                icon(tint)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** KMK (r115): the rail's source label: the name in capitals, cut to its first words when long ("SLICE OF LIFE") */
internal fun railShortName(name: String): String {
    val clean = name.trim().ifBlank { "Source" }
    if (clean.length <= 12) return clean.uppercase()
    val words = clean.split(' ').filter { it.isNotBlank() }
    val out = StringBuilder()
    for (w in words) {
        if (out.isNotEmpty() && out.length + 1 + w.length > 14) break
        if (out.isNotEmpty()) out.append(' ')
        out.append(w)
    }
    return out.toString().ifBlank { clean.take(12) }.uppercase()
}

/** The rail / tab icon of a source tab */
internal fun LabTab.icon(): ImageVector = when (this) {
    LabTab.Code -> Icons.Outlined.Code
    LabTab.Explain -> Icons.Outlined.ChatBubbleOutline
    LabTab.Test -> Icons.Outlined.PlayArrow
    LabTab.Lint -> Icons.Outlined.Warning
    LabTab.History -> Icons.Outlined.Schedule
    LabTab.Report -> Icons.Outlined.BugReport
}

/**
 * The one-row top bar (64 dp): an optional back arrow (phone; the tablet's rail has ‹ Sources), the title with its
 * subtitle, an optional status badge, then [actions] (buttons, Save, ⋮). A divider follows it (the caller's).
 */
@Composable
internal fun LabTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    badge: Pair<String, StripColor>? = null,
    // KMK (r106): the phone draws the badge in the subtitle line, so the title keeps its room at 360 dp
    badgeInline: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    val besideTitle = badge != null && !badgeInline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        }
        // KMK (r108) fix: the title (and its badge) in one row that takes the free room. Two weights (the title's with
        // fill = false and a Spacer's) split the room in half, so the buttons stopped short of the right edge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.weight(1f, fill = !besideTitle)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null && badgeInline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            badge.first,
                            style = MaterialTheme.typography.labelMedium,
                            color = badge.second.tint(),
                            maxLines = 1,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                " · $subtitle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null && besideTitle) {
                StatusBadge(text = badge.first, color = badge.second)
            }
        }
        actions()
    }
}

/**
 * KMK (r115, phone < 600 dp): the bottom bar in place of the rail. No source open: the Lab's places (Sources, New,
 * Rules, Templates). A source open ([sourceOpen]): its tabs Code, Explain, Test, Lint (count as a badge), History,
 * Report; the top bar's back arrow returns to Sources. Logs is in the top bar's ⋮. Items share the width (at least
 * 64 dp each); when they don't fit the row scrolls sideways.
 */
@Composable
internal fun LabBottomBar(
    sourceOpen: Boolean,
    tab: LabTab,
    dest: RailDest,
    lintCount: Int,
    onSources: () -> Unit,
    onNewSource: () -> Unit,
    onRules: () -> Unit,
    onTemplates: () -> Unit,
    onTab: (LabTab) -> Unit,
) {
    var historyPlay by remember { mutableIntStateOf(0) }
    val count = if (sourceOpen) LabTab.entries.size else 4
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            val itemWidth = maxOf(64.dp, maxWidth / count)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                if (sourceOpen) {
                    LabTab.entries.forEach { t ->
                        LabRailItem(
                            label = t.label,
                            selected = tab == t,
                            badge = if (t == LabTab.Lint && lintCount > 0) lintCount else null,
                            width = itemWidth,
                            onClick = {
                                if (t == LabTab.History) historyPlay++
                                onTab(t)
                            },
                        ) { tint ->
                            if (t == LabTab.History) {
                                LiveIcon(LiveIconKind.History, play = historyPlay, tint = tint, size = 22.dp)
                            } else {
                                Icon(t.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                } else {
                    LabRailItem(label = "Sources", selected = dest == RailDest.Sources, width = itemWidth, onClick = onSources) { tint ->
                        Icon(Icons.Outlined.GridView, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                    LabRailItem(label = "New", selected = dest == RailDest.New, width = itemWidth, onClick = onNewSource) { tint ->
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                    LabRailItem(label = "Rules", selected = dest == RailDest.Rules, width = itemWidth, onClick = onRules) { tint ->
                        Icon(Icons.Outlined.Checklist, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                    LabRailItem(label = "Templates", selected = dest == RailDest.Templates, width = itemWidth, onClick = onTemplates) { tint ->
                        Icon(Icons.Outlined.Dashboard, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** A small filled badge in a status colour: "● 1 of 5 passing", "○ Not tested" */
@Composable
internal fun StatusBadge(text: String, color: StripColor, modifier: Modifier = Modifier) {
    val tint = color.tint()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.16f),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The open source's badge: testing now, never tested, else "k of n passing" in the worst step's colour */
internal fun runBadge(running: Boolean, run: TestRun?): Pair<String, StripColor> = when {
    running -> "● Testing…" to StripColor.Blue
    run == null -> "○ Not tested" to StripColor.Neutral
    run.counted == 0 -> "○ Nothing tested" to StripColor.Neutral
    else -> "● ${run.passed} of ${run.counted} passing" to when (run.worst) {
        StepStatus.Red -> StripColor.Red
        StepStatus.Amber -> StripColor.Amber
        StepStatus.Green -> StripColor.Green
        else -> StripColor.Neutral
    }
}

/** A Home row's badge from its health */
internal fun healthBadge(state: HealthState): Pair<String, StripColor> = when (state) {
    HealthState.Good -> "● Passing" to StripColor.Green
    HealthState.Attention -> "● Warnings" to StripColor.Amber
    HealthState.Failing -> "● Failing" to StripColor.Red
    HealthState.Unknown -> "○ Not tested" to StripColor.Neutral
}

/** Whether the builder (Build / Edit by tapping) wrote [source]: only those have picks to edit */
internal fun madeByTapping(source: LabSource?): Boolean {
    val lab = source?.lab ?: return false
    return lab.engine == "generic-list" || lab.templateId?.startsWith("generic-list") == true
}

/** Where a source came from, for Home's second line: "Made by tapping · site.com", "Converted from an extension"… */
internal fun originOf(source: LabSource): String {
    val lab = source.lab
    val host = lab?.siteUrl?.substringAfter("://", "")?.substringBefore('/')?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    return when {
        lab == null -> "Hand-written"
        madeByTapping(source) -> "Made by tapping" + (host?.let { " · $it" }.orEmpty())
        !lab.fromExtension.isNullOrBlank() -> "Converted from an extension"
        lab.templateId != null -> "Template · ${lab.templateId}" + (host?.let { " · $it" }.orEmpty())
        lab.engine != null -> "Detected · ${lab.engine}" + (host?.let { " · $it" }.orEmpty())
        else -> "Hand-written"
    }
}
// KMK <--

// KMK --> (r115) widths
/** Under this screen width (dp, the whole screen's, like More's test) the Lab uses its phone layout: bottom bar */
internal const val LAB_MIN_WIDTH_DP = 600

/** From this screen width (dp) the top bars show labelled buttons and the search field instead of icons */
internal const val LAB_ROOMY_BAR_DP = 840
// KMK <--
