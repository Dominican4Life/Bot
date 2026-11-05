package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.Build
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.browse.components.liquidGlass
import eu.kanade.presentation.browse.components.glassPress
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r125): the reader's "glass" bars. A round back button, a title pill (tap = Chapters / Pages) and ⋮ on top; the
 * page slider as a glass bar (bottom, or down the side when the vertical slider is on); ⋮ opens a sheet with the
 * reading modes and the reader's own buttons. Settings › Reader › "Glass reader bars" turns it off (the old bars).
 */
object ReaderGlass {
    private val store get() = Injekt.get<PreferenceStore>()

    fun enabled(): Preference<Boolean> = store.getBoolean("reader_glass_bars", true)

    /** KMK (r131): the tablet's Chapters / Pages side panel sits on the left */
    fun sheetLeft(): Preference<Boolean> = store.getBoolean("reader_glass_sheet_left", false)

    /** The tint every glass surface of the reader uses: dark so white text reads on white pages too */
    // KMK (r126): follows Settings › Appearance › Glass
    val tint: Color
        get() = eu.kanade.presentation.browse.components.GlassStyle.fill(Color(0xFF14161E))
    val content = Color(0xFFE6E8EF)
    val accent = Color(0xFFA8C7FA)
    val accentOn = Color(0xFF062E6F)
}

/** True inside the reader's bars while the glass look is on (the slider reads it) */
val LocalReaderGlass = staticCompositionLocalOf { false }

/**
 * A glass surface. KMK (r127): real glass (the page behind blurred, a lens edge, light on the rim) through
 * [eu.kanade.presentation.browse.components.liquidGlass]; the tinted look where that isn't available.
 */
fun Modifier.readerGlass(shape: Shape): Modifier = this
    .clip(shape)
    .liquidGlass(shape, Color(0xFF14161E))

@Composable
internal fun GlassTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    pageText: String?,
    navigateUp: () -> Unit,
    onClickTitle: () -> Unit,
    onClickMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassRoundButton(
            painter = rememberVectorPainter(Icons.AutoMirrored.Outlined.ArrowBack),
            contentDescription = stringResource(MR.strings.action_bar_up_description),
            onClick = navigateUp,
        )
        // KMK (r129): glass flexes and lights up when touched (no ripple)
        val titlePress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Column(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .glassPress(titlePress, CircleShape)
                .readerGlass(CircleShape)
                .clickable(interactionSource = titlePress, indication = null, onClick = onClickTitle)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = mangaTitle.orEmpty(),
                color = ReaderGlass.content,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(chapterTitle, pageText).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    color = ReaderGlass.content.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GlassRoundButton(
            painter = rememberVectorPainter(Icons.Outlined.MoreVert),
            contentDescription = stringResource(MR.strings.label_more),
            onClick = onClickMore,
        )
    }
}

@Composable
private fun GlassRoundButton(painter: Painter, contentDescription: String?, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .glassPress(press, CircleShape)
            .readerGlass(CircleShape)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painter, contentDescription = contentDescription, tint = ReaderGlass.content)
    }
}

/**
 * The ⋮ sheet: the reading modes in a row (the current one filled; tap = this series), then the reader's own buttons
 * (the same ones the old bottom bar had, nothing new).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GlassActionSheet(
    onDismissRequest: () -> Unit,
    readingMode: ReadingMode,
    onSetReadingMode: ((ReadingMode) -> Unit)?,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    enabledButtons: ImmutableSet<String>,
    onClickChapterList: () -> Unit,
    onClickPageGrid: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    onClickTools: (() -> Unit)? = null,
) {
    // KMK (r127): drawn in the reader's own window (not a dialog) so it can be real glass over the page
    androidx.activity.compose.BackHandler(onBack = onDismissRequest)
    val sheetShape = RoundedCornerShape(eu.kanade.presentation.browse.components.GlassStyle.cornerDp() + 6.dp)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .pointerInput(Unit) { detectTapGestures { onDismissRequest() } },
        )
        fun run(action: () -> Unit): () -> Unit = {
            onDismissRequest()
            action()
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = slideInVertically { it } + fadeIn(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(8.dp)
                .sizeIn(maxWidth = 640.dp)
                .fillMaxWidth()
                .shadow(18.dp, sheetShape)
                .liquidGlass(sheetShape, Color(0xFF14161E), thick = true)
                .pointerInput(Unit) {}
                .padding(top = 10.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.6f)),
            )
            // Reading modes for this series
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ReadingMode.entries.forEach { mode ->
                    val selected = mode == readingMode
                    Box(
                        modifier = Modifier
                            .size(width = 48.dp, height = 42.dp)
                            .clip(CircleShape)
                            .background(if (selected) ReaderGlass.accent else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                if (onSetReadingMode != null) onSetReadingMode(mode) else run(onClickReadingMode)()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(mode.iconRes),
                            contentDescription = stringResource(mode.stringRes),
                            tint = if (selected) ReaderGlass.accentOn else ReaderGlass.content,
                        )
                    }
                }
            }
            Text(
                text = stringResource(readingMode.stringRes) + " · " + stringResource(KMR.strings.reader_glass_this_series),
                color = ReaderGlass.accent,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                maxItemsInEachRow = 4,
            ) {
                ActionTile(
                    rememberVectorPainter(Icons.Outlined.FormatListNumbered),
                    stringResource(MR.strings.chapters),
                    run(onClickChapterList),
                )
                ActionTile(
                    rememberVectorPainter(Icons.Outlined.GridView),
                    stringResource(KMR.strings.reader_pages),
                    run(onClickPageGrid),
                )
                ActionTile(
                    painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                    stringResource(MR.strings.pref_crop_borders),
                    onClickCropBorder,
                )
                ActionTile(
                    rememberVectorPainter(orientation.icon),
                    stringResource(MR.strings.pref_rotation_type),
                    run(onClickOrientation),
                )
                if (!dualPageSplitEnabled && ReadingMode.isPagerType(currentReadingMode.flagValue)) {
                    ActionTile(
                        painterResource(R.drawable.ic_book_open_variant_24dp),
                        stringResource(SYMR.strings.page_layout),
                        run(onClickPageLayout),
                    )
                }
                if (doublePages) {
                    ActionTile(
                        painterResource(R.drawable.ic_page_next_outline_24dp),
                        stringResource(SYMR.strings.shift_double_pages),
                        run(onClickShiftPage),
                    )
                }
                if (onClickWebView != null) {
                    ActionTile(
                        rememberVectorPainter(Icons.Outlined.Public),
                        stringResource(MR.strings.action_open_in_web_view),
                        run(onClickWebView),
                    )
                }
                if (onClickBrowser != null && ReaderBottomButton.Browser.isIn(enabledButtons)) {
                    ActionTile(
                        rememberVectorPainter(Icons.Outlined.Explore),
                        stringResource(MR.strings.action_open_in_browser),
                        run(onClickBrowser),
                    )
                }
                if (onClickShare != null) {
                    ActionTile(
                        rememberVectorPainter(Icons.Outlined.Share),
                        stringResource(MR.strings.action_share),
                        run(onClickShare),
                    )
                }
                // KMK (r127): auto scroll, retry all, boost page (was the ⌄ strip under the top bar)
                if (onClickTools != null) {
                    ActionTile(
                        rememberVectorPainter(Icons.Outlined.Build),
                        stringResource(KMR.strings.reader_glass_tools),
                        run(onClickTools),
                    )
                }
                ActionTile(
                    rememberVectorPainter(Icons.Outlined.Settings),
                    stringResource(MR.strings.action_settings),
                    run(onClickSettings),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        }
    }
}

@Composable
private fun ActionTile(painter: Painter, label: String, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .glassPress(press, CircleShape)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painter, contentDescription = null, tint = ReaderGlass.content)
        }
        Text(
            text = label,
            color = ReaderGlass.content.copy(alpha = 0.9f),
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}


/**
 * KMK (r125): the glass slider knob. KMK (r128): the Glass knob (default) is a clear lens like Apple's: the track
 * shows through it magnified (accent on the left of its middle, the rest on the right), light on the top rim, a
 * darker lower rim and a small highlight; it grows and magnifies a little more while dragged. [fraction] is where
 * the knob sits on the track (0..1). Capsule / Large are the frosted pill, Dot is Material's.
 */
@Composable
fun GlassSliderThumb(
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
    fraction: Float = 0.5f,
) {
    // KMK (r126): Settings › Appearance › Glass › Slider knob
    val style = remember { eu.kanade.presentation.browse.components.GlassStyle.knob() }.get()
    if (style == eu.kanade.presentation.browse.components.GlassStyle.KNOB_DOT) {
        androidx.compose.material3.SliderDefaults.Thumb(interactionSource = interactionSource)
        return
    }
    val pressed by interactionSource.collectIsDraggedAsState()
    val pressedToo by interactionSource.collectIsPressedAsState()
    val active = pressed || pressedToo
    if (style == eu.kanade.presentation.browse.components.GlassStyle.KNOB_GLASS) {
        GlassLensKnob(active = active, fraction = fraction)
        return
    }
    val large = style == eu.kanade.presentation.browse.components.GlassStyle.KNOB_LARGE
    val width by animateDpAsState(
        if (active) (if (large) 54.dp else 44.dp) else (if (large) 42.dp else 34.dp),
        label = "glassKnobWidth",
    )
    val height by animateDpAsState(
        if (active) (if (large) 34.dp else 28.dp) else (if (large) 26.dp else 22.dp),
        label = "glassKnobHeight",
    )
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .shadow(if (active) 10.dp else 6.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (active) 0.75f else 0.97f),
                    1f to Color(0xFFDCE3F0).copy(alpha = if (active) 0.55f else 0.90f),
                ),
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)), CircleShape),
    ) {
        // The bright line along the top, like light catching the glass
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .size(width = width * 0.55f, height = 3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

/** KMK (r128): the clear lens knob (see [GlassSliderThumb]) */
@Composable
private fun GlassLensKnob(active: Boolean, fraction: Float) {
    val width by animateDpAsState(if (active) 50.dp else 40.dp, label = "lensWidth")
    val height by animateDpAsState(if (active) 32.dp else 26.dp, label = "lensHeight")
    val zoom by androidx.compose.animation.core.animateFloatAsState(if (active) 1.75f else 1.45f, label = "lensZoom")
    val accent = ReaderGlass.accent
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .drawBehind {
                val shape = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                // A soft shadow just under the lens
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (active) 0.28f else 0.2f),
                    topLeft = Offset(0f, 2.5f.dp.toPx()),
                    size = size,
                    cornerRadius = shape,
                )
            }
            .clip(CircleShape)
            .drawBehind {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                // The glass itself: nearly clear, a touch lighter
                drawRoundRect(Color.White.copy(alpha = if (active) 0.08f else 0.13f), cornerRadius = r)
                // The track seen through it, magnified: split where the knob sits
                val track = 6.dp.toPx() * zoom
                val cy = size.height / 2f
                val split = size.width / 2f
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(-track, cy - track / 2f),
                    size = androidx.compose.ui.geometry.Size(split + track, track),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(track / 2f),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(split, cy - track / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - split + track, track),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(track / 2f),
                )
                // Light gathered at the rim (the glass is thicker there)
                drawRoundRect(
                    brush = Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.22f),
                        center = center,
                        radius = size.width * 0.62f,
                    ),
                    cornerRadius = r,
                )
                // The highlight in the upper half
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                        startY = size.height * 0.08f,
                        endY = size.height * 0.42f,
                    ),
                    topLeft = Offset(size.width * 0.2f, size.height * 0.08f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.34f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.17f),
                )
                // Rim: bright on top, darker below
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.95f),
                        0.5f to Color.White.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.35f),
                    ),
                    cornerRadius = r,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()),
                )
            },
    )
}

/**
 * KMK (r128): the glass slider's track: one continuous bar (no gap around the knob) so the lens sits on it, the
 * accent up to [fraction] and a faint rest.
 */
@Composable
fun GlassSliderTrack(fraction: Float) {
    val accent = ReaderGlass.accent
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = r)
        drawRoundRect(
            accent,
            size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = r,
        )
    }
}

/** Track colours for the glass slider: the accent filled part, a faint rest, no step dots */
@Composable
fun glassSliderColors(): androidx.compose.material3.SliderColors = androidx.compose.material3.SliderDefaults.colors(
    activeTrackColor = ReaderGlass.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)
