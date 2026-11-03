package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.diagnostics.DiagnosticsScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.tachiyomi.ui.pairsync.PairSyncDialog
import eu.kanade.tachiyomi.ui.pairsync.PairSyncPopup
import eu.kanade.tachiyomi.ui.setting.PlayerSettingsScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.sourcelab.SourceLabScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import exh.ui.batchadd.BatchAddScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object MoreTab : Tab {
    private fun readResolve(): Any = MoreTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(SettingsScreen())
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MoreScreenModel() }
        val downloadQueueState by screenModel.downloadQueueState.collectAsState()
        // KMK (r114): live counts for the More rows
        val categoryCount by screenModel.categoryCount.collectAsState()
        val updateErrorCount by screenModel.updateErrorCount.collectAsState()
        // KMK (r89): the Sync devices pop-up, for phones (the tablet rail has a Sync button too); KMK (r90): kept
        // open across rotation
        var showPairSync by rememberSaveable { mutableStateOf(false) }
        if (showPairSync) PairSyncDialog(onDismissRequest = { showPairSync = false })
        // KMK (r93): a tapped sync notification opens the pop-up (HomeScreen usually takes it first)
        LaunchedEffect(Unit) {
            PairSyncPopup.openRequest.collect { requested ->
                if (requested && PairSyncPopup.takeOpenRequest()) showPairSync = true
            }
        }
        MoreScreen(
            downloadQueueStateProvider = { downloadQueueState },
            downloadedOnly = screenModel.downloadedOnly,
            onDownloadedOnlyChange = { screenModel.downloadedOnly = it },
            incognitoMode = screenModel.incognitoMode,
            onIncognitoModeChange = { screenModel.incognitoMode = it },
            // SY -->
            showNavUpdates = screenModel.showNavUpdates,
            showNavHistory = screenModel.showNavHistory,
            // SY <--
            onClickDownloadQueue = { navigator.push(DownloadQueueScreen) },
            onClickCategories = { navigator.push(CategoryScreen()) },
            onClickStats = { navigator.push(StatsScreen()) },
            onClickDataAndStorage = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onClickSettings = { navigator.push(SettingsScreen()) },
            // KMK -->
            onClickPlayerSettings = { navigator.push(PlayerSettingsScreen()) },
            // KMK <--
            onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
            // SY -->
            onClickBatchAdd = { navigator.push(BatchAddScreen()) },
            onClickUpdates = { navigator.push(UpdatesTab) },
            onClickHistory = { navigator.push(HistoryTab) },
            // SY <--
            // KMK -->
            onClickLibraryUpdateErrors = { navigator.push(LibraryUpdateErrorScreen()) },
            // Logs was only on the tablet navigation rail; this opens the same screen on phones too
            // KMK (r97): the row keeps its "Logs" title and opens Diagnostics (last tab, or Log on a new problem)
            onClickLogs = { navigator.push(DiagnosticsScreen()) },
            onClickPairSync = { showPairSync = true },
            // KMK (r114): Source Lab moved here from the Browse › Sources toolbar
            onClickSourceLab = { navigator.push(SourceLabScreen()) },
            categoryCount = categoryCount,
            updateErrorCount = updateErrorCount,
            // KMK <--
        )
    }
}

private class MoreScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    preferences: BasePreferences = Injekt.get(),
    // SY -->
    uiPreferences: UiPreferences = Injekt.get(),
    // SY <--
    // KMK (r114) -->
    getCategories: GetCategories = Injekt.get(),
    animeRepository: AnimeRepository = Injekt.get(),
    getLibraryUpdateErrors: GetLibraryUpdateErrorWithRelations = Injekt.get(),
    // KMK <--
) : ScreenModel {

    var downloadedOnly by preferences.downloadedOnly().asState(screenModelScope)
    var incognitoMode by preferences.incognitoMode().asState(screenModelScope)

    // SY -->
    val showNavUpdates by uiPreferences.showNavUpdates().asState(screenModelScope)
    val showNavHistory by uiPreferences.showNavHistory().asState(screenModelScope)
    // SY <--

    // KMK (r114): More › Categories count (manga categories without the default one, plus anime categories, as the
    // Categories screen lists them) and More › Update errors count (what the Update errors screen lists)
    val categoryCount: StateFlow<Int> = combine(
        getCategories.subscribe().map { list -> list.count { !it.isSystemCategory } },
        animeRepository.subscribeCategories().map { it.size }.onStart { emit(0) }.catch { emit(0) },
    ) { manga, anime -> manga + anime }
        .catch { emit(0) }
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val updateErrorCount: StateFlow<Int> = getLibraryUpdateErrors.subscribeAll()
        .map { it.size }
        .catch { emit(0) }
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    init {
        // Handle running/paused status change and queue progress updating
        screenModelScope.launchIO {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
            ) { isRunning, downloadQueue -> Pair(isRunning, downloadQueue.size) }
                .collectLatest { (isDownloading, downloadQueueSize) ->
                    val pendingDownloadExists = downloadQueueSize != 0
                    _downloadQueueState.value = when {
                        !pendingDownloadExists -> DownloadQueueState.Stopped
                        !isDownloading -> DownloadQueueState.Paused(downloadQueueSize)
                        else -> DownloadQueueState.Downloading(downloadQueueSize)
                    }
                }
        }
    }
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}
