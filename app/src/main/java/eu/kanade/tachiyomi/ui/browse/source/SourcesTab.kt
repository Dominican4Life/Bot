package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined._18UpRating
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.model.icon
import eu.kanade.domain.source.model.installedExtension
import eu.kanade.presentation.browse.SourceCategoriesDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import exh.ui.smartsearch.SmartSearchScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun Screen.sourcesTab(
    smartSearchConfig: SmartSearchConfig? = null,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { SourcesScreenModel(smartSearchConfig = smartSearchConfig) }
    val state by screenModel.state.collectAsState()
    // KMK -->
    val context = LocalContext.current
    var sheetSource by remember { mutableStateOf<tachiyomi.domain.source.model.Source?>(null) }
    // KMK <--

    return TabContent(
        // SY -->
        titleRes = when (smartSearchConfig == null) {
            true -> MR.strings.label_sources
            false -> SYMR.strings.find_in_another_source
        },
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen(smartSearchConfig?.origTitle ?: "")) },
            ),
            // KMK -->
            AppBar.Action(
                title = stringResource(KMR.strings.action_toggle_nsfw_only),
                icon = Icons.Outlined._18UpRating,
                iconTint = if (state.nsfwOnly) MaterialTheme.colorScheme.error else LocalContentColor.current,
                onClick = { screenModel.toggleNsfwOnly() },
            ),
            // KMK <--
        ).let { base ->
            // KMK: the Code editor, for JS / JSON sources (Sumizuri style)
            if (smartSearchConfig != null) {
                base
            } else {
                base.add(
                    AppBar.Action(
                        title = "Code editor",
                        icon = Icons.Outlined.Code,
                        onClick = { navigator.push(eu.kanade.tachiyomi.ui.sumizuri.SourceEditorScreen()) },
                    ),
                )
            }
        },
        // KMK: the sources filter button now sits next to the search bar
        // SY <--
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    // SY -->
                    val screen = when {
                        // Search selected source for entries to merge or for the recommending entry
                        smartSearchConfig != null -> SmartSearchScreen(source.id, smartSearchConfig)
                        listing == Listing.Popular && screenModel.useNewSourceNavigation -> SourceFeedScreen(source.id)
                        else -> BrowseSourceScreen(source.id, listing.query)
                    }
                    navigator.push(screen)
                    // SY <--
                },
                onClickPin = screenModel::togglePin,
                onLongClickItem = screenModel::showSourceDialog,
                // KMK -->
                onChangeSearchQuery = screenModel::search,
                onClickFilter = { navigator.push(SourcesFilterScreen()) }.takeIf { smartSearchConfig == null },
                onClickSettings = { source -> sheetSource = source },
                onClickDeviceFiles = {
                    navigator.push(eu.kanade.tachiyomi.ui.devicefiles.DeviceFilesScreen())
                }.takeIf { smartSearchConfig == null },
                // KMK <--
            )

            // KMK --> per-source sheet: details, on/off, pin, WebView, cookies and site data
            sheetSource?.let { source ->
                val httpSource = remember(source.id) {
                    Injekt.get<tachiyomi.domain.source.service.SourceManager>().get(source.id)
                        as? eu.kanade.tachiyomi.source.online.HttpSource
                }
                val urls = remember(source.id) {
                    listOfNotNull(httpSource?.baseUrl, runCatching { httpSource?.getHomeUrl() }.getOrNull())
                }
                val extension = source.installedExtension
                eu.kanade.presentation.browse.components.SourceInfoSheet(
                    info = eu.kanade.presentation.browse.components.SourceSheetInfo(
                        name = source.name,
                        baseUrl = httpSource?.baseUrl.orEmpty(),
                        lang = source.lang,
                        isAnime = false,
                        icon = remember(source.id) { source.icon },
                        enabled = true,
                        pinned = tachiyomi.domain.source.model.Pin.Pinned in source.pin,
                        isNsfw = extension?.isNsfw == true,
                        extensionName = extension?.name,
                        versionName = extension?.versionName,
                    ),
                    onDismissRequest = { sheetSource = null },
                    onToggleEnabled = { enabled ->
                        if (!enabled) {
                            screenModel.toggleSource(source)
                            sheetSource = null
                        }
                    },
                    onTogglePin = {
                        screenModel.togglePin(source)
                        sheetSource = null
                    },
                    onOpenWebView = httpSource?.let {
                        {
                            sheetSource = null
                            navigator.push(WebViewScreen(url = it.baseUrl, initialTitle = it.name, sourceId = it.id))
                        }
                    },
                    onClearCookies = {
                        val network = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>()
                        urls.forEach { url ->
                            runCatching { network.cookieJar.remove(url.toHttpUrl()) }
                        }
                        context.toast(MR.strings.cookies_cleared)
                    },
                    onClearSiteData = {
                        eu.kanade.presentation.browse.components.clearSourceSiteData(urls)
                        context.toast(KMR.strings.source_sheet_site_data_cleared)
                    },
                    onOpenLatest = {
                        sheetSource = null
                        navigator.push(BrowseSourceScreen(source.id, GetRemoteManga.QUERY_LATEST))
                    }.takeIf { source.supportsLatest },
                    onRemove = extension?.let { ext ->
                        {
                            sheetSource = null
                            Injekt.get<eu.kanade.tachiyomi.extension.ExtensionManager>().uninstallExtension(ext)
                        }
                    } ?: (httpSource as? eu.kanade.tachiyomi.sumizuri.SumizuriMangaSource)?.let { js ->
                        {
                            sheetSource = null
                            Injekt.get<eu.kanade.tachiyomi.sumizuri.SumizuriManager>().delete(js.info.key)
                        }
                    },
                    onSettings = extension?.let { ext ->
                        {
                            sheetSource = null
                            navigator.push(ExtensionDetailsScreen(ext.pkgName))
                        }
                    } ?: (httpSource as? eu.kanade.tachiyomi.sumizuri.SumizuriMangaSource)?.let { js ->
                        // A source from the Code editor: its code opens in the editor
                        {
                            sheetSource = null
                            navigator.push(eu.kanade.tachiyomi.ui.sumizuri.SourceEditorScreen(js.info.key))
                        }
                    },
                )
            }
            // KMK <--

            when (val dialog = state.dialog) {
                is SourcesScreenModel.Dialog.SourceLongClick -> {
                    val source = dialog.source
                    SourceOptionsDialog(
                        source = source,
                        onClickPin = {
                            screenModel.togglePin(source)
                            screenModel.closeDialog()
                        },
                        onClickDisable = {
                            screenModel.toggleSource(source)
                            screenModel.closeDialog()
                        },
                        // SY -->
                        onClickSetCategories = {
                            screenModel.showSourceCategoriesDialog(source)
                        }.takeIf { state.categories.isNotEmpty() },
                        onClickToggleDataSaver = {
                            screenModel.toggleExcludeFromDataSaver(source)
                            screenModel.closeDialog()
                        }.takeIf { state.dataSaverEnabled },
                        // SY <--
                        onDismiss = screenModel::closeDialog,
                        // KMK -->
                        onClickSettings = {
                            if (source.installedExtension !== null) {
                                navigator.push(ExtensionDetailsScreen(source.installedExtension!!.pkgName))
                            }
                            screenModel.closeDialog()
                        },
                        // KMK <--
                    )
                }
                is SourcesScreenModel.Dialog.SourceCategories -> {
                    val source = dialog.source
                    SourceCategoriesDialog(
                        source = source,
                        categories = state.categories,
                        onClickCategories = { categories ->
                            screenModel.setSourceCategories(source, categories)
                            screenModel.closeDialog()
                        },
                        onDismissRequest = screenModel::closeDialog,
                    )
                }
                null -> Unit
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        SourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
