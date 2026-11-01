package eu.kanade.tachiyomi.jssource.lab

import eu.kanade.tachiyomi.util.system.AppLog

/**
 * KMK (r96, Source Lab): writes a source from a template: every `{{placeholder}}` replaced by a value, the parts
 * the site doesn't have dropped (marker to marker), the comments and markers kept, and the template's Explain
 * sidecar turned into the entries Half A re-lines from the markers. A placeholder with no value is an error
 * ("template needs {{x}}"): the Lab never ships `{{` into saved code. No `id` line is ever written here: the
 * source id travels in [GeneratedSource.sourceId] and Half A writes it (CONTRACT-LAB §7.1).
 */
internal object Generator {

    // Both braces escaped: Android's java.util.regex is ICU, which refuses a bare `}` (U_REGEX_RULE_SYNTAX); an
    // unescaped one here made this object's init throw, and every later use `NoClassDefFoundError: …Generator`
    private val PLACEHOLDER = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

    /**
     * @param values placeholder → value; the sidecar's defaults fill what is missing
     * @param drop part ids to leave out (required methods are never dropped: the source wouldn't start)
     * @param notWritten part ids to flag "not written" in Explain on top of the TODO parts
     * @param header comment lines put under the template's first line (what came from where, for an import)
     * @param leading Explain entries placed before the template's own (e.g. "Imported from extension")
     * @param anime the kind when the caller knows it; else from the template's kind and the parts kept
     */
    fun generate(
        template: LabTemplate,
        values: Map<String, String>,
        lab: LabMeta,
        drop: Set<String> = emptySet(),
        note: String? = null,
        suggestedName: String? = null,
        sourceId: Long? = null,
        notWritten: Set<String> = emptySet(),
        header: List<String> = emptyList(),
        leading: List<ExplainEntry> = emptyList(),
        anime: Boolean? = null,
    ): GeneratedSource {
        val raw = Templates.code(template) ?: throw IllegalStateException("Template ${template.id} has no code any more")
        val filled = LinkedHashMap<String, String>()
        filled.putAll(Templates.defaults(template))
        // r100 fix pass: the "Lists and options" placeholders of generic-list v3 have safe values even when an older
        // (imported) sidecar without these defaults is read next to the v3 code
        EXTRAS_FALLBACKS.forEach { (k, v) -> if (k !in filled) filled[k] = v }
        values.forEach { (k, v) -> filled[k] = v }
        // `{{xJson}}` is the JSON twin of `{{x}}`: the same value with the engine's own placeholder names
        for (key in Templates.placeholdersIn(raw)) {
            if (key.endsWith("Json") && key !in filled) {
                filled[key.removeSuffix("Json")]?.let { filled[key] = it.replace("{q}", "{query}") }
            }
        }

        val dropped = drop.filter { it !in Templates.REQUIRED_PARTS && it !in HEADER_PARTS }.toSet()
        if (dropped.size != drop.size) {
            AppLog.add('W', "lab", "Template ${template.id}: kept ${drop - dropped} (a source needs them)")
        }
        var code = dropParts(raw, dropped, template.json)
        code = replacePlaceholders(code, filled, template.json)
        if (header.isNotEmpty()) code = withHeader(code, header, template.json)
        PLACEHOLDER.find(code)?.let { throw IllegalArgumentException("template needs {{${it.groupValues[1]}}}") }

        val partTexts = partTexts(code, template.json)
        val explain = leading + template.explain
            .filter { it.id !in dropped }
            .map { entry ->
                val text = partTexts[entry.id]
                entry.copy(
                    notWritten = entry.notWritten || entry.id in notWritten || (text != null && "TODO" in text),
                )
            }
        val baseUrl = filled["baseUrl"].orEmpty()
        val hasPages = "getPageList" in partTexts || "\"pages\"" in code
        val hasVideos = "getVideoList" in partTexts || "\"videos\"" in code
        val animeHint = anime ?: (template.kind == "anime" || (template.kind != "manga" && hasVideos && !hasPages))
        return GeneratedSource(
            code = code,
            json = template.json,
            animeHint = animeHint,
            suggestedName = suggestedName ?: filled["name"].orEmpty().ifBlank { template.name },
            baseUrl = baseUrl,
            explain = explain,
            lab = lab.copy(templateId = lab.templateId ?: template.id, templateVersion = lab.templateVersion ?: template.version),
            note = note ?: defaultNote(template, lab),
            sourceId = sourceId,
        )
    }

    fun defaultNote(template: LabTemplate, lab: LabMeta): String = when {
        lab.engine == "generic-list" && lab.siteUrl != null ->
            "Built by tapping on ${lab.siteUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')}"
        lab.confidence != null -> "Generated from the ${template.name} template (${lab.confidence} %)"
        else -> "Generated from the ${template.name} template"
    }

    // ---------- parts ----------

    private val EXTRAS_FALLBACKS = mapOf(
        "tabPopularName" to "",
        "tabLatestName" to "",
        "tabsList" to "[]",
        "filtersList" to "[]",
        "settingsList" to "[]",
        // r101 (v4 title page): a pre-v4 explain sidecar paired with v4 code must not fail "template needs {{…}}"
        "detailsCover" to "",
        "detailsCoverAttr" to "",
        "descriptionSelector" to "",
        "tagsSelector" to "",
        "authorSelector" to "",
        "statusSelector" to "",
        // r102 (v5 grouped tags): JSON text list of the title page's Tags groups; "" = none (the template reads "[]")
        "tagGroups" to "",
        // r104 (v7 Suggestions): JSON text list of the title page's sections tapped as Suggestions
        "suggestionGroups" to "[]",
    )

    /** The parts that hold the identity block; dropping them would leave no `extension` */
    private val HEADER_PARTS = setOf("headers", "helpers")

    private fun startId(line: String, json: Boolean): String? =
        (if (json) PartMarkers.JSON_START else PartMarkers.JS_START).find(line)?.groupValues?.get(1)

    private fun isEnd(line: String, json: Boolean): Boolean =
        (if (json) PartMarkers.JSON_END else PartMarkers.JS_END).containsMatchIn(line)

    /**
     * Removes the lines from each dropped part's start marker to its end marker, both included.
     *
     * A part whose text has unbalanced braces/brackets/parentheses can't be dropped without breaking the code
     * around it (the templates once kept the `};` that closes `var extension = {` inside their last part, and
     * dropping that part wrote a source that didn't parse), so that throws with a clear message instead.
     */
    fun dropParts(code: String, drop: Set<String>, json: Boolean): String {
        if (drop.isEmpty()) return code
        val out = ArrayList<String>()
        var skipping = false
        var skippingId: String? = null
        val skipped = StringBuilder()
        var droppedSomething = false
        for (line in code.lines()) {
            if (!skipping) {
                val id = startId(line, json)
                if (id != null && id in drop) {
                    skipping = true
                    skippingId = id
                    skipped.setLength(0)
                    droppedSomething = true
                    continue
                }
                out.add(line)
            } else if (isEnd(line, json)) {
                skipping = false
                if (!isBalanced(skipped.toString())) {
                    throw IllegalStateException(
                        "Template part \"$skippingId\" can't be dropped: its text has unbalanced braces. " +
                            "The `};` that closes `extension` (or any closer) must sit outside the part markers.",
                    )
                }
            } else {
                skipped.append(line).append('\n')
            }
        }
        // Blank lines left in a row by the removal
        val tidy = ArrayList<String>()
        for (line in out) {
            if (line.isBlank() && tidy.lastOrNull()?.isBlank() == true) continue
            tidy.add(line)
        }
        val text = tidy.joinToString("\n")
        return if (json && droppedSomething) fixTrailingCommas(text) else text
    }

    /**
     * Whether `{}`, `[]` and `()` pair up in [text], with string literals, comments and regex literals skipped
     * (a `/` after an operator, a bracket or at the start is taken as a regex). A heuristic, good enough for the
     * templates: every droppable part of every shipped template passes it, `headers` (never dropped) doesn't.
     */
    internal fun isBalanced(text: String): Boolean {
        var depth = 0
        var i = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        var regex = false
        var regexClass = false
        var last = ' ' // the last significant character, to tell a regex literal from a division
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                lineComment -> if (c == '\n') lineComment = false
                blockComment -> if (c == '*' && next == '/') {
                    blockComment = false
                    i++
                }
                quote != null -> when (c) {
                    '\\' -> i++
                    quote -> quote = null
                    '\n' -> if (quote != '`') quote = null
                }
                regex -> when {
                    c == '\\' -> i++
                    regexClass -> if (c == ']') regexClass = false
                    c == '[' -> regexClass = true
                    c == '/' || c == '\n' -> {
                        regex = false
                        last = '/'
                    }
                }
                c == '/' && next == '/' -> {
                    lineComment = true
                    i++
                }
                c == '/' && next == '*' -> {
                    blockComment = true
                    i++
                }
                c == '/' && (last == ' ' || last in REGEX_BEFORE) -> regex = true
                c == '"' || c == '\'' || c == '`' -> {
                    quote = c
                    last = c
                }
                c == '{' || c == '[' || c == '(' -> {
                    depth++
                    last = c
                }
                c == '}' || c == ']' || c == ')' -> {
                    depth--
                    last = c
                    if (depth < 0) return false
                }
                !c.isWhitespace() -> last = c
            }
            i++
        }
        return depth == 0
    }

    /** A `/` after one of these starts a regex literal, not a division */
    private const val REGEX_BEFORE = "(,=:[!&|?{};"

    /** A JSON part removed before a closing brace leaves `…,` on the line above it: that comma goes */
    private fun fixTrailingCommas(text: String): String {
        val lines = text.lines().toMutableList()
        for (i in lines.indices) {
            if (!lines[i].trimEnd().endsWith(",")) continue
            val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: continue
            val head = lines[next].trimStart()
            if (head.startsWith("}") || head.startsWith("]")) lines[i] = lines[i].trimEnd().dropLast(1)
        }
        return lines.joinToString("\n")
    }

    /** id → the part's text (markers excluded), for the TODO check */
    fun partTexts(code: String, json: Boolean): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        for (line in code.lines()) {
            val id = startId(line, json)
            when {
                id != null -> current = id.also { out[it] = StringBuilder() }
                isEnd(line, json) -> current = null
                current != null -> out[current]?.append(line)?.append('\n')
            }
        }
        return out.mapValues { it.value.toString() }
    }

    // ---------- placeholders ----------

    private fun replacePlaceholders(code: String, values: Map<String, String>, json: Boolean): String =
        PLACEHOLDER.replace(code) { match ->
            val value = values[match.groupValues[1]] ?: return@replace match.value
            escape(value, json)
        }

    /** Values land inside string literals (or comments): quotes, backslashes and line breaks can't pass as is */
    private fun escape(value: String, json: Boolean): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n', '\r' -> append(' ')
                '\t' -> append(' ')
                else -> if (json && ch < ' ') append(' ') else append(ch)
            }
        }
    }

    private fun withHeader(code: String, header: List<String>, json: Boolean): String {
        val lines = code.lines()
        val prefix = if (json) "  // " else "// "
        val comment = header.map { prefix + it.replace("\n", " ") }
        return if (lines.isEmpty()) comment.joinToString("\n") else {
            // After the first line (`// @version N`, or `{` for JSON), so the version stays on line 1
            (listOf(lines.first()) + comment + lines.drop(1)).joinToString("\n")
        }
    }
}
