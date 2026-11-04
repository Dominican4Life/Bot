package eu.kanade.tachiyomi.ui.sourcelab.tapfix

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.sourcelab.StripColor
import eu.kanade.tachiyomi.ui.sourcelab.tint
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/**
 * KMK (r118): "Tap to fix" for any Source Lab source (the builder edits builder sources; this changes only the picked
 * selector text in a hand-written or template source). The places in the code that pick something from a page
 * ([TapFixScanner]) are listed; one opens the site in Tap mode: a tap highlights the element and the page works out a
 * selector for it ([TapFixPageScript]); "Use this" writes it into that literal and saves (a new History version).
 *
 * Phones (< 600 dp): the list fills the screen; a place opens its panel over the page. Tablets: the list on the left,
 * the panel and the page on the right. Bot checks are never touched: the screen says so and offers WebView.
 *
 * [onOpenCode]: "Edit in code" for a selector built from pieces (the Lab passes its "go to line"); null = only the
 * line is shown. Not kept when the screen is restored after the process was killed (a lambda can't be saved).
 */
class TapFixScreen(
    private val sourceKey: String,
    @Transient private val onOpenCode: ((line: Int) -> Unit)? = null,
) : Screen() {

    /** The page's console fallback carries this ("TAPFIX:<token>:<json>"), so the site's own console can't fake a pick */
    private val token: String = UUID.randomUUID().toString().replace("-", "")

    /** What the page calls; runs on a WebView thread. Kept from R8 by the `@JavascriptInterface` rule in proguard-rules.pro */
    private class Bridge(private val model: TapFixScreenModel) {
        @JavascriptInterface
        fun report(json: String) {
            model.onReport(json)
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { TapFixScreenModel(sourceKey) }
        val state by model.state.collectAsState()
        val holder = remember { arrayOfNulls<WebView>(1) }
        val loaded = remember { booleanArrayOf(false) }
        var created by remember { mutableIntStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        val openCode = onOpenCode

        // Scripts for the page, on the main thread
        LaunchedEffect(model) {
            model.pageScripts.collect { script -> holder[0]?.evaluateJavascript(script, null) }
        }
        LaunchedEffect(state.message) {
            val text = state.message ?: return@LaunchedEffect
            snackbar.showSnackbar(text)
            model.consumeMessage(text)
        }
        // The site, once both the WebView and its address are there (the address is read on IO)
        LaunchedEffect(state.startUrl, created) {
            val url = state.startUrl ?: return@LaunchedEffect
            val web = holder[0] ?: return@LaunchedEffect
            // a recreated WebView (rotation) already went back to the page it showed
            if (!loaded[0] && model.state.value.pageUrl == null) {
                loaded[0] = true
                web.loadUrl(url)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                holder[0]?.let { web ->
                    runCatching {
                        web.stopLoading()
                        web.removeJavascriptInterface(TapFixPageScript.BRIDGE)
                        web.destroy()
                    }
                }
                holder[0] = null
            }
        }
        BackHandler(enabled = !wide && state.selectedId != null) { model.select(null) }

        val onEditInCode: ((TapFixScanner.Place) -> Unit)? = if (openCode == null) {
            null
        } else {
            { place ->
                AppLog.add('D', "lab", "Tap to fix: ${state.name}: edit line ${place.line} in the code")
                openCode(place.line)
                navigator.pop()
            }
        }
        val openWebView: () -> Unit = {
            val url = state.pageUrl ?: state.startUrl
            if (url != null) navigator.push(WebViewScreen(url = url, initialTitle = state.name, sourceId = model.sourceId))
        }
        val reload: () -> Unit = { holder[0]?.reload() }
        val pageBack: () -> Unit = {
            val web = holder[0]
            if (web != null && web.canGoBack()) web.goBack()
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Tap to fix · ${state.name.ifBlank { "source" }}",
                    subtitle = state.pageUrl?.let { hostOf(it) },
                    navigateUp = { if (!wide && state.selectedId != null) model.select(null) else navigator.pop() },
                    actions = {
                        IconButton(onClick = reload) { Icon(Icons.Outlined.Refresh, contentDescription = "Reload the page") }
                        IconButton(onClick = openWebView) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in WebView")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val selected = state.selected
            val showPage = selected?.fixable == true
            if (wide) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    PlaceList(
                        state = state,
                        onOpen = { model.select(if (it.id == state.selectedId) null else it.id) },
                        onEditInCode = onEditInCode,
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        if (selected != null) {
                            PlacePanel(
                                state = state,
                                place = selected,
                                model = model,
                                onEditInCode = onEditInCode,
                                onOpenWebView = openWebView,
                                onReload = reload,
                                onPageBack = pageBack,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                            )
                        } else {
                            PageHint(state, onOpenWebView = openWebView, onReload = reload)
                        }
                        WebCard(model, holder, onCreated = { created++ }, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                    if (selected == null) {
                        PlaceList(
                            state = state,
                            onOpen = { model.select(it.id) },
                            onEditInCode = onEditInCode,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        PlacePanel(
                            state = state,
                            place = selected,
                            model = model,
                            onEditInCode = onEditInCode,
                            onOpenWebView = openWebView,
                            onReload = reload,
                            onPageBack = pageBack,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 300.dp),
                        )
                    }
                    // Always composed (height 0 while the list shows), so the page isn't loaded again per place
                    WebCard(
                        model,
                        holder,
                        onCreated = { created++ },
                        modifier = if (showPage) {
                            Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)
                        } else {
                            Modifier.fillMaxWidth().height(0.dp)
                        },
                    )
                }
            }
        }
    }

    // ---------- the list ----------

    @Composable
    private fun PlaceList(
        state: TapFixScreenModel.State,
        onOpen: (TapFixScanner.Place) -> Unit,
        onEditInCode: ((TapFixScanner.Place) -> Unit)?,
        modifier: Modifier,
    ) {
        val named = remember(state.places) { state.places.filter { it.named && it.fixable } }
        val inCode = remember(state.places) { state.places.filter { !it.named && it.fixable } }
        val pieces = remember(state.places) { state.places.filter { !it.fixable } }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = modifier,
        ) {
            item {
                when {
                    state.loading -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null -> Muted(state.error, color = StripColor.Red.tint())
                    state.places.isEmpty() -> Muted(
                        "No selector found in this source's code (nothing passed to select / selectOne / querySelector / \$ …). " +
                            "Edit it in the code instead.",
                    )
                    else -> Muted("The places in the code that pick something from the page. Pick one, then tap the right thing on the site.")
                }
            }
            if (named.isNotEmpty()) {
                item { Head("Named picks · ${named.size}") }
                item {
                    RowsCard { named.forEach { PlaceRow(it, state, onOpen, onEditInCode) } }
                }
            }
            if (inCode.isNotEmpty()) {
                item { Head("In the code · ${inCode.size}") }
                item {
                    RowsCard { inCode.forEach { PlaceRow(it, state, onOpen, onEditInCode) } }
                }
            }
            if (pieces.isNotEmpty()) {
                item { Head("Built from pieces · edit in code") }
                item {
                    RowsCard { pieces.forEach { PlaceRow(it, state, onOpen, onEditInCode) } }
                }
            }
        }
    }

    @Composable
    private fun PlaceRow(
        place: TapFixScanner.Place,
        state: TapFixScreenModel.State,
        onOpen: (TapFixScanner.Place) -> Unit,
        onEditInCode: ((TapFixScanner.Place) -> Unit)?,
    ) {
        val isOpen = place.id == state.selectedId
        val dot = when {
            !place.fixable -> StripColor.Amber
            isOpen && state.old != null -> if (state.old.ok && state.old.count > 0) StripColor.Green else StripColor.Red
            isOpen -> StripColor.Blue
            place.selector.isBlank() -> StripColor.Red
            else -> StripColor.Neutral
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isOpen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable { onOpen(place) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Dot(dot)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(place.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Mono(place.selector.ifBlank { "not set" }, maxLines = 1)
                Muted(
                    "line ${place.line} · ${place.where}" + (place.relativeName?.let { " · inside each $it" } ?: ""),
                    maxLines = 1,
                )
            }
            if (!place.fixable && onEditInCode != null) {
                TextButton(onClick = { onEditInCode(place) }) { Text("Edit in code") }
            }
        }
    }

    // ---------- the open place ----------

    @Composable
    private fun PlacePanel(
        state: TapFixScreenModel.State,
        place: TapFixScanner.Place,
        model: TapFixScreenModel,
        onEditInCode: ((TapFixScanner.Place) -> Unit)?,
        onOpenWebView: () -> Unit,
        onReload: () -> Unit,
        onPageBack: () -> Unit,
        modifier: Modifier,
    ) {
        val scroll = rememberScrollState()
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(scroll).padding(12.dp),
            ) {
                Head(place.label)
                Muted("line ${place.line} · ${place.where}" + (place.relativeName?.let { " · read inside each $it" } ?: ""))
                if (!place.fixable) {
                    Mono(place.selector)
                    Muted(place.why ?: "Built from pieces: edit it in the code", color = StripColor.Amber.tint())
                    CodeLine(place.line, place.lineText)
                    if (onEditInCode != null) {
                        Button(onClick = { onEditInCode(place) }) { Text("Edit in code") }
                    } else {
                        Muted("Change it in the Source Lab's Code tab, line ${place.line}.")
                    }
                } else {
                    if (state.botCheck) BotCheckBanner(onOpenWebView, onReload)
                    ModeRow(state, model, onPageBack)
                    Compare(
                        title = "Now",
                        selector = place.selector.ifBlank { "not set" },
                        count = oldText(state.old),
                        samples = state.old?.samples.orEmpty(),
                        color = when {
                            state.old == null -> StripColor.Neutral
                            state.old.ok && state.old.count > 0 -> StripColor.Green
                            else -> StripColor.Red
                        },
                    )
                    val pick = state.pick
                    if (pick == null) {
                        Muted(
                            when {
                                state.saving -> "Saving…"
                                state.botCheck -> "Tap is off while the site shows a bot check."
                                state.browse -> "Browse to the page that shows the ${place.label.lowercase()}, then switch to Tap."
                                else -> "Tap the ${place.label.lowercase()} on the page (Browse first if it's on another page)."
                            },
                        )
                    } else {
                        Compare(
                            title = "New",
                            selector = pick.selector,
                            count = newText(pick),
                            samples = pick.samples,
                            color = if (pick.count > 0) StripColor.Green else StripColor.Red,
                        )
                        Text(
                            "Matches on this page: ${state.old?.takeIf { it.ok && !it.empty }?.count ?: 0} → ${pick.count}" +
                                if (pick.cards >= 0) " (of ${pick.cards} Items)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (pick.weak) {
                            Muted(
                                "No short selector finds the same set: this one follows the page's structure and may break when the site changes.",
                                color = StripColor.Amber.tint(),
                            )
                        }
                        pick.note?.let { Muted(it, color = StripColor.Amber.tint()) }
                        if (place.kind == TapFixScanner.Kind.Item) {
                            Muted("The other picks are read inside each Item: check them after changing it.")
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = model::useThis, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Use this") }
                            OutlinedButton(onClick = model::tapAgain, enabled = !state.saving) { Text("Tap again") }
                            TextButton(onClick = { model.select(null) }, enabled = !state.saving) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModeRow(state: TapFixScreenModel.State, model: TapFixScreenModel, onPageBack: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = !state.browse,
                onClick = { model.setBrowse(false) },
                enabled = !state.botCheck,
                label = { Text("Tap") },
                leadingIcon = { Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = state.browse,
                onClick = { model.setBrowse(true) },
                label = { Text("Browse") },
                leadingIcon = { Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onPageBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Page back")
            }
            if (state.pick != null) {
                IconButton(onClick = model::up) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Up (the parent)") }
                IconButton(onClick = model::down) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Down (a child)") }
            }
        }
    }

    @Composable
    private fun Compare(title: String, selector: String, count: String, samples: List<String>, color: StripColor) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Muted(count, maxLines = 1)
                }
                Mono(selector, maxLines = 3)
                samples.take(3).forEach { Muted("· $it", maxLines = 1) }
            }
        }
    }

    @Composable
    private fun BotCheckBanner(onOpenWebView: () -> Unit, onReload: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = StripColor.Red.tint().copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(StripColor.Red)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("The site is asking for a bot check — solve it in WebView first", style = MaterialTheme.typography.bodyMedium)
                }
                Row {
                    TextButton(onClick = onOpenWebView) { Text("Open in WebView") }
                    TextButton(onClick = onReload) { Text("Reload") }
                }
            }
        }
    }

    /** Tablet, nothing open: what to do, and the bot check line when the page shows one */
    @Composable
    private fun PageHint(state: TapFixScreenModel.State, onOpenWebView: () -> Unit, onReload: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.botCheck) BotCheckBanner(onOpenWebView, onReload)
            Muted(
                if (state.startUrl == null && !state.loading) {
                    "This source has no site address (its baseUrl), so there's no page to tap."
                } else {
                    "Pick a place on the left: the page below switches to Tap. Links work until then."
                },
            )
        }
    }

    // ---------- the page ----------

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebCard(
        model: TapFixScreenModel,
        holder: Array<WebView?>,
        onCreated: () -> Unit,
        modifier: Modifier,
    ) {
        val userAgent = remember { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }
        Surface(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = modifier,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                factory = { context ->
                    // On the main thread (AndroidView's factory). Same settings and user agent as the builder's page
                    WebView(context).apply {
                        setDefaultSettings()
                        settings.userAgentString = userAgent
                        settings.blockNetworkImage = false
                        settings.loadsImagesAutomatically = true
                        // Before any loadUrl: an interface is only seen by pages loaded after it was added
                        addJavascriptInterface(Bridge(model), TapFixPageScript.BRIDGE)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                val text = consoleMessage?.message() ?: return false
                                val prefix = "${TapFixPageScript.CONSOLE_PREFIX}$token:"
                                if (!text.startsWith(prefix)) return false
                                model.onReport(text.removePrefix(prefix))
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // Tap mode: the page never leaves (a script's own navigation too); Browse: links work
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (request?.isForMainFrame != true) return false
                                if (!model.state.value.tapActive) return false
                                AppLog.add('D', "lab", "Tap to fix: stayed on the page in Tap mode (it asked for ${request?.url})")
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view ?: return
                                model.onPageLoaded(view.url ?: url)
                                inject(view, model)
                            }

                            // In-page navigations (history.pushState): the script is put back, a no-op when it's there
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                view ?: return
                                if (model.onPageLoaded(view.url ?: url, soft = true)) inject(view, model)
                            }
                        }
                        holder[0] = this
                        onCreated()
                        // A recreated WebView (rotation) shows the page it showed
                        model.state.value.pageUrl?.let { loadUrl(it) }
                    }
                },
            )
        }
    }

    /** The page script with this screen's token, then the mode and the open place's context (one each) */
    private fun inject(view: WebView, model: TapFixScreenModel) {
        view.evaluateJavascript(TapFixPageScript.install(token), null)
        view.evaluateJavascript(model.afterInstall(), null)
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    private fun oldText(m: TapFixScreenModel.Measure?): String {
        val base = when {
            m == null -> "counting…"
            m.empty -> "not set"
            !m.ok -> m.error ?: "can't run in the browser"
            m.cards >= 0 -> "in ${m.count} of ${m.cards} Items"
            else -> "${m.count} on this page"
        }
        return base + if (m?.approx == true) " (text tests left out)" else ""
    }

    private fun newText(p: TapFixScreenModel.Pick): String =
        (if (p.cards >= 0) "in ${p.count} of ${p.cards} Items" else "${p.count} on this page") + " · ${p.tag}"

    // ---------- small parts (the Lab's look: rounded cards, small caps primary heads, status dots) ----------

    @Composable
    private fun Head(text: String) {
        Text(
            text.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }

    @Composable
    private fun Muted(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = Int.MAX_VALUE) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    private fun Mono(text: String, maxLines: Int = Int.MAX_VALUE) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    private fun CodeLine(line: Int, text: String) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "$line  ${text.trim()}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }

    @Composable
    private fun Dot(color: StripColor) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.tint()))
    }

    @Composable
    private fun RowsCard(content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(6.dp)) { content() }
        }
    }
}
