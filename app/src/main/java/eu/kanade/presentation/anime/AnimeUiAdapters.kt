package eu.kanade.presentation.anime

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/*
 * KMK: Anime are shown with the same building blocks as manga (covers, info header, grids), so the anime screens
 * look and behave like the manga ones. These turn an anime into the manga models those components take.
 * Negative ids keep anime apart from manga in cover caches.
 */

fun Anime.coverId(): Long = -id

fun Anime.asMangaCover(): MangaCover = MangaCover(
    mangaId = coverId(),
    sourceId = source,
    isMangaFavorite = favorite,
    ogUrl = thumbnailUrl,
    lastModified = 0L,
)

fun Anime.asDisplayManga(): Manga = Manga.create().copy(
    id = coverId(),
    source = source,
    favorite = favorite,
    url = url,
    ogTitle = title,
    ogArtist = artist,
    ogAuthor = author,
    ogThumbnailUrl = thumbnailUrl,
    ogDescription = description,
    ogGenre = genre,
    ogStatus = status,
    initialized = initialized,
    dateAdded = dateAdded,
)

/** Picks the theme color from the anime's cover, like the manga details page does. */
fun loadAnimeSeedColor(anime: Anime, onColor: (Color) -> Unit) {
    if (!Injekt.get<UiPreferences>().themeCoverBased().get()) return
    val cover = anime.asMangaCover()
    cover.vibrantCoverColor?.let {
        onColor(Color(it))
        return
    }
    if (anime.thumbnailUrl.isNullOrBlank()) return
    val context = Injekt.get<Application>()
    val request = ImageRequest.Builder(context)
        .data(cover)
        .allowHardware(false)
        .target(
            onSuccess = { image ->
                val bitmap = image.asDrawable(context.resources).getBitmapOrNull() ?: return@target
                Palette.from(bitmap).generate { palette ->
                    val color = palette?.getBestColor() ?: return@generate
                    cover.vibrantCoverColor = color
                    onColor(Color(color))
                }
            },
        )
        .build()
    context.imageLoader.enqueue(request)
}
