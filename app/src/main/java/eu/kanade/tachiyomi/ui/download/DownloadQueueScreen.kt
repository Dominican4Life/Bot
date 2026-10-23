package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.download.AnimeDownloadManager
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.ui.anime.AnimeDownloadQueueRow
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.anime.DownloadQueueSectionHeader
import eu.kanade.tachiyomi.ui.anime.MangaDownloadQueueRow
import eu.kanade.presentation.components.MediaTab
import eu.kanade.presentation.components.MediaTabPager
import eu.kanade.presentation.components.MediaTabRow
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

object DownloadQueueScreen : Screen() {
    @Suppress("unused")
    private fun readResolve(): Any = DownloadQueueScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel {
            DownloadQueueScreenModel(
                // KMK -->
                navigator = navigator,
                // KMK <--
            )
        }
        val downloadList by screenModel.state.collectAsState()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.subItems.size } }
        }
        // KMK -->
        // One queue for anime and manga: Anime | Manga, swiped between
        var tab by rememberSaveable { mutableIntStateOf(TAB_ANIME) }
        val animeManager = remember { Injekt.get<AnimeDownloadManager>() }
        val animeQueue by animeManager.queue.collectAsState()
        val shownCount = if (tab == TAB_MANGA) downloadCount else animeQueue.size
        // The manga list is rebuilt when coming back to its tab: stop the old list's listeners
        val collectorJobs = remember { mutableListOf<Job>() }
        // KMK <--

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            // All this lines just for fab state :/
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                // KMK
                Column {
                    AppBar(
                        titleContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(MR.strings.label_download_queue),
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f, false),
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (shownCount > 0) {
                                    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                    Pill(
                                        text = "$shownCount",
                                        modifier = Modifier.padding(start = 4.dp),
                                        color = MaterialTheme.colorScheme.onBackground
                                            .copy(alpha = pillAlpha),
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        },
                        navigateUp = navigator::pop,
                        actions = {
                            // KMK -->
                            if (tab == TAB_ANIME && shownCount > 0) {
                                AppBarActions(
                                    persistentListOf(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_cancel_all),
                                            onClick = { animeManager.cancelAll() },
                                        ),
                                    ),
                                )
                            }
                            // KMK <--
                            if (tab == TAB_MANGA && downloadList.isNotEmpty()) {
                                var sortExpanded by remember { mutableStateOf(false) }
                                val onDismissRequest = { sortExpanded = false }
                                DropdownMenu(
                                    expanded = sortExpanded,
                                    onDismissRequest = onDismissRequest,
                                ) {
                                    NestedMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                        children = { closeMenu ->
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_newest)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.chapter.dateUpload },
                                                        true,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.chapter.dateUpload },
                                                        false,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                        },
                                    )
                                    NestedMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                        children = { closeMenu ->
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_asc)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.chapter.chapterNumber },
                                                        false,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_desc)) },
                                                onClick = {
                                                    screenModel.reorderQueue(
                                                        { it.download.chapter.chapterNumber },
                                                        true,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                        },
                                    )
                                }

                                AppBarActions(
                                    persistentListOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_sort),
                                            icon = Icons.AutoMirrored.Outlined.Sort,
                                            onClick = { sortExpanded = true },
                                        ),
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_cancel_all),
                                            onClick = { screenModel.clearQueue() },
                                        ),
                                    ),
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                    // KMK -->
                    MediaTabRow(
                        selected = tab,
                        onSelect = { tab = it },
                        counts = listOf(animeQueue.size, downloadCount),
                    )
                }
                // KMK <--
            },
            floatingActionButton = {
                val isRunning by screenModel.isDownloaderRunning.collectAsState()
                SmallExtendedFloatingActionButton(
                    text = {
                        val id = if (isRunning) {
                            MR.strings.action_pause
                        } else {
                            MR.strings.action_resume
                        }
                        Text(text = stringResource(id))
                    },
                    icon = {
                        val icon = if (isRunning) {
                            Icons.Outlined.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        }
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = {
                        if (isRunning) {
                            screenModel.pauseDownloads()
                        } else {
                            screenModel.startDownloads()
                        }
                    },
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = tab != TAB_ANIME && downloadList.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            // KMK: swipe left or right between Anime and Manga
            MediaTabPager(selected = tab, onSelect = { tab = it }) { page ->
                if (page == TAB_ANIME) {
                    if (animeQueue.isEmpty()) {
                        EmptyScreen(
                            stringRes = MR.strings.information_no_downloads,
                            modifier = Modifier.padding(contentPadding),
                        )
                        return@MediaTabPager
                    }
                    ScrollbarLazyColumn(
                        contentPadding = contentPadding,
                        modifier = Modifier.nestedScroll(nestedScrollConnection),
                    ) {
                        items(animeQueue, key = { "anime-${it.episode.id}" }) { item ->
                            AnimeDownloadQueueRow(
                                item = item,
                                manager = animeManager,
                                onClick = { navigator.push(AnimeScreen(item.anime.id)) },
                            )
                        }
                    }
                    return@MediaTabPager
                }
                MangaQueuePage(contentPadding, downloadList, nestedScrollConnection, screenModel, scope, collectorJobs)
            }
        }
    }

    // KMK: the manga queue, the original list
    @Composable
    private fun MangaQueuePage(
        contentPadding: androidx.compose.foundation.layout.PaddingValues,
        downloadList: List<DownloadHeaderItem>,
        nestedScrollConnection: NestedScrollConnection,
        screenModel: DownloadQueueScreenModel,
        scope: kotlinx.coroutines.CoroutineScope,
        collectorJobs: MutableList<Job>,
    ) {
        run {
            // KMK <--
            if (downloadList.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_downloads,
                    modifier = Modifier.padding(contentPadding),
                )
                return@run
            }

            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val left = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx().roundToInt() }
            val top = with(density) { contentPadding.calculateTopPadding().toPx().roundToInt() }
            val right = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx().roundToInt() }
            val bottom = with(density) { contentPadding.calculateBottomPadding().toPx().roundToInt() }

            // KMK -->
            val colorScheme = AndroidViewColorScheme(MaterialTheme.colorScheme)
            // KMK <--

            Box(modifier = Modifier.nestedScroll(nestedScrollConnection)) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        screenModel.controllerBinding = DownloadListBinding.inflate(LayoutInflater.from(context))
                        screenModel.adapter = DownloadAdapter(
                            screenModel.listener,
                            // KMK -->
                            colorScheme,
                            // KMK <--
                        )
                        screenModel.controllerBinding.root.adapter = screenModel.adapter
                        screenModel.adapter?.isHandleDragEnabled = true
                        screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                        ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                        // KMK -->
                        collectorJobs.forEach { it.cancel() }
                        collectorJobs.clear()
                        collectorJobs += scope.launchUI {
                            screenModel.getDownloadStatusFlow()
                                .collect(screenModel::onStatusChange)
                        }
                        collectorJobs += scope.launchUI {
                            screenModel.getDownloadProgressFlow()
                                .collect(screenModel::onUpdateDownloadedPages)
                        }
                        // KMK <--

                        screenModel.controllerBinding.root
                    },
                    update = {
                        screenModel.controllerBinding.root
                            .updatePadding(
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                            )

                        screenModel.adapter?.updateDataSet(downloadList)
                    },
                )
            }
        }
    }

    // KMK -->
    private const val TAB_ANIME = MediaTab.ANIME
    private const val TAB_MANGA = MediaTab.MANGA
    // KMK <--
}
