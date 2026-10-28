package eu.kanade.tachiyomi.ui.codeeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * KMK: A code area for the Code editor: line numbers, JS / JSON colours, no wrapping (scrolls sideways), and new
 * lines keep the indentation of the line above.
 *
 * [findOpen] shows a find bar above the code, like a browser's: every match is marked in yellow and the current one
 * in orange, with "3 of 17", ▲ / ▼ (or Enter) to move between them and Aa for exact case. The code scrolls to the
 * current match and the cursor goes there. Ctrl+F opens it ([onOpenFind]), ✕ or Back closes it ([onCloseFind]).
 */
@Composable
fun CodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    json: Boolean,
    modifier: Modifier = Modifier,
    findOpen: Boolean = false,
    onOpenFind: () -> Unit = {},
    onCloseFind: () -> Unit = {},
) {
    var value by remember { mutableStateOf(TextFieldValue(code)) }
    LaunchedEffect(code) {
        if (code != value.text) value = TextFieldValue(code, TextRange(0))
    }
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val colors = remember(dark) { if (dark) SyntaxColors.Dark else SyntaxColors.Light }
    val syntax = remember(json, colors) { SyntaxHighlight(json, colors) }
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val density = LocalDensity.current

    // Find
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    val matches = remember(value.text, query, matchCase, findOpen) {
        if (findOpen) findAll(value.text, query, matchCase) else emptyList()
    }
    if (current >= matches.size) current = 0
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Scrolls only when asked (a search or ▲ / ▼), not while the code is being typed in
    var revealTick by remember { mutableIntStateOf(0) }
    val transformation = remember(syntax, matches, current, dark) {
        FindHighlight(syntax, matches, current.takeIf { matches.isNotEmpty() }, dark)
    }
    // Brings the current match into view and puts the cursor on it
    fun reveal(index: Int) {
        val range = matches.getOrNull(index) ?: return
        current = index
        value = value.copy(selection = TextRange(range.first, range.last + 1))
        revealTick++
    }
    LaunchedEffect(revealTick) {
        if (revealTick == 0) return@LaunchedEffect
        val range = matches.getOrNull(current) ?: return@LaunchedEffect
        val box = layout?.takeIf { range.first < it.layoutInput.text.length }?.getBoundingBox(range.first)
            ?: return@LaunchedEffect
        val top = with(density) { 8.dp.toPx() }
        val viewport = vertical.viewportSize.takeIf { it > 0 } ?: return@LaunchedEffect
        val targetY = (top + box.top - viewport / 3f).toInt().coerceIn(0, vertical.maxValue)
        vertical.animateScrollTo(targetY)
        val start = with(density) { 8.dp.toPx() }
        val width = horizontal.viewportSize
        val left = start + box.left
        if (left < horizontal.value || left > horizontal.value + width - with(density) { 48.dp.toPx() }) {
            horizontal.animateScrollTo((left - width / 4f).toInt().coerceIn(0, horizontal.maxValue))
        }
    }
    BackHandler(findOpen) { onCloseFind() }

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.F) {
                onOpenFind()
                true
            } else {
                false
            }
        },
    ) {
        if (findOpen) {
            FindBar(
                query = query,
                onQueryChange = {
                    query = it
                    current = 0
                    val found = findAll(value.text, it, matchCase)
                    found.firstOrNull()?.let { range ->
                        value = value.copy(selection = TextRange(range.first, range.last + 1))
                        revealTick++
                    }
                },
                matchCase = matchCase,
                onMatchCaseChange = {
                    matchCase = it
                    current = 0
                    revealTick++
                },
                count = matches.size,
                current = current,
                onPrevious = { if (matches.isNotEmpty()) reveal((current - 1 + matches.size) % matches.size) },
                onNext = { if (matches.isNotEmpty()) reveal((current + 1) % matches.size) },
                onClose = onCloseFind,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .verticalScroll(vertical),
        ) {
            Text(
                text = remember(lines) { (1..lines).joinToString("\n") },
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), textAlign = TextAlign.End),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 200.dp)
                    .widthIn(min = 28.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontal),
                contentAlignment = Alignment.TopStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { new ->
                        val adjusted = keepIndent(value, new)
                        value = adjusted
                        if (adjusted.text != code) onCodeChange(adjusted.text)
                    },
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .widthIn(min = 600.dp)
                        .fillMaxSize()
                        .padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 200.dp),
                )
            }
        }
    }
}

/** KMK: the find bar: the words to find, how many there are, previous / next, exact case, close */
@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCase: Boolean,
    onMatchCaseChange: (Boolean) -> Unit,
    count: Int,
    current: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Find in code") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Text(
                        text = if (count == 0) "0 of 0" else "${current + 1} of $count",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (count == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        if (event.isShiftPressed) onPrevious() else onNext()
                        true
                    } else {
                        false
                    }
                },
        )
        // Aa: exact upper / lower case
        TextButton(onClick = { onMatchCaseChange(!matchCase) }) {
            Text(
                text = "Aa",
                fontWeight = if (matchCase) FontWeight.Bold else FontWeight.Normal,
                color = if (matchCase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (matchCase) TextDecoration.Underline else null,
            )
        }
        IconButton(onClick = onPrevious, enabled = count > 0) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous")
        }
        IconButton(onClick = onNext, enabled = count > 0) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close")
        }
    }
}

/** Where [query] is in [text] (at most [MAX_MATCHES]), ignoring case unless [matchCase] */
private fun findAll(text: String, query: String, matchCase: Boolean): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (out.size < MAX_MATCHES) {
        val at = text.indexOf(query, from, ignoreCase = !matchCase)
        if (at < 0) break
        out += at until at + query.length
        from = at + query.length
    }
    return out
}

private const val MAX_MATCHES = 10_000

/** The code colours, plus a yellow mark on every match and an orange one on the current match */
private class FindHighlight(
    private val syntax: SyntaxHighlight,
    private val matches: List<IntRange>,
    private val current: Int?,
    private val dark: Boolean,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val coloured = syntax.filter(text).text
        if (matches.isEmpty()) return TransformedText(coloured, OffsetMapping.Identity)
        val length = coloured.length
        val marked = buildAnnotatedString {
            append(coloured)
            val other = SpanStyle(background = if (dark) Color(0x66C9A100) else Color(0x80FFE14D))
            val now = SpanStyle(
                background = if (dark) Color(0xCCE07B00) else Color(0xFFFF9632),
                color = Color.Black,
            )
            matches.forEachIndexed { index, range ->
                if (range.last < length) addStyle(if (index == current) now else other, range.first, range.last + 1)
            }
        }
        return TransformedText(marked, OffsetMapping.Identity)
    }
}

/** A typed new line starts with the indentation of the line it came from (plus a step after { [ or ( ) */
private fun keepIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val cursor = new.selection.start
    if (!new.selection.collapsed || new.text.length != old.text.length + 1 || cursor < 1 || new.text[cursor - 1] != '\n') {
        return new
    }
    val lineStart = new.text.lastIndexOf('\n', cursor - 2) + 1
    val previous = new.text.substring(lineStart, cursor - 1)
    var indent = previous.takeWhile { it == ' ' || it == '\t' }
    if (previous.trimEnd().lastOrNull() in listOf('{', '[', '(')) indent += "  "
    if (indent.isEmpty()) return new
    val text = new.text.substring(0, cursor) + indent + new.text.substring(cursor)
    return new.copy(text = text, selection = TextRange(cursor + indent.length))
}

private fun Color.luminanceIsDark(): Boolean = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

private data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val key: Color,
    val number: Color,
    val comment: Color,
) {
    companion object {
        val Dark = SyntaxColors(
            keyword = Color(0xFFC792EA),
            string = Color(0xFFC3E88D),
            key = Color(0xFF82AAFF),
            number = Color(0xFFF78C6C),
            comment = Color(0xFF7F8C98),
        )
        val Light = SyntaxColors(
            keyword = Color(0xFF7B30D0),
            string = Color(0xFF2E7D32),
            key = Color(0xFF1565C0),
            number = Color(0xFFD84315),
            comment = Color(0xFF808080),
        )
    }
}

private class SyntaxHighlight(private val json: Boolean, private val colors: SyntaxColors) : VisualTransformation {

    private var lastText: String? = null
    private var lastResult: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val annotated = if (raw == lastText) {
            lastResult!!
        } else {
            highlight(raw).also {
                lastText = raw
                lastResult = it
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }

    private fun highlight(text: String): AnnotatedString {
        // Very long files are left plain so typing stays quick
        if (text.length > 150_000) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            TOKENS.findAll(text).forEach { match ->
                val value = match.value
                val style = when {
                    value.startsWith("//") || value.startsWith("/*") -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                    value.startsWith("\"") || value.startsWith("'") || value.startsWith("`") -> {
                        var next = match.range.last + 1
                        while (next < text.length && text[next].isWhitespace()) next++
                        if (next < text.length && text[next] == ':' && (json || value.startsWith("\""))) {
                            SpanStyle(color = colors.key)
                        } else {
                            SpanStyle(color = colors.string)
                        }
                    }
                    value.first().isDigit() -> SpanStyle(color = colors.number)
                    !json || value in JSON_WORDS -> SpanStyle(color = colors.keyword)
                    else -> null
                }
                if (style != null) addStyle(style, match.range.first, match.range.last + 1)
            }
        }
    }

    private companion object {
        val JSON_WORDS = setOf("true", "false", "null")
        val TOKENS = Regex(
            """//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`|""" +
                """\b\d+(?:\.\d+)?\b|\b(?:var|let|const|function|async|await|return|if|else|for|while|do|of|in|new|try|catch|""" +
                """finally|throw|true|false|null|undefined|this|typeof|instanceof|break|continue|switch|case|default|class|extends)\b""",
        )
    }
}
