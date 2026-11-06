package eu.kanade.presentation.manga.components

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

@Composable
fun DeleteChaptersDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = stringResource(MR.strings.confirm_delete_chapters))
        },
    )
}

// KMK -->
@Composable
fun ClearMangaDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (Boolean, Boolean) -> Unit,
) {
    var list by remember {
        mutableStateOf(
            buildList<CheckboxState.State<StringResource>> {
                add(CheckboxState.State.None(KMR.strings.downloaded_data))
                add(CheckboxState.State.None(KMR.strings.chapters_from_database))
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = list.any { it.isChecked },
                onClick = {
                    onDismissRequest()
                    onConfirm(
                        list[0].isChecked,
                        list[1].isChecked,
                    )
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Column {
                list.forEachIndexed { index, state ->
                    LabeledCheckbox(
                        label = stringResource(state.value),
                        checked = state.isChecked,
                        onCheckedChange = {
                            val mutableList = list.toMutableList()
                            mutableList[index] = state.next() as CheckboxState.State<StringResource>
                            list = mutableList.toList()
                        },
                    )
                }
            }
        },
    )
}
// KMK <--

@Composable
fun SetIntervalDialog(
    interval: Int,
    nextUpdate: Instant?,
    onDismissRequest: () -> Unit,
    onValueChanged: ((Int) -> Unit)? = null,
) {
    var selectedInterval by rememberSaveable { mutableIntStateOf(if (interval < 0) -interval else 0) }

    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate != null) {
            val now = Instant.now()
            now.until(nextUpdate, ChronoUnit.DAYS).toInt().coerceAtLeast(0)
        } else {
            null
        }
    }

    val showPicker = onValueChanged != null && (!isReleaseBuildType)
    // KMK (r132): a glass window that says it plainly, with a small timeline to the expected date
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(tachiyomi.presentation.core.components.LocalGlassAccent.current.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = eu.kanade.presentation.components.GlassIcons.NextUpdate,
                        contentDescription = null,
                        tint = tachiyomi.presentation.core.components.LocalGlassAccent.current,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = if (nextUpdateDays != null && interval >= 0) {
                            stringResource(
                                KMR.strings.smart_update_next_in,
                                pluralStringResource(MR.plurals.day, count = nextUpdateDays, nextUpdateDays),
                            )
                        } else {
                            stringResource(MR.strings.pref_library_update_smart_update)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(KMR.strings.smart_update_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column {
                if (nextUpdateDays != null && nextUpdateDays >= 0 && interval >= 0) {
                    val total = interval.absoluteValue.coerceAtLeast(1)
                    val fraction = ((total - nextUpdateDays).toFloat() / total).coerceIn(0.05f, 0.95f)
                    val gold = tachiyomi.presentation.core.components.LocalGlassAccent.current
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                        val w = maxWidth
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)),
                        )
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .width(w * fraction)
                                .height(8.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(gold.copy(alpha = 0.85f)),
                        )
                        Box(
                            Modifier
                                .padding(start = (w * fraction - 10.dp).coerceAtLeast(0.dp))
                                .size(20.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color.White),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(KMR.strings.smart_update_today),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(KMR.strings.smart_update_expected),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = eu.kanade.presentation.components.GlassIcons.Tracker,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                KMR.strings.smart_update_checks_every,
                                pluralStringResource(MR.plurals.day, count = interval.absoluteValue, interval.absoluteValue),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                } else {
                    Text(
                        stringResource(MR.strings.manga_interval_expected_update_null),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.padding.small))

                if (showPicker) {
                    Text(stringResource(MR.strings.manga_interval_custom_amount))

                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val size = DpSize(width = maxWidth / 2, height = 128.dp)
                        val items =
                            // KMK -->
                            (
                                listOf(stringResource(MR.strings.action_disable)) +
                                    // KMK <--
                                    (0..FetchInterval.MAX_INTERVAL)
                                        .map {
                                            if (it == 0) {
                                                stringResource(MR.strings.label_default)
                                            } else {
                                                it.toString()
                                            }
                                        }
                                )
                                .toImmutableList()
                        WheelTextPicker(
                            items = items,
                            size = size,
                            startIndex = (
                                selectedInterval +
                                    // KMK -->
                                    1
                                ).takeIf { selectedInterval != FetchInterval.MANUAL_DISABLE } ?: 0,
                            // KMK <--
                            onSelectionChanged = { idx ->
                                selectedInterval = (
                                    idx -
                                        // KMK -->
                                        1
                                    ).takeIf { idx != 0 } ?: FetchInterval.MANUAL_DISABLE
                                // KMK <--
                            },
                        )
                    }
                }
            }
        },
        dismissButton = if (showPicker) {
            {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        } else {
            null
        },
        confirmButton = {
            eu.kanade.presentation.category.components.GlassDialogButton(
                text = stringResource(if (showPicker) MR.strings.action_ok else KMR.strings.action_got_it),
                primary = true,
                onClick = {
                    onValueChanged?.invoke(selectedInterval)
                    onDismissRequest()
                },
            )
        },
    )
}
