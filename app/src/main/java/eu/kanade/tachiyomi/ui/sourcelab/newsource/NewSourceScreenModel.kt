package eu.kanade.tachiyomi.ui.sourcelab.newsource

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.ApkImport
import eu.kanade.tachiyomi.jssource.lab.DetectionResult
import eu.kanade.tachiyomi.jssource.lab.DetectionStep
import eu.kanade.tachiyomi.jssource.lab.Detector
import eu.kanade.tachiyomi.jssource.lab.EngineGuess
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder
import eu.kanade.tachiyomi.jssource.lab.FingerprintFile
import eu.kanade.tachiyomi.jssource.lab.Fingerprints
import eu.kanade.tachiyomi.jssource.lab.GeneratedSource
import eu.kanade.tachiyomi.jssource.lab.Generator
import eu.kanade.tachiyomi.jssource.lab.LabHandoff
import eu.kanade.tachiyomi.jssource.lab.LabMeta
import eu.kanade.tachiyomi.jssource.lab.LabTemplate
import eu.kanade.tachiyomi.jssource.lab.RecordedConverter
import eu.kanade.tachiyomi.jssource.lab.StepStatus
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r96, Source Lab): the state behind the New-source screen: the URL and kind chips, the detection as it
 * runs, the fingerprint card, and the "Or start from" ways in (a template, Build by tapping, Import a file,
 * Duplicate a source, Import from extension). Nothing is saved here: a finished source is handed to Half A through
 * [LabHandoff] (CONTRACT-LAB §5.2).
 *
 * KMK (r98): Import from extension's second level, "Record and convert": [startRecording] runs the extension's own
 * source through [ExtensionRecorder] (only on the owner's tap) and [RecordedConverter] writes the JS from it.
 */
internal class NewSourceScreenModel(kindHint: Boolean?) : StateScreenModel<NewSourceScreenModel.State>(State(kindHint = kindHint)) {

    enum class Phase { Entry, Detecting, Detected }

    /** Which picker is open over the entry screen */
    enum class Picker { None, Template, Duplicate, Extension }

    @Immutable
    data class State(
        val url: String = "",
        val kindHint: Boolean? = null,                 // null = let the Lab decide, false = manga, true = anime
        val phase: Phase = Phase.Entry,
        val steps: List<DetectionStep> = emptyList(),
        val result: DetectionResult? = null,
        val fingerprints: FingerprintFile? = null,
        val templates: List<LabTemplate> = emptyList(),
        val picker: Picker = Picker.None,
        val savedSources: List<Pair<String, String>> = emptyList(),   // key to name, for Duplicate
        val extensions: List<ApkImport.ExtensionSourceRow>? = null,   // null while loading
        val extensionImport: ApkImport.ImportResult? = null,          // the card after an APK was read
        // KMK (r98): Record and convert
        val extensionRow: ApkImport.ExtensionSourceRow? = null,       // the source the card is about
        val recordSteps: List<ExtensionRecorder.StepProgress>? = null, // non-null once a recording started
        val recording: Boolean = false,                               // running now
        val recorded: RecordedConverter.Result? = null,               // the summary after the conversion
        val recordError: String? = null,
        val busy: String? = null,                                     // "Reading the APK…", "Writing…"
        val error: String? = null,
        val botCheckUrl: String? = null,                              // a detection step hit a bot check
        val delivered: Boolean = false,                               // the hand-off happened: the screen pops
    ) {
        val requests: Int get() = steps.count { it.url != null && it.status != StepStatus.Skipped && it.status != StepStatus.Running }
        val best: EngineGuess? get() = result?.best
        val templateForBest: LabTemplate? get() = best?.let { b -> templates.firstOrNull { it.id == b.templateId } }
    }

    private val context: Context get() = Injekt.get<Application>()
    private var detection: Job? = null
    private var record: Job? = null

    init {
        screenModelScope.launchIO {
            val fingerprints = runCatching { Fingerprints.load() }.getOrNull()
            val templates = runCatching { Templates.list() }.getOrElse { emptyList() }
            mutableState.update { it.copy(fingerprints = fingerprints, templates = templates) }
        }
    }

    fun setUrl(url: String) = mutableState.update { it.copy(url = url, error = null) }

    fun setKind(kind: Boolean?) = mutableState.update { it.copy(kindHint = kind) }

    fun openPicker(picker: Picker) {
        mutableState.update { it.copy(picker = picker, error = null, extensionImport = null, extensionRow = null) }
        when (picker) {
            Picker.Duplicate -> mutableState.update { s ->
                s.copy(savedSources = Injekt.get<JsSourceManager>().savedFlow.value.map { it.key to it.name })
            }
            Picker.Extension -> if (state.value.extensions == null) {
                screenModelScope.launchIO {
                    val rows = runCatching { ApkImport.installed() }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrElse { emptyList() }
                    mutableState.update { it.copy(extensions = rows) }
                }
            }
            else -> Unit
        }
    }

    fun closePicker() {
        record?.cancel()
        record = null
        mutableState.update {
            it.copy(
                picker = Picker.None,
                extensionImport = null,
                extensionRow = null,
                recordSteps = null,
                recording = false,
                recorded = null,
                recordError = null,
            )
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    // ---------- detection ----------

    fun detect() {
        val url = state.value.url.trim()
        if (url.isBlank()) {
            mutableState.update { it.copy(error = "Paste the site's address first") }
            return
        }
        detection?.cancel()
        mutableState.update { it.copy(phase = Phase.Detecting, steps = emptyList(), result = null, error = null, botCheckUrl = null) }
        detection = screenModelScope.launchIO {
            try {
                val detector = Detector(context, state.value.fingerprints ?: Fingerprints.load())
                val result = detector.detect(url, state.value.kindHint) { steps ->
                    mutableState.update { it.copy(steps = steps) }
                }
                val botCheck = result.steps.firstOrNull { it.status == StepStatus.Red && "bot check" in it.note.orEmpty() }
                mutableState.update {
                    it.copy(phase = Phase.Detected, result = result, steps = result.steps, botCheckUrl = botCheck?.url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "Detection of $url failed: ${e.message}")
                mutableState.update { it.copy(phase = Phase.Entry, error = "Detection failed: ${e.message ?: e.toString()}") }
            }
        }
    }

    fun backToEntry() {
        detection?.cancel()
        mutableState.update { it.copy(phase = Phase.Entry, steps = emptyList(), result = null, botCheckUrl = null) }
    }

    /** "Write the source": the best engine's template filled with what detection found */
    fun writeDetected() {
        val s = state.value
        val best = s.best ?: return
        val template = s.templateForBest ?: run {
            mutableState.update { it.copy(error = "No template named ${best.templateId} in the app") }
            return
        }
        deliver("Writing the source…") {
            val lab = LabMeta(
                engine = best.engineId,
                confidence = best.confidence,
                markers = best.markers,
                templateId = template.id,
                templateVersion = template.version,
                detectedAt = System.currentTimeMillis(),
                siteUrl = s.result?.siteUrl,
            )
            Generator.generate(template = template, values = best.fill, lab = lab)
        }
    }

    // ---------- the other ways in ----------

    /** "A template": the chosen template with blank fills (the owner edits the code afterwards) */
    fun writeTemplate(template: LabTemplate) {
        deliver("Writing from the ${template.name} template…") {
            val site = state.value.url.trim().takeIf { it.isNotBlank() }?.let { Detector.normalize(it) }
            val host = site?.removePrefix("https://")?.removePrefix("http://")
            val values = mapOf(
                "name" to (host?.removePrefix("www.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() } ?: "New ${template.name} source"),
                "lang" to "en",
                "baseUrl" to (site ?: "https://example.com"),
                "iconUrl" to (host?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" } ?: ""),
            )
            Generator.generate(
                template = template,
                values = values,
                lab = LabMeta(templateId = template.id, templateVersion = template.version, siteUrl = site),
                note = "Generated from the ${template.name} template",
            )
        }
    }

    /** "Import a file": the text as it is; A's adopt path saves it */
    fun importFile(uri: Uri) {
        deliver("Reading the file…") {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "file"
            val text = resolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: throw IllegalStateException("The file could not be opened")
            val json = name.endsWith(".json", ignoreCase = true) || (!name.endsWith(".js", ignoreCase = true) && text.trimStart().startsWith("{"))
            val anime = "getVideoList" in text || "\"videos\"" in text
            GeneratedSource(
                code = text,
                json = json,
                animeHint = anime,
                suggestedName = quickValue(text, "name") ?: name.substringBeforeLast('.'),
                baseUrl = quickValue(text, "baseUrl").orEmpty(),
                explain = emptyList(),
                lab = LabMeta(),
                note = "Imported from $name",
            )
        }
    }

    /** "Duplicate a source": the saved code under a new key; A strips the `id` line (CONTRACT-LAB §7.1) */
    fun duplicate(key: String) {
        deliver("Copying the source…") {
            val manager = Injekt.get<JsSourceManager>()
            val info = manager.get(key) ?: throw IllegalStateException("That source is gone")
            val code = manager.code(key) ?: throw IllegalStateException("The code of ${info.name} could not be read")
            GeneratedSource(
                code = code,
                json = info.json,
                animeHint = info.anime,
                suggestedName = info.name + " copy",
                baseUrl = info.baseUrl,
                explain = emptyList(),
                lab = LabMeta(),
                note = "Duplicated from ${info.name}",
            )
        }
    }

    /** "Import from extension": reads the APK and shows the card; [confirmExtensionImport] hands it over */
    fun importExtension(row: ApkImport.ExtensionSourceRow) {
        mutableState.update { it.copy(busy = "Reading the APK of ${row.extensionName}…", error = null, extensionRow = row) }
        screenModelScope.launchIO {
            try {
                val result = withIOContext { ApkImport.import(context, row) }
                mutableState.update { it.copy(busy = null, extensionImport = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "Import from ${row.pkgName} failed: ${e.message}")
                mutableState.update { it.copy(busy = null, error = "Import failed: ${e.message ?: e.toString()}") }
            }
        }
    }

    fun confirmExtensionImport() {
        val result = state.value.extensionImport ?: return
        deliver("Writing the source…") { result.generated }
    }

    // ---------- KMK (r98): Record and convert ----------

    /**
     * Runs the chosen extension's own source (popular 1 and 2, latest, a search, the first title's details and
     * chapters or episodes, the first chapter's pages or the first episode's videos) and converts what it did. Only
     * ever started by the owner's tap; each step is time-boxed by the recorder, and nothing touches the library.
     */
    fun startRecording() {
        val row = state.value.extensionRow ?: return
        if (record?.isActive == true) return
        mutableState.update { it.copy(recordSteps = emptyList(), recording = true, recorded = null, recordError = null, error = null) }
        record = screenModelScope.launchIO {
            try {
                val recording = ExtensionRecorder.record(context, row) { steps ->
                    mutableState.update { it.copy(recordSteps = steps) }
                }
                val result = RecordedConverter.convert(recording, state.value.extensionImport?.facts)
                mutableState.update { it.copy(recording = false, recorded = result) }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(recording = false, recordError = "Stopped") }
                throw e
            } catch (e: Throwable) {
                val described = ApkImport.describe(e)
                AppLog.add('E', "lab", "Record and convert of ${row.sourceName} (${row.pkgName}) failed: $described")
                mutableState.update { it.copy(recording = false, recordError = described) }
            }
        }
    }

    fun stopRecording() {
        record?.cancel()
        record = null
    }

    /** Back from the record card to the import card (the APK card stays) */
    fun closeRecording() {
        record?.cancel()
        record = null
        mutableState.update { it.copy(recordSteps = null, recording = false, recorded = null, recordError = null) }
    }

    fun confirmRecorded() {
        val result = state.value.recorded ?: return
        deliver("Writing the source…") { result.generated }
    }

    // ---------- hand-off ----------

    private fun deliver(busy: String, build: suspend () -> GeneratedSource) {
        mutableState.update { it.copy(busy = busy, error = null) }
        screenModelScope.launchIO {
            try {
                val generated = withIOContext { build() }
                LabHandoff.deliver(generated)
                AppLog.add('I', "lab", "Handed over: ${generated.note}")
                mutableState.update {
                    it.copy(busy = null, picker = Picker.None, extensionImport = null, recordSteps = null, recorded = null, delivered = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "New source not written: ${e.message}")
                mutableState.update { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    /** `name: "…"` / `"name": "…"` from a source text, like the editor's quickInfo */
    private fun quickValue(code: String, key: String): String? =
        Regex("""(?:\b$key\s*:|"$key"\s*:)\s*(["'`])((?:\\.|(?!\1).)*)\1""").find(code)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }

    override fun onDispose() {
        detection?.cancel()
        record?.cancel()
        super.onDispose()
    }
}
