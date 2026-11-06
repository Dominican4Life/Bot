package eu.kanade.presentation.browse.components

import eu.kanade.presentation.components.GlassIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * KMK (r107, glass set in r133): the icon of a source's catalog tab in Browse, picked from its name, so catalog tabs show as icon
 * chips like Popular and Latest (icon only, the name too when selected). A name with no match gets a list icon.
 */
object CatalogTabIcons {

    private val rules: List<Pair<Regex, ImageVector>> = listOf(
        Regex("trend|rising") to GlassIcons.TabTrending,
        Regex("""\bhot|fire|viral""") to GlassIcons.TabHot,
        Regex("random|shuffle|surprise|lucky") to GlassIcons.SortRandom,
        Regex("""\btop\b|best|rank|award|chart""") to GlassIcons.TabTop,
        Regex("today|daily|week|month|year|season|calendar|schedule") to GlassIcons.TabCalendar,
        Regex("""\bnew|latest|fresh|release""") to GlassIcons.TabNew,
        Regex("recent|updated|update|added|ago") to GlassIcons.TabRecent,
        Regex("popular|views?|viewed|watched") to GlassIcons.Read,
        Regex("favou?rite|loved|liked|likes") to GlassIcons.TabFavourite,
        // KMK (r107) fix: people before the star rule ("Pornstars" is people), with the directory's people words
        Regex("actress|actor|model|performer|cast|idol|people|person|creator|artist|pornstar") to GlassIcons.TabPeople,
        Regex("featured|pick|recommend|star|rated|rating") to GlassIcons.TabFeatured,
        Regex("""\bsub|\bdub|caption""") to GlassIcons.TabSubtitles,
        Regex("movie|film|video|clip|episode") to GlassIcons.TabMovie,
    )

    fun of(name: String): ImageVector {
        val lower = name.lowercase()
        return rules.firstOrNull { (regex, _) -> regex.containsMatchIn(lower) }?.second ?: GlassIcons.LayoutList
    }
}
