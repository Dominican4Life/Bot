package mihon.feature.migration.list

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.manga.components.MangaCover
import kotlinx.coroutines.launch
import mihon.feature.migration.list.models.MigratingManga.Candidate
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** KMK: How many candidates show under a title before "N more candidates" */
private const val SHOWN_CANDIDATES = 2

/**
 * KMK: Under a title on the matching screen: its match and other close results with their covers, a dot to pick
 * one and ⓘ to open it, "N more candidates" when there are more, and Candidates to search for others.
 */
@Composable
fun CandidatesSection(
    result: SearchResult,
    candidates: List<Candidate>,
    onPick: (Candidate) -> Unit,
    onInfo: (Candidate) -> Unit,
    onOpenCandidates: () -> Unit,
) {
    if (result == SearchResult.Searching) return
    val selectedId = (result as? SearchResult.Success)?.manga?.id
    Column(modifier = Modifier.padding(start = 48.dp + MaterialTheme.padding.medium)) {
        candidates.take(SHOWN_CANDIDATES).forEach { candidate ->
            CandidateRow(
                candidate = candidate,
                selected = candidate.manga.id == selectedId,
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
private fun CandidateRow(
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
            data = candidate.manga,
            modifier = Modifier.width(44.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Text(
                text = candidate.manga.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.source + " · " + (
                    candidate.chapterCount?.let { stringResource(KMR.strings.migrate_n_chapters, it) }
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
 * KMK: A window listing a title's candidates, with a search box to look for others in the sources being searched,
 * and pick one.
 */
@Composable
fun CandidatesWindow(
    title: String,
    candidates: List<Candidate>,
    selectedId: Long?,
    search: suspend (String) -> List<Candidate>,
    onPick: (Candidate) -> Unit,
    onInfo: (Candidate) -> Unit,
    onDismissRequest: () -> Unit,
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
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.padding.small),
                )
                if (searching) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        CircularProgressIndicator()
                    }
                }
                val shown = results ?: candidates
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.manga.id }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            selected = candidate.manga.id == selectedId,
                            onPick = { onPick(candidate) },
                            onInfo = { onInfo(candidate) },
                        )
                    }
                }
            }
        }
    }
}
