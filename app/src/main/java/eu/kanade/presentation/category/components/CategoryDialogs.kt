package eu.kanade.presentation.category.components

import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.preference.asToggleableState
import eu.kanade.presentation.category.visualName
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun CategoryCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
    categories: ImmutableList<String>,
    // SY -->
    title: String = stringResource(MR.strings.action_add_category),
    extraMessage: String? = null,
    alreadyExistsError: StringResource = MR.strings.error_category_exists,
    // SY <--
) {
    var name by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name) { categories.contains(name) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = name.isNotEmpty() && !nameAlreadyExists,
                onClick = {
                    onCreate(name)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            // SY -->
            Text(text = title)
            // SY <--
        },
        text = {
            // SY -->
            Column {
                extraMessage?.let { Text(it) }
                // SY <--

                OutlinedTextField(
                    modifier = Modifier
                        .focusRequester(focusRequester),
                    value = name,
                    onValueChange = { name = it },
                    label = {
                        Text(text = stringResource(MR.strings.name))
                    },
                    supportingText = {
                        val msgRes = if (name.isNotEmpty() && nameAlreadyExists) {
                            // SY -->
                            alreadyExistsError
                            // SY <--
                        } else {
                            MR.strings.information_required_plain
                        }
                        Text(text = stringResource(msgRes))
                    },
                    isError = name.isNotEmpty() && nameAlreadyExists,
                    singleLine = true,
                )
                // SY -->
            }
            // SY <--
        },
    )

    LaunchedEffect(focusRequester) {
        // TODO: https://issuetracker.google.com/issues/204502668
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}

@Composable
fun CategoryRenameDialog(
    onDismissRequest: () -> Unit,
    onRename: (String) -> Unit,
    categories: ImmutableList<String>,
    category: String,
) {
    var name by remember { mutableStateOf(category) }
    var valueHasChanged by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name) { categories.contains(name) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = valueHasChanged && !nameAlreadyExists,
                onClick = {
                    onRename(name)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_rename_category))
        },
        text = {
            OutlinedTextField(
                modifier = Modifier.focusRequester(focusRequester),
                value = name,
                onValueChange = {
                    valueHasChanged = name != it
                    name = it
                },
                label = { Text(text = stringResource(MR.strings.name)) },
                supportingText = {
                    val msgRes = if (valueHasChanged && nameAlreadyExists) {
                        MR.strings.error_category_exists
                    } else {
                        MR.strings.information_required_plain
                    }
                    Text(text = stringResource(msgRes))
                },
                isError = valueHasChanged && nameAlreadyExists,
                singleLine = true,
            )
        },
    )

    LaunchedEffect(focusRequester) {
        // TODO: https://issuetracker.google.com/issues/204502668
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}

@Composable
fun CategoryDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    // SY -->
    category: String = "",
    title: String = stringResource(MR.strings.delete_category),
    text: String = stringResource(MR.strings.delete_category_confirmation, category),
    // SY <--
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            // SY -->
            Text(text = title)
            // SY <--
        },
        text = {
            // SY -->
            Text(text = text)
            // SY <--
        },
    )
}

@Composable
fun ChangeCategoryDialog(
    initialSelection: ImmutableList<CheckboxState<Category>>,
    onDismissRequest: () -> Unit,
    onEditCategories: () -> Unit,
    onConfirm: (List<Long>, List<Long>) -> Unit,
) {
    if (initialSelection.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                tachiyomi.presentation.core.components.material.TextButton(
                    onClick = {
                        onDismissRequest()
                        onEditCategories()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_edit_categories))
                }
            },
            title = {
                Text(text = stringResource(MR.strings.action_move_category))
            },
            text = {
                Text(text = stringResource(MR.strings.information_empty_category_dialog))
            },
        )
        return
    }
    var selection by remember { mutableStateOf(initialSelection) }
    // KMK (r132): a glass window: rounded boxes, a pencil to edit categories, "+ New category", Cancel / Save capsules
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        confirmButton = {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    text = stringResource(MR.strings.action_cancel),
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismissRequest,
                )
                GlassDialogButton(
                    text = stringResource(MR.strings.action_save),
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onConfirm(
                            selection
                                .filter { it is CheckboxState.State.Checked || it is CheckboxState.TriState.Include }
                                .map { it.value.id },
                            selection
                                .filter { it is CheckboxState.State.None || it is CheckboxState.TriState.None }
                                .map { it.value.id },
                        )
                    },
                )
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(MR.strings.categories), modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(
                    onClick = {
                        onDismissRequest()
                        onEditCategories()
                    },
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Edit,
                        contentDescription = stringResource(MR.strings.action_edit_categories),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                selection.forEach { checkbox ->
                    val onChange: (CheckboxState<Category>) -> Unit = {
                        val index = selection.indexOf(it)
                        if (index != -1) {
                            val mutableList = selection.toMutableList()
                            mutableList[index] = it.next()
                            selection = mutableList.toList().toImmutableList()
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable { onChange(checkbox) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tachiyomi.presentation.core.components.GlassTriStateBox(
                            state = when (checkbox) {
                                is CheckboxState.TriState.Include -> tachiyomi.core.common.preference.TriState.ENABLED_IS
                                is CheckboxState.TriState.Exclude -> tachiyomi.core.common.preference.TriState.ENABLED_NOT
                                is CheckboxState.State.Checked -> tachiyomi.core.common.preference.TriState.ENABLED_IS
                                else -> tachiyomi.core.common.preference.TriState.DISABLED
                            },
                        )
                        Text(
                            text = checkbox.value.visualName,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable {
                            onDismissRequest()
                            onEditCategories()
                        }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(MR.strings.action_add_category),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
            }
        },
    )
}

/** KMK (r132): a glass window's button: a capsule, tinted gold when it's the main one */
@Composable
fun GlassDialogButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(21.dp)
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(
                if (primary) {
                    tachiyomi.presentation.core.components.LocalGlassAccent.current.copy(alpha = 0.92f)
                } else {
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f)
                },
            )
            .border(
                1.dp,
                androidx.compose.ui.graphics.Color.White.copy(alpha = if (primary) 0.45f else 0.18f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) androidx.compose.ui.graphics.Color(0xFF2A2105) else MaterialTheme.colorScheme.onSurface,
        )
    }
}
