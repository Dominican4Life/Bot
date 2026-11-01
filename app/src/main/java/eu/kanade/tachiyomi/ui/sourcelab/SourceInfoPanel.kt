package eu.kanade.tachiyomi.ui.sourcelab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import eu.kanade.tachiyomi.ui.codeeditor.SourceTemplates
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK --> (r101) the Source info panel: the source's name, language, addresses, kind and format beside the code
// (tablet) or in a bottom sheet from the Code tab's header row (phone). Replaces the Code tab's folding Details strip.

/** Whether the tablet's Source info panel is open (kept across sessions) and how to change it */
internal class SourceInfoToggle(val open: Boolean, val set: (Boolean) -> Unit)

@Composable
internal fun rememberSourceInfoOpen(): SourceInfoToggle {
    val preference = remember { Injekt.get<PreferenceStore>().getBoolean(SOURCE_INFO_OPEN_KEY, true) }
    val open by preference.collectAsState()
    return SourceInfoToggle(open) { preference.set(it) }
}

private const val SOURCE_INFO_OPEN_KEY = "pref_lab_source_info_open"

/**
 * The Source info panel. [onHide] draws the hide button (›) in the header (tablet); null in the phone's sheet.
 * The JS / JSON chips are offered only while the code is empty or an untouched template: once a source has its
 * own code the format is fixed (flipping it broke a JS source, r101) and nothing about it is shown (r102).
 * [onEditByTapping] (r102) draws "Edit by tapping" at the bottom for a saved source the Lab wrote.
 */
@Composable
internal fun SourceInfoPanel(
    editor: SourceEditorScreenModel,
    editorState: SourceEditorScreenModel.State,
    labState: SourceLabScreenModel.State,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onEditByTapping: (() -> Unit)? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfoIcon(editorState.iconUrl, size = 32)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text("Source info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (onHide != null) "Shown with the code · hide with ›" else "Name, addresses and kind",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onHide != null) {
                IconButton(onClick = onHide) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Hide source info")
                }
            }
        }

        OutlinedTextField(
            value = editorState.name,
            onValueChange = editor::setName,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editorState.lang,
            onValueChange = editor::setLang,
            label = { Text("Language") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editorState.baseUrl,
            onValueChange = editor::setBaseUrl,
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editorState.iconUrl,
            onValueChange = editor::setIconUrl,
            label = { Text("Icon URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Left empty, these come from the source's own name, lang, baseUrl and iconUrl.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        InfoLabel("Kind")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !editorState.anime, onClick = { editor.setAnime(false) }, label = { Text("Manga") })
            FilterChip(selected = editorState.anime, onClick = { editor.setAnime(true) }, label = { Text("Anime") })
        }

        // KMK (r102): the Format card is gone; the JS / JSON chips stay only while the code is empty or an untouched
        // template (once a source has its own code the format is fixed and needs no card)
        val formatOpen = editorState.code.isBlank() || SourceTemplates.isTemplate(editorState.code)
        if (formatOpen) {
            InfoLabel("Format")
            // Still an untouched template (or nothing): switching swaps the template, nothing is lost
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !editorState.json, onClick = { editor.setJson(false) }, label = { Text("JS extension") })
                FilterChip(selected = editorState.json, onClick = { editor.setJson(true) }, label = { Text("JSON declarative") })
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val url = editorState.baseUrl.trim()
                    if (url.startsWith("http")) {
                        navigator.push(WebViewScreen(url = url, initialTitle = editorState.name.ifBlank { url }, sourceId = null))
                    } else {
                        context.toast("Set the Base URL first")
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in browser")
            }
            OutlinedButton(onClick = { editor.openSettings() }) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Source settings")
            }
        }

        val facts = sourceFacts(editorState, labState)
        if (facts.isNotEmpty()) {
            HorizontalDivider()
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    facts.forEach { (label, value) ->
                        Row {
                            Text(
                                "$label ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        // KMK (r102): Edit by tapping, only for sources the builder wrote (generic-list): opens the builder on this source.
        // Detected engines / imported / hand-written sources have lab meta too but no picks the builder could edit.
        val labMeta = labState.source?.lab
        val builtByBuilder = labMeta != null &&
            (labMeta.engine == "generic-list" || labMeta.templateId?.startsWith("generic-list") == true)
        if (onEditByTapping != null && editorState.saved && builtByBuilder) {
            HorizontalDivider()
            FilledTonalButton(onClick = onEditByTapping, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit by tapping")
            }
            Text(
                "Opens the site with this source's picks; Save to this source there makes a new version.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The hidden panel: a 44 dp strip on the right (icon + vertical "Source info ‹") that opens it again */
@Composable
internal fun SourceInfoStrip(
    iconUrl: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
    ) {
        InfoIcon(iconUrl, size = 28)
        Spacer(Modifier.height(14.dp))
        Text(
            "Source info ‹",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .layout { measurable, _ ->
                    // Measured as one line, then laid out with width and height swapped for the 90° turn
                    val placeable = measurable.measure(Constraints())
                    layout(placeable.height, placeable.width) {
                        placeable.place(
                            x = (placeable.height - placeable.width) / 2,
                            y = (placeable.width - placeable.height) / 2,
                        )
                    }
                }
                .rotate(90f),
        )
    }
}

@Composable
private fun InfoIcon(iconUrl: String, size: Int) {
    AsyncImage(
        model = iconUrl.ifBlank { null },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun InfoLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** "Written from …", the source id and the parts tested / failed, each only when known */
@Composable
private fun sourceFacts(
    editorState: SourceEditorScreenModel.State,
    labState: SourceLabScreenModel.State,
): List<Pair<String, String>> {
    val sourceId = remember(editorState.key, editorState.saved, labState.sources) {
        if (editorState.saved) Injekt.get<JsSourceManager>().get(editorState.key)?.id else null
    }
    val facts = mutableListOf<Pair<String, String>>()
    val lab = labState.source?.lab
    when {
        lab?.templateId != null -> facts += "Written from" to "${lab.templateId} template v${lab.templateVersion ?: 1}"
        lab?.fromExtension != null -> facts += "Imported from" to lab.fromExtension
    }
    sourceId?.let { facts += "Source id" to it.toString() }
    val parts = labState.explain
    val run = labState.lastRun
    if (parts.isNotEmpty()) {
        if (run != null) {
            // The same reading as the Explain pane's pills: a part's status is its best non-skipped step
            val statuses = run.steps
                .filter { it.status != StepStatus.Skipped }
                .flatMap { step -> step.partIds.map { it to step.status } }
                .groupBy({ it.first }, { it.second })
            val shown = parts.mapNotNull { part -> statuses[part.id]?.minByOrNull { it.ordinal } }
            val failed = shown.count { it == StepStatus.Red }
            facts += "Parts" to "${parts.size} · ${shown.size - failed} tested · $failed failed"
        } else {
            facts += "Parts" to "${parts.size} · not tested yet"
        }
    }
    return facts
}
// KMK <--
