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
