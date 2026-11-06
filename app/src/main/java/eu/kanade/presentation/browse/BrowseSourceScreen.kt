package eu.kanade.presentation.browse

import eu.kanade.presentation.components.GlassIcons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import tachiyomi.i18n.kmk.KMR
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
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

    // KMK (r134): back from WebView (opened from here), the list loads again by itself
    val openedWebView = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && openedWebView.value) {
                openedWebView.value = false
                if (mangaList.itemCount == 0 || mangaList.loadState.refresh is LoadState.Error) mangaList.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openWebView: (() -> Unit)? = onWebViewClick?.let { open ->
        {
            openedWebView.value = true
            open()
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        // KMK (r134): still nothing after a while: the site may be showing a check the app can't see, so offer
        // WebView (you look and solve it there yourself) or to keep waiting
        SlowLoadingScreen(
            contentPadding = contentPadding,
            onWebViewClick = openWebView,
        )
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
                    if (openWebView != null) {
                        EmptyScreenAction(
                            MR.strings.action_open_in_web_view,
                            icon = GlassIcons.WebView,
                            onClick = openWebView,
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

/**
 * KMK (r134): the spinner, and after [delayMs] without anything a card: "Still loading", with Open In WebView (to see
 * whether the site wants a check; you solve it there yourself) and Keep Waiting. Nothing is solved or clicked for you.
 */
@Composable
fun SlowLoadingScreen(
    contentPadding: PaddingValues,
    onWebViewClick: (() -> Unit)?,
    delayMs: Long = 8_000L,
) {
    var slow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var dismissed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs)
        slow = true
    }
    if (!slow || dismissed || onWebViewClick == null) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp),
    ) {
        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(36.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Text(
            text = stringResource(KMR.strings.browse_slow_title),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Text(
            text = stringResource(KMR.strings.browse_slow_text),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            eu.kanade.presentation.components.GlassButton(
                text = stringResource(KMR.strings.browse_slow_keep_waiting),
                primary = false,
                onClick = { dismissed = true },
            )
            eu.kanade.presentation.components.GlassButton(
                text = stringResource(MR.strings.action_open_in_web_view),
                primary = true,
                onClick = onWebViewClick,
                icon = GlassIcons.WebView,
            )
        }
    }
}
