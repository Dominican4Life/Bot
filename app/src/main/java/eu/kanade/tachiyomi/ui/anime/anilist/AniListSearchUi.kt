package eu.kanade.tachiyomi.ui.anime.anilist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.swipeBetweenTabs
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.time.LocalDate

/** KMK: Included genres and tags are blue, excluded ones red */
val AniListIncludeColor = Color(0xFF3D8BFD)
val AniListExcludeColor = Color(0xFFE5484D)

/** KMK: The five lists of the globe tab */
enum class AniListFilterTab(val icon: ImageVector, val label: StringResource) {
    GENRES(Icons.Outlined.Tag, KMR.strings.anilist_genres),
    SEASON(Icons.Outlined.CalendarMonth, KMR.strings.anilist_season),
    FORMAT(Icons.Outlined.Tv, KMR.strings.anilist_format),
    STATUS(Icons.Outlined.DesktopWindows, KMR.strings.anilist_status),
    SORT(Icons.AutoMirrored.Outlined.Sort, KMR.strings.anilist_sort),
}

/**
 * KMK: A button of the globe tab's row, drawn like the Browse buttons: a square icon until it's picked, then its
 * icon and name. [onLongClick] is for holding it (the # button fetches the genres and tags again).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AniListTabChip(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier
            .height(32.dp)
            .clip(FilterChipDefaults.shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            )
            .animateContentSize(),
        shape = FilterChipDefaults.shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (selected) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** KMK: Genres, Season, Format, Status and Sort: the picked one shows its name and opens its list */
@Composable
fun AniListFilterTabRow(
    open: AniListFilterTab?,
    onOpen: (AniListFilterTab?) -> Unit,
    onRefreshGenres: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AniListFilterTab.entries.forEach { tab ->
            AniListTabChip(
                selected = open == tab,
                label = stringResource(tab.label),
                icon = tab.icon,
                onClick = { onOpen(if (open == tab) null else tab) },
                onLongClick = if (tab == AniListFilterTab.GENRES) onRefreshGenres else null,
            )
        }
    }
}

/**
 * KMK: Under the buttons: everything picked, as chips (tapping one takes it away), and the small / large card
 * buttons at the end.
 */
@Composable
fun AniListChipBar(
    model: AniListSearchModel,
    modifier: Modifier = Modifier,
) {
    val filters = model.filters
    val largeCards by model.largeCardsPref.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sortLabel = stringResource(filters.sort.label)
        val seasonLabel = AniListSearchModel.SEASONS.firstOrNull { it.first == filters.season }
            ?.let { stringResource(it.second) }
        val screenshotLabel = stringResource(KMR.strings.anilist_screenshot_chip)
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (model.traceMatches != null) {
                item(key = "screenshot") {
                    AniListFilterTag(text = screenshotLabel, color = AniListIncludeColor) { model.clearScreenshot() }
                }
            }
            items(filters.genresIncluded, key = { "g+$it" }) { name ->
                AniListFilterTag(name, AniListIncludeColor) { model.updateFilters { it.toggleGenre(name, false, false) } }
            }
            items(filters.genresExcluded, key = { "g-$it" }) { name ->
                AniListFilterTag(name, AniListExcludeColor) { model.updateFilters { it.toggleGenre(name, false, true) } }
            }
            items(filters.tagsIncluded, key = { "t+$it" }) { name ->
                AniListFilterTag(name, AniListIncludeColor) { model.updateFilters { it.toggleGenre(name, true, false) } }
            }
            items(filters.tagsExcluded, key = { "t-$it" }) { name ->
                AniListFilterTag(name, AniListExcludeColor) { model.updateFilters { it.toggleGenre(name, true, true) } }
            }
            if (seasonLabel != null) {
                item(key = "season") {
                    AniListFilterTag(seasonLabel, AniListIncludeColor) { model.updateFilters { it.copy(season = null) } }
                }
            }
            filters.year?.let { year ->
                item(key = "year") {
                    AniListFilterTag(year.toString(), AniListIncludeColor) {
                        model.updateFilters { it.copy(year = null) }
                    }
                }
            }
            items(filters.formats, key = { "f$it" }) { format ->
                AniListFilterTag(AniListLabels.format(format), AniListIncludeColor) {
                    model.updateFilters { it.copy(formats = it.formats - format) }
                }
            }
            items(filters.statuses, key = { "s$it" }) { status ->
                AniListFilterTag(AniListLabels.status(status), AniListIncludeColor) {
                    model.updateFilters { it.copy(statuses = it.statuses - status) }
                }
            }
            if (filters.sort != AniListSort.BEST_MATCH) {
                item(key = "sort") {
                    AniListFilterTag(
                        text = sortLabel + if (filters.ascending) " ↑" else " ↓",
                        color = AniListIncludeColor,
                    ) { model.updateFilters { it.copy(sort = AniListSort.BEST_MATCH, ascending = false) } }
                }
            }
        }
        CardSizeButton(
            selected = !largeCards,
            icon = Icons.Outlined.GridView,
            label = stringResource(KMR.strings.anilist_small_cards),
            onClick = { model.largeCardsPref.set(false) },
        )
        CardSizeButton(
            selected = largeCards,
            icon = Icons.Outlined.ViewAgenda,
            label = stringResource(KMR.strings.anilist_large_cards),
            onClick = { model.largeCardsPref.set(true) },
        )
    }
}

@Composable
private fun CardSizeButton(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One picked filter in the chip bar; tapping it takes it away */
@Composable
private fun AniListFilterTag(text: String, color: Color, onRemove: () -> Unit) {
    Surface(
        onClick = onRemove,
        shape = FilterChipDefaults.shape,
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.height(28.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(imageVector = Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * KMK: The open list of the globe tab, over the results. Swiping left or right moves to the next list; tapping
 * outside closes it.
 */
@Composable
fun AniListFilterPanel(
    open: AniListFilterTab?,
    model: AniListSearchModel,
    onOpen: (AniListFilterTab?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = open != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onOpen(null) },
                    ),
            )
        }
        AnimatedVisibility(
            visible = open != null,
            enter = fadeIn() + slideInVertically { -it / 8 },
            exit = fadeOut() + slideOutVertically { -it / 8 },
        ) {
            val tab = open ?: AniListFilterTab.GENRES
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .swipeBetweenTabs(
                        count = AniListFilterTab.entries.size,
                        selected = tab.ordinal,
                        onSelect = { onOpen(AniListFilterTab.entries[it]) },
                    ),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 6.dp,
            ) {
                when (tab) {
                    AniListFilterTab.GENRES -> GenresList(model)
                    AniListFilterTab.SEASON -> SeasonList(model)
                    AniListFilterTab.FORMAT -> ChoiceList(
                        values = model.formats,
                        picked = model.filters.formats,
                        label = AniListLabels::format,
                        onToggle = { value ->
                            model.updateFilters {
                                it.copy(formats = if (value in it.formats) it.formats - value else it.formats + value)
                            }
                        },
                    )
                    AniListFilterTab.STATUS -> ChoiceList(
                        values = AniListSearchModel.STATUSES,
                        picked = model.filters.statuses,
                        label = AniListLabels::status,
                        onToggle = { value ->
                            model.updateFilters {
                                it.copy(
                                    statuses = if (value in it.statuses) it.statuses - value else it.statuses + value,
                                )
                            }
                        },
                    )
                    AniListFilterTab.SORT -> SortList(model)
                }
            }
        }
    }
}

private enum class PickState { NONE, INCLUDED, EXCLUDED }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickRow(
    text: String,
    state: PickState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
) {
    val haptic = LocalHapticFeedback.current
    val color = when (state) {
        PickState.INCLUDED -> AniListIncludeColor
        PickState.EXCLUDED -> AniListExcludeColor
        PickState.NONE -> LocalContentColor.current
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(if (state == PickState.NONE) Color.Transparent else color.copy(alpha = 0.12f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = leadingIcon ?: when (state) {
            PickState.INCLUDED -> Icons.Outlined.Check
            PickState.EXCLUDED -> Icons.Outlined.Remove
            PickState.NONE -> null
        }
        Box(modifier = Modifier.width(28.dp)) {
            if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (state == PickState.NONE) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Genres then tags; picked ones move to the top of their list. Tap: blue (included). Hold: red (excluded). */
@Composable
private fun GenresList(model: AniListSearchModel) {
    var filterText by rememberSaveable { mutableStateOf("") }
    val filters = model.filters
    val matches: (String) -> Boolean = { filterText.isBlank() || it.contains(filterText.trim(), ignoreCase = true) }
    val genres = remember(model.genres, filters, filterText) {
        val picked = filters.genresIncluded + filters.genresExcluded
        picked + model.genres.filter { it !in picked && matches(it) }
    }
    val tags = remember(model.tags, filters, filterText) {
        val picked = filters.tagsIncluded + filters.tagsExcluded
        val byName = model.tags.associateBy { it.name }
        picked.map { byName[it] ?: AniListTag(it, "", false) } +
            model.tags.filter { it.name !in picked && matches(it.name) }
    }
    Column {
        // A small search box: there are hundreds of tags
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (filterText.isEmpty()) {
                        Text(
                            text = stringResource(KMR.strings.anilist_filter_list_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = filterText,
                        onValueChange = { filterText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (filterText.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { filterText = "" },
                    )
                }
            }
        }
        val genresTitle = stringResource(KMR.strings.anilist_genres)
        val tagsTitle = stringResource(KMR.strings.anilist_tags)
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            item(key = "genres-header") { ListHeader(genresTitle) }
            items(genres, key = { "genre-$it" }) { genre ->
                PickRow(
                    text = genre,
                    state = when (genre) {
                        in filters.genresIncluded -> PickState.INCLUDED
                        in filters.genresExcluded -> PickState.EXCLUDED
                        else -> PickState.NONE
                    },
                    onClick = { model.updateFilters { it.toggleGenre(genre, isTag = false, exclude = false) } },
                    onLongClick = { model.updateFilters { it.toggleGenre(genre, isTag = false, exclude = true) } },
                )
            }
            if (tags.isNotEmpty()) {
                item(key = "tags-header") { ListHeader(tagsTitle) }
                items(tags, key = { "tag-${it.name}" }) { tag ->
                    PickRow(
                        text = tag.name,
                        subtitle = tag.category.takeIf { it.isNotBlank() },
                        state = when (tag.name) {
                            in filters.tagsIncluded -> PickState.INCLUDED
                            in filters.tagsExcluded -> PickState.EXCLUDED
                            else -> PickState.NONE
                        },
                        onClick = { model.updateFilters { it.toggleGenre(tag.name, isTag = true, exclude = false) } },
                        onLongClick = { model.updateFilters { it.toggleGenre(tag.name, isTag = true, exclude = true) } },
                    )
                }
            }
        }
    }
}

/** Season (Winter to Fall) and the year, each picked once; tapping the picked one again clears it */
@Composable
private fun SeasonList(model: AniListSearchModel) {
    val filters = model.filters
    val years = remember { (LocalDate.now().year + 1 downTo 1940).toList() }
    val seasonTitle = stringResource(KMR.strings.anilist_season)
    val yearTitle = stringResource(KMR.strings.anilist_year)
    val seasonLabels = AniListSearchModel.SEASONS.map { it.first to stringResource(it.second) }
    LazyColumn {
        // Manga have no seasons, only the year they started
        if (!model.manga) {
            item(key = "season-header") { ListHeader(seasonTitle) }
            items(seasonLabels, key = { "season-${it.first}" }) { (value, label) ->
                PickRow(
                    text = label,
                    state = if (filters.season == value) PickState.INCLUDED else PickState.NONE,
                    onClick = { model.updateFilters { it.copy(season = if (it.season == value) null else value) } },
                )
            }
        }
        item(key = "year-header") { ListHeader(yearTitle) }
        items(years, key = { "year-$it" }) { year ->
            PickRow(
                text = year.toString(),
                state = if (filters.year == year) PickState.INCLUDED else PickState.NONE,
                onClick = { model.updateFilters { it.copy(year = if (it.year == year) null else year) } },
            )
        }
    }
}

/** Format or status: any number picked, picked ones first */
@Composable
private fun ChoiceList(
    values: List<String>,
    picked: List<String>,
    label: (String) -> String,
    onToggle: (String) -> Unit,
) {
    val ordered = remember(values, picked) { picked + values.filter { it !in picked } }
    LazyColumn {
        items(ordered, key = { it }) { value ->
            PickRow(
                text = label(value),
                state = if (value in picked) PickState.INCLUDED else PickState.NONE,
                onClick = { onToggle(value) },
            )
        }
    }
}

/** Like the library's sort menu: the arrow on the left shows up (ascending) or down (descending) */
@Composable
private fun SortList(model: AniListSearchModel) {
    val filters = model.filters
    LazyColumn {
        items(AniListSort.entries, key = { it.name }) { sort ->
            val active = filters.sort == sort
            PickRow(
                text = stringResource(sort.label),
                state = if (active) PickState.INCLUDED else PickState.NONE,
                leadingIcon = when {
                    !active -> null
                    sort == AniListSort.BEST_MATCH -> Icons.Outlined.Check
                    filters.ascending -> Icons.Outlined.ArrowUpward
                    else -> Icons.Outlined.ArrowDownward
                },
                onClick = { model.updateFilters { it.pickSort(sort) } },
            )
        }
    }
}

/** KMK: The AniList results, as small cover cards or large Shiru-style cards */
@Composable
fun AniListResults(
    model: AniListSearchModel,
    onClick: (AniListMedia) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val largeCards by model.largeCardsPref.collectAsState()
    val results = model.results
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, results.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (results.isNotEmpty() && last >= results.size - 8) model.loadMore() }
    }
    LaunchedEffect(model.filters, model.query, model.traceMatches) { gridState.scrollToItem(0) }
    Box(modifier = modifier.fillMaxSize()) {
        when {
            results.isEmpty() && model.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            results.isEmpty() -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = model.error ?: stringResource(MR.strings.no_results_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.error != null) {
                    TextButton(onClick = { model.search(model.query) }) { Text(stringResource(MR.strings.action_retry)) }
                }
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = if (largeCards) GridCells.Adaptive(330.dp) else GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp + contentPadding.calculateTopPadding(),
                    bottom = 8.dp + contentPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(if (largeCards) 10.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (largeCards) 10.dp else 8.dp),
            ) {
                items(results, key = { it.id }) { media ->
                    val match = model.traceMatches?.firstOrNull { it.anilistId == media.id }
                    if (largeCards) {
                        AniListLargeCard(media, match, onClick = { onClick(media) })
                    } else {
                        AniListSmallCard(media, match, onClick = { onClick(media) })
                    }
                }
                if (model.loading) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Star, contentDescription = null, tint = Color(0xFFFACC15), modifier = Modifier.size(11.dp))
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = "$score%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TraceBadge(match: TraceMoeMatch, modifier: Modifier = Modifier) {
    Text(
        text = buildString {
            match.episode?.let { append("Ep ").append(it).append(" · ") }
            append((match.similarity * 100).toInt()).append('%')
        },
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(AniListIncludeColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
internal fun AniListSmallCard(media: AniListMedia, match: TraceMoeMatch?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = media.cover,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            media.averageScore?.let { ScoreBadge(it, Modifier.align(Alignment.TopEnd).padding(4.dp)) }
            match?.let { TraceBadge(it, Modifier.align(Alignment.BottomStart).padding(4.dp)) }
        }
        Text(
            text = media.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }
}

/** Like Shiru's large card: the banner behind, the cover on the left, then the details */
@Composable
private fun AniListLargeCard(media: AniListMedia, match: TraceMoeMatch?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp),
    ) {
        Box {
            AsyncImage(
                model = media.banner ?: media.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSizeCompat()
                    .alpha(0.18f),
            )
            Row {
                Box(modifier = Modifier.width(120.dp).fillMaxHeight()) {
                    AsyncImage(
                        model = media.cover,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    match?.let { TraceBadge(it, Modifier.align(Alignment.BottomStart).padding(4.dp)) }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val meta = listOfNotNull(
                            AniListLabels.format(media.format).takeIf { it.isNotEmpty() },
                            media.episodes?.let { "$it eps" },
                            listOfNotNull(AniListLabels.season(media.season).takeIf { it.isNotEmpty() }, media.seasonYear)
                                .joinToString(" ")
                                .takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        media.averageScore?.let {
                            Spacer(modifier = Modifier.width(6.dp))
                            ScoreBadge(it)
                        }
                    }
                    if (media.genres.isNotEmpty()) {
                        Text(
                            text = media.genres.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = media.description.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** fillMaxSize inside the card's Box, so the banner takes the whole card */
private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxSize()

/**
 * KMK: Shiru's top-right toast for the genre refresh and the screenshot search: a title, a line under it and a bar
 * along the bottom. The bar runs while it's working, then counts down until the toast goes away. A newer toast
 * replaces it. Put it over the whole screen (it keeps clear of the status bar).
 */
@Composable
fun AniListNoticeHost(
    notice: AniListNotice?,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<AniListNotice?>(null) }
    val countdown = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(notice) {
        if (notice != null) {
            shown = notice
            countdown.snapTo(1f)
            if (notice.loading) {
                // Stays until the work is done (a newer toast), with a safety limit
                delay(60_000)
            } else {
                countdown.animateTo(
                    0f,
                    androidx.compose.animation.core.tween(NOTICE_MILLIS, easing = androidx.compose.animation.core.LinearEasing),
                )
            }
            onDismiss(notice.id)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopEnd,
    ) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            val current = shown ?: return@AnimatedVisibility
            Surface(
                modifier = Modifier
                    .padding(12.dp)
                    .width(320.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp,
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when {
                            current.loading -> CircularProgressIndicator(
                                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            current.isError -> Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = AniListExcludeColor,
                                modifier = Modifier.size(20.dp),
                            )
                            else -> Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            current.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val barColor = when {
                        current.isError -> AniListExcludeColor
                        current.loading -> MaterialTheme.colorScheme.primary
                        else -> Color(0xFF22C55E)
                    }
                    if (current.loading) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = barColor,
                            trackColor = Color.Transparent,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(countdown.value.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(barColor),
                        )
                    }
                }
            }
        }
    }
}

private const val NOTICE_MILLIS = 4000

/** A thin divider used between the globe tab's rows */
@Composable
fun AniListRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
