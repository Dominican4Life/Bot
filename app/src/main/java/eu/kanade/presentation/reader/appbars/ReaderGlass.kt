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

    /** The tint every glass surface of the reader uses: dark so white text reads on white pages too */
    val tint = Color(0xFF14161E).copy(alpha = 0.66f)
    val content = Color(0xFFE6E8EF)
    val accent = Color(0xFFA8C7FA)
    val accentOn = Color(0xFF062E6F)
}

/** True inside the reader's bars while the glass look is on (the slider reads it) */
val LocalReaderGlass = staticCompositionLocalOf { false }

/** A glass surface: the dark tint, a soft light from the top-left and a thin bright edge */
fun Modifier.readerGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(ReaderGlass.tint)
    .background(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.13f),
            0.45f to Color.White.copy(alpha = 0.02f),
            1f to Color.White.copy(alpha = 0.05f),
        ),
    )
    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape)

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
        Column(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .readerGlass(CircleShape)
                .clickable(onClick = onClickTitle)
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
    Box(
        modifier = Modifier
            .size(44.dp)
            .readerGlass(CircleShape)
            .clickable(onClick = onClick),
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xFF181B24).copy(alpha = 0.94f),
        contentColor = ReaderGlass.content,
        scrimColor = Color.Black.copy(alpha = 0.25f),
    ) {
        fun run(action: () -> Unit): () -> Unit = {
            onDismissRequest()
            action()
        }
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
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

@Composable
private fun ActionTile(painter: Painter, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
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
 * KMK (r125): the glass slider knob: a frosted capsule with a bright top edge and a soft shadow that grows a little
 * while it's dragged (in place of Material's round dot).
 */
@Composable
fun GlassSliderThumb(interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource) {
    val pressed by interactionSource.collectIsDraggedAsState()
    val pressedToo by interactionSource.collectIsPressedAsState()
    val active = pressed || pressedToo
    val width by animateDpAsState(if (active) 44.dp else 34.dp, label = "glassKnobWidth")
    val height by animateDpAsState(if (active) 28.dp else 22.dp, label = "glassKnobHeight")
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

/** Track colours for the glass slider: the accent filled part, a faint rest, no step dots */
@Composable
fun glassSliderColors(): androidx.compose.material3.SliderColors = androidx.compose.material3.SliderDefaults.colors(
    activeTrackColor = ReaderGlass.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)
