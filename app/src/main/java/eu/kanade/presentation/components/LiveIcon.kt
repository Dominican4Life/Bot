package eu.kanade.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.Group
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorComposable
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * KMK (r114): the "live" icons of Settings and More. Plain one-colour line icons (24×24, stroke 1.8, round caps and
 * joins) where one part makes one small move each time [LiveIcon]'s `play` counter changes. Nothing loops.
 *
 * The E-Hentai / MangaDex settings rows keep their [exh.assets.EhAssets] logos, so they have no kind here.
 */
enum class LiveIconKind(internal val durationMs: Int) {
    /** Palette: the paint dots pop */
    Appearance(520),

    /** Three books: they tilt */
    Library(560),

    /** Open book: the bookmark ribbon drops in */
    Reader(560),

    /** Arrow onto a line: the arrow drops */
    Downloads(480),

    /** Two turning arrows: one turn */
    Tracking(620),

    /** Compass: the needle swings */
    Browse(620),

    /** Cloud with an arrow: the arrow rises (Settings › Data and storage) */
    DataStorage(500),

    /** Padlock: the shackle clicks */
    Security(460),

    /** Three sliders: the knobs slide */
    Advanced(620),

    /** Info circle: the dot hops */
    About(520),

    /** Download queue (same drawing as [Downloads]) */
    Queue(480),

    /** Label: it nudges forward */
    Categories(520),

    /** Bar chart: the bars grow */
    Statistics(560),

    /** Warning triangle: it shakes */
    UpdateErrors(480),

    /** List with a plus: the plus turns */
    BatchAdd(520),

    /** Settings cog: it turns once, 120° */
    Settings(600),

    /** Screen with a play triangle: the triangle pulses */
    PlayerSettings(500),

    /** Flask: the bubbles rise */
    SourceLab(560),

    /** Two phones: a dot passes from one to the other */
    SyncDevices(800),

    /** Monitor: the text types across, cursor blinks */
    Logs(900),

    /** Database: the middle ring lifts (More › Data and storage) */
    Database(500),

    /** Badge: it spins (More › Recent updates) */
    Updates(600),

    /** Clock: the hands sweep back (More › History) */
    History(640),
}

/**
 * Draws [kind] in [tint]. Every time [play] changes (not on the first composition) the icon's move plays once.
 * With "Remove animations" (animator duration scale 0) it never moves.
 */
@Composable
fun LiveIcon(
    kind: LiveIconKind,
    play: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 24.dp,
) {
    Image(
        painter = rememberLiveIconPainter(kind, play, tint),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/** The painter behind [LiveIcon], for widgets that take a [Painter] (e.g. `TextPreferenceWidget`'s icon). */
@Composable
fun rememberLiveIconPainter(
    kind: LiveIconKind,
    play: Int,
    tint: Color = MaterialTheme.colorScheme.primary,
): Painter {
    val context = LocalContext.current
    val animate = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
    // 1 = at rest; a move runs 0 → 1
    val progress = remember { Animatable(1f) }
    val lastPlay = remember { intArrayOf(play) }
    LaunchedEffect(play, kind) {
        if (play == lastPlay[0]) return@LaunchedEffect
        lastPlay[0] = play
        if (!animate) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = kind.durationMs, easing = LinearEasing))
    }
    return rememberVectorPainter(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = false,
    ) { _, _ ->
        LiveIconContent(kind = kind, t = progress.value, brush = SolidColor(tint))
    }
}

// ---- drawing ----------------------------------------------------------------------------------------------------

@Composable
@VectorComposable
private fun LiveIconContent(kind: LiveIconKind, t: Float, brush: Brush) {
    when (kind) {
        LiveIconKind.Appearance -> {
            Line(P_PALETTE, brush)
            val s = kf(t, 0f, 1f, 0.22f, 1.5f, 0.44f, 0.9f, 0.65f, 1.1f, 1f, 1f)
            Group(pivotX = 11.25f, pivotY = 9f, scaleX = s, scaleY = s) {
                Fill(P_PALETTE_DOTS, brush)
            }
        }
        LiveIconKind.Library -> {
            Group(rotation = kf(t, 0f, 0f, 0.36f, -10f, 0.69f, 4f, 1f, 0f), pivotX = 6f, pivotY = 12f) {
                Line(P_BOOK_1, brush)
            }
            Group(translationY = kf(t, 0f, 0f, 0.36f, -2.5f, 0.69f, 0.5f, 1f, 0f)) {
                Line(P_BOOK_2, brush)
            }
            Group(rotation = kf(t, 0f, 0f, 0.36f, 14f, 0.69f, -4f, 1f, 0f), pivotX = 17f, pivotY = 12.45f) {
                Line(P_BOOK_3, brush)
            }
        }
        LiveIconKind.Reader -> {
            Line(P_READER_BOOK, brush)
            Group(translationY = kf(t, 0f, -6f, 0.45f, 1.5f, 0.7f, 0f, 1f, 0f)) {
                Line(P_READER_RIBBON, brush, alpha = kf(t, 0f, 0f, 0.2f, 1f, 1f, 1f))
            }
        }
        LiveIconKind.Downloads, LiveIconKind.Queue -> {
            Group(translationY = kf(t, 0f, 0f, 0.36f, 3f, 0.62f, -1.5f, 1f, 0f)) {
                Line(P_DOWNLOAD_ARROW, brush)
            }
            Line(P_DOWNLOAD_BASE, brush)
        }
        LiveIconKind.Tracking -> {
            Group(rotation = 360f * Spin.transform(t), pivotX = 12f, pivotY = 12f) {
                Line(P_TRACKING, brush)
            }
        }
        LiveIconKind.Browse -> {
            Line(P_CIRCLE_9, brush)
            Group(
                rotation = kf(t, 0f, 0f, 0.27f, -70f, 0.58f, 40f, 0.84f, -10f, 1f, 0f),
                pivotX = 12f,
                pivotY = 12f,
            ) {
                Fill(P_NEEDLE, brush)
            }
        }
        LiveIconKind.DataStorage -> {
            Line(P_CLOUD, brush)
            Group(translationY = kf(t, 0f, 0f, 0.4f, -3.5f, 0.69f, 0.5f, 1f, 0f)) {
                Line(P_CLOUD_ARROW, brush)
            }
        }
        LiveIconKind.Security -> {
            Line(P_LOCK_BODY, brush)
            Group(translationY = kf(t, 0f, 0f, 0.3f, -3f, 0.6f, 0f, 1f, 0f)) {
                Line(P_LOCK_SHACKLE, brush)
            }
            Fill(P_LOCK_HOLE, brush)
        }
        LiveIconKind.Advanced -> {
            Line(P_TUNE_LINES, brush, alpha = 0.9f)
            Group(translationX = kf(t, 0f, 0f, 0.42f, -6f, 0.75f, 0f, 1f, 0f)) {
                Line(P_TUNE_K1, brush, width = 2.4f)
            }
            Group(translationX = kf(t, 0f, 0f, 0.47f, 7f, 0.8f, 0f, 1f, 0f)) {
                Line(P_TUNE_K2, brush, width = 2.4f)
            }
            Group(translationX = kf(t, 0f, 0f, 0.52f, -5f, 0.85f, 0f, 1f, 0f)) {
                Line(P_TUNE_K3, brush, width = 2.4f)
            }
        }
        LiveIconKind.About -> {
            Line(P_CIRCLE_9, brush)
            Line(P_ABOUT_STEM, brush)
            Group(translationY = kf(t, 0f, 0f, 0.25f, -3.5f, 0.51f, 0f, 0.69f, -1f, 1f, 0f)) {
                Fill(P_ABOUT_DOT, brush)
            }
        }
        LiveIconKind.Categories -> {
            val dx = kf(t, 0f, 0f, 0.27f, -1.5f, 0.53f, 2f, 0.73f, 0f, 1f, 0f)
            val sx = kf(t, 0f, 1f, 0.27f, 0.94f, 0.53f, 1.03f, 0.73f, 1f, 1f, 1f)
            Group(pivotX = 3f, pivotY = 12f, scaleX = sx, translationX = dx) {
                Fill(P_LABEL, brush)
            }
        }
        LiveIconKind.Statistics -> {
            Line(P_STATS_BASE, brush)
            // The bars are redrawn at their height (scaling would squash the round caps)
            Bar(7f, 5f * kf(t, 0f, 1f, 0.13f, 0.2f, 0.5f, 1.12f, 0.7f, 1f, 1f, 1f), brush)
            Bar(12f, 11f * kf(t, 0f, 1f, 0.05f, 1f, 0.18f, 0.2f, 0.55f, 1.12f, 0.75f, 1f, 1f, 1f), brush)
            Bar(17f, 8f * kf(t, 0f, 1f, 0.1f, 1f, 0.23f, 0.2f, 0.6f, 1.12f, 0.8f, 1f, 1f, 1f), brush)
        }
        LiveIconKind.UpdateErrors -> {
            Group(
                translationX = kf(t, 0f, 0f, 0.13f, -2f, 0.27f, 2f, 0.4f, -1.5f, 0.53f, 1f, 0.67f, 0f, 1f, 0f),
            ) {
                Line(P_WARNING, brush)
                Fill(P_WARNING_DOT, brush)
            }
        }
        LiveIconKind.BatchAdd -> {
            Line(P_BATCH_LINES, brush)
            Group(rotation = 180f * Spin.transform(t), pivotX = 17.5f, pivotY = 17f) {
                Line(P_BATCH_PLUS, brush)
            }
        }
        LiveIconKind.Settings -> {
            Group(rotation = 120f * Spin.transform(t), pivotX = 12f, pivotY = 12f) {
                Fill(P_COG, brush)
            }
        }
        LiveIconKind.PlayerSettings -> {
            Line(P_PLAYER_SCREEN, brush)
            val s = kf(t, 0f, 1f, 0.33f, 1.35f, 0.62f, 0.9f, 1f, 1f)
            Group(pivotX = 12.35f, pivotY = 11f, scaleX = s, scaleY = s) {
                Fill(P_PLAYER_PLAY, brush)
            }
        }
        LiveIconKind.SourceLab -> {
            Line(P_FLASK, brush)
            Group(translationY = kf(t, 0f, 0f, 0.42f, -4f, 0.67f, 0f, 1f, 0f)) {
                Fill(P_BUBBLES, brush, alpha = kf(t, 0f, 1f, 0.42f, 0.3f, 0.67f, 1f, 1f, 1f))
            }
        }
        LiveIconKind.SyncDevices -> {
            Line(P_PHONES, brush)
            // The dot leaves to the right, then comes back in from the left to the middle
            val dx = if (t < 0.45f) kf(t, 0f, 0f, 0.45f, 6f) else kf(t, 0.45f, -6f, 1f, 0f)
            Group(translationX = dx) {
                Fill(P_SYNC_DOT, brush, alpha = kf(t, 0f, 1f, 0.25f, 1f, 0.42f, 0f, 0.55f, 0f, 0.75f, 1f, 1f, 1f))
            }
        }
        LiveIconKind.Logs -> {
            Line(P_MONITOR, brush)
            val s1 = steps(clamp01((t - 0.05f) / 0.3f), 6)
            val s2 = steps(clamp01((t - 0.25f) / 0.3f), 8)
            val s3 = steps(clamp01((t - 0.45f) / 0.3f), 4)
            TypedLine(5.5f, 8f, 9f * s1, brush)
            TypedLine(5.5f, 10.7f, 12f * s2, brush)
            TypedLine(5.5f, 13.4f, 6f * s3, brush)
            val cursorX = 5.5f + 6f * s3 + 1.3f
            val blink = if (t >= 1f || floor(t * 8f).toInt() % 2 == 0) 1f else 0.2f
            Line(addPathNodes("M$cursorX 12.6v1.6"), brush, width = 1.6f, alpha = blink)
        }
        LiveIconKind.Database -> {
            Line(P_DB_TOP, brush)
            Line(P_DB_BODY, brush)
            Group(translationY = kf(t, 0f, 0f, 0.33f, -3f, 0.6f, 0.5f, 1f, 0f)) {
                Line(P_DB_RING, brush)
            }
        }
        LiveIconKind.Updates -> {
            Group(rotation = 45f * Spin.transform(t), pivotX = 12f, pivotY = 12f) {
                Line(P_BURST, brush)
            }
            Line(P_BURST_STEM, brush)
            Fill(P_BURST_DOT, brush)
        }
        LiveIconKind.History -> {
            Line(P_HISTORY_ARC, brush)
            Group(rotation = -360f * Spin.transform(t), pivotX = 12f, pivotY = 12f) {
                Line(P_HISTORY_HANDS, brush)
            }
        }
    }
}

@Composable
@VectorComposable
private fun Line(nodes: List<PathNode>, brush: Brush, width: Float = 1.8f, alpha: Float = 1f) {
    Path(
        pathData = nodes,
        stroke = brush,
        strokeAlpha = alpha,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

@Composable
@VectorComposable
private fun Fill(nodes: List<PathNode>, brush: Brush, alpha: Float = 1f) {
    Path(pathData = nodes, fill = brush, fillAlpha = alpha)
}

/** A vertical bar standing on y = 20 */
@Composable
@VectorComposable
private fun Bar(x: Float, height: Float, brush: Brush) {
    Line(addPathNodes("M$x 20v${-height.coerceAtLeast(0.01f)}"), brush)
}

/** A text line typed so far; hidden while empty (a round cap would show a dot) */
@Composable
@VectorComposable
private fun TypedLine(x: Float, y: Float, length: Float, brush: Brush) {
    Line(addPathNodes("M$x ${y}h${length.coerceAtLeast(0.01f)}"), brush, alpha = if (length < 0.05f) 0f else 1f)
}

// ---- timing helpers ---------------------------------------------------------------------------------------------

private val Spin = CubicBezierEasing(0.3f, 0.7f, 0.3f, 1f)

/** Keyframes as (time, value) pairs over t = 0..1, smoothed between each pair */
private fun kf(t: Float, vararg pts: Float): Float {
    if (t <= pts[0]) return pts[1]
    var i = 0
    while (i + 3 < pts.size) {
        val t0 = pts[i]
        val v0 = pts[i + 1]
        val t1 = pts[i + 2]
        val v1 = pts[i + 3]
        if (t <= t1) {
            val f = if (t1 > t0) (t - t0) / (t1 - t0) else 1f
            val e = f * f * (3f - 2f * f)
            return v0 + (v1 - v0) * e
        }
        i += 2
    }
    return pts[pts.size - 1]
}

private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

private fun steps(v: Float, n: Int): Float = if (v >= 1f) 1f else floor(v * n) / n

// ---- shapes -----------------------------------------------------------------------------------------------------

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): String =
    "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0z"

private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
        "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

private fun nodes(vararg parts: String): List<PathNode> = addPathNodes(parts.joinToString(""))

private val P_CIRCLE_9 = nodes(circle(12f, 12f, 9f))

private val P_PALETTE = nodes(
    "M12 3a9 9 0 1 0 0 18c1.4 0 2-1 2-2 0-1.3-1-1.6-1-2.8 0-1 .8-1.7 1.8-1.7H17a4 4 0 0 0 4-4C21 6.4 17 3 12 3z",
)
private val P_PALETTE_DOTS = nodes(circle(7.5f, 11f, 1.2f), circle(10f, 7f, 1.2f), circle(15f, 7.2f, 1.2f))

private val P_BOOK_1 = nodes(roundRect(4f, 4f, 4f, 16f, 1f))
private val P_BOOK_2 = nodes(roundRect(10f, 4f, 4f, 16f, 1f))
private val P_BOOK_3 = nodes("M13.8 5.2l3.4-.9 3 15.4-3.4.9z")

private val P_READER_BOOK = nodes("M3 5.5c2.8-1 5.8-1 9 1v13c-3.2-2-6.2-2-9-1z", "M21 5.5c-2.8-1-5.8-1-9 1v13c3.2-2 6.2-2 9-1z")
private val P_READER_RIBBON = nodes("M15.2 6.3v5.6l1.6-1.3 1.6 1.3V5.8")

private val P_DOWNLOAD_ARROW = nodes("M12 4v10M8 10l4 4 4-4")
private val P_DOWNLOAD_BASE = nodes("M5 19h14")

private val P_TRACKING = nodes(
    "M20 11a8 8 0 0 0-14.3-4.3M4 13a8 8 0 0 0 14.3 4.3",
    "M5 3.5v3.7h3.7M19 20.5v-3.7h-3.7",
)

private val P_NEEDLE = nodes("M14.8 9.2l-1.6 4-4 1.6 1.6-4z")

private val P_CLOUD = nodes("M7 18a4.5 4.5 0 0 1-.6-9 6 6 0 0 1 11.5 1.4A3.8 3.8 0 0 1 17.5 18z")
private val P_CLOUD_ARROW = nodes("M12 16v-5M9.8 13l2.2-2.2 2.2 2.2")

private val P_LOCK_BODY = nodes(roundRect(5f, 11f, 14f, 10f, 2f))
private val P_LOCK_SHACKLE = nodes("M8 11V8a4 4 0 0 1 8 0v3")
private val P_LOCK_HOLE = nodes(circle(12f, 16f, 1.2f))

private val P_TUNE_LINES = nodes("M3 6h18M3 12h18M3 18h18")
private val P_TUNE_K1 = nodes("M15 3.8v4.4")
private val P_TUNE_K2 = nodes("M8 9.8v4.4")
private val P_TUNE_K3 = nodes("M13 15.8v4.4")

private val P_ABOUT_STEM = nodes("M12 11v6")
private val P_ABOUT_DOT = nodes(circle(12f, 7.6f, 1.2f))

/** Material `Icons.AutoMirrored.Outlined.Label` */
private val P_LABEL = nodes(
    "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 " +
        "1.27-.33 1.63-.84L22 12l-4.37-6.16zM16 17H5V7h11l3.55 5L16 17z",
)

private val P_STATS_BASE = nodes("M4 20h16")

private val P_WARNING = nodes("M12 3.5L21.5 20h-19z", "M12 10v4.5")
private val P_WARNING_DOT = nodes(circle(12f, 17.2f, 1.1f))

private val P_BATCH_LINES = nodes("M4 6.5h11M4 11.5h11M4 16.5h6")
private val P_BATCH_PLUS = nodes("M17.5 13.5v7M14 17h7")

/** Material outlined settings cog */
private val P_COG = nodes(
    "M19.43 12.98c.04-.32.07-.64.07-.98 0-.34-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.09-.16-.26-" +
        ".25-.44-.25-.06 0-.12.01-.17.03l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65C14.46 2.18 14.25 2 14 2h-4c-.25 " +
        "0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1c-.06-.02-.12-.03-.18-.03-.17 0-.34.09-.43.25l-2 " +
        "3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.07.65-.07.98 0 .33.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12." +
        "64l2 3.46c.09.16.26.25.44.25.06 0 .12-.01.17-.03l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c" +
        ".25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.06.02.12.03.18.03.17 0 .34-.09.43-.25l2-3.46" +
        "c.12-.22.07-.49-.12-.64l-2.11-1.65zm-1.98-1.71c.04.31.05.52.05.73 0 .21-.02.43-.05.73l-.14 1.13.89.7 1.08.84" +
        "-.7 1.21-1.27-.51-1.04-.42-.9.68c-.43.32-.84.56-1.25.73l-1.06.43-.16 1.13-.2 1.35h-1.4l-.19-1.35-.16-1.13-1." +
        "06-.43c-.43-.18-.83-.41-1.23-.71l-.91-.7-1.06.43-1.27.51-.7-1.21 1.08-.84.89-.7-.14-1.13c-.03-.31-.05-.54-.05" +
        "-.74s.02-.43.05-.73l.14-1.13-.89-.7-1.08-.84.7-1.21 1.27.51 1.04.42.9-.68c.43-.32.84-.56 1.25-.73l1.06-.43.16" +
        "-1.13.2-1.35h1.39l.19 1.35.16 1.13 1.06.43c.43.18.83.41 1.23.71l.91.7 1.06-.43 1.27-.51.7 1.21-1.07.85-.89.7" +
        ".14 1.13zM12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 6c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 " +
        "2-2 2z",
)

private val P_PLAYER_SCREEN = nodes(roundRect(3f, 4.5f, 18f, 13f, 2f), "M8 21h8")
private val P_PLAYER_PLAY = nodes("M10.2 8.3v5.4l4.3-2.7z")

private val P_FLASK = nodes("M9 3.5h6M10 3.5v5.5L4.5 18.5A1.5 1.5 0 0 0 5.8 21h12.4a1.5 1.5 0 0 0 1.3-2.5L14 9V3.5")
private val P_BUBBLES = nodes(circle(10f, 16.5f, 1.1f), circle(13.5f, 14.5f, 0.9f))

private val P_PHONES = nodes(
    roundRect(2.5f, 5f, 7f, 14f, 1.6f),
    roundRect(14.5f, 5f, 7f, 14f, 1.6f),
    "M5.3 16.5h1.4M17.3 16.5h1.4",
)
private val P_SYNC_DOT = nodes(circle(12f, 12f, 1.5f))

private val P_MONITOR = nodes(roundRect(2.5f, 4f, 19f, 13f, 1.8f), "M9 21h6M12 17v4")

private val P_DB_TOP = nodes(ellipse(12f, 6f, 7.5f, 2.8f))
private val P_DB_BODY = nodes("M4.5 6v12c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8V6")
private val P_DB_RING = nodes("M4.5 12c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8")

private val P_BURST = nodes(
    "M12 2.8l2.2 2 2.9-.4.9 2.8 2.6 1.4-.9 2.8 1.3 2.6-2.4 1.7-.2 2.9-2.9.3-1.8 2.3L12 19.4l-2.6 1.3-1.8-2.3-2.9-" +
        ".3-.2-2.9-2.4-1.7 1.3-2.6-.9-2.8 2.6-1.4.9-2.8 2.9.4z",
)
private val P_BURST_STEM = nodes("M12 8v4.5")
private val P_BURST_DOT = nodes(circle(12f, 15.5f, 1.1f))

private val P_HISTORY_ARC = nodes("M3.5 12a8.5 8.5 0 1 0 2.5-6", "M3 4v4h4")
private val P_HISTORY_HANDS = nodes("M12 12V7.5M12 12l3 2")
