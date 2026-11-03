package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.WideCoverStyle
import eu.kanade.presentation.more.settings.WideCoverPlace

// KMK (r115): the "Wide covers" card of Settings › Appearance (mockup SCovers): a four-style picker and the
// per-place list. Hard-coded English labels (listed in the r115 report).

fun wideCoverStyleLabel(style: WideCoverStyle): String = when (style) {
    WideCoverStyle.OFF -> "Off (crop)"
    WideCoverStyle.FILL -> "Color fill"
    WideCoverStyle.FIT -> "Fit width"
    WideCoverStyle.WIDE -> "Wide"
}

fun wideCoverPlaceLabel(place: WideCoverPlace): String = when (place) {
    WideCoverPlace.LIBRARY -> "Library"
    WideCoverPlace.BROWSE -> "Browse sources"
    WideCoverPlace.HISTORY_UPDATES -> "History & Updates"
    WideCoverPlace.SEARCH -> "Search & suggestions"
    WideCoverPlace.TITLE_HEADER -> "Title page header"
    WideCoverPlace.DIALOGS -> "Edit & duplicate dialogs"
}

/** Four tiles with a small drawing of how a landscape cover sits in a portrait slot */
@Composable
fun WideCoverStylePicker(
    selected: WideCoverStyle?,
    note: String?,
    onPick: (WideCoverStyle) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = PrefsHorizontalPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "How a landscape cover (a video's poster) shows in grids. One choice for the whole app; " +
                "each screen's view menu can still change it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WideCoverStyle.entries.forEach { style ->
                val on = style == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (on) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                        )
                        .then(
                            if (on) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onPick(style) }
                        .padding(8.dp),
                ) {
                    WideCoverPreview(style)
                    Text(
                        text = if (style == WideCoverStyle.WIDE) "Wide (2 places)" else wideCoverStyleLabel(style),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun WideCoverPreview(style: WideCoverStyle) {
    val cover = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    val slot = when (style) {
        WideCoverStyle.FILL -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(slot),
        contentAlignment = Alignment.Center,
    ) {
        when (style) {
            // the middle of the wide picture, cropped to the slot
            WideCoverStyle.OFF -> Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.56f)
                    .background(cover),
            )
            WideCoverStyle.FILL, WideCoverStyle.FIT -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
                    .fillMaxSize()
                    .background(cover),
            )
            WideCoverStyle.WIDE -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .fillMaxSize()
                    .background(cover),
            )
        }
    }
}

/** One row of the per-place list; [value] is the place's current look. Choosable only when [enabled]. */
@Composable
fun WideCoverPlaceRow(
    place: WideCoverPlace,
    value: WideCoverStyle,
    note: String?,
    enabled: Boolean,
    onPick: (WideCoverStyle) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val valueText = when {
        place.hasStyles -> wideCoverStyleLabel(value)
        value == WideCoverStyle.OFF -> wideCoverStyleLabel(WideCoverStyle.OFF)
        else -> "On"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 44.dp)
                    .clickable(enabled = enabled) { menuOpen = true }
                    .padding(horizontal = PrefsHorizontalPadding, vertical = 6.dp)
                    .alpha(if (enabled) 1f else 0.7f),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = wideCoverPlaceLabel(place), style = MaterialTheme.typography.bodyMedium)
                    if (note != null) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = valueText + if (enabled) "  ▾" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val choices = if (place.hasStyles) {
                    WideCoverStyle.entries.toList()
                } else {
                    listOf(WideCoverStyle.OFF, WideCoverStyle.FIT)
                }
                choices.forEach { choice ->
                    val label = if (!place.hasStyles && choice != WideCoverStyle.OFF) {
                        "On"
                    } else {
                        wideCoverStyleLabel(choice)
                    }
                    val isCurrent = if (place.hasStyles) {
                        choice == value
                    } else {
                        (choice == WideCoverStyle.OFF) == (value == WideCoverStyle.OFF)
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onPick(choice)
                        },
                    )
                }
            }
        }
    }
}
