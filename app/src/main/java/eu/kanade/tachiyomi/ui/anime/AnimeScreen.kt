package eu.kanade.tachiyomi.ui.anime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.AnimeCoverImage
import eu.kanade.presentation.anime.AnimeGridItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.anime.Episode
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/**
 * KMK: Anime details with its episode list (merged from Anikku).
 */
class AnimeScreen(private val animeId: Long) : Screen() {

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { AnimeScreenModel(animeId) }
        val state by screenModel.state.collectAsState()
        val anime = state.anime
        val playerPreferences = remember { Injekt.get<PlayerPreferences>() }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = anime?.title,
                    navigateUp = navigator::pop,
                    actions = {
                        val source = screenModel.source
                        if (source is AnimeHttpSource && anime != null) {
                            IconButton(
                                onClick = {
                                    val url = runCatching { source.getAnimeUrl(anime.toSAnime()) }
                                        .getOrElse { source.baseUrl + anime.url }
                                    navigator.push(WebViewScreen(url, anime.title))
                                },
                            ) {
                                Icon(Icons.Outlined.Public, contentDescription = stringResource(MR.strings.action_web_view))
                            }
                        }
                        IconButton(onClick = screenModel::refresh, enabled = !state.isRefreshing) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_webview_refresh))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (anime == null) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ScrollbarLazyColumn(
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                ) {
                    item(key = "header") {
                        AnimeHeader(
                            title = anime.title,
                            author = listOfNotNull(anime.author, anime.artist?.takeIf { it != anime.author })
                                .filter { it.isNotBlank() }
                                .joinToString(", "),
                            status = statusText(anime.status),
                            sourceName = state.sourceName,
                            thumbnailUrl = anime.thumbnailUrl,
                            source = screenModel.source,
                            favorite = anime.favorite,
                            onToggleFavorite = screenModel::toggleFavorite,
                        )
                    }
                    val description = anime.description?.takeIf { it.isNotBlank() }
                    if (description != null) {
                        item(key = "description") {
                            var expanded by rememberSaveable { mutableStateOf(true) }
                            Column {
                                SectionHeader(
                                    title = stringResource(KMR.strings.browse_description),
                                    expanded = expanded,
                                    onToggle = { expanded = !expanded },
                                )
                                AnimatedVisibility(visible = expanded) {
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .secondaryItemAlpha(),
                                    )
                                }
                            }
                        }
                    }
                    val tags = anime.genre.orEmpty().filter { it.isNotBlank() }
                    if (tags.isNotEmpty()) {
                        item(key = "tags") {
                            var expanded by rememberSaveable { mutableStateOf(true) }
                            Column {
                                SectionHeader(
                                    title = stringResource(KMR.strings.browse_tags),
                                    expanded = expanded,
                                    onToggle = { expanded = !expanded },
                                )
                                AnimatedVisibility(visible = expanded) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    ) {
                                        tags.forEach { tag ->
                                            SuggestionChip(
                                                onClick = {},
                                                label = { Text(tag, style = MaterialTheme.typography.bodySmall) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.error != null) {
                        item(key = "error") {
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (state.sourceMissing) {
                        item(key = "source_missing") {
                            Text(
                                text = stringResource(KMR.strings.anime_source_not_loaded),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (state.seasons.isNotEmpty()) {
                        item(key = "seasons") {
                            Column {
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                                Text(
                                    text = stringResource(KMR.strings.anime_seasons),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(16.dp),
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                ) {
                                    items(state.seasons, key = { it.id }) { season ->
                                        Box(Modifier.width(120.dp)) {
                                            AnimeGridItem(
                                                anime = season,
                                                source = screenModel.source,
                                                onClick = { navigator.push(AnimeScreen(season.id)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.seasons.isEmpty()) {
                        item(key = "episodes_header") {
                            Column {
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 4.dp),
                                ) {
                                    Text(
                                        text = stringResource(KMR.strings.anime_episodes_count, state.episodes.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = screenModel::toggleSort) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.Sort,
                                            contentDescription = stringResource(MR.strings.action_sort),
                                        )
                                    }
                                }
                            }
                        }
                        if (state.episodes.isEmpty() && !state.isRefreshing && state.isLoaded) {
                            item(key = "no_episodes") {
                                Text(
                                    text = stringResource(KMR.strings.anime_no_episodes),
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .secondaryItemAlpha(),
                                )
                            }
                        }
                        items(state.episodes, key = { "ep-${it.id}" }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                onClick = {
                                    // KMK: built-in player unless the outside player is chosen in settings
                                    if (playerPreferences.alwaysUseExternalPlayer().get()) {
                                        screenModel.loadVideos(episode)
                                    } else {
                                        context.startActivity(
                                            PlayerActivity.newIntent(context, episode.animeId, episode.id),
                                        )
                                    }
                                },
                                onToggleSeen = { screenModel.toggleSeen(episode) },
                                onToggleBookmark = { screenModel.toggleBookmark(episode) },
                            )
                        }
                    }
                }
            }
        }

        when (val dialog = state.videoDialog) {
            null -> {}
            is AnimeScreenModel.VideoDialog.Loading -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(dialog.episode.name) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(KMR.strings.anime_loading_videos))
                    }
                },
            )
            is AnimeScreenModel.VideoDialog.Pick -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(stringResource(KMR.strings.anime_choose_video)) },
                text = {
                    ScrollbarLazyColumn {
                        items(dialog.videos) { video ->
                            Text(
                                text = video.videoTitle.ifBlank { video.videoUrl },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        screenModel.resolveAndPlay(dialog.episode, video) { resolved ->
                                            openExternalPlayer(context, resolved, anime?.title, dialog.episode)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }
                },
            )
            is AnimeScreenModel.VideoDialog.Empty -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) { Text(stringResource(MR.strings.action_ok)) }
                },
                title = { Text(dialog.episode.name) },
                text = { Text(stringResource(KMR.strings.anime_no_videos)) },
            )
            is AnimeScreenModel.VideoDialog.Failed -> AlertDialog(
                onDismissRequest = screenModel::dismissVideoDialog,
                confirmButton = {
                    TextButton(
                        onClick = { screenModel.loadVideos(dialog.episode) },
                    ) { Text(stringResource(MR.strings.action_retry)) }
                },
                dismissButton = {
                    TextButton(onClick = screenModel::dismissVideoDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(dialog.episode.name) },
                text = { Text(dialog.message) },
            )
        }
    }
}

@Composable
private fun AnimeHeader(
    title: String,
    author: String,
    status: String?,
    sourceName: String?,
    thumbnailUrl: String?,
    source: eu.kanade.tachiyomi.animesource.AnimeSource?,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AnimeCoverImage(
                url = thumbnailUrl,
                source = source,
                modifier = Modifier
                    .width(110.dp)
                    .height(165.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (author.isNotEmpty()) {
                    Text(text = author, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.secondaryItemAlpha())
                }
                val info = listOfNotNull(status, sourceName).joinToString(" • ")
                if (info.isNotEmpty()) {
                    Text(text = info, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.secondaryItemAlpha())
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (favorite) MR.strings.in_library else MR.strings.add_to_library),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    onClick: () -> Unit,
    onToggleSeen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (episode.seen) 0.45f else 1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!episode.seen) {
                    val dotColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(8.dp)) { drawCircle(color = dotColor) }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (episode.bookmark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val subtitle = listOfNotNull(
                episode.dateUpload.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) },
                episode.scanlator?.takeIf { it.isNotBlank() },
                if (episode.fillermark) "Filler" else null,
            ).joinToString(" • ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        }
        IconButton(onClick = onToggleSeen) {
            Icon(
                imageVector = if (episode.seen) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(
                    if (episode.seen) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                ),
            )
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                imageVector = if (episode.bookmark) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = stringResource(
                    if (episode.bookmark) MR.strings.action_remove_bookmark else MR.strings.action_bookmark,
                ),
                tint = if (episode.bookmark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusText(status: Long): String? {
    return when (status.toInt()) {
        SAnime.ONGOING -> stringResource(MR.strings.ongoing)
        SAnime.COMPLETED -> stringResource(MR.strings.completed)
        SAnime.LICENSED -> stringResource(MR.strings.licensed)
        SAnime.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
        SAnime.CANCELLED -> stringResource(MR.strings.cancelled)
        SAnime.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
        else -> null
    }
}

/** Plays a video in an outside player app until the built-in player arrives. Passes headers the way MX/mpv/VLC read them. */
private fun openExternalPlayer(context: Context, video: Video, animeTitle: String?, episode: Episode) {
    val headers = video.headers
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(video.videoUrl.toUri(), "video/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val title = listOfNotNull(animeTitle, episode.name).joinToString(" - ")
        putExtra("title", title)
        putExtra(Intent.EXTRA_TITLE, title)
        if (headers != null && headers.size > 0) {
            val pairs = ArrayList<String>()
            headers.forEach { (name, value) ->
                pairs += name
                pairs += value
            }
            putExtra("headers", pairs.toTypedArray())
        }
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.toast(KMR.strings.anime_no_player)
    }
}
