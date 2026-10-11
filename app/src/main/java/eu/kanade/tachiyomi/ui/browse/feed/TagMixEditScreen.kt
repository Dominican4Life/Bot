package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.AnimeSourceIcon
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Creates or edits a [TagMix]: manga or anime, its sources and its genres/tags. Typing a tag offers the genre and
 * tag filters of the picked sources; long-press a tag to remove it.
 */
class TagMixEditScreen(private val mixId: Long? = null) : Screen() {

    private data class SourceRow(val id: Long, val name: String, val lang: String)

    @OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val store = remember { TagMixStore() }
        val existing = remember { mixId?.let { store.find(it) } }

        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var anime by rememberSaveable { mutableStateOf(existing?.anime ?: false) }
        val selected = remember { mutableStateListOf<Long>().apply { addAll(existing?.sourceIds.orEmpty()) } }
        val tags = remember { mutableStateListOf<String>().apply { addAll(existing?.tags.orEmpty()) } }
        var tagInput by rememberSaveable { mutableStateOf("") }
        var query by rememberSaveable { mutableStateOf("") }
        var removing by remember { mutableStateOf<String?>(null) }

        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val mangaSources = remember {
            Injekt.get<SourceManager>().getVisibleOnlineSources()
                .map { SourceRow(it.id, it.name, it.lang) }
                .sortedBy { it.name.lowercase() }
        }
        val animeSources = remember {
            val disabled = sourcePreferences.animeDisabledSources().get()
            Injekt.get<AnimeSourceManager>().getCatalogueSources()
                .filter { it.id.toString() !in disabled }
                .map { SourceRow(it.id, it.name, it.lang) }
                .sortedBy { it.name.lowercase() }
        }
        val sources = if (anime) animeSources else mangaSources

        // Genre and tag filters of the picked sources, offered while typing
        val known by produceState(emptyList<String>(), anime, selected.toList()) {
            val ids = selected.toList()
            value = withIOContext { if (anime) knownAnimeTags(ids) else knownMangaTags(ids) }
        }

        fun addTag(tag: String) {
            val trimmed = tag.trim()
            if (trimmed.isEmpty() || tags.any { it.equals(trimmed, ignoreCase = true) }) return
            tags.add(trimmed)
            tagInput = ""
        }

        fun save() {
            if (selected.isEmpty() || tags.isEmpty()) {
                context.toast(KMR.strings.tag_mix_need_more)
                return
            }
            store.save(
                TagMix(
                    id = existing?.id ?: System.currentTimeMillis(),
                    name = name.trim(),
                    anime = anime,
                    sourceIds = selected.toList(),
                    tags = tags.toList(),
                ),
            )
            navigator.pop()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(if (existing == null) KMR.strings.tag_mix_new else KMR.strings.tag_mix_edit),
                    navigateUp = navigator::pop,
                    actions = {
                        TextButton(onClick = ::save) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(MR.strings.action_save))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val shown = remember(query, sources) {
                val q = query.trim()
                if (q.isEmpty()) sources else sources.filter { it.name.contains(q, ignoreCase = true) }
            }
            val padding = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 6.dp)
            LazyColumn(contentPadding = contentPadding) {
                item(key = "kind") {
                    PrimaryTabRow(selectedTabIndex = if (anime) 1 else 0) {
                        listOf(KMR.strings.feed_tab_manga, KMR.strings.feed_tab_anime).forEachIndexed { index, title ->
                            Tab(
                                selected = (index == 1) == anime,
                                onClick = {
                                    if ((index == 1) != anime) {
                                        anime = index == 1
                                        // Manga and anime sources are different, so start the picks over
                                        selected.clear()
                                    }
                                },
                                text = { Text(stringResource(title)) },
                            )
                        }
                    }
                }
                item(key = "name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(KMR.strings.tag_mix_name)) },
                        placeholder = { Text(stringResource(KMR.strings.tag_mix_name_hint)) },
                        modifier = padding.fillMaxWidth(),
                    )
                }
                item(key = "tags") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = padding) {
                        Text(stringResource(KMR.strings.tag_mix_tags), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(KMR.strings.tag_mix_tags_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(KMR.strings.tag_mix_tags_hint)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { addTag(tagInput) }),
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(onClick = { addTag(tagInput) }, enabled = tagInput.isNotBlank()) {
                                Icon(Icons.Outlined.Add, contentDescription = stringResource(KMR.strings.tag_mix_add_tag))
                            }
                        }
                        val matches = remember(tagInput, known, tags.toList()) {
                            val typed = tagInput.trim().lowercase()
                            if (typed.length < 2) {
                                emptyList()
                            } else {
                                known.filter { typed in it.lowercase() && tags.none { t -> t.equals(it, true) } }.take(10)
                            }
                        }
                        if (matches.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                matches.forEach { tag ->
                                    SuggestionChip(onClick = { addTag(tag) }, label = { Text("+ $tag") })
                                }
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            removing = tag
                                        },
                                    ),
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "sources-title") {
                    Text(
                        text = stringResource(KMR.strings.tag_mix_sources, selected.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = padding,
                    )
                }
                item(key = "search") {
                    SourcesSearchBox(
                        searchQuery = query,
                        onChangeSearchQuery = { query = it.orEmpty() },
                        placeholderText = stringResource(KMR.strings.sources_search_hint),
                        modifier = padding.fillMaxWidth(),
                    )
                }
                items(shown, key = { (if (anime) "a" else "m") + it.id }) { source ->
                    val checked = source.id in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (checked) selected.remove(source.id) else selected.add(source.id) }
                            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        if (anime) {
                            AnimeSourceIcon(sourceId = source.id)
                        } else {
                            SourceIcon(
                                source = Source(
                                    id = source.id,
                                    lang = source.lang,
                                    name = source.name,
                                    supportsLatest = false,
                                    isStub = false,
                                ),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${source.name} (${FlagEmoji.getEmojiLangFlag(source.lang)})")
                    }
                }
            }
        }

        removing?.let { tag ->
            AlertDialog(
                onDismissRequest = { removing = null },
                text = { Text(stringResource(KMR.strings.tag_mix_remove_tag, tag)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            tags.remove(tag)
                            removing = null
                        },
                    ) { Text(stringResource(MR.strings.action_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}
