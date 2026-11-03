package eu.kanade.presentation.more.settings

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.domain.ui.model.WideCoverStyle
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r115): the places that show wide (landscape) covers, each backed by prefs that already existed.
 * [hasStyles] places take Off / Color fill / Fit width / Wide; the others are on/off switches.
 */
enum class WideCoverPlace(val hasStyles: Boolean) {
    /** Library (manga + anime): `pref_library_panorama_style` + the library's Comfortable ↔ Wide covers grid */
    LIBRARY(true),

    /** A source's list (manga + anime) and related titles: `pref_browse_panorama_style` + the source grid */
    BROWSE(true),

    /** History and Updates: `pref_updates_history_screen_use_panorama_cover` (one key) + their grids */
    HISTORY_UPDATES(false),

    /** Search and suggestion rows: `use_panorama_cover_flow` */
    SEARCH(false),

    /** Title page header: `use_panorama_cover_manga_info` */
    TITLE_HEADER(false),

    /** Edit-title and duplicate dialogs: `use_panorama_cover_grid` */
    DIALOGS(false),
}

/**
 * KMK (r115): the one helper that keeps the wide-cover prefs in step. Every place that changes one of them
 * (Settings › Appearance, the Library / Browse / History / Updates view menus) calls [pick]; with "Same everywhere"
 * on (default) the look is copied to every place, otherwise only that place changes. No pref was removed or renamed,
 * so backups and older installs keep working. Changing only a display mode (Compact, List…) is not a wide-cover change and is not
 * copied, except that "Off (crop)" ↔ a style swaps a place's plain Comfortable grid ↔ its Wide covers grid.
 */
object WideCoverSync {
    private val ui: UiPreferences by lazy { Injekt.get() }
    private val library: LibraryPreferences by lazy { Injekt.get() }
    private val source: SourcePreferences by lazy { Injekt.get() }
    private val history: HistoryPreferences by lazy { Injekt.get() }
    private val updates: UpdatesPreferences by lazy { Injekt.get() }

    /** [place] was set to [style] by the user: copied everywhere when "Same everywhere" is on */
    fun pick(place: WideCoverPlace, style: WideCoverStyle) {
        if (ui.wideCoverSameEverywhere().get()) {
            setEverywhere(style)
        } else {
            apply(place, style)
        }
    }

    /** A view-menu switch (History / Updates "Panorama cover") was turned on or off */
    fun pickSwitch(place: WideCoverPlace, on: Boolean) {
        pick(place, if (on) onStyle() else WideCoverStyle.OFF)
    }

    /** The Appearance picker, and turning "Same everywhere" back on: every place gets [style] */
    fun setEverywhere(style: WideCoverStyle) {
        ui.wideCoverStyle().set(style)
        WideCoverPlace.entries.forEach { apply(it, style) }
    }

    /** Sets one place only (the per-place list when "Same everywhere" is off) */
    fun apply(place: WideCoverPlace, style: WideCoverStyle) {
        val on = style != WideCoverStyle.OFF
        when (place) {
            WideCoverPlace.LIBRARY -> {
                style.panorama?.let { ui.libraryPanoramaStyle().set(it) }
                swapGrid(library.displayMode(), on)
            }
            WideCoverPlace.BROWSE -> {
                style.panorama?.let { ui.browsePanoramaStyle().set(it) }
                swapGrid(source.sourceDisplayMode(), on)
            }
            WideCoverPlace.HISTORY_UPDATES -> {
                // History and Updates share one key (USE_PANORAMA_COVER_PREF); their grids use the Library's style
                history.usePanoramaCover().set(on)
                swapGrid(history.displayMode(), on)
                swapGrid(updates.displayMode(), on)
            }
            WideCoverPlace.SEARCH -> ui.usePanoramaCoverFlow().set(on)
            WideCoverPlace.TITLE_HEADER -> ui.usePanoramaCoverMangaInfo().set(on)
            WideCoverPlace.DIALOGS -> ui.usePanoramaCoverAlways().set(on)
        }
    }

    /** What [place] shows now (for a switch place: [WideCoverStyle.OFF] or the shared look when on) */
    fun current(place: WideCoverPlace): WideCoverStyle = when (place) {
        WideCoverPlace.LIBRARY -> styleOf(library.displayMode().get(), ui.libraryPanoramaStyle().get())
        WideCoverPlace.BROWSE -> styleOf(source.sourceDisplayMode().get(), ui.browsePanoramaStyle().get())
        WideCoverPlace.HISTORY_UPDATES -> switchStyle(history.usePanoramaCover().get())
        WideCoverPlace.SEARCH -> switchStyle(ui.usePanoramaCoverFlow().get())
        WideCoverPlace.TITLE_HEADER -> switchStyle(ui.usePanoramaCoverMangaInfo().get())
        WideCoverPlace.DIALOGS -> switchStyle(ui.usePanoramaCoverAlways().get())
    }

    /** A place's look from its grid and style: the plain Comfortable grid crops (Off); any other grid uses the style */
    fun styleOf(mode: LibraryDisplayMode, style: PanoramaStyle): WideCoverStyle =
        if (mode == LibraryDisplayMode.ComfortableGrid) WideCoverStyle.OFF else WideCoverStyle.of(style)

    /** The style a switch place gets when it is turned on */
    private fun onStyle(): WideCoverStyle {
        val shared = ui.wideCoverStyle().get()
        return if (shared != WideCoverStyle.OFF) shared else WideCoverStyle.of(ui.libraryPanoramaStyle().get())
    }

    private fun switchStyle(on: Boolean): WideCoverStyle = if (on) onStyle() else WideCoverStyle.OFF

    /** Off: a Wide covers grid becomes the plain Comfortable grid; a style: the other way round. Other grids stay. */
    private fun swapGrid(pref: Preference<LibraryDisplayMode>, on: Boolean) {
        val mode = pref.get()
        if (on && mode == LibraryDisplayMode.ComfortableGrid) {
            pref.set(LibraryDisplayMode.ComfortableGridPanorama)
        } else if (!on && mode == LibraryDisplayMode.ComfortableGridPanorama) {
            pref.set(LibraryDisplayMode.ComfortableGrid)
        }
    }
}
