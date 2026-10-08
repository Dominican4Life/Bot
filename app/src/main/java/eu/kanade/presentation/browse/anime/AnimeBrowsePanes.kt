package eu.kanade.presentation.browse.anime

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.anime.AnimeBrowseScreenModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

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
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        SourcesSearchBox(
            searchQuery = state.sourceQuery,
            onChangeSearchQuery = onSearch,
            placeholderText = stringResource(KMR.strings.browse_search_anime_sources),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val sources = state.filteredSources
        if (sources.isEmpty()) {
            EmptyMessage(stringResource(KMR.strings.anime_no_sources))
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            sources.groupBy { it.lang }.forEach { (lang, langSources) ->
                item(key = "header-$lang") {
                    SectionHeader(LocaleHelper.getSourceDisplayName(lang, context))
                }
                items(langSources, key = { "anime-source-${it.id}" }) { source ->
                    PaneRow(
                        icon = { AnimeIcon(drawable = null, url = null) },
                        title = source.name,
                        subtitle = LocaleHelper.getSourceDisplayName(source.lang, context),
                        onClick = { context.toast(KMR.strings.anime_source_browse_coming) },
                    )
                }
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
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            SourcesSearchBox(
                searchQuery = state.extensionQuery,
                onChangeSearchQuery = onSearch,
                placeholderText = stringResource(KMR.strings.browse_search_anime_extensions),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenRepos) { Text(stringResource(KMR.strings.anime_extension_repos)) }
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_webview_refresh))
                }
            }
        }

        val nothing = state.updates.isEmpty() && state.installed.isEmpty() &&
            state.untrusted.isEmpty() && state.available.isEmpty()
        if (nothing && !state.isRefreshing) {
            EmptyMessage(stringResource(KMR.strings.anime_no_extensions))
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            section(stringResource(MR.strings.ext_updates_pending), state.filteredUpdates) { ext ->
                InstalledRow(ext, state.installSteps[ext.pkgName], onUpdate, onUninstall, onCancel)
            }
            section(stringResource(MR.strings.ext_installed), state.filteredInstalled) { ext ->
                InstalledRow(ext, state.installSteps[ext.pkgName], onUpdate, onUninstall, onCancel)
            }
            section(stringResource(MR.strings.ext_untrusted), state.filteredUntrusted) { ext ->
                PaneRow(
                    icon = { AnimeIcon(drawable = null, url = null) },
                    title = ext.name,
                    subtitle = ext.versionName,
                    action = {
                        TextButton(onClick = { onTrust(ext) }) { Text(stringResource(MR.strings.ext_trust)) }
                        TextButton(onClick = { onUninstall(ext.pkgName) }) {
                            Text(stringResource(MR.strings.ext_uninstall))
                        }
                    },
                )
            }
            section(stringResource(KMR.strings.anime_available), state.filteredAvailable) { ext ->
                val step = state.installSteps[ext.pkgName]
                PaneRow(
                    icon = { AnimeIcon(drawable = null, url = ext.iconUrl) },
                    title = ext.name,
                    subtitle = listOfNotNull(
                        LocaleHelper.getSourceDisplayName(ext.lang, context),
                        ext.versionName,
                        stringResource(MR.strings.ext_nsfw_short).takeIf { ext.isNsfw },
                        ext.storeName,
                    ).joinToString(" • "),
                    action = {
                        InstallAction(
                            step = step,
                            label = stringResource(MR.strings.ext_install),
                            onClick = { onInstall(ext) },
                            onCancel = { onCancel(ext.pkgName, ext.signatureHash) },
                        )
                    },
                )
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

private fun <T> LazyListScope.section(title: String, items: List<T>, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    item(key = "section-$title") { SectionHeader(title) }
    items(items) { row(it) }
}

@Composable
private fun InstalledRow(
    ext: AnimeExtension,
    step: InstallStep?,
    onUpdate: (AnimeExtension) -> Unit,
    onUninstall: (String) -> Unit,
    onCancel: (String, String) -> Unit,
) {
    val context = LocalContext.current
    PaneRow(
        icon = { AnimeIcon(drawable = ext.icon, url = null) },
        title = ext.name,
        subtitle = listOfNotNull(
            LocaleHelper.getSourceDisplayName(ext.lang, context),
            ext.versionName,
            stringResource(MR.strings.ext_nsfw_short).takeIf { ext.isNsfw },
            stringResource(MR.strings.ext_obsolete).takeIf { ext.isObsolete },
            ext.storeName,
        ).joinToString(" • "),
        action = {
            if (ext.hasUpdate) {
                InstallAction(
                    step = step,
                    label = stringResource(MR.strings.ext_update),
                    onClick = { onUpdate(ext) },
                    onCancel = { onCancel(ext.pkgName, ext.signatureHash) },
                )
            }
            if (step == null) {
                TextButton(onClick = { onUninstall(ext.pkgName) }) { Text(stringResource(MR.strings.ext_uninstall)) }
            }
        },
    )
}

@Composable
private fun InstallAction(step: InstallStep?, label: String, onClick: () -> Unit, onCancel: () -> Unit) {
    when (step) {
        null, InstallStep.Idle, InstallStep.Error, InstallStep.Installed -> {
            TextButton(onClick = onClick) { Text(label) }
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        when (step) {
                            InstallStep.Downloading -> MR.strings.ext_downloading
                            InstallStep.Installing -> MR.strings.ext_installing
                            else -> MR.strings.ext_pending
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = onCancel) { Text(stringResource(MR.strings.action_cancel)) }
            }
        }
    }
}

@Composable
private fun PaneRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        }
        action()
    }
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
