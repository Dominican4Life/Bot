package eu.kanade.presentation.track

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.capitalize
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toLowerCase
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun TrackerSearch(
    state: TextFieldState,
    onDispatchQuery: () -> Unit,
    queryResult: Result<List<TrackSearch>>?,
    selected: TrackSearch?,
    onSelectedChange: (TrackSearch) -> Unit,
    onConfirmSelection: (private: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    supportsPrivateTracking: Boolean,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val dispatchQueryAndClearFocus: () -> Unit = {
        onDispatchQuery()
        focusManager.clearFocus()
    }

    // KMK (r137, item 18): a tinted glass sheet (dark themes): no line under the bar, white ← / ✕, glass cards and buttons
    val glass = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Scaffold(
        containerColor = if (glass) {
            tachiyomi.presentation.core.components.tintedGlass(1f, Color(0xFF16181F), 0.22f)
        } else {
            MaterialTheme.colorScheme.background
        },
        topBar = {
            Column {
                TopAppBar(
                    colors = if (glass) {
                        androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    } else {
                        androidx.compose.material3.TopAppBarDefaults.topAppBarColors()
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                imageVector = if (glass) {
                                    eu.kanade.presentation.components.GlassIcons.ArrowBack
                                } else {
                                    Icons.AutoMirrored.Outlined.ArrowBack
                                },
                                contentDescription = null,
                                tint = if (glass) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    title = {
                        BasicTextField(
                            state = state,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .runOnEnterKeyPressed(action = dispatchQueryAndClearFocus),
                            textStyle = MaterialTheme.typography.bodyLarge
                                .copy(color = MaterialTheme.colorScheme.onSurface),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = { dispatchQueryAndClearFocus() },
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorator = {
                                if (state.text.isEmpty()) {
                                    Text(
                                        text = stringResource(MR.strings.action_search_hint),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                it()
                            },
                        )
                    },
                    actions = {
                        if (state.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    state.clearText()
                                    focusRequester.requestFocus()
                                },
                            ) {
                                Icon(
                                    imageVector = if (glass) eu.kanade.presentation.components.GlassIcons.Close else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (glass) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                if (!glass) HorizontalDivider()
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selected != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .padding(MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    if (glass) {
                        eu.kanade.presentation.components.GlassButton(
                            text = stringResource(MR.strings.action_track),
                            primary = true,
                            onClick = { onConfirmSelection(false) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Button(
                            onClick = { onConfirmSelection(false) },
                            modifier = Modifier.weight(1f),
                            elevation = ButtonDefaults.elevatedButtonElevation(),
                        ) {
                            Text(text = stringResource(MR.strings.action_track))
                        }
                    }
                    if (supportsPrivateTracking) {
                        Button(
                            onClick = { onConfirmSelection(true) },
                            elevation = ButtonDefaults.elevatedButtonElevation(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VisibilityOff,
                                contentDescription = stringResource(MR.strings.action_toggle_private_on),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        if (queryResult == null) {
            LoadingScreen(modifier = Modifier.padding(innerPadding))
        } else {
            val availableTracks = queryResult.getOrNull()
            if (availableTracks != null) {
                if (availableTracks.isEmpty()) {
                    EmptyScreen(
                        modifier = Modifier.padding(innerPadding),
                        stringRes = MR.strings.no_results_found,
                    )
                } else {
                    ScrollbarLazyColumn(
                        contentPadding = innerPadding + PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = availableTracks,
                            key = { "tracker-search-${it.hashCode()}" },
                        ) {
                            SearchResultItem(
                                trackSearch = it,
                                selected = it == selected,
                                onClick = { onSelectedChange(it) },
                            )
                        }
                    }
                }
            } else {
                EmptyScreen(
                    modifier = Modifier.padding(innerPadding),
                    message = queryResult.exceptionOrNull()?.message
                        ?: stringResource(MR.strings.unknown_error),
                )
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    trackSearch: TrackSearch,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard: Clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val type = trackSearch.publishing_type.toLowerCase(Locale.current).capitalize(Locale.current)
    val status = trackSearch.publishing_status.toLowerCase(Locale.current).capitalize(Locale.current)
    val description = trackSearch.summary.trim()
    val shape = RoundedCornerShape(16.dp)
    // KMK (r137, item 18): faint glass cards on the glass sheet, the picked one outlined in the accent
    val glass = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val borderColor = when {
        !selected -> Color.Transparent
        glass -> tachiyomi.presentation.core.components.LocalGlassAccent.current
        else -> MaterialTheme.colorScheme.outline
    }
    var dropDownMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(if (glass) Color.White.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surface)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = shape,
            )
            .combinedClickable(
                onLongClick = { dropDownMenuExpanded = true },
                onClick = {
                    focusManager.clearFocus()
                    onClick()
                },
            )
            .padding(12.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column {
            Row {
                MangaCover.Book(
                    data = trackSearch.cover_url,
                    modifier = Modifier.height(96.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = trackSearch.title,
                        modifier = Modifier.padding(end = 28.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SearchResultItemDropDownMenu(
                        expanded = dropDownMenuExpanded,
                        onCollapseMenu = { dropDownMenuExpanded = false },
                        onCopyName = {
                            scope.launch {
                                val clipEntry = ClipData.newPlainText(
                                    trackSearch.title,
                                    trackSearch.title,
                                ).toClipEntry()
                                clipboard.setClipEntry(clipEntry)
                            }
                        },
                        onOpenInBrowser = {
                            val url = trackSearch.tracking_url
                            if (url.isNotBlank()) {
                                context.openInBrowser(url)
                            }
                        },
                    )
                    if (trackSearch.authors.isNotEmpty() || trackSearch.artists.isNotEmpty()) {
                        Text(
                            text = (trackSearch.authors + trackSearch.artists).distinct().joinToString(),
                            modifier = Modifier.secondaryItemAlpha(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (type.isNotBlank()) {
                        SearchResultItemDetails(
                            title = stringResource(MR.strings.track_type),
                            text = type,
                        )
                    }
                    if (trackSearch.start_date.isNotBlank()) {
                        SearchResultItemDetails(
                            title = stringResource(MR.strings.label_started),
                            text = trackSearch.start_date,
                        )
                    }
                    if (status.isNotBlank()) {
                        SearchResultItemDetails(
                            title = stringResource(MR.strings.track_status),
                            text = status,
                        )
                    }
                    if (trackSearch.score != -1.0) {
                        SearchResultItemDetails(
                            title = stringResource(MR.strings.score),
                            text = trackSearch.score.toString(),
                        )
                    }
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    modifier = Modifier
                        .paddingFromBaseline(top = 24.dp)
                        .secondaryItemAlpha(),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SearchResultItemDropDownMenu(
    expanded: Boolean,
    onCollapseMenu: () -> Unit,
    onCopyName: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onCollapseMenu,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(MR.strings.action_copy_to_clipboard)) },
            onClick = {
                onCopyName()
                onCollapseMenu()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(MR.strings.action_open_in_browser)) },
            onClick = {
                onOpenInBrowser()
            },
        )
    }
}

@Composable
private fun SearchResultItemDetails(
    title: String,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        Text(
            text = title,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .secondaryItemAlpha(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@PreviewLightDark
@Composable
private fun TrackerSearchPreviews(
    @PreviewParameter(TrackerSearchPreviewProvider::class)
    content: @Composable () -> Unit,
) {
    TachiyomiPreviewTheme { content() }
}
