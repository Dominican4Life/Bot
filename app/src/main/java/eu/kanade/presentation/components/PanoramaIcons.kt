package eu.kanade.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.PanoramaStyle

/**
 * KMK: Icons for the three Panorama styles, drawn on a 24 × 24 grid like Material icons (tinted by [Icon]).
 * The outlined card is a normal book cover, the filled shape is the wide cover.
 */
object PanoramaIcons {

    /** A book-cover frame, the wide cover filling its middle and a light tint above and below it */
    val ColorFill: ImageVector by lazy {
        icon("PanoramaColorFill") {
            outline(x = 6f, y = 2f, w = 12f, h = 20f)
            tint(x = 7.5f, y = 3.5f, w = 9f, h = 4.5f)
            fill(x = 7.5f, y = 8.5f, w = 9f, h = 7f)
            tint(x = 7.5f, y = 16f, w = 9f, h = 4.5f)
        }
    }

    /** A book cover next to a wide cover of the same width, so shorter */
    val FitWidth: ImageVector by lazy {
        icon("PanoramaFitWidth") {
            outline(x = 2f, y = 3f, w = 9f, h = 15f)
            fill(x = 13f, y = 3f, w = 9f, h = 6f)
        }
    }

    /** A book cover next to a wide cover of the same height, twice as wide */
    val Wide: ImageVector by lazy {
        icon("PanoramaWide") {
            outline(x = 1.5f, y = 5f, w = 7f, h = 14f)
            fill(x = 10.5f, y = 5f, w = 12f, h = 14f)
        }
    }

    fun of(style: PanoramaStyle): ImageVector = when (style) {
        PanoramaStyle.FILL -> ColorFill
        PanoramaStyle.FIT -> FitWidth
        PanoramaStyle.WIDE -> Wide
    }

    private class Shapes(val builder: ImageVector.Builder) {
        fun outline(x: Float, y: Float, w: Float, h: Float) {
            builder.path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineJoin = StrokeJoin.Round,
            ) { rect(x, y, w, h) }
        }

        fun fill(x: Float, y: Float, w: Float, h: Float) {
            builder.path(fill = SolidColor(Color.Black)) { rect(x, y, w, h) }
        }

        fun tint(x: Float, y: Float, w: Float, h: Float) {
            builder.path(fill = SolidColor(Color.Black), fillAlpha = 0.35f) { rect(x, y, w, h) }
        }

        private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
            moveTo(x, y)
            horizontalLineTo(x + w)
            verticalLineTo(y + h)
            horizontalLineTo(x)
            close()
        }
    }

    private fun icon(name: String, block: Shapes.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).also { Shapes(it).block() }.build()
}
