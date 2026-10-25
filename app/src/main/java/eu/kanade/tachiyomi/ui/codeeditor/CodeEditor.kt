package eu.kanade.tachiyomi.ui.codeeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * KMK: A code area for the Code editor: line numbers, JS / JSON colours, no wrapping (scrolls sideways), and new
 * lines keep the indentation of the line above.
 */
@Composable
fun CodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    json: Boolean,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(TextFieldValue(code)) }
    LaunchedEffect(code) {
        if (code != value.text) value = TextFieldValue(code, TextRange(0))
    }
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val colors = remember(dark) { if (dark) SyntaxColors.Dark else SyntaxColors.Light }
    val transformation = remember(json, colors) { SyntaxHighlight(json, colors) }
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val vertical = rememberScrollState()

    Row(
        modifier = modifier
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
                .horizontalScroll(rememberScrollState()),
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
                modifier = Modifier
                    .widthIn(min = 600.dp)
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 200.dp),
            )
        }
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
