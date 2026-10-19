package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.data.anime.Anime
import eu.kanade.tachiyomi.data.anime.track.AnimeTrack
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackRepository
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackSearch
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackService
import eu.kanade.tachiyomi.data.anime.track.AnimeTrackStatus
import eu.kanade.tachiyomi.data.anime.track.AnimeTracking
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/**
 * KMK: the anime tracking sheet (like the manga one): every tracker that can track anime, bind a title by
 * searching, then change status, episodes, score and dates. Changes are sent to the tracker right away.
 */
@Composable
fun AnimeTrackSheet(
    anime: Anime,
    /** Highest episode number marked seen, used when a new title is bound */
    lastSeenEpisode: Double,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { Injekt.get<AnimeTrackRepository>() }
    val tracks by remember(anime.id) { repository.subscribeTracks(anime.id) }.collectAsState(initial = emptyList())
    var searchFor by remember { mutableStateOf<AnimeTrackService?>(null) }
    var removing by remember { mutableStateOf<Pair<AnimeTrackService, AnimeTrack>?>(null) }

    fun failed(service: AnimeTrackService, e: Throwable) {
        logcat(LogPriority.WARN, e) { "Anime tracking: ${service.name}" }
        context.toast("${service.name}: ${e.message ?: e.javaClass.simpleName}")
    }

    fun update(service: AnimeTrackService, track: AnimeTrack) {
        scope.launch {
            repository.upsert(track)
            try {
                repository.upsert(service.save(track))
            } catch (e: Throwable) {
                failed(service, e)
            }
        }
    }

    // Pull the latest from each tracker when the sheet opens
    LaunchedEffect(anime.id) {
        repository.getTracks(anime.id).forEach { track ->
            val service = AnimeTracking.service(track.trackerId)?.takeIf { it.isLoggedIn } ?: return@forEach
            try {
                service.find(anime.id, track.remoteId)?.let { repository.upsert(it.copy(title = it.title.ifBlank { track.title })) }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime tracking: refresh ${service.name}" }
            }
        }
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = stringResource(KMR.strings.anime_track_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AnimeTracking.services.forEachIndexed { index, service ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                TrackerRow(
                    service = service,
                    track = tracks.firstOrNull { it.trackerId == service.id },
                    onAdd = { searchFor = service },
                    onChange = { update(service, it) },
                    onRemove = { removing = service to it },
                    onOpen = { context.openInBrowser(it.remoteUrl) },
                )
            }
        }
    }

    searchFor?.let { service ->
        TrackSearchDialog(
            service = service,
            initialQuery = anime.title,
            onDismissRequest = { searchFor = null },
            onPick = { result ->
                searchFor = null
                scope.launch {
                    try {
                        val existing = service.find(anime.id, result.remoteId)
                        val track = existing ?: AnimeTrack(
                            animeId = anime.id,
                            trackerId = service.id,
                            remoteId = result.remoteId,
                            libraryId = null,
                            title = result.title,
                            lastEpisodeSeen = lastSeenEpisode.coerceAtLeast(0.0),
                            totalEpisodes = result.totalEpisodes,
                            status = if (lastSeenEpisode > 0) AnimeTrackStatus.WATCHING else AnimeTrackStatus.PLAN_TO_WATCH,
                            score = 0.0,
                            remoteUrl = result.remoteUrl,
                            startDate = if (lastSeenEpisode > 0) System.currentTimeMillis() else 0L,
                            finishDate = 0L,
                            private = false,
                        ).let { service.save(it) }
                        repository.upsert(
                            track.copy(
                                title = track.title.ifBlank { result.title },
                                totalEpisodes = track.totalEpisodes.takeIf { it > 0 } ?: result.totalEpisodes,
                            ),
                        )
                    } catch (e: Throwable) {
                        failed(service, e)
                    }
                }
            },
        )
    }

    removing?.let { (service, track) ->
        var alsoRemote by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(KMR.strings.anime_track_remove)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { alsoRemote = !alsoRemote },
                ) {
                    Checkbox(checked = alsoRemote, onCheckedChange = { alsoRemote = it })
                    Text(stringResource(KMR.strings.anime_track_remove_remote, service.name))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val remote = alsoRemote
                        removing = null
                        scope.launch {
                            if (remote) {
                                try {
                                    service.delete(track)
                                } catch (e: Throwable) {
                                    failed(service, e)
                                }
                            }
                            repository.delete(track.animeId, track.trackerId)
                        }
                    },
                ) { Text(stringResource(KMR.strings.anime_track_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TrackerRow(
    service: AnimeTrackService,
    track: AnimeTrack?,
    onAdd: () -> Unit,
    onChange: (AnimeTrack) -> Unit,
    onRemove: (AnimeTrack) -> Unit,
    onOpen: (AnimeTrack) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(service.tracker.getLogo()),
                contentDescription = service.name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(service.name, style = MaterialTheme.typography.titleMedium)
                when {
                    !service.isLoggedIn -> Text(
                        stringResource(KMR.strings.anime_track_not_logged_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    track != null -> Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpen(track) },
                    )
                }
            }
            if (track != null) {
                IconButton(onClick = { onOpen(track) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(KMR.strings.anime_track_open))
                }
                IconButton(onClick = { onRemove(track) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(KMR.strings.anime_track_remove))
                }
            } else if (service.isLoggedIn) {
                OutlinedButton(onClick = onAdd) { Text(stringResource(KMR.strings.anime_track_add)) }
            }
        }
        if (track != null && service.isLoggedIn) {
            Spacer(Modifier.size(8.dp))
            StatusField(track.status) { onChange(track.copy(status = it)) }
            EpisodesField(track) { onChange(it) }
            ScoreField(track.score) { onChange(track.copy(score = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(
                    label = stringResource(KMR.strings.anime_track_start),
                    millis = track.startDate,
                    modifier = Modifier.weight(1f),
                ) { onChange(track.copy(startDate = it)) }
                DateField(
                    label = stringResource(KMR.strings.anime_track_finish),
                    millis = track.finishDate,
                    modifier = Modifier.weight(1f),
                ) { onChange(track.copy(finishDate = it)) }
            }
        }
    }
}

@Composable
private fun statusLabel(status: Long): String = stringResource(
    when (status) {
        AnimeTrackStatus.WATCHING -> KMR.strings.anime_track_status_watching
        AnimeTrackStatus.COMPLETED -> KMR.strings.anime_track_status_completed
        AnimeTrackStatus.ON_HOLD -> KMR.strings.anime_track_status_on_hold
        AnimeTrackStatus.DROPPED -> KMR.strings.anime_track_status_dropped
        AnimeTrackStatus.REWATCHING -> KMR.strings.anime_track_status_rewatching
        else -> KMR.strings.anime_track_status_plan
    },
)

@Composable
private fun FieldRow(label: String, value: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        trailing()
    }
}

@Composable
private fun StatusField(status: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldRow(stringResource(KMR.strings.anime_track_status), statusLabel(status), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AnimeTrackStatus.all.forEach { s ->
                DropdownMenuItem(text = { Text(statusLabel(s)) }, onClick = {
                    open = false
                    onPick(s)
                })
            }
        }
    }
}

@Composable
private fun EpisodesField(track: AnimeTrack, onChange: (AnimeTrack) -> Unit) {
    val seen = track.lastEpisodeSeen.toInt()
    val total = track.totalEpisodes.toInt()
    fun set(value: Int) {
        val v = if (total > 0) value.coerceIn(0, total) else value.coerceAtLeast(0)
        val done = total > 0 && v >= total
        onChange(
            track.copy(
                lastEpisodeSeen = v.toDouble(),
                status = if (done) AnimeTrackStatus.COMPLETED else track.status,
                finishDate = if (done && track.finishDate <= 0) System.currentTimeMillis() else track.finishDate,
            ),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(KMR.strings.anime_track_episodes), modifier = Modifier.weight(1f))
        IconButton(onClick = { set(seen - 1) }, enabled = seen > 0) { Icon(Icons.Outlined.Remove, null) }
        Text(
            if (total > 0) "$seen / $total" else "$seen",
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = { set(seen + 1) }, enabled = total <= 0 || seen < total) { Icon(Icons.Outlined.Add, null) }
    }
}

@Composable
private fun ScoreField(score: Double, onPick: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(KMR.strings.anime_track_not_set)
    Box {
        FieldRow(
            stringResource(KMR.strings.anime_track_score),
            if (score > 0) "${score.toInt()} / 10" else none,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..10).forEach { s ->
                DropdownMenuItem(text = { Text(if (s == 0) none else s.toString()) }, onClick = {
                    open = false
                    onPick(s.toDouble())
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, millis: Long, modifier: Modifier, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val text = if (millis > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis)) else
        stringResource(KMR.strings.anime_track_not_set)
    Column(modifier = modifier.clickable { open = true }.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis.takeIf { it > 0 } ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let(onPick)
                }) { Text(stringResource(MR.strings.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    open = false
                    onPick(0L)
                }) { Text(stringResource(KMR.strings.anime_track_clear_date)) }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun TrackSearchDialog(
    service: AnimeTrackService,
    initialQuery: String,
    onDismissRequest: () -> Unit,
    onPick: (AnimeTrackSearch) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<AnimeTrackSearch>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        loading = true
        error = null
        scope.launch {
            try {
                results = service.search(query)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Anime tracking: search ${service.name}" }
                error = e.message ?: e.javaClass.simpleName
                results = emptyList()
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { run() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(service.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { run() }) {
                            Icon(Icons.Outlined.Search, stringResource(KMR.strings.anime_track_search))
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                )
                Spacer(Modifier.size(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    results.isNullOrEmpty() -> Text(stringResource(KMR.strings.anime_track_no_results))
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(results!!, key = { it.remoteId }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 6.dp),
                            ) {
                                AsyncImage(
                                    model = item.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 68.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            item.format,
                                            item.startYear,
                                            item.totalEpisodes.takeIf { it > 0 }?.let { "$it ep" },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    item.summary?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
