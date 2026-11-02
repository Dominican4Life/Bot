package eu.kanade.tachiyomi.ui.sourcelab

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.ApkImport
import eu.kanade.tachiyomi.jssource.lab.ExplainFile
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder
import eu.kanade.tachiyomi.jssource.lab.LabStore
import eu.kanade.tachiyomi.jssource.lab.PartIndex
import eu.kanade.tachiyomi.jssource.lab.RecordedConverter
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.toJson
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r105): "Convert again" (⋮ of a source made by Record and convert): records the extension its metadata names
 * (`lab.fromExtension`, the same source of the package by id) once more, converts it with the current converter and
 * saves the result as a new version of the same source: same key, same id, the owner's name / language / icon / address
 * kept. The screen then opens Test Run ([State.savedAt]). An extension that isn't installed any more is said so.
 */
internal class ConvertAgainScreenModel(
    private val manager: JsSourceManager = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateScreenModel<ConvertAgainScreenModel.State>(State()) {

    @Immutable
    data class State(
        val open: Boolean = false,
        val key: String? = null,
        val title: String = "",                                   // "AllManga · AllManga 1.6.29"
        val steps: List<ExtensionRecorder.StepProgress> = emptyList(),
        val running: Boolean = false,
        val phase: String? = null,                                // "Finding the extension…", "Converting…", "Saving…"
        val result: RecordedConverter.Result? = null,
        val version: Int? = null,                                 // the History version the result was saved as
        val error: String? = null,
        val savedAt: Long = 0L,                                   // set once saved: the screen reloads and opens Test Run
    )

    private var job: Job? = null

    /** Whether [key]'s source can be converted again: made by Record and convert (a recorded-* template, a package) */
    fun offered(fromExtension: String?, templateId: String?): Boolean =
        !fromExtension.isNullOrBlank() && templateId?.startsWith("recorded-") == true

    fun start(key: String) {
        if (job?.isActive == true) return
        mutableState.update { State(open = true, key = key, running = true, phase = "Finding the extension…") }
        job = screenModelScope.launchIO {
            try {
                convertAgainOf(key)
            } catch (e: CancellationException) {
                mutableState.update { it.copy(running = false, phase = null, error = "Stopped: nothing was saved.") }
                throw e
            } catch (e: Throwable) {
                val described = ApkImport.describe(e)
                AppLog.add('E', "lab", "Convert again of $key failed: $described")
                mutableState.update { it.copy(running = false, phase = null, error = described) }
            }
        }
    }

    private suspend fun convertAgainOf(key: String) {
        val info = manager.get(key) ?: return fail("The source is gone.")
        val lab = LabStore.source(info).lab
        val pkg = lab?.fromExtension
        if (pkg.isNullOrBlank() || lab?.templateId?.startsWith("recorded-") != true) {
            return fail("${info.name} wasn't made by Record and convert, so there is no extension to record again.")
        }
        val rows = ApkImport.installed().filter { it.pkgName == pkg }
        if (rows.isEmpty()) {
            AppLog.add('W', "lab", "Convert again of ${info.name}: the extension $pkg isn't installed")
            return fail("The extension $pkg isn't installed (any more). Install it again, then Convert again.")
        }
        // The same source of the package: by the id this source keeps, else by name, language, kind
        val row = rows.firstOrNull { it.sourceId == info.customId }
            ?: rows.firstOrNull { it.sourceName == info.name && it.anime == info.anime }
            ?: rows.firstOrNull { it.lang == info.lang && it.anime == info.anime }
            ?: rows.firstOrNull { it.anime == info.anime }
            ?: rows.first()
        mutableState.update {
            it.copy(title = "${row.sourceName} (${row.lang}) · ${row.extensionName} ${row.versionName}", phase = null)
        }
        AppLog.add('I', "lab", "Convert again of ${info.name} ($key) from ${row.pkgName} ${row.versionName}, source ${row.sourceId}")
        val recording = ExtensionRecorder.record(context, row) { steps -> mutableState.update { it.copy(steps = steps) } }
        mutableState.update { it.copy(phase = "Converting…") }
        // The APK's facts only name its theme in the header; the conversion doesn't need them
        val facts = runCatching { ApkImport.import(context, row).facts }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val result = RecordedConverter.convert(recording, facts)
        mutableState.update { it.copy(phase = "Saving…", result = result) }
        // Same key and same id: the id this source has now (the extension's, when it kept it); a source that has its
        // own id keeps it (no id line)
        val generated = result.generated
        val code = info.customId?.let { LabStore.injectId(generated.code, false, it) }
            ?: LabStore.stripIdLines(generated.code, false)
        val note = "Converted again from ${row.extensionName} ${row.versionName} (Record and convert)" +
            (if (result.todo.isNotEmpty()) "; TODO ${result.todo.joinToString(", ")}" else "")
        val saved = manager.save(
            key = key,
            code = code,
            json = false,
            animeHint = info.anime,
            overrides = JsSourceInfo(
                key = key,
                name = info.name,
                lang = info.lang,
                iconUrl = info.iconUrl,
                baseUrl = info.baseUrl,
                lab = generated.lab.toJson(),
            ),
            note = note,
        )
        runCatching {
            LabStore.writeExplain(
                key,
                ExplainFile(
                    entries = PartIndex.of(code, false, generated.explain),
                    templateId = generated.lab.templateId,
                    templateVersion = generated.lab.templateVersion,
                ),
            )
        }.onFailure { AppLog.add('W', "lab", "Convert again of ${info.name}: explain.json not written: ${it.message}") }
        LabStore.invalidate(key)
        val version = runCatching { LabStore.source(saved).version }.getOrNull()
        AppLog.add(
            'I',
            "lab",
            "Converted ${info.name} again as v${version ?: "?"}: written ${result.written.joinToString(",")}; " +
                "TODO ${result.todo.joinToString(",").ifBlank { "none" }}" +
                (if (result.checks.isNotEmpty()) "; checked ${result.checks.joinToString(", ")}" else ""),
        )
        mutableState.update {
            it.copy(running = false, phase = null, version = version, savedAt = System.currentTimeMillis())
        }
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(running = false, phase = null, error = message) }
    }

    /** The screen opened Test Run for the saved version: not again on the next composition */
    fun consumeSaved() = mutableState.update { it.copy(savedAt = 0L) }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun close() {
        job?.cancel()
        job = null
        mutableState.update { State() }
    }

    override fun onDispose() {
        job?.cancel()
        super.onDispose()
    }
}

/** KMK (r105): the steps of Convert again as they run, then what was written, checked and left as TODO */
@Composable
internal fun ConvertAgainDialog(
    state: ConvertAgainScreenModel.State,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onClose() },
        title = { Text("Convert again") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (state.title.isNotBlank()) Text(state.title, style = MaterialTheme.typography.titleSmall)
                if (state.running) {
                    Text(
                        "The extension runs again with your connection (${ExtensionRecorder.STEP_TIMEOUT_MS / 1000} s per step at " +
                            "most); the result is saved as a new version of this source, same key and id, and Test Run opens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.phase?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
                state.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        val color = when (step.status) {
                            StepStatus.Green -> StripColor.Green
                            StepStatus.Amber -> StripColor.Amber
                            StepStatus.Red -> StripColor.Red
                            StepStatus.Running -> StripColor.Blue
                            StepStatus.Skipped -> StripColor.Neutral
                        }
                        Text("●", color = color.tint(), fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(step.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            step.note?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (step.status == StepStatus.Red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (index < state.steps.lastIndex) HorizontalDivider()
                }
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                val result = state.result
                if (result != null && !state.running && state.error == null) {
                    Spacer(Modifier.height(10.dp))
                    val lines = buildList {
                        add("saved as       ${state.version?.let { "v$it" } ?: "a new version"} (same key and id)")
                        add("written        ${result.written.joinToString(", ").ifBlank { "nothing" }}")
                        add("TODO parts     ${result.todo.joinToString(", ").ifBlank { "none" }}")
                        if (result.checks.isNotEmpty()) add("checked        ${result.checks.joinToString(", ")}")
                        result.botCheck?.let { add("bot check      $it (passed in the hidden browser)") }
                    }
                    MonoCard(title = null, text = lines.joinToString("\n"))
                    Text(
                        "Test Run is running on the new version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    result.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}
