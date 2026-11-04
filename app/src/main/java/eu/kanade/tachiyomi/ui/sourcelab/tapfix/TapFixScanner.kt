package eu.kanade.tachiyomi.ui.sourcelab.tapfix

/**
 * KMK (r118): Tap to fix's code reader. Plain Kotlin (no Android), so it can be reasoned about and tested alone.
 *
 * Finds the places in a Source Lab source that pick something from a page:
 * - the first argument of `.select(`, `.selectOne(`, `.selectFirst(`, `.selectAll(`, `.querySelector(`,
 *   `.querySelectorAll(`, `.find(`, `.children(` and `$(` when it is a string literal (single, double or backtick
 *   quotes, escapes kept);
 * - `var|let|const NAME = "…"` literals that are passed to one of those calls, or that carry a generic-list name
 *   (`ITEM`, `TITLE`, `COVER`, … see [VAR_LABELS]) or a `…SELECTOR` / `…_SEL` name;
 * - in a JSON source, the values of `itemSelector`, `selector` and `…Selector` keys.
 *
 * A selector built from pieces (`+`, a template with `${…}`, a list joined) is listed too, but not tap-fixable
 * ([Place.fixable] false: "edit in code"). Comments and regex literals are skipped, so a quote inside them is never
 * taken for a string.
 */
internal object TapFixScanner {

    /** What the tapped element should be (the page script adjusts a tap to it: a link, a picture, a frame…) */
    enum class Kind { Item, Link, Picture, Text, Frame }

    data class Place(
        /** stable within one scan and across a save of this place (the literal's start offset) */
        val id: String,
        /** 1-based */
        val line: Int,
        /** a plain name: "Title link", "Cover picture", or the code line when nothing says what it is */
        val label: String,
        /** where it sits: "var TITLE", "in getChapterList", "popular › fields" */
        val where: String,
        val lineText: String,
        /** the literal's value (escapes decoded); for a place built from pieces, the expression as written */
        val selector: String,
        val quote: Char,
        /** first character of the literal's content (after the opening quote) */
        val start: Int,
        /** the closing quote's index (exclusive end of the content) */
        val end: Int,
        /** the content exactly as in the code (escapes kept) */
        val raw: String,
        val fixable: Boolean,
        /** why it isn't tap-fixable */
        val why: String? = null,
        val kind: Kind,
        /** the code reads only the first match (selectOne / selectFirst / querySelector, NEXT, DESCRIPTION…) */
        val single: Boolean,
        /** the Item selector this one is read inside (generic-list TITLE / COVER inside ITEM; JSON fields) */
        val relativeTo: String? = null,
        /** the Item's name, for the UI ("ITEM", "chapters › itemSelector") */
        val relativeName: String? = null,
        /** a named place (`var NAME` or a JSON key): listed first */
        val named: Boolean,
    )

    /** The generic-list variables (PickHealer.CODE_VARS' names) with their plain names */
    val VAR_LABELS: Map<String, String> = linkedMapOf(
        "ITEM" to "Item (each card)",
        "TITLE" to "Title link",
        "COVER" to "Cover picture",
        "NEXT" to "Next page",
        "CHAPTER" to "Episode / chapter links",
        "CHAPTER_DATE" to "Date",
        "EPISODE_NUMBER" to "Episode number",
        "IMAGE" to "Page images",
        "VIDEO_FRAME" to "Video frame",
        "DETAILS_COVER" to "Cover on the title page",
        "DESCRIPTION" to "Description",
        "TAGS" to "Genres / tags",
        "AUTHOR" to "Author",
        "STATUS" to "Status",
    )

    private val VAR_KINDS: Map<String, Kind> = mapOf(
        "ITEM" to Kind.Item,
        "TITLE" to Kind.Link,
        "COVER" to Kind.Picture,
        "NEXT" to Kind.Link,
        "CHAPTER" to Kind.Link,
        "IMAGE" to Kind.Picture,
        "VIDEO_FRAME" to Kind.Frame,
        "DETAILS_COVER" to Kind.Picture,
    )

    /** Read with one selectOne on the page (PickHealer.SINGLE_LABELS' variables) */
    private val SINGLE_VARS = setOf("NEXT", "DETAILS_COVER", "DESCRIPTION", "AUTHOR", "STATUS", "VIDEO_FRAME", "EPISODE_NUMBER")

    private val CALLS = setOf("select", "selectOne", "selectFirst", "selectAll", "querySelector", "querySelectorAll", "find", "children", "$")
    private val SINGLE_CALLS = setOf("selectOne", "selectFirst", "querySelector")

    // ---------- tokens ----------

    private enum class T { Str, Ident, Punct, Num, Regex }

    private class Tok(
        val type: T,
        val start: Int,
        /** exclusive */
        val end: Int,
        /** identifier / punctuation text; for a string its raw content (between the quotes) */
        val text: String,
        val quote: Char = ' ',
        /** a template literal with `${…}` in it */
        val interp: Boolean = false,
    )

    private val OPS = listOf(
        "===", "!==", ">>>", "...", "**=", "&&=", "||=", "??=",
        "==", "!=", "<=", ">=", "=>", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "<<", ">>", "**",
    )

    private val REGEX_AFTER_WORDS = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await",
    )

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** End (exclusive) of the string starting at [i] and whether it has `${…}`; end = -1 when it never closes */
    private fun skipString(code: String, i: Int): Pair<Int, Boolean> {
        val q = code[i]
        val n = code.length
        var j = i + 1
        var interp = false
        while (j < n) {
            val c = code[j]
            when {
                c == '\\' -> j += 2
                c == q -> return Pair(j + 1, interp)
                q != '`' && c == '\n' -> return Pair(-1, false)
                q == '`' && c == '$' && j + 1 < n && code[j + 1] == '{' -> {
                    interp = true
                    j = skipInterpolation(code, j + 2)
                    if (j < 0) return Pair(-1, false)
                }
                else -> j++
            }
        }
        return Pair(-1, false)
    }

    /** From inside `${`: the index after its closing `}` (nested strings and braces skipped), -1 when it never closes */
    private fun skipInterpolation(code: String, from: Int): Int {
        var depth = 1
        var j = from
        while (j < code.length) {
            val c = code[j]
            when (c) {
                '"', '\'', '`' -> {
                    val end = skipString(code, j).first
                    if (end < 0) return -1
                    j = end
                    continue
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j + 1
                }
            }
            j++
        }
        return -1
    }

    /** End (exclusive) of the regex literal at [i] with its flags, -1 when it isn't one (a line break first) */
    private fun skipRegex(code: String, i: Int): Int {
        var j = i + 1
        var inClass = false
        while (j < code.length) {
            val c = code[j]
            when {
                c == '\\' -> {
                    j += 2
                    continue
                }
                c == '\n' -> return -1
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '/' -> {
                    j++
                    while (j < code.length && code[j].isLetter()) j++
                    return j
                }
            }
            j++
        }
        return -1
    }

    private fun regexAllowed(prev: Tok?): Boolean = when {
        prev == null -> true
        prev.type == T.Punct -> prev.text != ")" && prev.text != "]" && prev.text != "}"
        prev.type == T.Ident -> prev.text in REGEX_AFTER_WORDS
        else -> false
    }

    private fun tokenize(code: String, json: Boolean): List<Tok> {
        val out = ArrayList<Tok>()
        val n = code.length
        var i = 0
        while (i < n) {
            val c = code[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val e = code.indexOf('\n', i)
                    i = if (e < 0) n else e
                }
                c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val e = code.indexOf("*/", i + 2)
                    i = if (e < 0) n else e + 2
                }
                c == '"' || c == '\'' || (c == '`' && !json) -> {
                    val (end, interp) = skipString(code, i)
                    if (end < 0) {
                        // never closed: skip to the end of the line
                        val e = code.indexOf('\n', i)
                        i = if (e < 0) n else e
                    } else {
                        out.add(Tok(T.Str, i, end, code.substring(i + 1, end - 1), c, interp))
                        i = end
                    }
                }
                c == '/' && !json && regexAllowed(out.lastOrNull()) -> {
                    val end = skipRegex(code, i)
                    if (end < 0) {
                        out.add(Tok(T.Punct, i, i + 1, "/"))
                        i++
                    } else {
                        out.add(Tok(T.Regex, i, end, code.substring(i, end)))
                        i = end
                    }
                }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && isIdentPart(code[j])) j++
                    out.add(Tok(T.Ident, i, j, code.substring(i, j)))
                    i = j
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    out.add(Tok(T.Num, i, j, code.substring(i, j)))
                    i = j
                }
                else -> {
                    val op = OPS.firstOrNull { code.startsWith(it, i) } ?: c.toString()
                    out.add(Tok(T.Punct, i, i + op.length, op))
                    i += op.length
                }
            }
        }
        return out
    }

    // ---------- escapes ----------

    /** A JS / JSON string literal's content as a value */
    fun decode(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i + 1 >= raw.length) {
                append(ch)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'n' -> {
                    append('\n')
                    i += 2
                }
                't' -> {
                    append('\t')
                    i += 2
                }
                'r' -> {
                    append('\r')
                    i += 2
                }
                'b', 'f', 'v' -> i += 2
                '0' -> {
                    append('\u0000')
                    i += 2
                }
                'x' -> {
                    val hex = raw.substring(i + 2, minOf(i + 4, raw.length))
                    val v = hex.takeIf { it.length == 2 }?.toIntOrNull(16)
                    if (v != null) {
                        append(v.toChar())
                        i += 4
                    } else {
                        append('x')
                        i += 2
                    }
                }
                'u' -> {
                    if (i + 2 < raw.length && raw[i + 2] == '{') {
                        val close = raw.indexOf('}', i + 3)
                        val v = if (close > 0) raw.substring(i + 3, close).toIntOrNull(16) else null
                        if (v != null && close > 0) {
                            appendCodePoint(v)
                            i = close + 1
                        } else {
                            append('u')
                            i += 2
                        }
                    } else {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        val v = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (v != null) {
                            append(v.toChar())
                            i += 6
                        } else {
                            append('u')
                            i += 2
                        }
                    }
                }
                // a line continuation
                '\n' -> i += 2
                '\r' -> i += if (i + 2 < raw.length && raw[i + 2] == '\n') 3 else 2
                else -> {
                    append(next)
                    i += 2
                }
            }
        }
    }

    private fun StringBuilder.appendCodePoint(cp: Int) {
        if (cp in 0..0xFFFF) {
            append(cp.toChar())
        } else if (cp in 0x10000..0x10FFFF) {
            val v = cp - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    /**
     * [value] written for a literal in [quote]: backslashes and that quote escaped, line breaks and tabs as spaces;
     * in a template literal every `$` is escaped too (so no `${` can start). Valid JSON for `"`.
     */
    fun escapeFor(quote: Char, value: String): String = buildString {
        for (ch in value) {
            when {
                ch == '\\' -> append("\\\\")
                ch == quote -> append('\\').append(ch)
                ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
                quote == '`' && ch == '$' -> append('\\').append('$')
                ch < ' ' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /**
     * [code] with [place]'s literal content replaced by [newSelector] (escaped for its quotes, the quotes kept).
     * Null when the place isn't tap-fixable or the code at its range is no longer what the scan saw.
     */
    fun replace(code: String, place: Place, newSelector: String): String? {
        if (!place.fixable) return null
        if (place.start < 1 || place.end > code.length || place.start > place.end) return null
        if (code[place.start - 1] != place.quote || place.end >= code.length || code[place.end] != place.quote) return null
        if (code.substring(place.start, place.end) != place.raw) return null
        return code.substring(0, place.start) + escapeFor(place.quote, newSelector) + code.substring(place.end)
    }

    // ---------- the scan ----------

    fun scan(code: String, json: Boolean): List<Place> = runCatching {
        if (json) Json(code).scan() else Js(code).scan()
    }.getOrDefault(emptyList())

    /** Line starts, line numbers and line texts of [code] */
    private class Lines(private val code: String) {
        private val starts: IntArray = buildList {
            add(0)
            code.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()

        /** 1-based line of offset [pos] */
        fun lineOf(pos: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo + 1
        }

        fun startOf(line: Int): Int = starts[(line - 1).coerceIn(0, starts.size - 1)]

        fun text(line: Int): String {
            val s = startOf(line)
            val e = code.indexOf('\n', s).let { if (it < 0) code.length else it }
            return code.substring(s, e).trimEnd('\r')
        }
    }

    // ---------- plain names ----------

    private class Cat(val label: String, val kind: Kind, val words: List<String>)

    /** Checked in this order: the first category with a word a hint's word starts with wins */
    private val CATS = listOf(
        Cat("Next page", Kind.Link, listOf("next", "pagination", "pager")),
        Cat("Episodes / chapters", Kind.Link, listOf("episode", "chapter")),
        Cat("Genres / tags", Kind.Text, listOf("genre", "tag", "categor", "keyword")),
        Cat("Description", Kind.Text, listOf("desc", "summary", "synopsis", "plot", "overview")),
        Cat("Date", Kind.Text, listOf("date", "time", "upload", "released")),
        Cat("Author / studio", Kind.Text, listOf("author", "artist", "studio")),
        Cat("Status", Kind.Text, listOf("status")),
        Cat("Episode number", Kind.Text, listOf("number")),
        Cat("Video frame", Kind.Frame, listOf("video", "iframe", "player", "embed", "frame")),
        Cat("Cover / picture", Kind.Picture, listOf("cover", "thumb", "poster", "img", "image", "pic", "photo")),
        Cat("Title", Kind.Text, listOf("title", "name", "heading", "h1", "h2", "h3")),
        Cat("Link", Kind.Link, listOf("link", "url", "href")),
        Cat("Item (each card)", Kind.Item, listOf("item", "card", "entry", "result", "row", "grid", "list")),
    )

    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val NON_WORD = Regex("[^A-Za-z0-9]+")

    private fun words(hint: String): List<String> =
        hint.split(CAMEL).flatMap { it.split(NON_WORD) }.map { it.lowercase() }.filter { it.isNotEmpty() }

    private fun categoryOf(hint: String?): Cat? {
        if (hint.isNullOrBlank()) return null
        val ws = words(hint)
        return CATS.firstOrNull { cat -> ws.any { w -> cat.words.any { w.startsWith(it) } } }
    }

    /** The enclosing method's name as a hint: only names that say what the page is */
    private fun functionCategory(name: String?): Cat? {
        val lower = name?.lowercase() ?: return null
        return when {
            "chapter" in lower || "episode" in lower -> CATS.first { it.label == "Episodes / chapters" }
            "pagelist" in lower || "pages" in lower -> Cat("Page images", Kind.Picture, emptyList())
            "video" in lower -> CATS.first { it.label == "Video frame" }
            else -> null
        }
    }

    private val ASSIGN = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*(?:=(?![=>])|:(?!:))""")

    // ---------- JS ----------

    private class Def(val name: String, val pos: Int, val rhs: List<Tok>, val nameTok: Tok)

    private class Js(private val code: String) {
        private val toks = tokenize(code, json = false)
        private val lines = Lines(code)

        /** A function / method: its name, its parameters' names and its body's range (from `{` to after `}`) */
        private class Head(val name: String, val params: List<String>, val from: Int, val to: Int)

        /** Every function / method with a body, in code order */
        private val heads: List<Head> = buildList {
            val keywords = setOf("if", "for", "while", "switch", "catch", "function", "return", "with")
            // the body after the parameters that start at token [open] (a "(")
            fun body(name: String, open: Int) {
                if (toks.getOrNull(open)?.text != "(") return
                val close = matching(open)
                if (close < 0 || toks.getOrNull(close + 1)?.text != "{") return
                val end = matching(close + 1)
                if (end < 0) return
                // a parameter is the name right after "(" or a "," at the parameters' own depth
                val params = ArrayList<String>()
                var depth = 0
                for (k in open until close) {
                    val p = toks[k]
                    if (p.type == T.Punct) {
                        when (p.text) {
                            "(", "[", "{" -> depth++
                            ")", "]", "}" -> depth--
                        }
                    }
                    val q = toks[k + 1]
                    if (depth == 1 && p.type == T.Punct && (p.text == "(" || p.text == ",") && q.type == T.Ident) params.add(q.text)
                }
                add(Head(name, params, toks[close + 1].start, toks[end].end))
            }
            for (i in toks.indices) {
                val t = toks[i]
                if (t.type != T.Ident) continue
                val next = toks.getOrNull(i + 1)
                val prev = toks.getOrNull(i - 1)
                // name: function (…) {  /  name: async function (…) {
                if (next?.type == T.Punct && next.text == ":") {
                    var j = i + 2
                    if (toks.getOrNull(j)?.text == "async") j++
                    if (toks.getOrNull(j)?.text == "function") {
                        j++
                        if (toks.getOrNull(j)?.type == T.Ident) j++ // a named function expression
                        body(t.text, j)
                    }
                    continue
                }
                // function name(…) {
                if (prev?.type == T.Ident && prev.text == "function" && next?.text == "(") {
                    body(t.text, i + 1)
                    continue
                }
                // var name = function (…) {  /  var name = async function (…) {
                if (next?.type == T.Punct && next.text == "=" && prev?.type == T.Ident && (prev.text == "var" || prev.text == "let" || prev.text == "const")) {
                    var j = i + 2
                    if (toks.getOrNull(j)?.text == "async") j++
                    if (toks.getOrNull(j)?.text == "function") body(t.text, j + 1)
                    continue
                }
                // async name(…) {  /  name(…) {  (object method shorthand)
                if (next?.type == T.Punct && next.text == "(" && t.text !in keywords) {
                    val prevOk = prev == null || (prev.type == T.Ident && prev.text == "async") ||
                        (prev.type == T.Punct && (prev.text == "{" || prev.text == "," || prev.text == "}" || prev.text == ";"))
                    if (prevOk) body(t.text, i + 1)
                }
            }
        }

        /** Index of the token closing the bracket at [open], -1 when none */
        private fun matching(open: Int): Int {
            var depth = 0
            for (j in open until toks.size) {
                val t = toks[j]
                if (t.type != T.Punct) continue
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> {
                        depth--
                        if (depth == 0) return j
                    }
                }
            }
            return -1
        }

        /** The innermost function whose body holds [pos] */
        private fun functionAt(pos: Int): String? =
            heads.filter { pos >= it.from && pos < it.to }.minByOrNull { it.to - it.from }?.name

        private fun newlineBetween(a: Tok, b: Tok): Boolean = code.indexOf('\n', a.end).let { it in a.end until b.start }

        private val CONTINUE_NEXT = setOf("+", "-", "*", "/", ".", "?", ":", "||", "&&", "??", "?.", "===", "!==", "==", "!=", "(", "[")

        /** A declaration's value: the tokens after `=` up to `;` / `,` at depth 0 or a line end that ends it */
        private fun rhsFrom(from: Int): List<Tok> {
            val out = ArrayList<Tok>()
            var depth = 0
            var j = from
            while (j < toks.size) {
                val t = toks[j]
                if (t.type == T.Punct) {
                    when (t.text) {
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> {
                            if (depth == 0) break
                            depth--
                        }
                        ";", "," -> if (depth == 0) break
                    }
                }
                if (depth == 0 && out.isNotEmpty() && newlineBetween(out.last(), t)) {
                    val last = out.last()
                    val continues = (last.type == T.Punct && last.text != ")" && last.text != "]" && last.text != "}") ||
                        (t.type == T.Punct && t.text in CONTINUE_NEXT)
                    if (!continues) break
                }
                out.add(t)
                j++
            }
            return out
        }

        /** Every argument of the call whose `(` is at [open] */
        private fun allArgs(open: Int): List<List<Tok>> {
            val out = ArrayList<List<Tok>>()
            var cur = ArrayList<Tok>()
            var depth = 0
            var j = open + 1
            while (j < toks.size) {
                val t = toks[j]
                if (t.type == T.Punct) {
                    when (t.text) {
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> {
                            if (depth == 0) break
                            depth--
                        }
                        "," -> if (depth == 0) {
                            out.add(cur)
                            cur = ArrayList()
                            j++
                            continue
                        }
                    }
                }
                cur.add(t)
                j++
            }
            if (cur.isNotEmpty()) out.add(cur)
            return out
        }

        private fun headAt(pos: Int): Head? = heads.filter { pos >= it.from && pos < it.to }.minByOrNull { it.to - it.from }

        /** The first argument of the call whose `(` is at [open]: up to `,` or `)` at depth 0 */
        private fun firstArg(open: Int): List<Tok> {
            val out = ArrayList<Tok>()
            var depth = 0
            var j = open + 1
            while (j < toks.size) {
                val t = toks[j]
                if (t.type == T.Punct) {
                    when (t.text) {
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> {
                            if (depth == 0) break
                            depth--
                        }
                        "," -> if (depth == 0) break
                    }
                }
                out.add(t)
                j++
            }
            return out
        }

        private enum class Shape { Literal, Pieces, Other }

        private fun shapeOf(expr: List<Tok>): Shape {
            if (expr.isEmpty()) return Shape.Other
            if (expr.size == 1 && expr[0].type == T.Str) return if (expr[0].interp) Shape.Pieces else Shape.Literal
            // a function / object / arrow is not a selector expression (`.find(function (x) { … "a" … })`)
            val isCode = expr.any {
                (it.type == T.Ident && it.text == "function") || (it.type == T.Punct && (it.text == "=>" || it.text == "{"))
            }
            if (isCode) return Shape.Other
            // pieces: strings joined with `+`, a template with `${…}`, or a list joined / concatenated
            val joined = expr.any {
                (it.type == T.Punct && it.text == "+") || (it.type == T.Str && it.interp) ||
                    (it.type == T.Ident && (it.text == "join" || it.text == "concat"))
            }
            return if (joined && expr.any { it.type == T.Str }) Shape.Pieces else Shape.Other
        }

        /** Not a selector: an address, an unfilled template value */
        private fun notSelector(value: String): Boolean {
            val v = value.trim()
            return v.startsWith("http://") || v.startsWith("https://") || v.startsWith("//") || v.startsWith("{{") ||
                "{page}" in v || "{query}" in v
        }

        private fun exprText(expr: List<Tok>): String =
            code.substring(expr.first().start, expr.last().end).replace(Regex("\\s+"), " ").take(160)

        fun scan(): List<Place> {
            // declarations
            val defs = ArrayList<Def>()
            for (i in 0 until toks.size - 2) {
                val t = toks[i]
                if (t.type != T.Ident || (t.text != "var" && t.text != "let" && t.text != "const")) continue
                val name = toks[i + 1]
                val eq = toks[i + 2]
                if (name.type != T.Ident || eq.type != T.Punct || eq.text != "=") continue
                defs.add(Def(name.text, name.start, rhsFrom(i + 3), name))
            }
            fun defFor(name: String, pos: Int): Def? =
                defs.lastOrNull { it.name == name && it.pos < pos } ?: defs.firstOrNull { it.name == name }

            val uses = HashMap<Def, MutableList<String>>()
            // helper → its parameter index → the select calls it is passed to (`parseCards(html, selector)`)
            val helperUses = HashMap<String, MutableMap<Int, MutableList<String>>>()
            val places = ArrayList<Place>()
            val seen = HashSet<Int>()

            for (i in toks.indices) {
                val t = toks[i]
                if (t.type != T.Ident || t.text !in CALLS) continue
                val open = toks.getOrNull(i + 1) ?: continue
                if (open.type != T.Punct || open.text != "(") continue
                val prev = toks.getOrNull(i - 1)
                val dotted = prev?.type == T.Punct && (prev.text == "." || prev.text == "?.")
                if (t.text == "$") {
                    if (prev?.type == T.Ident) continue // `function $(`
                } else if (!dotted) {
                    continue
                }
                val arg = firstArg(i + 1)
                when (shapeOf(arg)) {
                    Shape.Literal -> {
                        val s = arg[0]
                        if (seen.add(s.start)) callPlace(t, s, fixable = true)?.let { places.add(it) }
                    }
                    Shape.Pieces -> {
                        val s = arg.first { it.type == T.Str }
                        if (seen.add(s.start)) places.add(piecesPlace(t, arg, s))
                    }
                    Shape.Other -> {
                        if (arg.size == 1 && arg[0].type == T.Ident) {
                            val name = arg[0].text
                            val head = headAt(t.start)
                            val index = head?.params?.indexOf(name) ?: -1
                            if (head != null && index >= 0) {
                                helperUses.getOrPut(head.name) { HashMap() }.getOrPut(index) { ArrayList() }.add(t.text)
                            } else {
                                defFor(name, t.start)?.let { d -> uses.getOrPut(d) { ArrayList() }.add(t.text) }
                            }
                        }
                    }
                }
            }

            // literals passed to such a helper (one level): `parseCards(html, ".page-item-detail")`
            if (helperUses.isNotEmpty()) {
                for (i in toks.indices) {
                    val t = toks[i]
                    if (t.type != T.Ident) continue
                    val byIndex = helperUses[t.text] ?: continue
                    if (toks.getOrNull(i + 1)?.text != "(") continue
                    val prev = toks.getOrNull(i - 1)
                    if (prev?.type == T.Ident && (prev.text == "function" || prev.text == "async")) continue
                    val close = matching(i + 1)
                    if (close >= 0 && toks.getOrNull(close + 1)?.text == "{") continue // its own definition
                    val args = allArgs(i + 1)
                    for ((index, calls) in byIndex) {
                        val arg = args.getOrNull(index) ?: continue
                        when (shapeOf(arg)) {
                            Shape.Literal -> {
                                val s = arg[0]
                                if (seen.add(s.start)) {
                                    helperPlace(t, s, calls)?.let { places.add(it) }
                                }
                            }
                            Shape.Pieces -> {
                                val s = arg.first { it.type == T.Str }
                                if (seen.add(s.start)) places.add(piecesPlace(t, arg, s))
                            }
                            Shape.Other -> Unit
                        }
                    }
                }
            }

            // generic-list relations: TITLE / COVER inside ITEM, CHAPTER_DATE inside CHAPTER (when a list of episodes)
            fun literalOf(name: String): String? = defs.firstOrNull { it.name == name }
                ?.rhs?.takeIf { it.size == 1 && it[0].type == T.Str && !it[0].interp }?.let { decode(it[0].text) }
            val item = literalOf("ITEM")?.takeIf { it.isNotBlank() }
            val chapter = literalOf("CHAPTER")?.takeIf { it.isNotBlank() }
            val singleEpisode = defs.firstOrNull { it.name == "SINGLE_EPISODE" }
                ?.rhs?.firstOrNull { it.type == T.Str }?.text == "true"

            for (d in defs) {
                val known = d.name in VAR_LABELS
                val named = known || d.name.endsWith("SELECTOR") || d.name.endsWith("_SEL")
                val used = uses[d]
                if (!named && used == null) continue
                val rhs = d.rhs
                when (shapeOf(rhs)) {
                    Shape.Literal -> {
                        val s = rhs[0]
                        val value = decode(s.text)
                        if (notSelector(value)) continue
                        if (value.isBlank() && !known) continue
                        if (!seen.add(s.start)) continue
                        val line = lines.lineOf(s.start)
                        val cat = if (known) null else categoryOf(d.name) ?: categoryOf(value)
                        val relative = when (d.name) {
                            "TITLE", "COVER" -> item?.let { it to "ITEM" }
                            "CHAPTER_DATE" -> if (!singleEpisode) chapter?.let { it to "CHAPTER" } else null
                            else -> null
                        }
                        places.add(
                            Place(
                                id = (s.start + 1).toString(),
                                line = line,
                                label = VAR_LABELS[d.name] ?: cat?.label ?: "var ${d.name}",
                                where = "var ${d.name}",
                                lineText = lines.text(line),
                                selector = value,
                                quote = s.quote,
                                start = s.start + 1,
                                end = s.end - 1,
                                raw = s.text,
                                fixable = true,
                                kind = VAR_KINDS[d.name] ?: cat?.kind ?: Kind.Text,
                                single = d.name in SINGLE_VARS ||
                                    (d.name !in VAR_LABELS && used != null && used.all { it in SINGLE_CALLS }),
                                relativeTo = relative?.first,
                                relativeName = relative?.second,
                                named = true,
                            ),
                        )
                    }
                    Shape.Pieces -> {
                        val s = rhs.first { it.type == T.Str }
                        if (!seen.add(s.start)) continue
                        val line = lines.lineOf(d.nameTok.start)
                        val cat = if (d.name in VAR_LABELS) null else categoryOf(d.name)
                        places.add(
                            Place(
                                id = (s.start + 1).toString(),
                                line = line,
                                label = VAR_LABELS[d.name] ?: cat?.label ?: "var ${d.name}",
                                where = "var ${d.name}",
                                lineText = lines.text(line),
                                selector = exprText(rhs),
                                quote = s.quote,
                                start = s.start + 1,
                                end = s.end - 1,
                                raw = s.text,
                                fixable = false,
                                why = "Built from pieces: edit it in the code",
                                kind = VAR_KINDS[d.name] ?: cat?.kind ?: Kind.Text,
                                single = d.name in SINGLE_VARS,
                                named = true,
                            ),
                        )
                    }
                    Shape.Other -> Unit
                }
            }
            return places.sortedWith(compareBy<Place>({ !it.named }, { !it.fixable }, { it.line }, { it.start }))
        }

        /** Label and kind of a call's selector from its line: the assigned name / key, the selector, `.attr("src")`, the method */
        private fun guess(call: Tok, selector: String, after: String): Triple<String?, Kind, String?> {
            val line = lines.lineOf(call.start)
            val lineStart = lines.startOf(line)
            val before = code.substring(lineStart, call.start)
            val target = ASSIGN.findAll(before).lastOrNull()?.groupValues?.get(1)
                ?.takeIf { it !in setOf("var", "let", "const", "await", "return") }
            val fn = functionAt(call.start)
            val afterKind = when {
                Regex("""["'](?:data-src|data-lazy-src|data-original|src|srcset)["']""").containsMatchIn(after) -> Kind.Picture
                Regex("""["']href["']""").containsMatchIn(after) -> Kind.Link
                else -> null
            }
            // "links" / "items" say little: the selector's own words ("a[href*='/genre']") come first then
            val targetCat = categoryOf(target)
            val generic = targetCat == null || targetCat.kind == Kind.Item || targetCat.label == "Link"
            val cat = if (generic) categoryOf(selector) ?: targetCat ?: functionCategory(fn) else targetCat
            return Triple(cat?.label, afterKind ?: cat?.kind ?: Kind.Text, fn)
        }

        private fun restOfLine(from: Int): String {
            val e = code.indexOf('\n', from).let { if (it < 0) code.length else it }
            return code.substring(from, e)
        }

        private fun callPlace(call: Tok, s: Tok, fixable: Boolean): Place? {
            val value = decode(s.text)
            if (value.isBlank() || notSelector(value)) return null
            val line = lines.lineOf(s.start)
            val text = lines.text(line)
            val (label, kind, fn) = guess(call, value, restOfLine(s.end))
            return Place(
                id = (s.start + 1).toString(),
                line = line,
                label = label ?: text.trim().take(70),
                where = fn?.let { "in $it" } ?: "line $line",
                lineText = text,
                selector = value,
                quote = s.quote,
                start = s.start + 1,
                end = s.end - 1,
                raw = s.text,
                fixable = fixable,
                kind = kind,
                single = call.text in SINGLE_CALLS,
                named = false,
            )
        }

        /** A literal passed to a helper that hands it to [calls] (select / selectOne …) */
        private fun helperPlace(helper: Tok, s: Tok, calls: List<String>): Place? {
            val value = decode(s.text)
            if (value.isBlank() || notSelector(value)) return null
            val line = lines.lineOf(s.start)
            val text = lines.text(line)
            val (label, kind, fn) = guess(helper, value, restOfLine(s.end))
            return Place(
                id = (s.start + 1).toString(),
                line = line,
                label = label ?: text.trim().take(70),
                where = "passed to ${helper.text}()" + (fn?.let { " in $it" } ?: ""),
                lineText = text,
                selector = value,
                quote = s.quote,
                start = s.start + 1,
                end = s.end - 1,
                raw = s.text,
                fixable = true,
                kind = kind,
                single = calls.all { it in SINGLE_CALLS },
                named = false,
            )
        }

        private fun piecesPlace(call: Tok, arg: List<Tok>, s: Tok): Place {
            val line = lines.lineOf(call.start)
            val text = lines.text(line)
            val expr = exprText(arg)
            val (label, kind, fn) = guess(call, decode(s.text), restOfLine(arg.last().end))
            return Place(
                id = (s.start + 1).toString(),
                line = line,
                label = label ?: text.trim().take(70),
                where = fn?.let { "in $it" } ?: "line $line",
                lineText = text,
                selector = expr,
                quote = s.quote,
                start = s.start + 1,
                end = s.end - 1,
                raw = s.text,
                fixable = false,
                why = "Built from pieces: edit it in the code",
                kind = kind,
                single = call.text in SINGLE_CALLS,
                named = false,
            )
        }
    }

    // ---------- JSON ----------

    private class Json(private val code: String) {
        private val toks = tokenize(code, json = true)
        private val lines = Lines(code)

        /** An open `{` or `[`: the key it is the value of, and the itemSelector found directly in it */
        private class Frame(val key: String?, var item: String? = null, var itemName: String? = null)

        private val SKIP_KEYS = setOf("or", "and", "fields", "all")

        fun scan(): List<Place> {
            val places = ArrayList<Place>()
            val stack = ArrayList<Frame>()
            var pendingKey: String? = null
            var i = 0
            while (i < toks.size) {
                val t = toks[i]
                val next = toks.getOrNull(i + 1)
                when {
                    t.type == T.Punct && (t.text == "{" || t.text == "[") -> {
                        stack.add(Frame(pendingKey))
                        pendingKey = null
                    }
                    t.type == T.Punct && (t.text == "}" || t.text == "]") -> {
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                        pendingKey = null
                    }
                    t.type == T.Str && next?.type == T.Punct && next.text == ":" -> {
                        pendingKey = decode(t.text)
                        i += 2
                        continue
                    }
                    t.type == T.Str -> {
                        val key = pendingKey
                        pendingKey = null
                        if (key != null && (key == "selector" || key.endsWith("Selector"))) {
                            place(t, key, stack)?.let { places.add(it) }
                            if (key == "itemSelector") {
                                stack.lastOrNull()?.let { f ->
                                    f.item = decode(t.text).takeIf { it.isNotBlank() }
                                    f.itemName = (f.key ?: "list") + " › itemSelector"
                                }
                            }
                        }
                    }
                    else -> pendingKey = null
                }
                i++
            }
            return places.sortedWith(compareBy<Place>({ it.line }, { it.start }))
        }

        private fun place(s: Tok, key: String, stack: List<Frame>): Place? {
            val value = decode(s.text)
            // an unfilled template value, or the engine's own capture of a rendered page (not on the site)
            if (value.startsWith("{{") || "#captured" in value) return null
            val line = lines.lineOf(s.start)
            val path = stack.mapNotNull { it.key }.filter { it !in SKIP_KEYS }
            val block = path.firstOrNull()
            val field = path.lastOrNull()?.takeIf { path.size > 1 }
            val itemPlace = key == "itemSelector"
            // a field selector is read inside the nearest list's itemSelector (not the list's own)
            val holder = if (itemPlace) null else stack.lastOrNull { it.item != null }
            val cat = when {
                // a list's entries: by what the list is (chapters → links, pages → pictures, videos → a frame)
                itemPlace -> functionCategory(block)?.let { c ->
                    if (c.kind == Kind.Link) Cat("Episode / chapter links", Kind.Link, emptyList()) else c
                } ?: CATS.first { it.label == "Item (each card)" }
                else -> categoryOf(field) ?: categoryOf(key.removeSuffix("Selector")) ?: categoryOf(value)
            }
            val label = cat?.label ?: lines.text(line).trim().take(70)
            return Place(
                id = (s.start + 1).toString(),
                line = line,
                label = if (block != null && cat != null) "$label · $block" else label,
                where = (path + key).joinToString(" › "),
                lineText = lines.text(line),
                selector = value,
                quote = '"',
                start = s.start + 1,
                end = s.end - 1,
                raw = s.text,
                fixable = true,
                kind = cat?.kind ?: Kind.Text,
                single = !itemPlace && holder == null,
                relativeTo = holder?.item,
                relativeName = holder?.itemName,
                named = true,
            )
        }
    }
}
