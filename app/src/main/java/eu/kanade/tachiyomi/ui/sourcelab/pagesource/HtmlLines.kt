package eu.kanade.tachiyomi.ui.sourcelab.pagesource

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * KMK (r115, mockup XSource): a page's HTML as numbered lines for Page source, and its colours. Minified pages are
 * one long line: a line longer than [WRAP] is broken before its tags (`<`), and a tag-less run every [WRAP] characters.
 * At most [MAX_LINES] lines are kept (the screen says so). The colouring is per line (a comment or a value spanning
 * lines is coloured on its first line only): good enough to read, no parser.
 */
internal object HtmlLines {

    const val WRAP = 220
    const val MAX_LINES = 40_000

    data class Lines(val lines: List<String>, val cut: Boolean)

    fun split(text: String): Lines {
        val out = ArrayList<String>()
        var cut = false
        for (raw in text.lineSequence()) {
            if (out.size >= MAX_LINES) {
                cut = true
                break
            }
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (line.length <= WRAP) {
                out.add(line)
                continue
            }
            var start = 0
            while (start < line.length && out.size < MAX_LINES) {
                if (line.length - start <= WRAP) {
                    out.add(line.substring(start))
                    break
                }
                // break before the last '<' inside the window, else at the window's end
                val window = line.substring(start, start + WRAP)
                val at = window.lastIndexOf('<').takeIf { it > 20 } ?: WRAP
                out.add(line.substring(start, start + at))
                start += at
            }
            if (out.size >= MAX_LINES) cut = true
        }
        return Lines(out, cut)
    }

    /** Lines holding [query] (ignoring case) and how many times it occurs in all */
    fun matches(lines: List<String>, query: String): Pair<List<Int>, Int> {
        if (query.isBlank()) return emptyList<Int>() to 0
        val hits = ArrayList<Int>()
        var total = 0
        lines.forEachIndexed { i, line ->
            var from = 0
            var n = 0
            while (true) {
                val at = line.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                n++
                from = at + query.length
            }
            if (n > 0) {
                hits.add(i)
                total += n
            }
        }
        return hits to total
    }

    class Palette(val tag: Color, val attr: Color, val value: Color, val comment: Color, val text: Color, val hit: Color)

    /** One line coloured: tags, attribute names, values, comments; [query] hits get a background */
    fun highlight(line: String, palette: Palette, query: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                line.startsWith("<!--", i) -> {
                    val end = line.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                    withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i, end)) }
                    i = end
                }
                c == '<' -> {
                    // the tag name (with / or !)
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "/!-:_")) j++
                    withStyle(SpanStyle(color = palette.tag)) { append(line.substring(i, j)) }
                    i = j
                    // attributes until '>'
                    while (i < n && line[i] != '>') {
                        val ch = line[i]
                        if (ch == '"' || ch == '\'') {
                            val close = line.indexOf(ch, i + 1).let { if (it < 0) n else it + 1 }
                            withStyle(SpanStyle(color = palette.value)) { append(line.substring(i, close)) }
                            i = close
                        } else if (ch.isLetter() || ch == '@' || ch == ':') {
                            var k = i
                            while (k < n && (line[k].isLetterOrDigit() || line[k] in "-_:@.")) k++
                            withStyle(SpanStyle(color = palette.attr)) { append(line.substring(i, k)) }
                            i = k
                        } else {
                            withStyle(SpanStyle(color = palette.text)) { append(ch) }
                            i++
                        }
                    }
                    if (i < n) {
                        withStyle(SpanStyle(color = palette.tag)) { append('>') }
                        i++
                    }
                }
                else -> {
                    val next = line.indexOf('<', i).let { if (it < 0) n else it }
                    withStyle(SpanStyle(color = palette.text)) { append(line.substring(i, next)) }
                    i = next
                }
            }
        }
        // search hits over the colours
        if (query.isNotBlank()) {
            var from = 0
            while (true) {
                val at = line.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                addStyle(SpanStyle(background = palette.hit), at, at + query.length)
                from = at + query.length
            }
        }
    }
}
