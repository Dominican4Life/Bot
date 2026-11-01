package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.SourceHealth
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK (r96): everything the Source Lab reads and writes next to a saved source (`js_sources/<key>/`): the history
 * versions, the Test Runs, `lint.json`, `health.json` and `explain.json` (C§4). A sidecar is only read when the
 * source exists for [JsSourceManager] (its `meta.json` parses); nothing here makes a source exist.
 *
 * [onSaved] is the one hook in the manager's `save()`: it records the version and runs Lint. [saveGenerated] is
 * the end of Half B's New-source flow (C§5.2).
 */
internal object LabStore {

    private val context: Application by lazy { Injekt.get<Application>() }
    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val filesDir: File get() = context.filesDir

    /** Bumped when a sidecar changes, so [sources] re-reads */
    private val tick = MutableStateFlow(0)
    private val cache = ConcurrentHashMap<String, LabSource>()

    fun invalidate(key: String? = null) {
        if (key == null) cache.clear() else cache.keys.removeAll { it.startsWith("$key@") }
        tick.update { it + 1 }
    }

    /**
     * Every saved source with its sidecar facts, newest change first; re-read on every save, run or lint. r98: a
     * source with no history first gets its code as v1 "Baseline" ([baseline], once per source and process).
     */
    fun sources(): Flow<List<LabSource>> = combine(manager.savedFlow, tick) { saved, _ -> saved }
        .map { infos ->
            withContext(Dispatchers.IO) {
                infos.forEach { baseline(it) }
                infos.map { source(it) }
            }
        }

    /** Keys already given a baseline (or found with history) in this process */
    private val baselined = ConcurrentHashMap.newKeySet<String>()

    /** KMK (r98): v1 "Baseline" for a saved source without history, so History always has the code to go back to */
    private fun baseline(info: JsSourceInfo) {
        // A source saved a moment ago is getting its own v1 from onSaved (savedFlow changes before the manager calls
        // onSaved): writing "Baseline" first would take that version's note. Left for a later pass.
        if (System.currentTimeMillis() - info.updatedAt < BASELINE_SETTLE_MS) return
        if (!baselined.add(info.key)) return
        runCatching {
            if (History.index(info.key).versions.isNotEmpty()) return
            val code = manager.code(info.key) ?: return
            if (History.baseline(info.key, code, info.json) != null) cache.keys.removeAll { it.startsWith("${info.key}@") }
        }.onFailure { AppLog.add('W', "lab", "Couldn't write the baseline of ${info.name}: ${it.message}") }
    }

    fun source(key: String): LabSource? = manager.get(key)?.let { source(it) }

    fun source(info: JsSourceInfo): LabSource = cache.getOrPut("${info.key}@${info.updatedAt}") { load(info) }

    private fun load(info: JsSourceInfo): LabSource {
        val lastRun = runCatching { TestRuns.latest(info.key) }.getOrNull()
        val lastLint = runCatching { Lint.read(info.key) }.getOrNull()
        val health = runCatching { SourceHealth.of(context).snapshot(info.key, lastRun, lastLint) }.getOrNull()
            ?: SourceHealthSnapshot(HealthState.Unknown, 0, 0, null, List(7) { 0 }, null)
        val code = manager.code(info.key).orEmpty()
        return LabSource(
            key = info.key,
            name = info.name,
            anime = info.anime,
            json = info.json,
            hasVideos = !info.anime && hasVideos(code, info.json),
            // r98: the version the code is now (a restored one), not the newest
            version = runCatching { History.latestVersion(info.key) }.getOrDefault(0),
            lastRun = lastRun,
            lastLint = lastLint,
            health = health.copy(note = note(lastRun, lastLint, health)),
            lab = info.labMeta,
        )
    }

    /** The home row's note line (D§2.1): the red step, else the lint findings, else today's error */
    private fun note(run: TestRun?, lint: LintResult?, health: SourceHealthSnapshot): String? {
        run?.steps?.firstOrNull { it.status == StepStatus.Red }?.let { step ->
            return "${step.name.lowercase()} step failed · ${step.evidence}"
        }
        val findings = lint?.findings.orEmpty().filter { !it.ignored }
        if (findings.isNotEmpty()) {
            val head = "${findings.size} lint ${if (findings.size == 1) "finding" else "findings"}"
            return "$head · ${findings.first().title.lowercase()}"
        }
        return health.note
    }

    /** A manga source whose chapters carry videos (ReaderVideos): `getVideoList` or `videoUrl` next to `getPageList` */
    fun hasVideos(code: String, json: Boolean): Boolean = if (json) {
        "\"videos\"" in code && "\"pages\"" in code
    } else {
        "getPageList" in code && ("getVideoList" in code || "videoUrl" in code || "videoListUrl" in code)
    }

    // ---------- hooks ----------

    /**
     * Called by [JsSourceManager.save] after `meta.json` is written. Records the version ([note], the save's own:
     * "Restored v3", "Lint fix applied: …"; "Saved" by default) and runs Lint in the background. Never throws past
     * the manager's runCatching. The note travels with the save call: a shared "pending" field was taken by
     * whichever save came first.
     */
    fun onSaved(info: JsSourceInfo, code: String, note: String? = null) {
        // r98: a Restore's save carries RESTORE_NOTE + n: the version becomes current, no new version is added
        // r102: a Save with UPDATE_NOTE overwrites the current version in place (see History.updateCurrent)
        val restored = note?.takeIf { it.startsWith(RESTORE_NOTE) }?.removePrefix(RESTORE_NOTE)?.toIntOrNull()
        if (restored != null) {
            // r98 fix: the version may have gone between the Restore and this save (trimmed or deleted): then the
            // newest version with the same code is marked current (or the code is added), never left unmarked
            if (!History.setCurrent(info.key, restored)) {
                AppLog.add('W', "lab", "${info.name}: v$restored is gone, marking the version with the same code current")
                History.markCurrentByCode(info.key, code, info.json, "Restored v$restored")
            }
        } else if (note?.startsWith(UPDATE_NOTE) == true) {
            // KMK (r102): Save → "Update vN (current)": the current version is overwritten in place (History falls
            // back to a new version when there is none or it is the generated one); the inner note is the fallback's
            val inner = note.orEmpty().removePrefix(UPDATE_NOTE).ifBlank { "Saved" }
            History.updateCurrent(info.key, code, info.json, inner)
        } else {
            History.append(info.key, code, info.json, note ?: "Saved")
        }
        invalidate(info.key)
        scope.launch {
            runCatching { Lint.run(info.key) }
                .onFailure { AppLog.add('W', "lab", "Lint after saving ${info.name} failed: ${it.message}") }
            invalidate(info.key)
        }
    }

    /** What became of a [GeneratedSource] handed to the Lab */
    sealed interface Adopted {
        data class Saved(val info: JsSourceInfo, val idNote: String?) : Adopted

        /** The contract script failed: the code opens unsaved under [key] with [error], nothing is lost */
        data class Unsaved(val key: String, val generated: GeneratedSource, val error: String) : Adopted
    }

    /**
     * Saves what Half B generated as a new source: version 1 (`generatedFrom = 1`), `explain.json` re-lined from the
     * part markers, then Lint. A `sourceId` (Import from extension) is written into the code's metadata first, so
     * `describe()` picks it up as the custom id; the outcome is said in [Adopted.Saved.idNote].
     */
    suspend fun saveGenerated(generated: GeneratedSource): Adopted = withContext(Dispatchers.IO) {
        val key = manager.newKey()
        var code = generated.code
        if (generated.note.startsWith("Duplicated")) code = stripIdLines(code, generated.json)
        generated.sourceId?.let { code = injectId(code, generated.json, it) }
        val prepared = generated.copy(code = code)
        val info = try {
            manager.save(
                key = key,
                code = code,
                json = generated.json,
                animeHint = generated.animeHint,
                overrides = JsSourceInfo(
                    key = key,
                    name = generated.suggestedName.trim(),
                    baseUrl = generated.baseUrl.trim(),
                    lab = generated.lab.toJson(),
                ),
                note = generated.note,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.add('E', "lab", "Couldn't save the generated source ${generated.suggestedName}: ${e.message}")
            return@withContext Adopted.Unsaved(key, prepared, e.message ?: e.toString())
        }
        // onSaved wrote v1 with the note; mark it as the version the code was generated from
        runCatching { History.markGeneratedFrom(key) }
        // No entries (an imported file, a duplicate): no explain.json; PartIndex derives the parts from the code
        if (generated.explain.isNotEmpty()) runCatching {
            writeExplain(
                key,
                ExplainFile(
                    entries = PartIndex.of(code, generated.json, generated.explain),
                    templateId = generated.lab.templateId,
                    templateVersion = generated.lab.templateVersion,
                ),
            )
        }.onFailure { AppLog.add('W', "lab", "Couldn't write explain.json of $key: ${it.message}") }
        val idNote = generated.sourceId?.let { wanted ->
            when {
                info.customId == wanted -> "Keeps the extension's id $wanted: library titles stay linked"
                generated.json -> "Couldn't keep the extension's id $wanted: JSON sources can't carry an id in this build"
                else -> "Couldn't keep the extension's id $wanted: the app refused it (see Logs, tag js); the source has its own id"
            }
        }
        AppLog.add('I', "lab", "Saved generated source ${info.name} as $key (${generated.note})${idNote?.let { " · $it" }.orEmpty()}")
        invalidate(key)
        Adopted.Saved(info, idNote)
    }

    /**
     * The note a Restore passes to the manager's save: [onSaved] then marks the version current instead of
     * appending one (r98). It travels with the call, like every History note (r96: no shared pending field).
     */
    const val RESTORE_NOTE = "\u0000restore:"

    /**
     * KMK (r102): the note prefix of a Save → "Update vN (current)": [onSaved] overwrites the current History version
     * in place instead of appending one ([History.updateCurrent]); what follows the prefix is the note used when it
     * has to fall back to a new version. Travels with the call like [RESTORE_NOTE].
     */
    const val UPDATE_NOTE = "\u0000update:"

    private const val BASELINE_SETTLE_MS = 30_000L

    /**
     * Writes version [n]'s code back as the source's code through the manager's save and marks it current in
     * `index.json` (r98: no new version; r96 added "Restored vN"); Lint follows.
     */
    suspend fun restore(key: String, n: Int): Result<JsSourceInfo> = withContext(Dispatchers.IO) {
        val info = manager.get(key) ?: return@withContext Result.failure<JsSourceInfo>(IllegalStateException("The source is gone"))
        val code = History.code(key, n) ?: return@withContext Result.failure<JsSourceInfo>(IllegalStateException("Version $n was trimmed"))
        val json = History.index(key).versions.firstOrNull { it.n == n }?.json ?: info.json
        runCatching {
            manager.save(
                key = key,
                code = code,
                json = json,
                animeHint = info.anime,
                overrides = JsSourceInfo(
                    key = key,
                    name = info.name,
                    lang = info.lang,
                    iconUrl = info.iconUrl,
                    baseUrl = info.baseUrl,
                    lab = info.lab,
                ),
                note = RESTORE_NOTE + n,
            )
        }.onFailure {
            AppLog.add('W', "lab", "Restoring v$n of ${info.name} failed: ${it.message}")
        }
    }

    // ---------- explain.json ----------

    fun explain(key: String): ExplainFile? = runCatching {
        val f = LabPaths.explain(filesDir, checkedKey(key))
        if (f.exists()) jsJson.decodeFromString(ExplainFile.serializer(), f.readText()) else null
    }.getOrNull()

    fun writeExplain(key: String, file: ExplainFile) {
        val f = LabPaths.explain(filesDir, checkedKey(key))
        if (f.parentFile?.isDirectory != true) return
        f.writeText(jsJson.encodeToString(ExplainFile.serializer(), file))
    }

    // ---------- ids in code ----------

    /**
     * Removes a top-level `id:` line (JS) or `"id":` line (JSON) so a duplicate never takes the original's id
     * (C§7.1). Only lines on their own are touched; an `id` inside a nested object two levels deep is left.
     */
    fun stripIdLines(code: String, json: Boolean): String {
        val pattern = if (json) JSON_ID_LINE else JS_ID_LINE
        return code.lines().filterNot { pattern.matches(it) }.joinToString("\n")
    }

    /**
     * Writes [id] into the code's metadata so `describe()` reads it as the custom id: replaces an existing top-level
     * `id` line, else adds one right after the `extension = {` (or the JSON's first `{`) line, else (JS) appends
     * `extension.id = "…"`, which the metadata script reads the same way.
     */
    fun injectId(code: String, json: Boolean, id: Long): String {
        val lines = code.lines().toMutableList()
        val pattern = if (json) JSON_ID_LINE else JS_ID_LINE
        val existing = lines.indexOfFirst { pattern.matches(it) }
        if (existing >= 0) {
            val indent = lines[existing].takeWhile { it == ' ' || it == '\t' }
            lines[existing] = if (json) "$indent\"id\": \"$id\"," else "${indent}id: \"$id\","
            return lines.joinToString("\n")
        }
        val opener = if (json) lines.indexOfFirst { it.trim() == "{" } else lines.indexOfFirst { JS_EXTENSION_OPEN.containsMatchIn(it) }
        if (opener >= 0) {
            val indent = lines.getOrNull(opener + 1)?.takeWhile { it == ' ' || it == '\t' }?.ifEmpty { "  " } ?: "  "
            lines.add(opener + 1, if (json) "$indent\"id\": \"$id\"," else "${indent}id: \"$id\",")
            return lines.joinToString("\n")
        }
        if (json) return code
        return code.trimEnd() + "\n\n// Source Lab: the id of the extension this source replaces\nextension.id = \"$id\";\n"
    }

    private fun checkedKey(key: String): String {
        require(JsSourceManager.KEY_PATTERN.matches(key)) { "Not a valid source key: $key" }
        return key
    }

    private val JS_ID_LINE = Regex("""^\s{0,4}id\s*:\s*[^,\n]+,?\s*$""")
    private val JSON_ID_LINE = Regex("""^\s{0,4}"id"\s*:\s*"?\d+"?\s*,?\s*$""")
    private val JS_EXTENSION_OPEN = Regex("""\bextension\s*=\s*\{\s*$""")
}

// ---------- LabMeta in JsSourceInfo (kept as JSON there: JsSourceInfo is public, LabMeta internal) ----------

internal val JsSourceInfo.labMeta: LabMeta?
    get() = lab?.let { runCatching { jsJson.decodeFromJsonElement(LabMeta.serializer(), it) }.getOrNull() }

internal fun LabMeta.toJson(): JsonElement = jsJson.encodeToJsonElement(LabMeta.serializer(), this)
