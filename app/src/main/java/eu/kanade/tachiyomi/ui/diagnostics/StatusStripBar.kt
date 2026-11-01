package eu.kanade.tachiyomi.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.diagnostics.ColorKey
import eu.kanade.tachiyomi.diagnostics.Diag
import eu.kanade.tachiyomi.diagnostics.HealthState
import eu.kanade.tachiyomi.diagnostics.RecordingState
import eu.kanade.tachiyomi.diagnostics.StatusStrip
import eu.kanade.tachiyomi.diagnostics.StripAction
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * KMK (r97): Option B status strip shown on every Diagnostics tab and in the probe editor (contract §1 Half F,
 * ph-01): the health pill (or the REC chip while a recording runs), then Workers · Heap MB · Native MB · Net MB/s
 * with thin bars. Tapping the pill shows the engine's sentence and runs its one-tap action. The red recording strip
 * (ph-06) sits under it. The engine ticks the strip only while one is composed ([setStripVisible] from a
 * [DisposableEffect]; the engine reference-counts, so two strips cost one tick).
 */

/** One palette for every [ColorKey] so a resource looks the same on every tab and in the floating window. */
internal object DiagColors {
    val source0 = Color(0xFF6FA8FF) // blue
    val source1 = Color(0xFFF07CA0) // pink
    val source2 = Color(0xFFFFB454) // amber
    val source3 = Color(0xFF7ED48A) // green
    val source4 = Color(0xFFB796FF) // purple
    val source5 = Color(0xFF4FD1C5) // teal
    val other = Color(0xFF8E96A3) // grey
    val covers = Color(0xFFC4A7FF) // lavender
    val upload = Color(0xFFE6E9EF)
    val heap = Color(0xFF6FA8FF)
    val images = Color(0xFF9CC7FF)
    val js = Color(0xFFFFB454)
    val native = Color(0xFF8E96A3)
    val trimLine = Color(0xFFFF6B6B)
    val ioBusy = Color(0xFFFFB454)
    val defaultBusy = Color(0xFF6FA8FF)
    val jsBusy = Color(0xFF4FD1C5)
    val blocked = Color(0xFFFFB454)
    val parked = Color(0xFF50586A)
    val free = Color(0x00000000)
    val dbQueries = Color(0xFF8FC8FF)
    val dbWal = Color(0xFFE6E9EF)
    val playerCache = Color(0xFF7ED48A)
    val playerDropped = Color(0xFFFF6B6B)
    val probe = Color(0xFF4FD1C5)
    val error = Color(0xFFFF6B6B)
    val stall = Color(0xFFFFB454)
    val trim = Color(0xFFFF9F43)
    val sync = Color(0xFF7ED48A)
    val update = Color(0xFF6FA8FF)
    val crash = Color(0xFFFF6B6B)
    val good = Color(0xFF7ED48A)
    val attention = Color(0xFFFFB454)
    val problem = Color(0xFFFF6B6B)

    /** The one key that follows the theme (marks are drawn in the primary colour) is resolved in composition. */
    @Composable
    fun of(key: ColorKey): Color = if (key == ColorKey.MARK) MaterialTheme.colorScheme.primary else fixed(key)

    /** The same mapping without a composition (for draw-time use); MARK falls back to the heap blue. */
    fun fixed(key: ColorKey): Color = when (key) {
        ColorKey.SOURCE_0 -> source0
        ColorKey.SOURCE_1 -> source1
        ColorKey.SOURCE_2 -> source2
        ColorKey.SOURCE_3 -> source3
        ColorKey.SOURCE_4 -> source4
        ColorKey.SOURCE_5 -> source5
        ColorKey.OTHER -> other
        ColorKey.UPLOAD -> upload
        ColorKey.COVERS -> covers
        ColorKey.HEAP -> heap
        ColorKey.IMAGES -> images
        ColorKey.JS -> js
        ColorKey.NATIVE -> native
        ColorKey.TRIM_LINE -> trimLine
        ColorKey.IO_BUSY -> ioBusy
        ColorKey.DEFAULT_BUSY -> defaultBusy
        ColorKey.JS_BUSY -> jsBusy
        ColorKey.BLOCKED -> blocked
        ColorKey.PARKED -> parked
        ColorKey.FREE -> free
        ColorKey.DB_QUERIES -> dbQueries
        ColorKey.DB_WAL -> dbWal
        ColorKey.PLAYER_CACHE -> playerCache
        ColorKey.PLAYER_DROPPED -> playerDropped
        ColorKey.PROBE -> probe
        ColorKey.MARK -> heap
        ColorKey.ERROR -> error
        ColorKey.STALL -> stall
        ColorKey.TRIM -> trim
        ColorKey.SYNC -> sync
        ColorKey.UPDATE -> update
        ColorKey.CRASH -> crash
    }

    fun health(state: HealthState): Color = when (state) {
        HealthState.GOOD -> good
        HealthState.ATTENTION -> attention
        HealthState.PROBLEM -> problem
    }
}

/**
 * The strip. [onOpenHealth] / [onOpenLog] let the sentence's action switch tabs; the other actions run in the
 * engine and show their result as a toast.
 */
@Composable
internal fun StatusStripBar(
    modifier: Modifier = Modifier,
    onOpenHealth: () -> Unit = {},
    onOpenLog: () -> Unit = {},
) {
    val diag = remember { Diag.get() }
    val strip by diag.strip.collectAsState()
    val recording by diag.recording.collectAsState()
    DisposableEffect(Unit) {
        diag.setStripVisible(true)
        onDispose { diag.setStripVisible(false) }
    }
    var showSentence by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            val rec = recording
            if (rec != null) {
                RecChip(rec, modifier = Modifier.fillMaxHeight().clickable { showSentence = !showSentence })
            } else {
                HealthPill(strip, modifier = Modifier.fillMaxHeight().clickable { showSentence = !showSentence })
            }
            VerticalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StripFigure(
                label = "Workers",
                value = "${strip.workersBusy}",
                suffix = "/${strip.workersTotal}",
                fraction = strip.workersBusy.toFloat() / strip.workersTotal.coerceAtLeast(1),
                color = DiagColors.ioBusy.copy(alpha = 0.85f).takeIf { strip.workersBusy > strip.workersTotal / 2 }
                    ?: DiagColors.heap,
                modifier = Modifier.weight(1f),
            )
            StripFigure(
                label = "Heap MB",
                value = "${strip.heapMb}",
                suffix = "/${strip.heapMaxMb}",
                fraction = strip.heapMb.toFloat() / strip.heapMaxMb.coerceAtLeast(1),
                color = if (strip.heapMb * 4 >= strip.heapMaxMb * 3) DiagColors.problem else DiagColors.heap,
                modifier = Modifier.weight(1f),
            )
            StripFigure(
                label = "Native MB",
                value = "${strip.nativeMb}",
                suffix = "",
                fraction = (strip.nativeMb / 512f).coerceIn(0f, 1f),
                color = DiagColors.heap,
                modifier = Modifier.weight(1f),
            )
            StripFigure(
                label = "Net MB/s",
                value = String.format(Locale.US, "%.1f", strip.netMbps),
                suffix = "",
                fraction = (strip.netMbps / strip.netMaxMbps.coerceAtLeast(0.1f)).coerceIn(0f, 1f),
                color = DiagColors.sync,
                modifier = Modifier.weight(1f),
            )
        }
        if (showSentence && strip.sentence.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    strip.sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val action = strip.action
                if (action != null) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable {
                                when (action) {
                                    StripAction.OPEN_HEALTH -> onOpenHealth()
                                    StripAction.OPEN_LOG -> onOpenLog()
                                    StripAction.FREE_MEMORY -> scope.launch {
                                        val result = runCatching { diag.trimMemory() }
                                            .getOrElse { it.message ?: "Couldn't free memory" }
                                        context.toast(result)
                                    }
                                    StripAction.CLOSE_IDLE_JS -> scope.launch {
                                        val n = runCatching { diag.closeIdleRuntimes() }.getOrDefault(0)
                                        context.toast("Closed $n idle JS runtime${if (n == 1) "" else "s"}")
                                    }
                                    StripAction.THREAD_DUMP -> scope.launch {
                                        val dump = runCatching { diag.threadDump() }.getOrNull()
                                        context.toast(dump?.summary ?: "Thread dump failed")
                                    }
                                }
                                showSentence = false
                            },
                    )
                }
            }
        }
        if (recording != null) {
            RecordingStrip(recording!!, onStop = { diag.stopRecording() })
        }
    }
}

@Composable
private fun HealthPill(strip: StatusStrip, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(DiagColors.health(strip.health)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            strip.health.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** REC m:ss in place of the pill while recording (ph-06). */
@Composable
private fun RecChip(rec: RecordingState, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val remaining = rec.remainingMs(now)
    val shown = if (remaining != null) formatClock(remaining) else formatClock(now - rec.startedAt)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(DiagColors.problem.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(DiagColors.problem))
        Spacer(Modifier.width(8.dp))
        Text("REC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(shown, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun StripFigure(
    label: String,
    value: String,
    suffix: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (suffix.isNotEmpty()) {
                Text(
                    suffix,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

/** The red "Recording · m:ss left · N lines · x MB · Stop" strip (ph-06), under the figures, above the tabs. */
@Composable
internal fun RecordingStrip(rec: RecordingState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val remaining = rec.remainingMs(now)
    val timePart = if (remaining != null) "${formatClock(remaining)} left" else "${formatClock(now - rec.startedAt)}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DiagColors.problem.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(DiagColors.problem))
        Spacer(Modifier.width(10.dp))
        Text(
            "Recording · $timePart · ${formatCount(rec.lines)} lines · ${formatBytes(rec.traceBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = DiagColors.problem,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onStop,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) { Text("Stop", color = DiagColors.problem) }
    }
}
