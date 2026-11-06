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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.navigationBars
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.Injekt
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
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
    // KMK (r135): phones get the M4 side sheet: full width, just under the button row, slides in from its side
    if (glassPanel && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600) {
        PhoneSideSheet(visible, fromStart, title, onDismiss, content)
        return
    }
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

/** KMK (r135): true inside the phone side sheet (the Filter content then puts Save / Clear / Apply at the bottom) */
val LocalPhoneSheet = androidx.compose.runtime.compositionLocalOf { false }

/** KMK (r135): the phone sheet's height: full (under the buttons) or half; remembered */
private val phoneSheetHalf by lazy {
    Injekt.get<tachiyomi.core.common.preference.PreferenceStore>()
        .getBoolean("browse_phone_sheet_half", false)
}

/**
 * KMK (r135/r136): Filter / Source Settings on a phone. r136: built like a title page's chapter sheet: fixed to the
 * bottom edge, full width, rounded top corners, a handle, washed with the accent. It slides up to just under the button
 * row; it moves only up and down: drag to snap between full and half height (remembered), or down to close. Tapping
 * the covers closes it too.
 */
@Composable
private fun PhoneSideSheet(
    visible: Boolean,
    @Suppress("UNUSED_PARAMETER") fromStart: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val half by phoneSheetHalf.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.slideOutVertically { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val fullPx = constraints.maxHeight.toFloat()
                var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                val targetPx = if (half) fullPx * 0.5f else fullPx
                val heightPx by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = (targetPx - dragY).coerceIn(fullPx * 0.15f, fullPx),
                    animationSpec = if (dragging) {
                        androidx.compose.animation.core.snap<Float>()
                    } else {
                        androidx.compose.animation.core.spring<Float>(dampingRatio = 0.8f)
                    },
                    label = "phoneSheetHeight",
                )
                val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                val tint = tachiyomi.presentation.core.components.tintedGlass(0.9f, Color(0xFF151820), 0.3f)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(with(density) { heightPx.toDp() })
                        .shadow(16.dp, shape)
                        .liquidGlass(shape)
                        .drawBehind { drawOutline(shape.createOutline(size, layoutDirection, this), tint) }
                        .windowInsetsPadding(
                            androidx.compose.foundation.layout.WindowInsets.navigationBars.only(
                                androidx.compose.foundation.layout.WindowInsetsSides.Bottom,
                            ),
                        )
                        // Keep touches on the sheet from reaching the covers
                        .pointerInput(Unit) {},
                ) {
                    // Handle and title: drag up / down only
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(fullPx, half) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragging = true
                                        dragY = 0f
                                    },
                                    onDragEnd = {
                                        dragging = false
                                        val now = targetPx - dragY
                                        when {
                                            now < fullPx * 0.3f -> onDismiss()
                                            else -> phoneSheetHalf.set(now < fullPx * 0.75f)
                                        }
                                        dragY = 0f
                                    },
                                    onDragCancel = {
                                        dragging = false
                                        dragY = 0f
                                    },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount
                                    },
                                )
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 10.dp, bottom = 4.dp)
                                .align(Alignment.CenterHorizontally)
                                .size(width = 40.dp, height = 5.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.White.copy(alpha = 0.35f)),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            eu.kanade.presentation.components.LocalGlassPanel provides true,
                            LocalPhoneSheet provides true,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}
