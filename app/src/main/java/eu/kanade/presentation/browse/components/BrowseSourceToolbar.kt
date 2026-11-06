package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import eu.kanade.presentation.components.GlassIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaStyle
import eu.kanade.domain.ui.model.WideCoverStyle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.PanoramaIcons
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.more.settings.WideCoverPlace
import eu.kanade.presentation.more.settings.WideCoverSync
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    // KMK -->
    onToggleIncognito: () -> Unit,
    /** Incognito is on for this source: its switch in the menu shows it */
    incognito: Boolean = false,
    // KMK <--
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // KMK -->
    toggleSelectionMode: () -> Unit,
    isRunning: Boolean,
    /** Covers per row shown in the display menu slider (0 = auto), or null to hide the slider */
    columns: Int? = null,
    onColumnsChange: (Int) -> Unit = {},
    /** Title when there is no manga [source] (anime sources) */
    titleOverride: String? = null,
    /** The source whose icon shows next to the title (anime sources, which aren't a manga [source]) */
    iconSourceId: Long? = null,
    /** The line under the title: the source's language */
    subtitleOverride: String? = null,
    showBulkSelection: Boolean = true,
    showIncognito: Boolean = true,
    /** KMK (r117): "Source info only" for this source (anime sources); null hides the menu item */
    sourceInfoOnly: Boolean? = null,
    onToggleSourceInfoOnly: () -> Unit = {},
    /** KMK (r118): Edit source / Make an editable copy; null hides it */
    editAction: eu.kanade.tachiyomi.ui.sourcelab.BrowseEditAction? = null,
    // KMK <--
) {
    // Avoid capturing unstable source in actions lambda
    val title = titleOverride ?: source?.name
    val isLocalSource = source is LocalSource

    var selectingDisplayMode by remember { mutableStateOf(false) }

    // KMK (r133): floating glass (design A): see-through bar, the source name in a glass capsule, glass buttons
    val glass = remember { eu.kanade.presentation.components.TitleSheet.glass().get() }
    SearchToolbar(
        navigateUp = navigateUp,
        glassButtons = glass,
        backgroundColor = if (glass) Color.Transparent else null,
        // KMK (r134): dark frosted glass so the buttons read over busy covers
        glassBase = BrowseGlassBase,
        titleContent = {
            // KMK: the source's icon and language next to its name
            androidx.compose.foundation.layout.Box(
                modifier = if (glass) {
                    Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .liquidGlass(androidx.compose.foundation.shape.CircleShape, BrowseGlassBase)
                        .padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                } else {
                    Modifier
                },
            ) {
                SourceTitle(
                    title = title,
                    // KMK (r134): no language line, so the name sits centred in its capsule
                    subtitle = null,
                    sourceId = iconSourceId ?: source?.id,
                )
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        if (displayMode != null) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_display_mode),
                                    icon = if (displayMode == LibraryDisplayMode.List) {
                                        GlassIcons.LayoutList
                                    } else {
                                        GlassIcons.Display
                                    },
                                    onClick = { selectingDisplayMode = true },
                                ),
                            )
                        }
                        // KMK -->
                        // A bulk add that is still running keeps its spinner in the bar
                        if (showBulkSelection && isRunning) add(bulkSelectionButton(isRunning, toggleSelectionMode))
                        // The menu: Incognito, WebView, Bulk Selection. Settings is a button next to Filter.
                        if (showIncognito) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(KMR.strings.browse_menu_incognito),
                                    onClick = onToggleIncognito,
                                    // The More screen's Incognito icon: glasses when off, the hat drops on when on
                                    painter = rememberAnimatedVectorPainter(
                                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_incognito),
                                        incognito,
                                    ),
                                    checked = incognito,
                                ),
                            )
                        }
                        // KMK (r120): "AniList" under Incognito, switch on = AniList used; the magnifier is crossed out while off
                        if (sourceInfoOnly != null) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(KMR.strings.browse_menu_source_info_only),
                                    onClick = onToggleSourceInfoOnly,
                                    icon = ImageVector.vectorResource(if (sourceInfoOnly) R.drawable.ic_glass_anilist_off else R.drawable.ic_glass_anilist), // KMK (r133): the AniList mark
                                    checked = !sourceInfoOnly,
                                ),
                            )
                        }
                        // KMK (r118): Edit source (a Source Lab source) / Make an editable copy (an extension)
                        if (editAction != null) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(
                                        if (editAction.copy) {
                                            KMR.strings.browse_menu_editable_copy
                                        } else {
                                            KMR.strings.browse_menu_edit_source
                                        },
                                    ),
                                    onClick = editAction.onClick,
                                    icon = if (editAction.copy) GlassIcons.EditableCopy else GlassIcons.EditSource,
                                ),
                            )
                        }
                        // KMK <--
                        if (isLocalSource) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.label_help),
                                    onClick = onHelpClick,
                                    // KMK -->
                                    icon = GlassIcons.Help,
                                    // KMK <--
                                ),
                            )
                        } else {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_web_view),
                                    onClick = onWebViewClick,
                                    // KMK -->
                                    icon = GlassIcons.WebView,
                                    // KMK <--
                                ),
                            )
                        }
                        // KMK -->
                        if (showBulkSelection && !isRunning) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(KMR.strings.browse_menu_bulk_selection),
                                    onClick = toggleSelectionMode,
                                    icon = GlassIcons.BulkSelect,
                                ),
                            )
                        }
                        // KMK <--
                    }
                    .build(),
            )

            // KMK: Back closes the menu
            BackHandler(enabled = selectingDisplayMode) { selectingDisplayMode = false }
            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
                // KMK: not focusable, so closing it doesn't hand focus back to the screen and flash the keyboard
                properties = PopupProperties(focusable = false),
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    leadingIcon = { Icon(imageVector = GlassIcons.LayoutComfortable, contentDescription = null) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    leadingIcon = { Icon(imageVector = GlassIcons.LayoutCompact, contentDescription = null) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                // KMK -->
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_cover_only_grid)) },
                    leadingIcon = { Icon(imageVector = GlassIcons.LayoutCovers, contentDescription = null) },
                    isChecked = displayMode == LibraryDisplayMode.CoverOnlyGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CoverOnlyGrid)
                }
                // KMK <--
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    leadingIcon = { Icon(imageVector = GlassIcons.LayoutList, contentDescription = null) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
                // KMK: the Panorama styles, as a row of icons
                PanoramaStyleSection(
                    displayMode = displayMode,
                    onDisplayModeChange = onDisplayModeChange,
                )
                // KMK -->
                if (columns != null && displayMode != LibraryDisplayMode.List) {
                    HorizontalDivider()
                    Box(modifier = Modifier.width(300.dp)) {
                        // KMK (r133): the stepped glass slider
                        eu.kanade.presentation.components.GlassColumnsSlider(columns = columns, onChange = onColumnsChange)
                    }
                }
                // KMK <--
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/** KMK (r134): the dark frosted tint of every floating glass piece while browsing a source */
val BrowseGlassBase = Color(0xFF14161C)

/** KMK: A source's icon, name and language, for the top of a source's pages */
@Composable
fun SourceTitle(title: String?, subtitle: String?, sourceId: Long?) {
    val icon = remember(sourceId) { sourceId?.let(::sourceIconBitmap) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        AppBarTitle(title, subtitle = subtitle?.takeIf { it.isNotBlank() })
    }
}

/** The icon of any source: a manga or anime extension's, a built-in one's, or a Code editor source's */
fun sourceIconBitmap(sourceId: Long): ImageBitmap? = runCatching {
    (
        Injekt.get<ExtensionManager>().getAppIconForSource(sourceId)
            ?: Injekt.get<AnimeExtensionManager>().getAppIconForSource(sourceId)
        )?.toBitmap()?.asImageBitmap()
}.getOrNull()

// KMK -->
/** "Panorama comfortable grid" in its three styles (color fill, fit width, wide), as a row of icons */
@Composable
internal fun PanoramaStyleSection(
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
) {
    val stylePref = remember { Injekt.get<UiPreferences>().browsePanoramaStyle() }
    val style by stylePref.collectAsState()
    // KMK (r132): "Wide Covers (Panorama)" as a glass switch: each style with its icon and its name
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(KMR.strings.wide_covers_panorama),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(
                KMR.strings.panorama_short_fit to PanoramaStyle.FIT,
                KMR.strings.panorama_short_fill to PanoramaStyle.FILL,
                KMR.strings.panorama_short_wide to PanoramaStyle.WIDE,
            ).forEach { (label, value) ->
                val on = displayMode == LibraryDisplayMode.ComfortableGridPanorama && style == value
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
                        .background(
                            if (on) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable {
                            // KMK (r115): through WideCoverSync, so "Same everywhere" copies it to the other places
                            WideCoverSync.pick(WideCoverPlace.BROWSE, WideCoverStyle.of(value))
                            onDisplayModeChange(LibraryDisplayMode.ComfortableGridPanorama)
                        }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = PanoramaIcons.of(value),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
// KMK <--
