package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.jssource.lab.HealthState
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.TestRun

// KMK --> (r106) the code editor revamp's frame (mocks EHome / ECode / ETest / ELint / EHistory): the labelled rail,
// the one-row top bar that replaced the status strip, and the status badge. Nothing here imports jssource.lab.History,
// so the History icon (Icons.Outlined.Schedule) can't clash with it.

/**
 * The tablet rail. At home: Sources, New, Rules, Templates and (bottom) Logs. With a source open ([sourceOpen]):
 * ‹ Sources, Code, Explain, Test, Lint (with [lintCount] as a badge), History, Report and (bottom) Logs.
 * KMK (r110): New and Rules are places like Sources and Templates (shown next to the rail, item lit), not pushed.
 */
@Composable
internal fun LabRail(
    sourceOpen: Boolean,
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
    NavigationRail {
        if (sourceOpen) {
            NavigationRailItem(
                selected = false,
                onClick = onSources,
                icon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to sources") },
                label = { Text("Sources") },
            )
            // A fixed width, never fillMaxWidth: the rail's Column is wrap-content and is measured with the whole
            // screen as its max, so a fillMaxWidth divider grew the rail to the full width (r96: empty panes)
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp).width(40.dp))
            LabTab.entries.forEach { t ->
                NavigationRailItem(
                    selected = tab == t,
                    onClick = { onTab(t) },
                    icon = {
                        if (t == LabTab.Lint && lintCount > 0) {
                            BadgedBox(badge = { Badge { Text(lintCount.toString()) } }) {
                                Icon(t.icon(), contentDescription = null)
                            }
                        } else {
                            Icon(t.icon(), contentDescription = null)
                        }
                    },
                    label = { Text(t.label) },
                )
            }
        } else {
            NavigationRailItem(
                selected = dest == RailDest.Sources,
                onClick = onSources,
                icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                label = { Text("Sources") },
            )
            NavigationRailItem(
                selected = dest == RailDest.New,
                onClick = onNewSource,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                label = { Text("New") },
            )
            NavigationRailItem(
                selected = dest == RailDest.Rules,
                onClick = onRules,
                icon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                label = { Text("Rules") },
            )
            NavigationRailItem(
                selected = dest == RailDest.Templates,
                onClick = onTemplates,
                icon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) },
                label = { Text("Templates") },
            )
        }
        // r98: Logs at the bottom of the rail (the rail's Column fills the height, so the spacer takes what's left)
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = false,
            onClick = onLogs,
            icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
            label = { Text("Logs") },
        )
    }
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
 * KMK (r106): the phone's bottom navigation, in place of the rail. At home: Sources, New, Rules, Templates, Logs. With
 * a source open: Code, Test, Lint (its count as a badge), History, More (Explain, Report, ‹ Sources, Logs).
 */
@Composable
internal fun LabBottomBar(
    sourceOpen: Boolean,
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
    var more by remember { mutableStateOf(false) }
    NavigationBar {
        if (sourceOpen) {
            listOf(LabTab.Code, LabTab.Test, LabTab.Lint, LabTab.History).forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { onTab(t) },
                    icon = {
                        if (t == LabTab.Lint && lintCount > 0) {
                            BadgedBox(badge = { Badge { Text(lintCount.toString()) } }) {
                                Icon(t.icon(), contentDescription = null)
                            }
                        } else {
                            Icon(t.icon(), contentDescription = null)
                        }
                    },
                    label = { Text(t.label, maxLines = 1) },
                )
            }
            NavigationBarItem(
                selected = tab == LabTab.Explain || tab == LabTab.Report,
                onClick = { more = true },
                icon = {
                    Box {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = null)
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            listOf(LabTab.Explain, LabTab.Report).forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    leadingIcon = { Icon(t.icon(), contentDescription = null) },
                                    onClick = {
                                        more = false
                                        onTab(t)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Sources") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) },
                                onClick = {
                                    more = false
                                    onSources()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Logs") },
                                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                                onClick = {
                                    more = false
                                    onLogs()
                                },
                            )
                        }
                    }
                },
                label = { Text(if (tab == LabTab.Explain || tab == LabTab.Report) tab.label else "More", maxLines = 1) },
            )
        } else {
            NavigationBarItem(
                selected = dest == RailDest.Sources,
                onClick = onSources,
                icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                label = { Text("Sources", maxLines = 1) },
            )
            NavigationBarItem(
                selected = dest == RailDest.New,
                onClick = onNewSource,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                label = { Text("New", maxLines = 1) },
            )
            NavigationBarItem(
                selected = dest == RailDest.Rules,
                onClick = onRules,
                icon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                label = { Text("Rules", maxLines = 1) },
            )
            NavigationBarItem(
                selected = dest == RailDest.Templates,
                onClick = onTemplates,
                icon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) },
                label = { Text("Templates", maxLines = 1) },
            )
            NavigationBarItem(
                selected = false,
                onClick = onLogs,
                icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                label = { Text("Logs", maxLines = 1) },
            )
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
