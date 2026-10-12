package eu.kanade.presentation.components

import androidx.compose.foundation.isSystemInDarkTheme
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
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The color around a wide cover in the "Panorama: color fill" style. It's picked from the cover and
 * colored with Settings › Appearance › Cover based theme style (Tonal Spot, Vibrant, Monochrome…), like the
 * details page's colors. Null while the cover's color isn't known yet, or when [enabled] is false.
 */
@Composable
fun rememberCoverFillColor(cover: MangaCover, enabled: Boolean): Color? {
    // Covers that don't use the fill (most of them) do no work at all
    if (!enabled) return null
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val style by uiPreferences.themeCoverBasedStyle().collectAsState()
    val amoled by uiPreferences.themeDarkAmoled().collectAsState()
    // The color the app already keeps for the cover first (saved for library titles), so the picture isn't
    // loaded a second time; only a cover with no known color is read
    var seed by remember(cover.mangaId) {
        mutableStateOf(cover.vibrantCoverColor ?: cover.dominantCoverColors?.first)
    }

    LaunchedEffect(cover.mangaId, enabled) {
        if (!enabled || seed != null) return@LaunchedEffect
        seed = withIOContext {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(cover)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
                val bitmap = result.image.asDrawable(context.resources).getBitmapOrNull() ?: return@runCatching null
                Palette.from(bitmap).generate().getBestColor()
            }.getOrNull()
        }?.also { cover.vibrantCoverColor = it }
    }

    return remember(enabled, seed, style, isDark, amoled) {
        val color = seed.takeIf { enabled } ?: return@remember null
        dynamicColorScheme(
            seedColor = Color(color),
            isDark = isDark,
            isAmoled = isDark && amoled,
            style = style,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        ).primaryContainer
    }
}
