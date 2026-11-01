package eu.kanade.tachiyomi.ui.diagnostics

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.diagnostics.DiagKeys
import eu.kanade.tachiyomi.diagnostics.DiagTab
import eu.kanade.tachiyomi.diagnostics.FloatingLogState
import eu.kanade.tachiyomi.diagnostics.LogFilter
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK (r97): The Diagnostics hub that replaced the Logs screen (design `scratchpad/r96-diag/DESIGN.md`, contract
 * `scratchpad/r97/CONTRACT-DIAG.md`): header, the Option B status strip, six tabs — Live · Log · Capture ·
 * Health · Report · Probes — as a tab row + pager on phones (only the visible page is composed, so nothing samples
 * behind another tab) and as a left rail on tablets. Lands on [initialTab] when given, on Log when a W/E arrived
 * since the last visit, else on the last tab. Entry points: More › Logs, the tablet rail's Logs button,
 * `DiagActions.OPEN`.
 */
internal class DiagnosticsScreen(
    private val initialTab: DiagTab? = null,
    private val probeId: String? = null,
) : Screen() {

    // One instance in the stack: MainActivity replaces an open DiagnosticsScreen on a second DiagActions.OPEN instead
    // of stacking (a fixed Voyager key would make rememberScreenModel hand the replaced screen's model to the new one)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { DiagnosticsScreenModel(context, initialTab, probeId) }
        val tab by model.tab.collectAsState()
        val tablet = isTabletUi()
        val header = remember { HeaderSlot() }
        val view = LocalView.current
        var menuOpen by remember { mutableStateOf(false) }
        val floating by FloatingLogState.visible.collectAsState()
        var keepOn by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            // The one line that proves the screen's path works (guide 01 "Logging conventions")
            AppLog.add('I', "diag", "Diagnostics opened on ${model.tab.value.label}")
        }
        LaunchedEffect(keepOn) { view.keepScreenOn = keepOn }

        Scaffold { padding ->
            if (tablet) {
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    DiagRail(
                        tab = tab,
                        onTab = model::setTab,
                        onBack = navigator::pop,
                        floating = floating,
                        onFloating = { toggleFloating(context, it) },
                        model = model,
                    )
                    Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) {
                        Header(
                            title = if (tab == DiagTab.LIVE) "Overview" else tab.label,
                            subtitle = header.subtitle.value ?: defaultSubtitle(tab),
                            back = null,
                            actions = header.actions.value,
                            onMenu = { menuOpen = true },
                            menu = {
                                OverflowMenu(
                                    open = menuOpen,
                                    onDismiss = { menuOpen = false },
                                    floating = floating,
                                    onFloating = { toggleFloating(context, it) },
                                    keepOn = keepOn,
                                    onKeepOn = { keepOn = it },
                                    onClearLog = { model.diag.clearLog() },
                                )
                            },
                        )
                        StatusStripBar(
                            onOpenHealth = { model.setTab(DiagTab.HEALTH) },
                            onOpenLog = { model.setTab(DiagTab.LOG) },
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            TabPage(tab = tab, model = model, header = header, tablet = true)
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    Column(modifier = Modifier.widthIn(max = 980.dp).fillMaxSize()) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Header(
                                title = "Diagnostics",
                                subtitle = header.subtitle.value ?: defaultSubtitle(tab),
                                back = navigator::pop,
                                actions = header.actions.value,
                                onMenu = { menuOpen = true },
                                menu = {
                                    OverflowMenu(
                                        open = menuOpen,
                                        onDismiss = { menuOpen = false },
                                        floating = floating,
                                        onFloating = { toggleFloating(context, it) },
                                        keepOn = keepOn,
                                        onKeepOn = { keepOn = it },
                                        onClearLog = { model.diag.clearLog() },
                                    )
                                },
                            )
                            StatusStripBar(
                                onOpenHealth = { model.setTab(DiagTab.HEALTH) },
                                onOpenLog = { model.setTab(DiagTab.LOG) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val pager = rememberPagerState(initialPage = tab.ordinal) { DiagTab.entries.size }
                        LaunchedEffect(tab) {
                            if (pager.currentPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal)
                        }
                        LaunchedEffect(pager.settledPage) {
                            val settled = DiagTab.entries[pager.settledPage]
                            if (settled != model.tab.value) model.setTab(settled)
                        }
                        SecondaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.padding(top = 8.dp)) {
                            DiagTab.entries.forEach { t ->
                                Tab(
                                    selected = tab == t,
                                    onClick = { model.setTab(t) },
                                    text = { Text(t.label, fontSize = 12.5.sp, maxLines = 1, softWrap = false) },
                                )
                            }
                        }
                        HorizontalPager(
                            state = pager,
                            beyondViewportPageCount = 0,
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { page ->
                            TabPage(tab = DiagTab.entries[page], model = model, header = header, tablet = false)
                        }
                    }
                }
            }
        }
    }
}

/** What a tab contributes to the shared header: a subtitle and its icon actions. Each tab sets it while composed. */
internal class HeaderSlot {
    val subtitle: MutableState<String?> = mutableStateOf(null)
    val actions: MutableState<(@Composable RowScope.() -> Unit)?> = mutableStateOf(null)
}

private fun defaultSubtitle(tab: DiagTab): String = when (tab) {
    DiagTab.LIVE -> "live · one graph for every resource"
    DiagTab.LOG -> "Log · newest first"
    DiagTab.CAPTURE -> "Capture · what gets logged"
    DiagTab.HEALTH -> "Health · last 7 days"
    DiagTab.REPORT -> "Report · build and share a bundle"
    DiagTab.PROBES -> "Probes · scripts that watch the app"
}

@Composable
private fun TabPage(tab: DiagTab, model: DiagnosticsScreenModel, header: HeaderSlot, tablet: Boolean) {
    when (tab) {
        DiagTab.LIVE -> LiveTab(model = model, header = header, tablet = tablet)
        DiagTab.LOG -> LogTab(model = model, header = header)
        DiagTab.CAPTURE -> CaptureTab(model = model, header = header)
        DiagTab.HEALTH -> HealthTab(model = model, header = header)
        DiagTab.REPORT -> ReportTab(model = model, header = header)
        DiagTab.PROBES -> ProbesTab(model = model, header = header, tablet = tablet)
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    back: (() -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    onMenu: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (back != null) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actions != null) actions()
        Box {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            menu()
        }
    }
}

@Composable
private fun OverflowMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    floating: Boolean,
    onFloating: (Boolean) -> Unit,
    keepOn: Boolean,
    onKeepOn: (Boolean) -> Unit,
    onClearLog: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Floating log") },
            trailingIcon = { Switch(checked = floating, onCheckedChange = null) },
            onClick = {
                onFloating(!floating)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Keep screen on") },
            trailingIcon = { Switch(checked = keepOn, onCheckedChange = null) },
            onClick = {
                onKeepOn(!keepOn)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Clear log") },
            onClick = {
                onClearLog()
                onDismiss()
            },
        )
    }
}

/** Flips the floating window and remembers the choice so a restart during testing brings it back (contract §5). */
internal fun toggleFloating(context: Context, on: Boolean) {
    FloatingLogState.visible.value = on
    if (on) FloatingLogState.collapsed.value = false
    diagPrefs(context).edit { putBoolean(DiagKeys.FLOAT_ON, on) }
}

private fun DiagTab.icon(): ImageVector = when (this) {
    DiagTab.LIVE -> Icons.Outlined.ShowChart
    DiagTab.LOG -> Icons.AutoMirrored.Outlined.List
    DiagTab.CAPTURE -> Icons.Outlined.RadioButtonUnchecked
    DiagTab.HEALTH -> Icons.Outlined.FavoriteBorder
    DiagTab.REPORT -> Icons.Outlined.Description
    DiagTab.PROBES -> Icons.Outlined.Code
}

/** The tablet rail (tb-01): back + title, the six tabs (a dot on Probes while running, a badge on Log), floating log */
@Composable
private fun DiagRail(
    tab: DiagTab,
    onTab: (DiagTab) -> Unit,
    onBack: () -> Unit,
    floating: Boolean,
    onFloating: (Boolean) -> Unit,
    model: DiagnosticsScreenModel,
) {
    val probes by model.diag.probes.collectAsState()
    val context = LocalContext.current
    // New problems since the Log tab was last shown: the lines newer than SEEN_VERSION (≤ 4000), counted every
    // 10 s rather than on every line (contract §8 rule 4)
    val newProblems by produceState(initialValue = 0, tab) {
        while (true) {
            val logVersion = model.diag.logVersion.value
            val seen = diagPrefs(context).getInt(DiagKeys.SEEN_VERSION, -1)
            value = if (tab == DiagTab.LOG || seen < 0 || seen >= logVersion) {
                0
            } else {
                runCatching {
                    model.diag.logLines(LogFilter(), limit = (logVersion - seen).coerceAtMost(4000))
                        .count { it.isProblem }
                }.getOrDefault(0)
            }
            delay(10_000L)
        }
    }
    NavigationRail(
        modifier = Modifier.width(236.dp).fillMaxHeight(),
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) {
        DiagTab.entries.forEach { t ->
            NavigationRailItem(
                selected = tab == t,
                onClick = { onTab(t) },
                icon = {
                    when {
                        t == DiagTab.LOG && newProblems > 0 -> BadgedBox(badge = { Badge { Text("$newProblems") } }) {
                            Icon(t.icon(), contentDescription = t.label)
                        }
                        t == DiagTab.PROBES && probes.runningCount > 0 -> BadgedBox(
                            badge = {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DiagColors.probe))
                            },
                        ) { Icon(t.icon(), contentDescription = t.label) }
                        else -> Icon(t.icon(), contentDescription = t.label)
                    }
                },
                label = { Text(t.label) },
                alwaysShowLabel = true,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Floating log", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = floating, onCheckedChange = onFloating)
        }
        Text(
            "Komikku ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
        )
    }
}
