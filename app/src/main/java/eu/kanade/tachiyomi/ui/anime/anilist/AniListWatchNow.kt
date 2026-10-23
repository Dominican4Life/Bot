package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import eu.kanade.presentation.browse.components.MangaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Searches every enabled source for one title, trying its other names when nothing turns up */
private class SourcePickerSearch(private val scope: CoroutineScope, private val manga: Boolean) {
    var rows by mutableStateOf<List<PickRow>>(emptyList())
        private set
    private var job: Job? = null

    fun search(queries: List<String>, names: List<String>) {
        job?.cancel()
        val tries = queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (tries.isEmpty()) return
        val sources = AniListPlayback.sources(manga)
        rows = sources.map { (id, name, lang) -> PickRow(id, name, lang) }
        val semaphore = Semaphore(5)
        job = scope.launch {
            sources.forEach { (sourceId, _, _) ->
                launch {
                    semaphore.withPermit {
                        var found = emptyList<PickItem>()
                        for (query in tries) {
                            found = try {
                                AniListPlayback.search(sourceId, query, manga)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                emptyList()
                            }
                            if (found.isNotEmpty()) break
                        }
                        // Best match first
                        val sorted = found.sortedByDescending { AniListPlayback.similarity(it.title, names) }
                        rows = rows.map { if (it.sourceId == sourceId) it.copy(items = sorted) else it }
                            .sortedWith(
                                compareBy<PickRow> { it.items?.isEmpty() == true }
                                    .thenByDescending { row ->
                                        row.items?.firstOrNull()?.let { AniListPlayback.similarity(it.title, names) } ?: 0.0
                                    },
                            )
                    }
                }
            }
        }
    }
}

/**
 * KMK: "Pick a source": looks for the AniList title in your installed sources (APK and JS) with the best match of
 * each source first (outlined). Picking one plays the episode (or opens the chapter) straight from it.
 */
@Composable
fun AniListSourcePicker(
    media: AniListMedia,
    manga: Boolean,
    onDismissRequest: () -> Unit,
    onPick: (PickRow, PickItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val search = remember(manga) { SourcePickerSearch(scope, manga) }
    val names = remember(media) {
        listOfNotNull(media.english, media.romaji, media.userPreferred, media.native).filter { it.isNotBlank() }
    }
    var query by remember { mutableStateOf(names.firstOrNull().orEmpty()) }
    LaunchedEffect(media.id) { search.search(names, names) }

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
                        text = stringResource(KMR.strings.anilist_pick_source),
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
                        IconButton(onClick = { search.search(listOf(query), names + query) }) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search.search(listOf(query), names + query) }),
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
                    items(withResults, key = { it.sourceId }) { row ->
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = row.sourceName + "  ·  " + row.lang.uppercase(),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(row.items.orEmpty(), key = { _, item -> item.id }) { index, item ->
                                    // The best match of each source is outlined
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = if (index == 0) {
                                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                        } else {
                                            null
                                        },
                                    ) {
                                        MangaItem(
                                            title = item.title,
                                            cover = item.cover,
                                            isFavorite = item.favorite,
                                            onClick = {
                                                onDismissRequest()
                                                onPick(row, item)
                                            },
                                            onLongClick = {
                                                onDismissRequest()
                                                onPick(row, item)
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
}
