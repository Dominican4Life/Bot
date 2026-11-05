package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SliderDefaults
import eu.kanade.presentation.reader.appbars.GlassSliderThumb
import eu.kanade.presentation.reader.appbars.LocalReaderGlass
import eu.kanade.presentation.reader.appbars.glassSliderColors
import eu.kanade.presentation.reader.appbars.ReaderGlass
import eu.kanade.presentation.reader.appbars.readerGlass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterNavigator(
    isRtl: Boolean,
    isVerticalSlider: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
) {
    // SY -->
    if (isVerticalSlider) {
        ChapterNavigatorVert(
            onNextChapter = onNextChapter,
            enabledNext = enabledNext,
            onPreviousChapter = onPreviousChapter,
            enabledPrevious = enabledPrevious,
            currentPage = currentPage,
            currentPageText = currentPageText,
            totalPages = totalPages,
            onPageIndexChange = onPageIndexChange,
        )
        return
    }
    // SY <--
    val isTabletUi = isTabletUi()
    val horizontalPadding = if (isTabletUi) 24.dp else 8.dp
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val haptic = LocalHapticFeedback.current
    // KMK (r125): one glass pill around the buttons and the slider
    val glass = LocalReaderGlass.current

    // Match with toolbar background color set in ReaderActivity
    val backgroundColor = if (glass) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    }
    val buttonColor = IconButtonDefaults.filledIconButtonColors(
        containerColor = backgroundColor,
        disabledContainerColor = backgroundColor,
        // KMK -->
        contentColor = if (glass) ReaderGlass.content else MaterialTheme.colorScheme.primary,
        // KMK <--
    )
    val textColor = if (glass) ReaderGlass.content else MaterialTheme.colorScheme.onSurface

    // We explicitly handle direction based on the reader viewer rather than the system direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .then(if (glass) Modifier.readerGlass(CircleShape).padding(4.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                enabled = if (isRtl) enabledNext else enabledPrevious,
                onClick = if (isRtl) onNextChapter else onPreviousChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(
                        if (isRtl) MR.strings.action_next_chapter else MR.strings.action_previous_chapter,
                    ),
                )
            }

            if (totalPages > 1) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(backgroundColor)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(contentAlignment = Alignment.CenterEnd) {
                            Text(
                                // SY -->
                                text = currentPageText,
                                // SY <--
                                // KMK -->
                                color = textColor,
                                // KMK <--
                            )
                            // Taking up full length so the slider doesn't shift when 'currentPage' length changes
                            Text(text = totalPages.toString(), color = Color.Transparent)
                        }

                        val interactionSource = remember { MutableInteractionSource() }
                        val sliderDragged by interactionSource.collectIsDraggedAsState()
                        LaunchedEffect(currentPage) {
                            if (sliderDragged) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        Slider(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            value = currentPage,
                            valueRange = 1..totalPages,
                            onValueChange = f@{
                                if (it == currentPage) return@f
                                onPageIndexChange(it - 1)
                            },
                            interactionSource = interactionSource,
                            // KMK (r125): the glass knob and track
                            colors = if (glass) glassSliderColors() else SliderDefaults.colors(),
                            thumb = {
                                if (glass) {
                                    GlassSliderThumb(interactionSource, glassFraction(it))
                                } else {
                                    SliderDefaults.Thumb(interactionSource = interactionSource)
                                }
                            },
                            // KMK (r128): a whole track under the glass lens
                            track = { state ->
                                if (glass && glassLensKnob()) {
                                    eu.kanade.presentation.reader.appbars.GlassSliderTrack(glassFraction(state))
                                } else {
                                    SliderDefaults.Track(
                                        colors = if (glass) glassSliderColors() else SliderDefaults.colors(),
                                        sliderState = state,
                                    )
                                }
                            },
                        )

                        Text(
                            text = totalPages.toString(),
                            // KMK -->
                            color = textColor,
                            // KMK <--
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            FilledIconButton(
                enabled = if (isRtl) enabledPrevious else enabledNext,
                onClick = if (isRtl) onPreviousChapter else onNextChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(
                        if (isRtl) MR.strings.action_previous_chapter else MR.strings.action_next_chapter,
                    ),
                )
            }
        }
    }
}

@Composable
fun ChapterNavigatorVert(
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
) {
    val isTabletUi = isTabletUi()
    val verticalPadding = if (isTabletUi) 24.dp else 8.dp

    val haptic = LocalHapticFeedback.current

    // KMK (r125): one glass bar around the buttons and the slider
    val glass = LocalReaderGlass.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = verticalPadding, horizontal = 8.dp)
            .then(if (glass) Modifier.readerGlass(RoundedCornerShape(28.dp)).padding(vertical = 4.dp) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Match with toolbar background color set in ReaderActivity
        val backgroundColor = if (glass) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme
                .surfaceColorAtElevation(3.dp)
                .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
        }
        val buttonColor = IconButtonDefaults.filledIconButtonColors(
            containerColor = backgroundColor,
            disabledContainerColor = backgroundColor,
            // KMK -->
            contentColor = if (glass) ReaderGlass.content else MaterialTheme.colorScheme.primary,
            // KMK <--
        )
        val textColor = if (glass) ReaderGlass.content else MaterialTheme.colorScheme.onSurface

        FilledIconButton(
            enabled = enabledPrevious,
            onClick = onPreviousChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(MR.strings.action_previous_chapter),
                modifier = Modifier.rotate(90f),
            )
        }

        if (totalPages > 1) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(backgroundColor)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    // SY -->
                    text = currentPageText,
                    // SY <--
                    // KMK -->
                    color = textColor,
                    // KMK <--
                )

                val interactionSource = remember { MutableInteractionSource() }
                val sliderDragged by interactionSource.collectIsDraggedAsState()
                LaunchedEffect(currentPage) {
                    if (sliderDragged) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                Slider(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .graphicsLayer {
                            rotationZ = 90f
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                Constraints(
                                    minWidth = constraints.minHeight,
                                    maxWidth = constraints.maxHeight,
                                    minHeight = constraints.minWidth,
                                    maxHeight = constraints.maxWidth,
                                ),
                            )
                            layout(placeable.height, placeable.width) {
                                placeable.place(0, -placeable.height)
                            }
                        }
                        .weight(1f),
                    value = currentPage,
                    valueRange = 1..totalPages,
                    onValueChange = f@{
                        if (it == currentPage) return@f
                        onPageIndexChange(it - 1)
                    },
                    interactionSource = interactionSource,
                    // KMK (r125): the glass knob and track
                    colors = if (glass) glassSliderColors() else SliderDefaults.colors(),
                    thumb = {
                        if (glass) {
                            GlassSliderThumb(interactionSource, glassFraction(it))
                        } else {
                            SliderDefaults.Thumb(interactionSource = interactionSource)
                        }
                    },
                    track = { state ->
                        if (glass && glassLensKnob()) {
                            eu.kanade.presentation.reader.appbars.GlassSliderTrack(glassFraction(state))
                        } else {
                            SliderDefaults.Track(
                                colors = if (glass) glassSliderColors() else SliderDefaults.colors(),
                                sliderState = state,
                            )
                        }
                    },
                )

                Text(
                    text = totalPages.toString(),
                    // KMK -->
                    color = textColor,
                    // KMK <--
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        FilledIconButton(
            enabled = enabledNext,
            onClick = onNextChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(MR.strings.action_next_chapter),
                modifier = Modifier.rotate(90f),
            )
        }
    }
}

@Preview
@Composable
private fun ChapterNavigatorPreview() {
    var currentPage by remember { mutableIntStateOf(1) }
    TachiyomiPreviewTheme {
        ChapterNavigator(
            isRtl = false,
            onNextChapter = {},
            enabledNext = true,
            onPreviousChapter = {},
            enabledPrevious = true,
            currentPage = currentPage,
            totalPages = 10,
            onPageIndexChange = { currentPage = (it + 1) },
            // SY -->
            currentPageText = "1",
            isVerticalSlider = false,
            // SY <--
        )
        // KMK -->
        ChapterNavigator(
            isRtl = false,
            onNextChapter = {},
            enabledNext = true,
            onPreviousChapter = {},
            enabledPrevious = true,
            currentPage = currentPage,
            totalPages = 10,
            onPageIndexChange = { currentPage = (it + 1) },
            currentPageText = "1",
            isVerticalSlider = true,
        )
        // KMK <--
    }
}

/** KMK (r128): where the knob sits on the track, 0..1 */
private fun glassFraction(state: androidx.compose.material3.SliderState): Float {
    val range = state.valueRange
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else ((state.value - range.start) / span).coerceIn(0f, 1f)
}

/** KMK (r128): the Glass (lens) knob is chosen */
@androidx.compose.runtime.Composable
private fun glassLensKnob(): Boolean =
    androidx.compose.runtime.remember { eu.kanade.presentation.browse.components.GlassStyle.knob() }.get() ==
        eu.kanade.presentation.browse.components.GlassStyle.KNOB_GLASS
