package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Green once a title is fully read (same as Kototoro) */
private val CompletedColor = Color(0xFF34C759)

/** How much of a title is read, 0..1, or null when there's nothing to show */
fun readingProgress(read: Long, total: Long): Float? =
    if (total <= 0L || read <= 0L) null else (read.toFloat() / total).coerceIn(0f, 1f)

@Composable
fun rememberCoverProgressBarEnabled(): Boolean {
    val pref = remember { Injekt.get<UiPreferences>().coverProgressBar() }
    val enabled by pref.collectAsState()
    return enabled
}

/**
 * KMK: The 4dp reading-progress bar drawn along the bottom edge of a cover (ported from Kototoro's
 * ContentCardBottomProgressBar). Put it last inside the cover's clipped box, aligned to the bottom.
 */
@Composable
fun CoverProgressBar(
    percent: Float?,
    modifier: Modifier = Modifier,
) {
    if (percent == null || percent <= 0f) return
    val completed = percent >= 1f
    val shown = percent.coerceIn(0.04f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(shown)
                .fillMaxHeight()
                .background(
                    color = if (completed) CompletedColor else MaterialTheme.colorScheme.primary,
                    shape = if (shown < 0.98f) RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp) else RectangleShape,
                ),
        )
    }
}
