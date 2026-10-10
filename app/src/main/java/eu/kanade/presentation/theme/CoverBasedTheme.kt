package eu.kanade.presentation.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.DynamicScheme
import com.materialkolor.toColorScheme
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Applies a color scheme generated from the cover of [coverData] to [content] when the
 * "Theme based on cover" setting is enabled, otherwise the regular app theme is kept.
 *
 * [coverData] can be anything the app's image loader understands, e.g. a manga.
 */
@Composable
fun CoverBasedTheme(
    coverData: Any?,
    content: @Composable () -> Unit,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val themeCoverBased by uiPreferences.themeCoverBased.collectAsState()
    val style by uiPreferences.themeCoverBasedStyle.collectAsState()

    val seedColor = rememberCoverSeedColor(coverData = coverData.takeIf { themeCoverBased })
    val isDark = isSystemInDarkTheme()
    val isAmoled by uiPreferences.themeDarkAmoled.collectAsState()
    val coverColorScheme = remember(seedColor, style, isDark, isAmoled) {
        seedColor?.let {
            CoverColorScheme(it, style).getColorScheme(
                isDark = isDark,
                isAmoled = isAmoled,
                overrideDarkSurfaceContainers = false,
            )
        }
    }
    // Always wrap the content so it isn't recreated when the cover color becomes available
    MaterialExpressiveTheme(
        colorScheme = coverColorScheme ?: MaterialTheme.colorScheme,
        content = content,
    )
}

@Composable
private fun rememberCoverSeedColor(coverData: Any?): Color? {
    val context = LocalContext.current
    // Keep the previous color until the new one is ready to avoid flashing the default theme
    var seedColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverData) {
        seedColor = coverData?.let { withIOContext { extractCoverSeedColor(context, it) } }
    }
    return seedColor
}

private suspend fun extractCoverSeedColor(context: Context, coverData: Any): Color? {
    val request = ImageRequest.Builder(context)
        .data(coverData)
        // Palette needs to read the pixels, which isn't possible with hardware bitmaps
        .allowHardware(false)
        .size(COVER_PALETTE_SIZE)
        .build()
    val bitmap = context.imageLoader.execute(request).image
        ?.asDrawable(context.resources)
        ?.getBitmapOrNull()
        ?: return null
    val palette = Palette.from(bitmap).generate()
    return palette.getBestColor()?.let { Color(it) }
}

/**
 * Picks the most representative color of a cover.
 *
 * Ported from Komikku, originally by Jays2Kings.
 */
private fun Palette.getBestColor(): Int? {
    // How big is the vibrant color
    val vibPopulation = vibrantSwatch?.population ?: -1
    // Saturation of the most dominant color
    val domSat = dominantSwatch?.hsl?.get(1) ?: 0f
    // Brightness of the most dominant color
    val domLum = dominantSwatch?.hsl?.get(2) ?: -1f
    // How big is the muted color
    val mutedPopulation = mutedSwatch?.population ?: -1
    // Saturation of the muted color
    val mutedSat = mutedSwatch?.hsl?.get(1) ?: 0f
    // If muted color is 3 times bigger than vibrant color then minimum acceptable saturation
    // for muted color is lower (more likely to use it even if it's not colorful)
    val mutedSatMinAcceptable = if (mutedPopulation > vibPopulation * 3f) 0.1f else 0.25f

    val dominantIsColorful = domSat >= .25f
    val dominantBrightnessJustRight = domLum <= .8f && domLum > .2f
    val vibrantIsConsiderableBigEnough = vibPopulation >= mutedPopulation * 0.75f
    val mutedIsBig = mutedPopulation > vibPopulation * 1.5f
    val mutedIsNotTooBoring = mutedSat > mutedSatMinAcceptable

    return when {
        dominantIsColorful && dominantBrightnessJustRight -> dominantSwatch
        // use vibrant color even if it's only 0.75 times smaller than muted color
        vibrantIsConsiderableBigEnough -> vibrantSwatch
        // use muted color if it's 1.5 times bigger than vibrant color and colorful enough (above the limit)
        mutedIsBig && mutedIsNotTooBoring -> mutedSwatch
        // return major vibrant color variant with more favor of vibrant color (size x3)
        else -> listOfNotNull(vibrantSwatch, lightVibrantSwatch, darkVibrantSwatch)
            .maxByOrNull {
                if (it === vibrantSwatch) vibPopulation * 3 else it.population
            }
    }?.rgb
}

private class CoverColorScheme(seedColor: Color, style: PaletteStyle) : BaseColorScheme() {
    override val lightScheme: ColorScheme = generate(seedColor, style, dark = false)
    override val darkScheme: ColorScheme = generate(seedColor, style, dark = true)

    private fun generate(seedColor: Color, style: PaletteStyle, dark: Boolean): ColorScheme {
        return DynamicScheme(seedColor = seedColor, isDark = dark, style = style)
            .toColorScheme(isAmoled = false)
    }
}

private const val COVER_PALETTE_SIZE = 256
