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

// Remember collapsed/expanded sections while the app is open
private var descriptionExpandedState by mutableStateOf(true)
private var tagsExpandedState by mutableStateOf(true)

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

        val description = manga.description?.takeIf { it.isNotBlank() }
        if (description != null) {
            CollapsibleHeader(
                title = stringResource(KMR.strings.browse_description),
                expanded = descriptionExpandedState,
                onToggle = { descriptionExpandedState = !descriptionExpandedState },
            )
            AnimatedVisibility(visible = descriptionExpandedState) {
                SelectionContainer {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .secondaryItemAlpha(),
                    )
                }
            }
        }

        val tags = manga.genre.orEmpty().filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            CollapsibleHeader(
                title = stringResource(KMR.strings.browse_tags),
                expanded = tagsExpandedState,
                onToggle = { tagsExpandedState = !tagsExpandedState },
            )
            val haptic = LocalHapticFeedback.current
            AnimatedVisibility(visible = tagsExpandedState) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    tags.forEach { tag ->
                        // KMK: tap searches this source, hold does a global search (like the title page)
                        SuggestionChip(
                            onClick = { onSearch(tag, false) },
                            modifier = Modifier.onHold {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSearch(tag, true)
                            },
                            label = { Text(tag, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }
        }
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
private fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
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
