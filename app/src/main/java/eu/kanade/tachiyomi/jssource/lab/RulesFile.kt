package eu.kanade.tachiyomi.jssource.lab

/**
 * KMK (r96): reads `rules.md`: a YAML front matter between two `---` lines that lists the machine rules, then the
 * guide's markdown. Only a small YAML subset is read (D§2.7 risks table): `version: N`, `rules:` as a list of
 * maps with scalar values, quoted strings, inline `{ k: v, … }` for `apply` / `params` and `[a, b]` lists.
 * Anything else fails with the line number, so a bad import never becomes the current rules file.
 */
internal object RulesParser {

    fun parse(text: String, fileName: String?, importedAt: Long?): RulesFile {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") fail(1, "the file must start with a `---` line (the front matter)")
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: fail(lines.size, "the front matter has no closing `---` line")
        val front = lines.subList(1, end)
        var version: Int? = null
        val rules = ArrayList<LintRule>()
        var i = 0
        while (i < front.size) {
            val raw = front[i]
            val lineNo = i + 2
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }
            if (raw.first().isWhitespace()) fail(lineNo, "unexpected indented line outside a rule")
            val key = line.substringBefore(':').trim()
            val rest = line.substringAfter(':', "").trim()
            when (key) {
                "version" -> version = rest.trim('"', '\'').toIntOrNull() ?: fail(lineNo, "`version` must be a whole number")
                "rules" -> {
                    if (rest.isNotEmpty() && rest != "[]") fail(lineNo, "`rules:` must be followed by a list of `- id: …` items")
                    i++
                    while (i < front.size) {
                        val item = front[i]
                        if (item.isBlank() || item.trimStart().startsWith("#")) {
                            i++
                            continue
                        }
                        if (!item.first().isWhitespace()) break
                        val trimmed = item.trimStart()
                        if (!trimmed.startsWith("- ")) fail(i + 2, "expected a list item starting with `- ` here")
                        val itemIndent = item.length - trimmed.length
                        val fields = LinkedHashMap<String, Any>()
                        val firstLine = i + 2
                        putField(fields, trimmed.removePrefix("- "), i + 2)
                        i++
                        while (i < front.size) {
                            val more = front[i]
                            if (more.isBlank() || more.trimStart().startsWith("#")) {
                                i++
                                continue
                            }
                            val indent = more.length - more.trimStart().length
                            if (indent <= itemIndent) break
                            putField(fields, more.trim(), i + 2)
                            i++
                        }
                        rules.add(toRule(fields, firstLine))
                    }
                    continue
                }
                else -> fail(lineNo, "unknown top-level key `$key` (only `version` and `rules` are read)")
            }
            i++
        }
        if (version == null) fail(2, "the front matter has no `version: N` line")
        val body = lines.drop(end + 1).joinToString("\n")
        return RulesFile(
            version = version,
            rules = rules,
            body = body,
            sections = sectionIndex(body.lines()),
            fileName = fileName,
            importedAt = importedAt,
        )
    }

    /** `key: value` of one rule field; the value may be a scalar, an inline map or an inline list */
    private fun putField(into: MutableMap<String, Any>, line: String, lineNo: Int) {
        val colon = line.indexOf(':')
        if (colon <= 0) fail(lineNo, "expected `key: value` here")
        val key = line.substring(0, colon).trim()
        if (!KEY.matches(key)) fail(lineNo, "`$key` isn't a valid key")
        val rest = line.substring(colon + 1).trim()
        into[key] = when {
            rest.startsWith("{") -> inlineMap(rest, lineNo)
            rest.startsWith("[") -> inlineList(rest, lineNo)
            else -> scalar(rest, lineNo)
        }
    }

    private fun toRule(fields: Map<String, Any>, lineNo: Int): LintRule {
        fun str(key: String): String? = (fields[key] as? String)?.takeIf { it.isNotEmpty() }
        val id = str("id") ?: fail(lineNo, "a rule needs an `id`")
        if (!ID.matches(id)) fail(lineNo, "rule id `$id` may only have letters, digits and dashes")
        val severity = when (str("severity")?.lowercase()) {
            "error" -> LintSeverity.Error
            "warn", "warning" -> LintSeverity.Warn
            "info", null -> LintSeverity.Info
            else -> fail(lineNo, "rule `$id`: severity must be error, warn or info")
        }
        @Suppress("UNCHECKED_CAST")
        val apply = (fields["apply"] as? Map<String, String>)?.let { map ->
            val find = map["find"] ?: fail(lineNo, "rule `$id`: `apply` needs `find`")
            LintApply(find = find, replace = map["replace"] ?: fail(lineNo, "rule `$id`: `apply` needs `replace`"))
        }
        @Suppress("UNCHECKED_CAST")
        val applies: List<String> = when (val value = fields["applies"]) {
            null -> emptyList()
            is List<*> -> value.map { it.toString() }
            is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> fail(lineNo, "rule `$id`: `applies` must be a list")
        }
        @Suppress("UNCHECKED_CAST")
        val params: Map<String, String> = when (val value = fields["params"]) {
            null -> emptyMap()
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value.toString() }
            else -> fail(lineNo, "rule `$id`: `params` must be an inline map `{ k: v }`")
        }
        return LintRule(
            id = id,
            severity = severity,
            check = str("check") ?: "text",
            section = str("section").orEmpty(),
            title = str("title") ?: id,
            fix = str("fix").orEmpty(),
            snippet = str("snippet"),
            apply = apply,
            applies = applies,
            params = params,
        )
    }

    private fun scalar(text: String, lineNo: Int): String {
        if (text.isEmpty()) return ""
        if (text.first() == '"' || text.first() == '\'') {
            val (value, next) = readQuoted(text, 0, lineNo)
            val tail = text.substring(next).trim()
            if (tail.isNotEmpty() && !tail.startsWith("#")) fail(lineNo, "unexpected text after the closing quote")
            return value
        }
        return text
    }

    /** Reads a quoted string starting at [from]; returns the value and the index after the closing quote */
    private fun readQuoted(text: String, from: Int, lineNo: Int): Pair<String, Int> {
        val quote = text[from]
        val out = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '"' && c == '\\' && i + 1 < text.length -> {
                    when (val n = text[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(n)
                    }
                    i += 2
                }
                quote == '\'' && c == '\'' && i + 1 < text.length && text[i + 1] == '\'' -> {
                    out.append('\'')
                    i += 2
                }
                c == quote -> return out.toString() to i + 1
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        fail(lineNo, "a quoted value isn't closed")
    }

    /** `{ k: v, k2: "v, 2" }` */
    private fun inlineMap(text: String, lineNo: Int): Map<String, String> {
        if (!text.endsWith("}")) fail(lineNo, "an inline map must end with `}` on the same line")
        val out = LinkedHashMap<String, String>()
        splitInline(text.substring(1, text.length - 1), lineNo).forEach { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) fail(lineNo, "expected `key: value` inside `{ }`")
            val key = part.substring(0, colon).trim()
            if (!KEY.matches(key)) fail(lineNo, "`$key` isn't a valid key")
            out[key] = scalar(part.substring(colon + 1).trim(), lineNo)
        }
        return out
    }

    /** `[a, b, "c, d"]` */
    private fun inlineList(text: String, lineNo: Int): List<String> {
        if (!text.endsWith("]")) fail(lineNo, "an inline list must end with `]` on the same line")
        return splitInline(text.substring(1, text.length - 1), lineNo).map { scalar(it.trim(), lineNo) }
    }

    /** Splits on commas outside quotes */
    private fun splitInline(text: String, lineNo: Int): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> {
                    val (_, next) = readQuoted(text, i, lineNo)
                    current.append(text, i, next)
                    i = next
                }
                c == ',' -> {
                    parts.add(current.toString())
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Headings numbered like `### 8.2 Addresses` → "8.2" to the range of body lines (0-based) from the heading to
     * just before the next heading of the same or a higher level.
     */
    fun sectionIndex(bodyLines: List<String>): Map<String, IntRange> {
        val headings = bodyLines.mapIndexedNotNull { index, line ->
            HEADING.find(line)?.let { Triple(index, it.groupValues[1].length, it.groupValues[2]) }
        }
        val out = LinkedHashMap<String, IntRange>()
        headings.forEachIndexed { n, (start, level, number) ->
            val endExclusive = headings.drop(n + 1).firstOrNull { it.second <= level }?.first ?: bodyLines.size
            if (number !in out) out[number] = start until endExclusive
        }
        return out
    }

    /** The markdown of [section] ("8.2"), or null when the body has no such heading */
    fun sectionText(rules: RulesFile, section: String): String? {
        val range = rules.sections[section] ?: return null
        return rules.body.lines().slice(range).joinToString("\n")
    }

    private fun fail(line: Int, message: String): Nothing = throw IllegalArgumentException("line $line: $message")

    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_-]*""")
    private val ID = Regex("""[a-z0-9][a-z0-9-]*""")
    private val HEADING = Regex("""^(#{1,6})\s+(\d+(?:\.\d+)*)\.?\s+\S""")
}
