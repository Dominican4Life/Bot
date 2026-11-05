package eu.kanade.presentation.more.settings.screen.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.GlassStyle
import eu.kanade.presentation.browse.components.PreviewGlass
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.reader.appbars.LocalReaderGlass
import eu.kanade.presentation.reader.appbars.ReaderGlass
import eu.kanade.presentation.reader.appbars.readerGlass
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/**
 * KMK (r126): Settings › Appearance › Glass. A live preview on top (covers with the glass panel over them, the
 * reader's title pill and slider) and the choices under it; every change shows in the preview at once.
 */
class GlassSettingsScreen : Screen() {

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val materialPref = remember { GlassStyle.material() }
        val material by materialPref.collectAsState()
        val seePref = remember { GlassStyle.seeThrough() }
        val see by seePref.collectAsState()
        val cornersPref = remember { GlassStyle.corners() }
        val corners by cornersPref.collectAsState()
        val knobPref = remember { GlassStyle.knob() }
        val knob by knobPref.collectAsState()
        val reducePref = remember { GlassStyle.reduceTransparency() }
        val reduce by reducePref.collectAsState()
        val contrastPref = remember { GlassStyle.contrast() }
        val contrast by contrastPref.collectAsState()
        val panelPref = remember { PreviewGlass.enabled() }
        val panelOn by panelPref.collectAsState()
        val readerPref = remember { ReaderGlass.enabled() }
        val readerOn by readerPref.collectAsState()
        // Any change redraws the preview (the glass reads the settings as it draws)
        val revision = listOf(material, see, corners, knob, reduce, contrast).hashCode()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.glass_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
                GlassPreview(revision)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Group(stringResource(KMR.strings.glass_material))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice(material == GlassStyle.REGULAR, stringResource(KMR.strings.glass_regular)) {
                            materialPref.set(GlassStyle.REGULAR)
                        }
                        Choice(material == GlassStyle.CLEAR, stringResource(KMR.strings.glass_clear)) {
                            materialPref.set(GlassStyle.CLEAR)
                        }
                        Choice(material == GlassStyle.SOLID, stringResource(KMR.strings.glass_solid)) {
                            materialPref.set(GlassStyle.SOLID)
                        }
                    }
                    Note(stringResource(KMR.strings.glass_material_note))
                    if (material == GlassStyle.REGULAR) {
                        SliderRow(stringResource(KMR.strings.glass_see_through), see, 10..80, "%") { seePref.set(it) }
                    }
                    SliderRow(stringResource(KMR.strings.glass_corners), corners, 8..36, " dp") { cornersPref.set(it) }

                    Group(stringResource(KMR.strings.glass_knob))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice(knob == GlassStyle.KNOB_GLASS, stringResource(KMR.strings.glass_knob_glass)) {
                            knobPref.set(GlassStyle.KNOB_GLASS)
                        }
                        Choice(knob == GlassStyle.KNOB_CAPSULE, stringResource(KMR.strings.glass_knob_capsule)) {
                            knobPref.set(GlassStyle.KNOB_CAPSULE)
                        }
                        Choice(knob == GlassStyle.KNOB_LARGE, stringResource(KMR.strings.glass_knob_large)) {
                            knobPref.set(GlassStyle.KNOB_LARGE)
                        }
                        Choice(knob == GlassStyle.KNOB_DOT, stringResource(KMR.strings.glass_knob_dot)) {
                            knobPref.set(GlassStyle.KNOB_DOT)
                        }
                    }

                    Group(stringResource(KMR.strings.glass_accessibility))
                    SwitchRow(stringResource(KMR.strings.glass_reduce_transparency), reduce) { reducePref.set(it) }
                    Text(stringResource(KMR.strings.glass_contrast), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice(contrast == GlassStyle.FOLLOW_SYSTEM, stringResource(KMR.strings.glass_follow_system)) {
                            contrastPref.set(GlassStyle.FOLLOW_SYSTEM)
                        }
                        Choice(contrast == GlassStyle.ON, stringResource(KMR.strings.glass_on)) {
                            contrastPref.set(GlassStyle.ON)
                        }
                        Choice(contrast == GlassStyle.OFF, stringResource(KMR.strings.glass_off)) {
                            contrastPref.set(GlassStyle.OFF)
                        }
                    }

                    Group(stringResource(KMR.strings.glass_where))
                    SwitchRow(stringResource(KMR.strings.preview_glass_pref), panelOn) { panelPref.set(it) }
                    SwitchRow(stringResource(KMR.strings.reader_glass_pref), readerOn) { readerPref.set(it) }
                    TextButtonRow(stringResource(KMR.strings.glass_reset)) {
                        materialPref.delete()
                        seePref.delete()
                        cornersPref.delete()
                        knobPref.delete()
                        reducePref.delete()
                        contrastPref.delete()
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Covers, the glass panel over them, and the reader's title pill and slider, drawn with the current settings */
@Composable
private fun GlassPreview(revision: Int) {
    val corner = GlassStyle.cornerDp()
    val panelShape = RoundedCornerShape(corner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color.Black),
    ) {
        // Covers behind the glass
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val palettes = listOf(
                listOf(Color(0xFF5D7BD6), Color(0xFFE0A3B8)),
                listOf(Color(0xFF0F3443), Color(0xFF34E89E)),
                listOf(Color(0xFFFF512F), Color(0xFFF09819)),
                listOf(Color(0xFF41295A), Color(0xFFFF7EB3)),
                listOf(Color(0xFF134E5E), Color(0xFF71B280)),
            )
            palettes.forEach { colors ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) { i ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(if (i == 0) colors else colors.reversed())),
                        )
                    }
                }
            }
        }
        // The panel
        Column(
            modifier = Modifier
                .padding(12.dp)
                .width(170.dp)
                .fillMaxSize()
                .clip(panelShape)
                .background(GlassStyle.fill(Color(0xFF12141B)))
                .border(BorderStroke(GlassStyle.edgeWidth(), GlassStyle.edgeColor()), panelShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Example title", fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
                }
            }
            repeat(4) { n ->
                Text("Chapter ${24 - n}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("Continue", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
        // The reader's title pill and slider
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(200.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .readerGlass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("Chapter 4 · 8 / 24", color = ReaderGlass.content, style = MaterialTheme.typography.labelMedium)
            }
            var page by remember(revision) { mutableIntStateOf(8) }
            CompositionLocalProvider(LocalReaderGlass provides true) {
                androidx.compose.runtime.key(revision) {
                ChapterNavigator(
                    isRtl = false,
                    onNextChapter = {},
                    enabledNext = true,
                    onPreviousChapter = {},
                    enabledPrevious = true,
                    currentPage = page,
                    totalPages = 24,
                    onPageIndexChange = { page = it + 1 },
                    isVerticalSlider = false,
                    currentPageText = page.toString(),
                )
                }
            }
        }
    }
}

@Composable
private fun Group(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("$value$unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 12.dp)) {
        Text(label)
    }
}
