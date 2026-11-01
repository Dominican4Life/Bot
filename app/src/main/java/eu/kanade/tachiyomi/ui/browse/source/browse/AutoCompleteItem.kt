package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** KMK: the colours of a tag that's searched for (blue) and one that's left out (red), in the list and the chips */
private val IncludeColor = Color(0xFF3DA5F4)
private val ExcludeColor = Color(0xFFE85D75)

/**
 * KMK: a tag as shown: each word starts with a capital ("big breasts" → "Big Breasts"). Only how it's shown; the
 * source still gets the tag as it gave it.
 */
fun tagLabel(tag: String): String {
    val out = StringBuilder(tag.length)
    var start = true
    tag.forEach { c ->
        out.append(if (start) c.titlecaseChar() else c)
        start = !(c.isLetterOrDigit() || c == '\'' || c == '’')
    }
    return out.toString()
}

/**
 * KMK: 0-9 first, then A-Z, whatever the case. r103: each tag's sort key is worked out once ([TagKey]) instead of
 * lower-casing both tags on every comparison (a 14,000-tag list did that ~400,000 times per sort).
 */
private class TagKey(val tag: String) {
    val group: Int = tag.firstOrNull { it.isLetterOrDigit() }.let { first ->
        when {
            first == null -> 2
            first.isDigit() -> 0
            else -> 1
        }
    }
    val lower: String = tag.lowercase()
}

private val tagKeyOrder = compareBy<TagKey>({ it.group }, { it.lower })

/** KMK r103: the tags without repeats, in [TagKey] order (same result as `distinct().sortedWith(order)`) */
private fun sortTags(values: List<String>): List<String> {
    val seen = HashSet<String>(values.size * 2)
    val keys = ArrayList<TagKey>(values.size)
    for (tag in values) if (seen.add(tag)) keys.add(TagKey(tag))
    keys.sortWith(tagKeyOrder)
    return keys.map { it.tag }
}

/**
 * KMK r103: tag lists kept between redraws and between openings of the filter sheet, by list identity (a few at
 * most). [copyOf]: one immutable copy of a filter's tag list (a JS source's list can be 100,000 tags; it used to be
 * copied on every redraw of the sheet). [sorted]: that copy sorted once (it used to be sorted again each time the
 * sheet opened, and the typing bar sorted its matches on every key).
 */
private object TagLists {
    private const val KEEP = 4

    private class Copy(val source: WeakReference<List<String>>, val print: TagPrint, val copy: ImmutableList<String>)
    private class Sorted(val source: WeakReference<List<String>>, val print: TagPrint, val sorted: List<String>)

    private val copies = ArrayList<Copy>()
    private val sortedLists = ArrayList<Sorted>()

    /** The kept copy of [source] while it is the same list with the same [TagPrint]; otherwise a new copy */
    fun copyOf(source: List<String>): ImmutableList<String> {
        if (source is ImmutableList<String>) return source
        val print = TagPrint.of(source)
        synchronized(copies) {
            copies.removeAll { it.source.get() == null }
            copies.firstOrNull { it.source.get() === source && it.print == print }?.let { return it.copy }
        }
        val copy = source.toImmutableList()
        synchronized(copies) {
            copies.add(0, Copy(WeakReference(source), print, copy))
            while (copies.size > KEEP) copies.removeAt(copies.lastIndex)
        }
        return copy
    }

    /** [values] sorted, if it was sorted before; null otherwise (cheap, for a first frame) */
    fun peek(values: List<String>): List<String>? {
        val print = TagPrint.of(values)
        return synchronized(sortedLists) {
            sortedLists.firstOrNull { it.source.get() === values && it.print == print }?.sorted
        }
    }

    /** [values] sorted ([sortTags]), worked out once per list; call off the main thread */
    fun sorted(values: List<String>): List<String> {
        peek(values)?.let { return it }
        val print = TagPrint.of(values)
        val start = System.nanoTime()
        val sorted = sortTags(values)
        if (values.size >= LOG_FROM) {
            val ms = (System.nanoTime() - start) / 1_000_000
            AppLog.add('D', "BrowseTags", "Tag box: sorted ${values.size} tags in $ms ms (kept until the list changes)")
        }
        synchronized(sortedLists) {
            sortedLists.removeAll { it.source.get() == null }
            sortedLists.add(0, Sorted(WeakReference(values), print, sorted))
            while (sortedLists.size > KEEP) sortedLists.removeAt(sortedLists.lastIndex)
        }
        return sorted
    }

    private const val LOG_FROM = 500
}

/**
 * KMK r103 fix pass: a cheap fingerprint of a tag list (size, first and last tag), so a kept copy / sort is reused only
 * while the list still holds the same tags (a list changed in place to the same size is noticed at its ends)
 */
private data class TagPrint(val size: Int, val first: String?, val last: String?) {
    companion object {
        fun of(values: List<String>): TagPrint = runCatching {
            TagPrint(values.size, values.firstOrNull(), values.lastOrNull())
        }.getOrElse { TagPrint(-1, null, null) }
    }
}

/**
 * KMK r103: a filter's tag list as the tag box takes it: one immutable copy, kept while the filter's list is the same
 * object with the same size (a JS source's list that fills in later changes size, so the box still gets the new
 * tags on the next redraw, as before).
 */
@Composable
fun rememberTagValues(source: List<String>): ImmutableList<String> {
    val print = TagPrint.of(source)
    return remember(IdentityKey(source), print) { TagLists.copyOf(source) }
}

/** KMK r103: a `remember` key that matches only the very same object (a list's equals compares every element) */
private class IdentityKey(val ref: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && other.ref === ref
    override fun hashCode(): Int = System.identityHashCode(ref)
}

@Composable
fun AutoCompleteItem(
    name: String,
    state: ImmutableList<String>,
    hint: String,
    values: ImmutableList<String>,
    skipAutoFillTags: ImmutableList<String>,
    validPrefixes: ImmutableList<String>,
    onChange: (List<String>) -> Unit,
) {
    // KMK: a tag left out is the tag with this mark in front ("-tag"), as typing it did before
    val excludeMark = remember(validPrefixes) { if ("-" in validPrefixes) "-" else validPrefixes.firstOrNull() }
    fun excluded(entry: String) = excludeMark != null && entry.startsWith(excludeMark)
    fun bare(entry: String) = if (excluded(entry)) entry.removePrefix(excludeMark!!).trim() else entry
    Column(
        Modifier.fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    ) {
        AutoCompleteTextField(
            values = values,
            label = name,
            placeholder = hint,
            onValueFilter = { tag ->
                val prefix = validPrefixes.find { tag.startsWith(it) }
                val tagNoPrefix = if (prefix != null) {
                    tag.removePrefix(prefix)
                } else {
                    tag
                }

                Pair({ it.contains(tagNoPrefix.trim(), true) }, prefix)
            },
            onSubmit = { tag ->
                val tagNoPrefix = validPrefixes.find { tag.startsWith(it) }?.let { tag.removePrefix(it).trim() } ?: tag
                if (tagNoPrefix.isNotBlank() && tagNoPrefix !in skipAutoFillTags) {
                    onChange(state.filterNot { bare(it).equals(tagNoPrefix, true) } + tag)
                    true
                } else {
                    false
                }
            },
            // KMK: tap a tag in the list to search for it (blue +), hold it to leave it out (red −); tap again to clear
            markOf = { tag ->
                state.firstOrNull { bare(it) == tag }?.let { if (excluded(it)) TagMark.Exclude else TagMark.Include }
                    ?: TagMark.None
            },
            onPick = { tag, exclude ->
                val current = state.firstOrNull { bare(it) == tag }
                val rest = state.filterNot { bare(it) == tag }
                val wantExclude = exclude && excludeMark != null
                onChange(
                    when {
                        // Tapped again: cleared
                        current != null && excluded(current) == wantExclude -> rest
                        wantExclude -> rest + (excludeMark + tag)
                        else -> rest + tag
                    },
                )
            },
        )
        FlowRow(
            modifier = Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.forEach {
                val color = if (excluded(it)) ExcludeColor else IncludeColor
                InputChip(
                    selected = false,
                    onClick = {
                        onChange(state - it)
                    },
                    label = {
                        Text(
                            text = tagLabel(bare(it)),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = it)
                    },
                    colors = InputChipDefaults.inputChipColors(
                        containerColor = color.copy(alpha = 0.18f),
                        labelColor = color,
                        trailingIconColor = color,
                    ),
                    border = InputChipDefaults.inputChipBorder(
                        enabled = true,
                        selected = false,
                        borderColor = color.copy(alpha = 0.6f),
                    ),
                )
            }
        }
    }
}

enum class TagMark { None, Include, Exclude }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutoCompleteTextField(
    label: String? = null,
    placeholder: String? = null,
    values: ImmutableList<String>,
    onValueFilter: ((String) -> (Pair<(String) -> Boolean, String?>)),
    onSubmit: (String) -> Boolean,
    markOf: (String) -> TagMark = { TagMark.None },
    onPick: ((tag: String, exclude: Boolean) -> Unit)? = null,
) {
    // KMK: the box never types in place. A tap opens the list under it (no keyboard); holding it (or tapping a box
    // without a list) opens a typing bar right above the keyboard, on its own layer, so the filter sheet and the
    // screen behind don't jump about when the keyboard comes up
    var expanded by remember { mutableStateOf(false) }
    var barOpen by remember { mutableStateOf(false) }
    // The box's width and place on screen, so the list and the typing bar line up with it
    var anchorWidth by remember { mutableStateOf(0) }
    var anchorX by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val hasList = values.isNotEmpty()

    // KMK r103: sorted once per list (kept for the next time the sheet opens, so it shows at once then)
    val allValues by produceState(TagLists.peek(values) ?: emptyList(), values) {
        withContext(Dispatchers.Default) {
            this@produceState.value = TagLists.sorted(values)
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { },
    ) {
        Box {
            OutlinedTextField(
                value = TextFieldValue(""),
                onValueChange = { },
                label = if (label != null) {
                    { Text(label) }
                } else {
                    null
                },
                placeholder = if (placeholder != null) {
                    { Text(placeholder) }
                } else {
                    null
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        anchorWidth = it.size.width
                        anchorX = it.positionInWindow().x.roundToInt()
                    },
                singleLine = true,
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded,
                    )
                },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
            )
            // Covers the box: tap = the list (or the typing bar when there's no list), hold = the typing bar
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (hasList) {
                                expanded = !expanded
                            } else {
                                barOpen = true
                            }
                        },
                        onLongClick = {
                            expanded = false
                            barOpen = true
                        },
                    ),
            )
        }

        // KMK: a lazy list, so a list of thousands of tags only builds the rows on screen. The menu measures its
        // content's intrinsic width, which a lazy list can't answer, so the list gets a fixed width and height
        if (allValues.isNotEmpty()) {
            ExposedDropdownMenu(
                modifier = Modifier.exposedDropdownSize(matchAnchorWidth = true),
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                TagList(
                    tags = allValues,
                    width = with(density) { anchorWidth.toDp() }.coerceAtLeast(200.dp),
                    markOf = markOf,
                    onPick = onPick,
                    onSubmitTag = { tag ->
                        if (onSubmit(tag)) expanded = false
                    },
                )
            }
        }
    }

    if (barOpen) {
        TypingBar(
            label = label,
            placeholder = placeholder,
            values = values,
            onValueFilter = onValueFilter,
            onSubmit = onSubmit,
            markOf = markOf,
            onPick = onPick,
            x = with(density) { anchorX.toDp() },
            width = with(density) { anchorWidth.toDp() }.coerceAtLeast(240.dp),
            onClose = { barOpen = false },
        )
    }
}

/** KMK: the scrolling tag list, shared by the dropdown and the typing bar */
@Composable
private fun TagList(
    tags: List<String>,
    width: Dp,
    markOf: (String) -> TagMark,
    onPick: ((tag: String, exclude: Boolean) -> Unit)?,
    onSubmitTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .width(width)
            .height((tags.size.coerceAtMost(6) * 48).dp),
    ) {
        items(tags, key = { it }) { tag ->
            TagRow(
                tag = tag,
                mark = if (onPick != null) markOf(tag) else TagMark.None,
                onClick = {
                    if (onPick != null) onPick(tag, false) else onSubmitTag(tag)
                },
                onLongClick = { onPick?.invoke(tag, true) },
            )
        }
    }
}

/**
 * KMK: the typing bar: the box and its matching tags, right above the keyboard, on a layer of its own over the
 * screen. The screen behind keeps its place (it isn't resized for the keyboard while the bar is open). Back, a tap
 * outside or the keyboard's Send closes it; Send also adds what was typed.
 */
@Composable
private fun TypingBar(
    label: String?,
    placeholder: String?,
    values: ImmutableList<String>,
    onValueFilter: ((String) -> (Pair<(String) -> Boolean, String?>)),
    onSubmit: (String) -> Boolean,
    markOf: (String) -> TagMark,
    onPick: ((tag: String, exclude: Boolean) -> Unit)?,
    x: Dp,
    width: Dp,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // The screen behind isn't resized for the keyboard while the bar is open
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val before = window?.attributes?.softInputMode
        window?.setSoftInputMode(
            (before ?: 0) and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
        onDispose { if (before != null) window?.setSoftInputMode(before) }
    }

    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun close() {
        keyboard?.hide()
        onClose()
    }
    fun send() {
        if (value.text.isNotBlank()) onSubmit(value.text)
        close()
    }
    val matches by produceState(emptyList(), value, values) {
        withContext(Dispatchers.Default) {
            val (filter, prefix) = onValueFilter(value.text)
            // KMK r103: the matches of the list sorted once (same order as sorting the matches on every key)
            val found = TagLists.sorted(values).asSequence().filter(filter)
            this@produceState.value = if (prefix != null && onPick == null) {
                found.map { prefix + it }.toList()
            } else {
                found.toList()
            }
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // A light dim, and the bar's own window follows the keyboard
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.25f)
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { close() },
                ),
        ) {
            val barWidth = width.coerceAtMost(maxWidth - 16.dp)
            val barX = x.coerceIn(8.dp, (maxWidth - barWidth - 8.dp).coerceAtLeast(8.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .imePadding()
                    .padding(start = barX, bottom = 8.dp)
                    .width(barWidth)
                    // Taps on the bar itself don't close it
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { },
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (matches.isNotEmpty()) {
                        TagList(
                            tags = matches,
                            width = barWidth - 16.dp,
                            markOf = markOf,
                            onPick = onPick,
                            onSubmitTag = { tag ->
                                onSubmit(tag)
                                value = TextFieldValue("")
                            },
                        )
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = if (label != null) {
                            { Text(label) }
                        } else {
                            null
                        },
                        placeholder = if (placeholder != null) {
                            { Text(placeholder) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardActions = KeyboardActions { send() },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        trailingIcon = {
                            IconButton(onClick = { close() }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .runOnEnterKeyPressed { send() },
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** KMK: one tag in the list: + and blue when searched for, − and red when left out */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagRow(tag: String, mark: TagMark, onClick: () -> Unit, onLongClick: () -> Unit) {
    val color = when (mark) {
        TagMark.Include -> IncludeColor
        TagMark.Exclude -> ExcludeColor
        TagMark.None -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (mark != TagMark.None) Modifier.background(color.copy(alpha = 0.12f)) else Modifier)
            .padding(horizontal = 12.dp),
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (mark) {
                TagMark.Include -> Icon(Icons.Filled.Add, contentDescription = null, tint = color)
                TagMark.Exclude -> Icon(Icons.Filled.Remove, contentDescription = null, tint = color)
                TagMark.None -> Unit
            }
        }
        Text(
            text = tagLabel(tag),
            color = color,
            fontWeight = if (mark != TagMark.None) FontWeight.Medium else null,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
