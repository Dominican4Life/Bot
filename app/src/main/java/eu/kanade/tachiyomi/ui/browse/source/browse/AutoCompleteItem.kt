package eu.kanade.tachiyomi.ui.browse.source.browse

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.util.runOnEnterKeyPressed

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

                Pair({ it.contains(tagNoPrefix, true) }, prefix)
            },
            onSubmit = { tag ->
                val tagNoPrefix = validPrefixes.find { tag.startsWith(it) }?.let { tag.removePrefix(it).trim() } ?: tag
                if (tagNoPrefix !in skipAutoFillTags) {
                    onChange(state + tag)
                    true
                } else {
                    false
                }
            },
        )
        FlowRow(
            modifier = Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.forEach {
                InputChip(
                    selected = false,
                    onClick = {
                        onChange(state - it)
                    },
                    label = {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = it)
                    },
                    colors = InputChipDefaults.inputChipColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}

@Composable
fun AutoCompleteTextField(
    label: String? = null,
    placeholder: String? = null,
    values: ImmutableList<String>,
    onValueFilter: ((String) -> (Pair<(String) -> Boolean, String?>)),
    onSubmit: (String) -> Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    // KMK: the box's width, so the list below it can have a fixed size (see the menu)
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focusManager = LocalFocusManager.current
    // KMK: a box with a list opens the list on the first tap without the keyboard; a second tap in the box brings the
    // keyboard up to type. A box without a list types right away
    val hasList = values.isNotEmpty()
    var typing by remember(hasList) { mutableStateOf(!hasList) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // When the open list closes because the box itself was tapped, the box asks to open it again right after:
    // that's the second tap too
    var listClosedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(typing) {
        if (typing && hasList) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    fun submit() {
        if (onSubmit(value.text)) {
            focusManager.clearFocus()
            // KMK: the list now also shows for an empty box, so close it after a pick
            expanded = false
            typing = !hasList
            value = TextFieldValue("")
        }
    }
    BackHandler(expanded) {
        focusManager.clearFocus()
        expanded = false
        typing = !hasList
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            // KMK: tapping the box while its list is open (and not typing yet) starts typing instead of closing it
            if (!open && expanded && !typing) {
                typing = true
            } else if (open && hasList && !typing && SystemClock.uptimeMillis() - listClosedAt < 400) {
                typing = true
                expanded = true
            } else {
                expanded = open
                if (!open) typing = !hasList
            }
        },
    ) {
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
            // KMK: no keyboard until the second tap (see typing)
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

        val filteredValues by produceState(emptyList(), value) {
            withContext(Dispatchers.Default) {
                val (filter, prefix) = onValueFilter(value.text)
                // KMK: every match, not only the first 100: the list scrolls, so tapping the empty box shows the
                // whole tag list and typing narrows it down
                this@produceState.value = values.asSequence()
                    .filter(filter)
                    .let {
                        if (prefix != null) {
                            it.map { tag ->
                                prefix + tag
                            }
                        } else {
                            it
                        }
                    }
                    .toList()
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
                    expanded = false
                    typing = !hasList
                    listClosedAt = SystemClock.uptimeMillis()
                },
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(with(density) { anchorWidth.toDp() }.coerceAtLeast(200.dp))
                        .height((filteredValues.size.coerceAtMost(6) * 48).dp),
                ) {
                    items(filteredValues) {
                        DropdownMenuItem(
                            text = { Text(it) },
                            onClick = {
                                value = TextFieldValue(it, TextRange(it.length))
                                submit()
                            },
                        )
                    }
                }
            }
        }
    }
}
