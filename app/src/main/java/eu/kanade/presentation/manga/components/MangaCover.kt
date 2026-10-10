package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R

enum class MangaCover(val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),

    // SY -->
    Panorama(3f / 2f),
    // SY <--
    ;

    @Composable
    operator fun invoke(
        data: Any?,
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.extraSmall,
        onClick: (() -> Unit)? = null,
        // SY -->
        /** Called with the height / width ratio of the loaded image */
        onCoverLoaded: ((ratio: Float) -> Unit)? = null,
        contentScale: ContentScale = ContentScale.Crop,
        // SY <--
    ) {
        AsyncImage(
            model = data,
            placeholder = ColorPainter(CoverPlaceholderColor),
            error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
            // SY -->
            onSuccess = onCoverLoaded?.let { callback ->
                { state: AsyncImagePainter.State.Success ->
                    val image = state.result.image
                    if (image.width > 0) callback(image.height.toFloat() / image.width)
                }
            },
            // SY <--
            contentDescription = contentDescription,
            modifier = modifier
                .aspectRatio(ratio)
                .clip(shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentScale = contentScale,
        )
    }
}

private val CoverPlaceholderColor = Color(0x1F888888)

// SY -->

/** Covers whose height / width ratio is at or below this are wide enough to show as panorama */
const val RatioSwitchToPanorama = 0.75f
// SY <--
