package eu.kanade.tachiyomi.ui.sourcelab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.jssource.lab.LintCheck
import eu.kanade.tachiyomi.jssource.lab.LintRule
import eu.kanade.tachiyomi.jssource.lab.LintSeverity
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK (r96): the Rules file screen (D§2.7): the file card (version, imported when, from which file, counts),
 * Update from file (SAF OpenDocument, like the player's script editor), Check all sources, Export; the reading
 * view (a small markdown renderer, no library), the machine rules table and the versions with roll back.
 * [ruleId] scrolls the reading view to that rule's section.
 */
internal class RulesScreen(private val ruleId: String? = null) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { SourceLabScreenModel(null, LabTab.Code) }
        val state by model.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var view by rememberSaveable { mutableStateOf(0) } // 0 reading, 1 machine rules, 2 versions
        val rules = state.rules
        val listState = rememberLazyListState()
        val blocks = remember(rules?.body) { rules?.body?.let { markdownBlocks(it) }.orEmpty() }
        val machine = rules?.rules.orEmpty().filter { it.check != "text" && it.check.isNotBlank() }
        val versions = remember(rules) { runCatching { model.rulesVersions() }.getOrDefault(emptyList()) }
        val hits = remember(state.sources) {
            state.sources.flatMap { s -> s.lastLint?.findings.orEmpty().filter { !it.ignored }.map { it.ruleId }.distinct() }
                .groupingBy { it }.eachCount()
        }

        LaunchedEffect(state.message) {
            val message = state.message ?: return@LaunchedEffect
            try {
                snackbar.showSnackbar(message)
            } finally {
                model.consumeMessage(message)
            }
        }
        // Read the rule: jump to its section once the body is there
        LaunchedEffect(blocks.size, ruleId) {
            val rule = ruleId?.let { id -> rules?.rules?.firstOrNull { it.id == id } } ?: return@LaunchedEffect
            val index = blocks.indexOfFirst { it is Block.Heading && it.number == rule.section }
            if (index >= 0) listState.scrollToItem(index + 2) // after the file card and the view chips
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment
                model.updateRules(text, name)
            }.onFailure { context.toast("Couldn't read the file: ${it.message}") }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Rules file", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (rules == null) "no rules file" else "v${rules.version} · ${rules.rules.size} rules · ${machine.size} checked by the Lab",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                LabStatusStrip(
                    head = if (rules == null) "Missing" else "Good",
                    color = if (rules == null) StripColor.Red else StripColor.Green,
                    figures = listOf(
                        "Version" to (rules?.version?.let { "v$it" } ?: "—"),
                        "Rules" to (rules?.rules?.size ?: 0).toString(),
                        "Machine" to machine.size.toString(),
                        "Sources hit" to hits.keys.size.toString(),
                    ),
                )
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "card") {
                        LabCard(title = "rules.md") {
                            Text(
                                text = buildString {
                                    if (rules == null) {
                                        append(state.rulesError ?: "The built-in file didn't parse.")
                                    } else {
                                        append("v${rules.version}")
                                        append(rules.importedAt?.let { " · imported ${SourceLabScreenModel.agoText(it)}" } ?: " · built into this release")
                                        rules.fileName?.let { append(" from $it") }
                                        append(" · markdown with a YAML front matter")
                                    }
                                    state.rulesError?.takeIf { rules != null }?.let { append("\nLast import refused: $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("Update from file") }
                                OutlinedButton(onClick = model::checkAllSources) { Text("Check all sources") }
                                OutlinedButton(
                                    onClick = {
                                        val file = model.exportRules()
                                        if (file == null) {
                                            context.toast("Nothing to export")
                                        } else {
                                            runCatching { context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "text/plain")) }
                                                .onFailure { context.toast(it.message ?: "Couldn't share") }
                                        }
                                    },
                                ) { Text("Export") }
                            }
                        }
                    }
                    item(key = "views") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = view == 0, onClick = { view = 0 }, label = { Text("Reading view") })
                            FilterChip(selected = view == 1, onClick = { view = 1 }, label = { Text("Machine rules") })
                            FilterChip(selected = view == 2, onClick = { view = 2 }, label = { Text("Versions") })
                        }
                    }
                    when (view) {
                        0 -> items(blocks.size, key = { "b$it" }) { index ->
                            val block = blocks[index]
                            val machineRule = (block as? Block.Heading)?.let { h -> machine.firstOrNull { it.section == h.number } }
                            MarkdownBlock(block, machineRule?.id)
                        }
                        1 -> items(machine.size, key = { "m${machine[it].id}" }) { index ->
                            val rule = machine[index]
                            RuleRow(rule = rule, hits = hits[rule.id] ?: 0, known = LintCheck.of(rule.check) != null)
                        }
                        else -> {
                            if (versions.isEmpty()) {
                                item(key = "no-versions") { Text("No versions yet.", style = MaterialTheme.typography.bodyMedium) }
                            }
                            items(versions.size, key = { "v${versions[it].first}" }) { index ->
                                val (version, label) = versions[index]
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    val current = rules != null &&
                                        ((version == 0 && rules.importedAt == null) || (version > 0 && rules.importedAt != null && rules.version == version))
                                    if (current) {
                                        Text("current", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        TextButton(onClick = { model.rollBackRules(version) }) { Text("Roll back") }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: LintRule, hits: Int, known: Boolean) {
    val tint = when (rule.severity) {
        LintSeverity.Error -> StripColor.Red.tint()
        LintSeverity.Warn -> StripColor.Amber.tint()
        LintSeverity.Info -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rule.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Pill(rule.severity.name.lowercase(), tint)
            Spacer(Modifier.width(6.dp))
            Pill(if (known) rule.check else "not checked by this build", if (known) MaterialTheme.colorScheme.onSurfaceVariant else StripColor.Amber.tint())
        }
        Text(
            "${rule.title} · §${rule.section}" + if (hits > 0) " · $hits ${if (hits == 1) "source" else "sources"} hit" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- a small markdown renderer: headings, paragraphs with bold / code, lists, fenced code, tables ----------

internal sealed interface Block {
    data class Heading(val level: Int, val text: String, val number: String?) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String, val ordered: Boolean) : Block
    data class Code(val text: String) : Block
}

internal fun markdownBlocks(markdown: String): List<Block> {
    val out = ArrayList<Block>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    fun flush() {
        if (paragraph.isNotBlank()) out.add(Block.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }
    markdown.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(Block.Code(code.toString().trimEnd()))
                code.setLength(0)
            } else {
                flush()
            }
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code.appendLine(raw)
            return@forEach
        }
        val heading = Regex("""^(#{1,6})\s+(.*)$""").find(line)
        when {
            heading != null -> {
                flush()
                val text = heading.groupValues[2].trim()
                out.add(Block.Heading(heading.groupValues[1].length, text, Regex("""^(\d+(?:\.\d+)*)\.?\s""").find(text)?.groupValues?.get(1)))
            }
            line.isBlank() -> flush()
            Regex("""^\s*[-*]\s+""").containsMatchIn(line) -> {
                flush()
                out.add(Block.Bullet(line.replaceFirst(Regex("""^\s*[-*]\s+"""), ""), ordered = false))
            }
            Regex("""^\s*\d+\.\s+""").containsMatchIn(line) -> {
                flush()
                out.add(Block.Bullet(line.replaceFirst(Regex("""^\s*\d+\.\s+"""), ""), ordered = true))
            }
            line.trimStart().startsWith("|") -> {
                flush()
                if (!Regex("""^\|[\s:|-]+\|$""").matches(line.trim())) out.add(Block.Code(line.trim()))
            }
            else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(line.trim())
        }
    }
    flush()
    if (inCode && code.isNotBlank()) out.add(Block.Code(code.toString().trimEnd()))
    return out
}

/** `**bold**`, `` `code` `` and `[text](link)` inline */
internal fun inlineText(text: String, codeColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val linkless = text.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    while (i < linkless.length) {
        when {
            linkless.startsWith("**", i) -> {
                val end = linkless.indexOf("**", i + 2)
                if (end < 0) {
                    append(linkless.substring(i))
                    i = linkless.length
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(linkless.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
            }
            linkless[i] == '`' -> {
                val end = linkless.indexOf('`', i + 1)
                if (end < 0) {
                    append(linkless.substring(i))
                    i = linkless.length
                } else {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, fontSize = 12.sp))
                    append(linkless.substring(i + 1, end))
                    pop()
                    i = end + 1
                }
            }
            else -> {
                append(linkless[i])
                i++
            }
        }
    }
}

@Composable
internal fun MarkdownBlock(block: Block, machineRuleId: String?) {
    val codeColor = MaterialTheme.colorScheme.primary
    when (block) {
        is Block.Heading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (block.level <= 2) 12.dp else 8.dp)) {
            Text(
                inlineText(block.text, codeColor),
                style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (machineRuleId != null) Pill("machine rule", codeColor)
        }
        is Block.Paragraph -> Text(inlineText(block.text, codeColor), style = MaterialTheme.typography.bodyMedium)
        is Block.Bullet -> Row(modifier = Modifier.padding(start = 8.dp)) {
            Text(if (block.ordered) "·" else "•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
            Text(inlineText(block.text, codeColor), style = MaterialTheme.typography.bodyMedium)
        }
        is Block.Code -> MonoCard(title = null, text = block.text)
    }
}
