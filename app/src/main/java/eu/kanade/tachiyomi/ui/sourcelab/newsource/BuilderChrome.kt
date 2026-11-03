package eu.kanade.tachiyomi.ui.sourcelab.newsource

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/*
 * KMK (r106, W1): the builder's chrome, from the approved mockups (`scratchpad/design106/project/TLabels`, `TTitle`,
 * `TTags`, `TCode`, `TAddress`): the one-row top bar (step pills, "N of M picked" pill, Undo, the Save split button,
 * ⋮), the address bar over the page (its icons inside, on the right), the armed strip over the page's bottom, the
 * right panel's tabs, label rows, selector chips, the Tags / Description / Both segments and the panel footer. Pure
 * views: BuilderScreen gives them the state and the actions (no model here).
 */

/** The mockups' green / amber on a dark theme; deeper ones that read on a light theme */
@Composable
internal fun builderOkColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF7FD99A) else Color(0xFF2E7D32)

@Composable
internal fun builderWarnColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFB86B) else Color(0xFFB26A00)

/** One step pill: "1 List page" (current: filled), "✓ List page" (done), "3 Video" (ahead) */
internal class BuilderStepPill(val number: Int, val name: String, val done: Boolean, val current: Boolean)

@Composable
internal fun BuilderStepPills(steps: List<BuilderStepPill>, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ok = builderOkColor()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Box(modifier = Modifier.width(16.dp).height(1.dp).background(colors.outlineVariant))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (step.current) colors.primaryContainer else Color.Transparent)
                    .clickable { onStep(step.number) }
                    .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                val ring = when {
                    step.current -> colors.primary
                    step.done -> ok
                    else -> colors.outline
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (step.current) colors.primary else Color.Transparent)
                        .border(1.dp, ring, CircleShape),
                ) {
                    Text(
                        if (step.done && !step.current) "✓" else step.number.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            step.current -> colors.onPrimary
                            step.done -> ok
                            else -> colors.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    step.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (step.current) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small grey pill ("5 of 8 picked · +3", "Anime") */
@Composable
internal fun BuilderPill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pill: @Composable () -> Unit = {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    if (onClick == null) {
        Box(modifier = modifier) { pill() }
    } else {
        // A tappable pill keeps its look but takes a 44 dp touch target
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp),
        ) { pill() }
    }
}

/** An entry of the Save button's ▾ menu */
internal class BuilderSaveItem(val title: String, val note: String?, val onClick: () -> Unit)

/**
 * The Save split button of the top bar: the main action ("Save as v4", "Write the source") and ▾ with [menu]. The
 * builder writes a new History version on every save (BuilderScreenModel.write), so the label says so.
 */
@Composable
internal fun BuilderSaveButton(label: String, enabled: Boolean, onSave: () -> Unit, menu: List<BuilderSaveItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onSave,
                enabled = enabled,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 12.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 1)
            }
            Spacer(Modifier.width(2.dp))
            Button(
                onClick = { open = true },
                enabled = menu.isNotEmpty(),
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(40.dp),
            ) {
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Save options")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.title)
                            if (item.note != null) {
                                Text(
                                    item.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/**
 * The address bar (mock TAddress), over the page only: rounded, its icons inside on the right. Empty → one Search
 * icon; a page open → Back, Forward (dimmed when it can't), Reload; the text edited to differ from the page → Reload
 * becomes Go (→); loading → Reload becomes Stop, with a thin progress line under the bar. Enter / Go = [onGo].
 */
@Composable
internal fun BuilderAddressBar(
    text: String,
    pageOpen: Boolean,
    edited: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    progress: Int,
    onText: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(shape)
                .background(if (edited) colors.surfaceContainerLowest else colors.surfaceContainer)
                .border(if (edited) 2.dp else 1.dp, if (edited) colors.primary else colors.outlineVariant, shape)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        "Search or type an address",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!pageOpen && !loading) {
                BarButton(Icons.Outlined.Search, "Search", filled = true, enabled = text.isNotBlank(), onClick = onGo)
            } else {
                BarButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Back", enabled = canGoBack, onClick = onBack)
                BarButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Forward", enabled = canGoForward, onClick = onForward)
                when {
                    edited -> BarButton(Icons.AutoMirrored.Outlined.ArrowForward, "Go", filled = true, onClick = onGo)
                    loading -> BarButton(Icons.Outlined.Close, "Stop loading", onClick = onStop)
                    else -> BarButton(Icons.Outlined.Refresh, "Reload", onClick = onReload)
                }
            }
        }
        if (loading) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(5, 100) / 100f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (filled) {
        FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The armed strip over the page's bottom (mock TLabels / TTags): "● Tapping for **Date** · tap the date under any
 * card · Stop". The only armed bar: it replaces r103's "Tapping for: X" bar over the top.
 */
@Composable
internal fun BuilderArmedStrip(what: String, hint: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.primaryContainer,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colors.primary))
            Spacer(Modifier.width(10.dp))
            Text(
                "Tapping for $what" + if (hint.isNotBlank()) " · $hint" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("Stop", color = colors.onPrimaryContainer)
            }
        }
    }
}

/** A short note over the page's top (a Tags tap in the site's menu …) with its OK */
@Composable
internal fun BuilderNoticeBar(text: String, onOk: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = colors.tertiaryContainer, shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onTertiaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOk) { Text("OK", color = colors.onTertiaryContainer) }
        }
    }
}

/** The panel's tabs (Labels · Title page · Lists & filters · Code): text tabs with a 2 dp line under the chosen one */
@Composable
internal fun BuilderPanelTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
            tabs.forEachIndexed { index, name ->
                val on = index == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .clickable { onSelect(index) },
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) colors.primary else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .height(2.dp)
                            .background(if (on) colors.primary else Color.Transparent),
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
    }
}

/** "LIST PAGE", "EPISODES": a small heading over a card of rows */
@Composable
internal fun BuilderSectionHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** A muted line (the panel's explanations) */
@Composable
internal fun BuilderMuted(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A rounded card of rows (the mockups' #1d1e22 card) */
@Composable
internal fun BuilderCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(6.dp), content = content)
    }
}

/** A selector in a mono chip (tap: [onClick], e.g. copy) */
@Composable
internal fun BuilderSelectorChip(text: String, modifier: Modifier = Modifier, maxWidth: Int = 120, onClick: (() -> Unit)? = null) {
    val chip: @Composable () -> Unit = {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = maxWidth.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
    if (onClick == null) {
        Box(modifier = modifier) { chip() }
    } else {
        // Tap = copy: a 44 dp touch target around the chip
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
        ) { chip() }
    }
}

/**
 * A label row (mock TLabels): status glyph (✓ / ● armed / ○ / – / ! / i), the name, then the selector chip and count
 * when picked, else a muted hint, and ✕ when it can be removed. [highlighted]: armed, or a tap waits for a label.
 */
@Composable
internal fun BuilderLabelRow(
    glyph: String,
    glyphColor: Color,
    name: String,
    selector: String?,
    count: Int?,
    hint: String?,
    highlighted: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) colors.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 10.dp, end = if (onRemove != null) 0.dp else 10.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            glyph,
            color = glyphColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                BuilderMuted(subtitle, maxLines = 2)
            }
        }
        if (selector != null) {
            Spacer(Modifier.width(8.dp))
            BuilderSelectorChip(selector)
        }
        if (count != null) {
            Text(
                count.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).widthIn(min = 22.dp),
            )
        }
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (trailing != null) trailing()
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** "Open ›": the link-styled action of a row (Tags, Suggestions, "Show the selectors ›") */
@Composable
internal fun BuilderLinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** A sub-view's head (Tags, Suggestions): ‹ back to the Title page, the name, a muted count */
@Composable
internal fun BuilderSubHead(title: String, note: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Title page", modifier = Modifier.size(20.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        BuilderMuted(note)
    }
}

/** Tags / Description / Both (mock TTags): one bordered segmented control */
@Composable
internal fun BuilderSegments(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(modifier = Modifier.height(44.dp).clip(shape).border(1.dp, colors.outline, shape)) {
        options.forEach { (key, name) ->
            val on = key == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (on) colors.primary else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 9.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A Tags row (mock TTags): the row's name and first values, its segments; hold → [onHold] (rename / remove); [splitNote] offers "Split into rows" */
@Composable
internal fun BuilderTagRow(
    name: String,
    values: String,
    target: String,
    onTarget: (String) -> Unit,
    onHold: () -> Unit,
    splitNote: Boolean = false,
    onSplit: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = {}, onLongClick = onHold)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(values, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            BuilderSegments(
                options = listOf("tags" to "Tags", "description" to "Description", "both" to "Both"),
                selected = target,
                onSelect = onTarget,
            )
        }
        // r104: a restored group that reads whole rows ("Code FC2-PPV…") or the rows' labels ("Code, Type…")
        if (splitNote) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "Reads whole rows or their labels",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSplit) { Text("Split into rows") }
            }
        }
    }
}

/**
 * A row of the Code tab (mock TCode): the label on the left; the selector in a mono chip (tap = copy) and one line
 * saying what it means; or a summary with "Show the selectors ›" that unfolds [details].
 */
@Composable
internal fun BuilderDerivedRow(
    label: String,
    value: String?,
    meaning: String,
    summary: String?,
    expanded: Boolean,
    onCopy: (String) -> Unit,
    onToggle: () -> Unit,
    details: List<Pair<String, String>>,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp).padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (summary != null) {
                Text(summary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) {
                    BuilderLinkButton(if (expanded) "Hide the selectors ‹" else "Show the selectors ›", onToggle)
                }
                if (expanded) {
                    details.forEach { (name, selector) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(90.dp))
                            BuilderSelectorChip(selector.ifEmpty { "(none)" }, maxWidth = 240, onClick = { onCopy(selector) })
                        }
                    }
                }
            } else {
                BuilderSelectorChip((value ?: "").ifEmpty { "(none)" }, maxWidth = 320, onClick = { onCopy(value.orEmpty()) })
            }
            if (meaning.isNotBlank()) BuilderMuted(meaning, maxLines = 3)
        }
    }
}

/** The phone sheet's resting heights: 0 = peek (handle + tabs), 1 = half, 2 = full */
private const val SHEET_PEEK = 0
private const val SHEET_HALF = 1
private const val SHEET_FULL = 2

/**
 * r106 (phone): the page on top and the panel as a draggable bottom sheet (peek / half / full). The sheet's top (a
 * handle and the scrollable tabs) drags it; a tap on the handle steps it up (full → half). [page] gets the space above
 * the sheet's resting height (at most the half level: about 55 % of the height for the page); it is not re-laid out
 * while the finger drags (the sheet slides over it), only when the sheet settles. While [tapping] (a label or a menu
 * pick armed) the sheet drops to its peek so the page is free; when it stops, the sheet goes back where it was, on
 * the same tab. [openTick] changing opens a peeked sheet to half (a new menu card, the helper's verdict).
 */
@Composable
internal fun BuilderPhoneSheet(
    tabs: List<String>,
    tab: Int,
    onTab: (Int) -> Unit,
    tapping: Boolean,
    openTick: Int,
    page: @Composable (Modifier) -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Remembered before anything else (Compose rule)
    var level by rememberSaveable { mutableIntStateOf(SHEET_HALF) }
    var levelBeforeTapping by rememberSaveable { mutableIntStateOf(-1) }
    var settleTick by remember { mutableIntStateOf(0) }
    var sheetPx by remember { mutableFloatStateOf(-1f) }
    // [peek, half, full] in px, written while laying out (read by the drag callbacks; not state)
    val stops = remember { FloatArray(3) }
    val scroll = rememberScrollState()
    val panelScroll = remember(scroll) { PanelScroll(scroll) } // KMK (r109) fix
    val density = LocalDensity.current
    val drag = rememberDraggableState { delta ->
        if (stops[2] > 0f) {
            val from = if (sheetPx < 0f) stops[level] else sheetPx
            sheetPx = (from - delta).coerceIn(stops[0], stops[2])
        }
    }
    LaunchedEffect(tapping) {
        if (tapping) {
            if (level != SHEET_PEEK) {
                levelBeforeTapping = level
                level = SHEET_PEEK
            }
        } else if (levelBeforeTapping >= 0) {
            level = levelBeforeTapping
            levelBeforeTapping = -1
        }
    }
    LaunchedEffect(openTick) {
        if (openTick > 0 && level == SHEET_PEEK && !tapping) level = SHEET_HALF
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val areaPx = with(density) { maxHeight.toPx() }
        val peekPx = with(density) { 92.dp.toPx() }
        val halfPx = (areaPx * 0.45f).coerceAtLeast(peekPx)
        val fullPx = (areaPx - with(density) { 64.dp.toPx() }).coerceAtLeast(halfPx)
        stops[0] = peekPx
        stops[1] = halfPx
        stops[2] = fullPx
        LaunchedEffect(level, settleTick, areaPx) {
            val target = stops[level.coerceIn(0, 2)]
            if (sheetPx < 0f) {
                sheetPx = target
            } else {
                animate(sheetPx, target) { value, _ -> sheetPx = value }
            }
        }
        val shownPx = if (sheetPx < 0f) stops[level.coerceIn(0, 2)] else sheetPx
        val pageBottom = with(density) { (if (level == SHEET_PEEK) peekPx else halfPx).toDp() }
        page(Modifier.fillMaxSize().padding(bottom = pageBottom))
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { shownPx.toDp() }),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = drag,
                            onDragStopped = { velocity ->
                                val now = if (sheetPx < 0f) stops[level] else sheetPx
                                level = when {
                                    // a fling (px/s; down is positive) goes one stop on; else the nearest stop
                                    velocity < -1200f -> (0..2).firstOrNull { stops[it] > now + 1f } ?: SHEET_FULL
                                    velocity > 1200f -> (2 downTo 0).firstOrNull { stops[it] < now - 1f } ?: SHEET_PEEK
                                    else -> (0..2).minByOrNull { abs(stops[it] - now) } ?: SHEET_HALF
                                }
                                settleTick++
                            },
                        ),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable {
                                level = if (level == SHEET_FULL) SHEET_HALF else level + 1
                                settleTick++
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tab,
                        edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        tabs.forEachIndexed { index, name ->
                            Tab(
                                selected = index == tab,
                                onClick = {
                                    onTab(index)
                                    if (level == SHEET_PEEK) level = SHEET_HALF
                                },
                                text = { Text(name, maxLines = 1) },
                            )
                        }
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // KMK (r109) fix: the viewport's top, for a card brought to the panel's top
                        .onGloballyPositioned { panelScroll.viewportTop = it.positionInRoot().y }
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    val column = this
                    CompositionLocalProvider(LocalPanelScroll provides panelScroll) { column.body() }
                }
                // The step's action stays at the sheet's bottom (not at the peek: only the tabs show there)
                if (level != SHEET_PEEK) footer()
            }
        }
    }
}

/**
 * KMK (r109) fix: the builder panel's own scroll ([state]) and its viewport's top in the root ([viewportTop], px): a card
 * that comes to the panel's top scrolls this one only (never the screen or the page around it)
 */
@Stable
internal class PanelScroll(val state: ScrollState) {
    var viewportTop by mutableFloatStateOf(0f)
}

/** KMK (r109) fix: the panel a pane sits in (null = not in one) */
internal val LocalPanelScroll = compositionLocalOf<PanelScroll?> { null }

/**
 * r106 (phone): the top bar's step indicator: the title, "2/3 · Title page ▾" and the picked count under it; a tap
 * lists the three steps ([steps], tap: [onStep])
 */
@Composable
internal fun BuilderStepIndicator(
    title: String,
    steps: List<BuilderStepPill>,
    subtitle: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = steps.firstOrNull { it.current } ?: steps.first()
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${current.number}/${steps.size} · ${current.name} ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            steps.forEach { step ->
                DropdownMenuItem(
                    text = { Text("${step.number} · ${step.name}") },
                    leadingIcon = {
                        Text(
                            when {
                                step.current -> "●"
                                step.done -> "✓"
                                else -> "○"
                            },
                            color = if (step.current) MaterialTheme.colorScheme.primary else builderOkColor(),
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = {
                        open = false
                        onStep(step.number)
                    },
                )
            }
        }
    }
}

/**
 * KMK (r112, mock BEasy): one missing pick of the panel's "What's left" card: what is missing ("Video: tap the player
 * on a title"), whether it is optional, and its jump ("Title page") that switches the step / tab and arms the label
 */
internal class BuilderLeftItem(val text: String, val optional: Boolean, val jump: String, val onJump: () -> Unit)

/**
 * KMK (r112, mock BEasy): "What's left" at the bottom of the panel: the picks still missing across the steps (each with
 * its jump), the ones done folded into one line ("✓ List page, episodes, tags done"); replaces reading "6 of 8 picked"
 */
@Composable
internal fun BuilderWhatsLeft(items: List<BuilderLeftItem>, done: String?, modifier: Modifier = Modifier) {
    val ok = builderOkColor()
    val warn = builderWarnColor()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("What's left", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (items.isEmpty()) {
                BuilderMuted("Nothing: every pick is there. Save when you're ready.")
            }
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    LeftDot(if (item.optional) "·" else "!", if (item.optional) MaterialTheme.colorScheme.onSurfaceVariant else warn)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = item.onJump) { Text("${item.jump} ›", maxLines = 1) }
                }
            }
            if (!done.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    LeftDot("✓", ok)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$done done",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** KMK (r112): the mockup's round mark (✓ / ! / ·) */
@Composable
private fun LeftDot(mark: String, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(20.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
    ) {
        Text(mark, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
