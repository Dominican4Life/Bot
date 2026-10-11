package eu.kanade.tachiyomi.ui.browse.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.FeedAddDialog
import eu.kanade.presentation.browse.FeedAddSearchDialog
import eu.kanade.presentation.browse.FeedAnimeSource
import eu.kanade.presentation.browse.FeedOrderScreen
import eu.kanade.presentation.browse.FeedScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.FeedActionsDialog
import eu.kanade.presentation.browse.components.SourceFeedDeleteDialog
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.feedTab(
    // KMK -->
    screenModel: FeedScreenModel,
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
    // KMK <--
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
    val showingFeedOrderScreen = rememberSaveable { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current

    // Anime rows of the feed (a source's Latest or Popular)
    val animeFeed = rememberScreenModel { AnimeFeedScreenModel() }
    val animeState by animeFeed.state.collectAsState()
    var addAnime by remember { mutableStateOf<AnimeFeedScreenModel.Option?>(null) }
    var removeAnime by remember { mutableStateOf<AnimeFeedScreenModel.Entry?>(null) }
    // Tag mix rows: titles from several sources for some genres or tags
    val tagMixFeed = rememberScreenModel { TagMixFeedScreenModel() }
    val tagMixState by tagMixFeed.state.collectAsState()
    var removeTagMix by remember { mutableStateOf<TagMix?>(null) }

    LaunchedEffect(bulkFavoriteState.selectionMode) {
        HomeScreen.showBottomNav(!bulkFavoriteState.selectionMode)
    }
    // KMK <--

    DisposableEffect(navigator.lastEvent) {
        if (navigator.lastEvent == StackEvent.Push) {
            screenModel.pushed = true
        } else if (!screenModel.pushed) {
            screenModel.init()
        }

        onDispose {
            if (navigator.lastEvent == StackEvent.Idle && screenModel.pushed) {
                screenModel.pushed = false
            }
        }
    }

    return TabContent(
        titleRes = SYMR.strings.feed,
        actions =
        // KMK -->
        if (showingFeedOrderScreen.value) {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(KMR.strings.action_sort_feed),
                    icon = Icons.Outlined.Close,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { showingFeedOrderScreen.value = false },
                ),
            )
        } else {
            // KMK <--
            persistentListOf(
                AppBar.Action(
                    title = stringResource(MR.strings.action_add),
                    icon = Icons.Outlined.Add,
                    onClick = {
                        screenModel.openAddDialog()
                    },
                ),
                // KMK -->
                AppBar.Action(
                    title = stringResource(KMR.strings.action_sort_feed),
                    icon = Icons.Outlined.SwapVert,
                    onClick = { showingFeedOrderScreen.value = true },
                ),
                bulkSelectionButton(
                    isRunning = bulkFavoriteState.isRunning,
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                ),
                // KMK <--
            )
        },
        content = { contentPadding, snackbarHostState ->
            // KMK -->
            BackHandler(enabled = bulkFavoriteState.selectionMode || showingFeedOrderScreen.value) {
                when {
                    bulkFavoriteState.selectionMode -> bulkFavoriteScreenModel.backHandler()
                    showingFeedOrderScreen.value -> showingFeedOrderScreen.value = false
                }
            }
            Crossfade(
                targetState = showingFeedOrderScreen.value,
                label = "feed_order_crossfade",
            ) { showingFeedOrderScreen ->
                if (showingFeedOrderScreen) {
                    FeedOrderScreen(
                        state = state,
                        onClickDelete = screenModel::openDeleteDialog,
                        onChangeOrder = screenModel::changeOrder,
                    )
                } else {
                    // KMK <--
                    FeedScreen(
                        state = state,
                        contentPadding = contentPadding,
                        onClickSavedSearch = { savedSearch, source ->
                            screenModel.sourcePreferences.lastUsedSource().set(savedSearch.source)
                            navigator.push(
                                BrowseSourceScreen(
                                    source.id,
                                    listingQuery = null,
                                    savedSearch = savedSearch.id,
                                ),
                            )
                        },
                        onClickSource = { source ->
                            screenModel.sourcePreferences.lastUsedSource().set(source.id)
                            navigator.push(
                                BrowseSourceScreen(
                                    source.id,
                                    // KMK -->
                                    listingQuery = if (!source.supportsLatest) {
                                        GetRemoteManga.QUERY_POPULAR
                                    } else {
                                        // KMK <--
                                        GetRemoteManga.QUERY_LATEST
                                    },
                                ),
                            )
                        },
                        // KMK -->
                        onLongClickFeed = screenModel::openActionsDialog,
                        // KMK <--
                        onClickManga = { manga ->
                            // KMK -->
                            if (bulkFavoriteState.selectionMode) {
                                bulkFavoriteScreenModel.toggleSelection(manga)
                            } else {
                                // KMK <--
                                navigator.push(MangaScreen(manga.id, true))
                            }
                        },
                        // KMK -->
                        onLongClickManga = { manga ->
                            if (!bulkFavoriteState.selectionMode) {
                                bulkFavoriteScreenModel.addRemoveManga(manga, haptic)
                            } else {
                                navigator.push(MangaScreen(manga.id, true))
                            }
                        },
                        selection = bulkFavoriteState.selection,
                        // KMK <--
                        onRefresh = {
                            screenModel.init()
                            // KMK
                            animeFeed.refresh()
                            tagMixFeed.refresh()
                        },
                        getMangaState = { manga -> screenModel.getManga(initialManga = manga) },
                        // KMK -->
                        hasAnimeRows = animeState.rows.isNotEmpty() || tagMixState.rows.isNotEmpty(),
                        animeRows = {
                            tagMixState.rows.forEach { row ->
                                item(key = "tag_mix_${row.mix.id}") {
                                    TagMixFeedRow(
                                        row = row,
                                        onClickHeader = { navigator.push(TagMixEditScreen(row.mix.id)) },
                                        onLongClickHeader = { removeTagMix = row.mix },
                                        onClickManga = { navigator.push(MangaScreen(it.id, true)) },
                                        onLongClickManga = { bulkFavoriteScreenModel.addRemoveManga(it, haptic) },
                                        onClickAnime = { navigator.push(AnimeScreen(it.id)) },
                                        onLongClickAnime = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            tagMixFeed.toggleAnimeFavorite(it)
                                        },
                                    )
                                }
                            }
                            animeState.rows.forEach { row ->
                                item(key = row.entry.key) {
                                    AnimeFeedRow(
                                        row = row,
                                        onClickHeader = {
                                            navigator.push(
                                                AnimeSourceScreen(row.entry.sourceId, initialLatest = row.entry.latest),
                                            )
                                        },
                                        onLongClickHeader = { removeAnime = row.entry },
                                        onClickAnime = { navigator.push(AnimeScreen(it.id)) },
                                        onLongClickAnime = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            animeFeed.toggleFavorite(it)
                                        },
                                    )
                                }
                            }
                        },
                        // KMK <--
                    )
                }
            }

            state.dialog?.let { dialog ->
                val onDismissRequest = screenModel::dismissDialog
                when (dialog) {
                    is FeedScreenModel.Dialog.AddFeed -> {
                        // KMK: anime sources in the dialog's Anime tab
                        val animeOptions = remember(dialog) { animeFeed.options() }
                        FeedAddDialog(
                            sources = dialog.options,
                            onDismiss = onDismissRequest,
                            onClickAdd = {
                                if (it != null) {
                                    screenModel.openAddSearchDialog(it)
                                }
                                onDismissRequest()
                            },
                            // KMK -->
                            animeSources = animeOptions.map { FeedAnimeSource(it.id, it.name, it.lang) },
                            onClickAddAnime = { id ->
                                addAnime = animeOptions.firstOrNull { it.id == id }
                                onDismissRequest()
                            },
                            onClickTagMix = {
                                onDismissRequest()
                                navigator.push(TagMixEditScreen())
                            },
                            // KMK <--
                        )
                    }
                    is FeedScreenModel.Dialog.AddFeedSearch -> {
                        FeedAddSearchDialog(
                            source = dialog.source,
                            savedSearches = dialog.options,
                            onDismiss = onDismissRequest,
                            onClickAdd = { source, savedSearch ->
                                screenModel.createFeed(source, savedSearch)
                                onDismissRequest()
                            },
                        )
                    }
                    is FeedScreenModel.Dialog.DeleteFeed -> {
                        SourceFeedDeleteDialog(
                            onDismissRequest = onDismissRequest,
                            deleteFeed = {
                                screenModel.deleteFeed(dialog.feed)
                                onDismissRequest()
                            },
                        )
                    }
                    // KMK -->
                    is FeedScreenModel.Dialog.FeedActions -> {
                        FeedActionsDialog(
                            feed = dialog.feedItem.feed,
                            title = dialog.feedItem.title,
                            onDismissRequest = onDismissRequest,
                            onClickDelete = { screenModel.openDeleteDialog(it) },
                        )
                    }
                    // KMK <--
                }
            }

            // KMK --> anime feed rows: pick Latest or Popular, and remove
            addAnime?.let { option ->
                AnimeFeedAddDialog(
                    option = option,
                    onDismiss = { addAnime = null },
                    onAdd = { latest ->
                        animeFeed.add(option.id, latest)
                        addAnime = null
                    },
                )
            }
            removeAnime?.let { entry ->
                SourceFeedDeleteDialog(
                    onDismissRequest = { removeAnime = null },
                    deleteFeed = {
                        animeFeed.remove(entry)
                        removeAnime = null
                    },
                )
            }

            removeTagMix?.let { mix ->
                AlertDialog(
                    onDismissRequest = { removeTagMix = null },
                    text = { Text(stringResource(KMR.strings.tag_mix_delete, mix.title)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                tagMixFeed.delete(mix)
                                removeTagMix = null
                            },
                        ) { Text(stringResource(MR.strings.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { removeTagMix = null }) { Text(stringResource(MR.strings.action_cancel)) }
                    },
                )
            }

            BulkFavoriteDialogs(
                bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                dialog = bulkFavoriteState.dialog,
            )
            // KMK <--

            val internalErrString = stringResource(MR.strings.internal_error)
            val tooManyFeedsString = stringResource(KMR.strings.too_many_in_feed)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        FeedScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                        FeedScreenModel.Event.TooManyFeeds -> {
                            launch { snackbarHostState.showSnackbar(tooManyFeedsString) }
                        }
                    }
                }
            }
        },
    )
}
