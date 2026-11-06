package eu.kanade.presentation.manga.components

import eu.kanade.presentation.components.GlassIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

enum class ChapterDownloadAction {
    START,
    START_NOW,
    CANCEL,
    DELETE,
}

@Composable
fun ChapterDownloadIndicator(
    enabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val downloadState = downloadStateProvider()) {
        Download.State.NOT_DOWNLOADED -> NotDownloadedIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
        Download.State.QUEUE, Download.State.DOWNLOADING -> DownloadingIndicator(
            enabled = enabled,
            modifier = modifier,
            downloadState = downloadState,
            downloadProgressProvider = downloadProgressProvider,
            onClick = onClick,
        )
        Download.State.DOWNLOADED -> DownloadedIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
        Download.State.ERROR -> ErrorIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
    }
}

@Composable
private fun NotDownloadedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START_NOW) },
                onClick = { onClick(ChapterDownloadAction.START) },
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // KMK (r132): the glass icon set (a solid arrow in a light ring)
            imageVector = GlassIcons.ChapterDownload,
            contentDescription = stringResource(MR.strings.manga_download),
            modifier = Modifier.size(IndicatorSize),
            // KMK (r135, W): white while not downloaded; the colour is kept for "on" (downloaded)
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun DownloadingIndicator(
    enabled: Boolean,
    downloadState: Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.CANCEL) },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val arrowColor: Color
        val strokeColor = MaterialTheme.colorScheme.primary // KMK: onSurfaceVariant
        val downloadProgress = downloadProgressProvider()
        val indeterminate = downloadState == Download.State.QUEUE ||
            (downloadState == Download.State.DOWNLOADING && downloadProgress == 0)
        // KMK (r132): queued = a dashed ring with a small clock; downloading = a light ring that fills in solid
        if (downloadState == Download.State.QUEUE) {
            arrowColor = Color.Transparent
            Icon(
                imageVector = GlassIcons.ChapterQueued,
                contentDescription = null,
                modifier = Modifier.size(IndicatorSize),
                tint = strokeColor,
            )
        } else if (indeterminate) {
            arrowColor = strokeColor
            CircularProgressIndicator(
                modifier = IndicatorModifier,
                color = strokeColor,
                strokeWidth = IndicatorStrokeWidth,
                trackColor = strokeColor.copy(alpha = 0.3f),
                strokeCap = StrokeCap.Round,
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress / 100f,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            )
            arrowColor = strokeColor
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = IndicatorModifier,
                color = strokeColor,
                strokeWidth = IndicatorStrokeWidth,
                trackColor = strokeColor.copy(alpha = 0.3f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_start_downloading_now)) },
                onClick = {
                    onClick(ChapterDownloadAction.START_NOW)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterDownloadAction.CANCEL)
                    isMenuExpanded = false
                },
            )
        }
        Icon(
            imageVector = GlassIcons.ChapterDownloadArrow,
            contentDescription = null,
            modifier = ArrowModifier,
            tint = arrowColor,
        )
    }
}

@Composable
private fun DownloadedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = GlassIcons.ChapterDownloaded,
            contentDescription = null,
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.primary, // KMK: onSurfaceVariant
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterDownloadAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun ErrorIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START) },
                onClick = { onClick(ChapterDownloadAction.START) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = GlassIcons.ChapterDownloadError,
            contentDescription = stringResource(MR.strings.chapter_error),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun Modifier.commonClickable(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.combinedClickable(
    enabled = enabled,
    onLongClick = {
        onLongClick()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    },
    onClick = onClick,
    role = Role.Button,
    interactionSource = null,
    indication = ripple(
        bounded = false,
        radius = IconButtonTokens.StateLayerSize / 2,
    ),
)

internal val IndicatorSize = 26.dp
private val IndicatorPadding = 2.dp

// To match composable parameter name when used later
private val IndicatorStrokeWidth = IndicatorPadding

private val IndicatorModifier = Modifier
    .size(IndicatorSize)
    .padding(IndicatorPadding)
private val ArrowModifier = Modifier
    .size(IndicatorSize - 7.dp)
