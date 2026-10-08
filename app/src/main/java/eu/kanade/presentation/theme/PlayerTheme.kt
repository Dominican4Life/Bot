package eu.kanade.presentation.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable

// KMK: ripple used by the anime player controls (from AniZen)
private const val RIPPLE_DRAGGED_ALPHA = .5f
private const val RIPPLE_FOCUSED_ALPHA = .6f
private const val RIPPLE_HOVERED_ALPHA = .4f
private const val RIPPLE_PRESSED_ALPHA = .6f

@OptIn(ExperimentalMaterial3Api::class)
val playerRippleConfiguration
    @Composable get() = RippleConfiguration(
        color = MaterialTheme.colorScheme.primaryContainer,
        rippleAlpha = RippleAlpha(
            draggedAlpha = RIPPLE_DRAGGED_ALPHA,
            focusedAlpha = RIPPLE_FOCUSED_ALPHA,
            hoveredAlpha = RIPPLE_HOVERED_ALPHA,
            pressedAlpha = RIPPLE_PRESSED_ALPHA,
        ),
    )
