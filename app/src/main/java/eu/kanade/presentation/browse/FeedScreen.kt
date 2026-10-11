package eu.kanade.presentation.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.AnimeSourceIcon
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.browse.feed.AnimeFeedScreenModel
import eu.kanade.tachiyomi.ui.browse.feed.FeedScreenState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.source.model.Source as DomainSource

data class FeedItemUI(
    val feed: FeedSavedSearch,
    val savedSearch: SavedSearch?,
    val source: Source?,
    val title: String,
    val subtitle: String,
    val results: List<Manga>?,
)

@Composable
fun FeedScreen(
    state: FeedScreenState,
    contentPadding: PaddingValues,
    onClickSavedSearch: (SavedSearch, Source) -> Unit,
    onClickSource: (Source) -> Unit,
    // KMK -->
    onLongClickFeed: (FeedItemUI) -> Unit,
    // KMK <--
    onClickManga: (Manga) -> Unit,
    // KMK -->
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
    // KMK <--
    onRefresh: () -> Unit,
    getMangaState: @Composable (Manga) -> State<Manga>,
    // KMK --> anime rows, shown after the manga rows
    hasAnimeRows: Boolean = false,
    animeRows: LazyListScope.() -> Unit = {},
    // KMK <--
) {
    when {
        state.isLoading -> LoadingScreen()
        state.isEmpty && !hasAnimeRows -> EmptyScreen(
            SYMR.strings.feed_tab_empty,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            var refreshing by remember { mutableStateOf(false) }
            LaunchedEffect(refreshing) {
                if (refreshing) {
                    delay(1.seconds)
                    refreshing = false
                }
            }
            PullRefresh(
                refreshing = refreshing && state.isLoadingItems,
                onRefresh = {
                    refreshing = true
                    onRefresh()
                },
                enabled = !state.isLoadingItems,
            ) {
                ScrollbarLazyColumn(
                    contentPadding = contentPadding + topSmallPaddingValues,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // KMK -->
                    val feeds = state.items.orEmpty()
                    items(
                        items = feeds,
                        key = { it.feed.key },
                    ) { item ->
                        // KMK <--
                        GlobalSearchResultItem(
                            title = item.title,
                            subtitle = item.subtitle,
                            onLongClick = {
                                // KMK -->
                                onLongClickFeed(item)
                                // KMK <--
                            },
                            onClick = {
                                if (item.savedSearch != null && item.source != null) {
                                    onClickSavedSearch(item.savedSearch, item.source)
                                } else if (item.source != null) {
                                    onClickSource(item.source)
                                }
                            },
                            modifier = Modifier.animateItem(),
                        ) {
                            FeedItem(
                                item = item,
                                getMangaState = { getMangaState(it) },
                                onClickManga = onClickManga,
                                // KMK -->
                                onLongClickManga = onLongClickManga,
                                selection = selection,
                                // KMK <--
                            )
                        }
                    }
                    // KMK
                    animeRows()
                }
            }
        }
    }
}

@Composable
fun FeedItem(
    item: FeedItemUI,
    getMangaState: @Composable ((Manga) -> State<Manga>),
    onClickManga: (Manga) -> Unit,
    // KMK -->
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
    // KMK <--
) {
    when {
        item.results == null -> {
            GlobalSearchLoadingResultItem()
        }
        item.results.isEmpty() -> {
            GlobalSearchErrorResultItem(message = stringResource(MR.strings.no_results_found))
        }
        else -> {
            GlobalSearchCardRow(
                titles = item.results,
                getManga = getMangaState,
                onClick = onClickManga,
                // KMK -->
                onLongClick = onLongClickManga,
                selection = selection,
                // KMK <--
            )
        }
    }
}

/** KMK: An anime source in the Feed's add dialog */
data class FeedAnimeSource(val id: Long, val name: String, val lang: String)

@Composable
fun FeedAddDialog(
    sources: ImmutableList<Source>,
    onDismiss: () -> Unit,
    onClickAdd: (Source?) -> Unit,
    // KMK --> Anime tab; empty hides the tabs
    animeSources: List<FeedAnimeSource> = emptyList(),
    onClickAddAnime: (Long) -> Unit = {},
    /** Opens the tag mix editor: one row from several sources and some genres/tags */
    onClickTagMix: (() -> Unit)? = null,
    // KMK <--
) {
    // KMK -->
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    fun matches(name: String, id: Long): Boolean {
        if (query.isBlank()) return true
        return query.split(",").any {
            val input = it.trim()
            input.isNotEmpty() && (name.contains(input, ignoreCase = true) || id == input.toLongOrNull())
        }
    }
    val sourceList = sources.filter { matches(it.name, it.id) }
    val composeOptions: List<@Composable () -> Unit> = sourceList
        .map {
            {
                val source = DomainSource(
                    id = it.id,
                    lang = it.lang,
                    name = it.name,
                    supportsLatest = it.supportsLatest,
                    isStub = false,
                )
                SourceIcon(source = source)
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Text(text = it.getNameForMangaInfo())
            }
        }
    val animeList = animeSources.filter { matches(it.name, it.id) }
    // Icon and language flag, like the manga sources
    val animeOptions: List<@Composable () -> Unit> = animeList.map {
        {
            AnimeSourceIcon(sourceId = it.id)
            Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
            Text(text = "${it.name} (${FlagEmoji.getEmojiLangFlag(it.lang)})")
        }
    }
    // KMK <--
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        title = {
            Text(text = stringResource(SYMR.strings.feed))
        },
        text = {
            // KMK -->
            Column {
                if (animeSources.isNotEmpty()) {
                    PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(bottom = MaterialTheme.padding.small)) {
                        listOf(stringResource(KMR.strings.feed_tab_manga), stringResource(KMR.strings.feed_tab_anime))
                            .forEachIndexed { index, title ->
                                Tab(
                                    selected = tab == index,
                                    onClick = {
                                        if (tab != index) selected = null
                                        tab = index
                                    },
                                    text = { Text(title) },
                                )
                            }
                    }
                }
                RadioSelectorSearchable(
                    options = if (tab == 0) composeOptions else animeOptions,
                    queryString = query,
                    onChangeSearchQuery = {
                        query = it ?: ""
                        selected = null
                    },
                    selected = selected,
                ) {
                    selected = it
                }
            }
            // KMK <--
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // KMK -->
                    if (tab == 1) {
                        selected?.let { animeList.getOrNull(it) }?.let { onClickAddAnime(it.id) } ?: onDismiss()
                    } else {
                        // KMK <--
                        onClickAdd(selected?.let { sourceList.getOrNull(it) })
                    }
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        // KMK -->
        dismissButton = onClickTagMix?.let { open ->
            {
                TextButton(onClick = open) {
                    Text(text = stringResource(KMR.strings.tag_mix_new))
                }
            }
        },
        // KMK <--
    )
}

@Composable
fun FeedAddSearchDialog(
    source: Source,
    savedSearches: ImmutableList<SavedSearch?>,
    onDismiss: () -> Unit,
    onClickAdd: (Source, SavedSearch?) -> Unit,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        title = {
            Text(text = source.name)
        },
        text = {
            val context = LocalContext.current
            val savedSearchStrings = remember {
                savedSearches.map {
                    it?.name
                        // KMK -->
                        ?: if (source.supportsLatest) {
                            // KMK <--
                            context.stringResource(MR.strings.latest)
                            // KMK -->
                        } else {
                            context.stringResource(MR.strings.popular)
                        }
                    // KMK <--
                }.toImmutableList()
            }
            RadioSelectorSearchable(
                options = savedSearches,
                optionStrings = savedSearchStrings,
                selected = selected,
            ) {
                selected = it
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onClickAdd(source, selected?.let { savedSearches[it] }) },
                // KMK -->
                enabled = selected != null,
                // KMK <--
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun <T> RadioSelectorSearchable(
    options: ImmutableList<T>,
    optionStrings: ImmutableList<String> = remember { options.map { it.toString() }.toImmutableList() },
    selected: Int?,
    onSelectOption: (Int) -> Unit = {},
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        optionStrings.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onSelectOption(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == index, onClick = null)
                Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                Text(option, maxLines = 1)
            }
        }
    }
}

// KMK -->
@Composable
fun RadioSelectorSearchable(
    options: List<@Composable () -> Unit>,
    queryString: String? = null,
    onChangeSearchQuery: ((String?) -> Unit)? = null,
    selected: Int?,
    onSelectOption: (Int) -> Unit = {},
) {
    BackHandler(enabled = !queryString.isNullOrBlank()) {
        onChangeSearchQuery?.invoke("")
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SourcesSearchBox(
            searchQuery = queryString,
            onChangeSearchQuery = onChangeSearchQuery ?: {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaterialTheme.padding.small),
        ).takeIf { onChangeSearchQuery != null }
        options.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onSelectOption(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == index, onClick = null)
                Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                option()
            }
        }
    }
}
// KMK <--
