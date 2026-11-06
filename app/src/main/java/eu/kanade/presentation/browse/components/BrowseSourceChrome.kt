package eu.kanade.presentation.browse.components

import eu.kanade.presentation.components.GlassIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.vectorResource
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
 * [animatedIcon], when given, plays as the button gets selected (like the nav bar's Updates icon) and back.
 */
@Composable
fun BrowseTabChip(
    selected: Boolean,
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filledIcon: ImageVector? = null,
    animatedIcon: AnimatedImageVector? = null,
) {
    val showLabel = selected || icon == null
    // Starts at the beginning and moves to the end once selected, so the animation plays when shown selected
    var animatedAtEnd by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { animatedAtEnd = selected }
    val animatedPainter = animatedIcon?.let { rememberAnimatedVectorPainter(it, animatedAtEnd) }
    // KMK (r133): glass chips: a round glass button with just the icon when not picked, an accent capsule with the
    // (filled) icon and the name when picked
    val accent = tachiyomi.presentation.core.components.LocalGlassAccent.current
    val onAccent = tachiyomi.presentation.core.components.onGlassAccent(accent)
    if (!showLabel) {
        Box(
            modifier = modifier
                .size(PanelButtonSize)
                .clip(androidx.compose.foundation.shape.CircleShape)
                // KMK (r134): dark frosted glass so it reads over busy covers
                .liquidGlass(androidx.compose.foundation.shape.CircleShape, BrowseGlassBase)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (animatedPainter != null) {
                Icon(painter = animatedPainter, contentDescription = label, modifier = Modifier.size(19.dp))
            } else {
                Icon(imageVector = icon!!, contentDescription = label, modifier = Modifier.size(19.dp))
            }
        }
        return
    }
    Row(
        modifier = modifier
            .animateContentSize()
            .height(PanelButtonSize)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .then(
                if (selected) {
                    Modifier.background(accent)
                } else {
                    Modifier.liquidGlass(androidx.compose.foundation.shape.CircleShape, BrowseGlassBase)
                },
            )
            .clickable(onClick = onClick)
            .padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) onAccent else MaterialTheme.colorScheme.onSurface
        if (icon != null) {
            if (animatedPainter != null) {
                Icon(painter = animatedPainter, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
            } else {
                Icon(
                    imageVector = if (selected) filledIcon ?: icon else icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(modifier = Modifier.width(7.dp))
        }
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
            maxLines = 1,
        )
    }
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
    // KMK (r126): one button turns the preview panel on or off (the glass panel holds chapters, preview and
    // details together); turning it off clears both old sides
    val on = leftOn || rightOn
    PanelToggleButton(
        checked = on,
        onCheckedChange = { checked ->
            onLeftChange(checked)
            if (!checked && rightOn) onRightChange(false)
        },
        icon = if (on) GlassIcons.PreviewPanelOn else GlassIcons.PreviewPanel,
        label = stringResource(KMR.strings.preview_glass_pref),
    )
}

@Composable
private fun PanelToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    label: String,
) {
    // KMK (r133): a round glass button, the accent when the panel is on (like the Suggestions toggle)
    val accent = tachiyomi.presentation.core.components.LocalGlassAccent.current
    Box(
        modifier = Modifier
            .size(PanelButtonSize)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .then(
                if (checked) {
                    Modifier.background(accent)
                } else {
                    Modifier.liquidGlass(androidx.compose.foundation.shape.CircleShape, BrowseGlassBase)
                },
            )
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (checked) tachiyomi.presentation.core.components.onGlassAccent(accent) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

private val PanelButtonSize = 36.dp

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
    val glassPanel = remember { eu.kanade.presentation.components.TitleSheet.glass().get() }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // KMK (r134): only lightly dimmed with glass (the panel itself is dark frosted, P1)
                    .background(Color.Black.copy(alpha = if (glassPanel) 0.2f else 0.4f))
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
            // KMK (r133): a glass card with the glass design on; switches and fields inside turn glass too
            glass = glassPanel,
            // KMK (r134): dark frosted (P1) so Filter and Source Settings read over the covers
            glassDim = 0.78f,
            content = {
                androidx.compose.runtime.CompositionLocalProvider(
                    eu.kanade.presentation.components.LocalGlassPanel provides glassPanel,
                    content = content,
                )
            },
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

/**
 * KMK (r134): the floating top of a source's Browse screen slides away while scrolling down through the covers and
 * comes back on the way up; the covers pass under it. [keepShown] (a panel is open, glass is off) holds it in place.
 */
@androidx.compose.runtime.Stable
class BrowseBarHide {
    var height by androidx.compose.runtime.mutableIntStateOf(0)
    var offset by androidx.compose.runtime.mutableFloatStateOf(0f)
    var enabled = true

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            if (enabled && height > 0) {
                offset = (offset + available.y).coerceIn(-height.toFloat(), 0f)
            }
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }
}

@Composable
fun rememberBrowseBarHide(keepShown: Boolean): BrowseBarHide {
    val glass = remember { eu.kanade.presentation.components.TitleSheet.glass().get() }
    val hide = remember { BrowseBarHide() }
    hide.enabled = glass && !keepShown
    LaunchedEffect(hide.enabled) { if (!hide.enabled) hide.offset = 0f }
    return hide
}

/** KMK (r134): put on the top bar's Column */
fun Modifier.browseBarHide(hide: BrowseBarHide): Modifier = this
    .onSizeChanged { hide.height = it.height }
    .offset { androidx.compose.ui.unit.IntOffset(0, kotlin.math.round(hide.offset).toInt()) }
