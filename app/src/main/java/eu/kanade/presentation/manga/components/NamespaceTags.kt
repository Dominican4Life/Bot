package eu.kanade.presentation.manga.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedSuggestionChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults as SuggestionChipDefaultsM3
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ChipBorder
import eu.kanade.presentation.components.SuggestionChip
import eu.kanade.presentation.components.SuggestionChipDefaults
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.EXH_SOURCE_ID
import exh.source.eHentaiSourceIds
import exh.util.SourceTagsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Immutable
data class DisplayTag(
    val namespace: String?,
    val text: String,
    val search: String,
    val border: Int?,
)

@Immutable
@JvmInline
value class SearchMetadataChips(
    val tags: Map<String, List<DisplayTag>>,
) {
    companion object {
        operator fun invoke(meta: RaisedSearchMetadata?, sourceId: Long, tags: List<String>?): SearchMetadataChips? {
            return if (meta != null) {
                SearchMetadataChips(
                    meta.tags
                        .filterNot { it.type == RaisedSearchMetadata.TAG_TYPE_VIRTUAL }
                        .map {
                            DisplayTag(
                                namespace = it.namespace,
                                text = it.name,
                                search = if (!it.namespace.isNullOrEmpty()) {
                                    SourceTagsUtil.getWrappedTag(sourceId, namespace = it.namespace, tag = it.name)
                                } else {
                                    SourceTagsUtil.getWrappedTag(sourceId, fullTag = it.name)
                                } ?: it.name,
                                border = if (sourceId in eHentaiSourceIds) {
                                    when (it.type) {
                                        EHentaiSearchMetadata.TAG_TYPE_NORMAL -> 2
                                        EHentaiSearchMetadata.TAG_TYPE_LIGHT -> 1
                                        else -> null
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        .groupBy { it.namespace.orEmpty() },
                )
            } else if (tags != null && tags.all { it.contains(':') }) {
                SearchMetadataChips(
                    tags
                        .map { tag ->
                            val index = tag.indexOf(':')
                            DisplayTag(tag.substring(0, index).trim(), tag.substring(index + 1).trim(), tag, null)
                        }
                        .groupBy {
                            it.namespace.orEmpty()
                        },
                )
            } else {
                null
            }
        }
    }
}

@Composable
fun NamespaceTags(
    tags: SearchMetadataChips,
    onClick: (item: String) -> Unit,
    // KMK -->
    pureDarkMode: Boolean = false,
    onLongClick: ((item: String) -> Unit)? = null,
    /** KMK (r87): a double tap (copies the tag) */
    onDoubleClick: ((item: String) -> Unit)? = null,
    // KMK <--
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.tags.forEach { (namespace, tags) ->
            Row(Modifier.padding(start = 16.dp)) {
                if (namespace.isNotEmpty()) {
                    TagsChip(
                        modifier = Modifier.padding(top = 4.dp),
                        text = namespace,
                        onClick = null,
                        // KMK -->
                        pureDarkMode = pureDarkMode,
                        // KMK <--
                    )
                }
                FlowRow(
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { (_, text, search, border) ->
                        val borderDp = border?.dp
                        TagsChip(
                            modifier = Modifier.padding(vertical = 4.dp),
                            text = text,
                            onClick = { onClick(search) },
                            // KMK -->
                            onLongClick = onLongClick?.let { { it(search) } },
                            onDoubleClick = onDoubleClick?.let { { it(text) } },
                            // KMK <--
                            border = borderDp?.let {
                                SuggestionChipDefaults.suggestionChipBorder(
                                    borderWidth = it,
                                    // KMK -->
                                    borderColor = MaterialTheme.colorScheme.primary,
                                    // KMK <--
                                )
                            } ?: SuggestionChipDefaults.suggestionChipBorder(
                                // KMK -->
                                borderColor = MaterialTheme.colorScheme.primary,
                                // KMK <--
                            ),
                            borderM3 = borderDp?.let {
                                SuggestionChipDefaultsM3.suggestionChipBorder(
                                    enabled = true,
                                    borderWidth = it,
                                    // KMK -->
                                    borderColor = MaterialTheme.colorScheme.primary,
                                    // KMK <--
                                )
                            } ?: SuggestionChipDefaultsM3.suggestionChipBorder(
                                enabled = true,
                                // KMK -->
                                borderColor = MaterialTheme.colorScheme.primary,
                                // KMK <--
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TagsChip(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    border: ChipBorder? = SuggestionChipDefaults.suggestionChipBorder(),
    // KMK -->
    // borderM3: BorderStroke? = SuggestionChipDefaultsM3.suggestionChipBorder(enabled = true),
    borderM3: BorderStroke? = null,
    pureDarkMode: Boolean = false,
    /** Holding the chip; a tap still goes to [onClick] */
    onLongClick: (() -> Unit)? = null,
    /**
     * KMK (r87): a double tap, used to copy the tag (tap searches, hold searches globally). With it, a single tap
     * waits the double-tap time before [onClick] runs.
     */
    onDoubleClick: (() -> Unit)? = null,
    // KMK <--
) {
    // KMK -->
    val haptic = LocalHapticFeedback.current
    // Always called, in the same order, so the chip's composition stays stable whatever is passed
    val holdModifier = modifier.onHold {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongClick?.invoke()
    }
    @Suppress("NAME_SHADOWING")
    val modifier = if (onClick != null && onLongClick != null) holdModifier else modifier
    val scope = rememberCoroutineScope()
    val tap = remember { PendingTap() }
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val latestClick = rememberUpdatedState(onClick)
    val latestDoubleClick = rememberUpdatedState(onDoubleClick)
    @Suppress("NAME_SHADOWING")
    val onClick: (() -> Unit)? = if (onClick != null && onDoubleClick != null) {
        {
            tap.onTap(
                scope = scope,
                waitMs = doubleTapMs,
                single = { latestClick.value?.invoke() },
                double = { latestDoubleClick.value?.invoke() },
            )
        }
    } else {
        onClick
    }
    // KMK <--
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        if (onClick != null) {
            // KMK -->
            if (borderM3 != null || pureDarkMode) {
                // KMK <--
                SuggestionChip(
                    modifier = modifier,
                    onClick = onClick,
                    label = {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    border = borderM3
                        // KMK -->
                        ?: SuggestionChipDefaultsM3.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            } else {
                ElevatedSuggestionChip(
                    modifier = modifier,
                    onClick = onClick,
                    label = {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = SuggestionChipDefaultsM3.elevatedSuggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }
            // KMK <--
        } else {
            SuggestionChip(
                modifier = modifier,
                label = {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                border = border,
            )
        }
    }
}

@PreviewLightDark
@Composable
fun NamespaceTagsPreview() {
    TachiyomiPreviewTheme {
        Surface {
            NamespaceTags(
                tags = remember {
                    EHentaiSearchMetadata().apply {
                        this.tags.addAll(
                            arrayOf(
                                RaisedTag(
                                    "Male",
                                    "Test",
                                    EHentaiSearchMetadata.TAG_TYPE_NORMAL,
                                ),
                                RaisedTag(
                                    "Male",
                                    "Test2",
                                    EHentaiSearchMetadata.TAG_TYPE_WEAK,
                                ),
                                RaisedTag(
                                    "Male",
                                    "Test3",
                                    EHentaiSearchMetadata.TAG_TYPE_LIGHT,
                                ),
                                RaisedTag(
                                    "Female",
                                    "Test",
                                    EHentaiSearchMetadata.TAG_TYPE_NORMAL,
                                ),
                                RaisedTag(
                                    "Female",
                                    "Test2",
                                    EHentaiSearchMetadata.TAG_TYPE_WEAK,
                                ),
                                RaisedTag(
                                    "Female",
                                    "Test3",
                                    EHentaiSearchMetadata.TAG_TYPE_LIGHT,
                                ),
                            ),
                        )
                    }.let { SearchMetadataChips(it, EXH_SOURCE_ID, emptyList()) }!!
                },
                onClick = {},
            )
        }
    }
}

/** KMK (r87): the single tap waiting to see whether a second one follows (TagsChip's double tap) */
private class PendingTap {
    private var job: Job? = null

    /** A second tap within [waitMs] runs [double]; otherwise [single] runs once the wait is over */
    fun onTap(scope: CoroutineScope, waitMs: Long, single: () -> Unit, double: () -> Unit) {
        val waiting = job
        job = null
        if (waiting != null && waiting.isActive) {
            waiting.cancel()
            double()
            return
        }
        job = scope.launch {
            delay(waitMs)
            job = null
            single()
        }
    }
}

/**
 * KMK: Calls [onHold] when the element is held for the long-press time, and then swallows the rest of that touch so
 * the element's own tap doesn't fire too. A tap, or a touch that moves (scrolling), is left alone.
 *
 * KMK (r87): keyed on Unit with the latest [onHold]. Keyed on the lambda (new on every recomposition), any
 * recomposition during the hold restarted the gesture and cut off the long-press wait, so holds often did nothing.
 */
@Composable
fun Modifier.onHold(onHold: () -> Unit): Modifier {
    val latestOnHold = rememberUpdatedState(onHold)
    return pointerInput(Unit) { detectHold { latestOnHold.value() } }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectHold(onHold: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            true
        }
        if (ended == null) {
            onHold()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

