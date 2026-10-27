package eu.kanade.tachiyomi.ui.browse.source.browse

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.util.runOnEnterKeyPressed

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

/** KMK: 0-9 first, then A-Z, whatever the case */
private val tagOrder = compareBy<String>(
    { tag ->
        val first = tag.firstOrNull { it.isLetterOrDigit() }
        when {
            first == null -> 2
            first.isDigit() -> 0
            else -> 1
        }
    },
    { it.lowercase() },
)

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
    var expanded by remember { mutableStateOf(false) }
    // KMK: the box's width, so the list below it can have a fixed size (see the menu)
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focusManager = LocalFocusManager.current
    // KMK: a box with a list: a tap opens or closes the list without the keyboard, holding the box brings the keyboard
    // up to type and narrow the list down. A box without a list types right away
    val hasList = values.isNotEmpty()
    var typing by remember(hasList) { mutableStateOf(!hasList) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // A tap on the box while the list is open also reaches the list as a "tap outside" (closing it). These times tell
    // the two apart in either order, so one tap on the box closes the list once
    var listClosedAt by remember { mutableStateOf(0L) }
    var boxTappedAt by remember { mutableStateOf(0L) }
    fun now() = SystemClock.uptimeMillis()
    LaunchedEffect(typing) {
        if (typing && hasList) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    fun close() {
        focusManager.clearFocus()
        keyboard?.hide()
        expanded = false
        typing = !hasList
    }
    fun submit() {
        if (onSubmit(value.text)) {
            value = TextFieldValue("")
            if (!hasList) {
                focusManager.clearFocus()
                expanded = false
            }
        }
    }
    BackHandler(expanded) { close() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            // KMK: only reached while typing (otherwise the cover over the box takes the taps): keep the list open
            boxTappedAt = now()
            if (open || typing) expanded = true
        },
    ) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    expanded = true // todo remove if focus bug is fixed
                },
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
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .focusRequester(focusRequester)
                    .fillMaxWidth()
                    .onSizeChanged { anchorWidth = it.width }
                    .runOnEnterKeyPressed { submit() },
                singleLine = true,
                // KMK: no keyboard until the box is held (see typing)
                readOnly = !typing,
                keyboardActions = KeyboardActions { submit() },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded,
                    )
                },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
            )
            if (hasList && !typing) {
                // KMK: covers the box while it isn't typing: tap = open / close the list, hold = type
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                boxTappedAt = now()
                                if (expanded) {
                                    expanded = false
                                } else if (now() - listClosedAt > 500) {
                                    // (not the tap that just closed it as a "tap outside")
                                    expanded = true
                                }
                            },
                            onLongClick = {
                                boxTappedAt = now()
                                expanded = true
                                typing = true
                            },
                        ),
                )
            }
        }

        val filteredValues by produceState(emptyList(), value, values) {
            withContext(Dispatchers.Default) {
                val (filter, prefix) = onValueFilter(value.text)
                // KMK: every match, 0-9 then A-Z: the list scrolls, so tapping the empty box shows the whole tag list
                // and typing narrows it down. With tap / hold picking, the rows are the bare tags
                val matches = values.asSequence().filter(filter).distinct().sortedWith(tagOrder)
                this@produceState.value = if (prefix != null && onPick == null) {
                    matches.map { tag -> prefix + tag }.toList()
                } else {
                    matches.toList()
                }
            }
        }
        // KMK: shown as soon as the box is tapped, before anything is typed, like a dropdown. A lazy list, so a list
        // of thousands of tags only builds the rows on screen. The menu measures its content's intrinsic width,
        // which a lazy list can't answer, so the list gets a fixed width (the box's) as well as a fixed height
        if (filteredValues.isNotEmpty()) {
            ExposedDropdownMenu(
                modifier = Modifier
                    .exposedDropdownSize(matchAnchorWidth = true),
                expanded = expanded,
                onDismissRequest = {
                    // A tap on the box itself also arrives here as a tap outside the list: the box handles that one
                    if (now() - boxTappedAt > 500) {
                        close()
                        listClosedAt = now()
                    }
                },
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(with(density) { anchorWidth.toDp() }.coerceAtLeast(200.dp))
                        .height((filteredValues.size.coerceAtMost(6) * 48).dp),
                ) {
                    items(filteredValues, key = { it }) { tag ->
                        val mark = if (onPick != null) markOf(tag) else TagMark.None
                        TagRow(
                            tag = tag,
                            mark = mark,
                            onClick = {
                                if (onPick != null) {
                                    onPick(tag, false)
                                } else {
                                    value = TextFieldValue(tag)
                                    submit()
                                }
                            },
                            onLongClick = { onPick?.invoke(tag, true) },
                        )
                    }
                }
            }
        }
    }
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
