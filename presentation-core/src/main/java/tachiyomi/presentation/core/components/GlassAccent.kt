package tachiyomi.presentation.core.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** KMK (r132): the glass design's gold, used where nothing else gives a colour */
val GlassGold = Color(0xFFF2C94C)

/**
 * KMK (r133): the colour lit-up glass controls use (ticks, picked chips, the swipe pill, Resume, slider fill …).
 * Gold by default; on a page themed from a cover (title page, source preview, reader) it's that cover's colour,
 * brightened so it reads on dark glass. A cover with hardly any colour (black and white) keeps gold.
 */
val LocalGlassAccent = staticCompositionLocalOf { GlassGold }

/** The accent for a page themed from [seed], whose theme's primary colour is [primary] */
fun glassAccentFor(seed: Color?, primary: Color): Color {
    if (seed == null) return GlassGold
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    if (hsv[1] < 0.15f) return GlassGold
    var c = primary
    var step = 0
    while (c.luminance() < 0.42f && step < 6) {
        c = lerp(c, Color.White, 0.18f)
        step++
    }
    return c
}

/** Text / icon colour on top of an accent fill */
fun onGlassAccent(accent: Color): Color = if (accent.luminance() > 0.45f) Color(0xFF1B1405) else Color.White
