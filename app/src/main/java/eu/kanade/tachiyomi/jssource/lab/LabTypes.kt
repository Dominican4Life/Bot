package eu.kanade.tachiyomi.jssource.lab

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.jssource.lab.heal.HealedPick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// KMK (r96): shared types of the Source Lab, seeded from scratchpad/r96/CONTRACT-LAB.md §3–5. Neither Lab half edits this file.

// ---------- 3.1 Source and health ----------

/** KMK: a saved JS/JSON source as the Lab lists it: the saved info plus its sidecar facts. Built by LabStore. */
@Immutable
internal data class LabSource(
    val key: String,
    val name: String,
    val anime: Boolean,
    val json: Boolean,
    val hasVideos: Boolean,          // from the last contract flags (meta.json has no flag; LabStore reads code or last run)
    val version: Int,                // history/index.json last n, 0 when no history yet
    val lastRun: TestRun?,           // newest testruns/*.json
    val lastLint: LintResult?,       // lint.json
    val health: SourceHealthSnapshot,
    val lab: LabMeta?,               // meta.json.lab
)

internal enum class HealthState { Good, Attention, Failing, Unknown }

@Serializable
internal data class HealthDay(val day: String, val requests: Int = 0, val errors: Int = 0)   // day = yyyy-MM-dd (UTC)

@Serializable
internal data class HealthLastError(val time: Long, val method: String, val message: String)

/** health.json */
@Serializable
internal data class SourceHealthFile(
    val days: List<HealthDay> = emptyList(),      // at most 14, oldest first
    val lastError: HealthLastError? = null,
    // KMK (r110) fix: when the latest errors happened (ms, newest last, at most 10): "Failing" counts only those after
    // the latest Test Run. Missing in older files = none
    val recentErrors: List<Long> = emptyList(),
)

@Immutable
internal data class SourceHealthSnapshot(
    val state: HealthState,
    val requestsPerDay: Int,       // (yesterday + today) / 2, today alone when no yesterday
    val errorsToday: Int,
    val lastError: HealthLastError?,
    val sparkline: List<Int>,      // last 7 days' requests, oldest first, padded with 0
    val note: String?,             // the home row's note line (D§2.1), built by LabStore
)

// ---------- 3.2 Versions ----------

/** One line of history/index.json */
@Serializable
internal data class LabVersion(
    val n: Int,
    val time: Long,
    val note: String,              // "Saved", "Imported from file", "Generated from the Madara template", "Restored v3", owner text, lint fix applied
    val added: Int,
    val removed: Int,
    val testRunId: String? = null, // testruns/<id>.json at that time, if any
    val json: Boolean,             // which extension the file history/v<n>.js|json has
)

@Serializable
internal data class LabHistoryIndex(
    val versions: List<LabVersion> = emptyList(),
    val generatedFrom: Int? = null,
    // KMK (r98): the version the source's code is now (a Restore points here instead of adding a version); null = the newest
    val currentN: Int? = null,
    // KMK (r98): the highest version number ever given, so a deleted number is never reused (0 = read from versions)
    val lastN: Int = 0,
    // KMK (r106): the version made the baseline with History's "Make vN the baseline"; null = the implied one
    // (History.baselineOf: the "Baseline" note, else the generated-from version, else the first version)
    val baselineN: Int? = null,
)
// generatedFrom = the version the code was generated from (never trimmed)

// ---------- 3.3 Test Run ----------

internal enum class StepStatus { Green, Amber, Red, Skipped, Running }

/** Fixed step ids. Tab steps are "tab:<catalogId>". */
internal object StepId {
    const val POPULAR = "popular"
    const val LATEST = "latest"
    const val SEARCH = "search"
    const val TITLE = "title"
    const val CHAPTERS = "chapters"      // episodes for anime (same id)
    const val PAGES = "pages"
    const val HOSTERS = "hosters"
    const val VIDEO = "video"
    fun tab(catalogId: String) = "tab:$catalogId"
}

@Serializable
internal data class TestItem(
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val url: String,
    val kind: String,               // "entry" | "chapter" | "page" | "video" | "other"
    val raw: JsonObject? = null,    // the source's object as returned (for Lint result-field checks); first 60 only
)

@Serializable
internal data class TestRequest(
    val method: String,
    val url: String,
    val status: Int?,               // null = failed before a reply
    val ms: Long,
    val viaBrowser: Boolean = false,
    val error: String? = null,
    // KMK (r106): the reply's size in bytes (the body as read; Content-Length when it wasn't read); null = unknown
    val bytes: Long? = null,
)

@Serializable
internal data class TestStep(
    val id: String,
    val name: String,               // "Popular", "Tab New · page 1", "Chapters of X"
    val status: StepStatus,
    val count: Int,
    val ms: Long,
    val evidence: String,           // one line; red: the failure in words
    val items: List<TestItem> = emptyList(),
    val requests: List<TestRequest> = emptyList(),
    val partIds: List<String> = emptyList(),   // which Explain parts this step exercised (method names)
    // KMK (r116): picks PickHealer found again on this step's page (0 items / a field empty on all of them); missing
    // in older runs = none. The step is Amber "N picks moved"; the code is never changed by the run
    val healed: List<HealedPick> = emptyList(),
)

/** testruns/<id>.json; id = yyyyMMdd-HHmmss */
@Serializable
internal data class TestRun(
    val id: String,
    val key: String,
    val startedAt: Long,
    val totalMs: Long,
    val query: String,
    val steps: List<TestStep>,
    val console: List<String> = emptyList(),   // last 200 console lines
    val version: Int,                          // history version tested
    val rulesVersion: Int?,
    val appRelease: String,                    // BuildConfig.RELEASE_NUMBER (a String, "" locally)
) {
    val worst: StepStatus get() = when {
        steps.any { it.status == StepStatus.Red } -> StepStatus.Red
        steps.any { it.status == StepStatus.Amber } -> StepStatus.Amber
        steps.all { it.status == StepStatus.Skipped } -> StepStatus.Skipped
        else -> StepStatus.Green
    }
    val passed: Int get() = steps.count { it.status == StepStatus.Green }
    val counted: Int get() = steps.count { it.status != StepStatus.Skipped }
}

// ---------- 3.4 Rules and Lint ----------

internal enum class LintSeverity { Error, Warn, Info }

/** The fixed machine check kinds. Unknown `check` values in a rules file are listed as "not checked by this build". */
internal enum class LintCheck(val id: String) {
    RegexInCode("regex-in-code"),
    SelectorPattern("selector-pattern"),
    ResultField("result-field"),
    CompareSavedChapterUrls("compare-saved-chapter-urls"),
    HeaderPresent("header-present"),
    ResponseStatus("response-status"),
    RenderOption("render-option"),
    ;
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

@Serializable
internal data class LintApply(val find: String, val replace: String)

/** One rule from rules.md front matter */
@Serializable
internal data class LintRule(
    val id: String,
    val severity: LintSeverity,
    val check: String,                      // LintCheck.id or unknown
    val section: String,                    // heading anchor in the body, e.g. "8.2"
    val title: String,
    val fix: String = "",
    val snippet: String? = null,
    val apply: LintApply? = null,
    val applies: List<String> = emptyList(),// tags: manga, anime, api, madara, cloudflare, apk-port, …
    val params: Map<String, String> = emptyMap(), // check-specific scalars (pattern, field, header, status…)
)

@Serializable
internal data class LintFinding(
    val ruleId: String,
    val severity: LintSeverity,
    val title: String,
    val line: Int? = null,
    val found: String,                      // what was found (value, line text, URL)
    val fix: String,
    val snippet: String? = null,
    val applicable: Boolean = false,        // apply.find occurs exactly once in the part's line range
    val ignored: Boolean = false,
)

/** lint.json */
@Serializable
internal data class LintResult(
    val time: Long,
    val rulesVersion: Int,
    val checked: Int,
    val findings: List<LintFinding>,
    val passed: List<String>,               // rule ids
    val notChecked: List<String>,           // rule ids with unknown check kinds
    val toRead: List<String>,               // text-only rules whose applies tags match
    val ignoredRuleIds: List<String> = emptyList(),
)

/** rules.md parsed */
internal data class RulesFile(
    val version: Int,
    val rules: List<LintRule>,
    val body: String,                       // markdown after the front matter
    val sections: Map<String, IntRange>,    // "8.2" → line range in body
    val fileName: String?,
    val importedAt: Long?,
)

// ---------- 3.5 Explain ----------

/**
 * One part of a source for the Explain pane. For generated code: from the template's .explain.json plus the
 * part markers. For any other code: PartIndex derives one entry per `extension` method.
 */
@Serializable
internal data class ExplainEntry(
    val id: String,                 // part id = the extension method it implements when it does ("getPopular"), else free ("headers", "helpers")
    val title: String,
    val text: String,               // plain words: what it reads, which page, which selector, why
    val lineFrom: Int? = null,      // 1-based, inclusive; null when the part isn't in the code any more
    val lineTo: Int? = null,
    val optional: Boolean = false,
    val ruleIds: List<String> = emptyList(),   // "Why these choices" links into the Rules file
    val notWritten: Boolean = false,           // template left a TODO (e.g. unknown player)
) {
    val lines: IntRange? get() = if (lineFrom != null && lineTo != null) lineFrom..lineTo else null
}

/** explain.json (written by LabStore.saveGenerated from GeneratedSource.explain, re-lined by PartIndex) */
@Serializable
internal data class ExplainFile(val entries: List<ExplainEntry>, val templateId: String?, val templateVersion: Int?)

// ---------- 3.6 Part markers (B writes, A parses) ----------

/**
 * In generated code every part is wrapped as:
 *     /*@part id="getPopular"*/
 *     // Popular: reads the home page's .manga-item cards (Madara)
 *     ...code...
 *     /*@end*/
 * Rules: markers are on their own line; ids are unique per file; parts don't nest; JSON templates use
 *     "__part_getPopular": "start", ... "__part_getPopular_end": true
 * (keys the JSON engine ignores; stripJsonComments keeps them). PartIndex reads both.
 */
internal object PartMarkers {
    val JS_START = Regex("""^\s*/\*@part id="([A-Za-z0-9_.-]+)"\*/\s*$""")
    val JS_END = Regex("""^\s*/\*@end\*/\s*$""")
    val JSON_START = Regex("""^\s*"__part_([A-Za-z0-9_.-]+)"\s*:\s*"start"\s*,?\s*$""")
    val JSON_END = Regex("""^\s*"__part_([A-Za-z0-9_.-]+)_end"\s*:\s*true\s*,?\s*$""")
}

// ---------- 3.7 Lab meta (meta.json.lab) ----------

@Serializable
internal data class EngineMarkerResult(val id: String, val matched: Boolean, val weight: Int, val evidence: String)

@Serializable
internal data class LabMeta(
    val engine: String? = null,             // fingerprint id, "generic-list" for the builder, null for hand-written/imported
    val confidence: Int? = null,            // 0..100
    val markers: List<EngineMarkerResult> = emptyList(),
    val templateId: String? = null,
    val templateVersion: Int? = null,
    val picks: JsonElement? = null,         // BuilderSelection as JSON (B's shape; A stores it opaque)
    val detectedAt: Long? = null,
    val siteUrl: String? = null,
    val fromExtension: String? = null,      // KMK (r96, integrator): APK package name when imported from an extension
    val fromTheme: String? = null,          // the multisrc theme class the APK was built on, when found
)

// ---------- 3.8 Detection, templates, generation, builder (B produces, A consumes) ----------

@Immutable
internal data class EngineGuess(
    val engineId: String,
    val engineName: String,
    val kind: String,                       // "manga" | "anime" | "either"
    val confidence: Int,                    // 0..100 = matched weight / total weight
    val markers: List<EngineMarkerResult>,
    val fill: Map<String, String>,          // values lifted from the pages (D§2.2 fill block), shown as "What the Lab will write"
    val templateId: String,
)

@Immutable
internal data class DetectionStep(val name: String, val status: StepStatus, val ms: Long, val bytes: Int, val url: String?, val note: String?)

@Immutable
internal data class DetectionResult(
    val siteUrl: String,
    val steps: List<DetectionStep>,
    val best: EngineGuess?,                 // null when nothing matched
    val others: List<EngineGuess>,          // sorted by confidence desc
    val writeOffered: Boolean,              // best != null && best.confidence >= 60
)

@Immutable
internal data class LabTemplate(
    val id: String,                         // file stem, e.g. "madara"
    val name: String,
    val kind: String,                       // manga | anime | either
    val json: Boolean,
    val version: Int,
    val source: String,                     // "asset" | "file"
    val placeholders: List<String>,
    val explain: List<ExplainEntry>,        // from .explain.json, lines null
)

/** What B hands to A. A saves it as version 1 under a new key and opens it. */
@Immutable
internal data class GeneratedSource(
    val code: String,
    val json: Boolean,
    val animeHint: Boolean,
    val suggestedName: String,
    val baseUrl: String,
    val explain: List<ExplainEntry>,        // lines may be null; PartIndex re-lines them from markers
    val lab: LabMeta,
    val note: String,                       // history note, e.g. "Generated from the Madara template (87 %)"
    /**
     * KMK (r96, integrator addition): the source id the generated code must keep, or null for a fresh id. Set by
     * "Import from extension" so library titles saved by the APK's source keep working (A writes it as the
     * source's custom id; see JsSourceManager.customId).
     */
    val sourceId: Long? = null,
)

/** One pick in the tap builder */
@Serializable
internal data class BuilderPick(
    val label: String,                      // item | title | cover | next | chapter | date | number | image | video
    val cssPath: String,
    val tag: String,
    val attrs: Map<String, String>,
    val text: String,
    val count: Int,
    val sameParentCount: Int,
    val pageUrl: String,
)

/** Everything the builder decided, with reasons; stored as LabMeta.picks */
@Serializable
internal data class BuilderSelection(
    val siteUrl: String,
    val anime: Boolean,
    val picks: List<BuilderPick>,
    val derived: Map<String, String>,       // itemSelector, titleSelector, coverAttr, urlPattern, nextSelector, chapterSelector, …
    val reasons: Map<String, String>,       // same keys → one sentence each
)

// ---------- 3.9 Versioned file store (A implements, B uses) ----------

internal enum class LabFileKind(val folder: String, val assetPath: String?) {
    Rules("rules", "sourcelab/rules.md"),
    Fingerprints("fingerprints", "sourcelab/fingerprints.json"),
    Templates("templates", "sourcelab/templates"),   // a folder of files; versions are per file name
}

internal data class LabFileVersion(val name: String, val version: Int, val importedAt: Long, val fileName: String?, val file: java.io.File)

/**
 * Versioned files under filesDir/sourcelab/<kind.folder>/ (§4). `name` is the file name inside the kind
 * ("rules.md", "fingerprints.json", "madara.js"). The asset is the fallback when no imported file exists.
 */
internal interface LabFileStore {
    val kind: LabFileKind
    fun names(): List<String>                                   // asset names ∪ imported names
    fun current(name: String): String?                          // text of the current version (file, else asset)
    fun currentVersion(name: String): Int?
    fun versions(name: String): List<LabFileVersion>            // newest first
    /** Imports [text]; returns the new version or throws IllegalArgumentException(line-numbered message). Fails when version <= current. */
    fun import(name: String, text: String, fileName: String?): Int
    fun rollBack(name: String, version: Int): Boolean
    fun export(name: String): java.io.File                     // copy in cacheDir/sourcelab-export/
}

// ---------- 3.10 Navigation hand-off (§5) ----------

/** Marker for Half B's screens; A pops them with navigator.popUntil { it !is LabFlowScreen } if ever needed. */
internal interface LabFlowScreen

internal object LabHandoff {
    private val _pending = MutableStateFlow<GeneratedSource?>(null)
    val pending: StateFlow<GeneratedSource?> = _pending
    fun deliver(generated: GeneratedSource) { _pending.value = generated }
    /** A takes it once; null when nothing is waiting */
    fun take(): GeneratedSource? = _pending.getAndUpdate { null }
}

// ---------- 4 Storage paths ----------

internal object LabPaths {
    fun sourceDir(filesDir: java.io.File, key: String) = java.io.File(filesDir, "js_sources/$key")
    fun history(filesDir: java.io.File, key: String) = java.io.File(sourceDir(filesDir, key), "history")
    fun testRuns(filesDir: java.io.File, key: String) = java.io.File(sourceDir(filesDir, key), "testruns")
    fun lint(filesDir: java.io.File, key: String) = java.io.File(sourceDir(filesDir, key), "lint.json")
    fun health(filesDir: java.io.File, key: String) = java.io.File(sourceDir(filesDir, key), "health.json")
    fun explain(filesDir: java.io.File, key: String) = java.io.File(sourceDir(filesDir, key), "explain.json")
    fun labRoot(filesDir: java.io.File) = java.io.File(filesDir, "sourcelab")
    fun kindDir(filesDir: java.io.File, kind: LabFileKind) = java.io.File(labRoot(filesDir), kind.folder)
}
