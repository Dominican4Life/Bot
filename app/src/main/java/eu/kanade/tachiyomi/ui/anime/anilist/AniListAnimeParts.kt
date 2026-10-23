package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import kotlin.math.ceil

/** KMK: Filler episodes get a gold frame, like Hayase */
private val FillerGold = Color(0xFFFACC15)

/**
 * KMK: The top of the AniList anime page: the banner behind, the cover with Watch now under it, and all three
 * titles: Japanese above, the main one, then Romaji.
 */
@Composable
fun AniListHeader(
    details: AniListDetails,
    isTablet: Boolean,
    onWatchNow: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val card = details.card
    val background = MaterialTheme.colorScheme.background
    Box {
        AsyncImage(
            model = card.banner ?: card.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, background),
                            startY = size.height / 3,
                        ),
                    )
                }
                .alpha(0.35f),
        )
        if (isTablet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.6f)) {
                    Cover(card)
                    WatchNowButton(onWatchNow, Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Titles(details, TextAlign.Center, Alignment.CenterHorizontally, onSearch)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.width(112.dp)) {
                    Cover(card)
                    WatchNowButton(onWatchNow, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Titles(details, TextAlign.Start, Alignment.Start, onSearch)
                }
            }
        }
    }
}

@Composable
private fun Cover(card: AniListMedia) {
    AsyncImage(
        model = card.cover,
        contentDescription = card.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun WatchNowButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = stringResource(KMR.strings.anilist_watch_now), maxLines = 1, fontSize = 13.sp)
    }
}

@Composable
private fun Titles(
    details: AniListDetails,
    textAlign: TextAlign,
    alignment: Alignment.Horizontal,
    onSearch: (String) -> Unit,
) {
    val card = details.card
    Column(horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        card.native?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                textAlign = textAlign,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = textAlign,
            modifier = Modifier.clickableNoIndication(onClick = { onSearch(card.title) }),
        )
        card.romaji?.takeIf { it.isNotBlank() && it != card.title }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                textAlign = textAlign,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (card.studios.isNotEmpty()) {
            InfoLine(Icons.Outlined.Business, card.studios.joinToString(", "))
        }
        val meta = listOfNotNull(
            AniListLabels.format(card.format).takeIf { it.isNotEmpty() },
            AniListLabels.status(card.status).takeIf { it.isNotEmpty() },
            listOfNotNull(AniListLabels.season(card.season).takeIf { it.isNotEmpty() }, card.seasonYear)
                .joinToString(" ")
                .takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) InfoLine(Icons.Outlined.Info, meta)
        val length = listOfNotNull(
            card.episodes?.let { stringResource(KMR.strings.anilist_episode_count, it) },
            card.duration?.let { "${it}m" },
        ).joinToString(" · ")
        if (length.isNotEmpty()) InfoLine(Icons.Outlined.Schedule, length)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.secondaryItemAlpha(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text = text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** KMK: WebView, then Trailer, AniList and MAL (only on these AniList pages) */
@Composable
fun AniListActionRow(details: AniListDetails, actions: AniListActions) {
    Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)) {
        ActionButton(
            title = stringResource(MR.strings.action_web_view),
            icon = { Icon(Icons.Outlined.Public, contentDescription = null, tint = it, modifier = Modifier.size(20.dp)) },
            onClick = actions.onWebView,
        )
        ActionButton(
            title = stringResource(KMR.strings.anilist_trailer),
            icon = {
                Icon(Icons.Outlined.SmartDisplay, contentDescription = null, tint = it, modifier = Modifier.size(20.dp))
            },
            onClick = actions.onTrailer,
        )
        ActionButton(
            title = stringResource(KMR.strings.anilist_open_anilist),
            icon = { LetterMark("AL", it) },
            onClick = actions.onAniList,
        )
        ActionButton(
            title = stringResource(KMR.strings.anilist_open_mal),
            icon = { LetterMark("MAL", it) },
            onClick = actions.onMal,
        )
    }
}

/** Plain letters in a rounded box, for the AniList and MAL buttons */
@Composable
private fun LetterMark(letters: String, color: Color) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .widthIn(min = 20.dp)
            .border(1.5.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letters, color = color, fontSize = 9.sp, fontWeight = FontWeight.Black, lineHeight = 10.sp)
    }
}

@Composable
private fun RowScope.ActionButton(
    title: String,
    icon: @Composable (Color) -> Unit,
    onClick: (() -> Unit)?,
) {
    val color = if (onClick != null) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    }
    TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon(color)
            Spacer(Modifier.height(4.dp))
            Text(text = title, color = color, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Score, popularity, favourites, reviews and the next episode */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AniListStatsRow(details: AniListDetails, modifier: Modifier = Modifier) {
    val card = details.card
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (card.averageScore ?: details.meanScore)?.let { score ->
            Stat(Icons.Outlined.StarOutline, "$score%", tint = FillerGold)
        }
        details.popularity?.let { Stat(Icons.Outlined.Groups, compact(it)) }
        details.favourites?.let { Stat(Icons.Outlined.FavoriteBorder, compact(it)) }
        if (details.reviews > 0) {
            Stat(Icons.Outlined.RateReview, stringResource(KMR.strings.anilist_reviews, details.reviews))
        }
        card.nextAiring?.takeIf { it.airingAt > 0 }?.let { next ->
            Stat(
                Icons.Outlined.Schedule,
                stringResource(KMR.strings.anilist_next_airing, next.episode, airsIn(next.airingAt)),
            )
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, text: String, tint: Color = LocalContentColor.current) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun compact(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
    value >= 1_000 -> "%.1fK".format(value / 1_000f)
    else -> value.toString()
}

/** "in 2d 4h" from AniList's airing time (seconds) */
private fun airsIn(airingAt: Long): String {
    val seconds = airingAt - System.currentTimeMillis() / 1000
    if (seconds <= 0) return "now"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "in ${days}d ${hours}h"
        hours > 0 -> "in ${hours}h ${minutes}m"
        else -> "in ${minutes}m"
    }
}

/**
 * KMK: Hayase's episode cards: the picture with the minutes and ★ rating on it, the title and summary beside it.
 * Filler episodes have a gold frame and "Filler" in the corner. 16 a page.
 */
@Composable
fun AniListEpisodes(
    episodes: List<AniListEpisode>,
    loading: Boolean,
    columns: Int,
    onClick: (AniListEpisode) -> Unit,
) {
    if (episodes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = stringResource(KMR.strings.anilist_no_episodes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val perPage = 16
    val pages = ceil(episodes.size / perPage.toFloat()).toInt()
    var page by rememberSaveable(episodes.size) { mutableIntStateOf(0) }
    val start = page * perPage
    val end = minOf(start + perPage, episodes.size)
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        episodes.subList(start, end).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { episode ->
                    EpisodeCard(episode, onClick = { onClick(episode) }, modifier = Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        if (pages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { page-- }, enabled = page > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                }
                Text(
                    text = "${start + 1}–$end / ${episodes.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                IconButton(onClick = { page++ }, enabled = page < pages - 1) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: AniListEpisode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (episode.filler) BorderStroke(1.5.dp, FillerGold) else null,
    ) {
        Box {
            Row {
                if (episode.image != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 10f),
                    ) {
                        AsyncImage(
                            model = episode.image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        episode.minutes?.let { minutes ->
                            ImageBadge("${minutes}m", modifier = Modifier.align(Alignment.BottomStart))
                        }
                        episode.rating?.let { rating ->
                            ImageBadge(rating, star = true, modifier = Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "${episode.number}. " +
                            (episode.title ?: stringResource(KMR.strings.anilist_episode_number, episode.number)),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episode.summary.orEmpty(),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    episode.airDate?.let {
                        Text(text = it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (episode.filler) {
                Text(
                    text = stringResource(KMR.strings.anilist_filler),
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(FillerGold, RoundedCornerShape(topStart = 6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ImageBadge(text: String, modifier: Modifier = Modifier, star: Boolean = false) {
    Row(
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (star) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = FillerGold, modifier = Modifier.size(10.dp))
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(text = text, color = Color.White, fontSize = 9.sp)
    }
}

/** KMK: Openings and endings (AnimeThemes), with a play button for each version */
@Composable
fun AniListThemes(themes: List<AniListTheme>?, loading: Boolean, onPlay: (String) -> Unit) {
    if (themes.isNullOrEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading || themes == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = stringResource(KMR.strings.anilist_no_themes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        themes.forEach { theme ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = theme.type,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(52.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = theme.title, fontWeight = FontWeight.Bold)
                            if (theme.artists.isNotEmpty()) {
                                Text(
                                    text = stringResource(KMR.strings.anilist_theme_by, theme.artists.joinToString(", ")),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    theme.entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "v${entry.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(
                                text = if (entry.episodes.isNotBlank()) {
                                    stringResource(KMR.strings.anilist_theme_episodes, entry.episodes)
                                } else {
                                    ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            entry.videoUrl?.let { url ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .clickableNoIndication(onClick = { onPlay(url) }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** KMK: AniList's recommendations, as small cards; tapping one opens its AniList page */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AniListRecommendations(recommendations: List<AniListMedia>, onClick: (Int) -> Unit) {
    if (recommendations.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(KMR.strings.anilist_no_recommendations),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    FlowRow(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        recommendations.forEach { media ->
            Box(modifier = Modifier.width(110.dp)) {
                AniListSmallCard(media, null, onClick = { onClick(media.id) })
            }
        }
    }
}
