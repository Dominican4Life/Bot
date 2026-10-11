package tachiyomi.domain.history.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.updates.service.USE_PANORAMA_COVER_PREF

class HistoryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun filterUnfinishedManga() = preferenceStore.getEnum(
        "pref_filter_history_unfinished_manga",
        TriState.DISABLED,
    )

    fun filterUnfinishedChapter() = preferenceStore.getEnum(
        "pref_filter_history_unfinished_chapter",
        TriState.DISABLED,
    )

    fun filterNonLibraryManga() = preferenceStore.getEnum(
        "pref_filter_history_non_library_manga",
        TriState.DISABLED,
    )

    fun usePanoramaCover() = preferenceStore.getBoolean(
        USE_PANORAMA_COVER_PREF,
        false,
    )

    // KMK -->
    /** How history is shown: a list (default) or one of the library's grids */
    fun displayMode() = preferenceStore.getObjectFromString(
        "pref_history_display_mode",
        LibraryDisplayMode.List,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    /** Covers per row in the grid modes, 0 = auto */
    fun columns() = preferenceStore.getInt("pref_history_columns", 0)
    // KMK <--
}
