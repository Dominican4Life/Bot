package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Immutable
private data class WatchRow(val source: AnimeCatalogueSource, val items: List<Anime>? = null)

/** Searches every enabled anime source (APK and JS) for one title, trying its other names when nothing turns up */
private class WatchNowSearch(private val scope: CoroutineScope) {
    private val sourceManager = Injekt.get<AnimeSourceManager>()
    private val repository = Injekt.get<AnimeRepository>()
    private val sourcePreferences = Injekt.get<SourcePreferences>()

    var rows by mutableStateOf<List<WatchRow>>(emptyList())
        private set
    private var job: Job? = null

    fun search(queries: List<String>) {
        job?.cancel()
        val names = queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return
        val disabledLangs = sourcePreferences.animeDisabledLanguages().get()
        val disabledSources = sourcePreferences.animeDisabledSources().get()
        val pinned = sourcePreferences.animePinnedSources().get()
        val sources = sourceManager.getCatalogueSources()
            .filter { it.lang !in disabledLangs && it.id.toString() !in disabledSources }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
        rows = sources.map { WatchRow(it) }
        val semaphore = Semaphore(5)
        job = scope.launch {
            sources.forEach { source ->
                launch {
                    semaphore.withPermit {
                        var found = emptyList<Anime>()
                        for (name in names) {
                            found = try {
                                withIOContext {
                                    source.getSearchAnime(1, name, source.getFilterList()).animes.mapNotNull {
                                        runCatching { repository.networkToLocal(it, source.id) }.getOrNull()
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                emptyList()
                            }
                            if (found.isNotEmpty()) break
                        }
                        val result = WatchRow(source, found)
                        rows = rows.map { if (it.source.id == source.id) result else it }
                            .sortedBy { it.items?.isEmpty() == true }
                    }
                }
            }
        }
    }
}

/**
 * KMK: Watch now: looks for the AniList title in your installed anime sources (APK and JS) so you can pick one to
 * watch from. The search box can be changed and searched again.
 */
@Composable
fun AniListWatchNowDialog(media: AniListMedia, onDismissRequest: () -> Unit) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val search = remember { WatchNowSearch(scope) }
    val names = remember(media) {
        listOfNotNull(media.english, media.romaji, media.userPreferred, media.native)
    }
    var query by remember { mutableStateOf(names.firstOrNull().orEmpty()) }
    LaunchedEffect(media.id) { search.search(names) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(KMR.strings.anilist_watch_now),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { search.search(listOf(query)) }) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search.search(listOf(query)) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val rows = search.rows
                val running = rows.any { it.items == null }
                if (running) {
                    LinearProgressIndicator(
                        progress = { rows.count { it.items != null } / rows.size.coerceAtLeast(1).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val withResults = rows.filter { !it.items.isNullOrEmpty() }
                if (withResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (running) KMR.strings.anilist_watch_now_searching else KMR.strings.anilist_watch_now_none,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(withResults, key = { it.source.id }) { row ->
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = row.source.name + "  ·  " + row.source.lang.uppercase(),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(row.items.orEmpty(), key = { it.id }) { anime ->
                                    MangaItem(
                                        title = anime.title,
                                        cover = anime.asMangaCover(),
                                        isFavorite = anime.favorite,
                                        onClick = {
                                            onDismissRequest()
                                            navigator?.push(AnimeScreen(anime.id))
                                        },
                                        onLongClick = {
                                            onDismissRequest()
                                            navigator?.push(AnimeScreen(anime.id))
                                        },
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
