package eu.kanade.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * KMK (r132, more in r133): the app's own icon set for the glass design: solid shapes with a lighter second layer (the same family
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

    val Details: ImageVector by lazy {
        icon("Details") {
            fill("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0zM10.8 10.6h2.4v6.6h-2.4zM12 6.2a1.45 1.45 0 1 0 0 2.9a1.45 1.45 0 1 0 0-2.9z", evenOdd = true)
        }
    }

    val UpdateLibrary: ImageVector by lazy {
        icon("UpdateLibrary") {
            line("M19.5 10.5A8 8 0 0 0 5.2 7.4L4 9", width = 1.8f)
            line("M4 4.5V9h4.5", width = 1.8f)
            line("M4.5 13.5a8 8 0 0 0 14.3 3.1L20 15", width = 1.8f)
            line("M20 19.5V15h-4.5", width = 1.8f)
        }
    }

    val RandomTitle: ImageVector by lazy {
        icon("RandomTitle") {
            line("M7.5 3.5h9a4 4 0 0 1 4 4v9a4 4 0 0 1 -4 4h-9a4 4 0 0 1 -4 -4v-9a4 4 0 0 1 4 -4z", width = 1.8f)
            fill("M6.8 8.3a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z")
            fill("M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z")
            fill("M14.2 15.7a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z")
        }
    }

    val ReindexDownloads: ImageVector by lazy {
        icon("ReindexDownloads") {
            line("M3 7.5A1.5 1.5 0 0 1 4.5 6H9l2 2h8.5A1.5 1.5 0 0 1 21 9.5v8A1.5 1.5 0 0 1 19.5 19h-15A1.5 1.5 0 0 1 3 17.5z", width = 1.8f)
            line("M14.8 13.5a2.8 2.8 0 1 1-.8-2", width = 1.8f)
            line("M14.4 9.6v2h-2", width = 1.8f)
        }
    }

    val RefreshCovers: ImageVector by lazy {
        icon("RefreshCovers") {
            line("M14 19.5H5a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2V11", width = 1.8f)
            line("M5.5 15l2.6-3 2.2 2.2 1.2-1.2", width = 1.8f)
            fill("M6.9 7.2a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0z")
            line("M21 17a3.3 3.3 0 1 1-1-2.4", width = 1.8f)
            line("M20.4 12.6v2.1h-2.1", width = 1.8f)
        }
    }

    val SyncLibrary: ImageVector by lazy {
        icon("SyncLibrary") {
            line("M7 19a4.5 4.5 0 0 1-.7-8.95A6 6 0 0 1 17.6 9a5 5 0 0 1-.6 10z", width = 1.8f)
            line("M9.5 14l2.5-2.5 2.5 2.5M12 11.5V17", width = 1.8f)
        }
    }

    val ComingUp: ImageVector by lazy {
        icon("ComingUp") {
            line("M5.7 5h12.6a2.2 2.2 0 0 1 2.2 2.2v10.6a2.2 2.2 0 0 1 -2.2 2.2h-12.6a2.2 2.2 0 0 1 -2.2 -2.2v-10.6a2.2 2.2 0 0 1 2.2 -2.2z", width = 1.8f)
            line("M3.5 10h17M8 3v4M16 3v4", width = 1.8f)
            fill("M10.5 15a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z")
        }
    }

    val Category: ImageVector by lazy {
        icon("Category") {
            fill("M5 5.5h10.6a2 2 0 0 1 1.6.8l4.1 5.1a1 1 0 0 1 0 1.2l-4.1 5.1a2 2 0 0 1-1.6.8H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", alpha = 0.38f)
            line("M5 5.5h10.6a2 2 0 0 1 1.6.8l4.1 5.1a1 1 0 0 1 0 1.2l-4.1 5.1a2 2 0 0 1-1.6.8H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", width = 1.8f)
            fill("M13.9 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z")
        }
    }

    val Status: ImageVector by lazy {
        icon("Status") {
            line("M12 3.4a8.6 8.6 0 1 1 0 17.2a8.6 8.6 0 1 1 0-17.2", width = 2.4f, alpha = 0.35f)
            line("M12 3.4a8.6 8.6 0 0 1 8.6 8.6", width = 2.6f)
            line("M12 7.5V12l3 2", width = 2.2f)
        }
    }

    val Ungrouped: ImageVector by lazy {
        icon("Ungrouped") {
            fill("M4.8 3h3.9a1.8 1.8 0 0 1 1.8 1.8v3.9a1.8 1.8 0 0 1 -1.8 1.8h-3.9a1.8 1.8 0 0 1 -1.8 -1.8v-3.9a1.8 1.8 0 0 1 1.8 -1.8z")
            fill("M15.3 3h3.9a1.8 1.8 0 0 1 1.8 1.8v3.9a1.8 1.8 0 0 1 -1.8 1.8h-3.9a1.8 1.8 0 0 1 -1.8 -1.8v-3.9a1.8 1.8 0 0 1 1.8 -1.8z", alpha = 0.38f)
            fill("M4.8 13.5h3.9a1.8 1.8 0 0 1 1.8 1.8v3.9a1.8 1.8 0 0 1 -1.8 1.8h-3.9a1.8 1.8 0 0 1 -1.8 -1.8v-3.9a1.8 1.8 0 0 1 1.8 -1.8z", alpha = 0.38f)
            fill("M15.3 13.5h3.9a1.8 1.8 0 0 1 1.8 1.8v3.9a1.8 1.8 0 0 1 -1.8 1.8h-3.9a1.8 1.8 0 0 1 -1.8 -1.8v-3.9a1.8 1.8 0 0 1 1.8 -1.8z", alpha = 0.38f)
        }
    }

    val SortUp: ImageVector by lazy {
        icon("SortUp") {
            line("M12 19V5", width = 2.4f)
            line("M6.5 10.5L12 5l5.5 5.5", width = 2.4f)
        }
    }

    val SortDown: ImageVector by lazy {
        icon("SortDown") {
            line("M12 5v14", width = 2.4f)
            line("M6.5 13.5L12 19l5.5-5.5", width = 2.4f)
        }
    }

    val SortShuffle: ImageVector by lazy {
        icon("SortShuffle") {
            line("M19.5 10.5A8 8 0 0 0 5.2 7.4L4 9", width = 1.8f)
            line("M4 4.5V9h4.5", width = 1.8f)
            line("M4.5 13.5a8 8 0 0 0 14.3 3.1L20 15", width = 1.8f)
            line("M20 19.5V15h-4.5", width = 1.8f)
        }
    }

    val SortAlpha: ImageVector by lazy {
        icon("SortAlpha") {
            line("M3.5 19l5-14 5 14M5.6 14h5.8", width = 2f)
            line("M16 8h5M16 12h5M16 16h3.5", width = 1.8f, alpha = 0.5f)
        }
    }

    val SortTotal: ImageVector by lazy {
        icon("SortTotal") {
            fill("M4.4 4h15.2a1.4 1.4 0 0 1 1.4 1.4v1.4a1.4 1.4 0 0 1 -1.4 1.4h-15.2a1.4 1.4 0 0 1 -1.4 -1.4v-1.4a1.4 1.4 0 0 1 1.4 -1.4z")
            fill("M4.4 10h15.2a1.4 1.4 0 0 1 1.4 1.4v1.4a1.4 1.4 0 0 1 -1.4 1.4h-15.2a1.4 1.4 0 0 1 -1.4 -1.4v-1.4a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.6f)
            fill("M4.4 16h15.2a1.4 1.4 0 0 1 1.4 1.4v1.4a1.4 1.4 0 0 1 -1.4 1.4h-15.2a1.4 1.4 0 0 1 -1.4 -1.4v-1.4a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.35f)
        }
    }

    val SortLastRead: ImageVector by lazy {
        icon("SortLastRead") {
            fill("M2.5 12c2.6-4.4 5.8-6.6 9.5-6.6s6.9 2.2 9.5 6.6c-2.6 4.4-5.8 6.6-9.5 6.6S5.1 16.4 2.5 12z", alpha = 0.38f)
            fill("M8.7 12a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0 -6.6 0z")
        }
    }

    val SortUnread: ImageVector by lazy {
        icon("SortUnread") {
            fill("M5 5h9a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z", alpha = 0.38f)
            fill("M13.5 6.5a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z")
        }
    }

    val SortLatest: ImageVector by lazy {
        icon("SortLatest") {
            fill("M6 3h9a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z", alpha = 0.38f)
            line("M19.5 2.5v5M17 5h5", width = 1.8f)
        }
    }

    val SortFetched: ImageVector by lazy {
        icon("SortFetched") {
            line("M12 3v10", width = 2f)
            line("M8 9.5l4 4 4-4", width = 2f)
            fill("M4.6 16h14.8a1.6 1.6 0 0 1 1.6 1.6v1.3a1.6 1.6 0 0 1 -1.6 1.6h-14.8a1.6 1.6 0 0 1 -1.6 -1.6v-1.3a1.6 1.6 0 0 1 1.6 -1.6z", alpha = 0.5f)
        }
    }

    val SortAdded: ImageVector by lazy {
        icon("SortAdded") {
            fill("M5.2 5h13.6a2.2 2.2 0 0 1 2.2 2.2v10.6a2.2 2.2 0 0 1 -2.2 2.2h-13.6a2.2 2.2 0 0 1 -2.2 -2.2v-10.6a2.2 2.2 0 0 1 2.2 -2.2z", alpha = 0.38f)
            line("M8 3v4M16 3v4", width = 2f)
            line("M12 10.5v6M9 13.5h6", width = 2f)
        }
    }

    val SortScore: ImageVector by lazy {
        icon("SortScore") {
            fill("M12 3l2.7 5.6 6.1.8-4.5 4.2 1.1 6.1L12 16.8 6.6 19.7l1.1-6.1L3.2 9.4l6.1-.8z")
        }
    }

    val SortRandom: ImageVector by lazy {
        icon("SortRandom") {
            fill("M7.5 3.5h9a4 4 0 0 1 4 4v9a4 4 0 0 1 -4 4h-9a4 4 0 0 1 -4 -4v-9a4 4 0 0 1 4 -4z", alpha = 0.38f)
            fill("M6.6 8.3a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z")
            fill("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z")
            fill("M14 15.7a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z")
        }
    }

    val SortNumber: ImageVector by lazy {
        icon("SortNumber") {
            line("M9.5 3.5L7.5 20.5M16.5 3.5L14.5 20.5M4 8.5h16.5M3.5 15.5h16.5", width = 2f)
        }
    }

    val SortUpload: ImageVector by lazy {
        icon("SortUpload") {
            fill("M5.2 5h13.6a2.2 2.2 0 0 1 2.2 2.2v10.6a2.2 2.2 0 0 1 -2.2 2.2h-13.6a2.2 2.2 0 0 1 -2.2 -2.2v-10.6a2.2 2.2 0 0 1 2.2 -2.2z", alpha = 0.38f)
            line("M8 3v4M16 3v4", width = 2.2f)
            line("M12 17.5v-6.5M9 13.8l3-3 3 3", width = 2f)
        }
    }

    val ShowTitle: ImageVector by lazy {
        icon("ShowTitle") {
            fill("M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-10a3 3 0 0 1 3 -3z", alpha = 0.38f)
            line("M7 9.5h10M7 14.5h6.5", width = 2f)
        }
    }

    val SetDefault: ImageVector by lazy {
        icon("SetDefault") {
            line("M9 3.5h6l-.9 5.6 3.4 3.2v2H6.5v-2l3.4-3.2z", width = 1.9f)
            line("M12 14.3v6.2", width = 1.9f)
        }
    }

    val Reset: ImageVector by lazy {
        icon("Reset") {
            line("M4.8 13a7.5 7.5 0 1 0 2-6.3L4.5 9", width = 1.9f)
            line("M4.5 4.5V9H9", width = 1.9f)
        }
    }

    val Scanlators: ImageVector by lazy {
        icon("Scanlators") {
            fill("M8 11a3.2 3.2 0 1 0 0-6.4A3.2 3.2 0 0 0 8 11z")
            fill("M2.5 19.5c0-3 2.5-5.3 5.5-5.3s5.5 2.3 5.5 5.3z")
            fill("M16.5 10.5a2.7 2.7 0 1 0 0-5.4 2.7 2.7 0 0 0 0 5.4zM14.5 13.6a4.6 4.6 0 0 1 7 3.9v2h-6.4", alpha = 0.45f)
        }
    }

    val Play: ImageVector by lazy {
        icon("Play") {
            fill("M8.6 4.8c-.9-.6-2.1 0-2.1 1.1v12.2c0 1.1 1.2 1.7 2.1 1.1l9.3-6.1c.8-.5.8-1.7 0-2.2z")
        }
    }

    val ReadOff: ImageVector by lazy {
        icon("ReadOff") {
            fill("M2.5 12c2.6-4.4 5.8-6.6 9.5-6.6s6.9 2.2 9.5 6.6c-2.6 4.4-5.8 6.6-9.5 6.6S5.1 16.4 2.5 12z", alpha = 0.3f)
            line("M2.5 12c2.6-4.4 5.8-6.6 9.5-6.6s6.9 2.2 9.5 6.6c-2.6 4.4-5.8 6.6-9.5 6.6S5.1 16.4 2.5 12z", width = 1.4f, alpha = 0.7f)
            fill("M8.4 12a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0z", alpha = 0.75f)
        }
    }

    val BookmarkOff: ImageVector by lazy {
        icon("BookmarkOff") {
            fill("M6.5 3.5h11a1 1 0 0 1 1 1v16.2l-6.5-4.2-6.5 4.2V4.5a1 1 0 0 1 1-1z", alpha = 0.3f)
            line("M6.5 3.5h11a1 1 0 0 1 1 1v16.2l-6.5-4.2-6.5 4.2V4.5a1 1 0 0 1 1-1z", width = 1.7f, alpha = 0.85f)
        }
    }

    val PreviewSize: ImageVector by lazy {
        icon("PreviewSize") {
            line("M5.2 4h13.6a3.2 3.2 0 0 1 3.2 3.2v9.6a3.2 3.2 0 0 1 -3.2 3.2h-13.6a3.2 3.2 0 0 1 -3.2 -3.2v-9.6a3.2 3.2 0 0 1 3.2 -3.2z", width = 1.8f)
            line("M6 11V8.5h3.5M18 13v2.5h-3.5", width = 2.4f)
        }
    }

    // KMK (r137): sort direction (SF, arrow on the left): solid arrow, bars fading from long to short
    val SortNewest: ImageVector by lazy {
        icon("SortNewest") {
            line("M5.5 4.5v14.5M2.5 16l3 3 3-3", width = 2.1f)
            fill("M11.4 5h8.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-8.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z")
            fill("M11.4 10.6h5.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-5.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.7f)
            fill("M11.4 16.2h2.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-2.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.45f)
        }
    }

    val SortOldest: ImageVector by lazy {
        icon("SortOldest") {
            line("M5.5 19.5V5M2.5 8l3-3 3 3", width = 2.1f)
            fill("M11.4 5h2.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-2.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.45f)
            fill("M11.4 10.6h5.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-5.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z", alpha = 0.7f)
            fill("M11.4 16.2h8.2a1.4 1.4 0 0 1 1.4 1.4v0a1.4 1.4 0 0 1 -1.4 1.4h-8.2a1.4 1.4 0 0 1 -1.4 -1.4v-0a1.4 1.4 0 0 1 1.4 -1.4z")
        }
    }

    // KMK (r137): "Finished" under the next-update button: a double tick, the second faint
    val Finished: ImageVector by lazy {
        icon("Finished") {
            line("M2.5 12.5l4.2 4.2 7.3-8.2", width = 2.3f)
            line("M10.5 15.8l.9.9 9.6-10.2", width = 2.3f, alpha = 0.5f)
        }
    }

    // KMK (r137): the Suggestions arrow pointing back (glass back buttons)
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack") {
            line("M19.5 12H6", width = 2.5f)
            line("M11 6.5L5.5 12l5.5 5.5", width = 2.5f)
        }
    }

    // KMK (r137): the home button (HC): a solid rounded house with a faint glass door
    val Home: ImageVector by lazy {
        icon("Home") {
            fill("M3.6 11.3L12 4.1l8.4 7.2a.9 .9 0 0 1 -.6 1.6H19V19a1.5 1.5 0 0 1 -1.5 1.5H14v-4.5a2 2 0 0 0 -4 0v4.5H6.5A1.5 1.5 0 0 1 5 19v-6.1H4.2a.9 .9 0 0 1 -.6 -1.6z")
            fill("M10 20.5v-4.5a2 2 0 0 1 4 0v4.5z", alpha = 0.38f)
        }
    }

    // KMK (r134): the preview player's controls
    val Pause: ImageVector by lazy {
        icon("Pause") {
            fill("M7.5 5h2.2a1.3 1.3 0 0 1 1.3 1.3v11.4a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-11.4a1.3 1.3 0 0 1 1.3 -1.3z")
            fill("M14.3 5h2.2a1.3 1.3 0 0 1 1.3 1.3v11.4a1.3 1.3 0 0 1 -1.3 1.3h-2.2a1.3 1.3 0 0 1 -1.3 -1.3v-11.4a1.3 1.3 0 0 1 1.3 -1.3z")
        }
    }

    val SkipNext: ImageVector by lazy {
        icon("SkipNext") {
            fill("M6.8 5.8c-.8-.5-1.8.1-1.8 1v10.4c0 .9 1 1.5 1.8 1l7.9-5.2c.7-.4.7-1.4 0-1.9z")
            fill("M17.2 5.5h1.3a1 1 0 0 1 1 1v11a1 1 0 0 1 -1 1h-1.3a1 1 0 0 1 -1 -1v-11a1 1 0 0 1 1 -1z", alpha = 0.6f)
        }
    }

    val SkipPrevious: ImageVector by lazy {
        icon("SkipPrevious") {
            fill("M17.2 5.8c.8-.5 1.8.1 1.8 1v10.4c0 .9-1 1.5-1.8 1l-7.9-5.2c-.7-.4-.7-1.4 0-1.9z")
            fill("M5.5 5.5h1.3a1 1 0 0 1 1 1v11a1 1 0 0 1 -1 1h-1.3a1 1 0 0 1 -1 -1v-11a1 1 0 0 1 1 -1z", alpha = 0.6f)
        }
    }

    val Back10: ImageVector by lazy {
        icon("Back10") {
            line("M6 7.5A8 8 0 1 1 4 12.5", width = 1.9f, alpha = 0.55f)
            line("M6 3.8v3.7h3.7", width = 1.9f)
            line("M9.6 10.3l1.2-.8v5.6", width = 1.7f)
            line("M15 9.5a1.6 2.8 0 1 1 0 5.6a1.6 2.8 0 1 1 0 -5.6z", width = 1.7f)
        }
    }

    val Forward10: ImageVector by lazy {
        icon("Forward10") {
            line("M18 7.5A8 8 0 1 0 20 12.5", width = 1.9f, alpha = 0.55f)
            line("M18 3.8v3.7h-3.7", width = 1.9f)
            line("M8.6 10.3l1.2-.8v5.6", width = 1.7f)
            line("M14 9.5a1.6 2.8 0 1 1 0 5.6a1.6 2.8 0 1 1 0 -5.6z", width = 1.7f)
        }
    }

    /**
     * KMK (r143, F1): Watch on Car. Built like Material's Cast icon: Material's outlined car where Cast's screen frame
     * is (scaled 1.13, moved -1.2 / -3.3), its bottom-left cut away by a circle (r 12.6 around 1,21), and Cast's three
     * signal arcs in that gap.
     */
    val WatchOnCar: ImageVector by lazy {
        ImageVector.Builder(
            name = "Glass.WatchOnCar",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            group(
                name = "car",
                // The whole square minus the circle around the arcs (drawn the other way round, so it is a hole)
                clipPathData = PathParser().parsePathString(
                    "M0 0H24V24H0Z M-11.6 21a12.6 12.6 0 1 0 25.2 0a12.6 12.6 0 1 0 -25.2 0Z",
                ).toNodes(),
            ) {
                group(scaleX = 1.13f, scaleY = 1.13f, translationX = -1.2f, translationY = -3.3f) {
                    addPath(
                        pathData = PathParser().parsePathString(
                            "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1" +
                                "c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99z" +
                                "M6.85 7h10.29l1.08 3.11H5.77L6.85 7zM19 17H5v-5h14v5z" +
                                "M7.5 13a1.5 1.5 0 1 0 0 3a1.5 1.5 0 0 0 0-3z" +
                                "M16.5 13a1.5 1.5 0 1 0 0 3a1.5 1.5 0 0 0 0-3z",
                        ).toNodes(),
                        pathFillType = PathFillType.EvenOdd,
                        fill = SolidColor(Color.Black),
                    )
                }
            }
            addPath(
                pathData = PathParser().parsePathString(
                    "M1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7z" +
                        "m0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z",
                ).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            line("M6.5 6.5l11 11M17.5 6.5l-11 11", width = 2.2f)
        }
    }

    val Lock: ImageVector by lazy {
        icon("Lock") {
            line("M8 10.5V8a4 4 0 0 1 8 0v2.5", width = 2f)
            fill("M6.8 10.5h10.4a1.8 1.8 0 0 1 1.8 1.8v6.4a1.8 1.8 0 0 1 -1.8 1.8h-10.4a1.8 1.8 0 0 1 -1.8 -1.8v-6.4a1.8 1.8 0 0 1 1.8 -1.8z")
        }
    }

    val LockOpen: ImageVector by lazy {
        icon("LockOpen") {
            line("M8 10.5V8a4 4 0 0 1 7.6-1.7", width = 2f, alpha = 0.6f)
            fill("M6.8 10.5h10.4a1.8 1.8 0 0 1 1.8 1.8v6.4a1.8 1.8 0 0 1 -1.8 1.8h-10.4a1.8 1.8 0 0 1 -1.8 -1.8v-6.4a1.8 1.8 0 0 1 1.8 -1.8z", alpha = 0.4f)
        }
    }

    val Tune: ImageVector by lazy {
        icon("Tune") {
            line("M4 7h16M4 12h16M4 17h16", width = 1.6f, alpha = 0.45f)
            fill("M9 7m-2.3 0a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0")
            fill("M15.5 12m-2.3 0a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0")
            fill("M7.5 17m-2.3 0a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0")
        }
    }

    val Quality: ImageVector by lazy {
        icon("Quality") {
            fill("M5.2 5h13.6a3.2 3.2 0 0 1 3.2 3.2v7.6a3.2 3.2 0 0 1 -3.2 3.2h-13.6a3.2 3.2 0 0 1 -3.2 -3.2v-7.6a3.2 3.2 0 0 1 3.2 -3.2z", alpha = 0.38f)
            line("M6.5 9v6M6.5 12h3.6M10.1 9v6", width = 1.8f)
            line("M13.3 9h1.6a3 3 0 0 1 0 6h-1.6z", width = 1.8f)
        }
    }

    val Fullscreen: ImageVector by lazy {
        icon("Fullscreen") {
            line("M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1 -1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15", width = 2.1f)
        }
    }

    val VolumeOn: ImageVector by lazy {
        icon("VolumeOn") {
            fill("M4.5 9h3l4.3-3.6c.7-.6 1.7-.1 1.7.8v11.6c0 .9-1 1.4-1.7.8L7.5 15h-3a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1z")
            line("M16 9.2a4 4 0 0 1 0 5.6M18.5 6.8a7.4 7.4 0 0 1 0 10.4", width = 1.8f, alpha = 0.6f)
        }
    }

    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            fill("M4.5 9h3l4.3-3.6c.7-.6 1.7-.1 1.7.8v11.6c0 .9-1 1.4-1.7.8L7.5 15h-3a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1z", alpha = 0.55f)
            line("M16.5 9.5l5 5M21.5 9.5l-5 5", width = 2f)
        }
    }

    val ChevronUp: ImageVector by lazy {
        icon("ChevronUp") {
            line("M6 14.5l6-6 6 6", width = 2.2f)
        }
    }

    val DragHandle: ImageVector by lazy {
        icon("DragHandle") {
            fill("M9 6m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M15 6m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M9 12m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M15 12m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M9 18m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M15 18m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0", alpha = 0.7f)
        }
    }

    // KMK (r134): panel header buttons (swap sides, tuck away)
    val SwapSides: ImageVector by lazy {
        icon("SwapSides") {
            line("M4 8.5h14.5M15 5l3.5 3.5L15 12", width = 2.1f)
            line("M20 15.5H5.5M9 12l-3.5 3.5L9 19", width = 2.1f, alpha = 0.55f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft") {
            line("M14.5 6l-6 6 6 6", width = 2.2f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            line("M9.5 6l6 6-6 6", width = 2.2f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            line("M6 9.5l6 6 6-6", width = 2.2f)
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            fill("M10.5 4a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13", alpha = 0.38f)
            line("M10.5 4a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13", width = 2f)
            line("M15.3 15.3l5.2 5.2", width = 3f)
        }
    }

    val Popular: ImageVector by lazy {
        icon("Popular") {
            fill("M12 20.5s-8-4.7-8-10.6A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 5.9-8 10.6-8 10.6z", alpha = 0.25f)
            line("M12 20.5s-8-4.7-8-10.6A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 5.9-8 10.6-8 10.6z", width = 1.8f)
        }
    }

    val PopularOn: ImageVector by lazy {
        icon("PopularOn") {
            fill("M12 20.5s-8-4.7-8-10.6A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 5.9-8 10.6-8 10.6zM7.1 10.3a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0z", evenOdd = true)
        }
    }

    val Latest: ImageVector by lazy {
        icon("Latest") {
            fill("M12 2L14.15 3.98L17 3.34L17.87 6.13L20.66 7L20.02 9.85L22 12L20.02 14.15L20.66 17L17.87 17.87L17 20.66L14.15 20.02L12 22L9.85 20.02L7 20.66L6.13 17.87L3.34 17L3.98 14.15L2 12L3.98 9.85L3.34 7L6.13 6.13L7 3.34L9.85 3.98z", alpha = 0.3f)
            line("M12 2L14.15 3.98L17 3.34L17.87 6.13L20.66 7L20.02 9.85L22 12L20.02 14.15L20.66 17L17.87 17.87L17 20.66L14.15 20.02L12 22L9.85 20.02L7 20.66L6.13 17.87L3.34 17L3.98 14.15L2 12L3.98 9.85L3.34 7L6.13 6.13L7 3.34L9.85 3.98z", width = 1.6f)
            fill("M12 6.6h0a1.1 1.1 0 0 1 1.1 1.1v4.8a1.1 1.1 0 0 1 -1.1 1.1h-0a1.1 1.1 0 0 1 -1.1 -1.1v-4.8a1.1 1.1 0 0 1 1.1 -1.1zM10.75 16.4a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0z")
        }
    }

    val LatestOn: ImageVector by lazy {
        icon("LatestOn") {
            fill("M12 2L14.15 3.98L17 3.34L17.87 6.13L20.66 7L20.02 9.85L22 12L20.02 14.15L20.66 17L17.87 17.87L17 20.66L14.15 20.02L12 22L9.85 20.02L7 20.66L6.13 17.87L3.34 17L3.98 14.15L2 12L3.98 9.85L3.34 7L6.13 6.13L7 3.34L9.85 3.98zM12 6.6h0a1.1 1.1 0 0 1 1.1 1.1v4.8a1.1 1.1 0 0 1 -1.1 1.1h-0a1.1 1.1 0 0 1 -1.1 -1.1v-4.8a1.1 1.1 0 0 1 1.1 -1.1zM10.75 16.4a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0z", evenOdd = true)
        }
    }

    val SourceSettings: ImageVector by lazy {
        icon("SourceSettings") {
            fill("M10.3 2.5h3.4l.5 2.6 1.9.8 2.2-1.5 2.4 2.4-1.5 2.2.8 1.9 2.6.5v3.4l-2.6.5-.8 1.9 1.5 2.2-2.4 2.4-2.2-1.5-1.9.8-.5 2.6h-3.4l-.5-2.6-1.9-.8-2.2 1.5-2.4-2.4 1.5-2.2-.8-1.9-2.6-.5v-3.4l2.6-.5.8-1.9-1.5-2.2 2.4-2.4 2.2 1.5 1.9-.8z", alpha = 0.38f)
            line("M10.3 2.5h3.4l.5 2.6 1.9.8 2.2-1.5 2.4 2.4-1.5 2.2.8 1.9 2.6.5v3.4l-2.6.5-.8 1.9 1.5 2.2-2.4 2.4-2.2-1.5-1.9.8-.5 2.6h-3.4l-.5-2.6-1.9-.8-2.2 1.5-2.4-2.4 1.5-2.2-.8-1.9-2.6-.5v-3.4l2.6-.5.8-1.9-1.5-2.2 2.4-2.4 2.2 1.5 1.9-.8z", width = 1.6f)
            fill("M9.4 12a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0z")
        }
    }

    val EditSource: ImageVector by lazy {
        icon("EditSource") {
            fill("M5 3h9a2 2 0 0 1 2 2v13a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-13a2 2 0 0 1 2 -2z", alpha = 0.38f)
            fill("M17.4 7.8a1.6 1.6 0 0 1 2.3 0l.9.9a1.6 1.6 0 0 1 0 2.3L13 18.6l-3.8.8.8-3.8z")
        }
    }

    val EditableCopy: ImageVector by lazy {
        icon("EditableCopy") {
            line("M7 5V4a2 2 0 0 1 2-2h8.5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H15.5", width = 1.6f, alpha = 0.55f)
            fill("M5 5h8.5a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-8.5a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z", alpha = 0.38f)
            fill("M17.4 7.8a1.6 1.6 0 0 1 2.3 0l.9.9a1.6 1.6 0 0 1 0 2.3L13 18.6l-3.8.8.8-3.8z")
        }
    }

    val Help: ImageVector by lazy {
        icon("Help") {
            fill("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z", alpha = 0.38f)
            line("M9.4 9.4a2.7 2.7 0 1 1 3.7 2.5c-.7.3-1.1.9-1.1 1.6v.6", width = 2.4f)
            fill("M10.6 17.2a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0z")
        }
    }

    val BulkSelect: ImageVector by lazy {
        icon("BulkSelect") {
            line("M3 7.5l2 2 3.5-3.8M3 15.5l2 2 3.5-3.8", width = 2.2f)
            fill("M12.3 6.5h7.4a1.3 1.3 0 0 1 1.3 1.3v0a1.3 1.3 0 0 1 -1.3 1.3h-7.4a1.3 1.3 0 0 1 -1.3 -1.3v-0a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.45f)
            fill("M12.3 14.5h7.4a1.3 1.3 0 0 1 1.3 1.3v0a1.3 1.3 0 0 1 -1.3 1.3h-7.4a1.3 1.3 0 0 1 -1.3 -1.3v-0a1.3 1.3 0 0 1 1.3 -1.3z", alpha = 0.45f)
        }
    }

    val Retry: ImageVector by lazy {
        icon("Retry") {
            line("M12 3.4a8.6 8.6 0 1 1 0 17.2a8.6 8.6 0 1 1 0-17.2", width = 2.4f, alpha = 0.35f)
            line("M20.6 12a8.6 8.6 0 1 1-2.5-6.1", width = 2.6f)
            line("M19 2.8v4.4h-4.4", width = 2.4f)
        }
    }

    val PreviewPanel: ImageVector by lazy {
        icon("PreviewPanel") {
            fill("M8 2h8a3 3 0 0 1 3 3v14a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-14a3 3 0 0 1 3 -3z", alpha = 0.16f)
            line("M8 2h8a3 3 0 0 1 3 3v14a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-14a3 3 0 0 1 3 -3z", width = 1.6f, alpha = 0.5f)
            fill("M8.5 4.5h2.2a1 1 0 0 1 1 1v3.6a1 1 0 0 1 -1 1h-2.2a1 1 0 0 1 -1 -1v-3.6a1 1 0 0 1 1 -1z", alpha = 0.45f)
            fill("M13.3 5h2.6a0.7 0.7 0 0 1 0.7 0.7v0a0.7 0.7 0 0 1 -0.7 0.7h-2.6a0.7 0.7 0 0 1 -0.7 -0.7v-0a0.7 0.7 0 0 1 0.7 -0.7z", alpha = 0.36000000000000004f)
            fill("M13.2 7.6h1.8a0.6 0.6 0 0 1 0.6 0.6v0a0.6 0.6 0 0 1 -0.6 0.6h-1.8a0.6 0.6 0 0 1 -0.6 -0.6v-0a0.6 0.6 0 0 1 0.6 -0.6z", alpha = 0.24750000000000003f)
            fill("M8.25 12.5h7.5a0.75 0.75 0 0 1 0.75 0.75v0a0.75 0.75 0 0 1 -0.75 0.75h-7.5a0.75 0.75 0 0 1 -0.75 -0.75v-0a0.75 0.75 0 0 1 0.75 -0.75z", alpha = 0.3375f)
            fill("M8.25 15.3h7.5a0.75 0.75 0 0 1 0.75 0.75v0a0.75 0.75 0 0 1 -0.75 0.75h-7.5a0.75 0.75 0 0 1 -0.75 -0.75v-0a0.75 0.75 0 0 1 0.75 -0.75z", alpha = 0.24750000000000003f)
            fill("M8.25 18.1h7.5a0.75 0.75 0 0 1 0.75 0.75v0a0.75 0.75 0 0 1 -0.75 0.75h-7.5a0.75 0.75 0 0 1 -0.75 -0.75v-0a0.75 0.75 0 0 1 0.75 -0.75z", alpha = 0.18000000000000002f)
        }
    }

    val PreviewPanelOn: ImageVector by lazy {
        icon("PreviewPanelOn") {
            fill("M8 2h8a3 3 0 0 1 3 3v14a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-14a3 3 0 0 1 3 -3z", alpha = 0.38f)
            line("M8 2h8a3 3 0 0 1 3 3v14a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-14a3 3 0 0 1 3 -3z", width = 1.6f, alpha = 0.65f)
            fill("M8.5 4.5h2.2a1 1 0 0 1 1 1v3.6a1 1 0 0 1 -1 1h-2.2a1 1 0 0 1 -1 -1v-3.6a1 1 0 0 1 1 -1z")
            fill("M13.3 5h2.6a0.7 0.7 0 0 1 0.7 0.7v0a0.7 0.7 0 0 1 -0.7 0.7h-2.6a0.7 0.7 0 0 1 -0.7 -0.7v-0a0.7 0.7 0 0 1 0.7 -0.7z", alpha = 0.8f)
            fill("M13.2 7.6h1.8a0.6 0.6 0 0 1 0.6 0.6v0a0.6 0.6 0 0 1 -0.6 0.6h-1.8a0.6 0.6 0 0 1 -0.6 -0.6v-0a0.6 0.6 0 0 1 0.6 -0.6z", alpha = 0.55f)
            fill("M8.25 12.5h7.5a0.75 0.75 0 0 1 0.75 0.75v0a0.75 0.75 0 0 1 -0.75 0.75h-7.5a0.75 0.75 0 0 1 -0.75 -0.75v-0a0.75 0.75 0 0 1 0.75 -0.75z", alpha = 0.75f)
            fill("M8.25 15.3h7.5a0.75 0.75 0 0 1 0.75 0.75v0a0.75 0.75 0 0 1 -0.75 0.75h-7.5a0.75 0.75 0 0 1 -0.75 -0.75v-0a0.75 0.75 0 0 1 0.75 -0.75z", alpha = 0.55f)
            fill("M8.25 18.1h7.5a0.75 0.75 0 0 1 0.75 0.75v0a0.75 0.75 0 0 1 -0.75 0.75h-7.5a0.75 0.75 0 0 1 -0.75 -0.75v-0a0.75 0.75 0 0 1 0.75 -0.75z", alpha = 0.4f)
        }
    }

    val TabTrending: ImageVector by lazy {
        icon("TabTrending") {
            fill("M3 17l6-6 4 4 8-8V20H3z", alpha = 0.38f)
            line("M3 17l6-6 4 4 8-8", width = 2.2f)
            line("M15.5 7H21v5.5", width = 2.2f)
        }
    }

    val TabHot: ImageVector by lazy {
        icon("TabHot") {
            fill("M12 2.5c1 3.5 5.5 5.5 5.5 11a5.5 5.5 0 0 1-11 0c0-2.5 1.2-4 2.5-5 .2 1.8 1 3 2 3.5-.5-3.5.3-6.8 1-9.5z", alpha = 0.38f)
            fill("M12 13c1.3 1.3 2.6 2.5 2.6 4.3a2.6 2.6 0 0 1-5.2 0c0-1.5 1.3-2.8 2.6-4.3z")
        }
    }

    val TabTop: ImageVector by lazy {
        icon("TabTop") {
            fill("M7 3.5h10v5a5 5 0 0 1-10 0z")
            line("M7 5H4.5v1.5A3 3 0 0 0 7.5 9.5M17 5h2.5v1.5a3 3 0 0 1-3 3", width = 1.8f, alpha = 0.5f)
            line("M12 13.5v3.5", width = 2.2f)
            fill("M8.8 18.5h6.4a1.3 1.3 0 0 1 1.3 1.3v0a1.3 1.3 0 0 1 -1.3 1.3h-6.4a1.3 1.3 0 0 1 -1.3 -1.3v-0a1.3 1.3 0 0 1 1.3 -1.3z")
        }
    }

    val TabCalendar: ImageVector by lazy {
        icon("TabCalendar") {
            fill("M5.2 5h13.6a2.2 2.2 0 0 1 2.2 2.2v10.6a2.2 2.2 0 0 1 -2.2 2.2h-13.6a2.2 2.2 0 0 1 -2.2 -2.2v-10.6a2.2 2.2 0 0 1 2.2 -2.2z", alpha = 0.38f)
            line("M8 3v4M16 3v4", width = 2.2f)
            fill("M10 14a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z")
        }
    }

    val TabNew: ImageVector by lazy {
        icon("TabNew") {
            fill("M11 2.5l1.9 5.6 5.6 1.9-5.6 1.9L11 17.5l-1.9-5.6L3.5 10l5.6-1.9z")
            fill("M18.5 14l.9 2.6 2.6.9-2.6.9-.9 2.6-.9-2.6-2.6-.9 2.6-.9z", alpha = 0.45f)
        }
    }

    val TabRecent: ImageVector by lazy {
        icon("TabRecent") {
            fill("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z", alpha = 0.38f)
            line("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18", width = 1.8f)
            line("M12 7v5.2l3.4 2.1", width = 2.2f)
        }
    }

    val TabFavourite: ImageVector by lazy {
        icon("TabFavourite") {
            fill("M12 20.5s-8-4.7-8-10.6A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 8 2.6c0 5.9-8 10.6-8 10.6z", alpha = 0.38f)
            fill("M9.8 12.5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z")
        }
    }

    val TabPeople: ImageVector by lazy {
        icon("TabPeople") {
            fill("M8 7.5a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z")
            fill("M4 20.5c0-4.2 3.6-7 8-7s8 2.8 8 7z", alpha = 0.38f)
        }
    }

    val TabFeatured: ImageVector by lazy {
        icon("TabFeatured") {
            fill("M12 3l2.7 5.6 6.1.8-4.5 4.2 1.1 6.1L12 16.8 6.6 19.7l1.1-6.1L3.2 9.4l6.1-.8z", alpha = 0.38f)
            line("M12 3l2.7 5.6 6.1.8-4.5 4.2 1.1 6.1L12 16.8 6.6 19.7l1.1-6.1L3.2 9.4l6.1-.8z", width = 1.6f)
            fill("M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z")
        }
    }

    val TabSubtitles: ImageVector by lazy {
        icon("TabSubtitles") {
            fill("M5.5 5h13a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-13a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3z", alpha = 0.38f)
            fill("M6.6 11.5h3.8a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-3.8a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z")
            fill("M14.1 11.5h3.3a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-3.3a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z")
            fill("M6.6 15h10.8a1.1 1.1 0 0 1 1.1 1.1v0a1.1 1.1 0 0 1 -1.1 1.1h-10.8a1.1 1.1 0 0 1 -1.1 -1.1v-0a1.1 1.1 0 0 1 1.1 -1.1z", alpha = 0.6f)
        }
    }

    val TabMovie: ImageVector by lazy {
        icon("TabMovie") {
            fill("M5 9h14a2 2 0 0 1 2 2v7.5a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7.5a2 2 0 0 1 2 -2z", alpha = 0.38f)
            fill("M3 5.3l15.6-2.8.7 3.9L3.7 9.2z")
        }
    }

    val ShapeAuto: ImageVector by lazy {
        icon("ShapeAuto") {
            fill("M4.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v7.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-7.8a1.6 1.6 0 0 1 1.6 -1.6z")
            fill("M14.6 3h4.8a1.6 1.6 0 0 1 1.6 1.6v4.8a1.6 1.6 0 0 1 -1.6 1.6h-4.8a1.6 1.6 0 0 1 -1.6 -1.6v-4.8a1.6 1.6 0 0 1 1.6 -1.6z", alpha = 0.6f)
            fill("M4.6 16h14.8a1.6 1.6 0 0 1 1.6 1.6v1.8a1.6 1.6 0 0 1 -1.6 1.6h-14.8a1.6 1.6 0 0 1 -1.6 -1.6v-1.8a1.6 1.6 0 0 1 1.6 -1.6z", alpha = 0.4f)
        }
    }

    val ShapePage: ImageVector by lazy {
        icon("ShapePage") {
            fill("M8 2.5h8a2 2 0 0 1 2 2v15a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-15a2 2 0 0 1 2 -2z")
        }
    }

    val ShapeSquare: ImageVector by lazy {
        icon("ShapeSquare") {
            fill("M6.1 3.5h11.8a2.6 2.6 0 0 1 2.6 2.6v11.8a2.6 2.6 0 0 1 -2.6 2.6h-11.8a2.6 2.6 0 0 1 -2.6 -2.6v-11.8a2.6 2.6 0 0 1 2.6 -2.6z")
        }
    }

    val ShapeStrip: ImageVector by lazy {
        icon("ShapeStrip") {
            fill("M8.2 2h7.6a1.2 1.2 0 0 1 1.2 1.2v3.2a1.2 1.2 0 0 1 -1.2 1.2h-7.6a1.2 1.2 0 0 1 -1.2 -1.2v-3.2a1.2 1.2 0 0 1 1.2 -1.2z")
            fill("M8.2 9.2h7.6a1.2 1.2 0 0 1 1.2 1.2v3.2a1.2 1.2 0 0 1 -1.2 1.2h-7.6a1.2 1.2 0 0 1 -1.2 -1.2v-3.2a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.7f)
            fill("M8.2 16.4h7.6a1.2 1.2 0 0 1 1.2 1.2v3.2a1.2 1.2 0 0 1 -1.2 1.2h-7.6a1.2 1.2 0 0 1 -1.2 -1.2v-3.2a1.2 1.2 0 0 1 1.2 -1.2z", alpha = 0.45f)
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
