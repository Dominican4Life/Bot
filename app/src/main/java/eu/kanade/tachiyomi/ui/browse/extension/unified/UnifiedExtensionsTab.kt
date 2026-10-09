package eu.kanade.tachiyomi.ui.browse.extension.unified

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.ExtensionTrustDialog
import eu.kanade.presentation.browse.UNIFIED_TAB_COUNT
import eu.kanade.presentation.browse.UNIFIED_TAB_PACKAGES
import eu.kanade.presentation.browse.UnifiedExtensionsContent
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoreDialog
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoreScreenState
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreenModel
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreConfirmDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreCreateDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreDeleteDialog
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceFeedScreen
import eu.kanade.tachiyomi.ui.anime.source.AnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.AnimeExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: The Extensions tab of Browse: [UnifiedExtensionsContent] for every kind of extension. Adding and removing
 * repositories goes through the existing repo screen models (an Aniyomi repo added here lands with the anime
 * repos on its own).
 */
@Composable
fun unifiedExtensionsTab(
    screenModel: UnifiedExtensionsScreenModel,
    mangaStores: ExtensionStoresScreenModel,
    animeStores: ExtensionStoresScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val state by screenModel.state.collectAsState()
    val storesState by mangaStores.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_extensions,
        badgeNumber = state.updateCount.takeIf { it > 0 },
        searchEnabled = false,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(ExtensionFilterScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            val scope = rememberCoroutineScope()
            val pagerState = rememberPagerState { UNIFIED_TAB_COUNT }
            var trustItem by remember { mutableStateOf<UnifiedPackage?>(null) }
            var removeItem by remember { mutableStateOf<UnifiedPackage?>(null) }
            var deleteRepo by remember { mutableStateOf<UnifiedRepo?>(null) }

            BackHandler(enabled = state.filters.query.isNotEmpty()) { screenModel.search("") }

            // JAR import from storage, and messages from JAR installs
            val importJar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) screenModel.importJar(uri)
            }
            LaunchedEffect(state.message) {
                state.message?.let {
                    context.toast(it)
                    screenModel.consumeMessage()
                }
            }

            fun openDetails(kind: ExtensionKind, pkgName: String) {
                if (kind == ExtensionKind.ANIYOMI) {
                    navigator.push(AnimeExtensionDetailsScreen(pkgName))
                } else {
                    navigator.push(ExtensionDetailsScreen(pkgName))
                }
            }

            UnifiedExtensionsContent(
                state = state,
                pagerState = pagerState,
                contentPadding = contentPadding,
                onSearch = screenModel::search,
                onRefresh = screenModel::refresh,
                onKindClick = screenModel::toggleKind,
                onRepoClick = screenModel::selectRepo,
                onStatusClick = screenModel::selectStatus,
                onSourceEnabled = screenModel::setSourceEnabled,
                onSourcesEnabled = screenModel::setAllSourcesEnabled,
                onSourcePin = screenModel::togglePin,
                onSourceClick = { source ->
                    val feed = Injekt.get<UiPreferences>().useNewSourceNavigation().get()
                    navigator.push(
                        when {
                            source.kind == ExtensionKind.ANIYOMI && feed -> AnimeSourceFeedScreen(source.id)
                            source.kind == ExtensionKind.ANIYOMI -> AnimeSourceScreen(source.id)
                            feed -> SourceFeedScreen(source.id)
                            else -> BrowseSourceScreen(source.id, GetRemoteManga.QUERY_POPULAR)
                        },
                    )
                },
                onSourceSettings = { source ->
                    if (source.kind == ExtensionKind.JAR) {
                        navigator.push(BrowseSourceScreen(source.id, GetRemoteManga.QUERY_POPULAR))
                    } else {
                        openDetails(source.kind, source.pkgName)
                    }
                },
                onAddRepo = { mangaStores.showDialog(ExtensionStoreDialog.Create()) },
                onRepoEnabled = screenModel::setRepoEnabled,
                onRepoDelete = { deleteRepo = it },
                onRepoOpen = { repo ->
                    // A repo's packages: the Packages tab narrowed to it
                    if (state.filters.repoUrl != repo.indexUrl) screenModel.selectRepo(repo.indexUrl)
                    scope.launch { pagerState.animateScrollToPage(UNIFIED_TAB_PACKAGES) }
                },
                onUpdateAll = screenModel::updateAll,
                onPackagePrimary = { item ->
                    if (item.state == PackageState.UNTRUSTED) trustItem = item else screenModel.primaryAction(item)
                },
                onPackageCancel = screenModel::cancel,
                onPackageRemove = { item ->
                    if (context.isPackageInstalled(item.pkgName)) screenModel.uninstall(item) else removeItem = item
                },
                onImportJar = { importJar.launch(arrayOf("*/*")) },
                onPackageDetails = { item ->
                    when {
                        item.kind == ExtensionKind.JAR -> item.error?.let { context.toast(it) }
                        else -> when (item.state) {
                        PackageState.UNTRUSTED -> trustItem = item
                        PackageState.AVAILABLE, PackageState.INSTALLING -> Unit
                        else -> openDetails(item.kind, item.pkgName)
                        }
                    }
                },
            )

            trustItem?.let { item ->
                ExtensionTrustDialog(
                    onClickConfirm = {
                        screenModel.trust(item)
                        trustItem = null
                    },
                    onClickDismiss = {
                        screenModel.uninstall(item)
                        trustItem = null
                    },
                    onDismissRequest = { trustItem = null },
                )
            }

            removeItem?.let { item ->
                AlertDialog(
                    onDismissRequest = { removeItem = null },
                    title = { Text(stringResource(MR.strings.ext_confirm_remove)) },
                    text = { Text(stringResource(MR.strings.remove_private_extension_message, item.name)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                screenModel.uninstall(item)
                                removeItem = null
                            },
                        ) { Text(stringResource(MR.strings.ext_remove)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { removeItem = null }) { Text(stringResource(MR.strings.action_cancel)) }
                    },
                )
            }

            deleteRepo?.let { repo ->
                ExtensionStoreDeleteDialog(
                    onDismissRequest = { deleteRepo = null },
                    onDelete = {
                        when (repo.kind) {
                            ExtensionKind.JAR -> screenModel.removeJarRepo(repo.indexUrl)
                            ExtensionKind.ANIYOMI -> animeStores.deleteRepo(repo.indexUrl)
                            else -> mangaStores.deleteRepo(repo.indexUrl)
                        }
                        deleteRepo = null
                    },
                    storeName = repo.name,
                    storeIndexUrl = repo.indexUrl,
                )
            }

            val success = storesState as? ExtensionStoreScreenState.Success
            when (val dialog = success?.dialog) {
                is ExtensionStoreDialog.Create -> ExtensionStoreCreateDialog(
                    onDismissRequest = mangaStores::dismissDialog,
                    onCreate = { url ->
                        // A GitHub repository is a JAR repo; anything else goes to the APK repos
                        if (screenModel.addJarRepoIfMatches(url)) mangaStores.dismissDialog() else mangaStores.createRepo(url)
                    },
                    storeIndexUrls = state.allRepos.map { it.indexUrl }.toSet(),
                    processing = dialog.processing,
                    errorMessage = dialog.errorMessage,
                )
                is ExtensionStoreDialog.Confirm -> ExtensionStoreConfirmDialog(
                    onDismissRequest = mangaStores::dismissDialog,
                    onCreate = { mangaStores.createRepo(dialog.url) },
                    storeIndexUrl = dialog.url,
                    storeAlreadyExists = dialog.alreadyExists,
                    processing = dialog.processing,
                    errorMessage = dialog.errorMessage,
                )
                else -> Unit
            }
        },
    )
}
