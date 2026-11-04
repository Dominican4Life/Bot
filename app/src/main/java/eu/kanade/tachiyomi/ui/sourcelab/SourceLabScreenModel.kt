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
import eu.kanade.tachiyomi.jssource.lab.TemplateUpdate
import eu.kanade.tachiyomi.jssource.lab.Templates
import eu.kanade.tachiyomi.jssource.lab.TestRun
import eu.kanade.tachiyomi.jssource.lab.TestRuns
import eu.kanade.tachiyomi.jssource.lab.TestStep
import eu.kanade.tachiyomi.jssource.lab.heal.HealedPick
import eu.kanade.tachiyomi.jssource.lab.heal.PickHealer
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoDiagnosis
import eu.kanade.tachiyomi.jssource.lab.videodoctor.VideoDoctor
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

/**
 * The tabs of an open source; the phone shows them as a tab row, the tablet as the rail's "this source" group.
 * KMK (r106): History before Report, the order of the approved rail (nothing stores the ordinal).
 */
internal enum class LabTab(val label: String) { Code("Code"), Explain("Explain"), Test("Test"), Lint("Lint"), History("History"), Report("Report") }

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
        /** The History row shown on the right; [EDITOR_VERSION] = the editor's unsaved edits */
        val selectedVersion: Int? = null,
        val diff: List<History.DiffLine> = emptyList(),
        // KMK --> (r106) History's compare: what the selected row is compared with, and the parts the diff touches
        /** The other side: a version number, [EDITOR_VERSION], or null while reading / when there is nothing to compare */
        val compareWith: Int? = null,
        /** "v3 → v4", "v3 → your edits"; empty while reading */
        val diffTitle: String = "",
        val diffParts: List<PartChange> = emptyList(),
        val diffReady: Boolean = false,
        // KMK <--
        val reportOptions: IssueReport.Options = IssueReport.Options(),
        val reportPlan: List<IssueReport.Entry> = emptyList(),
        val reportBuilding: Boolean = false,
        val reportFile: File? = null,
        /** The outcome of a generated source's id ("Keeps the extension's id …"), shown once */
        val idNote: String? = null,
        val message: String? = null,
        /** KMK (r100): the open source's template has a newer version ("Update template"), null when it hasn't */
        val templateOffer: TemplateUpdate.Offer? = null,
        val updatingTemplate: Boolean = false,
        // KMK --> (r115) "Why won't it play" (W7's VideoDoctor) in the Test pane's Video step
        /** Each source's last diagnosis (by key), kept while the Lab is open */
        val diagnoses: Map<String, VideoDiagnosis> = emptyMap(),
        /** The source whose check is running (null: none) and its latest step in words */
        val doctorKey: String? = null,
        val doctorProgress: String? = null,
        // KMK <--
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
                compareWith = null,
                diffTitle = "",
                diffParts = emptyList(),
                diffReady = false,
                reportPlan = emptyList(),
                reportFile = null,
                idNote = null,
                templateOffer = null,
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
            // KMK (r100): a newer version of the template the source was written with
            val offer = runCatching { TemplateUpdate.offer(key) }
                .onFailure { AppLog.add('W', "lab", "Template update check of $key failed: ${it.message}") }
                .getOrNull()
            mutableState.update {
                if (it.key != key) return@update it
                it.copy(
                    lastRun = runs.getOrNull(0),
                    previousRun = runs.getOrNull(1),
                    lint = lint,
                    explain = explain,
                    history = history,
                    reportPlan = plan,
                    templateOffer = offer,
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
                    // KMK (r116): self-healing compares against the code being tested (saved or not)
                    code = s.code,
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

    // KMK --> (r115) "Why won't it play"
    private var doctorJob: Job? = null

    // the newest check: an older, cancelled one never clears the newer one's running mark
    @Volatile private var doctorToken = 0

    /**
     * Runs W7's VideoDoctor on the episode the last Test Run opened, with what the Test Run uses (the editor's test
     * runtime, base URL, the source's headers); the result is kept per source in [State.diagnoses]
     */
    fun checkWhyNoPlay(editor: SourceEditorScreenModel) {
        val key = state.value.key ?: return
        val episode = VideoDoctor.episodeOf(state.value.lastRun)
        if (episode == null) {
            message("Run the test first: the check needs the episode it opened.")
            return
        }
        doctorJob?.cancel()
        val token = ++doctorToken
        mutableState.update { it.copy(doctorKey = key, doctorProgress = null) }
        doctorJob = screenModelScope.launchIO {
            try {
                val s = editor.state.value
                val rt = editor.testRuntime()
                val info = manager.get(key)
                val diagnosis = VideoDoctor.diagnose(
                    context = context,
                    rt = rt,
                    episodeUrl = episode,
                    baseUrl = s.baseUrl.ifBlank { info?.baseUrl.orEmpty() },
                    sourceHeaders = info?.headers.orEmpty(),
                    sourceKey = key,
                    onStep = { text -> if (token == doctorToken) mutableState.update { it.copy(doctorProgress = text) } },
                )
                mutableState.update {
                    it.copy(diagnoses = it.diagnoses + (key to diagnosis), doctorKey = null, doctorProgress = null)
                }
            } catch (e: CancellationException) {
                if (token == doctorToken) mutableState.update { it.copy(doctorKey = null, doctorProgress = null) }
                throw e
            } catch (e: Throwable) {
                AppLog.add('W', "lab", "Why won't it play failed on $key: $e")
                if (token == doctorToken) mutableState.update { it.copy(doctorKey = null, doctorProgress = null) }
                message("The check failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Puts [snippet] into the editor before the last line of the part [partId] (its closing brace), indented one step
     * more than that line, as an unsaved change (nothing is saved). False (with a message) when the part isn't found.
     */
    fun insertIntoPart(editor: SourceEditorScreenModel, partId: String, snippet: String): Boolean {
        val range = state.value.explain.firstOrNull { it.id == partId }?.lines
        if (range == null) {
            message("No $partId part in the code: copy the lines and paste them yourself.")
            return false
        }
        val lines = editor.state.value.code.lines().toMutableList()
        val at = (range.last - 1).coerceIn(0, lines.size)
        val indent = lines.getOrNull(at)?.takeWhile { it == ' ' || it == '\t' }.orEmpty() + "  "
        lines.addAll(at, snippet.trimEnd().lines().map { if (it.isBlank()) "" else indent + it })
        editor.setCode(lines.joinToString("\n"))
        editor.saveNote = "Why won't it play: added a fix to $partId"
        revealLine(at + 1)
        message("Added to $partId at line ${at + 1}. Check it, then Save.")
        return true
    }

    /** A snackbar line from a pane (the Video step's Preview when it can't start) */
    fun say(text: String) = message(text)
    // KMK <--

    // KMK --> (r116) self-healing picks
    /**
     * "Use the new picks and test again": [picks] are written into the editor's code ([PickHealer.applyToCode], the
     * generic-list `var ITEM / TITLE / COVER … = "…"` values) as an unsaved change, then the Test Run runs again on it.
     * Nothing is saved (the owner saves). Code that doesn't change says so and asks for picking by tapping.
     */
    fun useHealedPicks(editor: SourceEditorScreenModel, picks: List<HealedPick>, query: String) {
        if (picks.isEmpty() || state.value.running) return
        val key = state.value.key ?: return
        val code = editor.state.value.code
        val fixed = runCatching { PickHealer.applyToCode(code, picks) }
            .onFailure { AppLog.add('W', "lab", "Self-healing on $key: applying the picks failed: $it") }
            .getOrNull()
        if (fixed == null || fixed == code) {
            message("The picks aren't plain values in this code: pick them again by tapping.")
            return
        }
        val labels = picks.joinToString(", ") { it.label }
        editor.setCode(fixed)
        editor.saveNote = "Self-healing: ${picks.size} ${if (picks.size == 1) "pick" else "picks"} moved ($labels)"
        val moved = picks.joinToString("; ") { "${it.label} ${it.oldSelector} -> ${it.newSelector} (${it.score}%)" }
        AppLog.add('I', "lab", "Self-healing on $key: used $moved")
        message("New picks for $labels are in the editor (not saved). Testing again…")
        runTest(editor, query)
    }
    // KMK <--

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

    // KMK --> (r106) Lint: fix previews and "Fix all"

    /** The findings "Fix all" applies: not ignored, on a line, applicable once, and their rule has a replacement */
    fun safeFixes(findings: List<LintFinding>): List<LintFinding> {
        val rules = state.value.rules?.rules.orEmpty()
        return findings.filter { f ->
            !f.ignored && f.applicable && f.line != null && rules.firstOrNull { it.id == f.ruleId }?.apply != null
        }
    }

    /**
     * What applying [finding] to [code] changes, as a line diff (null when the rule has no replacement or it no longer
     * applies exactly once). Reads the source's explain file: call it off the main thread.
     */
    fun previewFix(code: String, json: Boolean, finding: LintFinding): List<History.DiffLine>? {
        val rule = state.value.rules?.rules?.firstOrNull { it.id == finding.ruleId } ?: return null
        if (rule.apply == null) return null
        val key = state.value.key
        val fixed = runCatching {
            val explain = key?.let { LabStore.explain(it)?.entries }
            Lint.apply(code, finding, rule, json, explain)
        }.getOrNull() ?: return null
        return History.diff(code, fixed)
    }

    /**
     * "Fix all N safe fixes": every [safeFixes] finding is applied to the editor's code, bottom line first so the
     * lines of the ones above don't move. Fixes that no longer apply are skipped and counted. Not saved (the owner saves).
     */
    fun applyAllFixes(editor: SourceEditorScreenModel, findings: List<LintFinding>) {
        val rules = state.value.rules ?: return
        val todo = safeFixes(findings).sortedByDescending { it.line ?: 0 }
        if (todo.isEmpty()) return
        val s = editor.state.value
        val key = state.value.key
        val explain = runCatching { key?.let { LabStore.explain(it)?.entries } }.getOrNull()
        var code = s.code
        val applied = ArrayList<String>()
        todo.forEach { finding ->
            val rule = rules.rules.firstOrNull { it.id == finding.ruleId } ?: return@forEach
            val fixed = runCatching { Lint.apply(code, finding, rule, s.json, explain) }
                .onFailure { AppLog.add('W', "lab", "Fix all: ${rule.id} failed: $it") }
                .getOrNull()
            if (fixed != null && fixed != code) {
                code = fixed
                applied.add(rule.id)
            }
        }
        if (applied.isEmpty()) {
            message("None of the fixes applies any more: the code changed since the lint. Run Lint again.")
            return
        }
        editor.setCode(code)
        editor.saveNote = "Lint fixes applied: ${applied.distinct().joinToString(", ")}"
        AppLog.add('I', "lab", "Fix all on $key: applied ${applied.size} of ${todo.size} (${applied.joinToString(", ")})")
        val skipped = todo.size - applied.size
        message(
            "Applied ${applied.size} ${if (applied.size == 1) "fix" else "fixes"}" +
                (if (skipped > 0) " ($skipped no longer applied)" else "") + ". Save to keep them.",
        )
    }
    // KMK <--

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

    /**
     * KMK (r106): History's compare. Row [n] (a version, or [EDITOR_VERSION] for the editor's unsaved edits) is compared
     * with [against]: null = the one before it (the editor's edits: the open version; the oldest version: the editor),
     * else a version number or [EDITOR_VERSION]. The older side is the diff's "old" text, so '+' reads "added since".
     * [json] places the changed lines in parts.
     */
    fun selectVersion(n: Int?, currentCode: String, against: Int? = null, json: Boolean = false) {
        val key = state.value.key ?: return
        mutableState.update {
            it.copy(selectedVersion = n, diff = emptyList(), compareWith = null, diffTitle = "", diffParts = emptyList(), diffReady = false)
        }
        if (n == null) return
        screenModelScope.launchIO {
            val history = state.value.history
            val numbers = history.versions.map { it.n }
            val base: Int? = against ?: when (n) {
                EDITOR_VERSION -> History.currentOf(history).takeIf { it in numbers }
                else -> numbers.filter { it < n }.maxOrNull() ?: EDITOR_VERSION
            }
            fun textOf(v: Int): String? = if (v == EDITOR_VERSION) currentCode else History.code(key, v)
            fun nameOf(v: Int): String = if (v == EDITOR_VERSION) "your edits" else "v$v"
            val result = runCatching {
                val mine = textOf(n) ?: return@runCatching null
                if (base == null || base == n) return@runCatching Triple(History.diff(mine, mine), "${nameOf(n)} · nothing to compare with", emptyList<PartChange>())
                val other = textOf(base) ?: return@runCatching null
                // The editor is always the newer side; between versions, the lower number is the older one
                val selectedIsNewer = n == EDITOR_VERSION || (base != EDITOR_VERSION && n > base)
                val (oldN, newN) = if (selectedIsNewer) base to n else n to base
                val (oldText, newText) = if (selectedIsNewer) other to mine else mine to other
                val diff = History.diff(oldText, newText)
                val explain = runCatching { LabStore.explain(key)?.entries }.getOrNull()
                val parts = runCatching {
                    LabDiff.partChanges(diff, PartIndex.of(oldText, json, explain), PartIndex.of(newText, json, explain))
                }.getOrDefault(emptyList())
                Triple(diff, "${nameOf(oldN)} → ${nameOf(newN)}", parts)
            }.onFailure { AppLog.add('W', "lab", "History compare of $key v$n failed: ${it.message}") }.getOrNull()
            mutableState.update {
                if (it.key != key || it.selectedVersion != n) return@update it
                if (result == null) {
                    it.copy(diffReady = true, diffTitle = "${nameOf(n)} couldn't be read", compareWith = base)
                } else {
                    it.copy(diff = result.first, diffTitle = result.second, diffParts = result.third, compareWith = base, diffReady = true)
                }
            }
        }
    }

    /**
     * Restore vN: the version's code is written to the source through the manager's save and vN is marked current
     * (r98: no new version); the editor reloads. The caller has already asked about unsaved edits.
     */
    fun restore(editor: SourceEditorScreenModel, n: Int) {
        val key = state.value.key ?: return
        screenModelScope.launchIO {
            LabStore.restore(key, n)
                .onSuccess {
                    editor.open(key)
                    mutableState.update { if (it.key == key) it.copy(selectedVersion = null, diff = emptyList()) else it }
                    loadSource(key)
                    message("v$n is the current code now (no new version)")
                }
                .onFailure { message("Couldn't restore v$n: ${it.message}") }
        }
    }

    /**
     * KMK (r100): "Update template": the offered parts of the saved code are replaced by the template's newer ones and
     * saved as a new version (TemplateUpdate); the editor reloads. The caller has already asked about unsaved edits.
     */
    fun updateTemplate(editor: SourceEditorScreenModel) {
        val key = state.value.key ?: return
        val offer = state.value.templateOffer ?: return
        if (state.value.updatingTemplate) return
        mutableState.update { it.copy(updatingTemplate = true) }
        screenModelScope.launchIO {
            try {
                val source = LabStore.source(key)
                val template = Templates.get(offer.templateId)
                val outcome = if (source == null || template == null) {
                    TemplateUpdate.Outcome.Refused("The source or its template is gone")
                } else {
                    TemplateUpdate.update(source, template)
                }
                when (outcome) {
                    is TemplateUpdate.Outcome.Updated -> {
                        editor.open(key)
                        loadSource(key)
                        message(
                            "Updated to ${outcome.offer.templateName} v${outcome.offer.to} (${outcome.offer.partWords}). " +
                                "Run the Test to check it.",
                        )
                    }
                    is TemplateUpdate.Outcome.Refused -> {
                        AppLog.add('W', "lab", "Template update of $key refused: ${outcome.message}")
                        message("Not updated: ${outcome.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('E', "lab", "Template update of $key failed: $e")
                message("Not updated: ${e.message}")
            } finally {
                mutableState.update { it.copy(updatingTemplate = false) }
            }
        }
    }

    /** KMK (r106): History's "Make vN the baseline" (the caller asked first); the timeline re-reads */
    fun makeBaseline(n: Int) {
        val key = state.value.key ?: return
        val name = state.value.source?.name?.ifBlank { null } ?: key
        screenModelScope.launchIO {
            val ok = runCatching { History.setBaseline(key, n) }
                .onFailure { AppLog.add('W', "lab", "Making v$n the baseline of $name failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) {
                AppLog.add('I', "lab", "Baseline of $name is now v$n")
                LabStore.invalidate(key)
                loadSource(key)
                message("v$n is the baseline now")
            } else {
                message("Couldn't make v$n the baseline (the version is gone)")
            }
        }
    }

    /** KMK (r98): deletes versions [ns] from History (never the current one, never the generated-from one) */
    fun deleteVersions(ns: Set<Int>) {
        val key = state.value.key ?: return
        if (ns.isEmpty()) return
        screenModelScope.launchIO {
            val gone = runCatching { History.delete(key, ns) }
                .onFailure { AppLog.add('W', "lab", "Deleting versions of $key failed: ${it.message}") }
                .getOrDefault(emptyList())
            mutableState.update {
                if (it.key == key && it.selectedVersion?.let { v -> v in gone } == true) it.copy(selectedVersion = null, diff = emptyList()) else it
            }
            LabStore.invalidate(key)
            loadSource(key)
            message(
                when {
                    gone.isEmpty() -> "Nothing deleted (the current version and the generated one are kept)"
                    gone.size == 1 -> "Deleted v${gone.first()}"
                    else -> "Deleted ${gone.size} versions"
                },
            )
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

    // KMK (r102): ⋮ Duplicate is gone (the owner's request); JsSourceManager.duplicate stays for other callers

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
        /** KMK (r106): History's "unsaved edits" row (version numbers start at 1) */
        const val EDITOR_VERSION = 0

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
