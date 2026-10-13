package eu.kanade.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.util.PauseBetweenTitles
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/**
 * KMK: "Pause between titles": an on/off switch, and below it a slider with two handles to pick the range of
 * seconds (1–15) to wait between titles. Used in Settings › Library and in the migration options.
 */
@Composable
fun PauseBetweenTitlesItem(
    enabledPreference: Preference<Boolean>,
    rangePreference: Preference<String>,
    modifier: Modifier = Modifier,
) {
    val enabledPref by enabledPreference.collectAsState()
    val value by rangePreference.collectAsState()
    val savedRange = PauseBetweenTitles.range(value)
    // An old "none" choice counts as off
    val enabled = enabledPref && savedRange != null
    val range = savedRange ?: PauseBetweenTitles.range(PauseBetweenTitles.DEFAULT)!!

    // While dragging, the handles move freely; the range is saved when they're let go
    var dragging by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val shown = dragging ?: (range.first.toFloat()..range.last.toFloat())

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (enabled) {
                        enabledPreference.set(false)
                    } else {
                        if (savedRange == null) rangePreference.set(PauseBetweenTitles.DEFAULT)
                        enabledPreference.set(true)
                    }
                }
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(KMR.strings.migration_delay_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(KMR.strings.migration_delay_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            RangeSlider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let {
                        val from = it.start.roundToInt()
                        val to = it.endInclusive.roundToInt().coerceAtLeast(from)
                        rangePreference.set(PauseBetweenTitles.format(from..to))
                    }
                    dragging = null
                },
                valueRange = PauseBetweenTitles.MIN_SECONDS.toFloat()..PauseBetweenTitles.MAX_SECONDS.toFloat(),
                // One stop per second
                steps = PauseBetweenTitles.MAX_SECONDS - PauseBetweenTitles.MIN_SECONDS - 1,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${shown.start.roundToInt()}–${shown.endInclusive.roundToInt()} s",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            )
        }
    }
}
