package eu.kanade.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * KMK (r132): the app's own icon set for the glass design: solid shapes with a lighter second layer (the same family
 * as Chapters / Preview / Suggestions). Drawn on a 24 × 24 grid and tinted by `Icon`, like Material icons. Checks and
 * marks are cut out of the solid shape (even-odd), so they show the colour behind the icon.
 * Generated from the mockups; see docs/fork-guide/05-images-previews-reader.md (r132).
 */
object GlassIcons {
    val Chapters: ImageVector by lazy {
        icon("Chapters") {
            line("M3.5 4h8", width = 2.4f)
            fill("M3.1 9.2a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z")
            fill("M9.3 7.9h10.4a1.3 1.3 0 0 1 1.3 1.3v0a1.3 1.3 0 0 1 -1.3 1.3h-10.4a1.3 1.3 0 0 1 -1.3 -1.3v-0a1.3 1.3 0 0 1 1.3 -1.3z")
            fill("M3.1 14.2a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", alpha = 0.7f)
            fill("M9.3 12.9h10.4a1.3 1.3 0 0 1 1.3 1.3v0a1.3 1.3 0 0 1 -1.3 1.3h-10.4a1.3 1.3 0 0 1 -1.3 -1.3v-0a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.7f)
            fill("M3.1 19.2a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", alpha = 0.45f)
            fill("M9.3 17.9h6.4a1.3 1.3 0 0 1 1.3 1.3v0a1.3 1.3 0 0 1 -1.3 1.3h-6.4a1.3 1.3 0 0 1 -1.3 -1.3v-0a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.45f)
        }
    }

    val Preview: ImageVector by lazy {
        icon("Preview") {
            line("M3.5 4h8", width = 2.4f)
            fill("M4.2 7.5h2.8a1.2 1.2 0 0 1 1.2 1.2v5a1.2 1.2 0 0 1 -1.2 1.2h-2.8a1.2 1.2 0 0 1 -1.2 -1.2v-5a1.2 1.2 0 0 1 1.2 -1.2z")
            fill("M10.6 7.5h2.8a1.2 1.2 0 0 1 1.2 1.2v5a1.2 1.2 0 0 1 -1.2 1.2h-2.8a1.2 1.2 0 0 1 -1.2 -1.2v-5a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.7f)
            fill("M17 7.5h2.8a1.2 1.2 0 0 1 1.2 1.2v5a1.2 1.2 0 0 1 -1.2 1.2h-2.8a1.2 1.2 0 0 1 -1.2 -1.2v-5a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.45f)
            fill("M4.2 16.3h2.8a1.2 1.2 0 0 1 1.2 1.2v2.2a1.2 1.2 0 0 1 -1.2 1.2h-2.8a1.2 1.2 0 0 1 -1.2 -1.2v-2.2a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.45f)
            fill("M10.6 16.3h2.8a1.2 1.2 0 0 1 1.2 1.2v2.2a1.2 1.2 0 0 1 -1.2 1.2h-2.8a1.2 1.2 0 0 1 -1.2 -1.2v-2.2a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.3f)
            fill("M17 16.3h2.8a1.2 1.2 0 0 1 1.2 1.2v2.2a1.2 1.2 0 0 1 -1.2 1.2h-2.8a1.2 1.2 0 0 1 -1.2 -1.2v-2.2a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.2f)
        }
    }

    val Suggestions: ImageVector by lazy {
        icon("Suggestions") {
            fill("M4.2 5h2.6a1.2 1.2 0 0 1 1.2 1.2v7.1a1.2 1.2 0 0 1 -1.2 1.2h-2.6a1.2 1.2 0 0 1 -1.2 -1.2v-7.1a1.2 1.2 0 0 1 1.2 -1.2z")
            fill("M10.7 5h2.6a1.2 1.2 0 0 1 1.2 1.2v7.1a1.2 1.2 0 0 1 -1.2 1.2h-2.6a1.2 1.2 0 0 1 -1.2 -1.2v-7.1a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.7f)
            fill("M17.2 5h2.6a1.2 1.2 0 0 1 1.2 1.2v7.1a1.2 1.2 0 0 1 -1.2 1.2h-2.6a1.2 1.2 0 0 1 -1.2 -1.2v-7.1a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.45f)
            line("M3 19h18", width = 1.8f)
        }
    }

    val Download: ImageVector by lazy {
        icon("Download") {
            line("M12 3v9.5", width = 2.4f)
            line("M7.5 9l4.5 4.5L16.5 9", width = 2.4f)
            fill("M4.7 17h14.6a1.7 1.7 0 0 1 1.7 1.7v0a1.7 1.7 0 0 1 -1.7 1.7h-14.6a1.7 1.7 0 0 1 -1.7 -1.7v-0a1.7 1.7 0 0 1 1.7 -1.7z", alpha = 0.55f)
        }
    }

    val Filter: ImageVector by lazy {
        icon("Filter") {
            fill("M4.4 5h15.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-15.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z")
            fill("M7.4 10.6h9.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-9.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.7f)
            fill("M10.4 16.2h3.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-3.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.45f)
        }
    }

    val InLibrary: ImageVector by lazy {
        icon("InLibrary") {
            fill("M12 20.5s-8-4.7-8-10.6A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 5.9-8 10.6-8 10.6z", alpha = 0.55f)
            fill("M4 9.9A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 1.2-.3 2.3-.9 3.4H4.9C4.3 12.2 4 11 4 9.9z")
        }
    }

    val AddToLibrary: ImageVector by lazy {
        icon("AddToLibrary") {
            line("M12 20.5s-8-4.7-8-10.6A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 5.9-8 10.6-8 10.6z", width = 1.8f)
            line("M12 10.5v5M9.5 13h5", width = 1.9f)
        }
    }

    val NextUpdate: ImageVector by lazy {
        icon("NextUpdate") {
            fill("M6.2 2.5h11.6a1.2 1.2 0 0 1 1.2 1.2v0a1.2 1.2 0 0 1 -1.2 1.2h-11.6a1.2 1.2 0 0 1 -1.2 -1.2v-0a1.2 1.2 0 0 1 1.2 -1.2z")
            fill("M6.2 19.1h11.6a1.2 1.2 0 0 1 1.2 1.2v0a1.2 1.2 0 0 1 -1.2 1.2h-11.6a1.2 1.2 0 0 1 -1.2 -1.2v-0a1.2 1.2 0 0 1 1.2 -1.2z")
            fill("M7 5h10c0 3.6-3 5.4-4.1 7 1.1 1.6 4.1 3.4 4.1 7H7c0-3.6 3-5.4 4.1-7C10 10.4 7 8.6 7 5z", alpha = 0.3f)
            fill("M9.3 8h5.4c-.7 1.2-1.9 2.1-2.7 3-.8-.9-2-1.8-2.7-3zM8 19c.4-2.2 2.4-3.6 4-4.6 1.6 1 3.6 2.4 4 4.6z")
        }
    }

    val NextUpdateOff: ImageVector by lazy {
        icon("NextUpdateOff") {
            fill("M6.2 2.5h11.6a1.2 1.2 0 0 1 1.2 1.2v0a1.2 1.2 0 0 1 -1.2 1.2h-11.6a1.2 1.2 0 0 1 -1.2 -1.2v-0a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.45f)
            fill("M6.2 19.1h11.6a1.2 1.2 0 0 1 1.2 1.2v0a1.2 1.2 0 0 1 -1.2 1.2h-11.6a1.2 1.2 0 0 1 -1.2 -1.2v-0a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.45f)
            fill("M7 5h10c0 3.6-3 5.4-4.1 7 1.1 1.6 4.1 3.4 4.1 7H7c0-3.6 3-5.4 4.1-7C10 10.4 7 8.6 7 5z", alpha = 0.3f)
            line("M4 20L20 4", width = 2.4f)
        }
    }

    val Tracker: ImageVector by lazy {
        icon("Tracker") {
            line("M12 3.4a8.6 8.6 0 1 1 0 17.2a8.6 8.6 0 1 1 0-17.2", width = 2.6f, alpha = 0.35f)
            line("M12 3.4a8.6 8.6 0 1 1-8.2 11.2", width = 2.6f)
            line("M8.6 12.2l2.3 2.3 4.6-4.8", width = 2.2f)
        }
    }

    val TrackerAdd: ImageVector by lazy {
        icon("TrackerAdd") {
            line("M12 3.4a8.6 8.6 0 1 1 0 17.2a8.6 8.6 0 1 1 0-17.2", width = 2.6f, alpha = 0.35f)
            line("M12 3.4a8.6 8.6 0 0 1 8.6 8.6", width = 2.6f)
            line("M12 8.4v7.2M8.4 12h7.2", width = 2.2f)
        }
    }

    val WebView: ImageVector by lazy {
        icon("WebView") {
            fill("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z", alpha = 0.38f)
            line("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18", width = 1.8f)
            line("M3.5 12h17M12 3c2.6 2.6 2.6 15.4 0 18M12 3c-2.6 2.6-2.6 15.4 0 18", width = 1.6f)
        }
    }

    val Bookmark: ImageVector by lazy {
        icon("Bookmark") {
            fill("M5.5 7.5h13v13.2l-6.5-4.2-6.5 4.2z")
            fill("M6.5 3.5h11a1 1 0 0 1 1 1v3h-13v-3a1 1 0 0 1 1-1z", alpha = 0.45f)
        }
    }

    val BookmarkRemove: ImageVector by lazy {
        icon("BookmarkRemove") {
            fill("M5.5 7.5h13v13.2l-6.5-4.2-6.5 4.2z", alpha = 0.45f)
            fill("M6.5 3.5h11a1 1 0 0 1 1 1v3h-13v-3a1 1 0 0 1 1-1z", alpha = 0.3f)
            line("M3.5 20.5L20.5 3.5", width = 2.4f)
        }
    }

    val Read: ImageVector by lazy {
        icon("Read") {
            fill("M2.5 12c2.6-4.4 5.8-6.6 9.5-6.6s6.9 2.2 9.5 6.6c-2.6 4.4-5.8 6.6-9.5 6.6S5.1 16.4 2.5 12z", alpha = 0.4f)
            fill("M8.4 12a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0z")
        }
    }

    val Unread: ImageVector by lazy {
        icon("Unread") {
            fill("M2.5 12c2.6-4.4 5.8-6.6 9.5-6.6s6.9 2.2 9.5 6.6c-2.6 4.4-5.8 6.6-9.5 6.6S5.1 16.4 2.5 12z", alpha = 0.3f)
            fill("M8.4 12a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0z", alpha = 0.6f)
            line("M4 20L20 4", width = 2.4f)
        }
    }

    val Arrow: ImageVector by lazy {
        icon("Arrow") {
            line("M4.5 12h13.5", width = 2.5f)
            line("M13 6.5l5.5 5.5-5.5 5.5", width = 2.5f)
        }
    }

    val ChapterDownload: ImageVector by lazy {
        icon("ChapterDownload") {
            line("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18", width = 2f, alpha = 0.35f)
            line("M12 6.8v7.4", width = 2.3f)
            line("M8.8 11.4l3.2 3.2 3.2-3.2", width = 2.3f)
            fill("M8.9 16.8h6.2a0.9 0.9 0 0 1 0.9 0.9v0a0.9 0.9 0 0 1 -0.9 0.9h-6.2a0.9 0.9 0 0 1 -0.9 -0.9v-0a0.9 0.9 0 0 1 0.9 -0.9z", alpha = 0.6f)
        }
    }

    val ChapterQueued: ImageVector by lazy {
        icon("ChapterQueued") {
            line("M12.63 3.02A9 9 0 0 1 14.93 3.49M17.03 4.54A9 9 0 0 1 18.79 6.1M20.09 8.05A9 9 0 0 1 20.83 10.28M20.98 12.63A9 9 0 0 1 20.51 14.93M19.46 17.03A9 9 0 0 1 17.9 18.79M15.95 20.09A9 9 0 0 1 13.72 20.83M11.37 20.98A9 9 0 0 1 9.07 20.51M6.97 19.46A9 9 0 0 1 5.21 17.9M3.91 15.95A9 9 0 0 1 3.17 13.72M3.02 11.37A9 9 0 0 1 3.49 9.07M4.54 6.97A9 9 0 0 1 6.1 5.21M8.05 3.91A9 9 0 0 1 10.28 3.17", width = 2f, alpha = 0.55f)
            line("M12 7.5v5l3 2", width = 2.1f)
        }
    }

    val ChapterDownloadArrow: ImageVector by lazy {
        icon("ChapterDownloadArrow") {
            line("M12 6.8v7.4", width = 2.3f)
            line("M8.8 11.4l3.2 3.2 3.2-3.2", width = 2.3f)
        }
    }

    val ChapterDownloaded: ImageVector by lazy {
        icon("ChapterDownloaded") {
            fill("M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0zM6.95 13.15L9.75 15.95A1.2 1.2 0 0 0 11.46 15.93L17.06 10.13A1.2 1.2 0 0 0 15.34 8.47L10.58 13.39L8.65 11.45A1.2 1.2 0 0 0 6.95 13.15Z", evenOdd = true)
        }
    }

    val ChapterDownloadError: ImageVector by lazy {
        icon("ChapterDownloadError") {
            fill("M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0zM12 5.8h0a1.2 1.2 0 0 1 1.2 1.2v6a1.2 1.2 0 0 1 -1.2 1.2h-0a1.2 1.2 0 0 1 -1.2 -1.2v-6a1.2 1.2 0 0 1 1.2 -1.2zM10.6 16.6a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0z", evenOdd = true)
        }
    }

    val SelectAll: ImageVector by lazy {
        icon("SelectAll") {
            fill("M10 2.5h8a3.5 3.5 0 0 1 3.5 3.5v8a3.5 3.5 0 0 1 -3.5 3.5h-8a3.5 3.5 0 0 1 -3.5 -3.5v-8a3.5 3.5 0 0 1 3.5 -3.5z", alpha = 0.38f)
            fill("M6 6.5h8a3.5 3.5 0 0 1 3.5 3.5v8a3.5 3.5 0 0 1 -3.5 3.5h-8a3.5 3.5 0 0 1 -3.5 -3.5v-8a3.5 3.5 0 0 1 3.5 -3.5zM5.52 14.78L8.32 17.58A1.1 1.1 0 0 0 9.89 17.56L15.09 12.16A1.1 1.1 0 0 0 13.51 10.64L9.09 15.23L7.08 13.22A1.1 1.1 0 0 0 5.52 14.78Z", evenOdd = true)
        }
    }

    val InvertSelection: ImageVector by lazy {
        icon("InvertSelection") {
            fill("M5.1 9.5h4.8a2.6 2.6 0 0 1 2.6 2.6v4.8a2.6 2.6 0 0 1 -2.6 2.6h-4.8a2.6 2.6 0 0 1 -2.6 -2.6v-4.8a2.6 2.6 0 0 1 2.6 -2.6zM4.13 15.27L6.13 17.27A0.95 0.95 0 0 0 7.48 17.26L10.88 13.76A0.95 0.95 0 0 0 9.52 12.44L6.79 15.25L5.47 13.93A0.95 0.95 0 0 0 4.13 15.27Z", evenOdd = true)
            fill("M14.6 2.5h4.3a2.6 2.6 0 0 1 2.6 2.6v4.3a2.6 2.6 0 0 1 -2.6 2.6h-4.3a2.6 2.6 0 0 1 -2.6 -2.6v-4.3a2.6 2.6 0 0 1 2.6 -2.6z", alpha = 0.38f)
            line("M15.5 21a5.5 5.5 0 0 0 5-5.4", width = 1.9f)
            line("M18.4 17.4l2.1-2 2.1 2", width = 1.9f)
        }
    }

    val Display: ImageVector by lazy {
        icon("Display") {
            fill("M4.3 2.5h2.2a1.3 1.3 0 0 1 1.3 1.3v6a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-6a1.3 1.3 0 0 1 1.3 -1.3z")
            fill("M10.7 2.5h2.2a1.3 1.3 0 0 1 1.3 1.3v6a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-6a1.3 1.3 0 0 1 1.3 -1.3z")
            fill("M17.1 2.5h2.2a1.3 1.3 0 0 1 1.3 1.3v6a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-6a1.3 1.3 0 0 1 1.3 -1.3z")
            fill("M4.3 12.7h2.2a1.3 1.3 0 0 1 1.3 1.3v6a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-6a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.45f)
            fill("M10.7 12.7h2.2a1.3 1.3 0 0 1 1.3 1.3v6a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-6a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.45f)
            fill("M17.1 12.7h2.2a1.3 1.3 0 0 1 1.3 1.3v6a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-6a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.45f)
        }
    }

    val LayoutComfortable: ImageVector by lazy {
        icon("LayoutComfortable") {
            fill("M4.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v6.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-6.8a1.6 1.6 0 0 1 1.6 -1.6z")
            fill("M14.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v6.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-6.8a1.6 1.6 0 0 1 1.6 -1.6z")
            fill("M4.1 14.5h5.8a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-5.8a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.5f)
            fill("M14.1 14.5h5.8a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-5.8a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.5f)
            fill("M4 18.5h3a1 1 0 0 1 1 1v0a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v0a1 1 0 0 1 1 -1z", alpha = 0.3f)
            fill("M14 18.5h3a1 1 0 0 1 1 1v0a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v0a1 1 0 0 1 1 -1z", alpha = 0.3f)
        }
    }

    val LayoutCompact: ImageVector by lazy {
        icon("LayoutCompact") {
            fill("M4.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v14.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-14.8a1.6 1.6 0 0 1 1.6 -1.6zM5.2 16h3.6a1 1 0 0 1 1 1v0a1 1 0 0 1 -1 1h-3.6a1 1 0 0 1 -1 -1v0a1 1 0 0 1 1 -1zM14.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v14.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-14.8a1.6 1.6 0 0 1 1.6 -1.6zM15.2 16h3.6a1 1 0 0 1 1 1v0a1 1 0 0 1 -1 1h-3.6a1 1 0 0 1 -1 -1v0a1 1 0 0 1 1 -1z", evenOdd = true)
        }
    }

    val LayoutCovers: ImageVector by lazy {
        icon("LayoutCovers") {
            fill("M4.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v14.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-14.8a1.6 1.6 0 0 1 1.6 -1.6z")
            fill("M14.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v14.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-14.8a1.6 1.6 0 0 1 1.6 -1.6z", alpha = 0.6f)
        }
    }

    val LayoutList: ImageVector by lazy {
        icon("LayoutList") {
            fill("M4.1 3h2.4a1.1 1.1 0 0 1 1.1 1.1v2.4a1.1 1.1 0 0 1 -1.1 1.1h-2.4a1.1 1.1 0 0 1 -1.1 -1.1v-2.4a1.1 1.1 0 0 1 1.1 -1.1z")
            fill("M10.7 4.2h9.2a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-9.2a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z")
            fill("M4.1 9.7h2.4a1.1 1.1 0 0 1 1.1 1.1v2.4a1.1 1.1 0 0 1 -1.1 1.1h-2.4a1.1 1.1 0 0 1 -1.1 -1.1v-2.4a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.65f)
            fill("M10.7 10.9h9.2a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-9.2a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.65f)
            fill("M4.1 16.4h2.4a1.1 1.1 0 0 1 1.1 1.1v2.4a1.1 1.1 0 0 1 -1.1 1.1h-2.4a1.1 1.1 0 0 1 -1.1 -1.1v-2.4a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.35f)
            fill("M10.7 17.6h9.2a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-9.2a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.35f)
        }
    }

    val PanoramaFitWidth: ImageVector by lazy {
        icon("PanoramaFitWidth") {
            fill("M4 3h5a2 2 0 0 1 2 2v11a2 2 0 0 1 -2 2h-5a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2z", alpha = 0.38f)
            fill("M14.6 3h5.8a1.6 1.6 0 0 1 1.6 1.6v2.8a1.6 1.6 0 0 1 -1.6 1.6h-5.8a1.6 1.6 0 0 1 -1.6 -1.6v-2.8a1.6 1.6 0 0 1 1.6 -1.6z")
        }
    }

    val PanoramaColorFill: ImageVector by lazy {
        icon("PanoramaColorFill") {
            fill("M8.2 2h7.6a2.2 2.2 0 0 1 2.2 2.2v15.6a2.2 2.2 0 0 1 -2.2 2.2h-7.6a2.2 2.2 0 0 1 -2.2 -2.2v-15.6a2.2 2.2 0 0 1 2.2 -2.2z", alpha = 0.38f)
            fill("M7.4 8.5h9.2a1.4 1.4 0 0 1 1.4 1.4v4.2a1.4 1.4 0 0 1 -1.4 1.4h-9.2a1.4 1.4 0 0 1 -1.4 -1.4v-4.2a1.4 1.4 0 0 1 1.4 -1.4z")
        }
    }

    val PanoramaWide: ImageVector by lazy {
        icon("PanoramaWide") {
            fill("M3.5 5h3a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-3a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z", alpha = 0.38f)
            fill("M12.5 5h8a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z")
        }
    }

    private class Shapes(val builder: ImageVector.Builder) {
        fun fill(d: String, alpha: Float = 1f, evenOdd: Boolean = false) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.Black),
                fillAlpha = alpha,
            )
        }

        fun line(d: String, width: Float, alpha: Float = 1f) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeAlpha = alpha,
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }

    private fun icon(name: String, block: Shapes.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Glass.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply { Shapes(this).block() }.build()
}
