package eu.kanade.tachiyomi.ui.sourcelab

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.lab.ExplainEntry
import eu.kanade.tachiyomi.jssource.lab.GeneratedSource
import eu.kanade.tachiyomi.jssource.lab.History
import eu.kanade.tachiyomi.jssource.lab.IssueReport
import eu.kanade.tachiyomi.jssource.lab.LabFileKind
import eu.kanade.tachiyomi.jssource.lab.LabFiles
import eu.kanade.tachiyomi.jssource.lab.LabHistoryIndex
import eu.kanade.tachiyomi.jssource.lab.LabSource
import eu.kanade.tachiyomi.jssource.lab.LabStore
import eu.kanade.tachiyomi.jssource.lab.Lint
import eu.kanade.tachiyomi.jssource.lab.LintFinding
import eu.kanade.tachiyomi.jssource.lab.LintResult
import eu.kanade.tachiyomi.jssource.lab.PartIndex
import eu.kanade.tachiyomi.jssource.lab.RetestJob
import eu.kanade.tachiyomi.jssource.lab.RulesFile
import eu.kanade.tachiyomi.jssource.lab.RulesParser
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.ui.codeeditor.SourceEditorScreenModel
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** The tabs of an open source; the phone shows them as a tab row, the tablet as the rail's "Open source" group */
internal enum class LabTab(val label: String) { Code("Code"), Explain("Explain"), Test("Test"), Lint("Lint"), Report("Report"), History("History") }

/**
 * KMK (r96): the Source Lab's state around the Code editor's model: the source list with health, the open source's
 * Test Run, Lint, Explain parts, history and Issue Report. The editor model (code, Details, Save, manual tests) is
 * the screen's second screen model; the Lab calls it for the Test Run's runtime and for code changes (Apply, Restore).
 */
internal class SourceLabScreenModel(
    initialKey: String?,
    initialTab: LabTab,
    private val manager: JsSourceManager = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateScreenModel<SourceLabScreenModel.State>(State(key = initialKey, tab = initialTab)) {

    @Immutable
    data class State(
        /** The open source, or null for Home */
        val key: String? = null,
        val tab: LabTab = LabTab.Code,
        val sources: List<LabSource> = emptyList(),
        val rules: RulesFile? = null,
        val rulesError: String? = null,
        val retestLast: String = "",
        val retestProgress: String? = null,
        // the open source
        val lastRun: TestRun? = null,
        val previousRun: TestRun? = null,
        val runSteps: List<TestStep> = emptyList(),
        val running: Boolean = false,
        val lint: LintResult? = null,
        val linting: Boolean = false,
        val explain: List<ExplainEntry> = emptyList(),
        val highlight: IntRange? = null,
        val revealLine: Int? = null,
        val selectedPart: String? = null,
        val history: LabHistoryIndex = LabHistoryIndex(),
        val selectedVersion: Int? = null,
        val diff: List<History.DiffLine> = emptyList(),
        val reportOptions: IssueReport.Options = IssueReport.Options(),
        val reportPlan: List<IssueReport.Entry> = emptyList(),
        val reportBuilding: Boolean = false,
        val reportFile: File? = null,
        /** The outcome of a generated source's id ("Keeps the extension's id …"), shown once */
        val idNote: String? = null,
        val message: String? = null,
    ) {
        val source: LabSource? get() = key?.let { k -> sources.firstOrNull { it.key == k } }
    }

    private var runJob: Job? = null

    init {
        screenModelScope.launchIO {
            LabStore.sources().collect { list -> mutableState.update { it.copy(sources = list) } }
        }
        loadRules()
        refreshRetest()
        state.value.key?.let { loadSource(it) }
    }

    // ---------- navigation inside the Lab ----------

    fun open(key: String?, tab: LabTab = LabTab.Code) {
        runJob?.cancel()
        mutableState.update {
            it.copy(
                key = key,
                tab = tab,
                lastRun = null,
                previousRun = null,
                runSteps = emptyList(),
                running = false,
                lint = null,
                explain = emptyList(),
                highlight = null,
                revealLine = null,
                selectedPart = null,
                history = LabHistoryIndex(),
                selectedVersion = null,
                diff = emptyList(),
                reportPlan = emptyList(),
                reportFile = null,
                idNote = null,
            )
        }
        key?.let { loadSource(it) }
    }

    fun setTab(tab: LabTab) = mutableState.update { it.copy(tab = tab) }

    fun consumeMessage(text: String) = mutableState.update { if (it.message == text) it.copy(message = null) else it }

    fun consumeIdNote() = mutableState.update { it.copy(idNote = null) }

    private fun message(text: String) = mutableState.update { it.copy(message = text) }

    /** Re-reads the open source's sidecars (after a save, a run, a lint, a restore) */
    fun loadSource(key: String, code: String? = null) {
        screenModelScope.launchIO {
            val info = manager.get(key)
            val text = code ?: manager.code(key)
            val runs = runCatching { TestRuns.list(key) }.getOrDefault(emptyList())
            val lint = runCatching { Lint.read(key) }.getOrNull()
            val history = runCatching { History.index(key) }.getOrDefault(LabHistoryIndex())
            val explain = if (info != null && text != null) {
                runCatching { PartIndex.of(text, info.json, LabStore.explain(key)?.entries) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val plan = runCatching { IssueReport.plan(key, state.value.reportOptions) }.getOrDefault(emptyList())
            mutableState.update {
                if (it.key != key) return@update it
                it.copy(
                    lastRun = runs.getOrNull(0),
                    previousRun = runs.getOrNull(1),
                    lint = lint,
                    explain = explain,
                    history = history,
                    reportPlan = plan,
                )
            }
        }
    }

    /** The Explain parts follow the code as it is typed (the editor's 1.2 s debounce is on the caller) */
    fun relineExplain(code: String, json: Boolean) {
        val key = state.value.key ?: return
        screenModelScope.launchIO {
            val entries = runCatching { PartIndex.of(code, json, LabStore.explain(key)?.entries) }.getOrDefault(emptyList())
            mutableState.update { if (it.key == key) it.copy(explain = entries) else it }
        }
    }

    // ---------- Explain ----------

    /** Lights up a part's lines in the code and scrolls to its first one */
    fun showPart(entry: ExplainEntry) {
        val lines = entry.lines
        mutableState.update { it.copy(selectedPart = entry.id, highlight = lines, revealLine = null) }
        if (lines != null) {
            screenModelScope.launchIO {
                delay(30)
                mutableState.update { it.copy(revealLine = lines.first) }
            }
        }
    }

    fun revealLine(line: Int) {
        mutableState.update { it.copy(revealLine = null) }
        screenModelScope.launchIO {
            delay(30)
            mutableState.update { it.copy(revealLine = line, highlight = line..line) }
        }
    }

    // ---------- Test Run ----------

    /** Runs the pipeline on the editor's test-copy runtime; cancelling leaves no file behind */
    fun runTest(editor: SourceEditorScreenModel, query: String) {
        val key = state.value.key ?: return
        runJob?.cancel()
        RetestJob.lastQuery().set(query)
        mutableState.update { it.copy(running = true, runSteps = emptyList()) }
        runJob = screenModelScope.launchIO {
            try {
                val s = editor.state.value
                val rt = editor.testRuntime()
                val info = manager.get(key)
                val run = TestRuns.run(
                    key = key,
                    rt = rt,
                    anime = s.anime,
                    headers = info?.headers.orEmpty(),
                    baseUrl = s.baseUrl.ifBlank { info?.baseUrl.orEmpty() },
                    query = query,
                    onProgress = { steps -> mutableState.update { it.copy(runSteps = steps) } },
                )
                mutableState.update { it.copy(running = false, runSteps = run.steps, previousRun = it.lastRun, lastRun = run) }
                runCatching { Lint.run(key) }.onFailure { AppLog.add('W', "lab", "Lint after the Test Run failed: ${it.message}") }
                LabStore.invalidate(key)
                loadSource(key)
            } catch (e: CancellationException) {
                mutableState.update { it.copy(running = false) }
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "Test Run of $key failed: $e")
                mutableState.update { it.copy(running = false) }
                message("Test Run failed: ${e.message}")
            }
        }
    }

    fun cancelTest() {
        runJob?.cancel()
        runJob = null
        mutableState.update { it.copy(running = false) }
    }

    // ---------- Lint ----------

    fun lintNow() {
        val key = state.value.key ?: return
        mutableState.update { it.copy(linting = true) }
        screenModelScope.launchIO {
            val result = runCatching { Lint.run(key) }.getOrNull()
            mutableState.update { if (it.key == key) it.copy(lint = result ?: it.lint, linting = false) else it.copy(linting = false) }
            if (result == null) message("Lint needs a saved source and a rules file")
        }
    }

    fun ignoreRule(ruleId: String, ignored: Boolean) {
        val key = state.value.key ?: return
        screenModelScope.launchIO {
            Lint.ignore(key, ruleId, ignored)
            mutableState.update { if (it.key == key) it.copy(lint = Lint.read(key)) else it }
        }
    }

    /**
     * Puts the finding's fix into the editor (not saved: the owner saves) and notes it for the next version.
     * Returns false when the code changed since and the fix no longer applies exactly once.
     */
    fun applyFix(editor: SourceEditorScreenModel, finding: LintFinding): Boolean {
        val rules = state.value.rules ?: return false
        val rule = rules.rules.firstOrNull { it.id == finding.ruleId } ?: return false
        val s = editor.state.value
        val key = state.value.key
        val fixed = runCatching {
            val explain = key?.let { LabStore.explain(it)?.entries }
            Lint.apply(s.code, finding, rule, s.json, explain)
        }.getOrElse { e ->
            AppLog.add('W', "lab", "Applying ${rule.id} failed: $e")
            message("Couldn't apply ${rule.id}: ${e.message}")
            return false
        } ?: run {
            message("${rule.id} no longer applies: the code changed since the lint. Run Lint again.")
            return false
        }
        editor.setCode(fixed)
        editor.saveNote = "Lint fix applied: ${rule.id}"
        message("Applied ${rule.id} at line ${finding.line}. Save to keep it.")
        return true
    }

    // ---------- Rules file ----------

    fun loadRules() {
        screenModelScope.launchIO {
            val rules = runCatching { Lint.rules() }.getOrNull()
            mutableState.update { it.copy(rules = rules) }
        }
    }

    /** Imports a rules file; a bad one fails with its line number and the current one stays */
    fun updateRules(text: String, fileName: String?) {
        screenModelScope.launchIO {
            val result = runCatching { LabFiles.store(LabFileKind.Rules).import("rules.md", text, fileName) }
            result.onSuccess { version ->
                mutableState.update { it.copy(rulesError = null) }
                loadRules()
                message("Rules file v$version is current")
            }.onFailure { e ->
                mutableState.update { it.copy(rulesError = e.message) }
                message("Not imported: ${e.message}")
            }
        }
    }

    fun rollBackRules(version: Int) {
        screenModelScope.launchIO {
            val ok = runCatching { LabFiles.store(LabFileKind.Rules).rollBack("rules.md", version) }.getOrDefault(false)
            loadRules()
            message(if (ok) "Rules file ${if (version <= 0) "built-in" else "v$version"} is current" else "Couldn't roll back")
        }
    }

    /** The rules file's versions (newest first) with the asset as version 0 when it exists */
    fun rulesVersions(): List<Pair<Int, String>> {
        val store = LabFiles.store(LabFileKind.Rules)
        val imported = store.versions("rules.md").map { v ->
            v.version to "v${v.version} · imported ${v.fileName?.let { "from $it" }.orEmpty()}"
        }
        val assetVersion = runCatching {
            context.assets.open("sourcelab/rules.md").bufferedReader().use { it.readText() }
        }.getOrNull()?.let { RulesParser.parse(it, null, null).version }
        return imported + listOfNotNull(assetVersion?.let { 0 to "v$it · built in" })
    }

    fun exportRules(): File? = runCatching { LabFiles.store(LabFileKind.Rules).export("rules.md") }.getOrNull()

    /** Lints every saved source now */
    fun checkAllSources() {
        screenModelScope.launchIO {
            val results = runCatching { Lint.runAll() }.getOrDefault(emptyMap())
            val findings = results.values.sumOf { r -> r.findings.count { !it.ignored } }
            val hit = results.values.count { r -> r.findings.any { !it.ignored } }
            message("Checked ${results.size} sources: $findings findings in $hit")
            state.value.key?.let { loadSource(it) }
        }
    }

    // ---------- History ----------

    fun selectVersion(n: Int?, currentCode: String) {
        val key = state.value.key ?: return
        mutableState.update { it.copy(selectedVersion = n, diff = emptyList()) }
        if (n == null) return
        screenModelScope.launchIO {
            val old = History.code(key, n) ?: return@launchIO
            val diff = History.diff(old, currentCode)
            mutableState.update { if (it.key == key && it.selectedVersion == n) it.copy(diff = diff) else it }
        }
    }

    /** Restore vN: the version's code becomes a new version through the manager's save; the editor reloads */
    fun restore(editor: SourceEditorScreenModel, n: Int) {
        val key = state.value.key ?: return
        screenModelScope.launchIO {
            LabStore.restore(key, n)
                .onSuccess {
                    editor.open(key)
                    loadSource(key)
                    message("Restored v$n as v${History.latestVersion(key)}")
                }
                .onFailure { message("Couldn't restore v$n: ${it.message}") }
        }
    }

    // ---------- Issue Report ----------

    fun setReportOptions(options: IssueReport.Options) {
        val key = state.value.key ?: return
        mutableState.update { it.copy(reportOptions = options) }
        screenModelScope.launchIO {
            val plan = runCatching { IssueReport.plan(key, options) }.getOrDefault(emptyList())
            mutableState.update { if (it.key == key) it.copy(reportPlan = plan) else it }
        }
    }

    /** Builds the zip and opens the share sheet from [uiContext] (an Activity: the share intent needs one) */
    fun buildReport(uiContext: android.content.Context) {
        val key = state.value.key ?: return
        mutableState.update { it.copy(reportBuilding = true) }
        screenModelScope.launchIO {
            runCatching { IssueReport.build(key, state.value.reportOptions) }
                .onSuccess { file ->
                    mutableState.update { it.copy(reportBuilding = false, reportFile = file) }
                    withContext(Dispatchers.Main) { IssueReport.share(uiContext, file) }
                }
                .onFailure { e ->
                    mutableState.update { it.copy(reportBuilding = false) }
                    message("Couldn't build the report: ${e.message}")
                }
        }
    }

    // ---------- Re-test all ----------

    private fun refreshRetest() {
        val raw = RetestJob.lastResult().get()
        val parts = raw.split('|')
        val text = if (parts.size == 3) {
            val time = parts[0].toLongOrNull() ?: 0L
            "${agoText(time)} · ${parts[1]} of ${parts[2]} passed"
        } else {
            "never run"
        }
        mutableState.update { it.copy(retestLast = text) }
    }

    fun retestAll() {
        if (state.value.retestProgress != null) return
        mutableState.update { it.copy(retestProgress = "starting") }
        screenModelScope.launchIO {
            try {
                RetestJob.runAll { done, total, name ->
                    mutableState.update { it.copy(retestProgress = "${done + 1} of $total · $name") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message("Re-test all stopped: ${e.message}")
            }
            mutableState.update { it.copy(retestProgress = null) }
            refreshRetest()
            state.value.key?.let { loadSource(it) }
        }
    }

    fun setRetestSchedule(nightly: Boolean) {
        RetestJob.schedule().set(if (nightly) "nightly" else "off")
        RetestJob.apply(context)
    }

    fun setRetestWifiOnly(wifiOnly: Boolean) {
        RetestJob.wifiOnly().set(wifiOnly)
        RetestJob.apply(context)
    }

    fun retestNightly(): Boolean = RetestJob.schedule().get() == "nightly"

    fun retestWifiOnly(): Boolean = RetestJob.wifiOnly().get()

    fun lastQuery(): String = RetestJob.lastQuery().get()

    // ---------- the hand-off from the New-source flow ----------

    /**
     * Saves what Half B generated as version 1 and opens it on Explain; when the contract script fails, the code
     * opens unsaved in the editor with the save error, so a template bug never loses it (C§5.2).
     *
     * [gate] runs the editor-replacing step, now or after the screen's unsaved-changes dialog (Save / Discard /
     * Cancel) when the editor is dirty: `(what, open) -> askBeforeReplacing(what, open)`. The save itself never
     * waits for it; a cancelled open leaves the new source listed on Home.
     */
    fun adoptGenerated(
        editor: SourceEditorScreenModel,
        generated: GeneratedSource,
        gate: (what: String, open: () -> Unit) -> Unit = { _, open -> open() },
    ) {
        screenModelScope.launchIO {
            when (val adopted = LabStore.saveGenerated(generated)) {
                is LabStore.Adopted.Saved -> {
                    message("${adopted.info.name} saved as v1 (${generated.note})")
                    withContext(Dispatchers.Main) {
                        gate("Opening ${adopted.info.name}") {
                            editor.open(adopted.info.key)
                            open(adopted.info.key, LabTab.Explain)
                            mutableState.update { it.copy(idNote = adopted.idNote) }
                        }
                    }
                }
                is LabStore.Adopted.Unsaved -> {
                    withContext(Dispatchers.Main) {
                        gate("Opening the generated ${adopted.generated.suggestedName.ifBlank { "source" }} (it couldn't be saved)") {
                            editor.openUnsaved(
                                key = adopted.key,
                                code = adopted.generated.code,
                                json = adopted.generated.json,
                                anime = adopted.generated.animeHint,
                                name = adopted.generated.suggestedName,
                                baseUrl = adopted.generated.baseUrl,
                                error = adopted.error,
                            )
                            open(adopted.key, LabTab.Code)
                        }
                    }
                }
            }
        }
    }

    /** ⋮ Duplicate: a copy under a new key, " copy" after the name, no `id` line; opens it */
    fun duplicate(editor: SourceEditorScreenModel) {
        val key = state.value.key ?: return
        screenModelScope.launchIO {
            runCatching { manager.duplicate(key) }
                .onSuccess { copy ->
                    if (copy == null) {
                        message("Save the source first")
                    } else {
                        editor.open(copy.key)
                        open(copy.key, LabTab.Code)
                        message("Duplicated as ${copy.name}")
                    }
                }
                .onFailure { message("Couldn't duplicate: ${it.message}") }
        }
    }

    /** Called when the editor saved: the open key may have just become a saved source */
    fun onEditorSaved(info: JsSourceInfo) {
        if (state.value.key == null || state.value.key == info.key) {
            mutableState.update { it.copy(key = info.key) }
            loadSource(info.key)
        }
    }

    override fun onDispose() {
        runJob?.cancel()
        super.onDispose()
    }

    companion object {
        fun agoText(time: Long): String {
            if (time <= 0L) return "never"
            val minutes = (System.currentTimeMillis() - time) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 60 * 48 -> "${minutes / 60} h ago"
                else -> "${minutes / 60 / 24} days ago"
            }
        }
    }
}
