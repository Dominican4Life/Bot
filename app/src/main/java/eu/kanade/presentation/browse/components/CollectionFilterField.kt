package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.jssource.JsDirectory
import eu.kanade.tachiyomi.jssource.JsFilters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/*
 * KMK (r107): a JS source's collection filter in the filter sheet (mockups "CSheet", "CSort", "CActress", "CMakers"):
 * a filled field that opens the directory as a drop-down (list rows with the number, a small picture and the counts
 * right-aligned, or a 4-column face grid for people with pictures), a search box, the site's extra menus as chips, and
 * a square sort button whose menu opens under it. A live directory is read page by page as the list is scrolled.
 *
 * KMK (r109): a stored collection offers all the site's sorts and menus too: a sort the app can't apply to the stored
 * entries, or a menu value, reads the site page by page like a live one ([JsDirectory.readsLive]); the site's default
 * sort with no menu shows the stored entries again.
 */

/** The loaded pages of a live directory (getDirectory), kept while the sheet is shown */
@Stable
internal class LiveDirectoryState(private val directory: JsDirectory) {
    val items = mutableStateListOf<JsFilters.Entry>()
    var hasNext by mutableStateOf(true)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** KMK (r109): a stored list's live read found the site can't be read ([JsDirectory.siteReadable] went off) */
    var unreadable by mutableStateOf(false)
        private set

    /** KMK (r107) fix: counts [reset]s, a key of the effect that loads page 1 (a reset during its load starts it again) */
    var resets by mutableIntStateOf(0)
        private set

    private val seen = HashSet<String>()
    private var page = 1
    private var generation = 0
    private var job: Job? = null

    /** Starts again from page 1 (another sort or menu value) */
    fun reset() {
        job?.cancel()
        generation++
        items.clear()
        seen.clear()
        page = 1
        hasNext = true
        loading = false
        error = null
        resets++
    }

    fun loadMore(scope: CoroutineScope) {
        if (loading || !hasNext || error != null) return
        loading = true
        val gen = generation
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { directory.load(page) }
                if (gen != generation) return@launch
                val fresh = result.items.filter { seen.add(it.value) }
                items.addAll(fresh)
                page++
                // a site that repeats its last page for any page number ends here
                hasNext = result.hasNext && fresh.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) {
                    error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    // KMK (r109): the drop-down then goes back to the stored list with the local sorts only
                    if (!directory.siteReadable) unreadable = true
                }
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun retry(scope: CoroutineScope) {
        error = null
        loadMore(scope)
    }
}

/** "Any genre", "Any actress", "Any series" for an empty pick */
private fun anyLabel(name: String): String {
    val lower = name.trim().lowercase()
    val single = when {
        lower.endsWith("series") || lower.endsWith("species") -> lower
        lower.endsWith("sses") -> lower.dropLast(2)
        lower.endsWith("ies") -> lower.dropLast(3) + "y"
        lower.endsWith("s") && !lower.endsWith("ss") -> lower.dropLast(1)
        else -> lower
    }
    return if (single.isBlank()) "Any" else "Any $single"
}

/** "4,476 videos · 6.2M views · 17.2K ♥": the parts the entry has */
private fun countsOf(entry: JsFilters.Entry): String = listOfNotNull(
    entry.videos?.let { "$it videos" },
    entry.views?.let { "$it views" },
    entry.likes?.let { "$it ♥" },
).joinToString(" · ")

private fun initialsOf(label: String): String =
    label.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

/**
 * The collection's field and sort button. [value] is the picked entry's value ("" = any); [onPick] sets it ("" to
 * clear)
 */
@Composable
internal fun CollectionFilterField(
    directory: JsDirectory,
    value: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    // KMK (r107) fix: Clear in the sheet resets the directory's order and menus ([JsDirectory.resets]): these start again
    val resets = directory.resets
    var sortIndex by remember(directory, resets) { mutableIntStateOf(directory.sortIndex) }
    var ascending by remember(directory, resets) { mutableStateOf(directory.ascending) }
    val menuValues = remember(directory, resets) { mutableStateMapOf<String, String>().apply { putAll(directory.menuValues) } }
    var faces by remember(directory) { mutableStateOf(directory.people && directory.faces) }
    val live = remember(directory, resets) { LiveDirectoryState(directory) }
    // KMK (r109): the site's default isn't a sort (a stored list shows its own order then)
    val sorted = sortIndex >= 0 && directory.sortOptions.getOrNull(sortIndex) != null &&
        (!directory.isDefaultSort(sortIndex) || ascending)
    // KMK (r109): asks once whether the source has getDirectory (a stored list hides the site-only options if not)
    var siteReadable by remember(directory, resets) { mutableStateOf(directory.siteReadable) }
    LaunchedEffect(directory) {
        directory.checkSite()
        siteReadable = directory.siteReadable
    }
    // KMK (r109): a live read of a stored list found the site can't be read: back to the stored list, local sorts only
    LaunchedEffect(live, live.unreadable) {
        if (live.unreadable) {
            sortIndex = -1
            ascending = false
            menuValues.clear()
            siteReadable = false
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            val picked = value.isNotEmpty()
            FilledFilterField(
                label = directory.name,
                value = if (picked) directory.labelOf(value) ?: value else anyLabel(directory.name),
                open = open,
                set = picked,
                dimValue = !picked,
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
            )
            val panelWidth = with(density) { rowWidthPx.toDp() }.coerceIn(300.dp, 560.dp)
            val panelHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp.coerceIn(240.dp, 460.dp)
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                // A fixed size: the menu's own column scrolls, and a lazy list inside it needs a bounded height
                Box(modifier = Modifier.width(panelWidth).height(panelHeight)) {
                    CollectionPanel(
                        directory = directory,
                        value = value,
                        sortIndex = sortIndex,
                        ascending = ascending,
                        menuValues = menuValues,
                        faces = faces,
                        live = live,
                        scope = scope,
                        onFaces = {
                            faces = it
                            directory.faces = it
                        },
                        onMenu = { key, picked ->
                            menuValues[key] = picked
                            directory.menuValues[key] = picked
                            // KMK (r109) fix: read live now and the site can't reverse: the order goes back down
                            if (ascending && !directory.canFlip(sortIndex, menuValues)) {
                                ascending = false
                                directory.ascending = false
                            }
                            // KMK (r109): a stored list reads the site live while a menu value is picked
                            live.reset()
                        },
                        onPick = { picked ->
                            open = false
                            onPick(if (picked == value) "" else picked)
                        },
                    )
                }
            }
        }
        // KMK (r109): read here so the button and menu change when the site turns out not to be readable
        val sortOptions = remember(directory, resets, siteReadable) { directory.sortOptions }
        if (sortOptions.isNotEmpty()) {
            Box {
                val ring = if (sortOpen) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, FilterFieldShape)
                } else {
                    Modifier
                }
                Surface(
                    onClick = { sortOpen = true },
                    modifier = Modifier.size(FilterFieldHeight).then(ring),
                    shape = FilterFieldShape,
                    color = if (sorted || sortOpen) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    contentColor = if (sorted || sortOpen) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = "Sort ${directory.name.lowercase()}",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                FilterSortMenu(
                    expanded = sortOpen,
                    onDismiss = { sortOpen = false },
                    options = sortOptions.map { it.label },
                    selectedIndex = sortIndex,
                    ascending = ascending,
                    onPick = { index ->
                        if (index == sortIndex) {
                            // the same option again flips it, when the list can be reversed
                            if (directory.canFlip(index, menuValues)) {
                                ascending = !ascending
                                directory.ascending = ascending
                                if (directory.readsLive(index, menuValues) && directory.info.reverse) live.reset()
                            }
                        } else {
                            // KMK (r109): a stored list may switch between its own entries and a live read here
                            sortIndex = index
                            ascending = directory.startsAscending(index, menuValues)
                            directory.sortIndex = sortIndex
                            directory.ascending = ascending
                            live.reset()
                        }
                        sortOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CollectionPanel(
    directory: JsDirectory,
    value: String,
    sortIndex: Int,
    ascending: Boolean,
    menuValues: Map<String, String>,
    faces: Boolean,
    live: LiveDirectoryState,
    scope: CoroutineScope,
    onFaces: (Boolean) -> Unit,
    onMenu: (String, String) -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val chipsScroll = rememberScrollState()
    val loadedCount = live.items.size
    // KMK (r109): a stored list reads the site live while a site-only sort or a menu value is picked
    val liveNow = directory.readsLive(sortIndex, menuValues)
    // Sorting / filtering a 36,000-entry list again only when something it depends on changes
    val shown = remember(directory, sortIndex, ascending, query, loadedCount, live, liveNow) {
        val base = if (liveNow) live.items.toList() else directory.info.entries
        val ordered = directory.ordered(base, sortIndex, ascending, menuValues)
        val q = query.trim()
        if (q.isEmpty()) ordered else ordered.filter { it.label.contains(q, ignoreCase = true) }
    }
    val total = when {
        // KMK (r109): a narrowed live read of a stored list counts what it loaded
        liveNow && !directory.live && menuValues.values.any { it.isNotEmpty() } -> loadedCount
        directory.info.total > 0 -> directory.info.total
        liveNow -> loadedCount
        else -> directory.info.entries.size
    }
    val searching = query.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchBox(
            query = query,
            onQuery = { query = it },
            placeholder = "Search ${NumberFormat.getIntegerInstance().format(total)} ${directory.name.lowercase()}",
        )
        // KMK (r107) fix: the site's menus only for a live list. KMK (r109): also a stored one the site can read live
        if (directory.menus.isNotEmpty() || directory.people) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipsScroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                directory.menus.forEach { menu ->
                    MenuChip(menu = menu, value = menuValues[menu.key].orEmpty(), onPick = { onMenu(menu.key, it) })
                }
                if (directory.people) {
                    Spacer(Modifier.width(12.dp))
                    SmallChip(label = "Faces", selected = faces, onClick = { onFaces(true) })
                    SmallChip(label = "List", selected = !faces, onClick = { onFaces(false) })
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (faces) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown.size) { index ->
                        val entry = shown[index]
                        FaceCell(
                            entry = entry,
                            selected = entry.value == value,
                            headers = directory.imageHeaders,
                            onClick = { onPick(entry.value) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PanelFooter(liveNow, live, scope, searching, shown.isEmpty())
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (!searching) {
                        item {
                            FilterMenuRow(
                                label = anyLabel(directory.name),
                                selected = value.isEmpty(),
                                icon = if (value.isEmpty()) Icons.Filled.Check else null,
                                onClick = { onPick("") },
                            )
                        }
                    }
                    items(shown) { entry ->
                        EntryRow(
                            entry = entry,
                            selected = entry.value == value,
                            showNumber = true, // when the entry has one (only kept numbers are stored)
                            headers = directory.imageHeaders,
                            onClick = { onPick(entry.value) },
                        )
                    }
                    item {
                        PanelFooter(liveNow, live, scope, searching, shown.isEmpty())
                    }
                }
            }
        }
    }
}

/** The end of the list: loads the next live page when it comes into view, or says why there's nothing more */
@Composable
private fun PanelFooter(
    // KMK (r109): whether the list is read live now (a live directory, or a stored one with a site-only sort / menu)
    liveNow: Boolean,
    live: LiveDirectoryState,
    scope: CoroutineScope,
    searching: Boolean,
    empty: Boolean,
) {
    // Shown means scrolled to the end: the next page (not while searching the loaded ones: the box filters those)
    // KMK (r107) fix: + the resets: a menu / sort changed while page 1 loaded (nothing else changed) loads it again
    LaunchedEffect(live.items.size, live.hasNext, live.error, searching, live.resets, liveNow) {
        if (liveNow && !searching && live.hasNext && live.error == null) live.loadMore(scope)
    }
    val error = live.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            liveNow && error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { live.retry(scope) }) { Text("Try again") }
            }
            liveNow && live.loading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
            liveNow && live.hasNext && searching -> TextButton(onClick = { live.loadMore(scope) }) {
                Text("Searching the ${live.items.size} loaded · load more")
            }
            liveNow && live.hasNext -> FooterNote("More load as you scroll")
            empty -> FooterNote(if (searching) "Nothing matches" else "Nothing here")
            else -> Unit
        }
    }
}

@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit, trailingCaret: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(
                start = 10.dp,
                end = if (trailingCaret) 4.dp else 10.dp,
                top = 5.dp,
                bottom = 5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (trailingCaret) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One of the site's extra menus (Height, Cup, Age…) as a chip with its options under it */
@Composable
private fun MenuChip(menu: JsFilters.Menu, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = menu.options.firstOrNull { it.value == value }?.label
    Box {
        SmallChip(
            label = "${menu.name}: ${current ?: "any"}",
            selected = value.isNotEmpty(),
            onClick = { open = true },
            trailingCaret = true,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            FilterMenuRow(
                // KMK (r109): the site's own default option (value "", e.g. "Any height") names this row
                label = menu.options.firstOrNull { it.value.isEmpty() }?.label?.takeIf { it.isNotBlank() } ?: "Any",
                selected = value.isEmpty(),
                icon = if (value.isEmpty()) Icons.Filled.Check else null,
                onClick = {
                    open = false
                    onPick("")
                },
            )
            menu.options.filter { it.value.isNotEmpty() }.forEach { option ->
                FilterMenuRow(
                    label = option.label,
                    selected = option.value == value,
                    icon = if (option.value == value) Icons.Filled.Check else null,
                    onClick = {
                        open = false
                        onPick(option.value)
                    },
                )
            }
        }
    }
}

/** A picture with the source's headers (Referer…), or nothing while there is no address */
@Composable
private fun EntryPicture(url: String?, headers: Map<String, String>, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(url) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .httpHeaders(
                    NetworkHeaders.Builder().apply { headers.forEach { (name, v) -> add(name, v) } }.build(),
                )
                .build()
        }
    }
    if (request != null) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/** A list row: number (mono), small picture, name, and the counts on the right */
@Composable
private fun EntryRow(
    entry: JsFilters.Entry,
    selected: Boolean,
    showNumber: Boolean,
    headers: Map<String, String>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val number = entry.number
        if (showNumber && number != null) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (entry.picture != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = initialsOf(entry.label), style = MaterialTheme.typography.labelSmall)
                EntryPicture(url = entry.picture, headers = headers, modifier = Modifier.size(32.dp))
            }
        }
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val counts = countsOf(entry)
        if (counts.isNotEmpty()) {
            Text(
                text = counts,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A face in the grid: round picture (initials until it loads), name, counts; a tick when picked */
@Composable
private fun FaceCell(
    entry: JsFilters.Entry,
    selected: Boolean,
    headers: Map<String, String>,
    onClick: () -> Unit,
) {
    val cellShape = RoundedCornerShape(12.dp)
    val ring = if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, cellShape) else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
            )
            .then(ring)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialsOf(entry.label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EntryPicture(url = entry.picture, headers = headers, modifier = Modifier.size(56.dp))
            }
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            val firstLine = entry.videos?.let { "$it videos" }
            val secondLine = listOfNotNull(entry.views?.let { "$it views" }, entry.likes?.let { "$it ♥" })
                .joinToString(" · ")
            val stats = listOfNotNull(firstLine, secondLine.ifEmpty { null }).joinToString("\n")
            if (stats.isNotEmpty()) {
                Text(
                    text = stats,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
