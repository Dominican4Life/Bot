package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.LintCheck
import eu.kanade.tachiyomi.jssource.lab.LintFinding
import eu.kanade.tachiyomi.jssource.lab.LintRule
import eu.kanade.tachiyomi.jssource.lab.LintSeverity
import eu.kanade.tachiyomi.jssource.lab.RulesFile
import eu.kanade.tachiyomi.jssource.lab.TemplateUpdate
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KMK (r96, r106): the Lint pane (mock ELint): filter chips (All / Errors / Warnings / Tips, Ignored), problem cards
 * (title, part · line · rule id, what was found) with their actions: Apply fix (rules with a replacement that applies
 * once), Go to line, Ignore / Un-ignore, Read the rule; `filter-value-is-label` on a builder source adds "Learn the
 * codes" (Edit by tapping); a newer template adds a card with "Update template". On the right (wide) the selected
 * card's before / after of its fix ([SourceLabScreenModel.previewFix]), else its rule's text; on a phone the selected
 * card opens that view under itself. "Fix all N safe fixes" is the top bar's on a wide pane ([fixAllInBar]), else
 * this pane's head.
 */
@Composable
internal fun LintPane(
    state: SourceLabScreenModel.State,
    model: SourceLabScreenModel,
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    onGoToLine: (Int) -> Unit,
    onLearnCodes: (() -> Unit)?,
    onUpdateTemplate: () -> Unit,
    onFixAll: () -> Unit,
    fixAllInBar: Boolean,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val lint = state.lint ?: state.source?.lastLint
    val rules = state.rules
    val all = lint?.findings.orEmpty()
    var filter by rememberSaveable(state.key) { mutableStateOf(LintFilter.All) }
    var selectedKey by rememberSaveable(state.key) { mutableStateOf<String?>(null) }
    val offer = state.templateOffer
    // KMK (r115, mock M3Lint): the template offer belongs to "In the code"
    val showOffer = offer != null && (filter == LintFilter.All || filter == LintFilter.Code)
    val originOf: (LintFinding) -> LintOrigin = { f -> lintOrigin(f, rules) }
    // grouped by where they come from (the site, the code, the last test), in that order
    val findings = all.filter { filter.matches(it, originOf(it)) }.sortedBy { originOf(it).ordinal }
    val selected = findings.firstOrNull { findingKey(it) == selectedKey }
    val offerSelected = showOffer && selectedKey == TEMPLATE_KEY
    // Nothing chosen yet: the first card (wide panes show it on the right)
    val shown = selected ?: if (selectedKey == null && !offerSelected) findings.firstOrNull() else null
    val safe = model.safeFixes(all)
    val preview by produceState<List<History.DiffLine>?>(null, shown, editorState.code, editorState.json, rules) {
        val f = shown
        value = if (f == null) null else withContext(Dispatchers.IO) { model.previewFix(editorState.code, editorState.json, f) }
    }
    val applyOne: (LintFinding) -> Unit = { finding -> model.applyFix(editor, finding) }
    val learn: (LintFinding) -> (() -> Unit)? = { f ->
        onLearnCodes?.takeIf { f.ruleId == LEARN_RULE && madeByTapping(state.source) }
    }

    // One finding's card with its actions (the list and the phone's page share it)
    val findingCard: @Composable (LintFinding, Boolean, () -> Unit) -> Unit = { finding, on, onClick ->
        val rule = rules?.rules?.firstOrNull { it.id == finding.ruleId }
        val canApply = finding.applicable && finding.line != null && rule?.apply != null && !finding.ignored
        val learnCodes = learn(finding)
        ProblemCard(
            severity = finding.severity,
            title = finding.title,
            where = listOfNotNull(
                LabDiff.partOf(finding.line, state.explain)?.title,
                finding.line?.let { "line $it" },
                finding.ruleId,
            ).joinToString(" · ") + if (finding.ignored) " · ignored" else "",
            text = finding.found,
            selected = on,
            onClick = onClick,
        ) {
            if (learnCodes != null) OutlinedButton(onClick = learnCodes) { Text("Learn the codes") }
            if (canApply) Button(onClick = { applyOne(finding) }) { Text("Apply fix") }
            finding.line?.let { line -> TextButton(onClick = { onGoToLine(line) }) { Text("Go to line") } }
            TextButton(onClick = { model.ignoreRule(finding.ruleId, !finding.ignored) }) {
                Text(if (finding.ignored) "Un-ignore" else "Ignore")
            }
            TextButton(onClick = { navigator?.push(RulesScreen(ruleId = finding.ruleId)) }) { Text("Read the rule") }
        }
    }
    val offerCard: @Composable (TemplateUpdate.Offer, Boolean, () -> Unit) -> Unit = { o, on, onClick ->
        ProblemCard(
            severity = LintSeverity.Info,
            title = "The template has a newer version",
            where = "Whole source · ${o.templateName} v${o.from} → v${o.to}",
            text = o.problem ?: o.notes.lastOrNull() ?: "New ${o.partWords} part",
            selected = on,
            onClick = onClick,
        ) {
            Button(onClick = onUpdateTemplate, enabled = !state.updatingTemplate && o.problem == null) {
                Text(if (state.updatingTemplate) "Updating…" else "Update template")
            }
            TextButton(onClick = { selectedKey = TEMPLATE_KEY }) { Text("What changes") }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 760.dp
        // KMK (r106): a phone opens a tapped problem as its own page (back arrow / Back returns to the list)
        val page = if (wide) null else selected
        val offerPage = !wide && offerSelected && offer != null
        BackHandler(enabled = page != null || offerPage) { selectedKey = null }
        if (page != null || (offerPage && offer != null)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { selectedKey = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the problems")
                    }
                    Text(
                        if (page != null) {
                            listOfNotNull(LabDiff.partOf(page.line, state.explain)?.title ?: "Whole source", page.line?.let { "line $it" }).joinToString(" · ")
                        } else {
                            "Template update"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                ) {
                    if (page != null) {
                        findingCard(page, false) {}
                        FixView(
                            finding = page,
                            rule = rules?.rules?.firstOrNull { it.id == page.ruleId },
                            rules = rules,
                            preview = preview,
                            learn = learn(page) != null,
                            compact = false,
                        )
                    } else if (offer != null) {
                        offerCard(offer, false) {}
                        TemplateView(offer)
                    }
                }
            }
        } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (wide) 20.dp else 0.dp, end = if (wide) 20.dp else 0.dp, top = if (wide) 12.dp else 0.dp),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = if (wide) 0.dp else 12.dp, end = if (wide) 0.dp else 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = if (wide) Modifier.width(520.dp).fillMaxHeight() else Modifier.fillMaxSize(),
            ) {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            LintFilter.entries.forEach { f ->
                                val n = all.count { f.matches(it, originOf(it)) } +
                                    if (offer != null && (f == LintFilter.All || f == LintFilter.Code)) 1 else 0
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text("${f.label} $n") },
                                )
                            }
                        }
                        Text(
                            text = when {
                                lint == null -> "Not linted yet. Lint runs after every save and Test Run."
                                else -> "rules v${lint.rulesVersion} · ${lint.checked} checked · " +
                                    "${all.count { !it.ignored }} findings · ${SourceLabScreenModel.agoText(lint.time)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model::lintNow, enabled = !state.linting) {
                                Text(if (state.linting) "Checking…" else "Check now")
                            }
                            if (!fixAllInBar && safe.isNotEmpty()) {
                                Button(onClick = onFixAll) {
                                    Text(if (safe.size == 1) "Fix the 1 that can be fixed" else "Fix the ${safe.size} that can be fixed")
                                }
                            }
                        }
                    }
                }
                if (lint != null && findings.isEmpty() && !showOffer) {
                    item(key = "clean") {
                        Text(
                            if (filter == LintFilter.All) "No findings for this source." else "Nothing here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // KMK (r115, mock M3Lint): one group per origin under its small-caps heading (the template offer
                // first in "In the code")
                LintOrigin.entries.forEach { origin ->
                    val group = findings.filter { originOf(it) == origin }
                    val offerHere = origin == LintOrigin.Code && showOffer && offer != null
                    if (group.isEmpty() && !offerHere) return@forEach
                    item(key = "group-${origin.name}") {
                        LabCaption(origin.label, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                    }
                    if (offerHere && offer != null) {
                        item(key = TEMPLATE_KEY) {
                            val on = offerSelected && wide
                            offerCard(offer, on) { selectedKey = if (on) null else TEMPLATE_KEY }
                        }
                    }
                    items(group, key = { findingKey(it) }) { finding ->
                        // a phone highlights nothing in the list: a tap opens the problem's page
                        findingCard(finding, wide && finding === shown) { selectedKey = findingKey(finding) }
                    }
                }
                if (lint != null && lint.passed.isNotEmpty() && filter == LintFilter.All) {
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
                if (lint != null && lint.toRead.isNotEmpty() && filter == LintFilter.All) {
                    item(key = "to-read") {
                        Column {
                            Text("To read for this source", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            lint.toRead.forEach { id ->
                                val rule = rules?.rules?.firstOrNull { it.id == id }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
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
                if (lint != null && lint.notChecked.isNotEmpty() && filter == LintFilter.All) {
                    item(key = "not-checked") {
                        Text(
                            "Not checked by this build: ${lint.notChecked.joinToString(", ")} (their check kinds are newer than the app)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (wide) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 16.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        when {
                            offerSelected && offer != null -> TemplateView(offer)
                            shown != null -> {
                                val rule = rules?.rules?.firstOrNull { it.id == shown.ruleId }
                                Text(
                                    listOfNotNull(
                                        LabDiff.partOf(shown.line, state.explain)?.title ?: "Whole source",
                                        shown.line?.let { "line $it" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                FixView(
                                    finding = shown,
                                    rule = rule,
                                    rules = rules,
                                    preview = preview,
                                    learn = learn(shown) != null,
                                    compact = false,
                                )
                            }
                            else -> Text(
                                if (lint == null) "Not linted yet." else "Pick a problem to see what its fix changes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

private const val TEMPLATE_KEY = "template-update"

/** The rule whose findings get "Learn the codes" (Edit by tapping on a builder source) */
private const val LEARN_RULE = "filter-value-is-label"

private fun findingKey(f: LintFinding): String = "${f.ruleId}:${f.line}:${f.found.hashCode()}"

/** Lint's filter chips. KMK (r115, mock M3Lint): by where a finding comes from, not by its level */
internal enum class LintFilter(val label: String) {
    All("All"),
    Site("From the site"),
    Code("In the code"),
    Test("From the test"),
    Ignored("Ignored"),
    ;

    fun matches(f: LintFinding, origin: LintOrigin): Boolean = when (this) {
        All -> !f.ignored
        Site -> !f.ignored && origin == LintOrigin.Site
        Code -> !f.ignored && origin == LintOrigin.Code
        Test -> !f.ignored && origin == LintOrigin.Test
        Ignored -> f.ignored
    }
}

/** KMK (r115): where a finding comes from, by its rule's check kind */
internal enum class LintOrigin(val label: String) { Site("From the site"), Code("In the code"), Test("From the last test") }

/**
 * KMK (r115): the site's replies (headers, status codes, a bot check met) / the code itself (patterns, selectors) /
 * what the last test brought back (result fields, saved chapter addresses). A rule not in [rules] or with a check
 * kind this build doesn't know counts as code.
 */
internal fun lintOrigin(f: LintFinding, rules: RulesFile?): LintOrigin {
    val check = rules?.rules?.firstOrNull { it.id == f.ruleId }?.check ?: return LintOrigin.Code
    return when (LintCheck.of(check)) {
        LintCheck.HeaderPresent, LintCheck.ResponseStatus, LintCheck.RenderOption -> LintOrigin.Site
        LintCheck.ResultField, LintCheck.CompareSavedChapterUrls -> LintOrigin.Test
        // regex-in-code, selector-pattern, and any kind added later
        else -> LintOrigin.Code
    }
}

/** A problem card: severity glyph, title, where (part · line · rule), the text, then [actions] (they wrap) */
@Composable
private fun ProblemCard(
    severity: LintSeverity,
    title: String,
    where: String,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val (glyph, color) = when (severity) {
        LintSeverity.Error -> "!" to StripColor.Red
        LintSeverity.Warn -> "!" to StripColor.Amber
        LintSeverity.Info -> "i" to StripColor.Blue
    }
    val tint = color.tint()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f)),
            ) {
                Text(glyph, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // KMK (r115, mock M3Lint): the level as a chip beside the title
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LabChip(
                        when (severity) {
                            LintSeverity.Error -> "error"
                            LintSeverity.Warn -> "warn"
                            LintSeverity.Info -> "info"
                        },
                        when (severity) {
                            LintSeverity.Error -> StripColor.Red
                            LintSeverity.Warn -> StripColor.Amber
                            LintSeverity.Info -> null
                        },
                    )
                }
                Text(
                    where,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    actions()
                }
            }
        }
    }
}

/**
 * The selected problem: its fix's before / after when the rule has a replacement ([preview]), what "Learn the codes"
 * does for `filter-value-is-label`, else the rule's fix and snippet; then "Why this matters" (the rule's section).
 */
@Composable
private fun FixView(
    finding: LintFinding,
    rule: LintRule?,
    rules: RulesFile?,
    preview: List<History.DiffLine>?,
    learn: Boolean,
    compact: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = if (compact) Modifier.fillMaxWidth().padding(top = 8.dp) else Modifier.fillMaxWidth(),
    ) {
        when {
            learn -> {
                Text("What \"Learn the codes\" does", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Opens this source in the builder (Edit by tapping). In Lists & filters, Learn the codes presses " +
                        "each entry of the menu on the site and keeps the code its address uses. Nothing is saved " +
                        "until you press Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rule?.apply != null && preview != null -> {
                Text("What \"Apply fix\" will change", style = MaterialTheme.typography.labelLarge)
                DiffLines(diff = LabDiff.compact(preview), maxLines = if (compact) 30 else 120)
                Text(
                    "The code in the editor changes; nothing is saved until you press Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rule?.apply != null && finding.applicable -> {
                Text("Reading the fix…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                if (finding.fix.isNotBlank()) {
                    Text("Fix", style = MaterialTheme.typography.labelLarge)
                    Text(finding.fix, style = MaterialTheme.typography.bodySmall)
                }
                finding.snippet?.let { MonoCard(title = null, text = it) }
                if (rule?.apply != null && !finding.applicable) {
                    Text(
                        "The replacement doesn't apply exactly once here: change the line by hand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val why = ruleText(rules, rule)
        if (why != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Why this matters", style = MaterialTheme.typography.labelLarge)
                    Text(
                        why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 8 else 24,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A newer template: what changes and the parts it replaces */
@Composable
private fun TemplateView(offer: TemplateUpdate.Offer) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("${offer.templateName} v${offer.from} → v${offer.to}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val problem = offer.problem
        when {
            problem != null -> Text(problem, style = MaterialTheme.typography.bodySmall)
            offer.parts.isEmpty() -> Text(
                "Nothing to replace for this source: updating only marks it as written with v${offer.to}.",
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text("Replaces: ${offer.partWords}", style = MaterialTheme.typography.bodySmall)
        }
        offer.notes.forEach { note ->
            Text("• $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Your edits in these parts are replaced; the old code stays in History.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The rule's section of the Rules file (at most 30 lines), else its fix text */
private fun ruleText(rules: RulesFile?, rule: LintRule?): String? {
    if (rule == null) return null
    val section = rules?.sections?.get(rule.section)
    val text = if (rules != null && section != null) {
        val lines = rules.body.lines()
        val from = section.first.coerceIn(0, lines.size)
        val to = (section.last + 1).coerceIn(from, lines.size)
        lines.subList(from, to).take(30).joinToString("\n").trim()
    } else {
        ""
    }
    return text.ifBlank { rule.fix.ifBlank { null } }
}
