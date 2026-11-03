package eu.kanade.domain.ui.model

/** KMK: How "Panorama comfortable grid" shows wide covers */
enum class PanoramaStyle {
    /** Centered at full width, the cover's color filling above and below: same size as the other covers */
    FILL,

    /** Full width and wide, so shorter than the other covers */
    FIT,

    /** Same height as the other covers, so it takes two places in the row */
    WIDE,
}

/**
 * KMK (r115): the one "Wide covers" look of Settings › Appearance. [OFF] crops wide covers like the others; the other
 * three are the [PanoramaStyle]s. WideCoverSync maps it onto the per-screen prefs (they all stay, for backups).
 */
enum class WideCoverStyle(val panorama: PanoramaStyle?) {
    OFF(null),
    FILL(PanoramaStyle.FILL),
    FIT(PanoramaStyle.FIT),
    WIDE(PanoramaStyle.WIDE),
    ;

    companion object {
        fun of(style: PanoramaStyle): WideCoverStyle = when (style) {
            PanoramaStyle.FILL -> FILL
            PanoramaStyle.FIT -> FIT
            PanoramaStyle.WIDE -> WIDE
        }
    }
}
