package eu.kanade.presentation.browse.anime

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.presentation.browse.ExtensionHeader
import eu.kanade.presentation.browse.ExtensionTrustDialog
import eu.kanade.presentation.browse.components.SourceTile
import eu.kanade.presentation.browse.components.SourceTileGap
import eu.kanade.presentation.browse.components.SourceTileHeader
import eu.kanade.presentation.browse.components.SourceTileMinWidth
import eu.kanade.presentation.browse.components.sourceSiteOf
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.components.rememberSourceBadgeCorners
import eu.kanade.presentation.manga.components.DotSeparatorNoSpaceText
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.anime.AnimeBrowseScreenModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Shows manga on the left and anime on the right on tablets. On narrow screens it shows one at a
 * time, with a Manga / Anime switch at the top.
 */
@Composable
fun MangaAnimeSplit(
    contentPadding: PaddingValues,
    manga: @Composable (PaddingValues) -> Unit,
    anime: @Composable (PaddingValues) -> Unit,
) {
    if (isTabletUi()) {
        val layoutDirection = LocalLayoutDirection.current
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                manga(
                    PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                        start = contentPadding.calculateStartPadding(layoutDirection),
                    ),
                )
            }
            VerticalDivider(
                modifier = Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                anime(
                    PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                    ),
                )
            }
        }
    } else {
        var selected by rememberSaveable { mutableIntStateOf(0) }
        Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            PrimaryTabRow(selectedTabIndex = selected) {
                Tab(
                    selected = selected == 0,
                    onClick = { selected = 0 },
                    text = { Text(stringResource(KMR.strings.browse_manga)) },
                )
                Tab(
                    selected = selected == 1,
                    onClick = { selected = 1 },
                    text = { Text(stringResource(KMR.strings.browse_anime)) },
                )
            }
            val inner = PaddingValues(bottom = contentPadding.calculateBottomPadding())
            Box(modifier = Modifier.weight(1f)) {
                if (selected == 0) manga(inner) else anime(inner)
            }
        }
    }
}

/** Anime side of the Sources tab */
@Composable
fun AnimeSourcesPane(
    state: AnimeBrowseScreenModel.State,
    contentPadding: PaddingValues,
    onSearch: (String?) -> Unit,
    onTogglePin: (Long) -> Unit,
    onClickSource: (Long) -> Unit,
    onClickFilter: () -> Unit,
    onClickGlobalSearch: () -> Unit,
    onClickSettings: (pkgName: String) -> Unit,
    onClickLatest: (sourceId: Long) -> Unit,
) {
    val context = LocalContext.current
    val extensionManager = remember { Injekt.get<AnimeExtensionManager>() }
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            SourcesSearchBox(
                searchQuery = state.sourceQuery,
                onChangeSearchQuery = onSearch,
                placeholderText = stringResource(KMR.strings.browse_search_anime_sources),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClickGlobalSearch) {
                Icon(
                    imageVector = Icons.Outlined.TravelExplore,
                    contentDescription = stringResource(MR.strings.action_global_search),
                )
            }
            // Anime sources filter (languages / single sources), like the manga one
            IconButton(onClick = onClickFilter) {
                Icon(
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = stringResource(MR.strings.action_filter),
                    tint = if (state.filterActive) MaterialTheme.colorScheme.active else LocalContentColor.current,
                )
            }
        }
        val sources = state.filteredSources
        if (sources.isEmpty()) {
            EmptyMessage(stringResource(KMR.strings.anime_no_sources))
            return@Column
        }
        val pinnedTitle = stringResource(MR.strings.pinned_sources)
        val pinned = sources.filter { it.id.toString() in state.pinnedSources }
        // KMK: tiles, like the manga sources
        val corners = rememberSourceBadgeCorners()
        FastScrollLazyVerticalGrid(
            columns = GridCells.Adaptive(SourceTileMinWidth),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(SourceTileGap),
            verticalArrangement = Arrangement.spacedBy(SourceTileGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            val tile: @Composable (AnimeCatalogueSource) -> Unit = { source ->
                val icon = remember(source.id) {
                    extensionManager.getAppIconForSource(source.id)?.toBitmap()?.asImageBitmap()
                }
                val ext = remember(source.id) {
                    extensionManager.installedExtensionsFlow.value.find { e -> e.sources.any { it.id == source.id } }
                }
                SourceTile(
                    name = source.name,
                    site = remember(source.id) { sourceSiteOf((source as? AnimeHttpSource)?.baseUrl) },
                    icon = icon,
                    lang = source.lang,
                    isNsfw = ext?.isNsfw == true,
                    isPinned = source.id.toString() in state.pinnedSources,
                    supportsLatest = source.supportsLatest,
                    corners = corners,
                    onClick = { onClickSource(source.id) },
                    onLongClick = { onTogglePin(source.id) },
                    onClickSettings = ext?.let { { onClickSettings(it.pkgName) } },
                    onClickPin = { onTogglePin(source.id) },
                    onClickLatest = { onClickLatest(source.id) },
                )
            }
            if (pinned.isNotEmpty()) {
                item(key = "header-pinned", span = { GridItemSpan(maxLineSpan) }) { SourceTileHeader(pinnedTitle) }
                items(pinned, key = { "anime-pinned-${it.id}" }) { tile(it) }
            }
            sources.groupBy { it.lang }.forEach { (lang, langSources) ->
                item(key = "header-$lang", span = { GridItemSpan(maxLineSpan) }) {
                    SourceTileHeader(LocaleHelper.getSourceDisplayName(lang, context))
                }
                items(langSources, key = { "anime-source-${it.id}" }) { tile(it) }
            }
        }
    }
}

/** Anime side of the Extensions tab */
@Composable
fun AnimeExtensionsPane(
    state: AnimeBrowseScreenModel.State,
    contentPadding: PaddingValues,
    onSearch: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenRepos: () -> Unit,
    onInstall: (Extension.Available) -> Unit,
    onUpdate: (AnimeExtension) -> Unit,
    onUninstall: (String) -> Unit,
    onTrust: (Extension.Untrusted) -> Unit,
    onCancel: (pkgName: String, signatureHash: String) -> Unit,
    onOpenDetails: (AnimeExtension) -> Unit,
    onOpenWebView: (Extension.Available) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        ExtensionsPaneHeader(
            searchQuery = state.extensionQuery,
            onSearch = onSearch,
            placeholder = stringResource(KMR.strings.browse_search_anime_extensions),
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            onOpenRepos = onOpenRepos,
        )

        val nothing = state.updates.isEmpty() && state.installed.isEmpty() &&
            state.untrusted.isEmpty() && state.available.isEmpty()
        if (nothing && !state.isRefreshing) {
            EmptyMessage(stringResource(KMR.strings.anime_no_extensions))
            return@Column
        }

        val updatesTitle = stringResource(MR.strings.ext_updates_pending)
        val installedTitle = stringResource(MR.strings.ext_installed)
        val untrustedTitle = stringResource(MR.strings.ext_untrusted)
        val availableTitle = stringResource(KMR.strings.anime_available)
        var trustDialogFor by remember { mutableStateOf<Extension.Untrusted?>(null) }
        trustDialogFor?.let { ext ->
            ExtensionTrustDialog(
                onClickConfirm = {
                    onTrust(ext)
                    trustDialogFor = null
                },
                onClickDismiss = {
                    onUninstall(ext.pkgName)
                    trustDialogFor = null
                },
                onDismissRequest = { trustDialogFor = null },
            )
        }
        val hasUpdates = state.filteredUpdates.isNotEmpty()
        val hasInstalled = state.filteredInstalled.isNotEmpty()
        val hasUntrusted = state.filteredUntrusted.isNotEmpty()
        FastScrollLazyColumn(modifier = Modifier.fillMaxSize()) {
            section(updatesTitle, state.filteredUpdates, first = true) { ext ->
                InstalledRow(ext, state.installSteps[ext.pkgName], onUpdate, onUninstall, onCancel, onOpenDetails)
            }
            section(installedTitle, state.filteredInstalled, first = !hasUpdates) { ext ->
                InstalledRow(ext, state.installSteps[ext.pkgName], onUpdate, onUninstall, onCancel, onOpenDetails)
            }
            section(untrustedTitle, state.filteredUntrusted, first = !hasUpdates && !hasInstalled) { ext ->
                PaneRow(
                    icon = { UntrustedIcon() },
                    title = ext.name,
                    lang = ext.lang,
                    version = ext.versionName,
                    store = ext.storeName,
                    warning = stringResource(MR.strings.ext_untrusted),
                    // Same as manga: ask before trusting
                    onClick = { trustDialogFor = ext },
                    onLongClick = { onUninstall(ext.pkgName) },
                    action = {
                        IconButton(onClick = { trustDialogFor = ext }) {
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = stringResource(MR.strings.ext_trust))
                        }
                    },
                )
            }
            section(
                availableTitle,
                state.filteredAvailable,
                first = !hasUpdates && !hasInstalled && !hasUntrusted,
            ) { ext ->
                val step = state.installSteps[ext.pkgName]
                PaneRow(
                    icon = { AnimeIcon(drawable = null, url = ext.iconUrl) },
                    title = ext.name,
                    lang = ext.lang,
                    version = ext.versionName,
                    store = ext.storeName,
                    warning = stringResource(MR.strings.ext_nsfw_short).takeIf { ext.isNsfw },
                    onLongClick = { onInstall(ext) },
                    action = {
                        StepActions(
                            step = step,
                            onRetry = { onInstall(ext) },
                            onCancel = { onCancel(ext.pkgName, ext.signatureHash) },
                        ) {
                            if (ext.sources.any { it.baseUrl.isNotBlank() }) {
                                IconButton(onClick = { onOpenWebView(ext) }) {
                                    Icon(
                                        Icons.Outlined.Public,
                                        contentDescription = stringResource(MR.strings.action_open_in_web_view),
                                    )
                                }
                            }
                            IconButton(onClick = { onInstall(ext) }) {
                                Icon(Icons.Outlined.GetApp, contentDescription = stringResource(MR.strings.ext_install))
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Search bar with Repos and refresh buttons, shown above both the manga and anime extension lists.
 */
@Composable
fun ExtensionsPaneHeader(
    searchQuery: String?,
    onSearch: (String?) -> Unit,
    placeholder: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenRepos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
    ) {
        SourcesSearchBox(
            searchQuery = searchQuery,
            onChangeSearchQuery = onSearch,
            placeholderText = placeholder,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenRepos) { Text(stringResource(KMR.strings.anime_extension_repos)) }
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_webview_refresh))
            }
        }
    }
}

/** Anime side of the Migrate tab (needs the anime library, coming next) */
@Composable
fun AnimeMigratePane(contentPadding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        EmptyMessage(stringResource(KMR.strings.anime_migrate_coming))
    }
}

/** Manga-style section: divider between sections, then the same header the manga extension list uses */
private fun <T> LazyListScope.section(
    title: String,
    items: List<T>,
    first: Boolean,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "section-$title") {
        Column {
            if (!first) HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
            ExtensionHeader(text = title)
        }
    }
    items(items) { row(it) }
}

@Composable
private fun InstalledRow(
    ext: AnimeExtension,
    step: InstallStep?,
    onUpdate: (AnimeExtension) -> Unit,
    onUninstall: (String) -> Unit,
    onCancel: (String, String) -> Unit,
    onOpenDetails: (AnimeExtension) -> Unit,
) {
    PaneRow(
        icon = { AnimeIcon(drawable = ext.icon, url = null) },
        title = ext.name,
        lang = ext.lang,
        version = ext.versionName,
        store = ext.storeName,
        warning = when {
            ext.isObsolete -> stringResource(MR.strings.ext_obsolete)
            ext.isNsfw -> stringResource(MR.strings.ext_nsfw_short)
            else -> null
        },
        onClick = { onOpenDetails(ext) },
        onLongClick = { onUninstall(ext.pkgName) },
        action = {
            StepActions(
                step = step,
                onRetry = { onUpdate(ext) },
                onCancel = { onCancel(ext.pkgName, ext.signatureHash) },
            ) {
                IconButton(onClick = { onOpenDetails(ext) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(MR.strings.action_settings))
                }
                if (ext.hasUpdate) {
                    IconButton(onClick = { onUpdate(ext) }) {
                        Icon(Icons.Outlined.GetApp, contentDescription = stringResource(MR.strings.ext_update))
                    }
                }
            }
        },
    )
}

/** Same buttons as the manga extension list: cancel while installing, retry on error, otherwise [idle] */
@Composable
private fun StepActions(
    step: InstallStep?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    idle: @Composable () -> Unit,
) {
    when (step) {
        null, InstallStep.Idle, InstallStep.Installed -> idle()
        InstallStep.Error -> IconButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_retry))
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.action_cancel))
            }
        }
    }
}

/** Same layout and text style as the manga extension rows */
@Composable
private fun PaneRow(
    icon: @Composable () -> Unit,
    title: String,
    lang: String? = null,
    version: String? = null,
    store: String? = null,
    warning: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    action: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
        Column(modifier = Modifier.weight(1f).padding(start = MaterialTheme.padding.medium)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.secondaryItemAlpha(),
                )
                return@Column
            }
            FlowRow(
                modifier = Modifier.secondaryItemAlpha(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
                    var shown = false
                    if (!lang.isNullOrEmpty()) {
                        shown = true
                        Text(
                            text = FlagEmoji.getEmojiLangFlag(lang) + " " +
                                LocaleHelper.getSourceDisplayName(lang, context),
                        )
                    }
                    if (!version.isNullOrEmpty()) {
                        if (shown) DotSeparatorNoSpaceText()
                        Text(text = version)
                    }
                    Text(text = store?.let { "@$it" } ?: "(?)")
                    if (warning != null) {
                        DotSeparatorNoSpaceText()
                        Text(
                            text = warning.uppercase(),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { action() }
    }
}

/** Red stop sign, like untrusted manga extensions */
@Composable
private fun UntrustedIcon() {
    Image(
        imageVector = Icons.Filled.Dangerous,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
        modifier = Modifier.size(40.dp),
    )
}

@Composable
private fun AnimeIcon(drawable: Drawable?, url: String?) {
    val modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
    when {
        drawable != null -> {
            val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
        }
        url != null -> AsyncImage(model = url, contentDescription = null, modifier = modifier)
        else -> Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(6.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.secondaryItemAlpha())
    }
}
