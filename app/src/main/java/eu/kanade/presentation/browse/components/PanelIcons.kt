package eu.kanade.presentation.browse.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * KMK: Icons for the "Panels" option in the browse display menu, drawn in the same 24dp outlined style as
 * Material icons: a screen outline with covers in the middle, and filled side panels for the ones shown.
 */
object PanelIcons {
    val LeftOnly: ImageVector by lazy { build("PanelsLeft", left = true, right = false) }
    val Both: ImageVector by lazy { build("PanelsBoth", left = true, right = true) }
    val None: ImageVector by lazy { build("PanelsNone", left = false, right = false) }

    private fun build(name: String, left: Boolean, right: Boolean): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Screen outline
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                roundRect(3f, 5f, 21f, 19f, 2f)
            }
            // Side panels
            path(fill = SolidColor(Color.Black)) {
                if (left) rect(5f, 7f, 8.5f, 17f)
                if (right) rect(15.5f, 7f, 19f, 17f)
            }
            // Covers in the middle
            path(fill = SolidColor(Color.Black)) {
                if (left || right) {
                    rect(10f, 7.5f, 14f, 11.5f)
                    rect(10f, 12.5f, 14f, 16.5f)
                } else {
                    rect(6f, 7.5f, 11f, 11.5f)
                    rect(13f, 7.5f, 18f, 11.5f)
                    rect(6f, 12.5f, 11f, 16.5f)
                    rect(13f, 12.5f, 18f, 16.5f)
                }
            }
        }.build()
    }

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
        moveTo(l, t)
        lineTo(r, t)
        lineTo(r, b)
        lineTo(l, b)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
        moveTo(l + radius, t)
        lineTo(r - radius, t)
        arcToRelative(radius, radius, 0f, false, true, radius, radius)
        lineTo(r, b - radius)
        arcToRelative(radius, radius, 0f, false, true, -radius, radius)
        lineTo(l + radius, b)
        arcToRelative(radius, radius, 0f, false, true, -radius, -radius)
        lineTo(l, t + radius)
        arcToRelative(radius, radius, 0f, false, true, radius, -radius)
        close()
    }
}
