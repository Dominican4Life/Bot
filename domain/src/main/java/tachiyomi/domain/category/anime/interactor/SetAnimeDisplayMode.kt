package tachiyomi.domain.category.anime.interactor

import tachiyomi.domain.library.anime.service.AnimeLibraryPreferences
import tachiyomi.domain.library.model.LibraryDisplayMode

class SetAnimeDisplayMode(
    private val preferences: AnimeLibraryPreferences,
) {

    fun await(display: LibraryDisplayMode) {
        preferences.displayMode().set(display)
    }
}
