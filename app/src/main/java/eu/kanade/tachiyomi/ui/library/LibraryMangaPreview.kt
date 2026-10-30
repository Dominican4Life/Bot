package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.BrowseChapterPanel
import eu.kanade.presentation.browse.components.BrowsePagePanel
import eu.kanade.presentation.browse.components.BrowsePreviewInfoHeader
import eu.kanade.presentation.browse.components.PreviewPanelButtons
import eu.kanade.presentation.reader.sidePanelWidth
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowsePreviewScreenModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The Browse preview panels in the manga library: the Left Panel (details and chapters) and Right Panel (pages)
 * buttons sit at the right of the category row, and with either on, the first tap on a cover previews it in the
 * panels (a second tap opens the title).
 */
@Stable
class LibraryMangaPreview {
    var source by mutableStateOf<eu.kanade.tachiyomi.source.Source?>(null)
    var pending by mutableStateOf<Manga?>(null)
    var model by mutableStateOf<BrowsePreviewScreenModel?>(null)

    // KMK: the source [model] was made for (it can lag behind [source] while a title from another source loads)
    var modelSourceId by mutableStateOf<Long?>(null)
}

@Composable
fun Screen.rememberLibraryMangaPreview(): LibraryMangaPreview {
    val preview = remember { LibraryMangaPreview() }
    // KMK: the models outlive this composition (rememberScreenModel), so leaving the Library tab would leave the
    // previewed model with its panels open and its database/download listeners running in the background
    DisposableEffect(preview) {
        onDispose { preview.model?.clear() }
    }
    val prefs = remember { Injekt.get<SourcePreferences>().libraryPreviewPanels() }
    val panels by prefs.collectPrefAsState()
    val src = preview.source
    if (src != null) {
        key(src.id) {
            val m = rememberScreenModel(tag = "library_preview_${src.id}") { BrowsePreviewScreenModel(src) }
            LaunchedEffect(m) {
                // KMK: a title from another source is picked on its model before that model is shown, so the
                // panels stay open through the switch. Before, the old model was dropped first: for a moment there
                // were no panels, the covers jumped to the full width and back.
                preview.pending?.takeIf { it.source == src.id }?.let { manga ->
                    m.select(
                        manga,
                        showChapters = SourcePreferences.previewLeftOn(panels),
                        showPages = SourcePreferences.previewRightOn(panels),
                    )
                    preview.pending = null
                }
                val old = preview.model
                preview.model = m
                preview.modelSourceId = src.id
                if (old != null && old !== m) old.clear()
            }
        }
    }
    val model = preview.model
    LaunchedEffect(model, preview.pending) {
        val manga = preview.pending ?: return@LaunchedEffect
        if (model == null || preview.modelSourceId != manga.source) return@LaunchedEffect
        model.select(
            manga,
            showChapters = SourcePreferences.previewLeftOn(panels),
            showPages = SourcePreferences.previewRightOn(panels),
        )
        preview.pending = null
    }
    return preview
}

/** True when the tap previewed the cover; false when it should open the title as before */
fun LibraryMangaPreview.onCoverClick(manga: Manga): Boolean {
    val panels = Injekt.get<SourcePreferences>().libraryPreviewPanels().get()
    if (panels == SourcePreferences.PREVIEW_PANELS_NONE) return false
    if (model?.state?.value?.manga?.id == manga.id) return false
    // KMK: a merged title's chapters belong to the titles it merges (and MergedSource has no page list), so the
    // panels can't show it: open its title page as before
    if (manga.source == MERGED_SOURCE_ID) return false
    val src = Injekt.get<SourceManager>().getOrStub(manga.source)
    // KMK: the current panels stay until the new source's model has the title (see rememberLibraryMangaPreview)
    pending = manga
    if (source?.id != src.id) source = src
    return true
}

/** The Left Panel / Right Panel buttons for the category row */
@Composable
fun LibraryMangaPreview.Buttons(scope: RowScope) {
    val pref = remember { Injekt.get<SourcePreferences>().libraryPreviewPanels() }
    val panels by pref.collectPrefAsState()
    with(scope) {
        PreviewPanelButtons(
            leftOn = SourcePreferences.previewLeftOn(panels),
            rightOn = SourcePreferences.previewRightOn(panels),
            onLeftChange = { on ->
                pref.set(SourcePreferences.previewPanels(left = on, right = SourcePreferences.previewRightOn(panels)))
                if (on) model?.openChapterPanel() else model?.closeChapterPanel()
            },
            onRightChange = { on ->
                pref.set(SourcePreferences.previewPanels(left = SourcePreferences.previewLeftOn(panels), right = on))
                if (on) model?.openPagePanel() else model?.closePagePanel()
            },
        )
        Spacer(Modifier.width(0.dp))
    }
}

/** How much room the open panels take on each side, for the covers to move out of the way */
@Composable
fun LibraryMangaPreview.panelPadding(maxWidth: Dp): Pair<Dp, Dp> {
    val state = model?.state?.collectAsState()?.value
    val width = sidePanelWidth(maxWidth)
    val start by animateDpAsState(if (state?.chapterPanelOpen == true) width else 0.dp, label = "libraryPanelStart")
    val end by animateDpAsState(if (state?.pagePanelOpen == true) width else 0.dp, label = "libraryPanelEnd")
    return start to end
}

/** The cover being previewed */
@Composable
fun LibraryMangaPreview.highlighted(): Long? = model?.state?.collectAsState()?.value?.manga?.id

/**
 * The two panels, drawn over the covers.
 * [onToggleFavorite] is the library's own add/remove flow (KMK: the remove dialog with its delete-downloads choice,
 * and the category prompt when adding back)
 */
@Composable
fun LibraryMangaPreview.Panels(scope: BoxScope, onToggleFavorite: (Manga) -> Unit) {
    val model = model ?: return
    val src = source ?: return
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    with(scope) {
        BrowseChapterPanel(
            state = state,
            onClose = model::closeChapterPanel,
            onSelectChapter = model::selectChapter,
            onOpenChapter = { chapter ->
                context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
            },
            onToggleRead = model::toggleRead,
            onToggleBookmark = model::toggleBookmark,
            onChapterDownloadAction = model::runChapterDownloadAction,
            onDownloadAction = model::runDownloadAction,
            onOpenFilters = { state.manga?.let { navigator.push(MangaScreen(it.id)) } },
            onRefresh = model::refresh,
            infoHeader = { manga ->
                BrowsePreviewInfoHeader(
                    manga = manga,
                    source = src,
                    onAddToLibrary = { onToggleFavorite(manga) },
                    onWebView = {
                        val url = (src as? HttpSource)
                            ?.let { runCatching { it.getMangaUrl(manga.toSManga()) }.getOrNull() }
                        if (url != null) {
                            navigator.push(WebViewScreen(url = url, initialTitle = manga.title, sourceId = manga.source))
                        }
                    },
                    onOpenDetails = { navigator.push(MangaScreen(manga.id)) },
                    onSearch = { query, _ -> coroutines.launch { navigator.push(GlobalSearchScreen(query)) } },
                )
            },
        )
        BrowsePagePanel(
            state = state,
            source = model.httpSource,
            onClose = model::closePagePanel,
            onRetry = { state.selectedChapter?.let(model::selectChapter) },
            onOpenPage = { chapter, pageIndex, video ->
                context.startActivity(
                    ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, pageIndex, playVideo = video),
                )
            },
        )
    }
}

/**
 * KMK: Keeps the library's order still while a preview is open. Previewing a library title refreshes it from the
 * source, which changes its row (unread count, last update, sometimes its chapters and history), so under sorts like
 * "Last read" the tapped title and its neighbours moved, and the grid's scroll followed the first visible title.
 * While the panels are open, each category keeps the order its items had when it was first shown; new data (badges)
 * still shows in place, titles that left the list drop out and new ones go at the end.
 */
@Stable
class LibraryOrderFreeze {
    /** Pull to refresh turns the freeze off until the panels close */
    var released by mutableStateOf(false)

    private var key: Any? = null

    // KMK: one snapshot per category and its own sort (flags), so swiping between tabs keeps each tab's order
    private val orders = HashMap<Pair<Long, Long>, List<Long>>()

    /** [key] is null when nothing is frozen; a new key (sort, grouping or search changed) takes a new snapshot */
    fun apply(key: Any?, category: Category, items: List<LibraryItem>): List<LibraryItem> {
        if (key == null) {
            if (this.key != null) {
                this.key = null
                orders.clear()
            }
            return items
        }
        if (key != this.key) {
            this.key = key
            orders.clear()
        }
        val slot = category.id to category.flags
        val order = orders[slot]
        if (order == null) {
            orders[slot] = items.map { it.id }
            return items
        }
        val byId = items.associateBy { it.id }
        val kept = order.mapNotNull { byId[it] }
        val known = order.toHashSet()
        val added = items.filter { it.id !in known }
        if (added.isEmpty()) return kept
        orders[slot] = order + added.map { it.id }
        return kept + added
    }
}

/**
 * KMK: The key for [LibraryOrderFreeze.apply]: null when no preview is open (or after pull to refresh), otherwise it
 * changes with the sort and with [inputs] (the grouping, the search), which take a new snapshot of the order. A
 * tab's own sort is part of its snapshot slot in [LibraryOrderFreeze.apply], so swiping tabs keeps the freeze.
 */
@Composable
fun LibraryMangaPreview.orderFreezeKey(freeze: LibraryOrderFreeze, vararg inputs: Any?): Any? {
    val open = highlighted() != null
    LaunchedEffect(open) {
        if (!open) freeze.released = false
    }
    val sortPref = remember { Injekt.get<LibraryPreferences>().sortingMode() }
    val sort by sortPref.collectPrefAsState()
    return if (open && !freeze.released) listOf(sort, *inputs) else null
}
