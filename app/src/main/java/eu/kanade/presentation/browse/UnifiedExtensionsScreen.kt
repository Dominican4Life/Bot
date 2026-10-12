package eu.kanade.presentation.browse

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.unified.ExtensionKind
import eu.kanade.tachiyomi.ui.browse.extension.unified.PackageState
import eu.kanade.tachiyomi.ui.browse.extension.unified.PackageStatusFilter
import eu.kanade.tachiyomi.ui.browse.extension.unified.UnifiedExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.unified.UnifiedPackage
import eu.kanade.tachiyomi.ui.browse.extension.unified.UnifiedRepo
import eu.kanade.tachiyomi.ui.browse.extension.unified.UnifiedSource
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

const val UNIFIED_TAB_SOURCES = 0
const val UNIFIED_TAB_REPOSITORIES = 1
const val UNIFIED_TAB_PACKAGES = 2
const val UNIFIED_TAB_COUNT = 3

private val CardShape = RoundedCornerShape(16.dp)
private val ListPadding = 12.dp

/**
 * KMK: Extension management in one place, modelled on Kototoro: Sources / Repositories / Packages tabs, a search
 * box, and chip rows to narrow by repository, status and kind (Mihon, Aniyomi, JAR, Cloudstream).
 */
@Composable
fun UnifiedExtensionsContent(
    state: UnifiedExtensionsScreenModel.State,
    pagerState: PagerState,
    contentPadding: PaddingValues,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onKindClick: (ExtensionKind?) -> Unit,
    onRepoClick: (String?) -> Unit,
    onStatusClick: (PackageStatusFilter) -> Unit,
    onSourceEnabled: (UnifiedSource, Boolean) -> Unit,
    onSourcesEnabled: (List<UnifiedSource>, Boolean) -> Unit,
    onSourcePin: (UnifiedSource) -> Unit,
    onSourceClick: (UnifiedSource) -> Unit,
    onSourceSettings: (UnifiedSource) -> Unit,
    onAddRepo: () -> Unit,
    onRepoEnabled: (UnifiedRepo, Boolean) -> Unit,
    onRepoDelete: (UnifiedRepo) -> Unit,
    onRepoOpen: (UnifiedRepo) -> Unit,
    onUpdateAll: () -> Unit,
    onPackagePrimary: (UnifiedPackage) -> Unit,
    onPackageCancel: (UnifiedPackage) -> Unit,
    onPackageRemove: (UnifiedPackage) -> Unit,
    onPackageDetails: (UnifiedPackage) -> Unit,
    // KMK --> JS sources: edit in the Code editor, delete, make a new one
    onJsEdit: (UnifiedSource) -> Unit,
    onJsDelete: (UnifiedSource) -> Unit,
    onJsNew: () -> Unit,
    // KMK <--
) {
    val scope = rememberCoroutineScope()
    val tab = pagerState.currentPage
    val sources = state.sources
    val repos = state.repos
    val packages = state.packages

    Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
        SecondaryTabRow(selectedTabIndex = tab) {
            listOf(
                stringResource(KMR.strings.ext_tab_sources, sources.size),
                stringResource(KMR.strings.ext_tab_repositories, repos.size),
                stringResource(KMR.strings.ext_tab_packages, packages.size),
            ).forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            SourcesSearchBox(
                searchQuery = state.filters.query,
                onChangeSearchQuery = { onSearch(it.orEmpty()) },
                placeholderText = stringResource(KMR.strings.ext_search_all),
                modifier = Modifier.weight(1f),
            )
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_webview_refresh))
                }
            }
        }
        FilterRows(tab, state, onKindClick, onRepoClick, onStatusClick)
        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        val listPadding = PaddingValues(
            start = ListPadding,
            end = ListPadding,
            top = 4.dp,
            bottom = contentPadding.calculateBottomPadding() + ListPadding,
        )
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            when (page) {
                UNIFIED_TAB_SOURCES -> SourceList(
                    sources = sources,
                    padding = listPadding,
                    emptyKinds = state.filters.kinds,
                    onEnabled = onSourceEnabled,
                    onAllEnabled = onSourcesEnabled,
                    onPin = onSourcePin,
                    onClick = onSourceClick,
                    onSettings = onSourceSettings,
                    // KMK
                    onJsEdit = onJsEdit,
                    onJsDelete = onJsDelete,
                    onJsNew = onJsNew,
                )
                UNIFIED_TAB_REPOSITORIES -> RepoList(
                    repos = repos,
                    padding = listPadding,
                    emptyKinds = state.filters.kinds,
                    onAdd = onAddRepo,
                    onEnabled = onRepoEnabled,
                    onDelete = onRepoDelete,
                    onOpen = onRepoOpen,
                )
                else -> PackageList(
                    packages = packages,
                    updateCount = state.updateCount,
                    padding = listPadding,
                    emptyKinds = state.filters.kinds,
                    onUpdateAll = onUpdateAll,
                    onPrimary = onPackagePrimary,
                    onCancel = onPackageCancel,
                    onRemove = onPackageRemove,
                    onDetails = onPackageDetails,
                )
            }
        }
    }
}

@Composable
private fun FilterRows(
    tab: Int,
    state: UnifiedExtensionsScreenModel.State,
    onKindClick: (ExtensionKind?) -> Unit,
    onRepoClick: (String?) -> Unit,
    onStatusClick: (PackageStatusFilter) -> Unit,
) {
    val filters = state.filters
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        if (tab != UNIFIED_TAB_REPOSITORIES && state.allRepos.size > 1) {
            ChipRow {
                item(key = "repo_all") {
                    Chip(stringResource(KMR.strings.ext_all_repositories), filters.repoUrl == null) { onRepoClick(null) }
                }
                items(state.allRepos, key = { "repo_" + it.indexUrl }) { repo ->
                    Chip(repo.name, filters.repoUrl == repo.indexUrl) { onRepoClick(repo.indexUrl) }
                }
            }
        }
        if (tab == UNIFIED_TAB_PACKAGES) {
            ChipRow {
                items(PackageStatusFilter.entries, key = { it.name }) { status ->
                    Chip(
                        text = stringResource(
                            when (status) {
                                PackageStatusFilter.ALL -> KMR.strings.ext_status_all
                                PackageStatusFilter.UPDATES -> KMR.strings.ext_status_updates
                                PackageStatusFilter.INSTALLED -> KMR.strings.ext_status_installed
                                PackageStatusFilter.NOT_INSTALLED -> KMR.strings.ext_status_not_installed
                            },
                        ),
                        selected = filters.status == status,
                    ) { onStatusClick(status) }
                }
            }
        }
        ChipRow {
            item(key = "kind_all") {
                Chip(
                    text = stringResource(KMR.strings.ext_all_sources),
                    selected = filters.kinds.isEmpty(),
                    leading = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(16.dp)) },
                ) { onKindClick(null) }
            }
            items(ExtensionKind.entries, key = { it.name }) { kind ->
                Chip(kind.label, kind in filters.kinds, leading = { KindDot(kind) }) { onKindClick(kind) }
            }
        }
    }
}

@Composable
private fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        leadingIcon = leading,
    )
}

@Composable
private fun kindColor(kind: ExtensionKind) = when (kind) {
    ExtensionKind.MIHON -> MaterialTheme.colorScheme.primary
    ExtensionKind.ANIYOMI -> MaterialTheme.colorScheme.tertiary
    // KMK: yellow, darker on light themes so the text stays readable
    ExtensionKind.JS -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) JsYellowDark else JsYellowLight
}

private val JsYellowDark = androidx.compose.ui.graphics.Color(0xFFFFCA28)
private val JsYellowLight = androidx.compose.ui.graphics.Color(0xFFA67C00)

@Composable
private fun KindDot(kind: ExtensionKind) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(kindColor(kind)),
    )
}

@Composable
private fun Tag(text: String, warning: Boolean = false, color: androidx.compose.ui.graphics.Color? = null) {
    val tint = color ?: if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun PackageIcon(drawable: Drawable?, url: String?, size: Int = 36) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        when {
            drawable != null -> {
                val bitmap = remember(drawable) { runCatching { drawable.toBitmap().asImageBitmap() }.getOrNull() }
                if (bitmap != null) Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            url != null -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
            else -> Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size((size / 2).dp),
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
    )
}

@Suppress("UNUSED_PARAMETER")
private fun emptyText(kinds: Set<ExtensionKind>, default: String): String = default

@Composable
private fun SourceList(
    sources: List<UnifiedSource>,
    padding: PaddingValues,
    emptyKinds: Set<ExtensionKind>,
    onEnabled: (UnifiedSource, Boolean) -> Unit,
    onAllEnabled: (List<UnifiedSource>, Boolean) -> Unit,
    onPin: (UnifiedSource) -> Unit,
    onClick: (UnifiedSource) -> Unit,
    onSettings: (UnifiedSource) -> Unit,
    // KMK -->
    onJsEdit: (UnifiedSource) -> Unit,
    onJsDelete: (UnifiedSource) -> Unit,
    onJsNew: () -> Unit,
    // KMK <--
) {
    // KMK: with the JS chip on, a button to make a new JS source
    val showNewJs = ExtensionKind.JS in emptyKinds
    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (sources.isEmpty() && !showNewJs) {
            item(key = "empty") { EmptyState(emptyText(emptyKinds, stringResource(KMR.strings.ext_no_sources))) }
            return@LazyColumn
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // KMK -->
                if (showNewJs) {
                    AssistChip(
                        onClick = onJsNew,
                        label = { Text(stringResource(KMR.strings.ext_js_new)) },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                // KMK <--
                AssistChip(
                    onClick = { onAllEnabled(sources, true) },
                    label = { Text(stringResource(MR.strings.action_enable_all)) },
                )
                AssistChip(
                    onClick = { onAllEnabled(sources, false) },
                    label = { Text(stringResource(MR.strings.action_disable_all)) },
                )
            }
        }
        // KMK
        if (sources.isEmpty()) item(key = "empty_js") { EmptyState(stringResource(KMR.strings.ext_js_empty)) }
        items(sources, key = { "${it.kind}_${it.id}" }) { source ->
            ElevatedCard(
                shape = CardShape,
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onClick(source) }, onLongClick = { onSettings(source) })
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    PackageIcon(source.icon, source.js?.iconUrl)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            // KMK: a JSON source says so; JS sources also say manga or anime
                            Tag(if (source.js?.json == true) "JSON" else source.kind.label, color = kindColor(source.kind))
                            source.js?.let {
                                Tag(stringResource(if (it.anime) KMR.strings.ext_js_anime else KMR.strings.ext_js_manga))
                            }
                            Tag(langLabel(source.lang))
                            if (source.isNsfw) Tag(stringResource(MR.strings.ext_nsfw_short), warning = true)
                            source.repoName?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onPin(source) }) {
                        Icon(
                            imageVector = if (source.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(if (source.pinned) MR.strings.action_unpin else MR.strings.action_pin),
                            tint = if (source.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // KMK -->
                    if (source.js != null) {
                        IconButton(onClick = { onJsEdit(source) }) {
                            Icon(Icons.Outlined.Code, contentDescription = stringResource(KMR.strings.ext_js_edit))
                        }
                    }
                    // KMK <--
                    IconButton(onClick = { onSettings(source) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(MR.strings.action_settings))
                    }
                    // KMK -->
                    if (source.js != null) {
                        IconButton(onClick = { onJsDelete(source) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
                        }
                    }
                    // KMK <--
                    Switch(checked = source.enabled, onCheckedChange = { onEnabled(source, it) })
                }
            }
        }
    }
}

@Composable
private fun RepoList(
    repos: List<UnifiedRepo>,
    padding: PaddingValues,
    emptyKinds: Set<ExtensionKind>,
    onAdd: () -> Unit,
    onEnabled: (UnifiedRepo, Boolean) -> Unit,
    onDelete: (UnifiedRepo) -> Unit,
    onOpen: (UnifiedRepo) -> Unit,
) {
    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "add") {
            AssistChip(
                onClick = onAdd,
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(stringResource(KMR.strings.ext_add_repository)) },
            )
        }
        if (repos.isEmpty()) {
            item(key = "empty") { EmptyState(emptyText(emptyKinds, stringResource(KMR.strings.ext_no_repositories))) }
        }
        items(repos, key = { "${it.kind}_${it.indexUrl}" }) { repo ->
            ElevatedCard(
                shape = CardShape,
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(repo) }, onLongClick = { onDelete(repo) })
                        .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = repo.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Tag(repo.kind.label, color = kindColor(repo.kind))
                        }
                        Text(
                            text = repo.indexUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(KMR.strings.ext_repo_counts, repo.packageCount, repo.installedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    IconButton(onClick = { onDelete(repo) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
                    }
                    Switch(checked = repo.enabled, onCheckedChange = { onEnabled(repo, it) })
                }
            }
        }
    }
}

@Composable
private fun PackageList(
    packages: List<UnifiedPackage>,
    updateCount: Int,
    padding: PaddingValues,
    emptyKinds: Set<ExtensionKind>,
    onUpdateAll: () -> Unit,
    onPrimary: (UnifiedPackage) -> Unit,
    onCancel: (UnifiedPackage) -> Unit,
    onRemove: (UnifiedPackage) -> Unit,
    onDetails: (UnifiedPackage) -> Unit,
) {
    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (updateCount > 0) {
            item(key = "update_all") {
                ElevatedCard(
                    shape = CardShape,
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(KMR.strings.ext_updates_banner, updateCount),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                        )
                        Button(onClick = onUpdateAll, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                            Text(stringResource(MR.strings.ext_update_all), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (packages.isEmpty()) {
            item(key = "empty") { EmptyState(emptyText(emptyKinds, stringResource(KMR.strings.ext_no_packages))) }
        }
        items(packages, key = { it.key }) { item ->
            PackageRow(
                item = item,
                onPrimary = { onPrimary(item) },
                onCancel = { onCancel(item) },
                onRemove = { onRemove(item) },
                onDetails = { onDetails(item) },
            )
        }
    }
}

@Composable
private fun PackageRow(
    item: UnifiedPackage,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
) {
    ElevatedCard(
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onDetails, onLongClick = onDetails)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PackageIcon(item.icon, item.iconUrl)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Tag(item.kind.label, color = kindColor(item.kind))
                        stateLabel(item.state)?.let { (text, warning) -> Tag(text, warning = warning) }
                    }
                    Text(
                        text = buildString {
                            append("v").append(item.versionName)
                            item.repoName?.let { append(" · ").append(it) }
                            if (item.state.isInstalled && item.totalSources > 0) {
                                append(" · ")
                                append(stringResource(KMR.strings.ext_active_count, item.activeSources, item.totalSources))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.lang.isNotBlank()) Tag(langLabel(item.lang))
                item.installedVersionName?.let { Tag(stringResource(KMR.strings.ext_installed_version, it)) }
                if (item.isNsfw) Tag(stringResource(MR.strings.ext_nsfw_short), warning = true)
            }
            if (item.sourceNames.isNotEmpty() && item.sourceNames != listOf(item.name)) {
                Text(
                    text = item.sourceNames.take(8).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.state == PackageState.INSTALLING) {
                Text(
                    text = stringResource(
                        when (item.installStep) {
                            InstallStep.Pending -> MR.strings.ext_pending
                            InstallStep.Downloading -> MR.strings.ext_downloading
                            else -> MR.strings.ext_installing
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (item.state) {
                    PackageState.AVAILABLE -> ActionChip(stringResource(MR.strings.ext_install), onPrimary)
                    PackageState.UPDATE_AVAILABLE -> ActionChip(stringResource(MR.strings.ext_update), onPrimary)
                    PackageState.UNTRUSTED -> ActionChip(stringResource(MR.strings.ext_trust), onPrimary)
                    PackageState.INSTALLING -> ActionChip(stringResource(MR.strings.action_cancel), onCancel)
                    else -> Unit
                }
                if (item.state.isInstalled) {
                    ActionChip(stringResource(MR.strings.ext_remove), onRemove)
                    if (item.state != PackageState.UNTRUSTED) ActionChip(stringResource(KMR.strings.ext_details), onDetails)
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(text) })
}

@Composable
private fun stateLabel(state: PackageState): Pair<String, Boolean>? = when (state) {
    PackageState.UPDATE_AVAILABLE -> stringResource(MR.strings.ext_update) to false
    PackageState.UNTRUSTED -> stringResource(MR.strings.ext_untrusted) to true
    PackageState.OBSOLETE -> stringResource(MR.strings.ext_obsolete) to true
    PackageState.INSTALLED -> stringResource(MR.strings.ext_installed) to false
    else -> null
}

private fun langLabel(lang: String): String = when (lang) {
    "all", "multi", "mul", "" -> "ALL"
    "other" -> "?"
    else -> lang.uppercase()
}
