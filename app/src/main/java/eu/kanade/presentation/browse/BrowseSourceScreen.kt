package eu.kanade.presentation.browse

import eu.kanade.presentation.components.GlassIcons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceEHentaiList
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.notification.BotChecks
import eu.kanade.tachiyomi.ui.webview.BotCheckPrompt
import eu.kanade.tachiyomi.ui.webview.ReloadWhenBotCheckSolved
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.isEhBasedSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    columns: GridCells,
    // SY -->
    ehentaiBrowseDisplayMode: Boolean,
    // SY <--
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    // SY -->
    onWebViewClick: (() -> Unit)?,
    onHelpClick: (() -> Unit)?,
    onLocalSourceHelpClick: (() -> Unit)?,
    // SY <--
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // KMK -->
    selection: List<Manga>,
    /** The grid and list scroll states, held by the screen so each tab keeps its place */
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    // KMK <--
) {
    val context = LocalContext.current

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    // KMK (r116) -->
    // A bot check waiting for you: "Solve the check to continue" instead of the error, and the list reloads by
    // itself once you passed it
    val botChecks by BotChecks.waiting.collectAsState()
    val waitingCheck = source?.id?.let { botChecks[it] }
    ReloadWhenBotCheckSolved(source?.id, waitingCheck) {
        when {
            mangaList.loadState.refresh is LoadState.Error -> mangaList.refresh()
            mangaList.loadState.append is LoadState.Error -> mangaList.retry()
            else -> Unit
        }
    }
    // KMK <--

    LaunchedEffect(errorState, /* KMK (r116) */ waitingCheck != null) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            // KMK (r116): the snackbar asks you to solve the check instead
            val checkSourceId = waitingCheck?.sourceId
            val result = snackbarHostState.showSnackbar(
                message = if (checkSourceId != null) "Solve the check to continue" else getErrorMessage(errorState),
                actionLabel = if (checkSourceId != null) "Solve it" else context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed ->
                    if (checkSourceId != null) BotChecks.solve(context, checkSourceId) else mangaList.retry()
            }
        }
    }

    // KMK (r116): while the list waits for (or failed on) a check, ask for it
    if (mangaList.itemCount == 0 && waitingCheck != null &&
        (mangaList.loadState.refresh is LoadState.Loading || errorState is LoadState.Error)
    ) {
        BotCheckPrompt(
            waiting = waitingCheck,
            onSolve = { BotChecks.solve(context, waitingCheck.sourceId) },
            contentPadding = contentPadding,
        )
        return
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource /* SY --> */ && onLocalSourceHelpClick != null /* SY <-- */) {
                persistentListOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = eu.kanade.presentation.components.GlassIcons.Help,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOfNotNull(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = eu.kanade.presentation.components.GlassIcons.Retry,
                        onClick = mangaList::refresh,
                    ),
                    // SY -->
                    if (onWebViewClick != null) {
                        EmptyScreenAction(
                            MR.strings.action_open_in_web_view,
                            icon = GlassIcons.WebView,
                            onClick = onWebViewClick,
                        )
                    } else {
                        null
                    },
                    if (onHelpClick != null) {
                        EmptyScreenAction(
                            MR.strings.label_help,
                            icon = eu.kanade.presentation.components.GlassIcons.Help,
                            onClick = onHelpClick,
                        )
                    } else {
                        null
                    },
                    // SY <--
                ).toImmutableList()
            },
        )

        return
    }

    // SY -->
    if (source?.isEhBasedSource() == true && ehentaiBrowseDisplayMode) {
        BrowseSourceEHentaiList(
            mangaList = mangaList,
            contentPadding = contentPadding,
            onMangaClick = onMangaClick,
            onMangaLongClick = onMangaLongClick,
            // KMK -->
            selection = selection,
            listState = listState,
            // KMK <--
        )
        return
    }
    // SY <--

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                gridState = gridState,
                // KMK <--
            )
        }
        // KMK -->
        LibraryDisplayMode.ComfortableGridPanorama -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                gridState = gridState,
                usePanoramaCover = true,
                // KMK <--
            )
        }
        // KMK <--
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                listState = listState,
                // KMK <--
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                gridState = gridState,
                showTitle = displayMode == LibraryDisplayMode.CompactGrid,
                // KMK <--
            )
        }
        // KMK: a value outside the known modes falls back to the default compact grid instead of crashing
        // (NoWhenBranchMatchedException, as seen in the library after a pair sync in r89)
        else -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                selection = selection,
                gridState = gridState,
                showTitle = true,
            )
        }
        // KMK <--
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
