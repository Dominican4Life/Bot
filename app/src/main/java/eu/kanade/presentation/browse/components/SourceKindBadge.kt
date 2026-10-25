package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.browse.extension.unified.ExtensionKind

// KMK: fixed colors that don't follow the app theme: Mihon blue, Aniyomi pink, JS yellow, JSON orange. Each has a
// darker shade for light themes so the text stays readable.
private val MihonBlueDark = Color(0xFF8AB4F8)
private val MihonBlueLight = Color(0xFF1A62C9)
private val AniyomiPinkDark = Color(0xFFE3B9DE)
private val AniyomiPinkLight = Color(0xFF8B4A83)
private val JsYellowDark = Color(0xFFFFCA28)
private val JsYellowLight = Color(0xFFA67C00)
private val JsonOrangeDark = Color(0xFFFF9F43)
private val JsonOrangeLight = Color(0xFFC25A00)

@Composable
fun sourceKindColor(kind: ExtensionKind): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (kind) {
        ExtensionKind.MIHON -> if (dark) MihonBlueDark else MihonBlueLight
        ExtensionKind.ANIYOMI -> if (dark) AniyomiPinkDark else AniyomiPinkLight
        ExtensionKind.JS -> if (dark) JsYellowDark else JsYellowLight
        ExtensionKind.JSON -> if (dark) JsonOrangeDark else JsonOrangeLight
    }
}

/** KMK: A small outlined badge naming where a source comes from (Mihon, Aniyomi, JS or JSON), in that kind's color */
@Composable
fun SourceKindBadge(kind: ExtensionKind, modifier: Modifier = Modifier) {
    val color = sourceKindColor(kind)
    // Same text, padding and corners as the EN and 18+ badges, so they line up at the same height
    Text(
        text = kind.label,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
