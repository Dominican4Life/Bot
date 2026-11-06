package eu.kanade.presentation.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.material3.DropdownMenu as ComposeDropdownMenu

// KMK -->
val DefaultDropdownMenuOffset = DpOffset(8.dp, (-56).dp)
// KMK <--

/**
 * DropdownMenu but overlaps anchor and has width constraints to better
 * match non-Compose implementation.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = /* KMK --> */ DefaultDropdownMenuOffset, /* KMK <-- */
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ComposeDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.sizeIn(minWidth = 196.dp, maxWidth = 196.dp),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        // KMK (r132): every menu is a piece of glass: rounder, a lit rim, the dark glass tint
        shape = GlassMenuStyle.shape,
        containerColor = GlassMenuStyle.container(),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = GlassMenuStyle.rim(),
        content = content,
    )
}

/** KMK (r132): the look shared by the glass menus and windows (a menu is its own window, so it can't blur the page) */
object GlassMenuStyle {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

    @Composable
    fun container(): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(0xFF1C1E27).copy(alpha = 0.96f)

    fun rim() = androidx.compose.foundation.BorderStroke(
        1.dp,
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.42f),
            0.25f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
            1f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f),
        ),
    )
}

/** KMK (r132): the round tick used by the glass menus: gold with a check when on, an empty ring when off */
@Composable
fun GlassTick(checked: Boolean, modifier: Modifier = Modifier) {
    val gold = tachiyomi.presentation.core.components.LocalGlassAccent.current
    Box(
        modifier = modifier
            .size(22.dp)
            .then(
                if (checked) {
                    Modifier.background(gold, androidx.compose.foundation.shape.CircleShape)
                } else {
                    Modifier.border(
                        2.dp,
                        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f),
                        androidx.compose.foundation.shape.CircleShape,
                    )
                },
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color(0xFF2A2105),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
fun RadioMenuItem(
    text: @Composable () -> Unit,
    isChecked: Boolean,
    modifier: Modifier = Modifier,
    // KMK
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        // KMK
        leadingIcon = leadingIcon,
        // KMK (r132): the glass menus' round gold tick
        trailingIcon = { GlassTick(checked = isChecked) },
        modifier = modifier,
    )
}

@Composable
fun NestedMenuItem(
    text: @Composable () -> Unit,
    children: @Composable ColumnScope.(() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var nestedExpanded by remember { mutableStateOf(false) }
    val closeMenu = { nestedExpanded = false }

    Box {
        DropdownMenuItem(
            text = text,
            onClick = { nestedExpanded = true },
            trailingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowRight,
                    contentDescription = null,
                )
            },
        )

        DropdownMenu(
            expanded = nestedExpanded,
            onDismissRequest = closeMenu,
            modifier = modifier,
        ) {
            children(closeMenu)
        }
    }
}
