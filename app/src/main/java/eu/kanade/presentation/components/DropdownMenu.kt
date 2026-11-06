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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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

/**
 * KMK (r136): a glass menu whose top-right corner sits on its anchor's top-right corner (the composable it's placed in),
 * so it lines up with the row it opens from instead of hanging over or below it.
 */
@Composable
fun TopAlignedGlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val position = remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
        androidx.compose.animation.AnimatedVisibility(
            visibleState = shown,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.9f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f),
                ),
        ) {
            androidx.compose.material3.Surface(
                shape = GlassMenuStyle.shape,
                color = GlassMenuStyle.container(),
                border = GlassMenuStyle.rim(),
                shadowElevation = 18.dp,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .width(196.dp)
                        .heightIn(max = 480.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}
