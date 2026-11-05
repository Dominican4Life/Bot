package eu.kanade.presentation.browse.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.onHold
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * KMK: The top of the details page (title, author, status, library/WebView buttons, description and tags),
 * shown at the top of the browse chapters panel. Description and tags can be collapsed from their headers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowsePreviewInfoHeader(
    manga: Manga,
    source: Source,
    onAddToLibrary: () -> Unit,
    onWebView: () -> Unit,
    onOpenDetails: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
) {
    val sourceName = remember(source) { source.getNameForMangaInfo() }
    BrowsePreviewInfoHeader(
        manga = manga,
        sourceName = sourceName,
        onAddToLibrary = onAddToLibrary,
        onWebView = onWebView,
        onOpenDetails = onOpenDetails,
        onSearch = onSearch,
    )
}

/** KMK: Same header for anime, which come from anime sources */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowsePreviewInfoHeader(
    manga: Manga,
    sourceName: String,
    onAddToLibrary: () -> Unit,
    onWebView: () -> Unit,
    onOpenDetails: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Title, author, status (no cover image)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetails)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val author = listOfNotNull(manga.author, manga.artist?.takeIf { it != manga.author })
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (author.isNotEmpty()) {
                InfoLine(icon = { Icon(Icons.Outlined.PersonOutline, null, Modifier.size(16.dp)) }, text = author)
            }
            InfoLine(
                icon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp)) },
                text = listOfNotNull(statusText(manga.status), sourceName).joinToString(" • "),
            )
        }

        MangaActionRow(
            favorite = manga.favorite,
            trackingCount = 0,
            nextUpdate = null,
            isUserIntervalMode = manga.fetchInterval < 0,
            onAddToLibraryClicked = onAddToLibrary,
            onWebViewClicked = onWebView,
            onWebViewLongClicked = null,
            // Tracking and update interval need the full details page
            onTrackingClicked = onOpenDetails,
            onEditIntervalClicked = null,
            onEditCategory = null,
            onMergeClicked = null,
            status = manga.status,
            interval = manga.fetchInterval,
        )

        // KMK (r124): the description and tags fold the way the title page does it (the arrow under the text that
        // opens both), with the grouped tag rows; no section headings and no swipe card
        val context = androidx.compose.ui.platform.LocalContext.current
        val tags = remember(manga.genre) { manga.genre?.filter { it.isNotBlank() } }
        eu.kanade.presentation.manga.components.ExpandableMangaDescription(
            defaultExpandState = false,
            description = manga.description,
            tagsProvider = { tags },
            notes = manga.notes,
            onTagSearch = { onSearch(it, false) },
            onCopyTagToClipboard = { tag ->
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(tag, tag))
            },
            // Notes are edited on the full title page
            onEditNotes = onOpenDetails,
            searchMetadataChips = remember(manga.source, tags) {
                eu.kanade.presentation.manga.components.SearchMetadataChips(null, manga.source, tags)
            },
            doSearch = onSearch,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun InfoLine(icon: @Composable () -> Unit, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.secondaryItemAlpha(),
    ) {
        icon()
        Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun statusText(status: Long): String? {
    return when (status.toInt()) {
        SManga.ONGOING -> stringResource(MR.strings.ongoing)
        SManga.COMPLETED -> stringResource(MR.strings.completed)
        SManga.LICENSED -> stringResource(MR.strings.licensed)
        SManga.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
        SManga.CANCELLED -> stringResource(MR.strings.cancelled)
        SManga.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
        else -> null
    }
}
