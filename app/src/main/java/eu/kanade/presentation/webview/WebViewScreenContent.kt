package eu.kanade.presentation.webview

import eu.kanade.presentation.components.GlassIcons
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.webview.PhoneLayoutHosts
import eu.kanade.tachiyomi.ui.webview.WebViewPageScripts
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class WebViewWindow(webContent: WebContent, val navigator: WebViewNavigator) {
    var state by mutableStateOf(WebViewState(webContent))
    var popupMessage: Message? = null
        private set
    var webView: WebView? = null

    constructor(popupMessage: Message, navigator: WebViewNavigator) : this(WebContent.NavigatorOnly, navigator) {
        this.popupMessage = popupMessage
    }
}

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    onShare: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    headers: Map<String, String> = emptyMap(),
    onUrlChange: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    val windowStack = remember {
        mutableStateStackOf(
            WebViewWindow(
                WebContent.Url(url = url, additionalHttpHeaders = headers),
                WebViewNavigator(coroutineScope),
            ),
        )
    }

    val currentWindow = windowStack.lastItemOrNull!!
    val navigator = currentWindow.navigator

    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(url) }
    // KMK (r120): the last pop-up to another site that was blocked ("Allow once" opens it as a tab)
    var blockedPopup by remember { mutableStateOf<String?>(null) }
    var showCloudflareHelp by remember { mutableStateOf(false) }

    // KMK (r109): the site's phone layout, remembered per host (the ⋮ menu's "Phone layout")
    var phoneLayout by remember { mutableStateOf(PhoneLayoutHosts.isOn(url)) }

    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                    // KMK (r109)
                    phoneLayout = PhoneLayoutHosts.isOn(it)
                }
                // KMK (r109): as early as the page allows, so it's laid out phone-wide from the start
                if (PhoneLayoutHosts.isOn(url)) view.evaluateJavascript(WebViewPageScripts.PHONE_LAYOUT, null)
            }

            // KMK (r109) -->
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (PhoneLayoutHosts.isOn(url)) view?.evaluateJavascript(WebViewPageScripts.PHONE_LAYOUT, null)
            }
            // KMK (r109) <--

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // KMK (r109): hover menus open on a tap; the phone layout once more in case the page replaced it
                view.evaluateJavascript(WebViewPageScripts.HOVER_MENUS, null)
                if (PhoneLayoutHosts.isOn(url)) view.evaluateJavascript(WebViewPageScripts.PHONE_LAYOUT, null)
                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                    // KMK (r109)
                    phoneLayout = PhoneLayoutHosts.isOn(it)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Ignore intents urls
                if (url.startsWith("intent://")) return true

                // Only open valid web urls
                if (url.startsWith("http") || url.startsWith("https")) {
                    if (url != view?.url) {
                        view?.loadUrl(url, headers)
                        return true
                    }
                }

                return false
            }
        }
    }

    val webChromeClient = remember {
        object : AccompanistWebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // if it wasn't initiated by a user gesture, we should ignore it like a normal browser would
                if (!isUserGesture) return false
                // KMK (r120): a new window is first caught by a hidden probe to learn where it goes: the same site
                // opens as a tab like before, another site (an ad pop-up) is blocked with "Pop-up blocked · Allow once"
                val openerHost = siteOf(view.url ?: currentUrl)
                val probe = WebView(view.context)
                var decided = false
                fun decide(target: String?) {
                    if (decided || target.isNullOrBlank() || target == "about:blank") return
                    decided = true
                    probe.post { runCatching { probe.stopLoading(); probe.destroy() } }
                    if (openerHost != null && siteOf(target) == openerHost) {
                        windowStack.push(WebViewWindow(WebContent.Url(url = target), WebViewNavigator(coroutineScope)))
                    } else {
                        blockedPopup = target
                        eu.kanade.tachiyomi.util.system.AppLog.add(
                            'I',
                            "webview",
                            "Pop-up to another site blocked: ${siteOf(target) ?: target.take(60)}",
                        )
                    }
                }
                probe.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean {
                        decide(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        decide(url)
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = probe
                resultMsg.sendToTarget()
                // a window that never goes anywhere (about:blank written by script) is dropped after 10 s
                probe.postDelayed({
                    if (!decided) {
                        decided = true
                        runCatching { probe.destroy() }
                    }
                }, 10_000L)
                return true
            }
        }
    }

    fun initializePopup(webView: WebView, message: Message): WebView {
        val transport = message.obj as WebView.WebViewTransport
        transport.webView = webView
        message.sendToTarget()
        return webView
    }

    val popState = remember<() -> Unit> {
        {
            if (windowStack.size == 1) {
                onNavigateUp()
            } else {
                windowStack.pop()
            }
        }
    }

    BackHandler(windowStack.size > 1, popState)

    Scaffold(
        topBar = {
            Box {
                Column {
                    AppBar(
                        title = currentWindow.state.pageTitle ?: initialTitle,
                        subtitle = currentUrl,
                        navigateUp = onNavigateUp,
                        navigationIcon = Icons.Outlined.Close,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_back),
                                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                        onClick = {
                                            if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
                                        },
                                        enabled = navigator.canGoBack,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_forward),
                                        icon = GlassIcons.Arrow,
                                        onClick = {
                                            if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
                                        },
                                        enabled = navigator.canGoForward,
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_webview_refresh),
                                        onClick = { navigator.reload() },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_share),
                                        onClick = { onShare(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.pref_clear_cookies),
                                        onClick = { onClearCookies(currentUrl) },
                                    ),
                                    // KMK (r109): the site's phone layout for this host, then the page again
                                    AppBar.OverflowAction(
                                        title = "Phone layout",
                                        checked = phoneLayout,
                                        onClick = {
                                            val on = !phoneLayout
                                            if (PhoneLayoutHosts.set(currentUrl, on)) {
                                                phoneLayout = on
                                                navigator.reload()
                                            }
                                        },
                                    ),
                                ).builder().apply {
                                    if (windowStack.size > 1) {
                                        add(
                                            0,
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_webview_close_tab),
                                                icon = ImageVector.vectorResource(R.drawable.ic_tab_close_24px),
                                                onClick = popState,
                                            ),
                                        )
                                    }
                                }.build(),
                            )
                        },
                    )

                    // KMK (r120): "Pop-up blocked · Allow once"
                    blockedPopup?.let { target ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Text(
                                    text = "Pop-up blocked · ${siteOf(target) ?: target.take(40)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        blockedPopup = null
                                        windowStack.push(
                                            WebViewWindow(WebContent.Url(url = target), WebViewNavigator(coroutineScope)),
                                        )
                                    },
                                ) { androidx.compose.material3.Text("Allow once") }
                                androidx.compose.material3.TextButton(onClick = { blockedPopup = null }) {
                                    androidx.compose.material3.Text("✕")
                                }
                            }
                        }
                    }

                    if (showCloudflareHelp) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                        ) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri(
                                            "https://komikku-app.github.io/docs/guides/troubleshooting/#cloudflare",
                                        )
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = currentWindow.state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        // We need to key the WebView composable to the window object since simply updating the WebView composable will
        // not cause it to re-invoke the WebView factory and render the new current window's WebView. This lets us
        // completely reset the WebView composable when the current window switches.
        key(currentWindow) {
            WebView(
                state = currentWindow.state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                navigator = navigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()

                    // Debug mode (chrome://inspect/#devices)
                    if (isDebugBuildType &&
                        0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                    ) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    headers["user-agent"]?.let {
                        webView.settings.userAgentString = it
                    }
                },
                onDispose = { webView ->
                    val window = windowStack.items.find { it.webView == webView }
                    if (window == null) {
                        // If we couldn't find any window on the stack that owns this WebView, it means that we can
                        // safely dispose of it because the window containing it has been closed.
                        webView.destroy()
                    } else {
                        // The composable is being disposed but the WebView object is not.
                        // When the WebView element is recomposed, we will want the WebView to resume from its state
                        // before it was unmounted, we won't want it to reset back to its original target.
                        window.state.content = WebContent.NavigatorOnly
                    }
                },
                client = webClient,
                chromeClient = webChromeClient,
                factory = { context ->
                    currentWindow.webView
                        ?: WebView(context).also { webView ->
                            currentWindow.webView = webView
                            currentWindow.popupMessage?.let {
                                initializePopup(webView, it)
                            }
                        }
                },
            )
        }
    }
}

/** KMK (r120): the site part of an address ("example.tv" for "https://www.cdn.example.tv/x"), null when unreadable */
private fun siteOf(url: String): String? {
    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
        ?: return null
    val parts = host.split('.')
    if (parts.size <= 2) return host
    // two-part endings like co.uk / com.br keep three labels
    val twoPart = parts[parts.size - 2].length <= 3 && parts.last().length == 2
    return parts.takeLast(if (twoPart) 3 else 2).joinToString(".")
}
