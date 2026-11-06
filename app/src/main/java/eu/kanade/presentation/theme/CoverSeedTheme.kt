package eu.kanade.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Re-colors [content] from a cover's [seedColor], like the details page does, or keeps the
 * current colors when it's null.
 *
 * Unlike [TachiyomiTheme], switching between a color and null doesn't rebuild [content], so screens
 * keep their scroll position and other state when the colors change.
 */
@Composable
fun CoverSeedTheme(
    seedColor: Color?,
    content: @Composable () -> Unit,
) {
    val baseScheme = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val seededScheme = remember(seedColor, isDark) {
        seedColor?.let {
            dynamicColorScheme(
                seedColor = it,
                isDark = isDark,
                isAmoled = isDark && uiPreferences.themeDarkAmoled().get(),
                style = uiPreferences.themeCoverBasedStyle().get(),
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
            )
        }
    }
    // KMK (r135): nothing previewed / a colourless cover: the app colour from around this screen
    val appAccent = tachiyomi.presentation.core.components.LocalGlassAccent.current
    MaterialTheme(
        colorScheme = seededScheme ?: baseScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        // KMK (r133): glass controls light up in the previewed cover's colour (gold again when nothing is previewed)
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                tachiyomi.presentation.core.components.LocalGlassAccent provides
                    tachiyomi.presentation.core.components.glassAccentFor(
                            seedColor,
                            MaterialTheme.colorScheme.primary,
                            fallback = appAccent,
                        ),
                content = content,
            )
        },
    )
}
