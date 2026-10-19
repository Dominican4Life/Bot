package mihon.feature.migration.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.anime.asMangaCover
import eu.kanade.presentation.manga.components.MangaCover
import kotlinx.coroutines.launch
import mihon.feature.migration.anime.MigratingAnime.Candidate
import mihon.feature.migration.anime.MigratingAnime.SearchResult
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: How many candidates show under an anime before "N more candidates" */
private const val SHOWN_CANDIDATES = 2

/**
 * KMK: Under an anime on the matching screen: its match and other close results with their covers, a dot to pick
 * one and ⓘ to open it, "N more candidates" when there are more, and Candidates to search for others.
 */
@Composable
fun AnimeCandidatesSection(
    result: SearchResult,
    candidates: List<Candidate>,
    onPick: (Candidate) -> Unit,
    onInfo: (Candidate) -> Unit,
    onOpenCandidates: () -> Unit,
) {
    if (result == SearchResult.Searching) return
    val selectedId = (result as? SearchResult.Success)?.anime?.id
    Column(modifier = Modifier.padding(start = 48.dp + MaterialTheme.padding.medium)) {
        candidates.take(SHOWN_CANDIDATES).forEach { candidate ->
            AnimeCandidateRow(
                candidate = candidate,
                selected = candidate.anime.id == selectedId,
                onPick = { onPick(candidate) },
                onInfo = { onInfo(candidate) },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val more = candidates.size - SHOWN_CANDIDATES
            if (more > 0) {
                TextButton(onClick = onOpenCandidates) {
                    Text(stringResource(KMR.strings.migrate_more_candidates, more))
                }
            }
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenCandidates) {
                Text(stringResource(KMR.strings.migrate_candidates))
            }
        }
    }
}

@Composable
private fun AnimeCandidateRow(
    candidate: Candidate,
    selected: Boolean,
    onPick: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onPick)
        MangaCover.Book(
            data = candidate.anime.asMangaCover(),
            modifier = Modifier.width(44.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Text(
                text = candidate.anime.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.source + " · " + (
                    candidate.episodeCount?.let { stringResource(KMR.strings.anime_migrate_n_episodes, it) }
                        ?: stringResource(MR.strings.unknown)
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onInfo) {
            Icon(imageVector = Icons.Outlined.Info, contentDescription = null)
        }
    }
}

/**
 * KMK: A window listing an anime's candidates, with a search box to look for others in the sources being
 * searched, and pick one. Also "Search manually" ([searchNow] runs the search as soon as it opens).
 */
@Composable
fun AnimeCandidatesWindow(
    title: String,
    candidates: List<Candidate>,
    selectedId: Long?,
    search: suspend (String) -> List<Candidate>,
    onPick: (Candidate) -> Unit,
    onInfo: (Candidate) -> Unit,
    onDismissRequest: () -> Unit,
    searchNow: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(title) }
    var results by remember { mutableStateOf<List<Candidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    fun runSearch() {
        if (query.isBlank()) return
        searching = true
        scope.launch {
            results = try {
                search(query.trim())
            } finally {
                searching = false
            }
        }
    }
    LaunchedEffect(Unit) {
        if (searchNow) runSearch()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                Text(
                    text = stringResource(KMR.strings.migrate_candidates),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(KMR.strings.migrate_search_target_sources)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = ::runSearch) {
                            Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                        }
                    },
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.padding.small),
                )
                if (searching) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        CircularProgressIndicator()
                    }
                }
                // One row per anime, so the list keys stay unique
                val shown = (results ?: candidates).distinctBy { it.anime.id }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.anime.id }) { candidate ->
                        AnimeCandidateRow(
                            candidate = candidate,
                            selected = candidate.anime.id == selectedId,
                            onPick = { onPick(candidate) },
                            onInfo = { onInfo(candidate) },
                        )
                    }
                }
            }
        }
    }
}
