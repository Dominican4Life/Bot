package eu.kanade.tachiyomi.ui.sourcelab.pagesource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.jssource.lab.LabFlowScreen
import eu.kanade.tachiyomi.jssource.lab.sitecheck.PageFetch
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * KMK (r115, mockup XSource): "Page source" for one address: **Raw** (what the app's client gets: what a source
 * receives), **Rendered** (the hidden browser's page after its scripts) and **Requests** (what the page asked for while
 * rendering; a JSON one opens with its fields). Lines are numbered and coloured; Search counts the matches and steps
 * through them. When [itemSelector] (the builder's Item, or the source's `ITEM`) matches nothing in Raw but something
 * in Rendered, a banner says so: a source reading the plain page would find nothing.
 *
 * Opened from the builder's ⋮ (and a checklist row's "Page source"), the Site check's plan, and the code editor's ⋮:
 * `PageSourceScreen(url, sourceKey)` (the source's own `ITEM` is read from its code then).
 */
internal class PageSourceScreen(
    private val url: String,
    private val sourceKey: String? = null,
    private val itemSelector: String? = null,
) : Screen(), LabFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { PageSourceScreenModel(url, sourceKey, itemSelector) }
        val state by model.state.collectAsState()
        // Remembered before any branch (Compose rule)
        var tab by rememberSaveable { mutableIntStateOf(TAB_RAW) }
        var query by rememberSaveable { mutableStateOf("") }
        var hit by remember { mutableIntStateOf(0) }
        var jsonOnly by rememberSaveable { mutableStateOf(true) }
        val rawList = rememberLazyListState()
        val renderedList = rememberLazyListState()
        val requestList = rememberLazyListState()
        val palette = palette()

        val lines = when (tab) {
            TAB_RAW -> state.rawLines.lines
            TAB_RENDERED -> state.renderedLines.lines
            else -> state.requestLines.orEmpty()
        }
        val (hits, total) = remember(lines, query) { HtmlLines.matches(lines, query) }
        val listState = when (tab) {
            TAB_RAW -> rawList
            TAB_RENDERED -> renderedList
            else -> requestList
        }
        LaunchedEffect(query, tab) { hit = 0 }
        LaunchedEffect(hit, hits) {
            val line = hits.getOrNull(hit) ?: return@LaunchedEffect
            listState.scrollToItem((line - 2).coerceAtLeast(0))
        }

        val host = state.url.removePrefix("https://").removePrefix("http://").substringBefore('/')
        Scaffold(
            topBar = {
                AppBar(
                    title = "Page source",
                    subtitle = listOfNotNull(state.sourceName, host.ifBlank { null }).joinToString(" · "),
                    navigateUp = { navigator.pop() },
                    actions = {
                        IconButton(onClick = { model.load() }) { Icon(Icons.Outlined.Refresh, contentDescription = "Read again") }
                        IconButton(onClick = { navigator.push(WebViewScreen(url = state.url, initialTitle = host, sourceId = null)) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in WebView")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = model::setAddress,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { model.load() }),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == TAB_RAW, onClick = { tab = TAB_RAW }, text = {
                        TabLabel("Raw", state.raw?.let { PageFetch.size(it.body.length.toLong()) }, state.rawLoading)
                    })
                    Tab(selected = tab == TAB_RENDERED, onClick = { tab = TAB_RENDERED }, text = {
                        TabLabel("Rendered", if (state.rendered != null) "after scripts" else null, state.renderedLoading)
                    })
                    Tab(selected = tab == TAB_REQUESTS, onClick = { tab = TAB_REQUESTS }, text = {
                        val json = state.rendered?.requests?.count { it.data }
                        TabLabel("Requests", json?.let { "$it data" }, state.renderedLoading)
                    })
                }
                if (state.onlyRendered && tab != TAB_REQUESTS) {
                    Banner(
                        "These ${state.renderedCount} items (${state.itemSelector}) are only in Rendered: the raw page has none. " +
                            "A source reading the HTML would find nothing; see Requests for the site's own data.",
                        onAction = { tab = TAB_REQUESTS },
                        action = "Requests",
                    )
                } else if (state.itemSelector != null && state.rawCount >= 0 && tab != TAB_REQUESTS) {
                    Text(
                        "Item ${state.itemSelector}: ${state.rawCount} in Raw · " +
                            (if (state.renderedCount >= 0) "${state.renderedCount} in Rendered" else "Rendered still loading"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (tab != TAB_REQUESTS || state.request != null) {
                    SearchBar(
                        query = query,
                        onQuery = { query = it },
                        count = total,
                        lines = hits.size,
                        at = if (hits.isEmpty()) 0 else hit + 1,
                        onPrev = { if (hits.isNotEmpty()) hit = (hit - 1 + hits.size) % hits.size },
                        onNext = { if (hits.isNotEmpty()) hit = (hit + 1) % hits.size },
                    )
                }
                when (tab) {
                    TAB_RAW -> SourceBody(
                        loading = state.rawLoading,
                        error = state.raw?.let { r ->
                            r.error?.let { "Not read: $it" } ?: if (r.botCheck) "The app's client got a bot check (open it in WebView to pass it)" else null
                        },
                        lines = state.rawLines,
                        head = buildList {
                            state.raw?.let { add("${it.code} · ${it.contentType.substringBefore(';').ifBlank { "no type" }} · ${it.ms} ms") }
                            addAll(state.found)
                        },
                        list = rawList,
                        palette = palette,
                        query = query,
                        current = hits.getOrNull(hit),
                    )
                    TAB_RENDERED -> SourceBody(
                        loading = state.renderedLoading,
                        error = state.rendered?.error?.let { "The hidden browser couldn't open it: $it" },
                        lines = state.renderedLines,
                        head = listOfNotNull(
                            state.rendered?.let {
                                "${it.ms / 100 / 10.0} s · ${it.requests.size} requests" + if (it.timedOut) " · still loading after 20 s" else ""
                            },
                        ),
                        list = renderedList,
                        palette = palette,
                        query = query,
                        current = hits.getOrNull(hit),
                    )
                    else -> {
                        val open = state.request
                        if (open == null) {
                            RequestList(
                                requests = state.rendered?.requests.orEmpty(),
                                loading = state.renderedLoading,
                                jsonOnly = jsonOnly,
                                onJsonOnly = { jsonOnly = it },
                                onOpen = model::openRequest,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                IconButton(onClick = model::closeRequest) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the requests")
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(open.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    state.requestNote?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                }
                            }
                            SourceBody(
                                loading = state.requestLines == null,
                                error = null,
                                lines = HtmlLines.Lines(state.requestLines.orEmpty(), false),
                                head = emptyList(),
                                list = requestList,
                                palette = palette,
                                query = query,
                                current = hits.getOrNull(hit),
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAB_RAW = 0
        const val TAB_RENDERED = 1
        const val TAB_REQUESTS = 2
    }
}

@Composable
private fun TabLabel(name: String, note: String?, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name)
        if (loading) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else if (note != null) {
            Spacer(Modifier.width(6.dp))
            Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Banner(text: String, onAction: () -> Unit, action: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, count: Int, lines: Int, at: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Search (card__img, data-src, __NEXT_DATA__ …)") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        if (query.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (count == 0) "0" else "$at / $lines · $count",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onPrev, enabled = lines > 0) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match") }
            IconButton(onClick = onNext, enabled = lines > 0) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match") }
        }
    }
}

/** Numbered, coloured lines; [current] is the line of the match Search is on */
@Composable
private fun SourceBody(
    loading: Boolean,
    error: String?,
    lines: HtmlLines.Lines,
    head: List<String>,
    list: LazyListState,
    palette: HtmlLines.Palette,
    query: String,
    current: Int?,
) {
    // Remembered first (Compose rule): one sideways scroll for every line, so the columns stay lined up
    val sideways = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    if (loading && lines.lines.isEmpty()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize().horizontalScroll(sideways),
    ) {
        if (error != null) {
            item(key = "error") {
                Text(error, color = colors.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).widthIn(max = 600.dp))
            }
        }
        head.forEachIndexed { i, line ->
            item(key = "head:$i") {
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
        if (head.isNotEmpty()) item(key = "head-div") { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp).width(2000.dp)) }
        itemsIndexed(lines.lines, key = { i, _ -> "l:$i" }) { i, line ->
            val text = remember(line, query, palette) { HtmlLines.highlight(line, palette, query) }
            Row(
                modifier = Modifier
                    .background(if (i == current) colors.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                    .padding(end = 16.dp),
            ) {
                Text(
                    (i + 1).toString(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = colors.outline,
                    modifier = Modifier.width(52.dp).padding(start = 8.dp, top = 1.dp),
                )
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false)
            }
        }
        if (lines.cut) {
            item(key = "cut") {
                Text(
                    "Only the first ${HtmlLines.MAX_LINES} lines are shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** The page's requests: data ones (fetch / XHR) first; tap one to read its reply */
@Composable
private fun RequestList(
    requests: List<PageFetch.Req>,
    loading: Boolean,
    jsonOnly: Boolean,
    onJsonOnly: (Boolean) -> Unit,
    onOpen: (PageFetch.Req) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shown = requests
        .filter { !jsonOnly || it.data || it.type == "html" || it.type == "other" }
        .sortedByDescending { if (it.data) 2 else if (it.type == "html") 1 else 0 }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            FilterChip(selected = jsonOnly, onClick = { onJsonOnly(true) }, label = { Text("Data and pages") })
            FilterChip(selected = !jsonOnly, onClick = { onJsonOnly(false) }, label = { Text("Everything (${requests.size})") })
        }
        if (loading) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
        } else if (shown.isEmpty()) {
            Text(
                "The page asked for no ${if (jsonOnly) "data" else "other addresses"} while it rendered.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            RequestRows(shown, onOpen)
        }
    }
}

@Composable
private fun RequestRows(shown: List<PageFetch.Req>, onOpen: (PageFetch.Req) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(shown, key = { i, r -> "$i:${r.url}" }) { _, req ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(req) }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (req.data) colors.primaryContainer else colors.surfaceContainerHighest,
                    modifier = Modifier.width(56.dp),
                ) {
                    Text(
                        when (req.type) {
                            "fetch", "xhr" -> "DATA"
                            "script" -> "JS"
                            else -> req.type.uppercase()
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = if (req.data) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    req.url.removePrefix("https://").removePrefix("http://"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (req.bytes > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(PageFetch.size(req.bytes), fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

/** Tag / attribute / value colours that read on the app's dark and light themes */
@Composable
private fun palette(): HtmlLines.Palette {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    return remember(dark, colors.onSurface, colors.outline) {
        if (dark) {
            HtmlLines.Palette(
                tag = Color(0xFF7CB4FF),
                attr = Color(0xFFE0A3FF),
                value = Color(0xFF8FD6A0),
                comment = colors.outline,
                text = colors.onSurface,
                hit = Color(0x66FFB300),
            )
        } else {
            HtmlLines.Palette(
                tag = Color(0xFF1558C0),
                attr = Color(0xFF8A2BB0),
                value = Color(0xFF2E7D32),
                comment = colors.outline,
                text = colors.onSurface,
                hit = Color(0x66FFB300),
            )
        }
    }
}
