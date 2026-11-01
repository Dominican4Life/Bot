package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.jssource.lab.LintFinding
import eu.kanade.tachiyomi.jssource.lab.LintSeverity
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel

/**
 * KMK (r96): the Lint pane (D§2.6): the rules version and counts, each finding with what was found, the Fix box,
 * Apply to line N (only when the rule's replacement applies exactly once), Open line, Read the rule, Ignore; then
 * the passed rules as chips and the text-only rules to read for this source.
 */
@Composable
internal fun LintPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val tablet = isTabletUi()
    val lint = state.lint ?: state.source?.lastLint
    val rules = state.rules
    var confirmApply by remember { mutableStateOf<LintFinding?>(null) }
    var showIgnored by remember { mutableStateOf(false) }
    val findings = lint?.findings.orEmpty().filter { showIgnored || !it.ignored }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        item(key = "head") {
            Column {
                Text(
                    text = when {
                        lint == null -> "Not linted yet. Lint runs after every save and Test Run."
                        else -> "rules v${lint.rulesVersion} · ${lint.checked} checked · ${lint.findings.count { !it.ignored }} findings" +
                            (lint.ignoredRuleIds.takeIf { it.isNotEmpty() }?.let { " · ${it.size} ignored" }.orEmpty()) +
                            " · ${SourceLabScreenModel.agoText(lint.time)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = model::lintNow, enabled = !state.linting) { Text(if (state.linting) "Checking…" else "Check now") }
                    if (lint?.ignoredRuleIds?.isNotEmpty() == true) {
                        OutlinedButton(onClick = { showIgnored = !showIgnored }) { Text(if (showIgnored) "Hide ignored" else "Show ignored") }
                    }
                }
            }
        }
        if (lint != null && findings.isEmpty()) {
            item(key = "clean") {
                Text("No findings for this source.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(findings, key = { "${it.ruleId}:${it.line}:${it.found.hashCode()}" }) { finding ->
            FindingCard(
                finding = finding,
                onApply = { confirmApply = finding },
                onOpenLine = {
                    finding.line?.let { model.revealLine(it) }
                    // r101: the tablet shows the code beside this pane; Code would swap the pane for Source info
                    if (!tablet) model.setTab(LabTab.Code)
                },
                onRead = { navigator?.push(RulesScreen(ruleId = finding.ruleId)) },
                onIgnore = { model.ignoreRule(finding.ruleId, !finding.ignored) },
            )
        }
        if (lint != null && lint.passed.isNotEmpty()) {
            item(key = "passed") {
                Column {
                    Text("${lint.passed.size} rules passed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        lint.passed.forEach { id ->
                            AssistChip(
                                onClick = { navigator?.push(RulesScreen(ruleId = id)) },
                                label = { Text(id, fontFamily = FontFamily.Monospace, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }
        if (lint != null && lint.toRead.isNotEmpty()) {
            item(key = "to-read") {
                Column {
                    Text("To read for this source", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    lint.toRead.forEach { id ->
                        val rule = rules?.rules?.firstOrNull { it.id == id }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule?.title ?: id, style = MaterialTheme.typography.bodyMedium)
                                Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { navigator?.push(RulesScreen(ruleId = id)) }) { Text("Read") }
                        }
                    }
                }
            }
        }
        if (lint != null && lint.notChecked.isNotEmpty()) {
            item(key = "not-checked") {
                Text(
                    "Not checked by this build: ${lint.notChecked.joinToString(", ")} (their check kinds are newer than the app)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    confirmApply?.let { finding ->
        val rule = rules?.rules?.firstOrNull { it.id == finding.ruleId }
        AlertDialog(
            onDismissRequest = { confirmApply = null },
            title = { Text("Apply to line ${finding.line}?") },
            text = {
                Column {
                    Text("The code in the editor changes (not saved until you tap Save):")
                    Spacer(Modifier.height(8.dp))
                    MonoCard(title = null, text = "find:    ${rule?.apply?.find}\nreplace: ${rule?.apply?.replace}")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ok = model.applyFix(editor, finding)
                        confirmApply = null
                        if (ok && !tablet) model.setTab(LabTab.Code)
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmApply = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FindingCard(
    finding: LintFinding,
    onApply: () -> Unit,
    onOpenLine: () -> Unit,
    onRead: () -> Unit,
    onIgnore: () -> Unit,
) {
    val (icon, tint) = when (finding.severity) {
        LintSeverity.Error -> Icons.Outlined.ErrorOutline to StripColor.Red.tint()
        LintSeverity.Warn -> Icons.Outlined.Warning to StripColor.Amber.tint()
        LintSeverity.Info -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
    }
    LabCard(title = finding.title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                "  ${finding.ruleId}${finding.line?.let { " · line $it" }.orEmpty()}${if (finding.ignored) " · ignored" else ""}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("Found", style = MaterialTheme.typography.labelLarge)
        Text(finding.found, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = tint)
        if (finding.fix.isNotBlank() || finding.snippet != null) {
            Spacer(Modifier.height(6.dp))
            Text("Fix", style = MaterialTheme.typography.labelLarge)
            if (finding.fix.isNotBlank()) Text(finding.fix, style = MaterialTheme.typography.bodySmall)
            finding.snippet?.let {
                Spacer(Modifier.height(4.dp))
                MonoCard(title = null, text = it)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (finding.applicable && finding.line != null) {
                TextButton(onClick = onApply) { Text("Apply to line ${finding.line}") }
            }
            if (finding.line != null) TextButton(onClick = onOpenLine) { Text("Open line") }
            TextButton(onClick = onRead) { Text("Read the rule") }
            TextButton(onClick = onIgnore) { Text(if (finding.ignored) "Un-ignore" else "Ignore for this source") }
        }
    }
}
