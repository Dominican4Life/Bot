package eu.kanade.tachiyomi.jssource.lab

import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.bool
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * KMK (r96, Source Lab): the templates the Lab writes sources from. A template is ordinary source code under
 * `assets/sourcelab/templates/<stem>.js|json` (an imported file of the same name wins, through
 * `LabFiles.store(LabFileKind.Templates)`) with `{{placeholders}}` and part markers (CONTRACT-LAB §3.6), plus a
 * `<stem>.explain.json` sidecar: `{ name, kind, defaults, parts: { <id>: { title, text, optional, ruleIds } } }`.
 * The sidecar is shared by a `.js` and a `.json` of the same stem (their parts have the same ids).
 *
 * Ids: the stem for the `.js` file ("madara"); when a stem has both kinds, the JSON one is "<stem>.json".
 */
internal object Templates {

    private const val EXPLAIN_SUFFIX = ".explain.json"

    /** The placeholders a template text asks for, in order of first appearance, without duplicates */
    // Both braces escaped: Android's java.util.regex is ICU, which refuses a bare `}` (U_REGEX_RULE_SYNTAX). The
    // r96 build had `}}`: this object's init threw PatternSyntaxException (swallowed by the first caller's
    // runCatching) and every later use got `NoClassDefFoundError: eu.kanade.tachiyomi.jssource.lab.Templates`
    private val PLACEHOLDER = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

    /** Which method parts a source needs to start (the contract script of JsRuntime); never dropped */
    val REQUIRED_PARTS = setOf("search", "getPopular", "getLatest", "getChapterList")

    private val store: LabFileStore get() = LabFiles.store(LabFileKind.Templates)

    /** Every template, assets and imported files together, sorted with the engines first and the blanks last */
    fun list(): List<LabTemplate> {
        val names = runCatching { store.names() }.getOrElse { emptyList() }
        val codeFiles = names.filter { !it.endsWith(EXPLAIN_SUFFIX) && (it.endsWith(".js") || it.endsWith(".json")) }
        val stemsWithJs = codeFiles.filter { it.endsWith(".js") }.map { stemOf(it) }.toSet()
        return codeFiles.mapNotNull { file ->
            val stem = stemOf(file)
            val json = file.endsWith(".json")
            val id = if (json && stem in stemsWithJs) "$stem.json" else stem
            val text = runCatching { store.current(file) }.getOrNull() ?: return@mapNotNull null
            val sidecar = sidecar(stem)
            LabTemplate(
                id = id,
                name = (sidecar.str("name") ?: stem.replace('-', ' ').replaceFirstChar { it.uppercase() }) +
                    (if (json && stem in stemsWithJs) " (JSON)" else ""),
                kind = sidecar.str("kind") ?: if (file.startsWith("blank-anime") || "getVideoList" in text) "anime" else "either",
                json = json,
                version = runCatching { store.currentVersion(file) }.getOrNull() ?: 1,
                source = sourceOf(file),
                placeholders = placeholdersIn(text),
                explain = explainOf(sidecar),
            )
        }.sortedWith(compareBy<LabTemplate> { it.id.startsWith("blank-") }.thenBy { it.id.startsWith("generic") }.thenBy { it.id })
    }

    fun get(id: String): LabTemplate? = list().firstOrNull { it.id == id }

    /** The blank skeleton for a kind (the editor's old SourceTemplates, now files) */
    fun blank(json: Boolean, anime: Boolean): LabTemplate? =
        get((if (anime) "blank-anime" else "blank-manga") + (if (json) ".json" else ""))

    /** The template's code as it is now (file over asset), or null when it has gone */
    fun code(template: LabTemplate): String? = runCatching { store.current(fileNameOf(template)) }.getOrNull()

    /** The sidecar's `defaults` block: values a placeholder takes when nothing better is known */
    fun defaults(template: LabTemplate): Map<String, String> =
        sidecar(stemOfId(template.id)).obj("defaults")
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()

    /** The file name inside the Templates store for a template id ("madara" → "madara.js") */
    fun fileNameOf(template: LabTemplate): String =
        if (template.id.endsWith(".json")) template.id else template.id + (if (template.json) ".json" else ".js")

    fun explainFileNameOf(template: LabTemplate): String = stemOfId(template.id) + EXPLAIN_SUFFIX

    fun placeholdersIn(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * Sources written with a template that has a newer version now (CONTRACT-LAB §5.4). Half A shows the count
     * on Home; regenerating is not in this release.
     */
    fun staleSources(sources: List<LabSource>): List<Pair<LabSource, LabTemplate>> {
        val templates = list().associateBy { it.id }
        return sources.mapNotNull { source ->
            val lab = source.lab ?: return@mapNotNull null
            val template = lab.templateId?.let { templates[it] } ?: return@mapNotNull null
            val written = lab.templateVersion ?: return@mapNotNull null
            if (template.version > written) source to template else null
        }
    }

    /**
     * Imports a template (and its explain sidecar when given) through the store; returns the new version.
     * Throws IllegalArgumentException with a line number, as the store does.
     */
    fun import(fileName: String, text: String, explainText: String?, shownName: String?): Int {
        val name = fileName.substringAfterLast('/').trim()
        require(name.endsWith(".js") || name.endsWith(".json")) { "line 1: a template is a .js or .json file" }
        require(!name.endsWith(EXPLAIN_SUFFIX)) { "line 1: pick the template's code file; its .explain.json goes with it" }
        require(PLACEHOLDER.containsMatchIn(text) || "@part" in text || "__part_" in text) {
            "line 1: not a Lab template: no {{placeholders}} and no part markers"
        }
        if (explainText != null) {
            val parsed = runCatching { jsJson.parseToJsonElement(explainText) as? JsonObject }.getOrNull()
            require(parsed?.obj("parts") != null) { "line 1: the explain file needs a \"parts\" object" }
        }
        val version = store.import(name, text, shownName)
        if (explainText != null) {
            runCatching { store.import(stemOf(name) + EXPLAIN_SUFFIX, explainText, shownName) }
                .onFailure { AppLog.add('W', "lab", "Template ${stemOf(name)}: explain sidecar not imported: ${it.message}") }
        }
        return version
    }

    // ---------- helpers ----------

    private fun stemOf(fileName: String): String = fileName.substringBefore('.')

    private fun stemOfId(id: String): String = id.removeSuffix(".json")

    private fun sidecar(stem: String): JsonObject =
        runCatching { store.current(stem + EXPLAIN_SUFFIX)?.let { jsJson.parseToJsonElement(it) as? JsonObject } }
            .onFailure { AppLog.add('W', "lab", "Template $stem: explain sidecar unreadable: ${it.message}") }
            .getOrNull() ?: JsonObject(emptyMap())

    private fun explainOf(sidecar: JsonObject): List<ExplainEntry> =
        sidecar.obj("parts")?.mapNotNull { (id, value) ->
            val part = value as? JsonObject ?: return@mapNotNull null
            val text = part.str("text").orEmpty()
            ExplainEntry(
                id = id,
                title = part.str("title") ?: id,
                text = text,
                optional = part.bool("optional") ?: false,
                ruleIds = part.arr("ruleIds")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                notWritten = "TODO" in text,
            )
        }.orEmpty()

    /** "file" when the current version is an imported one, else "asset" */
    private fun sourceOf(fileName: String): String {
        val current = runCatching { store.currentVersion(fileName) }.getOrNull() ?: return "asset"
        val imported = runCatching { store.versions(fileName) }.getOrElse { emptyList() }
        return if (imported.any { it.version == current }) "file" else "asset"
    }
}
