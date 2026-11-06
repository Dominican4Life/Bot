package tachiyomi.presentation.core.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** KMK (r132): the glass design's gold, used where nothing else gives a colour */
val GlassGold = Color(0xFFF2C94C)

/**
 * KMK (r133): the colour lit-up glass controls use (ticks, picked chips, the swipe pill, Resume, slider fill …).
 * KMK (r135): the app colour by default (gold only outside any theme); on a page themed from a cover (title page, source preview, reader) it's that cover's colour,
 * brightened so it reads on dark glass. A cover with hardly any colour (black and white) keeps gold.
 */
val LocalGlassAccent = staticCompositionLocalOf { GlassGold }

/**
 * The accent for a page themed from [seed], whose theme's primary colour is [primary]. KMK (r135): with no seed or
 * a colourless cover it's [fallback] (the app colour from Settings › Appearance, see [appGlassAccent]), not gold.
 */
fun glassAccentFor(seed: Color?, primary: Color, fallback: Color = GlassGold): Color {
    if (seed == null) return fallback
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    if (hsv[1] < 0.15f) return fallback
    return brightened(primary)
}

/** KMK (r135): the accent everywhere no cover gives one: the app theme's primary colour, brightened for dark glass */
fun appGlassAccent(primary: Color): Color = brightened(primary)

private fun brightened(color: Color): Color {
    var c = color
    var step = 0
    while (c.luminance() < 0.42f && step < 6) {
        c = lerp(c, Color.White, 0.18f)
        step++
    }
    return c
}

/** Text / icon colour on top of an accent fill */
fun onGlassAccent(accent: Color): Color = if (accent.luminance() > 0.45f) Color(0xFF1B1405) else Color.White

/**
 * KMK (r135, W): dark glass washed with the accent (the cover's colour on title pages and previews, the app colour
 * elsewhere), for the glass panels, chips and round buttons. Icons and text on it stay white.
 */
@Composable
fun tintedGlass(alpha: Float = 0.6f, base: Color = Color(0xFF181A22), amount: Float = 0.26f): Color =
    lerp(base, LocalGlassAccent.current, amount).copy(alpha = alpha)
