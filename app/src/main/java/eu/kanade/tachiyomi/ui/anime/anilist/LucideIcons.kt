package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * KMK: The thin, rounded line icons Shiru and Hayase use (Lucide, ISC licence), drawn as Compose vectors. They take
 * the tint of the Icon they're in, like any other icon.
 */
object LucideIcons {

    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = "Lucide.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    /** "T": the Title box */
    val Type by lazy { icon("Type", "M4 7V4h16v3", "M9 20h6", "M12 4v16") }

    /** "#": genres and tags */
    val Hash by lazy { icon("Hash", "M4 9h16", "M4 15h16", "M10 3L8 21", "M16 3l-2 18") }

    val Calendar by lazy {
        icon(
            "Calendar",
            "M8 2v4",
            "M16 2v4",
            "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M3 10h18",
        )
    }

    /** A TV with its antenna: TV series and the Format box */
    val Tv by lazy {
        icon("Tv", "M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M17 2l-5 5-5-5")
    }

    /** A screen with a play mark: the Status box */
    val MonitorPlay by lazy {
        icon(
            "MonitorPlay",
            "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M12 17v4",
            "M8 21h8",
            "M10 7.5l5 2.5-5 2.5z",
        )
    }

    /** Lines getting shorter with an arrow down: Sort */
    val SortDown by lazy {
        icon("SortDown", "M3 16l4 4 4-4", "M7 20V4", "M11 4h10", "M11 8h7", "M11 12h4")
    }

    val Film by lazy {
        icon(
            "Film",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M7 3v18",
            "M3 7.5h4",
            "M3 12h18",
            "M3 16.5h4",
            "M17 3v18",
            "M17 7.5h4",
            "M17 16.5h4",
        )
    }

    val Music by lazy { icon("Music", "M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)) }

    val Disc by lazy { icon("Disc", circle(12f, 12f, 10f), circle(12f, 12f, 2f)) }

    val Globe by lazy {
        icon("Globe", circle(12f, 12f, 10f), "M12 2a14.5 14.5 0 0 0 0 20a14.5 14.5 0 0 0 0-20", "M2 12h20")
    }

    val BookOpen by lazy {
        icon(
            "BookOpen",
            "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z",
            "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z",
        )
    }

    /** A book with lines of text: novels */
    val BookText by lazy {
        icon(
            "BookText",
            "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20",
            "M8 11h8",
            "M8 7h6",
        )
    }

    /** A single page: one shots */
    val File by lazy {
        icon("File", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4")
    }
}
