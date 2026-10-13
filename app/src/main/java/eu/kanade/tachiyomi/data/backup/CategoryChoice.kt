package eu.kanade.tachiyomi.data.backup

/**
 * KMK: Keys for picking categories in backup and restore: manga and anime categories by name, and titles in no
 * category of each kind.
 */
object CategoryChoice {
    private const val MANGA = "manga:"
    private const val ANIME = "anime:"

    /** A manga category by name; an empty name for manga in no category */
    fun manga(name: String?) = MANGA + name.orEmpty()

    /** An anime category by name; an empty name for anime in no category */
    fun anime(name: String?) = ANIME + name.orEmpty()

    /** True when a title in [categories] (names; empty for none) was picked; always true when [keys] is null */
    fun keepManga(keys: Set<String>?, categories: List<String>): Boolean =
        keys == null || (if (categories.isEmpty()) manga(null) in keys else categories.any { manga(it) in keys })

    fun keepAnime(keys: Set<String>?, categories: List<String>): Boolean =
        keys == null || (if (categories.isEmpty()) anime(null) in keys else categories.any { anime(it) in keys })
}
