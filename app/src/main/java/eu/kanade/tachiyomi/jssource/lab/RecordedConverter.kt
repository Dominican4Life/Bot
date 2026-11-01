package eu.kanade.tachiyomi.jssource.lab

import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder.RecordedFilter
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder.RecordedRequest
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder.RecordedSetting
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder.RecordedStep
import eu.kanade.tachiyomi.jssource.lab.ExtensionRecorder.Recording
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * KMK (r98, Source Lab): turns an [ExtensionRecorder.Recording] into a JS source written from the
 * `recorded-manga` / `recorded-anime` templates.
 *
 * Every step is written from evidence only:
 * - the request that carried the data is the one whose reply holds the most of the values the extension returned;
 * - its address becomes a template: the page number from the diff of popular page 1 and 2 (or a `page=1` style
 *   parameter), the search words where the query was, the method's `url` (whole, a parameter or a path segment)
 *   where its pieces were, and a value an earlier reply of the same step held (a "chain", with the array item picked
 *   by a sibling field equal to a known value when there is one);
 * - a JSON reply becomes paths into the reply (each returned field solved as constant text and reply values,
 *   numbers and dates compared as numbers and days); an HTML reply becomes selectors (the elements whose address,
 *   text or attribute equal the returned values, their smallest common card, and a selector checked on every sample);
 * - videos are looked up in the replies as JSON values, attributes, text in a script, or text in a packed script
 *   (unpacked here exactly as the JS `unpacker` module does).
 *
 * Whatever has no evidence becomes a TODO in its part, with the recorded requests and an excerpt of the reply as
 * comments, and is flagged `notWritten` in Explain.
 */
internal object RecordedConverter {

    data class Result(
        val generated: GeneratedSource,
        val settings: Int,
        val filters: Int,
        val written: List<String>,     // part titles written from the recording
        val todo: List<String>,        // part titles left as TODO
        val notes: List<String>,       // one line per part: how it was written, or why not
        val botCheck: String?,
    )

    private class PartCode(
        val lines: List<String>,
        val written: Boolean,
        val explain: String,
        val todo: Boolean = !written,
        val settingsUsed: Set<String> = emptySet(),   // r98 fix: setting keys this part reads
        val pageRules: List<PageRule> = emptyList(),  // r98 fix: Popular's page pattern, for Latest and Search
    )

    private val pretty = Json { prettyPrint = false }

    fun convert(rec: Recording, facts: ApkImport.ApkFacts?): Result {
        val templateId = if (rec.anime) "recorded-anime" else "recorded-manga"
        val template = Templates.get(templateId) ?: throw IllegalStateException("Template $templateId is missing from the app")
        val site = siteSetting(rec)
        val parts = LinkedHashMap<String, PartCode>()
        val titles = mapOf(
            "getPopular" to "Popular",
            "getLatest" to "Latest",
            "search" to "Search",
            "getDetails" to "Title page",
            "getChapterList" to if (rec.anime) "Episodes" else "Chapters",
            "getPageList" to "Pages",
            "getVideoList" to "Videos",
            "getFilters" to "Filters",
            "getSourcePreferences" to "Settings",
        )

        parts["constants"] = PartCode(constantsLines(rec, site), true, "")
        parts["getPopular"] = guarded("getPopular", rec) { listPart(rec, "popular", Kind.Entries, rec.step("popular2")) }
        val popularRules = parts["getPopular"]?.pageRules.orEmpty()
        parts["getLatest"] = if (!rec.supportsLatest) {
            PartCode(listOf("// The extension has no Latest list.", "return [];"), true, "The extension has no Latest list, so this returns nothing.")
        } else {
            guarded("getLatest", rec) { listPart(rec, "latest", Kind.Entries, null, popularRules) }
        }
        parts["search"] = guarded("search", rec) { listPart(rec, "search", Kind.Entries, null, popularRules) }
        parts["getDetails"] = guarded("getDetails", rec) { detailsPart(rec) }
        parts["getChapterList"] = guarded("getChapterList", rec) { listPart(rec, "chapters", Kind.Chapters, null) }
        if (rec.anime) {
            parts["getVideoList"] = guarded("getVideoList", rec) { videoPart(rec) }
        } else {
            parts["getPageList"] = guarded("getPageList", rec) { listPart(rec, "pages", Kind.Pages, null) }
        }
        parts["getFilters"] = filtersPart(rec)
        parts["getSourcePreferences"] = settingsPart(rec, site, parts)

        val todoIds = parts.filter { (id, p) -> id != "constants" && p.todo }.keys
        val writtenIds = parts.filter { (id, p) -> id != "constants" && p.written && !p.todo }.keys
        val settingsCount = rec.settings.count { it.key.isNotBlank() }
        val filtersCount = ExtensionRecorder.countFilters(rec.filters)

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(rec.startedAt))
        val row = rec.row
        val header = buildList {
            add("Recorded from the extension ${row.extensionName} ${row.versionName} (${row.pkgName}) on $stamp with Record and convert.")
            add("Live from the loaded source: id ${row.sourceId}, name, lang, baseUrl. Settings $settingsCount, filters $filtersCount.")
            add("Written from the recording: ${writtenIds.joinToString(", ") { titles[it] ?: it }.ifBlank { "nothing" }}.")
            if (todoIds.isNotEmpty()) add("Left as TODO (the recorded requests are in each part): ${todoIds.joinToString(", ") { titles[it] ?: it }}.")
            rec.botCheck?.let { add("Bot check seen: $it (at ${rec.botCheckUrl ?: rec.baseUrl}); get() passes it once in the hidden browser.") }
            facts?.themeClass?.let { add("APK theme: $it.") }
        }.map(::commentSafe)

        val leading = ExplainEntry(
            id = "recorded",
            title = "Recorded from extension",
            text = buildString {
                append("${row.extensionName} ${row.versionName} (${row.pkgName}), recorded on $stamp. ")
                append("Id ${row.sourceId}, name \"${row.sourceName}\", lang ${row.lang}, address ${row.baseUrl}, all live. ")
                append("Settings: $settingsCount with the extension's keys and defaults")
                if (rec.settings.any { it.changed }) append(" (your saved values stay in the extension's settings file, which this source reads while it keeps the id)")
                append(". Filters: $filtersCount. ")
                append("Steps: ")
                append(rec.steps.joinToString("; ") { "${it.name} ${it.status.name.lowercase()} (${it.dataRequests.size} requests)" })
                append(". ")
                rec.botCheck?.let { append("A $it check answered during the recording: requests pass it once in the hidden browser. ") }
                if (todoIds.isNotEmpty()) append("Not written: ${todoIds.joinToString(", ") { titles[it] ?: it }} (the recorded requests and reply excerpts are in the code).")
            }.trim(),
        )
        val lab = LabMeta(
            engine = null,
            templateId = template.id,
            templateVersion = template.version,
            detectedAt = rec.startedAt,
            siteUrl = rec.baseUrl,
            fromExtension = row.pkgName,
            fromTheme = facts?.themeClass,
        )
        val generated = Generator.generate(
            template = template,
            values = mapOf(
                "name" to row.sourceName,
                "lang" to row.lang,
                "baseUrl" to rec.baseUrl,
                "iconUrl" to "https://www.google.com/s2/favicons?domain=${hostOf(rec.baseUrl).orEmpty()}&sz=128",
            ),
            lab = lab,
            note = "Recorded from ${row.extensionName} (Record and convert)",
            suggestedName = row.sourceName,
            sourceId = row.sourceId,
            notWritten = todoIds,
            header = header,
            leading = listOf(leading),
            anime = rec.anime,
        )
        val code = fillSlots(generated.code, parts)
        val explain = generated.explain.map { entry ->
            val part = parts[entry.id]
            if (part == null) {
                entry
            } else {
                entry.copy(text = part.explain.ifBlank { entry.text }, notWritten = part.todo)
            }
        }
        val notes = parts.filterKeys { it != "constants" }.map { (id, p) -> "${titles[id] ?: id}: ${p.explain}" }
        val logLine = "Converted the recording of ${row.sourceName}: written ${writtenIds.joinToString(",")}; " +
            "TODO ${todoIds.joinToString(",").ifBlank { "none" }}"
        AppLog.add('I', "lab", logLine)
        return Result(
            generated = generated.copy(code = code, explain = explain),
            settings = settingsCount,
            filters = filtersCount,
            written = writtenIds.map { titles[it] ?: it },
            todo = todoIds.map { titles[it] ?: it },
            notes = notes,
            botCheck = rec.botCheck,
        )
    }

    /** A converter bug in one part must not lose the others: it becomes that part's TODO */
    private fun guarded(method: String, rec: Recording, block: () -> PartCode): PartCode = try {
        block()
    } catch (e: Throwable) {
        AppLog.add('W', "lab", "Record and convert: $method not converted: ${ApkImport.describe(e)}")
        todoPart(method, "the converter failed: ${ApkImport.describe(e)}", null, returnFor(method))
    }

    private val SLOT = Regex("""^(\s*)/\*@slot ([A-Za-z]+)\*/.*$""")

    private fun fillSlots(code: String, parts: Map<String, PartCode>): String {
        val out = ArrayList<String>()
        for (line in code.lines()) {
            val m = SLOT.find(line)
            val part = m?.let { parts[it.groupValues[2]] }
            if (m == null || part == null) {
                out.add(line)
                continue
            }
            val indent = m.groupValues[1]
            part.lines.forEach { out.add(if (it.isEmpty()) "" else indent + it) }
        }
        return out.joinToString("\n")
    }

    // ---------- constants, settings, filters ----------

    private fun constantsLines(rec: Recording, site: RecordedSetting?): List<String> {
        val headers = LinkedHashMap<String, JsonElement>()
        rec.headers.forEach { (k, v) -> headers[k] = JsonPrimitive(v) }
        return buildList {
            add("var HEADERS = ${JsonObject(headers)};")
            if (rec.botCheck != null) add("// The recording met a ${commentSafe(rec.botCheck)} check at ${commentSafe(rec.botCheckUrl ?: rec.baseUrl)}")
            add("var BOT_CHECK = ${jsStr(rec.botCheck.orEmpty())};")
            if (site != null) add("// The site's address is the setting \"${commentSafe(site.title)}\" (${commentSafe(site.key)})")
            add("var SITE_KEY = ${jsStr(site?.key.orEmpty())};")
        }
    }

    /** A list or text setting whose value is the site's address: requests then follow the setting */
    private fun siteSetting(rec: Recording): RecordedSetting? {
        val base = hostOf(rec.baseUrl)?.removePrefix("www.") ?: return null
        return rec.settings.firstOrNull { s ->
            s.key.isNotBlank() && (s.type == "list" || s.type == "text") &&
                listOfNotNull(s.current, (s.default as? JsonPrimitive)?.content).any { value -> siteHost(value) == base }
        }
    }

    private fun siteHost(value: String): String? {
        val v = value.trim().trimEnd('/')
        if (v.isEmpty() || ' ' in v) return null
        val host = if ("://" in v) hostOf(v) else v.substringBefore('/').takeIf { '.' in it }
        return host?.lowercase()?.removePrefix("www.")
    }

    private fun settingsPart(rec: Recording, site: RecordedSetting?, parts: Map<String, PartCode>): PartCode {
        if (rec.settings.isEmpty()) {
            return PartCode(
                listOf("// ${commentSafe(rec.settingsNote ?: "The extension has no settings.")}", "return [];"),
                written = true,
                explain = rec.settingsNote?.replaceFirstChar { it.uppercase() } ?: "The extension has no settings.",
            )
        }
        val lines = ArrayList<String>()
        lines.add("// The extension's settings, same keys and defaults. Its saved values live in source_${rec.row.sourceId},")
        lines.add("// which this source reads while it keeps the extension's id.")
        if (site != null) lines.add("// \"${commentSafe(site.key)}\" holds the site's address: site() reads it for every request.")
        // r98 fix: which parts read each setting (setting(key, default) at their top), and which had no visible effect
        for (s in rec.settings) {
            if (s.key.isBlank() || s.type == "category" || s.type == "info" || s.key == site?.key) continue
            val readers = parts.filter { (_, p) -> s.key in p.settingsUsed }.keys
            if (readers.isEmpty()) {
                lines.add("// setting ${commentSafe(s.key)}: effect not seen in the recording")
            } else {
                lines.add("// setting ${commentSafe(s.key)}: read by ${readers.joinToString(", ")}")
            }
        }
        lines.add("return [")
        for (s in rec.settings) {
            val obj = LinkedHashMap<String, JsonElement>()
            when (s.type) {
                "category" -> {
                    obj["type"] = JsonPrimitive("category")
                    obj["title"] = JsonPrimitive(s.title)
                }
                "info" -> {
                    obj["type"] = JsonPrimitive("info")
                    if (s.title.isNotBlank()) obj["title"] = JsonPrimitive(s.title)
                    obj["summary"] = JsonPrimitive(s.summary ?: s.title)
                }
                else -> {
                    obj["key"] = JsonPrimitive(s.key)
                    obj["title"] = JsonPrimitive(s.title.ifBlank { s.key })
                    obj["type"] = JsonPrimitive(s.type)
                    s.summary?.let { obj["summary"] = JsonPrimitive(it) }
                    if (s.options.isNotEmpty()) {
                        obj["options"] = JsonArray(s.options.map { (label, value) -> JsonObject(mapOf("label" to JsonPrimitive(label), "value" to JsonPrimitive(value))) })
                    }
                    s.default?.let { obj["defaultValue"] = it }
                    if (s.type == "text") obj["dialogTitle"] = JsonPrimitive(s.title)
                }
            }
            lines.add("  ${pretty.encodeToString(JsonElement.serializer(), JsonObject(obj))},")
        }
        lines.add("];")
        val count = rec.settings.count { it.key.isNotBlank() }
        val changed = rec.settings.count { it.changed }
        return PartCode(
            lines,
            written = true,
            explain = "$count settings from the extension's settings screen with the same keys, types, options and defaults" +
                (if (changed > 0) "; $changed of them have a saved value, kept in the extension's settings file this source reads (same id)" else "") +
                (site?.let { "; \"${it.title}\" holds the site's address and every request follows it" } ?: "") + ".",
        )
    }

    private fun filtersPart(rec: Recording): PartCode {
        if (rec.filters.isEmpty()) {
            return PartCode(
                listOf("// ${commentSafe(rec.filtersNote ?: "The extension has no filters.")}", "return [];"),
                written = true,
                explain = rec.filtersNote?.replaceFirstChar { it.uppercase() } ?: "The extension has no filters.",
            )
        }
        val lines = ArrayList<String>()
        lines.add("// The extension's getFilterList(): names, options and defaults. search() gets their values by key;")
        lines.add("// how the extension puts them into its request was not recorded (search ran with the defaults).")
        lines.add("return [")
        rec.filters.forEach { lines.add("  ${pretty.encodeToString(JsonElement.serializer(), filterJson(it))},") }
        lines.add("];")
        val fromFields = countValuesFrom(rec.filters)
        return PartCode(
            lines,
            written = true,
            explain = "${ExtensionRecorder.countFilters(rec.filters)} filters as the extension lists them" +
                (if (fromFields > 0) "; $fromFields lists send the values the extension keeps next to their labels" else "") +
                ". search() receives them, but how the extension sends them was not recorded.",
        )
    }

    private fun countValuesFrom(filters: List<RecordedFilter>): Int =
        filters.sumOf { (if (it.valuesFrom != null) 1 else 0) + countValuesFrom(it.children) }

    private fun filterJson(f: RecordedFilter): JsonObject {
        val obj = LinkedHashMap<String, JsonElement>()
        f.key?.let { obj["key"] = JsonPrimitive(it) }
        obj["name"] = JsonPrimitive(f.name)
        obj["type"] = JsonPrimitive(f.type)
        fun options() = JsonArray(f.options.map { (label, value) -> JsonObject(mapOf("label" to JsonPrimitive(label), "value" to JsonPrimitive(value))) })
        when (f.type) {
            "select" -> {
                obj["options"] = options()
                obj["defaultIndex"] = JsonPrimitive(f.defaultIndex)
            }
            "sort" -> {
                obj["options"] = options()
                obj["defaultIndex"] = JsonPrimitive(f.defaultIndex)
                obj["ascending"] = JsonPrimitive(f.ascending)
            }
            "multiSelect" -> {
                obj["options"] = options()
                if (f.defaultSelected.isNotEmpty()) obj["defaultSelected"] = JsonArray(f.defaultSelected.map { JsonPrimitive(it) })
            }
            "autocomplete" -> obj["options"] = options()
            "checkbox" -> obj["defaultValue"] = JsonPrimitive(f.defaultBool)
            "tristate" -> obj["defaultValue"] = JsonPrimitive(f.defaultState)
            "text" -> obj["defaultValue"] = JsonPrimitive(f.defaultText)
            "group" -> obj["filters"] = JsonArray(f.children.map(::filterJson))
        }
        return JsonObject(obj)
    }

    // ---------- known values and request templates ----------

    /**
     * A value the method's code knows, and the expression that gives it. [setting] (r98 fix): the key of the
     * extension setting whose value this is; its variable is read with `setting(key, default)` at the top of the part.
     */
    private class Known(val value: String, val expr: String, val raw: Boolean = false, val setting: RecordedSetting? = null)

    /** Everything one step's code knows while it is written */
    private class Plan(val rec: Recording, val step: RecordedStep) {
        var queryUsed = false                      // the search words went into the request somewhere
        val settingsUsed = LinkedHashSet<String>() // setting keys whose value this part reads
        val lines = ArrayList<String>()
        val known = ArrayList<Known>()
        val vars = HashMap<Int, String>()          // request index → variable holding its reply (text or JSON)
        val urlExprs = HashMap<Int, String>()      // request index → its address expression
        val docs = HashMap<Int, Document?>()
        val parsed = HashMap<Int, JsonElement?>()
        val docVars = HashMap<Int, String>()
        val todos = ArrayList<String>()
        val foreign = ArrayList<String>()          // values the JS method can't know (a title it isn't given)
        var counter = 0
        val baseHost: String? = hostOf(rec.baseUrl)?.removePrefix("www.")

        fun json(i: Int): JsonElement? {
            if (parsed.containsKey(i)) return parsed[i]
            val r = step.requests.getOrNull(i)
            val body = r?.body
            val value = if (r != null && body != null && r.isJson) runCatching { Json.parseToJsonElement(body) }.getOrNull() else null
            parsed[i] = value
            return value
        }

        fun doc(i: Int): Document? {
            if (docs.containsKey(i)) return docs[i]
            val r = step.requests.getOrNull(i)
            val body = r?.body
            val value = if (r != null && body != null && !r.isJson) runCatching { Jsoup.parse(body, r.url) }.getOrNull() else null
            docs[i] = value
            return value
        }

        fun knownFor(value: String): Known? = known.firstOrNull { it.value == value }
    }

    private fun planFor(rec: Recording, step: RecordedStep): Plan {
        val plan = Plan(rec, step)
        step.query?.let { plan.known.add(Known(it, "query")) }
        val input = step.input
        if (input != null) {
            plan.known.add(Known(input, "url"))
            val absolute = resolve(rec.baseUrl + "/", input)
            if (absolute != null && absolute != input) plan.known.add(Known(absolute, "abs(url)"))
            absolute?.toHttpUrlOrNull()?.let { u ->
                val path = pathOf(absolute)
                if (path != input) plan.known.add(Known(path, "pathOf(abs(url))"))
                // Only pieces that look like ids or slugs: a plain word ("anime", "manga") is the site's structure
                for (i in 0 until u.querySize) {
                    val value = u.queryParameterValue(i) ?: continue
                    if (isIdLike(value)) plan.known.add(Known(value, "param(url, ${jsStr(u.queryParameterName(i))})"))
                }
                u.pathSegments.forEachIndexed { i, seg ->
                    if (isIdLike(seg)) plan.known.add(Known(seg, "seg(url, $i)"))
                }
            }
        }
        // r98 fix: root is the site's scheme + host only (a base address with a path was written twice before)
        plan.known.add(Known(originOf(rec.baseUrl), "root", raw = true))
        // r98 fix: the settings' values in effect during the recording, so a request that carried one reads the
        // setting instead of the recorded text (the site's address is SITE_KEY's, read by site())
        val siteKey = siteSetting(rec)?.key
        rec.settings.forEachIndexed { i, s ->
            if (s.key.isBlank() || s.key == siteKey || (s.type != "list" && s.type != "text")) return@forEachIndexed
            val value = s.current ?: (s.default as? JsonPrimitive)?.content ?: return@forEachIndexed
            if (value.length < 2 || value.equals("true", true) || value.equals("false", true)) return@forEachIndexed
            val name = "pref${i}_" + s.key.replace(Regex("""[^A-Za-z0-9_]"""), "_").take(24)
            plan.known.add(Known(value, name, setting = s))
        }
        // What the method isn't given: the title (details, chapters) or the chapter's name (pages, videos)
        val entry = (rec.step("popular")?.items.orEmpty()).firstOrNull { it["url"] == input }
        entry?.get("title")?.let { if (it.length >= 3) plan.foreign.add(it) }
        if (step.id == "pages" || step.id == "videos") {
            rec.step("chapters")?.items?.firstOrNull { it["url"] == input }?.get("name")?.let { if (it.length >= 3) plan.foreign.add(it) }
        }
        return plan
    }

    private fun isIdLike(value: String): Boolean =
        value.length >= 2 && (value.any { it.isDigit() } || '-' in value || '_' in value || '.' in value || value.length >= 12)

    private sealed interface Piece {
        data class Lit(val text: String) : Piece
        data class Expr(val code: String) : Piece
    }

    private fun concat(pieces: List<Piece>): String {
        val merged = ArrayList<Piece>()
        for (p in pieces) {
            val last = merged.lastOrNull()
            if (p is Piece.Lit && p.text.isEmpty()) continue
            if (p is Piece.Lit && last is Piece.Lit) merged[merged.size - 1] = Piece.Lit(last.text + p.text) else merged.add(p)
        }
        if (merged.isEmpty()) return "\"\""
        return merged.joinToString(" + ") { if (it is Piece.Lit) jsStr(it.text) else (it as Piece.Expr).code }
    }

    /**
     * A literal text with the known values inside it (length ≥ 4) turned into their expressions. r98 fix: the search
     * words only when [queryOk] (a request body value: never a path, a header or an item field), at any length,
     * matched without case at word boundaries.
     */
    private fun splitKnown(plan: Plan, text: String, encode: Boolean, queryOk: Boolean = false): List<Piece> {
        val candidates = plan.known.filter { k ->
            if (k.expr == "query") queryOk && k.value.isNotEmpty() else k.value.length >= 4 && k.value in text
        }.sortedByDescending { it.value.length }
        for (k in candidates) {
            val at = if (k.expr == "query") indexOfWord(text, k.value) else text.indexOf(k.value)
            if (at < 0) continue
            val found = text.substring(at, at + k.value.length)
            val left = splitKnown(plan, text.substring(0, at), encode, queryOk)
            val right = splitKnown(plan, text.substring(at + k.value.length), encode, queryOk)
            val value = valueExpr(plan, k, found)
            val expr = if (encode && !k.raw) "encodeURIComponent($value)" else value
            return left + Piece.Expr(expr) + right
        }
        return listOf(Piece.Lit(text))
    }

    /** `s(expr)` of a known value as it was [found] (the search words lower-cased when the site lower-cased them) */
    private fun valueExpr(plan: Plan, k: Known, found: String): String {
        if (k.expr == "query") plan.queryUsed = true
        return if (k.expr == "query" && found != k.value && found == k.value.lowercase()) "s(query).toLowerCase()" else "s(${k.expr})"
    }

    /** [word] in [text] without case, not inside a longer word (so a one-letter query doesn't match any letter) */
    private fun indexOfWord(text: String, word: String): Int {
        if (word.isEmpty()) return -1
        var from = 0
        while (from <= text.length - word.length) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) return -1
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + word.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return at
            from = at + 1
        }
        return -1
    }

    /**
     * r98 fix: a value that can stand for an item (a sibling id, a chained token): 4+ characters, or an integer of
     * 1000 or more (uuids, hex, session tokens are long anyway); never a boolean, null or a small number.
     */
    private fun looksLikeId(value: String): Boolean {
        val v = value.trim()
        if (v.equals("true", true) || v.equals("false", true) || v.equals("null", true)) return false
        if (v.toDoubleOrNull() != null) return (v.toLongOrNull() ?: return false) >= 1000
        return v.length >= 4
    }

    /** A known value standing as a whole token of an address or body ([path]: a path segment, never the search words) */
    private fun tokenKnown(plan: Plan, decoded: String, path: Boolean): Known? = plan.known.firstOrNull { k ->
        when {
            k.raw -> false
            k.expr == "query" -> !path && k.value.isNotEmpty() && k.value.equals(decoded, ignoreCase = true)
            k.setting != null -> k.value == decoded
            else -> k.value == decoded && looksLikeId(decoded)
        }
    }

    private val PAGE_NAMES = setOf("page", "p", "pg", "pagina", "paged", "pagenum", "page_num", "pageno", "currentpage")

    private class UrlParts(val origin: String, val host: String, val segments: List<String>, val rawSegments: List<String>, val query: List<Pair<String, String?>>)

    private fun urlParts(url: String): UrlParts? {
        val u = url.toHttpUrlOrNull() ?: return null
        val defaultPort = if (u.scheme == "https") 443 else 80
        val origin = "${u.scheme}://${u.host}" + (if (u.port != defaultPort) ":${u.port}" else "")
        val query = u.encodedQuery?.split('&')?.filter { it.isNotEmpty() }?.map { pair ->
            if ('=' in pair) pair.substringBefore('=') to pair.substringAfter('=') else pair to null
        }.orEmpty()
        return UrlParts(origin, u.host, u.pathSegments, u.encodedPathSegments, query)
    }

    /** The page expression when [x] (page 1) and [y] (page 2) are numbers that follow the page, else null */
    private fun pageStep(x: String?, y: String?): String? {
        val n1 = x?.toLongOrNull() ?: return null
        val n2 = y?.toLongOrNull() ?: return null
        val d = n2 - n1
        if (d <= 0) return null
        return when {
            n1 == 1L && d == 1L -> "page"
            n1 == d -> "String(page * $d)"
            else -> "String($n1 + (page - 1) * $d)"
        }
    }

    /**
     * Positions ("p<i>" path segment, "q<i>" query value) whose number follows the page, from page 1 and page 2.
     * r98 fix: also a page parameter only page 2 has (`"q+"` → its name: page 1 is the plain address) and a
     * `/page/2` only page 2 has (`"p+<i>"` → the segment's name, inserted before page 1's segment i).
     */
    private fun pageDiff(url1: String, url2: String): Map<String, String>? {
        val a = urlParts(url1) ?: return null
        val b = urlParts(url2) ?: return null
        if (a.origin != b.origin) return null
        if (b.query.size == a.query.size + 1 && a.segments == b.segments) {
            val extra = b.query.indexOfFirst { q -> a.query.none { it.first == q.first } }
            if (extra < 0) return null
            val (name, value) = b.query[extra]
            val rest = b.query.filterIndexed { i, _ -> i != extra }
            return if (name.lowercase() in PAGE_NAMES && value == "2" && rest == a.query) mapOf("q+" to name) else null
        }
        if (b.segments.size == a.segments.size + 2 && a.query == b.query) {
            for (i in 0..a.segments.size) {
                if (b.segments.subList(0, i) != a.segments.subList(0, i)) break
                if (b.segments[i].lowercase() in PAGE_NAMES && b.segments[i + 1] == "2" &&
                    b.segments.subList(i + 2, b.segments.size) == a.segments.subList(i, a.segments.size)
                ) {
                    return mapOf("p+$i" to b.segments[i])
                }
            }
            return null
        }
        if (a.segments.size != b.segments.size || a.query.size != b.query.size) return null
        val out = HashMap<String, String>()
        fun compare(pos: String, x: String?, y: String?): Boolean {
            if (x == y) return true
            out[pos] = pageStep(x, y) ?: return false
            return true
        }
        for (i in a.segments.indices) if (!compare("p$i", a.segments[i], b.segments[i])) return null
        for (i in a.query.indices) {
            if (a.query[i].first != b.query[i].first) return null
            if (!compare("q$i", a.query[i].second, b.query[i].second)) return null
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * r98 fix: the page in a POST body, from page 1's and page 2's bodies: a form value ("b<i>") or a JSON leaf
     * ("j" + its path key) whose number follows the page. Empty when the bodies differ in any other way.
     */
    private fun bodyPageDiff(a: RecordedRequest, b: RecordedRequest): Map<String, String> {
        val x = a.requestBody ?: return emptyMap()
        val y = b.requestBody ?: return emptyMap()
        if (x == y) return emptyMap()
        val out = HashMap<String, String>()
        val jx: JsonElement? = runCatching { Json.parseToJsonElement(x) }.getOrNull()
        val jy: JsonElement? = runCatching { Json.parseToJsonElement(y) }.getOrNull()
        if (jx != null && jy != null && (jx is JsonObject || jx is JsonArray)) {
            val lx = ArrayList<Pair<List<Any>, String>>().also { leaves(jx, emptyList(), it, 0, maxDepth = 8) }.associate { pathKey(it.first) to it.second }
            val ly = ArrayList<Pair<List<Any>, String>>().also { leaves(jy, emptyList(), it, 0, maxDepth = 8) }.associate { pathKey(it.first) to it.second }
            if (lx.keys != ly.keys) return emptyMap()
            for ((k, v) in lx) {
                val w = ly[k]
                if (v == w) continue
                out["j$k"] = pageStep(v, w) ?: return emptyMap()
            }
            return out
        }
        val px = x.split('&')
        val py = y.split('&')
        if (px.size != py.size) return emptyMap()
        for (i in px.indices) {
            if (px[i].substringBefore('=') != py[i].substringBefore('=')) return emptyMap()
            if (px[i] == py[i]) continue
            out["b$i"] = pageStep(px[i].substringAfter('=', ""), py[i].substringAfter('=', "")) ?: return emptyMap()
        }
        return out
    }

    /** The page-2 request of [page2] that is [request]'s next page: same shape and method, else one a page diff explains */
    private fun secondPage(request: RecordedRequest, page2: RecordedStep): RecordedRequest? {
        val usable = page2.requests.filter { !it.image && !it.infrastructure && it.method == request.method }
        return usable.firstOrNull { shapeOf(it.url) == shapeOf(request.url) }
            ?: usable.firstOrNull { pageDiff(request.url, it.url) != null }
    }

    /** r98 fix: Popular's page pattern by names, for a Latest or Search address recorded on one page only */
    private sealed interface PageRule {
        data class Param(val name: String, val expr: String) : PageRule
        data class AddParam(val name: String) : PageRule
        data class SegAfter(val name: String, val expr: String) : PageRule
        data class InsertSeg(val name: String, val fromEnd: Int) : PageRule
    }

    private fun rulesOf(url: String, pageMap: Map<String, String>): List<PageRule> {
        val p = urlParts(url) ?: return emptyList()
        return pageMap.mapNotNull { (pos, expr) ->
            when {
                pos == "q+" -> PageRule.AddParam(expr)
                pos.startsWith("p+") -> pos.removePrefix("p+").toIntOrNull()?.let { PageRule.InsertSeg(expr, p.segments.size - it) }
                pos.startsWith("q") -> pos.drop(1).toIntOrNull()?.let { p.query.getOrNull(it) }?.let { PageRule.Param(it.first, expr) }
                pos.startsWith("p") -> pos.drop(1).toIntOrNull()?.takeIf { it > 0 }?.let { p.segments.getOrNull(it - 1) }?.let { PageRule.SegAfter(it, expr) }
                else -> null // a body position belongs to that one request
            }
        }
    }

    private fun applyRules(url: String, rules: List<PageRule>): Map<String, String> {
        val p = urlParts(url) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (rule in rules) {
            when (rule) {
                is PageRule.Param -> p.query.indexOfFirst { it.first == rule.name && it.second?.toLongOrNull() != null }
                    .takeIf { it >= 0 }?.let { out["q$it"] = rule.expr }
                is PageRule.AddParam -> if (p.query.none { it.first == rule.name }) out["q+"] = rule.name
                is PageRule.SegAfter -> (1 until p.segments.size).firstOrNull { p.segments[it - 1] == rule.name && p.segments[it].toLongOrNull() != null }
                    ?.let { out["p$it"] = rule.expr }
                is PageRule.InsertSeg -> {
                    val at = p.segments.size - rule.fromEnd
                    if (p.segments.none { it == rule.name } && at in 0..p.segments.size) out["p+$at"] = rule.name
                }
            }
        }
        return out
    }

    /** One address alone: a `page=1` style parameter, or `/page/1` */
    private fun pageGuess(url: String): Map<String, String> {
        val p = urlParts(url) ?: return emptyMap()
        p.query.forEachIndexed { i, (name, value) ->
            if (name.lowercase() in PAGE_NAMES && value == "1") return mapOf("q$i" to "page")
        }
        p.segments.forEachIndexed { i, seg ->
            if (seg == "1" && i > 0 && p.segments[i - 1].lowercase() in PAGE_NAMES) return mapOf("p$i" to "page")
        }
        return emptyMap()
    }

    /** The JS expression of [url], requested as request [index] of the step (earlier replies may hold its pieces) */
    private fun urlExpr(plan: Plan, url: String, index: Int, pageMap: Map<String, String>, depth: Int): String {
        plan.knownFor(url)?.let { if (!it.raw && it.expr != "query") return it.expr }
        // r98 fix: an address with a page in it is a list's own address, never a link read from an earlier page
        if (depth < 3 && pageMap.isEmpty()) chainWhole(plan, url, index, depth)?.let { return it }
        val p = urlParts(url) ?: return concat(splitKnown(plan, url, encode = false))
        val pieces = ArrayList<Piece>()
        // root is the scheme + host only (r98 fix), so every path segment is written once, below
        pieces.add(if (plan.baseHost != null && p.host.removePrefix("www.") == plan.baseHost) Piece.Expr("root") else Piece.Lit(p.origin))
        fun pageSegment(at: Int) {
            pageMap["p+$at"]?.let { name -> pieces.add(Piece.Expr("(page > 1 ? ${jsStr("/$name/")} + page : \"\")")) }
        }
        p.segments.forEachIndexed { i, seg ->
            pageSegment(i)
            pieces.add(Piece.Lit("/"))
            pieces.addAll(tokenPieces(plan, seg, p.rawSegments[i], "p$i", pageMap, index, depth))
        }
        pageSegment(p.segments.size)
        p.query.forEachIndexed { i, (name, raw) ->
            pieces.add(Piece.Lit(if (i == 0) "?" else "&"))
            pieces.add(Piece.Lit(name))
            if (raw != null) {
                pieces.add(Piece.Lit("="))
                val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                pieces.addAll(tokenPieces(plan, decoded, raw, "q$i", pageMap, index, depth))
            }
        }
        pageMap["q+"]?.let { name ->
            val lead = (if (p.query.isEmpty()) "?" else "&") + name + "="
            pieces.add(Piece.Expr("(page > 1 ? ${jsStr(lead)} + page : \"\")"))
        }
        return concat(pieces)
    }

    private fun tokenPieces(
        plan: Plan,
        decoded: String,
        raw: String,
        pos: String,
        pageMap: Map<String, String>,
        index: Int,
        depth: Int,
    ): List<Piece> {
        pageMap[pos]?.let { return listOf(Piece.Expr(it)) }
        if (raw.isEmpty()) return emptyList()
        // r98 fix: the search words only in a query value or a body value (never a path segment), any length, any
        // case; any other known value only when it looks like an id (or is a setting's value)
        val path = pos.startsWith("p")
        tokenKnown(plan, decoded, path)?.let { k ->
            return listOf(Piece.Expr("encodeURIComponent(${valueExpr(plan, k, decoded)})"))
        }
        if (looksLikeId(decoded) && depth < 3) chainToken(plan, decoded, index, depth)?.let { return listOf(Piece.Expr("encodeURIComponent(s($it))")) }
        // A known value inside the token, as it was written in the address
        val inside = plan.known.filter { k ->
            when {
                k.raw -> false
                k.expr == "query" -> !path && k.value.isNotEmpty()
                else -> k.value.length >= 4 && (k.setting != null || looksLikeId(k.value))
            }
        }.sortedByDescending { it.value.length }.firstNotNullOfOrNull { k ->
            val forms = listOf(encodeComponent(k.value), k.value, URLEncoder.encode(k.value, "UTF-8")).distinct()
            forms.firstNotNullOfOrNull { form ->
                val at = if (k.expr == "query") indexOfWord(raw, form) else raw.indexOf(form)
                if (at < 0) null else Triple(k, at, form.length)
            }
        }
        if (inside != null) {
            val (k, at, length) = inside
            val expr = valueExpr(plan, k, raw.substring(at, at + length).let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) })
            return listOf(Piece.Lit(raw.substring(0, at)), Piece.Expr("encodeURIComponent($expr)"), Piece.Lit(raw.substring(at + length)))
        }
        plan.foreign.firstOrNull { norm(it).equals(norm(decoded), true) || (decoded.length >= 4 && norm(it).contains(norm(decoded), true)) }?.let {
            plan.todos.add("the address held \"${it.take(60)}\" (${pos.toPositionWord()}), which this method isn't given: only its url is")
        }
        return listOf(Piece.Lit(raw))
    }

    private fun String.toPositionWord(): String = if (startsWith("q")) "a query value" else "a path segment"

    /** The whole address as a value of an earlier reply (a JSON field or an HTML attribute) */
    private fun chainWhole(plan: Plan, url: String, index: Int, depth: Int): String? {
        for (j in index - 1 downTo 0) {
            val r = plan.step.requests[j]
            if (r.image || r.infrastructure || r.error != null || r.body == null) continue
            val json = plan.json(j)
            if (json != null) {
                val path = leafPathFor(json, url) ?: leafPathFor(json, pathOf(url))?.let { p -> return chainJsonExpr(plan, j, json, p, depth)?.let { "abs($it)" } }
                if (path != null) return chainJsonExpr(plan, j, json, path, depth)
                continue
            }
            val doc = plan.doc(j) ?: continue
            for (el in doc.allElements) {
                for (attr in el.attributes()) {
                    val value = attr.value.trim()
                    if (value.isEmpty() || value.length > 2000) continue
                    if (value == url || resolve(r.url, value) == url) {
                        val limit = maxOf(1, plan.step.items.size) * 3
                        val css = selectorFor(doc, el, attr.key, limit) ?: run {
                            plan.todos.add("the address ${shortUrl(url)} is an attribute of ${shortUrl(r.url)}, but no selector picks it out (at most $limit matches)")
                            null
                        } ?: continue
                        val all = doc.select(css)
                        val k = all.indexOfFirst { it === el }
                        if (k < 0) continue
                        val docVar = docVar(plan, j, depth)
                        return "abs(await attrAt($docVar, ${jsStr(css)}, $k, ${jsStr(attr.key)}))"
                    }
                }
            }
        }
        return null
    }

    /** A token of an address as a JSON value of an earlier reply */
    private fun chainToken(plan: Plan, token: String, index: Int, depth: Int): String? {
        for (j in index - 1 downTo 0) {
            val r = plan.step.requests[j]
            if (r.image || r.infrastructure || r.error != null) continue
            val json = plan.json(j) ?: continue
            val path = leafPathFor(json, token) ?: continue
            val expr = chainJsonExpr(plan, j, json, path, depth) ?: continue
            val name = "k${++plan.counter}"
            plan.lines.add("var $name = $expr;")
            plan.known.add(Known(token, name))
            return name
        }
        return null
    }

    /**
     * The expression reading [path] of request [j]'s JSON. When the path goes through an array, the item is picked by
     * a sibling field equal to a known value (`where(list, ["id"], param(url, "anime_id"))`); else the same index.
     */
    private fun chainJsonExpr(plan: Plan, j: Int, json: JsonElement, path: List<Any>, depth: Int): String? {
        val arrayAt = path.indexOfFirst { it is Int }
        if (arrayAt >= 0) {
            val listPath = path.subList(0, arrayAt)
            val item = at(json, path.subList(0, arrayAt + 1))
            val rest = path.subList(arrayAt + 1, path.size)
            if (item != null) {
                val siblings = ArrayList<Pair<List<Any>, String>>()
                leaves(item, emptyList(), siblings, 0, maxDepth = 3)
                for ((sibling, value) in siblings) {
                    if (sibling == rest || !looksLikeId(value)) continue
                    val known = plan.known.firstOrNull { !it.raw && it.expr != "query" && it.value == value } ?: continue
                    val variable = requestVar(plan, j, depth) ?: return null
                    return "v(where(v($variable, ${jsPath(listPath)}), ${jsPath(sibling)}, ${known.expr}), ${jsPath(rest)})"
                }
            }
            // r98 fix: no sibling id picks the item: a fixed index would read another item on another title
            plan.todos.add("a value of ${shortUrl(plan.step.requests[j].url)} at ${pathWords(path)} is read at a fixed array position; no field next to it matched what this method knows")
            return null
        }
        val variable = requestVar(plan, j, depth) ?: return null
        return "v($variable, ${jsPath(path)})"
    }

    private fun requestVar(plan: Plan, j: Int, depth: Int): String? {
        plan.vars[j]?.let { return it }
        val r = plan.step.requests[j]
        val url = urlExpr(plan, r.url, j, emptyMap(), depth + 1)
        val options = optionsExpr(plan, r, j, depth + 1)
        val name = "r${++plan.counter}"
        plan.urlExprs[j] = url
        plan.lines.add("var $name = await ${if (plan.json(j) != null) "getJson" else "get"}($url${options?.let { ", $it" }.orEmpty()});")
        plan.vars[j] = name
        return name
    }

    private fun docVar(plan: Plan, j: Int, depth: Int): String {
        plan.docVars[j]?.let { return it }
        val text = requestVar(plan, j, depth) ?: "\"\""
        val name = "d${++plan.counter}"
        plan.lines.add("var $name = await host.parse($text);")
        plan.docVars[j] = name
        return name
    }

    private val SKIP_HEADERS = setOf(
        "user-agent", "accept-encoding", "connection", "host", "cookie", "content-length", "authorization",
        "accept-language", "cache-control", "pragma", "upgrade-insecure-requests",
    )

    /** `{ method, body, headers }` for what the request had beyond a plain GET with the source's headers */
    private fun optionsExpr(plan: Plan, r: RecordedRequest, index: Int, depth: Int, pageMap: Map<String, String> = emptyMap()): String? {
        val parts = ArrayList<String>()
        if (r.method != "GET") parts.add("method: ${jsStr(r.method)}")
        val headers = ArrayList<String>()
        val origin = originOf(plan.rec.baseUrl)
        for ((name, value) in r.requestHeaders) {
            if (name.lowercase() in SKIP_HEADERS) continue
            if (plan.rec.headers.any { it.first.equals(name, true) && it.second == value }) continue
            val earlier = (0 until index).firstOrNull { plan.step.requests[it].url == value && plan.urlExprs[it] != null }
            val expr = when {
                earlier != null -> plan.urlExprs[earlier]!!
                value == "$origin/" -> "root + \"/\""
                value == origin -> "root"
                else -> concat(splitKnown(plan, value, encode = false))
            }
            headers.add("${jsStr(name)}: $expr")
        }
        if (headers.isNotEmpty()) parts.add("headers: { ${headers.joinToString(", ")} }")
        val body = r.requestBody
        if (body != null) {
            val form = r.requestHeaders.any { it.first.equals("Content-Type", true) && "x-www-form-urlencoded" in it.second }
            val expr = if (form) {
                val pieces = ArrayList<Piece>()
                body.split('&').forEachIndexed { i, pair ->
                    if (i > 0) pieces.add(Piece.Lit("&"))
                    val name = pair.substringBefore('=')
                    pieces.add(Piece.Lit(name))
                    if ('=' in pair) {
                        pieces.add(Piece.Lit("="))
                        val raw = pair.substringAfter('=')
                        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                        pieces.addAll(tokenPieces(plan, decoded, raw, "b$i", pageMap, index, depth))
                    }
                }
                concat(pieces)
            } else {
                jsonBodyExpr(plan, body, pageMap) ?: concat(splitKnown(plan, body, encode = false, queryOk = true))
            }
            parts.add("body: $expr")
        } else if (r.method != "GET" && r.method != "HEAD") {
            plan.todos.add("the ${r.method} body of ${shortUrl(r.url)} was not recorded (streamed or larger than 8 KB)")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ", "{ ", " }")
    }

    /**
     * r98 fix: a JSON request body written back value by value: the search words and known values only inside
     * string values (never in a key), the page where [pageMap] has its leaf ("j" + path key). Null when not JSON.
     */
    private fun jsonBodyExpr(plan: Plan, body: String, pageMap: Map<String, String>): String? {
        val json: JsonElement = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        if (json !is JsonObject && json !is JsonArray) return null
        val pieces = ArrayList<Piece>()
        fun inner(text: String) = jsStr(text).let { it.substring(1, it.length - 1) }
        fun write(el: JsonElement, path: List<Any>) {
            when (el) {
                is JsonObject -> {
                    pieces.add(Piece.Lit("{"))
                    el.entries.forEachIndexed { i, (k, v) ->
                        if (i > 0) pieces.add(Piece.Lit(","))
                        pieces.add(Piece.Lit(jsStr(k) + ":"))
                        write(v, path + k)
                    }
                    pieces.add(Piece.Lit("}"))
                }
                is JsonArray -> {
                    pieces.add(Piece.Lit("["))
                    el.forEachIndexed { i, v ->
                        if (i > 0) pieces.add(Piece.Lit(","))
                        write(v, path + i)
                    }
                    pieces.add(Piece.Lit("]"))
                }
                is JsonNull -> pieces.add(Piece.Lit("null"))
                is JsonPrimitive -> {
                    val page = pageMap["j" + pathKey(path)]
                    when {
                        page != null && el.isString -> pieces.addAll(listOf(Piece.Lit("\""), Piece.Expr(page), Piece.Lit("\"")))
                        page != null -> pieces.add(Piece.Expr(page))
                        !el.isString -> pieces.add(Piece.Lit(el.content))
                        else -> {
                            pieces.add(Piece.Lit("\""))
                            for (p in splitKnown(plan, el.content, encode = false, queryOk = true)) {
                                pieces.add(if (p is Piece.Lit) Piece.Lit(inner(p.text)) else Piece.Expr("JSON.stringify(${(p as Piece.Expr).code}).slice(1, -1)"))
                            }
                            pieces.add(Piece.Lit("\""))
                        }
                    }
                }
            }
        }
        write(json, emptyList())
        return concat(pieces)
    }

    /**
     * The first lines of a written part: the site's origin (`root`, scheme + host only), then (r98 fix) one
     * `setting(key, default)` read per setting whose recorded value the part's code uses, then the chain requests.
     */
    private fun partHead(plan: Plan, rest: List<String>): List<String> = buildList {
        add("var root = origin(await site());")
        val text = (plan.lines + rest).joinToString("\n")
        for (k in plan.known) {
            val s = k.setting ?: continue
            if (k.expr !in text) continue
            plan.settingsUsed.add(s.key)
            // No default: a list's value seen in the recording (one of its options); a text's own value never
            val default = s.default?.let { jsSafe(pretty.encodeToString(JsonElement.serializer(), it)) }
                ?: if (s.type == "list") jsStr(k.value) else "\"\""
            add("var ${k.expr} = await setting(${jsStr(s.key)}, $default);")
        }
        addAll(plan.lines)
    }

    // ---------- lists: entries, chapters, pages ----------

    private enum class Kind { Entries, Chapters, Pages }

    /** The JS field name for a recorded item field, per kind */
    private fun jsField(kind: Kind, field: String): String = when {
        kind == Kind.Chapters && field == "name" -> "title"
        kind == Kind.Chapters && field == "date" -> "dateUploaded"
        else -> field
    }

    private fun keyField(kind: Kind): String = when (kind) {
        Kind.Entries -> "title"
        Kind.Chapters -> "url"
        Kind.Pages -> "imageUrl"
    }

    private fun fieldsOf(kind: Kind): List<String> = when (kind) {
        Kind.Entries -> listOf("url", "title", "coverUrl")
        Kind.Chapters -> listOf("url", "name", "number", "date", "scanlator")
        Kind.Pages -> listOf("imageUrl")
    }

    private fun required(kind: Kind): List<String> = when (kind) {
        Kind.Entries -> listOf("url", "title")
        Kind.Chapters -> listOf("url")
        Kind.Pages -> listOf("imageUrl")
    }

    private fun returnFor(method: String): String = when (method) {
        "getDetails" -> "return { url: url };"
        "getFilters", "getSourcePreferences" -> "return [];"
        else -> "throw new Error(${jsStr("$method was not converted from the recording: see the TODO in the code")});"
    }

    private fun listPart(rec: Recording, stepId: String, kind: Kind, page2: RecordedStep?, popularRules: List<PageRule> = emptyList()): PartCode {
        val method = when (stepId) {
            "popular" -> "getPopular"
            "latest" -> "getLatest"
            "search" -> "search"
            "chapters" -> "getChapterList"
            else -> "getPageList"
        }
        val step = rec.step(stepId) ?: return todoPart(method, "this step was not recorded", null, returnFor(method))
        if (step.items.isEmpty()) {
            return todoPart(method, step.error?.let { "the extension failed: $it" } ?: "the extension returned nothing", step, returnFor(method))
        }
        val key = keyField(kind)
        val samples = step.items.filter { !it[key].isNullOrBlank() }.take(30)
        if (samples.isEmpty()) {
            return todoPart(method, "the returned items have no $key (the extension finds it later, one request per item)", step, returnFor(method))
        }
        // The request whose reply holds the most of the returned values
        val candidates = step.requests.indices.filter { i ->
            val r = step.requests[i]
            !r.image && !r.infrastructure && r.error == null && r.body != null && r.botCheck == null
        }
        val scored = candidates.map { i -> i to holdScore(step.requests[i], samples, key) }.filter { it.second > 0 }
        val top = scored.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })?.first
            ?: return todoPart(method, notHeldReason(step, "the returned ${if (key == "title") "titles" else "values"}"), step, returnFor(method))
        // Of several pages of the same address, the first one (the others are its page 2, 3, …)
        val best = scored.filter { shapeOf(step.requests[it.first].url) == shapeOf(step.requests[top].url) }.minOf { it.first }
        val plan = planFor(rec, step)
        val request = step.requests[best]

        // Page: from page 2 when recorded, else a `page=1` style parameter; a list over several replies loops
        val loop = if (stepId == "chapters" || stepId == "pages") pagedRun(step, best) else null
        // r98 fix: page 2 is matched by shape or by a page diff (`?page=` / `/page/2/` only on page 2), its POST
        // body compared too; one recorded page reuses Popular's pattern when its own address shows no page
        var pageFrom = ""
        val pageMap: Map<String, String> = when {
            loop != null -> loop.positions
            stepId == "chapters" || stepId == "pages" -> emptyMap()
            else -> {
                val second = page2?.let { secondPage(request, it) }
                val diff = second?.let { (pageDiff(request.url, it.url) ?: emptyMap()) + bodyPageDiff(request, it) }.orEmpty()
                val guess = if (diff.isEmpty()) pageGuess(request.url) else emptyMap()
                val reused = if (diff.isEmpty() && guess.isEmpty() && stepId != "popular") applyRules(request.url, popularRules) else emptyMap()
                pageFrom = when {
                    diff.isNotEmpty() -> "the page number from the diff of the recorded pages 1 and 2" +
                        (if (diff.keys.any { it.startsWith("b") || it.startsWith("j") }) " (in the request body)" else "")
                    guess.isNotEmpty() -> "the page number from its parameter"
                    reused.isNotEmpty() -> "Popular's page pattern (only page 1 was recorded here)"
                    else -> ""
                }
                diff.ifEmpty { guess.ifEmpty { reused } }
            }
        }
        val pageNote = when {
            loop != null -> "read page by page (${loop.count} pages recorded) until a page comes back empty"
            pageMap.isEmpty() && (stepId == "popular" || stepId == "latest" || stepId == "search") -> "no page number in the address: page 2 and on return nothing"
            else -> pageFrom
        }
        if (pageMap.isEmpty() && (stepId == "popular" || stepId == "latest" || stepId == "search")) {
            plan.lines.add("if (page > 1) return [];")
        }

        val json = plan.json(best)
        // r104: a JSON reply cut at the size limit can't be read as JSON (the HTML path below would only miss)
        if (json == null && request.isJson) {
            return todoPart(method, "the JSON reply of ${shortUrl(request.url)} holds the values, but it can't be read whole" + (if (request.truncated) " (cut at the recording's size limit)" else ""), step, returnFor(method))
        }
        // The address first: its chain values (a session read from an earlier reply) can appear in the item fields
        val address = urlExpr(plan, request.url, best, pageMap, 0) + optionsExpr(plan, request, best, 0, pageMap)?.let { ", $it" }.orEmpty()
        val body = ArrayList<String>()
        val how: String
        if (json != null) {
            val mapped = mapJsonList(plan, json, samples, kind) ?: return todoPart(method, "the JSON reply of ${shortUrl(request.url)} has the values, but no array maps onto every returned item", step, returnFor(method))
            val fetch = "await getJson($address)"
            if (loop != null) {
                body.add("var out = [];")
                body.add("for (var page = 1; page <= 100; page++) {")
                body.add("  var json = $fetch;")
                body.add("  var list = v(json, ${jsPath(mapped.arrayPath)}) || [];")
                body.add("  if (!list.length) break;")
                body.add("  var before = out.length;")
                body.addAll(itemLoop(mapped, kind).map { "  $it" })
                loop.lastPagePath?.let { body.add("  if (v(json, ${jsPath(it)}) !== null && page >= Number(v(json, ${jsPath(it)}))) break;") }
                body.add("  if (out.length === before) break;")
                body.add("}")
                body.add("return out;")
            } else {
                body.add("var json = $fetch;")
                body.add("var list = v(json, ${jsPath(mapped.arrayPath)}) || [];")
                body.add("var out = [];")
                body.addAll(itemLoop(mapped, kind))
                body.add("return out;")
            }
            how = "JSON at ${pathWords(mapped.arrayPath)} (${mapped.matched} of ${samples.size} returned items found in it): " +
                mapped.fields.entries.joinToString(", ") { (f, e) -> "${jsField(kind, f)} = ${e.words}" }
        } else {
            val doc = plan.doc(best) ?: return todoPart(method, "the reply of ${shortUrl(request.url)} is neither JSON nor HTML", step, returnFor(method))
            val mapped = mapHtmlList(doc, samples, kind) ?: return todoPart(method, "the page ${shortUrl(request.url)} has the values, but no selector finds every returned item", step, returnFor(method))
            val fetch = "await get($address)"
            val spec = mapped.fields.entries.joinToString(", ") { (f, p) -> "${jsField(kind, f)}: [${jsStr(p.css)}, ${jsStr(p.how)}, ${jsStr(p.transform)}]" }
            val post = ArrayList<String>()
            if (kind == Kind.Chapters && mapped.numberFromTitle) post.add("e.number = numberOf(e.title);")
            if (kind == Kind.Pages) post.add("e.index = out.length;")
            val requiredCheck = required(kind).joinToString(" && ") { "e.${jsField(kind, it)}" }
            val itemLines = buildList {
                add("var found = await htmlList(html, ${jsStr(mapped.item)}, { $spec });")
                add("for (var i = 0; i < found.length; i++) {")
                add("  var e = found[i];")
                add("  if (!($requiredCheck)) continue;")
                post.forEach { add("  $it") }
                add("  out.push(e);")
                add("}")
            }
            if (loop != null) {
                body.add("var out = [];")
                body.add("for (var page = 1; page <= 100; page++) {")
                body.add("  var html = $fetch;")
                body.add("  var before = out.length;")
                itemLines.forEach { body.add("  $it") }
                body.add("  if (out.length === before) break;")
                body.add("}")
            } else {
                body.add("var html = $fetch;")
                body.add("var out = [];")
                body.addAll(itemLines)
            }
            body.add("return out;")
            how = "HTML of ${shortUrl(request.url)}: items \"${mapped.item}\" (${mapped.matched} of ${samples.size} returned items found), " +
                mapped.fields.entries.joinToString(", ") { (f, p) -> "${jsField(kind, f)} from \"${p.css.ifBlank { "the item" }}\" ${p.how}" } +
                (if (mapped.numberFromTitle) ", number from the title" else "")
        }

        val lines = ArrayList<String>()
        lines.add("// Recorded: ${requestLine(request)}")
        if (step.dataRequests.size > 1) lines.add("// (${step.dataRequests.size} requests in this step; the reply above holds the items)")
        lines.add("// ${commentSafe(how)}")
        if (pageNote.isNotBlank()) lines.add("// Paging: ${commentSafe(pageNote)}")
        if (stepId == "search" && rec.filters.isNotEmpty()) {
            lines.add("// TODO filters: getFilters() lists the extension's filters, but how it sends them was not recorded")
            lines.add("// (the search ran with the defaults); read `filters` here and add them to the address.")
        }
        // r98 fix: say so when the search words are nowhere in the request (a query value or a body value)
        if (stepId == "search" && !plan.queryUsed) lines.add("// TODO search: query not found in the recorded request")
        plan.todos.distinct().forEach { lines.add("// TODO: ${commentSafe(it)}") }
        lines.addAll(partHead(plan, body))
        lines.addAll(body)
        val explain = "Written from ${requestWords(request)}: ${how}." +
            (if (pageNote.isNotBlank()) " Paging: $pageNote." else "") +
            (if (stepId == "search" && !plan.queryUsed) " The search words were not found in the recorded request." else "") +
            (if (plan.todos.isNotEmpty()) " To check: ${plan.todos.distinct().joinToString("; ")}." else "") +
            (if (stepId == "search" && rec.filters.isNotEmpty()) " Filters are listed but not sent yet (not recorded)." else "")
        return PartCode(
            lines,
            written = true,
            explain = explain,
            todo = false,
            settingsUsed = plan.settingsUsed.toSet(),
            pageRules = if (stepId == "popular" && pageMap.isNotEmpty()) rulesOf(request.url, pageMap) else emptyList(),
        )
    }

    /** How many sample values a reply holds as text (JSON leaves, or the page's text and attributes) */
    private fun holdScore(r: RecordedRequest, samples: List<Map<String, String>>, key: String): Int {
        val body = r.body ?: return 0
        if (r.isJson) {
            // r104: a JSON reply that doesn't parse (cut at the size limit) is searched as text, its escapes read
            val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                ?: return samples.count { s ->
                    val target = s[key] ?: return@count false
                    target.length >= 2 && (target in body || jsonEscaped(target).any { it in body })
                }
            val values = HashSet<String>()
            val out = ArrayList<Pair<List<Any>, String>>()
            leaves(json, emptyList(), out, 0, maxDepth = 10)
            out.forEach { values.add(norm(it.second)) }
            return samples.count { s ->
                val target = s[key] ?: return@count false
                norm(target) in values || (key != "title" && out.any { it.second.length >= 4 && it.second in target })
            }
        }
        val text = htmlUnescape(body)
        return samples.count { s ->
            val target = s[key] ?: return@count false
            target in body || target in text || norm(target) in norm(text) || pathOf(target).let { it.length >= 4 && it in body }
        }
    }

    /** A step whose items came over several replies of the same address with an increasing number */
    private class PagedRun(val positions: Map<String, String>, val count: Int, val lastPagePath: List<Any>?)

    private fun pagedRun(step: RecordedStep, first: Int): PagedRun? {
        val firstReq = step.requests[first]
        val shape = shapeOf(firstReq.url)
        val same = step.requests.withIndex().filter { (i, r) -> i > first && !r.image && !r.infrastructure && shapeOf(r.url) == shape }
        if (same.isEmpty()) return null
        val diff = pageDiff(firstReq.url, same.first().value.url) ?: return null
        // "q+" / "p+<i>" (a page only page 2 has) carry the parameter's name, not an expression
        if (diff.any { (pos, expr) -> '+' !in pos && expr != "page" }) return null
        val count = same.size + 1
        // A field of page 1 (outside the items) equal to the number of pages read: the last page
        val json = runCatching { firstReq.body?.let { Json.parseToJsonElement(it) } }.getOrNull()
        var last: List<Any>? = null
        if (json is JsonObject) {
            for ((k, v) in json) {
                val n = (v as? JsonPrimitive)?.content?.toDoubleOrNull() ?: continue
                if (n == count.toDouble() && Regex("""last|pages|total_page""", RegexOption.IGNORE_CASE).containsMatchIn(k)) {
                    last = listOf(k)
                    break
                }
            }
        }
        return PagedRun(diff, count, last)
    }

    /** An address with its numbers blanked: two addresses of the same list page by page have the same shape */
    private fun shapeOf(url: String): String = url.substringBefore('#').replace(Regex("""\d+"""), "#")

    // ---------- JSON lists ----------

    private class FieldExpr(val code: String, val words: String)

    private class JsonList(val arrayPath: List<Any>, val fields: Map<String, FieldExpr>, val matched: Int)

    private fun mapJsonList(plan: Plan, root: JsonElement, samples: List<Map<String, String>>, kind: Kind): JsonList? {
        val key = keyField(kind)
        val arrays = ArrayList<Pair<List<Any>, JsonArray>>()
        arraysIn(root, emptyList(), arrays, 0)
        var best: Triple<List<Any>, List<Pair<Map<String, String>, Map<String, String>>>, Int>? = null
        var bestPaths: Map<String, List<Any>> = emptyMap()
        for ((path, array) in arrays) {
            if (array.isEmpty()) continue
            val pathOfKey = HashMap<String, List<Any>>()
            val maps = array.map { element ->
                val out = ArrayList<Pair<List<Any>, String>>()
                if (element is JsonPrimitive) {
                    if (element !is JsonNull) out.add(emptyList<Any>() to element.content)
                } else {
                    leaves(element, emptyList(), out, 0, maxDepth = 4)
                }
                out.associate { (p, value) -> pathKey(p).also { pathOfKey[it] = p } to value }
            }
            val used = HashSet<Int>()
            val pairs = ArrayList<Pair<Map<String, String>, Map<String, String>>>()
            var exact = 0
            for (s in samples) {
                val target = s[key] ?: continue
                val n = norm(target)
                var hit = maps.indices.firstOrNull { it !in used && maps[it].values.any { v -> norm(v) == n } }
                if (hit != null) {
                    exact++
                } else if (key != "title") {
                    hit = maps.indices.firstOrNull { it !in used && maps[it].values.any { v -> v.length >= 4 && v in target } }
                }
                if (hit != null) {
                    used.add(hit)
                    pairs.add(s to maps[hit])
                }
            }
            val score = exact * 2 + (pairs.size - exact)
            if (pairs.size * 2 >= minOf(samples.size, array.size) && pairs.isNotEmpty() && score > (best?.third ?: 0)) {
                best = Triple(path, pairs, score)
                bestPaths = pathOfKey
            }
        }
        val (arrayPath, pairs, _) = best ?: return null
        val fields = LinkedHashMap<String, FieldExpr>()
        for (field in fieldsOf(kind)) {
            val expr = fieldExpr(plan, field, pairs, bestPaths) { p -> "v(it, ${jsPath(p)})" }
            if (expr != null) fields[field] = expr
        }
        if (required(kind).any { it !in fields }) return null
        // A chapter without a number of its own: the one in its title
        if (kind == Kind.Chapters && "number" !in fields && "name" in fields) {
            val fromTitle = pairs.all { (s, _) -> s["number"]?.toDoubleOrNull()?.let { n -> numberIn(s["name"]) == n } ?: true }
            if (fromTitle && pairs.any { it.first["number"] != null }) fields["number"] = FieldExpr("numberOf(${fields["name"]!!.code})", "the number in the title")
        }
        return JsonList(arrayPath, fields, pairs.size)
    }

    private fun itemLoop(mapped: JsonList, kind: Kind): List<String> = buildList {
        add("for (var i = 0; i < list.length; i++) {")
        add("  var it = list[i];")
        val fields = mapped.fields.entries.joinToString(", ") { (f, e) ->
            val code = if (f == "coverUrl" || f == "scanlator") "${e.code} || null" else e.code
            "${jsField(kind, f)}: $code"
        }
        add("  var e = { $fields };")
        if (kind == Kind.Pages) add("  e.index = out.length;")
        add("  if (${required(kind).joinToString(" && ") { "e.${jsField(kind, it)}" }}) out.push(e);")
        add("}")
    }

    /**
     * The expression of one returned [field] over the aligned (item, leaves) pairs: numbers and dates by value, text
     * as constant pieces and leaf values ([solve]); [leafCode] turns a leaf path into JS.
     */
    private fun fieldExpr(
        plan: Plan,
        field: String,
        pairs: List<Pair<Map<String, String>, Map<String, String>>>,
        paths: Map<String, List<Any>>,
        leafCode: (List<Any>) -> String,
    ): FieldExpr? {
        val present = pairs.filter { !it.first[field].isNullOrBlank() }
        if (present.isEmpty() || present.size * 2 < pairs.size) return null
        when (field) {
            "number" -> {
                val keys = present.first().second.keys.filter { k ->
                    present.all { (s, m) ->
                        val want = s[field]!!.toDoubleOrNull() ?: return@all false
                        val got = m[k]?.toDoubleOrNull() ?: return@all false
                        abs(want - got) < 0.0005
                    }
                }
                val k = keys.minByOrNull { (paths[it]?.size ?: 9) * 100 + it.length } ?: return null
                val p = paths[k] ?: return null
                return FieldExpr("numberOf(${leafCode(p)})", pathWords(p))
            }
            "date" -> {
                val k = present.first().second.keys.firstOrNull { k ->
                    present.all { (s, m) ->
                        val want = s[field]!!.toLongOrNull() ?: return@all false
                        val got = m[k]?.let(::parseDate) ?: return@all false
                        abs(want - got) <= 36L * 3600_000L
                    }
                } ?: return null
                val p = paths[k] ?: return null
                return FieldExpr("dateOf(${leafCode(p)})", pathWords(p) + " (a date)")
            }
        }
        val targets = present.map { it.first[field]!! }
        val samples = present.map { it.second }
        val address = field == "url" || field == "imageUrl" || field == "coverUrl"
        // r98 fix: one sample can't tell constant text from data: at most 3 constant characters around a value
        // (a separator) for a field that isn't an address; more becomes a TODO for that field
        val strict = present.size < 2 && !address
        val segs = solve(targets, samples, 0, allowConstant = present.size >= 3 && field != "url" && field != "imageUrl", maxConst = if (strict) 3 else Int.MAX_VALUE)
            ?.takeIf { found -> !strict || found.sumOf { if (it is Seg.Lit) it.text.length else 0 } <= 3 }
        if (segs == null) {
            if (strict && solve(targets, samples, 0, allowConstant = false) != null) {
                plan.todos.add("$field: one sample only, and its value has more than 3 characters of constant text around what the reply holds; not written")
            }
            return null
        }
        val pieces = ArrayList<Piece>()
        val words = ArrayList<String>()
        for (seg in segs) {
            when (seg) {
                is Seg.Lit -> {
                    pieces.addAll(splitKnown(plan, seg.text, encode = false))
                    words.add("\"${seg.text}\"")
                }
                is Seg.Ref -> {
                    val p = paths[seg.key] ?: return null
                    pieces.add(Piece.Expr("s(${leafCode(p)})"))
                    words.add(pathWords(p))
                }
            }
        }
        return FieldExpr(concat(pieces), words.joinToString(" + "))
    }

    private sealed interface Seg {
        data class Lit(val text: String) : Seg
        data class Ref(val key: String) : Seg
    }

    /**
     * [targets][i] as constant text and values of [samples][i] (key → value), the same recipe for every i: an exact
     * value first, then a value inside the text with the text around it solved the same way (two levels deep).
     */
    private fun solve(
        targets: List<String>,
        samples: List<Map<String, String>>,
        depth: Int,
        allowConstant: Boolean,
        maxConst: Int = Int.MAX_VALUE,
    ): List<Seg>? {
        if (targets.isEmpty() || targets.size != samples.size) return null
        if (allowConstant && targets.all { it == targets[0] }) {
            if (targets[0].length > maxConst) return null
            return if (targets[0].isEmpty()) emptyList() else listOf(Seg.Lit(targets[0]))
        }
        val keys = samples[0].keys
        keys.filter { k -> samples.indices.all { i -> samples[i][k]?.let { v -> norm(v) == norm(targets[i]) && v.isNotEmpty() } == true } }
            .minByOrNull { it.length }
            ?.let { return listOf(Seg.Ref(it)) }
        if (depth >= 2) return null
        val single = samples.size < 2
        val candidates = keys.filter { k ->
            samples.indices.all { i ->
                val v = samples[i][k]
                v != null && v.isNotEmpty() && (!single || v.length >= 3) && targets[i].contains(v)
            }
        }.sortedByDescending { k -> samples.indices.sumOf { samples[it][k]!!.length } }.take(12)
        for (k in candidates) {
            for (last in listOf(false, true)) {
                val lefts = ArrayList<String>()
                val rights = ArrayList<String>()
                for (i in samples.indices) {
                    val v = samples[i][k]!!
                    val t = targets[i]
                    val at = if (last) t.lastIndexOf(v) else t.indexOf(v)
                    lefts.add(t.substring(0, at))
                    rights.add(t.substring(at + v.length))
                }
                val left = solve(lefts, samples, depth + 1, allowConstant = true, maxConst = maxConst) ?: continue
                val right = solve(rights, samples, depth + 1, allowConstant = true, maxConst = maxConst) ?: continue
                return left + Seg.Ref(k) + right
            }
        }
        return null
    }

    // ---------- HTML lists ----------

    private class HtmlPick(val css: String, val how: String, val transform: String)

    private class HtmlList(val item: String, val fields: Map<String, HtmlPick>, val matched: Int, val numberFromTitle: Boolean)

    private val URL_ATTRS = listOf("href", "src", "data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "data-url", "data-href", "content", "data-bg", "poster", "data-image")
    private val TEXT_ATTRS = listOf("title", "alt", "data-title", "aria-label", "data-jname", "data-name", "content")

    /** Every address-like attribute value of [doc] in each form a pick can give (as is, absolute, path) */
    private fun urlIndex(doc: Document): Map<String, List<Triple<Element, String, String>>> {
        val pageUrl = doc.location()
        val out = HashMap<String, MutableList<Triple<Element, String, String>>>()
        fun put(key: String?, value: Triple<Element, String, String>) {
            if (key.isNullOrEmpty()) return
            out.getOrPut(key) { ArrayList() }.add(value)
        }
        for (el in doc.allElements) {
            for (a in URL_ATTRS) {
                val raw = el.attr(a)
                if (raw.isEmpty() || raw.length > 2000) continue
                val srcset = a.endsWith("srcset")
                val v = if (srcset) raw.trim().split(Regex("""[\s,]+""")).firstOrNull().orEmpty() else raw.trim()
                if (v.isEmpty()) continue
                put(v, Triple(el, a, if (srcset) "srcset" else ""))
                val absolute = resolve(pageUrl, v) ?: continue
                if (absolute != v) put(absolute, Triple(el, a, if (srcset) "srcsetabs" else "abs"))
                val path = pathOf(absolute)
                if (path != v && path != absolute) put(path, Triple(el, a, "path"))
            }
        }
        return out
    }

    /** The transform that turns [raw] (an attribute of a page at [pageUrl]) into [target], or null */
    private fun urlTransform(attr: String, raw: String, pageUrl: String, target: String): String? {
        val srcset = attr.endsWith("srcset")
        val v = (if (srcset) raw.trim().split(Regex("""[\s,]+""")).firstOrNull().orEmpty() else raw.trim())
        if (v.isEmpty()) return null
        val absolute = resolve(pageUrl, v)
        return when {
            v == target -> if (srcset) "srcset" else ""
            absolute == target -> if (srcset) "srcsetabs" else "abs"
            absolute != null && pathOf(absolute) == target -> "path"
            else -> null
        }
    }

    private fun mapHtmlList(doc: Document, samples: List<Map<String, String>>, kind: Kind): HtmlList? {
        val pageUrl = doc.location()
        // The element holding each item's address (entries and chapters) or picture (pages)
        val anchorField = if (kind == Kind.Pages) "imageUrl" else "url"
        val found = ArrayList<Triple<Map<String, String>, Element, Pair<String, String>>>()   // item, element, (attr, transform)
        val combos = HashMap<String, Int>()
        val index = urlIndex(doc)
        val perItem: List<List<Triple<Element, String, String>>> = samples.map { s ->
            val target = s[anchorField]
            if (target == null) emptyList() else index[target].orEmpty()
        }
        perItem.forEach { list -> list.map { "${it.first.tagName()}|${it.second}|${it.third}" }.distinct().forEach { combos[it] = (combos[it] ?: 0) + 1 } }
        val combo = combos.maxByOrNull { it.value }?.key ?: return null
        val (tag, attr, transform) = combo.split('|').let { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
        val usedEls = HashSet<Element>()
        samples.forEachIndexed { i, s ->
            val hit = perItem[i].firstOrNull { (el, a, t) -> el.tagName() == tag && a == attr && t == transform && usedEls.none { it === el } }
            if (hit != null) {
                usedEls.add(hit.first)
                found.add(Triple(s, hit.first, attr to transform))
            }
        }
        if (found.isEmpty() || found.size * 5 < samples.size * 3) return null

        // The card: the smallest ancestor level that holds the title too and isn't shared between items
        val titleField = when (kind) {
            Kind.Entries -> "title"
            Kind.Chapters -> "name"
            Kind.Pages -> null
        }
        var chosen: Int? = null
        if (titleField != null) {
            for (d in 0..6) {
                val level = found.map { ancestor(it.second, d) }
                if (level.any { it == null }) break
                if (level.distinctBy { System.identityHashCode(it) }.size < level.size) break
                val holds = found.indices.count { i -> textTarget(level[i]!!, found[i].first[titleField]) != null }
                if (holds * 5 >= found.size * 4) {
                    chosen = d
                    break
                }
            }
        }
        val depth = chosen ?: 0
        val cards = found.map { ancestor(it.second, depth) ?: it.second }
        val item = commonSelector(doc, cards) ?: return null

        val fields = LinkedHashMap<String, HtmlPick>()
        // The address / picture
        val anchorCss = relativeSelector(cards, found.map { it.second }) { card, css ->
            val el = if (css.isEmpty()) card else card.selectFirst(css)
            val i = cards.indexOfFirst { it === card }
            val target = found.getOrNull(i)?.first?.get(anchorField)
            el != null && target != null && urlTransform(attr, el.attr(attr), pageUrl, target) == transform
        } ?: return null
        fields[anchorField] = HtmlPick(anchorCss, "@$attr", transform)
        // The other fields, each checked on every sample
        for (field in fieldsOf(kind)) {
            if (field == anchorField || field == "number" || field == "date") continue
            val pick = fieldPick(cards, found.map { it.first[field] }, pageUrl, isUrl = field == "coverUrl") ?: continue
            fields[field] = pick
        }
        if (required(kind).any { it !in fields }) return null
        val numberFromTitle = kind == Kind.Chapters && "name" in fields &&
            found.any { it.first["number"] != null } &&
            found.all { (s, _, _) -> s["number"]?.toDoubleOrNull()?.let { numberIn(s["name"]) == it } ?: true }
        return HtmlList(item, fields, found.size, numberFromTitle)
    }

    private fun ancestor(el: Element, depth: Int): Element? {
        var cur: Element? = el
        repeat(depth) { cur = cur?.parent() }
        return cur?.takeIf { it.tagName() != "#root" && it.tagName() != "html" && it.tagName() != "body" }
    }

    /** The element inside [card] (itself included) whose text or a text attribute is [value], with how to read it */
    private fun textTarget(card: Element, value: String?): Pair<Element, String>? {
        if (value.isNullOrBlank()) return null
        val n = norm(value)
        var hit: Element? = null
        for (el in card.allElements) if (norm(el.text()) == n) hit = el    // the deepest one in document order
        if (hit != null) return hit to "text"
        for (el in card.allElements) for (a in TEXT_ATTRS) if (el.hasAttr(a) && norm(el.attr(a)) == n) return el to "@$a"
        return null
    }

    /** A field read the same way inside every card: text, a text attribute or (covers) an address attribute */
    private fun fieldPick(cards: List<Element>, values: List<String?>, pageUrl: String, isUrl: Boolean): HtmlPick? {
        val present = cards.indices.filter { !values[it].isNullOrBlank() }
        if (present.isEmpty() || present.size * 2 < cards.size) return null
        val first = present.first()
        val card0 = cards[first]
        val value0 = values[first]!!
        if (isUrl) {
            for (el in card0.allElements) {
                for (a in URL_ATTRS) {
                    val raw = el.attr(a)
                    if (raw.isEmpty()) continue
                    val t = urlTransform(a, raw, pageUrl, value0) ?: continue
                    val css = relativeSelector(present.map { cards[it] }, null, el, card0) { card, sel ->
                        val target = if (sel.isEmpty()) card else card.selectFirst(sel)
                        val i = cards.indexOfFirst { it === card }
                        target != null && urlTransform(a, target.attr(a), pageUrl, values[i]!!) == t
                    } ?: continue
                    return HtmlPick(css, "@$a", t)
                }
            }
            return null
        }
        val (el, how) = textTarget(card0, value0) ?: return null
        val css = relativeSelector(present.map { cards[it] }, null, el, card0) { card, sel ->
            val target = if (sel.isEmpty()) card else card.selectFirst(sel)
            val i = cards.indexOfFirst { it === card }
            target != null && norm(if (how == "text") target.text() else target.attr(how.removePrefix("@"))) == norm(values[i]!!)
        } ?: return null
        return HtmlPick(css, how, "")
    }

    /** A selector from each card to its target, the same for all, checked by [ok] on every card */
    private fun relativeSelector(cards: List<Element>, targets: List<Element>?, target0: Element? = null, card0: Element? = null, ok: (Element, String) -> Boolean): String? {
        val t0 = target0 ?: targets?.firstOrNull() ?: return null
        val c0 = card0 ?: cards.first()
        val candidates = LinkedHashSet<String>()
        if (t0 === c0) candidates.add("")
        val classes = stableClasses(t0)
        if (classes.isNotEmpty()) {
            candidates.add(t0.tagName() + classes.joinToString("") { ".$it" })
            candidates.add(t0.tagName() + "." + classes.first())
            candidates.add("." + classes.first())
        }
        candidates.add(t0.tagName())
        val parent = t0.parent()
        if (parent != null && parent !== c0) {
            val pc = stableClasses(parent)
            if (pc.isNotEmpty()) candidates.add(parent.tagName() + "." + pc.first() + " > " + t0.tagName())
            candidates.add(parent.tagName() + " > " + t0.tagName())
        }
        return candidates.firstOrNull { css -> cards.all { card -> ok(card, css) } }
    }

    private fun stableClasses(el: Element): List<String> =
        el.classNames().filter { c ->
            c.length in 2..40 && CLASS_NAME.matches(c) && !c.startsWith("css-") && !c.startsWith("sc-") &&
                !HEXISH.containsMatchIn(c) && !c.startsWith("active") && c != "selected"
        }

    private val CLASS_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_-]*$""")
    private val HEXISH = Regex("""[0-9a-f]{6,}""")

    /** A selector that selects all [els] and at most 3× as many elements (r98 fix: was 3× + 6) */
    private fun commonSelector(doc: Document, els: List<Element>): String? {
        val tag = els.first().tagName()
        if (els.any { it.tagName() != tag }) return null
        val common = els.map { stableClasses(it).toSet() }.reduce { a, b -> a intersect b }.toList()
        val tries = ArrayList<String>()
        if (common.isNotEmpty()) {
            tries.add(tag + common.joinToString("") { ".$it" })
            tries.add(tag + "." + common.first())
        }
        val parents = els.mapNotNull { it.parent() }
        if (parents.size == els.size) {
            val pc = parents.map { stableClasses(it).toSet() }.reduce { a, b -> a intersect b }
            val ptag = parents.first().tagName()
            if (pc.isNotEmpty() && parents.all { it.tagName() == ptag }) tries.add("$ptag.${pc.first()} > $tag")
            val pid = parents.first().id()
            if (pid.isNotEmpty() && parents.all { it === parents.first() } && CLASS_NAME.matches(pid)) tries.add("#$pid > $tag")
        }
        tries.add(tag)
        for (css in tries) {
            val selected = runCatching { doc.select(css) }.getOrNull() ?: continue
            if (els.all { el -> selected.any { it === el } } && selected.size <= els.size * 3) return css
        }
        return null
    }

    /**
     * A selector that matches [el] (with [attr] when given); ids and classes first, then the parent, then the path.
     * r98 fix: never a bare `tag[attr]` (no class, id or ancestor), and never one matching more than [limit]
     * elements (3× the recorded items); null then, and the caller writes a TODO.
     */
    private fun selectorFor(doc: Document, el: Element, attr: String?, limit: Int): String? {
        val tries = ArrayList<String>()
        val classes = stableClasses(el)
        val withAttr = if (attr != null) "[$attr]" else ""
        if (el.id().isNotEmpty() && CLASS_NAME.matches(el.id())) tries.add("#${el.id()}")
        if (classes.isNotEmpty()) tries.add(el.tagName() + classes.joinToString("") { ".$it" } + withAttr)
        el.parent()?.let { p ->
            val pc = stableClasses(p)
            if (pc.isNotEmpty()) tries.add("${p.tagName()}.${pc.first()} > ${el.tagName()}$withAttr")
            if (p.id().isNotEmpty() && CLASS_NAME.matches(p.id())) tries.add("#${p.id()} > ${el.tagName()}$withAttr")
        }
        runCatching { el.cssSelector() }.getOrNull()?.let { tries.add(it) }
        // Several matches are fine for attrAt (it takes an index), up to the limit
        for (css in tries) {
            val selected = runCatching { doc.select(css) }.getOrNull() ?: continue
            if (selected.any { it === el } && selected.size <= limit) return css
        }
        return null
    }

    // ---------- details ----------

    private fun detailsPart(rec: Recording): PartCode {
        val method = "getDetails"
        val step = rec.step("details") ?: return todoPart(method, "this step was not recorded", null, returnFor(method))
        val item = step.items.firstOrNull()
            ?: return todoPart(method, step.error?.let { "the extension failed: $it" } ?: "the extension returned nothing", step, returnFor(method))
        val fields = listOf("title", "author", "artist", "description", "genre", "coverUrl").filter { !item[it].isNullOrBlank() }
        if (fields.isEmpty()) return todoPart(method, "the details had no fields to look for", step, returnFor(method))
        val candidates = step.requests.indices.filter { i ->
            val r = step.requests[i]
            !r.image && !r.infrastructure && r.error == null && r.body != null && r.botCheck == null
        }
        val scored = candidates.map { i -> i to fields.count { f -> holdScore(step.requests[i], listOf(item), f) > 0 } }.filter { it.second > 0 }
        val best = scored.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })?.first
            ?: return todoPart(method, notHeldReason(step, "the title page's values"), step, returnFor(method))
        val plan = planFor(rec, step)
        val request = step.requests[best]
        val json = plan.json(best)
        if (json == null && request.isJson) {
            return todoPart(method, "the JSON reply of ${shortUrl(request.url)} holds the values, but it can't be read whole" + (if (request.truncated) " (cut at the recording's size limit)" else ""), step, returnFor(method))
        }
        val address = urlExpr(plan, request.url, best, emptyMap(), 0) + optionsExpr(plan, request, best, 0)?.let { ", $it" }.orEmpty()
        val body = ArrayList<String>()
        val words = ArrayList<String>()
        if (json != null) {
            val out = ArrayList<Pair<List<Any>, String>>()
            leaves(json, emptyList(), out, 0, maxDepth = 8)
            val paths = HashMap<String, List<Any>>()
            val map = LinkedHashMap<String, String>()
            out.forEach { (p, v) -> pathKey(p).let { k -> map[k] = v; paths[k] = p } }
            val props = ArrayList<String>()
            for (f in fields) {
                if (f == "genre") {
                    genreJson(json, item[f]!!)?.let { (listPath, rel) ->
                        props.add("genres: (v(json, ${jsPath(listPath)}) || []).map(function (g) { return s(v(g, ${jsPath(rel)})); }).filter(Boolean)")
                        words.add("genres from ${pathWords(listPath)}")
                    }
                    continue
                }
                val expr = fieldExpr(plan, f, listOf(item to map), paths) { p -> "v(json, ${jsPath(p)})" } ?: continue
                props.add("$f: ${expr.code}")
                words.add("$f = ${expr.words}")
            }
            if (props.isEmpty()) return todoPart(method, "the JSON reply of ${shortUrl(request.url)} holds no field the details had", step, returnFor(method))
            body.add("var json = await getJson($address);")
            body.add("var d = { ${(listOf("url: url") + props).joinToString(", ")} };")
            body.add("return d;")
        } else {
            val doc = plan.doc(best) ?: return todoPart(method, "the reply of ${shortUrl(request.url)} is neither JSON nor HTML", step, returnFor(method))
            val specs = ArrayList<String>()
            for (f in fields) {
                val pick = detailPick(doc, f, item[f]!!) ?: continue
                val name = if (f == "genre") "genres" else f
                specs.add("$name: [${jsStr(pick.css)}, ${jsStr(pick.how)}, ${jsStr(pick.transform)}]")
                words.add("$name from \"${pick.css}\" ${pick.how}")
            }
            if (specs.isEmpty()) return todoPart(method, "no element of ${shortUrl(request.url)} holds the details' values", step, returnFor(method))
            body.add("var html = await get($address);")
            body.add("var d = await htmlFields(html, { ${specs.joinToString(", ")} });")
            body.add("d.url = url;")
            body.add("return d;")
        }
        val lines = ArrayList<String>()
        lines.add("// Recorded: ${requestLine(request)}")
        lines.add("// ${commentSafe(words.joinToString("; "))}")
        val missing = fields.filter { f -> words.none { it.startsWith(if (f == "genre") "genres" else f) } }
        if (missing.isNotEmpty()) lines.add("// Not found in the reply (the list's values are kept): ${missing.joinToString(", ")}")
        plan.todos.distinct().forEach { lines.add("// TODO: ${commentSafe(it)}") }
        lines.addAll(partHead(plan, body))
        lines.addAll(body)
        return PartCode(
            lines,
            written = true,
            explain = "Written from ${requestWords(request)}: ${words.joinToString("; ")}." +
                (if (missing.isNotEmpty()) " Not found: ${missing.joinToString(", ")} (the list's values stay)." else "") +
                (if (plan.todos.isNotEmpty()) " To check: ${plan.todos.distinct().joinToString("; ")}." else ""),
            todo = false,
            settingsUsed = plan.settingsUsed.toSet(),
        )
    }

    /** An array whose items' field equals the genres, in any order */
    private fun genreJson(root: JsonElement, genre: String): Pair<List<Any>, List<Any>>? {
        val wanted = genre.split(',').map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return null
        val arrays = ArrayList<Pair<List<Any>, JsonArray>>()
        arraysIn(root, emptyList(), arrays, 0)
        for ((path, array) in arrays) {
            if (array.isEmpty()) continue
            val first = array.first()
            val rels = ArrayList<Pair<List<Any>, String>>()
            if (first is JsonPrimitive) rels.add(emptyList<Any>() to first.content) else leaves(first, emptyList(), rels, 0, maxDepth = 2)
            for ((rel, _) in rels) {
                val values = array.mapNotNull { at(it, rel)?.let { e -> (e as? JsonPrimitive)?.content?.let(::norm) } }.toSet()
                if (values == wanted) return path to rel
            }
        }
        return null
    }

    private fun detailPick(doc: Document, field: String, value: String): HtmlPick? {
        val pageUrl = doc.location()
        when (field) {
            "coverUrl" -> {
                for ((el, a) in doc.allElements.flatMap { e -> URL_ATTRS.map { e to it } }) {
                    val raw = el.attr(a)
                    if (raw.isEmpty()) continue
                    val t = urlTransform(a, raw, pageUrl, value) ?: continue
                    val css = uniqueSelector(doc, el) { it.attr(a).let { r -> r.isNotEmpty() && urlTransform(a, r, pageUrl, value) == t } } ?: continue
                    return HtmlPick(css, "@$a", t)
                }
                return null
            }
            "genre" -> {
                val genres = value.split(',').map { norm(it) }.filter { it.isNotEmpty() }
                if (genres.isEmpty()) return null
                val firstEl = doc.allElements.lastOrNull { norm(it.text()) == genres.first() && it.children().isEmpty() } ?: return null
                val tries = ArrayList<String>()
                stableClasses(firstEl).firstOrNull()?.let { tries.add("${firstEl.tagName()}.$it") }
                firstEl.parent()?.let { p ->
                    stableClasses(p).firstOrNull()?.let { tries.add("${p.tagName()}.$it > ${firstEl.tagName()}"); tries.add(".$it ${firstEl.tagName()}") }
                    p.parent()?.let { gp -> stableClasses(gp).firstOrNull()?.let { tries.add(".$it ${firstEl.tagName()}") } }
                }
                for (css in tries) {
                    val texts = runCatching { doc.select(css).map { norm(it.text()) } }.getOrNull() ?: continue
                    if (texts.toSet().containsAll(genres) && texts.size <= genres.size + 2) return HtmlPick(css, "text", "all")
                }
                return null
            }
            "description" -> {
                val n = norm(value)
                val probe = n.take(60)
                var hit: Element? = null
                for (el in doc.allElements) {
                    val t = norm(el.text())
                    if (t == n || (probe.length >= 20 && t.contains(probe) && t.length <= n.length * 2 + 40)) hit = el
                }
                val el = hit ?: return metaPick(doc, listOf("og:description", "description"), n)
                val css = uniqueSelector(doc, el) { norm(it.text()).contains(probe) } ?: return null
                return HtmlPick(css, "text", "")
            }
            else -> {
                val n = norm(value)
                var hit: Element? = null
                for (el in doc.body()?.allElements.orEmpty()) if (norm(el.text()) == n) hit = el
                if (hit != null) {
                    val css = uniqueSelector(doc, hit) { norm(it.text()) == n }
                    if (css != null) return HtmlPick(css, "text", "")
                }
                if (field == "title") return metaPick(doc, listOf("og:title", "twitter:title"), n)
                return null
            }
        }
    }

    private fun metaPick(doc: Document, names: List<String>, value: String): HtmlPick? {
        for (name in names) {
            for (attr in listOf("property", "name")) {
                val css = "meta[$attr=$name]"
                val el = runCatching { doc.selectFirst(css) }.getOrNull() ?: continue
                if (norm(el.attr("content")) == value || (value.length >= 40 && norm(el.attr("content")).startsWith(value.take(40)))) {
                    return HtmlPick(css, "@content", "")
                }
            }
        }
        return null
    }

    /** A selector whose first match passes [ok]: the element's id, classes, parent's class, then Jsoup's path */
    private fun uniqueSelector(doc: Document, el: Element, ok: (Element) -> Boolean): String? {
        val tries = ArrayList<String>()
        val classes = stableClasses(el)
        if (el.id().isNotEmpty() && CLASS_NAME.matches(el.id())) tries.add("#${el.id()}")
        if (classes.isNotEmpty()) {
            tries.add(el.tagName() + classes.joinToString("") { ".$it" })
            tries.add(el.tagName() + "." + classes.first())
        }
        el.parent()?.let { p ->
            if (p.id().isNotEmpty() && CLASS_NAME.matches(p.id())) tries.add("#${p.id()} > ${el.tagName()}")
            stableClasses(p).firstOrNull()?.let { tries.add("${p.tagName()}.$it > ${el.tagName()}") }
        }
        if (el.tagName() in setOf("h1", "h2")) tries.add(el.tagName())
        runCatching { el.cssSelector() }.getOrNull()?.let { tries.add(it) }
        return tries.firstOrNull { css -> runCatching { doc.selectFirst(css) }.getOrNull()?.let(ok) == true }
    }

    // ---------- videos ----------

    private fun videoPart(rec: Recording): PartCode {
        val method = "getVideoList"
        val step = rec.step("videos") ?: return todoPart(method, "this step was not recorded", null, returnFor(method))
        val videos = step.items.filter { !it["url"].isNullOrBlank() }
        if (videos.isEmpty()) {
            return todoPart(method, step.error?.let { "the extension failed: $it" } ?: "the extension returned no video", step, returnFor(method))
        }
        val plan = planFor(rec, step)
        val first = videos.first()
        val url = first["url"]!!
        val headerPairs = first.filterKeys { it.startsWith("header:") }.map { it.key.removePrefix("header:") to it.value }

        // Where each video's address is: a JSON value, an attribute, the text of a reply, or a packed script in it
        val located = videos.map { v -> locateVideo(plan, v["url"]!!) }
        val loc = located.first() ?: run {
            val reason = "the address ${shortUrl(url)} is in none of the recorded replies, not even inside a packed script " +
                "(the extension builds it, or got it from a request the app didn't see)"
            return todoPart(method, reason, step, returnFor(method), plan.lines)
        }
        val request = step.requests[loc.index]
        val lines = ArrayList<String>()
        val explain: String
        // Built after the addresses: a Referer that is an earlier request's address reads its expression
        val headersCode by lazy { headersObject(plan, headerPairs, loc.index) }

        when (loc.kind) {
            VideoWhere.Json -> {
                val json = plan.json(loc.index)!!
                val fetch = "await getJson(${urlExpr(plan, request.url, loc.index, emptyMap(), 0)}${optionsExpr(plan, request, loc.index, 0)?.let { ", $it" }.orEmpty()})"
                val samples = videos.filter { located.getOrNull(videos.indexOf(it))?.index == loc.index }
                val mapped = mapJsonVideos(plan, json, samples)
                    ?: return todoPart(method, "the JSON reply of ${shortUrl(request.url)} has the address, but no array maps onto the videos", step, returnFor(method), plan.lines)
                lines.add("var json = $fetch;")
                lines.add("var list = v(json, ${jsPath(mapped.arrayPath)}) || [];")
                lines.add("var out = [];")
                lines.add("for (var i = 0; i < list.length; i++) {")
                lines.add("  var it = list[i];")
                lines.add("  var e = { url: ${mapped.fields["url"]!!.code}, quality: ${mapped.fields["quality"]?.code ?: jsStr(first["quality"].orEmpty())}, headers: $headersCode };")
                lines.add("  if (e.url) out.push(e);")
                lines.add("}")
                lines.add("return out;")
                explain = "Written from ${requestWords(request)}: the videos at ${pathWords(mapped.arrayPath)}, address ${mapped.fields["url"]!!.words}" +
                    (mapped.fields["quality"]?.let { ", quality ${it.words}" } ?: "")
            }
            VideoWhere.Attr -> {
                val doc = plan.doc(loc.index)!!
                val css = loc.css!!
                val fetch = "await get(${urlExpr(plan, request.url, loc.index, emptyMap(), 0)}${optionsExpr(plan, request, loc.index, 0)?.let { ", $it" }.orEmpty()})"
                lines.add("var page = await host.parse($fetch);")
                lines.add("var found = await page.select(${jsStr(css)});")
                lines.add("var out = [];")
                lines.add("for (var i = 0; i < found.length; i++) {")
                lines.add("  var u = shape(found[i].attr(${jsStr(loc.attr!!)}), ${jsStr(loc.transform)});")
                lines.add("  if (u) out.push({ url: u, quality: ${jsStr(first["quality"].orEmpty())} + (found.length > 1 ? \" \" + (i + 1) : \"\"), headers: $headersCode });")
                lines.add("}")
                lines.add("return out;")
                explain = "Written from ${requestWords(request)}: every \"$css\" ${loc.attr} (${doc.select(css).size} on the recorded page)"
            }
            VideoWhere.Text, VideoWhere.Packed -> {
                // The reply that held the address may be one of several (one per server or quality) found from an
                // earlier page: then every link of that page is followed the same way
                val text = if (loc.kind == VideoWhere.Packed) "unpacked(body)" else "body"
                val regex = loc.regex!!
                val linkSource = linkSourceOf(plan, step, loc.index)
                if (linkSource != null && linkSource.count >= 2) {
                    val pageVar = docVar(plan, linkSource.pageIndex, 0)
                    val embedOptions = optionsExpr(plan, request, loc.index, 0)?.let { ", $it" }.orEmpty()
                    val qualityExpr = qualityFromLink(plan, videos, located, linkSource) ?: jsStr(first["quality"].orEmpty())
                    // r98 fix: at most twice the embed pages (hosters) the recording followed
                    val hosters = located.filterNotNull().map { it.index }.distinct().size.coerceAtLeast(1)
                    // r98 fix: inside the loop a Referer that was the recorded embed's address is this embed's
                    val loopHeaders = headersObject(plan, headerPairs, loc.index, embedUrl = request.url)
                    lines.add("var links = await $pageVar.select(${jsStr(linkSource.css)});")
                    lines.add("var out = [];")
                    lines.add("for (var i = 0; i < links.length && i < ${hosters * 2}; i++) {")
                    lines.add("  var link = links[i];")
                    lines.add("  var embed = abs(link.attr(${jsStr(linkSource.attr)}));")
                    lines.add("  if (!embed) continue;")
                    lines.add("  var body = await get(embed$embedOptions);")
                    lines.add("  var m = $text.match($regex);")
                    lines.add("  if (m) out.push({ url: unslash(m[1]), quality: $qualityExpr, headers: $loopHeaders });")
                    lines.add("}")
                    lines.add("return out;")
                    explain = "Written from the recorded chain: every \"${linkSource.css}\" ${linkSource.attr} of ${shortUrl(step.requests[linkSource.pageIndex].url)} " +
                        "(${linkSource.count} links), each page fetched and the address read with $regex from its ${if (loc.kind == VideoWhere.Packed) "unpacked packed script" else "text"}"
                } else {
                    val fetch = "await get(${urlExpr(plan, request.url, loc.index, emptyMap(), 0)}${optionsExpr(plan, request, loc.index, 0)?.let { ", $it" }.orEmpty()})"
                    lines.add("var body = $fetch;")
                    lines.add("var m = $text.match($regex);")
                    lines.add("if (!m) throw new Error(\"No video address in \" + ${jsStr(shortUrl(request.url))});")
                    lines.add("return [{ url: unslash(m[1]), quality: ${jsStr(first["quality"].orEmpty())}, headers: $headersCode }];")
                    explain = "Written from ${requestWords(request)}: the address read with $regex from its ${if (loc.kind == VideoWhere.Packed) "unpacked packed script" else "text"}"
                }
            }
        }
        val out = ArrayList<String>()
        out.add("// Recorded: ${requestLine(request)}")
        out.add("// ${commentSafe(explain)}")
        if (videos.size > 1 && located.count { it != null } < videos.size) {
            out.add("// ${videos.size - located.count { it != null }} of the ${videos.size} recorded videos were not found the same way (other servers): not written")
        }
        plan.todos.distinct().forEach { out.add("// TODO: ${commentSafe(it)}") }
        out.addAll(partHead(plan, lines))
        out.addAll(lines)
        return PartCode(
            out,
            written = true,
            explain = "$explain." + (if (plan.todos.isNotEmpty()) " To check: ${plan.todos.distinct().joinToString("; ")}." else ""),
            todo = false,
            settingsUsed = plan.settingsUsed.toSet(),
        )
    }

    private enum class VideoWhere { Json, Attr, Text, Packed }

    private class VideoLoc(val index: Int, val kind: VideoWhere, val css: String? = null, val attr: String? = null, val transform: String = "", val regex: String? = null)

    private fun locateVideo(plan: Plan, url: String): VideoLoc? {
        val requests = plan.step.requests
        for (j in requests.indices.reversed()) {
            val r = requests[j]
            if (r.image || r.infrastructure || r.error != null || r.body == null || r.url == url) continue
            val json = plan.json(j)
            if (json != null) {
                if (leafPathFor(json, url) != null) return VideoLoc(j, VideoWhere.Json)
                continue
            }
            val doc = plan.doc(j)
            if (doc != null) {
                for ((el, a) in doc.allElements.flatMap { e -> URL_ATTRS.map { e to it } }) {
                    val raw = el.attr(a)
                    if (raw.isEmpty()) continue
                    val t = urlTransform(a, raw, r.url, url) ?: continue
                    val limit = maxOf(1, plan.step.items.size) * 3
                    val css = selectorFor(doc, el, a, limit) ?: run {
                        plan.todos.add("the video address is an attribute of ${shortUrl(r.url)}, but no selector picks it out (at most $limit matches)")
                        null
                    } ?: continue
                    return VideoLoc(j, VideoWhere.Attr, css = css, attr = a, transform = t)
                }
            }
            textRegex(r.body, url)?.let { return VideoLoc(j, VideoWhere.Text, regex = it) }
            val unpacked = Unpacker.unpackAll(r.body).joinToString("\n")
            if (unpacked.isNotEmpty()) textRegex(unpacked, url)?.let { return VideoLoc(j, VideoWhere.Packed, regex = it) }
        }
        return null
    }

    /** A JS regex literal that finds [value] in [text] by what stands before it, ending at the character after it */
    private fun textRegex(text: String, value: String): String? {
        val forms = listOf(value, value.replace("/", "\\/"))
        for (form in forms) {
            val at = text.indexOf(form)
            if (at < 0) continue
            var start = at
            while (start > 0 && at - start < 24 && text[start - 1] !in ";{(\n,") start--
            val left = text.substring(start, at)
            if (left.isBlank()) continue
            val end = text.getOrNull(at + form.length) ?: continue
            if (end.isLetterOrDigit()) continue
            // The left text must find this value first, or the regex would read another one
            if (text.indexOf(left) != start) continue
            return "/" + regexEscape(left) + "([^" + regexClassEscape(end) + "]+)/"
        }
        return null
    }

    private fun regexEscape(s: String): String = buildString {
        for (c in s) {
            val control = controlEscape(c)
            if (control != null) {
                append(control)
                continue
            }
            if (c in "\\^$.|?*+()[]{}/") append('\\')
            append(c)
        }
    }

    private fun regexClassEscape(c: Char): String = when (c) {
        '\\', ']', '^', '-', '/' -> "\\$c"
        else -> controlEscape(c) ?: c.toString()
    }

    /**
     * r98 fix: a character that can't stand as itself in a JS regex or string literal (line breaks, U+2028 /
     * U+2029, other control characters) as its escape; null for any other character.
     */
    private fun controlEscape(c: Char): String? = when (c) {
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        '\u2028', '\u2029' -> "\\u" + c.code.toString(16).padStart(4, '0')
        else -> if (c < ' ' || c == '\u007f') "\\x" + c.code.toString(16).padStart(2, '0') else null
    }

    /** The elements of an earlier page whose attribute was the address of request [index] */
    private class LinkSource(val pageIndex: Int, val css: String, val attr: String, val count: Int)

    private fun linkSourceOf(plan: Plan, step: RecordedStep, index: Int): LinkSource? {
        val url = step.requests[index].url
        // r98 fix: a class (or the parent's) is required, a bare `tag[attr]` is never used, and at most 3× the
        // recorded videos may match
        val limit = maxOf(1, step.items.size) * 3
        var rejected: Int? = null
        for (j in index - 1 downTo 0) {
            val doc = plan.doc(j) ?: continue
            for ((el, attr) in doc.allElements.flatMap { e -> e.attributes().map { e to it } }) {
                val value = attr.value.trim()
                if (value.isEmpty() || (value != url && resolve(step.requests[j].url, value) != url)) continue
                val classes = stableClasses(el)
                val parentClass = el.parent()?.let { p -> stableClasses(p).firstOrNull()?.let { "${p.tagName()}.$it > " } }
                val tries = buildList {
                    if (classes.isNotEmpty()) add(el.tagName() + "." + classes.first() + "[${attr.key}]")
                    if (parentClass != null) add(parentClass + el.tagName() + "[${attr.key}]")
                }
                for (css in tries) {
                    val all = runCatching { doc.select(css) }.getOrNull() ?: continue
                    if (all.any { it === el } && all.size <= limit) return LinkSource(j, css, attr.key, all.size)
                }
                if (rejected == null) rejected = j
            }
        }
        rejected?.let { j ->
            plan.todos.add("the embed ${shortUrl(url)} is linked from ${shortUrl(step.requests[j].url)}, but no selector with a class picks its links (at most $limit matches): only the recorded embed is followed")
        }
        return null
    }

    /** The quality text built from attributes of each video's link (data-resolution, data-audio, …), when it is */
    private fun qualityFromLink(plan: Plan, videos: List<Map<String, String>>, located: List<VideoLoc?>, link: LinkSource): String? {
        val doc = plan.doc(link.pageIndex) ?: return null
        val elements = doc.select(link.css)
        val samples = ArrayList<Map<String, String>>()
        val targets = ArrayList<String>()
        videos.forEachIndexed { i, v ->
            val loc = located.getOrNull(i) ?: return@forEachIndexed
            val embedUrl = plan.step.requests[loc.index].url
            val el = elements.firstOrNull { resolve(plan.step.requests[link.pageIndex].url, it.attr(link.attr)) == embedUrl } ?: return@forEachIndexed
            samples.add(el.attributes().associate { it.key to it.value } + ("#text" to el.text()))
            targets.add(v["quality"].orEmpty())
        }
        if (samples.size < 2) return null
        val segs = solve(targets, samples, 0, allowConstant = false) ?: return null
        return concat(
            segs.map { seg ->
                when (seg) {
                    is Seg.Lit -> Piece.Lit(seg.text)
                    is Seg.Ref -> Piece.Expr(if (seg.key == "#text") "s(link.text)" else "s(link.attr(${jsStr(seg.key)}))")
                }
            },
        )
    }

    private fun mapJsonVideos(plan: Plan, root: JsonElement, videos: List<Map<String, String>>): JsonList? {
        val arrays = ArrayList<Pair<List<Any>, JsonArray>>()
        arraysIn(root, emptyList(), arrays, 0)
        for ((path, array) in arrays) {
            val pathOfKey = HashMap<String, List<Any>>()
            val maps = array.map { element ->
                val out = ArrayList<Pair<List<Any>, String>>()
                if (element is JsonPrimitive) out.add(emptyList<Any>() to element.content) else leaves(element, emptyList(), out, 0, maxDepth = 4)
                out.associate { (p, value) -> pathKey(p).also { pathOfKey[it] = p } to value }
            }
            val pairs = videos.mapNotNull { v -> maps.firstOrNull { m -> m.values.any { it == v["url"] } }?.let { v to it } }
            if (pairs.isEmpty()) continue
            val url = fieldExpr(plan, "url", pairs, pathOfKey) { p -> "v(it, ${jsPath(p)})" } ?: continue
            val quality = fieldExpr(plan, "quality", pairs, pathOfKey) { p -> "v(it, ${jsPath(p)})" }
            return JsonList(path, listOfNotNull("url" to url, quality?.let { "quality" to it }).toMap(), pairs.size)
        }
        return null
    }

    /** The video's headers; [embedUrl] (r98 fix): inside the embed loop, that address and its origin read `embed` */
    private fun headersObject(plan: Plan, headers: List<Pair<String, String>>, index: Int, embedUrl: String? = null): String {
        if (headers.isEmpty()) return "HEADERS"
        val origin = originOf(plan.rec.baseUrl)
        val embedOrigin = embedUrl?.let { originOf(it) }
        val parts = headers.map { (name, value) ->
            val earlier = (0..index).firstOrNull { plan.step.requests.getOrNull(it)?.url == value && plan.urlExprs[it] != null }
            val expr = when {
                embedUrl != null && value == embedUrl -> "embed"
                embedOrigin != null && value == "$embedOrigin/" -> "origin(embed) + \"/\""
                embedOrigin != null && value == embedOrigin -> "origin(embed)"
                earlier != null -> plan.urlExprs[earlier]!!
                value == "$origin/" -> "root + \"/\""
                else -> jsStr(value)
            }
            "${jsStr(name)}: $expr"
        }
        return "{ ${parts.joinToString(", ")} }"
    }

    /**
     * r104: why no reply of [step] holds [what]: no request recorded at all, or replies that hold nothing readable
     * (a JSON reply whose data is one long encoded string the extension decodes in its own code)
     */
    private fun notHeldReason(step: RecordedStep, what: String): String {
        if (step.dataRequests.isEmpty()) {
            return if (step.requests.isEmpty()) {
                "no request was recorded in this step, so there is nothing to convert from"
            } else {
                "no data request was recorded in this step (only images, bot-check parts or failed requests)"
            }
        }
        val encoded = step.dataRequests.firstNotNullOfOrNull { r ->
            if (!r.isJson) return@firstNotNullOfOrNull null
            val json = runCatching { Json.parseToJsonElement(r.body.orEmpty()) }.getOrNull() ?: return@firstNotNullOfOrNull null
            val out = ArrayList<Pair<List<Any>, String>>()
            leaves(json, emptyList(), out, 0, maxDepth = 6)
            out.firstOrNull { (_, v) -> v.length >= 200 && ENCODED.matches(v) }?.let { (p, _) -> r to p }
        }
        if (encoded != null) {
            return "none of the recorded replies holds $what as text; the JSON reply of ${shortUrl(encoded.first.url)} " +
                "carries one long encoded string at ${pathWords(encoded.second)}, which the extension decodes in its own code"
        }
        return "none of the recorded replies holds $what as text"
    }

    /** [s] as it can appear inside a JSON string: quotes and backslashes escaped, slashes as they are or as `\/` */
    private fun jsonEscaped(s: String): List<String> {
        val plain = JsonPrimitive(s).toString().let { it.substring(1, it.length - 1) }
        return listOf(plain, plain.replace("/", "\\/"))
    }

    // ---------- TODO parts ----------

    private fun todoPart(method: String, reason: String, step: RecordedStep?, last: String, extra: List<String> = emptyList()): PartCode {
        val lines = ArrayList<String>()
        lines.add("// TODO (not converted): ${commentSafe(reason)}")
        if (step != null) {
            val shown = step.requests.filter { !it.image }.take(12)
            lines.add("// Recorded requests of \"${commentSafe(step.name)}\" (${step.requests.size}, ${step.ms} ms), in order:")
            if (shown.isEmpty()) lines.add("//   none")
            shown.forEach { lines.add("//   ${requestLine(it)}") }
            val excerptOf = step.dataRequests.lastOrNull() ?: shown.lastOrNull()
            if (excerptOf != null) excerptOf.excerpt?.let { body ->
                val unpacked = Unpacker.unpackAll(body.take(ExtensionRecorder.BODY_EXCERPT_BYTES)).firstOrNull()
                lines.add("// Reply excerpt of ${commentSafe(shortUrl(excerptOf.url))}:")
                commentSafe(body.take(600)).chunked(110).forEach { lines.add("//   $it") }
                if (unpacked != null) {
                    lines.add("// It holds a packed script; unpacked (require(\"unpacker\").unpackAll), it starts:")
                    commentSafe(unpacked.take(400)).chunked(110).forEach { lines.add("//   $it") }
                }
            }
            if (step.items.isNotEmpty()) {
                lines.add("// The extension returned (${step.items.size}), first:")
                step.items.first().entries.take(6).forEach { (k, v) -> lines.add("//   $k = ${commentSafe(v.take(140))}") }
            }
            step.error?.let { lines.add("// Error: ${commentSafe(it)}") }
        }
        extra.forEach { lines.add("// (derived so far) ${commentSafe(it)}") }
        lines.add(last)
        return PartCode(lines, written = false, explain = "Not converted: ${reason}. The recorded requests and a reply excerpt are in the code.")
    }

    // ---------- small helpers ----------

    private fun leaves(el: JsonElement, path: List<Any>, out: MutableList<Pair<List<Any>, String>>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || out.size > 20_000) return
        when (el) {
            is JsonObject -> el.forEach { (k, v) -> leaves(v, path + k, out, depth + 1, maxDepth) }
            is JsonArray -> el.forEachIndexed { i, v -> leaves(v, path + i, out, depth + 1, maxDepth) }
            is JsonNull -> Unit
            is JsonPrimitive -> out.add(path to el.content)
        }
    }

    private fun arraysIn(el: JsonElement, path: List<Any>, out: MutableList<Pair<List<Any>, JsonArray>>, depth: Int) {
        if (depth > 6 || out.size > 500) return
        when (el) {
            is JsonArray -> {
                out.add(path to el)
                el.take(3).forEachIndexed { i, v -> arraysIn(v, path + i, out, depth + 1) }
            }
            is JsonObject -> el.forEach { (k, v) -> arraysIn(v, path + k, out, depth + 1) }
            else -> Unit
        }
    }

    private fun at(el: JsonElement, path: List<Any>): JsonElement? {
        var cur: JsonElement? = el
        for (p in path) {
            cur = when {
                p is String && cur is JsonObject -> cur[p]
                p is Int && cur is JsonArray -> cur.getOrNull(p)
                else -> null
            }
            if (cur == null) return null
        }
        return cur
    }

    /** The shortest path to a leaf equal to [value] */
    private fun leafPathFor(root: JsonElement, value: String): List<Any>? {
        if (value.isEmpty()) return null
        val out = ArrayList<Pair<List<Any>, String>>()
        leaves(root, emptyList(), out, 0, maxDepth = 10)
        return out.filter { it.second == value }.minByOrNull { it.first.size }?.first
    }

    private fun pathKey(p: List<Any>): String = p.joinToString("\u0001") { if (it is Int) "#$it" else it.toString() }

    private fun jsPath(p: List<Any>): String = p.joinToString(", ", "[", "]") { if (it is Int) it.toString() else jsStr(it.toString()) }

    private fun pathWords(p: List<Any>): String =
        if (p.isEmpty()) "the item" else p.joinToString("") { if (it is Int) "[$it]" else ".$it" }.removePrefix(".")

    /** A JS string literal; r98 fix: U+2028 / U+2029 escaped too (JSON leaves them as they are) */
    private fun jsStr(s: String): String = jsSafe(JsonPrimitive(s).toString())

    /** JSON text made safe as JS source: the two line separators JSON allows raw, escaped */
    private fun jsSafe(json: String): String = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /** The scheme + host (+ port) of [url], or [url] itself when it doesn't parse */
    private fun originOf(url: String): String = urlParts(url)?.origin ?: url.trimEnd('/')

    private fun norm(s: String): String = htmlUnescape(s).replace(WS, " ").trim()

    private val WS = Regex("""\s+""")

    /** r104: base64 / base64url / hex text without spaces (an encoded or encrypted payload) */
    private val ENCODED = Regex("""[A-Za-z0-9+/=_-]+""")

    private fun htmlUnescape(s: String): String =
        if ('&' !in s) s else s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun numberIn(text: String?): Double? = text?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

    private val DATE_FORMATS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "MMM dd, yyyy", "MMMM dd, yyyy", "dd MMM yyyy")

    /** A leaf as a time: epoch seconds or milliseconds, or one of the usual written forms (UTC) */
    private fun parseDate(value: String): Long? {
        val v = value.trim()
        v.toLongOrNull()?.let { n -> return if (n in 100_000_000L..99_999_999_999L) n * 1000 else if (n >= 100_000_000_000L) n else null }
        for (pattern in DATE_FORMATS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(v.take(pattern.length + 6))
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    private fun resolve(base: String, href: String): String? =
        runCatching { base.toHttpUrlOrNull()?.resolve(href.trim())?.toString() }.getOrNull()

    private val ORIGIN = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+""")

    private fun pathOf(url: String): String = url.trim().replace(ORIGIN, "")

    private fun encodeComponent(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun shortUrl(url: String): String = if (url.length <= 90) url else url.take(87) + "…"

    private fun requestLine(r: RecordedRequest): String = commentSafe(
        buildString {
            append(r.method).append(' ').append(shortUrl(r.url))
            append(" → ").append(r.status?.toString() ?: "failed")
            r.contentType?.let { append(' ').append(it.substringBefore(';')) }
            r.body?.let { append(", ").append(if (it.length >= 1024) "${it.length / 1024} KB" else "${it.length} B") }
            if (r.truncated) append(" (cut)")
            r.requestHeaders.filter { it.first.equals("Referer", true) || it.first.equals("Origin", true) || it.first.equals("X-Requested-With", true) }
                .forEach { (k, v) -> append(", ").append(k).append(": ").append(shortUrl(v)) }
            r.requestHeaders.firstOrNull { it.first.equals("Cookie", true) }?.let { append(", cookies sent") }
            r.botCheck?.let { append(" [").append(it).append(" check]") }
            if (r.infrastructure) append(" [bot-check machinery]")
            r.error?.let { append(" (").append(it).append(')') }
        },
    )

    private fun requestWords(r: RecordedRequest): String =
        "${r.method} ${shortUrl(r.url)} (${if (r.isJson) "JSON" else if (r.isHtml) "HTML" else r.contentType?.substringBefore(';') ?: "text"})"

    /** One comment line: no line breaks, no control characters */
    private fun commentSafe(s: String): String = buildString {
        for (c in s) append(if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' || c < ' ') ' ' else c)
    }

    // ---------- packed scripts ----------

    /**
     * Dean Edwards' p.a.c.k.e.r undone the way the JS `unpacker` module (stdlib.js) does it, with a hand-written
     * scanner instead of its regex: Android's regex engine recurses on `(?:\\.|[^\\])*` and a long payload would
     * overflow the stack.
     */
    internal object Unpacker {
        private val MARK = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)""")
        private val WORD = Regex("""\b\w+\b""")

        fun unpackAll(text: String): List<String> {
            val out = ArrayList<String>()
            var from = 0
            while (out.size < 8) {
                val m = MARK.find(text, from) ?: break
                from = m.range.last + 1
                runCatching { unpackAt(text, from) }.getOrNull()?.let { out.add(it) }
            }
            return out
        }

        /** From after the function head: `}('payload',radix,count,'words'.split('|')` */
        private fun unpackAt(text: String, start: Int): String? {
            var i = text.indexOf("}(", start)
            while (i >= 0) {
                var p = i + 2
                while (p < text.length && text[p].isWhitespace()) p++
                if (p < text.length && (text[p] == '\'' || text[p] == '"')) break
                i = text.indexOf("}(", i + 2)
            }
            if (i < 0) return null
            var p = i + 2
            while (p < text.length && text[p].isWhitespace()) p++
            val (payload, afterPayload) = readString(text, p) ?: return null
            p = afterPayload
            fun skip(ch: Char): Boolean {
                while (p < text.length && text[p].isWhitespace()) p++
                if (p >= text.length || text[p] != ch) return false
                p++
                while (p < text.length && text[p].isWhitespace()) p++
                return true
            }
            fun number(): Int? {
                val begin = p
                while (p < text.length && text[p].isDigit()) p++
                return text.substring(begin, p).toIntOrNull()
            }
            if (!skip(',')) return null
            val radix = number() ?: return null
            if (!skip(',')) return null
            val count = number() ?: return null
            if (!skip(',')) return null
            val (wordsText, _) = readString(text, p) ?: return null
            if (radix < 2 || radix > 62 || count > 100_000) return null
            val words = unescape(wordsText).split('|')
            val table = HashMap<String, String>()
            for (n in count - 1 downTo 0) {
                val key = baseDigit(n, radix)
                table[key] = words.getOrNull(n)?.takeIf { it.isNotEmpty() } ?: key
            }
            return WORD.replace(unescape(payload)) { table[it.value] ?: it.value }
        }

        /** A JS string literal at [at] (its quote included): the raw text inside and the index after it */
        private fun readString(text: String, at: Int): Pair<String, Int>? {
            if (at >= text.length) return null
            val quote = text[at]
            if (quote != '\'' && quote != '"') return null
            var i = at + 1
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    sb.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) return sb.toString() to (i + 1)
                sb.append(c)
                i++
            }
            return null
        }

        private fun unescape(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    sb.append(c)
                    i++
                    continue
                }
                val e = s[i + 1]
                when {
                    e == 'u' && i + 5 < s.length && s.substring(i + 2, i + 6).all { it.isLetterOrDigit() } &&
                        s.substring(i + 2, i + 6).toIntOrNull(16) != null -> {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                    }
                    e == 'x' && i + 3 < s.length && s.substring(i + 2, i + 4).toIntOrNull(16) != null -> {
                        sb.append(s.substring(i + 2, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> {
                        sb.append(
                            when (e) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                else -> e
                            },
                        )
                        i += 2
                    }
                }
            }
            return sb.toString()
        }

        private fun baseDigit(n: Int, radix: Int): String {
            val head = if (n < radix) "" else baseDigit(n / radix, radix)
            val d = n % radix
            return head + if (d > 35) (d + 29).toChar().toString() else d.toString(36)
        }
    }
}
