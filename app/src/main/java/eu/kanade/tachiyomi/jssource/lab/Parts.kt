package eu.kanade.tachiyomi.jssource.lab

/**
 * KMK (r96): the parts of a source for the Explain pane. Generated code carries part markers (C§3.6), which are
 * read first; anything not covered by a marker, and every hand-written source, gets one entry per `extension`
 * method found by a regex pass: `getPopular: function`, `getPopular(page) {`, `extension.getPopular = function`.
 * The `//` comment lines right above the method are its text, "no description" when there are none. No marker
 * is ever inserted into a source's code.
 */
internal object PartIndex {

    /** Titles of the well-known methods, in the order the pane lists them */
    private val KNOWN = linkedMapOf(
        "headers" to "Headers",
        "getPopular" to "Popular",
        "getLatest" to "Latest",
        "search" to "Search",
        "getFilters" to "Filters",
        "getCatalogs" to "Catalogs",
        "getCatalog" to "Catalog pages",
        "getDetails" to "Title details",
        "getChapterList" to "Chapters",
        "getPageList" to "Pages",
        "getVideoList" to "Videos",
        "getRelated" to "Related titles",
        "getTags" to "Tag suggestions",
        "getPagePreviews" to "Page previews",
        "getSourcePreferences" to "Settings",
        "helpers" to "Helpers",
    )

    /** Which Explain part a Test Run step exercises (StepId → part id) */
    fun partsOfStep(stepId: String, anime: Boolean): List<String> = when {
        stepId.startsWith("tab:") -> listOf("getCatalog", "getCatalogs")
        stepId == StepId.POPULAR -> listOf("getPopular")
        stepId == StepId.LATEST -> listOf("getLatest")
        stepId == StepId.SEARCH -> listOf("search", "getFilters")
        stepId == StepId.TITLE -> listOf("getDetails")
        stepId == StepId.CHAPTERS -> listOf("getChapterList")
        stepId == StepId.PAGES -> listOf("getPageList")
        stepId == StepId.HOSTERS || stepId == StepId.VIDEO -> if (anime) listOf("getVideoList") else listOf("getPageList", "getVideoList")
        else -> emptyList()
    }

    /**
     * The parts of [code]: marker parts (with [explain] texts when given, matched by id), then the methods no
     * marker covers. Line numbers are 1-based and inclusive. Explain entries whose part is gone keep `lines = null`.
     */
    fun of(code: String, json: Boolean, explain: List<ExplainEntry>?): List<ExplainEntry> {
        val lines = code.lines()
        val markers = markerRanges(lines, json)
        val byId = explain.orEmpty().associateBy { it.id }
        val out = ArrayList<ExplainEntry>()
        val covered = ArrayList<IntRange>()
        markers.forEach { (id, range) ->
            covered.add(range)
            val given = byId[id]
            val text = given?.text ?: commentAfterMarker(lines, range) ?: "no description"
            out.add(
                ExplainEntry(
                    id = id,
                    title = given?.title ?: KNOWN[id] ?: id,
                    text = text,
                    lineFrom = range.first,
                    lineTo = range.last,
                    optional = given?.optional ?: false,
                    ruleIds = given?.ruleIds.orEmpty(),
                    notWritten = given?.notWritten ?: false,
                )
            )
        }
        // Explain entries whose marker disappeared from the code stay listed, without lines
        explain.orEmpty().filter { e -> out.none { it.id == e.id } }.forEach { e ->
            out.add(e.copy(lineFrom = null, lineTo = null))
        }
        if (!json) {
            methods(lines).forEach { (name, range, comment) ->
                if (covered.any { it.first <= range.first && range.first <= it.last }) return@forEach
                place(
                    out,
                    ExplainEntry(
                        id = name,
                        title = KNOWN[name] ?: name,
                        text = comment ?: "no description",
                        lineFrom = range.first,
                        lineTo = range.last,
                        optional = name !in setOf("getPopular", "search", "getChapterList", "getPageList", "getVideoList"),
                    ),
                )
            }
        } else {
            jsonSections(lines).forEach { (name, range) ->
                if (covered.any { it.first <= range.first && range.first <= it.last }) return@forEach
                place(
                    out,
                    ExplainEntry(
                        id = name,
                        title = JSON_TITLES[name] ?: name,
                        text = jsonComment(lines, range) ?: "no description",
                        lineFrom = range.first,
                        lineTo = range.last,
                        optional = name !in setOf("popular", "search", "chapters", "pages", "videos"),
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.lines?.first ?: Int.MAX_VALUE }, { it.id }))
    }

    /**
     * Adds a derived (no-marker) entry. An entry of the same id that already has lines wins; one from the file whose
     * marker is gone (`lines == null`) takes the derived lines and keeps its own text, so the method is still
     * pointed at once the owner deletes the markers.
     */
    private fun place(out: MutableList<ExplainEntry>, derived: ExplainEntry) {
        val at = out.indexOfFirst { it.id == derived.id }
        when {
            at < 0 -> out.add(derived)
            out[at].lines == null -> out[at] = out[at].copy(lineFrom = derived.lineFrom, lineTo = derived.lineTo)
        }
    }

    /** The part ids the markers in [code] define, in file order */
    fun markerIds(code: String, json: Boolean): List<String> = markerRanges(code.lines(), json).map { it.first }

    /**
     * Marker parts in file order. A repeated id (the same marker pasted twice) gets `#2`, `#3`… so the ids stay
     * unique: the Explain pane keys its rows on them.
     */
    private fun markerRanges(lines: List<String>, json: Boolean): List<Pair<String, IntRange>> {
        val out = ArrayList<Pair<String, IntRange>>()
        var openId: String? = null
        var openAt = 0
        lines.forEachIndexed { index, line ->
            val start = if (json) PartMarkers.JSON_START.find(line) else PartMarkers.JS_START.find(line)
            if (start != null) {
                // an unclosed part ends where the next one starts
                openId?.let { out.add(it to (openAt..index)) }
                openId = start.groupValues[1]
                openAt = index + 1
                return@forEachIndexed
            }
            val end = if (json) PartMarkers.JSON_END.find(line) else PartMarkers.JS_END.find(line)
            if (end != null) {
                val id = openId ?: return@forEachIndexed
                if (json && end.groupValues[1] != id) return@forEachIndexed
                out.add(id to (openAt..index + 1))
                openId = null
            }
        }
        openId?.let { out.add(it to (openAt..lines.size)) }
        val seen = HashMap<String, Int>()
        return out.filter { it.second.first <= it.second.last }.map { (id, range) ->
            val n = (seen[id] ?: 0) + 1
            seen[id] = n
            (if (n == 1) id else "$id#$n") to range
        }
    }

    /** The `//` (or `"__comment_<id>"`) line right after a part's start marker */
    private fun commentAfterMarker(lines: List<String>, range: IntRange): String? {
        val text = ArrayList<String>()
        for (i in range.first until range.last.coerceAtMost(lines.size)) {
            val line = lines[i].trim()
            when {
                line.startsWith("//") -> text.add(line.removePrefix("//").trim())
                JSON_COMMENT.find(line) != null -> text.add(JSON_COMMENT.find(line)!!.groupValues[1])
                text.isEmpty() -> continue
                else -> break
            }
        }
        return text.joinToString(" ").ifBlank { null }
    }

    private data class Method(val name: String, val range: IntRange, val comment: String?)

    /** Top-level methods of the `extension` object (or `extension.x = function`), with their comment lines */
    private fun methods(lines: List<String>): List<Method> {
        val starts = ArrayList<Pair<String, Int>>()
        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val match = METHOD_PROPERTY.find(line) ?: METHOD_ASSIGN.find(line) ?: METHOD_SHORTHAND.find(line)
            if (match != null) {
                val name = match.groupValues[1]
                if (name in KNOWN || name in EXTRA_METHODS) starts.add(name to index)
            } else if (HEADERS_PROPERTY.find(line) != null) {
                starts.add("headers" to index)
            }
        }
        return starts.mapIndexed { n, (name, startIndex) ->
            val end = methodEnd(lines, startIndex, starts.getOrNull(n + 1)?.second ?: lines.size)
            val from = commentStart(lines, startIndex)
            val comment = (from until startIndex)
                .map { lines[it].trim() }
                .filter { it.startsWith("//") }
                .joinToString(" ") { it.removePrefix("//").trim() }
                .ifBlank { null }
            Method(name, (from + 1)..end, comment)
        }.distinctBy { it.name }
    }

    /** The line the method's block closes on (brace count from its first `{`), capped at the next method */
    private fun methodEnd(lines: List<String>, start: Int, limit: Int): Int {
        var depth = 0
        var seen = false
        for (i in start until limit.coerceAtMost(lines.size)) {
            val stripped = STRINGS_AND_COMMENTS.replace(lines[i], "")
            for (c in stripped) {
                if (c == '{') {
                    depth++
                    seen = true
                } else if (c == '}') {
                    depth--
                }
            }
            if (seen && depth <= 0) return i + 1
        }
        return limit.coerceAtMost(lines.size).coerceAtLeast(start + 1)
    }

    /** First index of the comment block directly above [index] (blank lines end it) */
    private fun commentStart(lines: List<String>, index: Int): Int {
        var i = index - 1
        while (i >= 0 && lines[i].trim().startsWith("//")) i--
        return i + 1
    }

    /** Top-level keys of a JSON definition that are sections (objects), by indentation two spaces or a tab */
    private fun jsonSections(lines: List<String>): List<Pair<String, IntRange>> {
        val starts = ArrayList<Pair<String, Int>>()
        lines.forEachIndexed { index, line ->
            JSON_SECTION.find(line)?.let { starts.add(it.groupValues[1] to index) }
        }
        return starts.mapIndexed { n, (name, start) ->
            val limit = starts.getOrNull(n + 1)?.second ?: lines.size
            var end = limit
            for (i in start until limit) {
                if (i > start && JSON_SECTION_END.matches(lines[i])) {
                    end = i + 1
                    break
                }
            }
            name to ((start + 1)..end.coerceAtLeast(start + 1))
        }
    }

    private fun jsonComment(lines: List<String>, range: IntRange): String? {
        val above = (range.first - 2).coerceAtLeast(0)
        val candidates = listOf(above, range.first).filter { it in lines.indices }
        return candidates.firstNotNullOfOrNull { JSON_COMMENT.find(lines[it])?.groupValues?.get(1) }
    }

    private val EXTRA_METHODS = setOf("headers", "helpers")
    private val JSON_TITLES = mapOf(
        "popular" to "Popular",
        "latest" to "Latest",
        "search" to "Search",
        "details" to "Title details",
        "chapters" to "Chapters",
        "pages" to "Pages",
        "videos" to "Videos",
        "filters" to "Filters",
        "catalogs" to "Catalogs",
        "preferences" to "Settings",
        "headers" to "Headers",
    )

    private val METHOD_PROPERTY = Regex("""^\s{0,4}([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:async\s+)?function\b""")
    private val METHOD_SHORTHAND = Regex("""^\s{0,4}(?:async\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*\{\s*$""")
    private val METHOD_ASSIGN = Regex("""^\s*extension\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:async\s+)?function\b""")
    private val HEADERS_PROPERTY = Regex("""^\s{0,4}headers\s*:\s*\{|^\s*(?:var|const|let)\s+HEADERS\s*=""")
    private val STRINGS_AND_COMMENTS = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|//.*$""")
    private val JSON_SECTION = Regex("""^(?: {1,4}|\t)"([A-Za-z][A-Za-z0-9_]*)"\s*:\s*[{\[]\s*$""")
    private val JSON_SECTION_END = Regex("""^(?: {1,4}|\t)[}\]]\s*,?\s*$""")
    private val JSON_COMMENT = Regex(""""__comment_[A-Za-z0-9_.-]+"\s*:\s*"((?:\\.|[^"\\])*)"""")
}
