package eu.kanade.presentation.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: What decides a fill's look besides the cover's own color: Settings › Appearance › Cover based theme style,
 * dark mode and AMOLED. A grid reads these once ([ProvideCoverFillSettings]) instead of every cover reading them.
 */
@Immutable
data class CoverFillSettings(val style: PaletteStyle, val isDark: Boolean, val amoled: Boolean)

private val LocalCoverFillSettings = staticCompositionLocalOf<CoverFillSettings?> { null }

@Composable
private fun readCoverFillSettings(): CoverFillSettings {
    val isDark = isSystemInDarkTheme()
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val style by uiPreferences.themeCoverBasedStyle().collectAsState()
    val amoled by uiPreferences.themeDarkAmoled().collectAsState()
    return remember(style, isDark, amoled) { CoverFillSettings(style, isDark, isDark && amoled) }
}

/** Reads the fill settings once for everything inside [content] (a grid of covers) */
@Composable
fun ProvideCoverFillSettings(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCoverFillSettings provides readCoverFillSettings(), content = content)
}

/** KMK: Fill colors already worked out, shared by every cover, and each cover's own base color */
private object CoverFillCache {
    /** (base color, settings) → the fill */
    val fills = ConcurrentHashMap<Pair<Int, CoverFillSettings>, Int>()

    /** Manga id → the base color its fill uses, so it stays the same after the title's details are opened */
    val seeds = ConcurrentHashMap<Long, Int>()

    fun fill(seed: Int, settings: CoverFillSettings): Int = fills.getOrPut(seed to settings) {
        dynamicColorScheme(
            seedColor = Color(seed),
            isDark = settings.isDark,
            isAmoled = settings.amoled,
            style = settings.style,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        ).primaryContainer.toArgb()
    }
}

/**
 * KMK: The color around a wide cover in the "Panorama: color fill" style, picked from the cover and styled like the
 * details page's colors. Null while it isn't known yet (the cover shows its plain background, then the color fades
 * in), or when [enabled] is false.
 *
 * Nothing heavy happens while drawing: the fill is worked out in the background once per base color and settings,
 * and shared by every cover. A cover with no saved color has a small copy of its picture read, once.
 */
@Composable
fun rememberCoverFillColor(cover: MangaCover, enabled: Boolean): Color? {
    // Covers that don't use the fill (most of them) do no work at all
    if (!enabled) return null
    val context = LocalContext.current
    val settings = LocalCoverFillSettings.current ?: readCoverFillSettings()

    var seed by remember(cover.mangaId) {
        mutableStateOf(
            CoverFillCache.seeds[cover.mangaId]
                ?: cover.dominantCoverColors?.first
                ?: cover.vibrantCoverColor,
        )
    }
    var fill by remember(cover.mangaId, settings) {
        mutableStateOf(seed?.let { CoverFillCache.fills[it to settings] })
    }

    LaunchedEffect(cover.mangaId, settings) {
        val base = seed ?: withIOContext {
            runCatching {
                // A small copy is enough to find the cover's color, and far quicker to read
                val request = ImageRequest.Builder(context)
                    .data(cover)
                    .size(SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
                val bitmap = result.image.asDrawable(context.resources).getBitmapOrNull() ?: return@runCatching null
                Palette.from(bitmap).generate().getBestColor()
            }.getOrNull()
        } ?: return@LaunchedEffect
        CoverFillCache.seeds.putIfAbsent(cover.mangaId, base)
        seed = base
        if (fill == null) fill = withContext(Dispatchers.Default) { CoverFillCache.fill(base, settings) }
    }

    return fill?.let { Color(it) }
}

private const val SAMPLE_SIZE = 96
