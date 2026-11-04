package eu.kanade.tachiyomi.jssource.lab.heal

import kotlinx.serialization.Serializable

/**
 * KMK (r116): what a tapped pick looked like when the builder last saw it, so [PickHealer] can find the same thing
 * after the site changed its markup. Only shapes are kept (tag, class and attribute names, text length…), never the
 * page itself. Saved per source and label by [PickFingerprints].
 */
@Serializable
data class Fingerprint(
    val tag: String,
    /** The element's class names as written */
    val classes: List<String> = emptyList(),
    /** Attribute names, sorted, without class / id / style */
    val attrs: List<String> = emptyList(),
    /** Length of the element's whitespace-collapsed text (capped at 2000) */
    val textLength: Int = 0,
    val words: Int = 0,
    /** Share of digits in the text, in percent */
    val digitShare: Int = 0,
    /**
     * Tags from the item card down to the element (the card itself left out, the element last); empty = the element is
     * the card; null = no repeating card was found around it
     */
    val path: List<String>? = null,
    /** Up to four ancestor tags, parent first */
    val ancestors: List<String> = emptyList(),
    val parentTag: String? = null,
    val parentClasses: List<String> = emptyList(),
    val prevTag: String? = null,
    val nextTag: String? = null,
    /** Child element tags in order (at most 12) */
    val children: List<String> = emptyList(),
    /** Tag paths below it, two levels deep ("a", "a/img", "h3"; at most 24) */
    val shape: List<String> = emptyList(),
    /** The first path segment of the element's href (or src), "" for a link to the site's root, null without one */
    val linkPrefix: String? = null,
    /** The first 80 characters of the text, for display only (never scored) */
    val sample: String = "",
    val savedAt: Long = 0L,
    val version: Int = 1,
)

/**
 * KMK (r116): one pick [PickHealer] found again: the label ("item", "title", "cover"…), the selector the code has and
 * the one that finds the same thing now, how sure it is (0–100), how many cards / elements the new selector finds of
 * [total], and up to 3 sample texts (a picture's address for covers).
 */
@Serializable
data class HealedPick(
    val label: String,
    val oldSelector: String,
    val newSelector: String,
    val score: Int,
    val matches: Int,
    val total: Int,
    val samples: List<String>,
)
