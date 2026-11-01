package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.jssource.JsMapping
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetMangaBySource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.roundToLong

/**
 * KMK (r96): the Source Lab's Lint: the machine rules of the rules file (front matter, [RulesParser]) checked
 * against a source's code and its last Test Run. Seven fixed check kinds ([LintCheck]); a rule with another
 * `check` is listed as "not checked by this build", never an error. Results go to `js_sources/<key>/lint.json`.
 * Lint runs after a save and after a Test Run and never blocks either. "Apply" only changes the code in the
 * editor (the owner still saves), and only when the rule's `find` occurs exactly once in the finding's part.
 */
internal object Lint {

    private val filesDir: File get() = Injekt.get<Application>().filesDir
    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }

    // ---------- the rules file ----------

    private var cachedText: String? = null
    private var cachedRules: RulesFile? = null

    /** The current rules file (imported, else the asset), parsed once per text */
    @Synchronized
    fun rules(): RulesFile? {
        val store = LabFiles.store(LabFileKind.Rules)
        val text = store.current(RULES_NAME) ?: return null
        cachedRules?.takeIf { cachedText == text }?.let { return it }
        val version = store.versions(RULES_NAME).firstOrNull { it.version == store.currentVersion(RULES_NAME) }
        return runCatching { RulesParser.parse(text, version?.fileName, version?.importedAt) }
            .onFailure { AppLog.add('W', "lab", "The rules file doesn't parse: ${it.message}") }
            .getOrNull()
            ?.also {
                cachedText = text
                cachedRules = it
            }
    }

    fun rulesVersion(): Int? = rules()?.version

    // ---------- lint.json ----------

    private fun file(key: String): File {
        require(JsSourceManager.KEY_PATTERN.matches(key)) { "Not a valid source key: $key" }
        return LabPaths.lint(filesDir, key)
    }

    fun read(key: String): LintResult? = runCatching {
        val f = file(key)
        if (f.exists()) jsJson.decodeFromString(LintResult.serializer(), f.readText()) else null
    }.getOrNull()

    private fun write(key: String, result: LintResult) {
        val f = file(key)
        if (f.parentFile?.isDirectory != true) return
        f.writeText(jsJson.encodeToString(LintResult.serializer(), result))
    }

    /** Hides [ruleId]'s findings for this source until un-ignored; kept in lint.json */
    fun ignore(key: String, ruleId: String, ignored: Boolean) {
        val current = read(key) ?: return
        val ids = if (ignored) (current.ignoredRuleIds + ruleId).distinct() else current.ignoredRuleIds - ruleId
        write(
            key,
            current.copy(
                ignoredRuleIds = ids,
                findings = current.findings.map { it.copy(ignored = it.ruleId in ids) },
            ),
        )
        LabStore.invalidate(key)
    }

    // ---------- running ----------

    /** Everything a check looks at */
    class Subject(
        val info: JsSourceInfo,
        val code: String,
        val lines: List<String>,
        val parts: List<ExplainEntry>,
        val run: TestRun?,
        val tags: Set<String>,
    )

    /** Lints [key] now (code + last run + rules) and writes lint.json; null when the source or the rules are missing */
    suspend fun run(key: String): LintResult? = withContext(Dispatchers.IO) {
        val info = manager.get(key) ?: return@withContext null
        val code = manager.code(key) ?: return@withContext null
        val rules = rules() ?: return@withContext null
        val run = runCatching { TestRuns.latest(key) }.getOrNull()
        val subject = Subject(
            info = info,
            code = code,
            lines = code.lines(),
            parts = PartIndex.of(code, info.json, LabStore.explain(key)?.entries),
            run = run,
            tags = tags(info, code, run),
        )
        val ignored = read(key)?.ignoredRuleIds.orEmpty()
        val findings = ArrayList<LintFinding>()
        val passed = ArrayList<String>()
        val notChecked = ArrayList<String>()
        val toRead = ArrayList<String>()
        var checked = 0
        rules.rules.forEach { rule ->
            if (rule.applies.isNotEmpty() && rule.applies.none { it in subject.tags }) return@forEach
            if (rule.check == "text" || rule.check.isBlank()) {
                toRead.add(rule.id)
                return@forEach
            }
            val kind = LintCheck.of(rule.check)
            if (kind == null) {
                notChecked.add(rule.id)
                return@forEach
            }
            checked++
            val found: List<LintFinding> = try {
                check(kind, rule, subject)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.add('W', "lab", "Rule ${rule.id} failed to run on ${info.name}: ${e.message}")
                emptyList()
            }
            if (found.isEmpty()) {
                passed.add(rule.id)
            } else {
                findings.addAll(found.map { it.copy(ignored = rule.id in ignored) })
            }
        }
        val result = LintResult(
            time = System.currentTimeMillis(),
            rulesVersion = rules.version,
            checked = checked,
            findings = findings,
            passed = passed,
            notChecked = notChecked,
            toRead = toRead.take(3),
            ignoredRuleIds = ignored,
        )
        runCatching { write(key, result) }.onFailure { AppLog.add('W', "lab", "Couldn't write lint.json of $key: ${it.message}") }
        AppLog.add('I', "lab", "Lint of ${info.name}: ${findings.count { !it.ignored }} findings, ${passed.size} passed (rules v${rules.version})")
        LabStore.invalidate(key)
        result
    }

    /** Lints every saved source, one after another; returns key → result */
    suspend fun runAll(): Map<String, LintResult> {
        val out = LinkedHashMap<String, LintResult>()
        manager.savedFlow.value.forEach { info -> run(info.key)?.let { out[info.key] = it } }
        return out
    }

    /** The `applies` tags of a source: manga/anime, js/json, api, madara, cloudflare, apk-port */
    fun tags(info: JsSourceInfo, code: String, run: TestRun?): Set<String> = buildSet {
        add(if (info.anime) "anime" else "manga")
        add(if (info.json) "json" else "js")
        if (API_HINT.containsMatchIn(code)) add("api")
        if (MADARA_HINT.containsMatchIn(code)) add("madara")
        if (run?.steps?.any { s -> s.requests.any { it.viaBrowser } } == true || "bot check" in run?.console.orEmpty().joinToString("\n")) add("cloudflare")
        if (info.customId != null || info.labMeta?.fromExtension != null) add("apk-port")
        if (LabStore.hasVideos(code, info.json)) add("videos")
        add("all")
    }

    private suspend fun check(kind: LintCheck, rule: LintRule, s: Subject): List<LintFinding> = when (kind) {
        LintCheck.RegexInCode -> regexInCode(rule, s)
        LintCheck.SelectorPattern -> selectorPattern(rule, s)
        LintCheck.ResultField -> resultField(rule, s)
        LintCheck.CompareSavedChapterUrls -> compareSavedChapterUrls(rule, s)
        LintCheck.HeaderPresent -> headerPresent(rule, s)
        LintCheck.ResponseStatus -> responseStatus(rule, s)
        LintCheck.RenderOption -> renderOption(rule, s)
    }

    private fun finding(rule: LintRule, found: String, line: Int? = null, s: Subject? = null): LintFinding = LintFinding(
        ruleId = rule.id,
        severity = rule.severity,
        title = rule.title,
        line = line,
        found = found.take(200),
        fix = rule.fix,
        snippet = rule.snippet,
        applicable = rule.apply != null && line != null && s != null && applicable(rule, line, s),
    )

    /** `apply.find` occurs exactly once inside the part that holds [line] (the line alone when no part covers it) */
    private fun applicable(rule: LintRule, line: Int, s: Subject): Boolean {
        val apply = rule.apply ?: return false
        // The finding's line is from an older lint; the code may be shorter now
        if (line < 1 || line > s.lines.size) return false
        val range = partRange(line, s)
        val text = s.lines.slice(range).joinToString("\n")
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(apply.find, from)
            if (at < 0) break
            count++
            from = at + apply.find.length
            if (count > 1) break
        }
        return count == 1
    }

    /** 0-based line indices of the part holding 1-based [line] */
    private fun partRange(line: Int, s: Subject): IntRange {
        val part = s.parts.firstOrNull { p -> p.lines?.contains(line) == true }?.lines
        val last = s.lines.size - 1
        return if (part != null) {
            (part.first - 1).coerceIn(0, last)..(part.last - 1).coerceIn(0, last)
        } else {
            (line - 1).coerceIn(0, last).let { it..it }
        }
    }

    /**
     * The code with [finding]'s fix applied, or null when it isn't applicable any more (the code changed since).
     * Only the part's range is edited; the rest of the file is byte for byte the same.
     */
    fun apply(code: String, finding: LintFinding, rule: LintRule, json: Boolean, explain: List<ExplainEntry>?): String? {
        val apply = rule.apply ?: return null
        val line = finding.line ?: return null
        val lines = code.lines()
        if (line < 1 || line > lines.size) return null
        val s = Subject(JsSourceInfo(key = "x", name = ""), code, lines, PartIndex.of(code, json, explain), null, emptySet())
        if (!applicable(rule, line, s)) return null
        val range = partRange(line, s)
        val before = lines.subList(0, range.first)
        val inside = lines.slice(range).joinToString("\n").replaceFirst(apply.find, apply.replace)
        val after = lines.subList(range.last + 1, lines.size)
        return (before + inside.lines() + after).joinToString("\n")
    }

    // ---------- the seven checks ----------

    private fun regex(rule: LintRule, key: String = "pattern"): Regex? {
        val pattern = rule.params[key]?.takeIf { it.isNotBlank() } ?: return null
        val flags = rule.params["flags"].orEmpty()
        val options = buildSet { if ('i' in flags) add(RegexOption.IGNORE_CASE) }
        return runCatching { Regex(pattern, options) }.getOrNull()
    }

    /** `pattern` per line (optionally only lines also matching `near`, never lines matching `unless`), within `part` */
    private fun regexInCode(rule: LintRule, s: Subject): List<LintFinding> {
        val pattern = regex(rule) ?: return emptyList()
        val unless = regex(rule, "unless")
        val near = regex(rule, "near")
        val part = rule.params["part"]?.let { id -> s.parts.firstOrNull { it.id == id }?.lines }
        val out = ArrayList<LintFinding>()
        s.lines.forEachIndexed { index, line ->
            val number = index + 1
            if (part != null && number !in part) return@forEachIndexed
            if (COMMENT_LINE.matches(line)) return@forEachIndexed
            if (!pattern.containsMatchIn(line)) return@forEachIndexed
            if (unless?.containsMatchIn(line) == true) return@forEachIndexed
            if (near != null && !near.containsMatchIn(line)) return@forEachIndexed
            out.add(finding(rule, "line $number: ${line.trim()}", number, s))
        }
        return out.take(5)
    }

    /** `pattern` over the string literals that look like CSS selectors */
    private fun selectorPattern(rule: LintRule, s: Subject): List<LintFinding> {
        val pattern = regex(rule) ?: return emptyList()
        val out = ArrayList<LintFinding>()
        s.lines.forEachIndexed { index, line ->
            if (COMMENT_LINE.matches(line)) return@forEachIndexed
            STRING_LITERAL.findAll(line).forEach { m ->
                val literal = m.groupValues[2].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[6] }
                if (!looksLikeSelector(literal)) return@forEach
                if (pattern.containsMatchIn(literal)) out.add(finding(rule, "line ${index + 1}: \"$literal\"", index + 1, s))
            }
        }
        return out.take(5)
    }

    private fun looksLikeSelector(text: String): Boolean =
        text.length in 2..200 && !text.startsWith("http") && !text.contains("://") &&
            (text.contains('.') || text.contains('#') || text.contains('[') || text.contains('>')) &&
            !text.contains("\\n") && SELECTOR_SHAPE.matches(text)

    /** Over the last run's items: `kind` = relative-url | same-status | date-zero | server-path | field-missing */
    private fun resultField(rule: LintRule, s: Subject): List<LintFinding> {
        val run = s.run ?: return emptyList()
        val kind = rule.params["kind"] ?: return emptyList()
        val stepFilter = rule.params["step"]
        val steps = run.steps.filter { it.status != StepStatus.Skipped && (stepFilter == null || it.id == stepFilter || it.id.startsWith("$stepFilter:")) }
        val raws = steps.flatMap { step -> step.items.mapNotNull { it.raw } }
        val out = ArrayList<LintFinding>()
        when (kind) {
            "relative-url" -> {
                // Not `url`: guide 08 §4.3 lets it be a path (the app joins it with baseUrl); only the fetched addresses
                val fields = (rule.params["field"] ?: "coverUrl,imageUrl,videoUrl,webUrl,thumbnailUrl").split(',').map { it.trim() }
                raws.forEach { raw ->
                    fields.forEach { field ->
                        val value = raw.str(field) ?: return@forEach
                        if (value.isNotBlank() && !value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("data:")) {
                            out.add(finding(rule, "$field = \"${value.take(80)}\" (from the last Test Run)"))
                        }
                    }
                }
            }
            "same-status" -> {
                val entries = steps.filter { it.id == StepId.POPULAR || it.id == StepId.LATEST || it.id.startsWith("tab:") }
                    .flatMap { step -> step.items.mapNotNull { it.raw } }
                val statuses = entries.mapNotNull { it.str("status")?.takeIf { v -> v.isNotBlank() } }
                if (entries.size >= 5 && statuses.size == entries.size && statuses.distinct().size == 1) {
                    out.add(finding(rule, "every one of the ${entries.size} list entries has status \"${statuses.first()}\""))
                }
            }
            "date-zero" -> {
                val chapters = steps.filter { it.id == StepId.CHAPTERS }.flatMap { step -> step.items.mapNotNull { it.raw } }
                val bad = chapters.mapNotNull { it.str("dateUploaded") }.filter { it.isNotBlank() && JsMapping.date(it) == 0L }
                if (bad.isNotEmpty()) out.add(finding(rule, "dateUploaded \"${bad.first().take(40)}\" reads as 0 (${bad.size} of ${chapters.size})"))
            }
            "server-path" -> {
                val fields = (rule.params["field"] ?: "coverUrl,imageUrl,thumbnailUrl").split(',').map { it.trim() }
                raws.forEach { raw ->
                    fields.forEach { field ->
                        val value = raw.str(field) ?: return@forEach
                        if (SERVER_PATH.containsMatchIn(value)) out.add(finding(rule, "$field = \"${value.take(100)}\""))
                    }
                }
            }
            "field-missing" -> {
                val field = rule.params["field"] ?: return emptyList()
                val missing = raws.count { it.str(field).isNullOrBlank() && it[field] == null }
                if (raws.isNotEmpty() && missing == raws.size) out.add(finding(rule, "none of the ${raws.size} items has `$field`"))
            }
        }
        return out.distinctBy { it.found }.take(5)
    }

    /**
     * The last run's chapter urls against the library's rows of the same title (matched by entry url): a chapter
     * with the same number (to 1/1000, as SyncChaptersWithSource r90) but another url is a finding. Silent under
     * three matching chapters.
     */
    private suspend fun compareSavedChapterUrls(rule: LintRule, s: Subject): List<LintFinding> {
        val run = s.run ?: return emptyList()
        if (s.info.anime) return emptyList()
        val chaptersStep = run.steps.firstOrNull { it.id == StepId.CHAPTERS && it.status != StepStatus.Skipped } ?: return emptyList()
        val entryUrl = run.steps.firstOrNull { it.id == StepId.TITLE }?.items?.firstOrNull()?.url
            ?: run.steps.firstOrNull { it.items.any { i -> i.kind == "entry" } }?.items?.firstOrNull { it.kind == "entry" }?.url
            ?: return emptyList()
        val manga = Injekt.get<GetMangaBySource>().await(s.info.id).firstOrNull { it.url == entryUrl } ?: return emptyList()
        val saved = Injekt.get<GetChaptersByMangaId>().await(manga.id)
        if (saved.isEmpty()) return emptyList()
        val byNumber = saved.associateBy { (it.chapterNumber * 1000).roundToLong() }
        var matched = 0
        val differing = ArrayList<Pair<String, String>>()
        chaptersStep.items.forEach { item ->
            val number = item.raw?.let { (it["number"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() } ?: return@forEach
            val row = byNumber[(number * 1000).roundToLong()] ?: return@forEach
            matched++
            if (row.url != item.url) differing.add(row.url to item.url)
        }
        if (matched < 3 || differing.isEmpty()) return emptyList()
        val (was, now) = differing.first()
        return listOf(
            finding(
                rule,
                "${differing.size} of $matched matching chapters have another address: saved \"${was.take(60)}\", source \"${now.take(60)}\"",
            ),
        )
    }

    /** `on` = videos: every video has headers.Referer or the source does; images: the page image answered 403 without a Referer */
    private fun headerPresent(rule: LintRule, s: Subject): List<LintFinding> {
        val run = s.run ?: return emptyList()
        val header = rule.params["header"] ?: "Referer"
        val sourceHas = s.info.headers.keys.any { it.equals(header, ignoreCase = true) }
        return when (rule.params["on"] ?: "videos") {
            "videos" -> {
                val videos = run.steps.filter { it.id == StepId.HOSTERS }.flatMap { st -> st.items.mapNotNull { it.raw } }
                if (videos.isEmpty() || sourceHas) return emptyList()
                val without = videos.count { v -> (v["headers"] as? JsonObject)?.keys?.none { it.equals(header, ignoreCase = true) } != false }
                if (without == 0) emptyList() else listOf(finding(rule, "$without of ${videos.size} videos carry no $header header, and the source sets none"))
            }
            "images" -> {
                val pages = run.steps.firstOrNull { it.id == StepId.PAGES } ?: return emptyList()
                val head = pages.requests.firstOrNull { it.method == "HEAD" } ?: return emptyList()
                if (head.status == 403 && !sourceHas) listOf(finding(rule, "the first page image answered 403 and the source sends no $header")) else emptyList()
            }
            else -> emptyList()
        }
    }

    /** A reply with `status` in the run's requests, unless the code matches `unless` (e.g. rateLimitMs) */
    private fun responseStatus(rule: LintRule, s: Subject): List<LintFinding> {
        val run = s.run ?: return emptyList()
        val status = rule.params["status"]?.toIntOrNull() ?: return emptyList()
        val unless = regex(rule, "unless")
        if (unless != null && unless.containsMatchIn(s.code)) return emptyList()
        val hit = run.steps.flatMap { it.requests }.firstOrNull { it.status == status } ?: return emptyList()
        return listOf(finding(rule, "${hit.method} ${hit.url.take(80)} answered $status during the last Test Run"))
    }

    /** `host.render(…)` with `option` on a source whose run met a bot check (a browser-page reply) */
    private fun renderOption(rule: LintRule, s: Subject): List<LintFinding> {
        val option = rule.params["option"] ?: "userAgent"
        val run = s.run
        val challenged = run?.steps?.any { st -> st.requests.any { it.viaBrowser } } == true ||
            run?.console.orEmpty().any { "bot check" in it || "through the browser" in it }
        if (!challenged && rule.params["always"] != "true") return emptyList()
        val out = ArrayList<LintFinding>()
        var inRender = false
        var renderLine = 0
        s.lines.forEachIndexed { index, line ->
            if (RENDER_CALL.containsMatchIn(line)) {
                inRender = true
                renderLine = index + 1
            }
            if (inRender && Regex("""\b${Regex.escape(option)}\s*:""").containsMatchIn(line)) {
                out.add(finding(rule, "line ${index + 1}: host.render(…) sets $option on a site that answered a bot check", index + 1, s))
                inRender = false
            }
            if (inRender && index + 1 > renderLine + 12) inRender = false
        }
        return out.take(3)
    }

    private const val RULES_NAME = "rules.md"
    private val COMMENT_LINE = Regex("""^\s*(//|\*|/\*).*""")
    private val STRING_LITERAL = Regex("""("((?:\\.|[^"\\])*)")|('((?:\\.|[^'\\])*)')|(`((?:\\.|[^`\\])*)`)""")
    private val SELECTOR_SHAPE = Regex("""^[A-Za-z0-9_\-.#\[\]=~^$*|'" >+:(),\s]+$""")
    private val SERVER_PATH = Regex("""://[^/]+//|/home/[^/]+/.*wp-content/""")
    private val API_HINT = Regex("""/api/|/wp-json/|application/json|JSON\.parse""")
    private val MADARA_HINT = Regex("""madara|wp-manga|manga_load_more|wp-admin/admin-ajax""", RegexOption.IGNORE_CASE)
    private val RENDER_CALL = Regex("""host\.render\s*\(""")
}
