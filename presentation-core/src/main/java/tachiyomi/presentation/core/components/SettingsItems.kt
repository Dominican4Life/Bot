package tachiyomi.presentation.core.components

import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.toggle
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

object SettingsItemsPaddings {
    val Horizontal = 24.dp
    val Vertical = 10.dp
}

@Composable
fun HeadingItem(labelRes: StringResource) {
    HeadingItem(stringResource(labelRes))
}

@Composable
fun HeadingItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.header,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )
}

@Composable
fun IconItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    BaseSettingsItem(
        label = label,
        widget = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onClick,
    )
}

/**
 * KMK (r133): a glass sort row. The picked sort is a lit row with the accent arrow (↑ / ↓) in a small bubble on the
 * left; the others show their own [icon] there (or nothing). Tap the picked one again to flip it.
 */
@Composable
fun SortItem(label: String, sortDescending: Boolean?, onClick: () -> Unit, icon: ImageVector? = null) {
    val arrowIcon = when (sortDescending) {
        true -> GlassArrows.Down
        false -> GlassArrows.Up
        null -> null
    }
    BaseSortItem(label = label, icon = arrowIcon, onClick = onClick, leadingIcon = icon)
}

/** KMK (r133): see [SortItem]; [icon] non-null = this row is the picked one (shown in the accent bubble) */
@Composable
fun BaseSortItem(label: String, icon: ImageVector?, onClick: () -> Unit, leadingIcon: ImageVector? = null) {
    val accent = LocalGlassAccent.current
    val picked = icon != null
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .then(
                if (picked) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.09f), shape)
                        .border(0.6.dp, Color.White.copy(alpha = 0.16f), shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(30.dp)
                .then(
                    if (picked) {
                        Modifier.background(accent.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                picked -> Icon(imageVector = icon!!, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                leadingIcon != null -> Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (picked) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
        )
        if (picked && leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** KMK (r133): the glass sort arrows */
object GlassArrows {
    val Up: ImageVector by lazy { arrow("Up", "M12 19V5M6.5 10.5L12 5l5.5 5.5") }
    val Down: ImageVector by lazy { arrow("Down", "M12 5v14M6.5 13.5L12 19l5.5-5.5") }
    val Check: ImageVector by lazy { arrow("Check", "M6 12.5l4 4 8-8.5", 2.6f) }

    private fun arrow(name: String, d: String, width: Float = 2.4f): ImageVector =
        ImageVector.Builder(
            name = "GlassArrow.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(),
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
        }.build()
}

/** KMK (r133): the round tick of pick-one rows: the accent with a check when picked, an empty ring when not */
@Composable
fun GlassRoundTick(checked: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalGlassAccent.current
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(22.dp)
            .then(
                if (checked) {
                    Modifier.background(accent, androidx.compose.foundation.shape.CircleShape)
                } else {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(imageVector = GlassArrows.Check, contentDescription = null, tint = onGlassAccent(accent), modifier = Modifier.size(15.dp))
        }
    }
}

/** KMK (r133): a pick-one glass row: optional icon, the name, the round tick on the right */
@Composable
fun GlassPickRow(label: String, selected: Boolean, onClick: () -> Unit, icon: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon?.invoke()
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        GlassRoundTick(selected)
    }
}

@Composable
fun CheckboxItem(label: String, pref: Preference<Boolean>) {
    val checked by pref.collectAsState()
    CheckboxItem(
        label = label,
        checked = checked,
        onClick = { pref.toggle() },
    )
}

@Composable
fun CheckboxItem(label: String, checked: Boolean, onClick: () -> Unit) {
    BaseSettingsItem(
        label = label,
        // KMK (r133): the glass tick box (like the Filter tab)
        widget = { GlassTriStateBox(if (checked) TriState.ENABLED_IS else TriState.DISABLED) },
        onClick = onClick,
    )
}

@Composable
fun RadioItem(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    // KMK (r133): a pick-one glass row (round tick on the right)
    GlassPickRow(
        label = label,
        selected = selected,
        onClick = onClick,
        icon = icon?.let { { Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(22.dp)) } },
    )
}

@Composable
fun SliderItem(
    value: Int,
    valueRange: IntProgression,
    label: String,
    onChange: (Int) -> Unit,
    steps: Int = with(valueRange) { (last - first) - 1 },
    valueString: String = value.toString(),
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    pillColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    BaseSliderItem(
        value = value,
        valueRange = valueRange,
        steps = steps,
        title = label,
        valueString = valueString,
        onChange = onChange,
        titleStyle = labelStyle,
        pillColor = pillColor,
        modifier = Modifier.padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    )
}

@Composable
fun BaseSliderItem(
    value: Int,
    valueRange: IntProgression,
    title: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    steps: Int = with(valueRange) { (last - first) - 1 },
    valueString: String = value.toString(),
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall,
    pillColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = subtitleStyle,
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                }
            }
            Pill(
                text = valueString,
                style = MaterialTheme.typography.bodyMedium,
                color = pillColor,
            )
        }
        Slider(
            value = value,
            onValueChange = f@{
                if (it == value) return@f
                onChange(it)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
@PreviewLightDark
fun SliderItemPreview() {
    MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var value by remember { mutableIntStateOf(0) }
        Surface {
            BaseSliderItem(
                value = value,
                valueRange = 0..10,
                title = "Item per row",
                valueString = if (value == 0) "Auto" else value.toString(),
                onChange = { value = it },
                modifier = Modifier.padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            )
        }
    }
}

@Composable
fun SelectItem(
    label: String,
    options: Array<out Any?>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            label = { Text(text = label) },
            // KMK: a list with no options, or a saved index past its end, crashed the filter sheet
            value = options.getOrNull(selectedIndex)?.toString().orEmpty(),
            onValueChange = {},
            enabled = false,
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded,
                )
            },
            // KMK (r133): a rounded glass box instead of the outlined one
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = Color.Transparent,
                disabledContainerColor = Color.White.copy(alpha = 0.07f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        ExposedDropdownMenu(
            modifier = Modifier.exposedDropdownSize(),
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, text ->
                DropdownMenuItem(
                    text = { Text(text.toString()) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun TriStateItem(
    label: String,
    state: TriState,
    enabled: Boolean = true,
    onClick: ((TriState) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .clickable(
                enabled = enabled && onClick != null,
                onClick = {
                    when (state) {
                        TriState.DISABLED -> onClick?.invoke(TriState.ENABLED_IS)
                        TriState.ENABLED_IS -> onClick?.invoke(TriState.ENABLED_NOT)
                        TriState.ENABLED_NOT -> onClick?.invoke(TriState.DISABLED)
                    }
                },
            )
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
    ) {
        val stateAlpha = if (enabled && onClick != null) 1f else DISABLED_ALPHA

        // KMK (r132): a rounded glass box: gold with a tick = only these, red with a dash = hide these
        GlassTriStateBox(state = state, alpha = stateAlpha)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = stateAlpha),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun TextItem(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        label = { Text(text = label) },
        value = value,
        onValueChange = onChange,
        singleLine = true,
    )
}

// SY -->
@Composable
fun IconItem(
    label: String,
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // KMK (r133): a pick-one glass row: icon (the accent when picked), name, round tick
    GlassPickRow(
        label = label,
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (selected) LocalGlassAccent.current else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        },
    )
}
// SY <--

@Composable
fun SettingsChipRow(labelRes: StringResource, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        HeadingItem(labelRes)
        FlowRow(
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                top = 0.dp,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            content = content,
        )
    }
}

@Composable
fun SettingsIconGrid(labelRes: StringResource, content: LazyGridScope.() -> Unit) {
    Column {
        HeadingItem(labelRes)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(128.dp),
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            content = content,
        )
    }
}

@Composable
private fun BaseSettingsItem(
    label: String,
    widget: @Composable RowScope.() -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        widget(this)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** KMK (r132): the rounded box of the glass filter sheet */
@Composable
fun GlassTriStateBox(state: TriState, alpha: Float = 1f) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(22.dp)
            .alpha(alpha)
            .then(
                when (state) {
                    TriState.DISABLED -> Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        shape,
                    )
                    TriState.ENABLED_IS -> Modifier.background(tachiyomi.presentation.core.components.LocalGlassAccent.current, shape)
                    TriState.ENABLED_NOT -> Modifier.background(androidx.compose.ui.graphics.Color(0xFFE5484D), shape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TriState.ENABLED_IS -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = onGlassAccent(tachiyomi.presentation.core.components.LocalGlassAccent.current),
                modifier = Modifier.size(17.dp),
            )
            TriState.ENABLED_NOT -> Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(17.dp),
            )
            TriState.DISABLED -> Unit
        }
    }
}

