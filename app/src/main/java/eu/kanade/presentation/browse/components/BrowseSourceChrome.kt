package eu.kanade.presentation.browse.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.SlidingSidePanel
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * KMK: A Popular / Latest / Filter / Settings button of a source's Browse screen. The selected one shows its
 * [label]; the others shrink to their [icon] (a button without an icon always shows its label).
 */
@Composable
fun BrowseTabChip(
    selected: Boolean,
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filledIcon: ImageVector? = null,
) {
    val showLabel = selected || icon == null
    // KMK: not selected, a button with an icon is a square the size of the Left / Right Panel buttons
    if (!showLabel) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(PanelButtonSize),
            shape = FilterChipDefaults.shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon!!,
                    contentDescription = label,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        }
        return
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.animateContentSize(),
        leadingIcon = if (showLabel && icon != null) {
            {
                Icon(
                    imageVector = if (selected) filledIcon ?: icon else icon,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        label = {
            if (showLabel) {
                Text(text = label)
            } else {
                Icon(
                    imageVector = icon!!,
                    contentDescription = label,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        },
    )
}

/**
 * KMK: The Left Panel and Right Panel buttons at the end of the Browse button row. Each turns its preview panel
 * on or off for the first tap of a cover.
 */
@Composable
fun RowScope.PreviewPanelButtons(
    leftOn: Boolean,
    rightOn: Boolean,
    onLeftChange: (Boolean) -> Unit,
    onRightChange: (Boolean) -> Unit,
) {
    PanelToggleButton(
        checked = leftOn,
        onCheckedChange = onLeftChange,
        icon = PanelToggleIcons.Left,
        label = stringResource(KMR.strings.browse_left_panel),
    )
    Spacer(modifier = Modifier.width(8.dp))
    PanelToggleButton(
        checked = rightOn,
        onCheckedChange = onRightChange,
        icon = PanelToggleIcons.Right,
        label = stringResource(KMR.strings.browse_right_panel),
    )
}

@Composable
private fun PanelToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    label: String,
) {
    run {
        // Same height, corner and icon size as the chips beside it; square, and filled like a selected chip when on
        Surface(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(PanelButtonSize),
            shape = FilterChipDefaults.shape,
            color = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = if (checked) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            border = if (checked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        }
    }
}

private val PanelButtonSize = 32.dp

/**
 * KMK: Filter (from the left) or source settings (from the right), slid in over the covers and the preview panels
 * at the preview panels' width. The covers behind are dimmed; tapping them or Back closes it.
 */
@Composable
fun BrowseOverlayPanel(
    visible: Boolean,
    fromStart: Boolean,
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        SlidingSidePanel(
            visible = visible,
            fromStart = fromStart,
            title = title,
            onClose = onDismiss,
            applyTopInset = false,
            showClose = false,
            squareTop = true,
            // KMK: no title bar: the Filter / Settings button that opened it is selected up top
            showHeader = false,
            actions = actions,
            content = content,
        )
    }
}

/** KMK: Icons of the Left Panel and Right Panel buttons: a screen outline with that side's panel filled in */
object PanelToggleIcons {
    val Left: ImageVector by lazy { build("PanelLeft", left = true) }
    val Right: ImageVector by lazy { build("PanelRight", left = false) }

    private fun build(name: String, left: Boolean): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 5f)
                lineTo(19f, 5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                lineTo(21f, 17f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                lineTo(5f, 19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                lineTo(3f, 7f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                val l = if (left) 5f else 13f
                val r = if (left) 11f else 19f
                moveTo(l, 7f)
                lineTo(r, 7f)
                lineTo(r, 17f)
                lineTo(l, 17f)
                close()
            }
        }.build()
    }
}
