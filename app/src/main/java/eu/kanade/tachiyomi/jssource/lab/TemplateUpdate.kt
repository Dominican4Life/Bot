package eu.kanade.tachiyomi.jssource.lab

import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r100): "Update template" in the Lab editor. A source written with an older template version takes over the
 * parts the template's sidecar lists under `updates` for every version step since (e.g. generic-list v3 and
 * anime-player v2: the Videos part now calls the shared video finder, `host.findVideo`). Only those parts are
 * replaced (found by their `/*@part id="x"*/ … /*@end*/` markers); the rest of the owner's code stays as it is.
 * The result is saved through the manager's save (like a Restore) as a new History version with the note
 * "Updated template <id> vA → vB: <parts>", and `lab.templateVersion` becomes the template's version.
 *
 * Refused (nothing changes): JSON sources; a listed part whose markers the owner removed; a template part that
 * needs a placeholder with no default; a step range where no version lists a part.
 *
 * r100 fix pass: a listed part the source never had (the builder / APK import drops `getVideoList` from a manga
 * source; or its explain.json has no written entry for it) is skipped, not refused. When nothing is left to replace,
 * the update still raises `lab.templateVersion` (same code, a History note "nothing to replace"), so the offer
 * doesn't stay forever. The source's first line `// @version N` follows the template's version.
 */
internal object TemplateUpdate {

    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }

    /** What the Update button offers (or why it can't do it, in [problem]) */
    data class Offer(
        val key: String,
        val sourceName: String,
        val templateId: String,
        val templateName: String,
        val from: Int,
        val to: Int,
        val parts: List<String>,
        val partTitles: List<String>,
        val notes: List<String>,
        val problem: String?,
        /** Titles of listed parts this source never had (dropped when it was written): skipped */
        val skippedTitles: List<String> = emptyList(),
    ) {
        val partWords: String get() = if (parts.isEmpty()) "nothing to replace for this source" else partTitles.joinToString(", ")
    }

    sealed interface Outcome {
        data class Updated(val info: JsSourceInfo, val offer: Offer) : Outcome
        data class Refused(val message: String) : Outcome
    }

    /** The offer for the saved source [key], or null when its template has no newer version (call on IO) */
    fun offer(key: String): Offer? {
        val info = manager.get(key) ?: return null
        val source = LabStore.source(info)
        val (_, template) = Templates.staleSources(listOf(source)).firstOrNull() ?: return null
        return offer(source, template, manager.code(key), runCatching { LabStore.explain(key) }.getOrNull())
    }

    private fun offer(source: LabSource, template: LabTemplate, code: String?, explain: ExplainFile?): Offer? {
        val from = source.lab?.templateVersion ?: return null
        val to = template.version
        if (to <= from) return null
        val updates = Templates.updates(template)
        val steps = (from + 1..to).mapNotNull { updates[it] }
        val listed = steps.flatMap { it.parts }.distinct()
        val skipped = if (code == null || source.json || template.json) {
            emptyList()
        } else {
            listed.filter { id -> neverHad(code, id, source, explain) }
        }
        val parts = listed.filter { it !in skipped }
        val titles = parts.map { id -> titleOf(template, id) }
        val notes = steps.map { it.note }.filter { it.isNotBlank() }
        if (skipped.isNotEmpty()) {
            AppLog.add(
                'D',
                "lab",
                "Template update offer ${source.name}: skipped ${skipped.joinToString(", ")} " +
                    "(not in this source: left out when it was written)" +
                    (if (parts.isEmpty()) "; nothing to replace, the update only raises the template version" else ""),
            )
        }
        val problem = when {
            source.json || template.json ->
                "JSON sources can't be updated automatically: build the source again, or copy the new parts by hand"
            listed.isEmpty() ->
                "Template v$from → v$to has no part this source can take over: build the source again to get it"
            code == null -> "The source's code couldn't be read"
            parts.isEmpty() -> null
            else -> replaceParts(code, template, parts).exceptionOrNull()?.message
        }
        return Offer(
            key = source.key,
            sourceName = source.name,
            templateId = template.id,
            templateName = template.name,
            from = from,
            to = to,
            parts = parts,
            partTitles = titles,
            notes = notes,
            problem = problem,
            skippedTitles = skipped.map { id -> titleOf(template, id) },
        )
    }

    /**
     * True when the listed part [id] is absent from [code] because the generator left it out, not because the
     * owner removed its markers: no start marker at all, and the source's explain.json has no written entry for it
     * (absent or "not written"); without an explain file, only `getVideoList` of a manga source counts as left out
     * (Build by tapping / APK import drop it for manga). A part the explain lists as written keeps the refusal.
     */
    private fun neverHad(code: String, id: String, source: LabSource, explain: ExplainFile?): Boolean {
        val hasStart = code.lineSequence().any { PartMarkers.JS_START.find(it)?.groupValues?.get(1) == id }
        if (hasStart) return false
        if (explain != null) {
            val entry = explain.entries.firstOrNull { it.id == id }
            return entry == null || entry.notWritten
        }
        return id == "getVideoList" && !source.anime
    }

    /**
     * Replaces the offered parts in [source]'s saved code with [template]'s, saves it as a new version and bumps
     * `lab.templateVersion`. Never throws for the owner's code: a refusal says why.
     */
    suspend fun update(source: LabSource, template: LabTemplate): Outcome = withContext(Dispatchers.IO) {
        val info = manager.get(source.key) ?: return@withContext Outcome.Refused("The source is gone")
        val code = manager.code(source.key) ?: return@withContext Outcome.Refused("The source's code couldn't be read")
        val fresh = LabStore.source(info)
        val offer = offer(fresh, template, code, runCatching { LabStore.explain(info.key) }.getOrNull())
            ?: return@withContext Outcome.Refused("This source is already on ${template.name} v${template.version}")
        offer.problem?.let { return@withContext Outcome.Refused(it) }
        val replaced = replaceParts(code, template, offer.parts).getOrElse {
            return@withContext Outcome.Refused(it.message ?: it.toString())
        }
        val updated = withVersionLine(replaced, offer.to)
        val lab = (fresh.lab ?: LabMeta()).copy(templateId = template.id, templateVersion = offer.to)
        val note = "Updated template ${template.id} v${offer.from} → v${offer.to}: " + if (offer.parts.isEmpty()) {
            "nothing to replace (no ${offer.skippedTitles.joinToString(", ")} part in this source)"
        } else {
            offer.partWords
        }
        val saved = try {
            manager.save(
                key = info.key,
                code = updated,
                json = info.json,
                animeHint = info.anime,
                overrides = JsSourceInfo(
                    key = info.key,
                    name = info.name,
                    lang = info.lang,
                    iconUrl = info.iconUrl,
                    baseUrl = info.baseUrl,
                    lab = lab.toJson(),
                ),
                note = note,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.add('W', "lab", "Template update ${info.name}: the updated code didn't save: ${e.message}")
            return@withContext Outcome.Refused("The updated code didn't save (nothing changed): ${e.message}")
        }
        // explain.json: the replaced parts get the template's new texts, the version follows
        runCatching {
            val old = LabStore.explain(info.key)
            if (old != null) {
                val newTexts = template.explain.associateBy { it.id }
                val entries = old.entries.map { entry ->
                    val fresher = newTexts[entry.id]
                    if (entry.id in offer.parts && fresher != null) fresher.copy(notWritten = false) else entry
                }
                LabStore.writeExplain(
                    info.key,
                    ExplainFile(
                        entries = PartIndex.of(updated, false, entries),
                        templateId = template.id,
                        templateVersion = offer.to,
                    ),
                )
            }
        }.onFailure { AppLog.add('W', "lab", "Template update ${info.name}: explain.json not updated: ${it.message}") }
        LabStore.invalidate(info.key)
        AppLog.add(
            'I',
            "lab",
            "Template update ${info.name}: ${template.id} v${offer.from} → v${offer.to} " +
                "(${offer.parts.joinToString(", ").ifEmpty { "nothing replaced" }}" +
                (if (offer.skippedTitles.isNotEmpty()) "; skipped ${offer.skippedTitles.joinToString(", ")}" else "") + ")",
        )
        Outcome.Updated(saved, offer)
    }

    /**
     * [code] with each part of [parts] replaced, marker to marker (both kept), by the same part of [template]'s code
     * with its placeholders filled from the template's defaults (and `videoNote` = ""). Fails with the owner's words.
     */
    internal fun replaceParts(code: String, template: LabTemplate, parts: List<String>): Result<String> = runCatching {
        val templateCode = Templates.code(template) ?: error("The template ${template.id} has no code any more")
        val values = Templates.defaults(template).toMutableMap()
        values["videoNote"] = ""
        var lines = code.lines()
        val templateLines = templateCode.lines()
        for (id in parts) {
            val title = titleOf(template, id)
            val target = rangeOf(lines, id)
                ?: error("The $title part markers were removed: update by hand")
            if (target.second > 1) error("The $title part is in the code twice: update by hand")
            val wanted = rangeOf(templateLines, id)
                ?: error("${template.name} v${template.version} has no $title part")
            var text = templateLines.subList(wanted.first.first, wanted.first.last + 1).joinToString("\n")
            val missing = Templates.placeholdersIn(text).filter { it !in values }
            if (missing.isNotEmpty()) {
                error("The new $title part needs ${missing.joinToString(", ") { "{{$it}}" }}: update by hand")
            }
            // Filled line by line: a placeholder that fills empty (`videoNote`) leaves no trailing space behind
            text = text.lines().joinToString("\n") { line ->
                if ("{{" in line) fill(line, values).trimEnd() else line
            }
            lines = lines.subList(0, target.first.first) + text.lines() + lines.subList(target.first.last + 1, lines.size)
        }
        lines.joinToString("\n")
    }

    private val VERSION_LINE = Regex("""^(\s*//\s*@version\s+)\d+""")

    /** The code's first line `// @version N` (written from the template's first line) follows the new version */
    private fun withVersionLine(code: String, version: Int): String {
        val first = code.substringBefore('\n')
        val match = VERSION_LINE.find(first) ?: return code
        val rest = if ('\n' in code) code.substring(first.length) else ""
        return first.replaceRange(match.range, match.groupValues[1] + version) + rest
    }

    /** The lines of part [id] (start marker to end marker, both included) and how many times its start appears */
    private fun rangeOf(lines: List<String>, id: String): Pair<IntRange, Int>? {
        val starts = lines.indices.filter { PartMarkers.JS_START.find(lines[it])?.groupValues?.get(1) == id }
        val start = starts.firstOrNull() ?: return null
        val end = (start + 1 until lines.size).firstOrNull { i ->
            PartMarkers.JS_END.containsMatchIn(lines[i]) || PartMarkers.JS_START.containsMatchIn(lines[i])
        } ?: return null
        // The next part started before this one ended: the end marker was removed
        if (!PartMarkers.JS_END.containsMatchIn(lines[end])) return null
        return (start..end) to starts.size
    }

    private fun titleOf(template: LabTemplate, id: String): String =
        template.explain.firstOrNull { it.id == id }?.title ?: KNOWN_TITLES[id] ?: id

    private val KNOWN_TITLES = mapOf(
        "getVideoList" to "Videos",
        "getPageList" to "Pages",
        "getChapterList" to "Chapters",
        "search" to "Search",
        "getPopular" to "Popular",
        "getLatest" to "Latest",
        "getDetails" to "Title details",
        "headers" to "Headers",
        "helpers" to "Helpers",
    )

    /** `{{name}}` → value, escaped the way the Generator does (values sit inside string literals or comments) */
    private fun fill(text: String, values: Map<String, String>): String {
        var out = text
        values.forEach { (key, value) -> out = out.replace("{{$key}}", escape(value)) }
        return out
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n', '\r', '\t' -> append(' ')
                else -> append(ch)
            }
        }
    }
}
