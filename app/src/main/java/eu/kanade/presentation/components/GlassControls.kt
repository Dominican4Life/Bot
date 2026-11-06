package eu.kanade.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.presentation.core.components.LocalGlassAccent
import tachiyomi.presentation.core.components.onGlassAccent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KMK (r133): the stepped glass slider (Columns, Items per row …), moving like iOS 26's: a white capsule while it's
 * still; touch it and it swells into a clear glass lens, stretches the way it moves and jumps tick to tick (a small
 * buzz on each); let go and it shrinks back into the capsule with one squish. The fill is the glass accent.
 * [labels] puts words under some ticks (e.g. 0 → "Auto").
 */
@Composable
fun GlassStepSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labels: Map<Int, String> = emptyMap(),
) {
    val accent = LocalGlassAccent.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val steps = (range.last - range.first).coerceAtLeast(1)
    val current by rememberUpdatedState(value.coerceIn(range))
    val onChange by rememberUpdatedState(onValueChange)
    var pressed by remember { mutableStateOf(false) }
    val frac = (current - range.first).toFloat() / steps
    val animFrac by animateFloatAsState(
        targetValue = frac,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "glassSliderPos",
    )
    val lens by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "glassSliderLens",
    )
    val lag = (frac - animFrac)
    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 14.dp)
                .pointerInput(range) {
                    fun pick(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f)
                        val v = range.first + (f * steps).roundToInt()
                        if (v != current) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(v)
                        }
                    }
                    detectDragGestures(
                        onDragStart = {
                            pressed = true
                            pick(it.x)
                        },
                        onDragEnd = { pressed = false },
                        onDragCancel = { pressed = false },
                        onDrag = { change, _ -> pick(change.position.x) },
                    )
                }
                .pointerInput(range) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { o ->
                            val f = (o.x / size.width).coerceIn(0f, 1f)
                            val v = range.first + (f * steps).roundToInt()
                            if (v != current) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onChange(v)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val w = maxWidth
            // track
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color(0x5C78787F)),
            )
            Box(
                Modifier
                    .width(w * animFrac)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            // ticks
            if (steps <= 20) {
                for (i in 0..steps) {
                    Box(
                        Modifier
                            .offset(x = w * (i.toFloat() / steps) - 2.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.55f)),
                    )
                }
            }
            // the knob: white capsule at rest, clear glass lens while held
            val stretch = 1f + (abs(lag) * 1.6f).coerceAtMost(0.18f) * lens
            val knobW = 40.dp + 22.dp * lens
            val knobH = 22.dp + 14.dp * lens
            Box(
                Modifier
                    .offset { IntOffset(with(density) { (w * animFrac - knobW / 2).roundToPx() }, 0) }
                    .size(knobW, knobH)
                    .graphicsLayer {
                        scaleX = stretch
                        scaleY = 2f - stretch
                    }
                    .clip(CircleShape)
                    .background(
                        if (lens < 0.5f) {
                            Color.White.copy(alpha = 1f - lens * 1.6f)
                        } else {
                            Color.White.copy(alpha = 0.10f)
                        },
                    )
                    .border(
                        (0.6f + lens).dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.25f + 0.6f * lens), Color.White.copy(alpha = 0.12f * lens)),
                        ),
                        CircleShape,
                    ),
            )
        }
        if (labels.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(16.dp)) {
                val w = maxWidth
                labels.forEach { (v, text) ->
                    val f = (v - range.first).toFloat() / steps
                    Text(
                        text = text,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .width(40.dp)
                            .offset(x = w * f - 20.dp),
                    )
                }
            }
        }
    }
}

/** KMK (r133): the small glass number bubble next to a slider's name ("4", "Auto") */
@Composable
fun GlassValuePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 28.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
    }
}

/** KMK (r133): a name with a value pill on the right, then the stepped glass slider under it */
@Composable
fun GlassSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueText: (Int) -> String = { it.toString() },
    labels: Map<Int, String> = emptyMap(),
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            GlassValuePill(valueText(value))
        }
        GlassStepSlider(value = value, range = range, onValueChange = onValueChange, labels = labels)
    }
}

/**
 * KMK (r133): a glass switch of a few choices (Auto / Page 2:3 / Square …): a dark groove with the picked choice as a
 * lit glass piece. Optional icon above each name. More than [perRow] choices wrap onto a second row.
 */
@Composable
fun <T> GlassChoiceSwitch(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<T, ImageVector> = emptyMap(),
    perRow: Int = 4,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        choices.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (value, label) ->
                    val on = value == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) Color.White.copy(alpha = 0.17f) else Color.Transparent)
                            .then(
                                if (on) {
                                    Modifier.border(
                                        0.8.dp,
                                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent)),
                                        RoundedCornerShape(15.dp),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelect(value) }
                            .padding(vertical = if (icons.isEmpty()) 8.dp else 7.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        icons[value]?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                tint = if (on) LocalGlassAccent.current else Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(20.dp).padding(bottom = 2.dp),
                            )
                        }
                        Text(
                            text = label,
                            fontSize = 12.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.78f),
                            // KMK (r134): long names ("Strip Slices") wrap onto a second line instead of being cut off
                            maxLines = 2,
                            lineHeight = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** KMK (r133): a glass on/off switch, the accent when on */
@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val accent = LocalGlassAccent.current
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.6f), label = "glassSwitch")
    Box(
        modifier = modifier
            .size(48.dp, 28.dp)
            .clip(CircleShape)
            .background(if (checked) accent else Color(0x6678787F))
            .then(if (onCheckedChange != null) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = 20.dp * pos)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** KMK (r133): a glass button: the accent filled when [primary], else clear glass */
@Composable
fun GlassButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val accent = LocalGlassAccent.current
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (primary) accent else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary) onGlassAccent(accent) else Color.White
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp).padding(end = 2.dp)) }
        Text(text = text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** KMK (r133): Columns / Items per row (0 = Auto … 15) as the stepped glass slider, for every display menu */
@Composable
fun GlassColumnsSlider(columns: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val auto = tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.MR.strings.label_auto)
    GlassSliderRow(
        label = tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.MR.strings.pref_library_columns),
        value = columns,
        range = 0..15,
        onValueChange = onChange,
        valueText = { if (it > 0) it.toString() else auto },
        labels = mapOf(0 to auto, 5 to "5", 10 to "10", 15 to "15"),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * KMK (r133): true inside the glass browse side panels (Filter / source Settings): switches turn into [GlassSwitch]
 * and filter fields into glass boxes there.
 */
val LocalGlassPanel = androidx.compose.runtime.staticCompositionLocalOf { false }
