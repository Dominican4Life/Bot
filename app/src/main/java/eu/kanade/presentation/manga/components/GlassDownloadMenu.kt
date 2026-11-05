package eu.kanade.presentation.manga.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import eu.kanade.presentation.components.GlassIcons
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** KMK (r132): how many chapters each download choice would add (the ones not downloaded or queued yet) */
@Immutable
data class DownloadMenuCounts(val unread: Int, val all: Int, val bookmarked: Int) {
    companion object {
        fun of(chapters: List<ChapterList.Item>): DownloadMenuCounts {
            val missing = chapters.filter { it.downloadState == Download.State.NOT_DOWNLOADED }
            return DownloadMenuCounts(
                unread = missing.count { !it.chapter.read },
                all = missing.size,
                bookmarked = missing.count { it.chapter.bookmark },
            )
        }
    }
}

/**
 * KMK (r132): the title page's download choices for a glass menu: Unread, All chapters, Bookmarked, each with its icon
 * and how many chapters it would download. Goes inside a DropdownMenu.
 */
@Composable
fun GlassDownloadMenuItems(
    counts: DownloadMenuCounts?,
    onDismiss: () -> Unit,
    onAction: (DownloadAction) -> Unit,
) {
    listOf(
        Triple(DownloadAction.UNREAD_CHAPTERS, stringResource(MR.strings.download_unread), GlassIcons.Unread),
        Triple(DownloadAction.ALL_CHAPTERS, stringResource(KMR.strings.browse_download_all_chapters), GlassIcons.Chapters),
        Triple(DownloadAction.BOOKMARKED_CHAPTERS, stringResource(MR.strings.download_bookmarked), GlassIcons.Bookmark),
    ).forEach { (action, label, icon) ->
        val count = when (action) {
            DownloadAction.UNREAD_CHAPTERS -> counts?.unread
            DownloadAction.ALL_CHAPTERS -> counts?.all
            DownloadAction.BOOKMARKED_CHAPTERS -> counts?.bookmarked
            else -> null
        }
        GlassMenuRow(label = label, icon = icon, trailing = count?.toString()) {
            onDismiss()
            onAction(action)
        }
    }
}

/** KMK (r132): a glass menu row: icon, label, and an optional grey note at the end (a count) */
@Composable
fun GlassMenuRow(
    label: String,
    icon: ImageVector?,
    trailing: String? = null,
    color: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = label, color = color) },
        leadingIcon = icon?.let { { Icon(imageVector = it, contentDescription = null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color) } },
        trailingIcon = trailing?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
}
