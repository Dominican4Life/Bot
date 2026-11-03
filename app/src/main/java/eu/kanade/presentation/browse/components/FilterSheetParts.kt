package eu.kanade.presentation.browse.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * KMK (r107): the parts of the filter sheet's look (mockup "CSheet" / "CSort"): rounded cards with small uppercase
 * titles, filled field boxes with a small label above the value and a caret, pills for short choices, and the sort
 * menu with the arrow on the left of the picked option. Colours come from the theme so the light theme works.
 */

/** Corner of the field boxes and the square sort button */
val FilterFieldShape = RoundedCornerShape(12.dp)

/** Height of a field box and size of the square sort button */
val FilterFieldHeight: Dp = 52.dp

/** Inner side padding of a card, for the fields (the older setting rows bring their own) */
val FilterCardPadding: Dp = 14.dp

/** A rounded section of the sheet with its small uppercase title */
@Composable
fun FilterCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(vertical = FilterCardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = FilterCardPadding + 4.dp),
            )
            content()
        }
    }
}

/** The background of a field box: tinted while open or set to something */
@Composable
fun filterFieldColor(highlighted: Boolean): Color = if (highlighted) {
    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
} else {
    MaterialTheme.colorScheme.surfaceContainerLowest
}

/**
 * A filled field box: [label] small above [value], a caret on the right, a ring in the primary colour while [open].
 * [leading] shows before the value (a sort arrow).
 * KMK (r110): [shape] (a collection's open box has square bottom corners: its panel hangs under it) and [trailing], a
 * small part before the caret (a collection's sort chip)
 */
@Composable
fun FilterFieldBox(
    label: String,
    value: String,
    open: Boolean,
    set: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimValue: Boolean = false,
    leading: ImageVector? = null,
    shape: Shape = FilterFieldShape,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ring = if (open) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .height(FilterFieldHeight)
            .clip(shape)
            .then(ring)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    Icon(
                        imageVector = leading,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dimValue) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // KMK (r110)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
            Spacer(Modifier.width(2.dp))
        }
        Icon(
            imageVector = if (open) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [FilterFieldBox] on its filled background */
@Composable
fun FilledFilterField(
    label: String,
    value: String,
    open: Boolean,
    set: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimValue: Boolean = false,
    leading: ImageVector? = null,
    // KMK (r110): see [FilterFieldBox]
    shape: Shape = FilterFieldShape,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = filterFieldColor(open || set),
    ) {
        FilterFieldBox(
            label = label,
            value = value,
            open = open,
            set = set,
            onClick = onClick,
            dimValue = dimValue,
            leading = leading,
            shape = shape,
            trailing = trailing,
        )
    }
}

/** A drop-down select as a filled field; its options open in a menu under it */
@Composable
fun FilterSelectField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilledFilterField(
            label = label,
            value = options.getOrNull(selectedIndex).orEmpty(),
            open = open,
            set = selectedIndex > 0,
            dimValue = selectedIndex <= 0,
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            options.forEachIndexed { index, option ->
                FilterMenuRow(
                    label = option,
                    selected = index == selectedIndex,
                    icon = if (index == selectedIndex) Icons.Filled.Check else null,
                    onClick = {
                        open = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

/** True when a select is better shown as pills: a few short options */
fun fitsPills(options: List<String>): Boolean =
    options.size in 2..5 && options.all { it.length <= 18 } && options.sumOf { it.length } <= 56

/** A select as a row of pills under its small label */
@Composable
fun FilterPills(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * One row of the sort menu (and of the select menus): [icon] on the LEFT in the primary colour (the sort arrow of the
 * picked option), or a blank space of the same width so the names line up. Looks like the library's sort rows
 * (presentation-core SortItem), with the arrow moved to the left
 */
@Composable
fun FilterMenuRow(
    label: String,
    selected: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The arrow of a sort direction */
fun sortArrow(ascending: Boolean): ImageVector =
    if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward

/**
 * The sort menu (mockup "CSort"): no title, opened right under its anchor. The picked option has its arrow on the
 * left; tapping another option picks it, tapping the picked one flips its direction ([onPick] decides); either
 * closes the menu (KMK r112)
 */
@Composable
fun FilterSortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<String>,
    selectedIndex: Int,
    ascending: Boolean,
    onPick: (Int) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(236.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        options.forEachIndexed { index, option ->
            FilterMenuRow(
                label = option,
                selected = index == selectedIndex,
                icon = if (index == selectedIndex) sortArrow(ascending) else null,
                onClick = {
                    onPick(index)
                    onDismiss() // KMK (r112): a pick or a flip closes the menu
                },
            )
        }
    }
}
