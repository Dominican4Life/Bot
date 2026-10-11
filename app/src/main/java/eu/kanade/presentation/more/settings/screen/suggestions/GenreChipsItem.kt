package eu.kanade.presentation.more.settings.screen.suggestions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.domain.suggestions.splitGenres
import eu.kanade.domain.suggestions.withGenre
import eu.kanade.domain.suggestions.withoutGenre
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: A genre list for suggestions, edited one genre at a time: type it and press + (or Done) to add it, pick one
 * of the matching genres from your library, and long-press a genre to remove it. Saved comma separated, as before.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GenreChipsItem(
    preference: Preference<String>,
    title: String,
    summary: String,
    onChanged: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val saved by preference.collectAsState()
    val genres = remember(saved) { saved.splitGenres() }
    var input by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }

    // Genres of the titles in the library, most common first, to pick from while typing
    val known by produceState(emptyList<String>()) {
        value = withIOContext {
            runCatching {
                Injekt.get<GetLibraryManga>().await()
                    .flatMap { it.manga.genre.orEmpty() }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .groupingBy { it.lowercase() }
                    .eachCount()
                    .entries.sortedByDescending { it.value }
                    .map { it.key }
            }.getOrDefault(emptyList())
        }
    }

    fun add(genre: String) {
        val trimmed = genre.trim()
        if (trimmed.isEmpty()) return
        preference.set(saved.withGenre(trimmed))
        input = ""
        onChanged()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
    ) {
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(stringResource(KMR.strings.suggestions_genre_add_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(input) }),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = { add(input) }, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(KMR.strings.suggestions_genre_add))
            }
        }

        // Matching genres from the library, one tap to add
        val matches = remember(input, known, genres) {
            val typed = input.trim().lowercase()
            if (typed.length < 2) {
                emptyList()
            } else {
                val have = genres.map { it.lowercase() }.toSet()
                known.filter { typed in it && it !in have }.take(8)
            }
        }
        if (matches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                matches.forEach { genre ->
                    SuggestionChip(onClick = { add(genre) }, label = { Text("+ $genre") })
                }
            }
        }

        if (genres.isEmpty()) {
            Text(
                text = stringResource(KMR.strings.suggestions_genres_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                genres.forEach { genre ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                removing = genre
                            },
                        ),
                    ) {
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(KMR.strings.suggestions_genre_remove_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    removing?.let { genre ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(KMR.strings.suggestions_genre_remove_title)) },
            text = { Text(stringResource(KMR.strings.suggestions_genre_remove_message, genre)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        preference.set(saved.withoutGenre(genre))
                        removing = null
                        onChanged()
                    },
                ) { Text(stringResource(MR.strings.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
