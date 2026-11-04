package eu.kanade.tachiyomi.ui.webview

import android.app.Activity
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.webview.WebViewScreenContent
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

class WebViewActivity : BaseActivity() {

    private val sourceManager: SourceManager by injectLazy()
    private val network: NetworkHelper by injectLazy()

    private var assistUrl: String? = null

    init {
        registerSecureActivity(this)
    }

    // KMK: the source this WebView was opened for, if any
    private var botCheckSourceId: Long? = null

    // KMK (r116): opened for you to pass a bot check (Solve it / the notification), or a source with a check
    // waiting: watches for the pass, tells the waiting requests, and (when opened to solve) closes by itself
    private var watchedUrl: String? = null
    private var closeWhenPassed = false
    private var botCheckPassed = false
    private var webViewUserAgent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }
        super.onCreate(savedInstanceState)

        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        val url = intent.extras?.getString(URL_KEY) ?: return
        assistUrl = url
        // KMK: the site's bot check is being passed now, so its notification goes away
        botCheckSourceId = intent.extras?.getLong(SOURCE_KEY)?.takeIf { it != 0L }
        eu.kanade.tachiyomi.data.notification.BotCheckNotifier.clear(url, botCheckSourceId)

        var headers = emptyMap<String, String>()
        (sourceManager.get(intent.extras!!.getLong(SOURCE_KEY)) as? HttpSource)?.let { source ->
            try {
                headers = source.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to build headers" }
            }
        }
        // KMK -->
        // Anime sources' headers too, so the page is opened as the source asks for it
        if (headers.isEmpty()) {
            botCheckSourceId?.let { id ->
                try {
                    val sourceHeaders = eu.kanade.tachiyomi.data.notification.BotCheckNotifier.connection(id)?.headers
                    if (sourceHeaders != null) {
                        headers = sourceHeaders.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to build headers" }
                }
            }
        }
        // The check's pass is only good for the browser that passed it: same user agent as the request that hit it
        intent.extras?.getString(USER_AGENT_KEY)?.takeIf { it.isNotBlank() }
            ?.let { headers = headers + ("user-agent" to it) }
        // KMK (r116): never WebView's own default: the app's user agent, so the pass carries over to its requests
        if (headers["user-agent"].isNullOrBlank()) {
            headers = headers + ("user-agent" to network.defaultUserAgentProvider())
        }
        webViewUserAgent = headers["user-agent"]
        closeWhenPassed = intent.extras?.getBoolean(BOT_CHECK_KEY) == true
        val waitingHere = botCheckSourceId?.let { it in eu.kanade.tachiyomi.data.notification.BotChecks.waiting.value } == true
        if (closeWhenPassed || waitingHere) {
            watchedUrl = url
            watchBotCheck(url)
        }
        // KMK <--

        setComposeContent {
            WebViewScreenContent(
                onNavigateUp = { finish() },
                initialTitle = intent.extras?.getString(TITLE_KEY),
                url = url,
                headers = headers,
                onUrlChange = { assistUrl = it },
                onShare = this::shareWebpage,
                onOpenInBrowser = this::openInBrowser,
                onClearCookies = this::clearCookies,
            )
        }
    }

    override fun onDestroy() {
        // KMK (r116): closed without a sign of a pass: the waiting requests try once more (not when only turning
        // the screen); a pass was already told when it was seen
        if (!isChangingConfigurations && !botCheckPassed) {
            watchedUrl?.let {
                eu.kanade.tachiyomi.data.notification.BotChecks.webViewDone(it, botCheckSourceId, false, webViewUserAgent)
            }
        }
        // KMK: a source opened to pass its bot check is checked again once WebView closes
        botCheckSourceId?.let { eu.kanade.tachiyomi.data.notification.SourceStatusCheck.recheck(it) }
        super.onDestroy()
    }

    /**
     * KMK: Looks every second for the site's pass (a new cf_clearance cookie, shared with the app's own connection):
     * you passed the check. The waiting requests are told and carry on with your session; when opened to solve the
     * check, WebView closes by itself. Nothing on the page is touched. Other checks have no clear sign: closing
     * WebView makes the waiting requests try once more.
     */
    private fun watchBotCheck(url: String) {
        val cookies = CookieManager.getInstance()
        fun clearance(): String? = cookies.getCookie(url)
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotEmpty() }
        val before = clearance()
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val now = clearance()
                if (now != null && now != before) {
                    botCheckPassed = true
                    // A moment for the site to finish its redirect
                    delay(500)
                    eu.kanade.tachiyomi.data.notification.BotChecks.webViewDone(url, botCheckSourceId, true, webViewUserAgent)
                    if (closeWhenPassed) finish()
                    break
                }
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    private fun shareWebpage(url: String) {
        try {
            startActivity(url.toUri().toShareIntent(this, type = "text/plain"))
        } catch (e: Exception) {
            toast(e.message)
        }
    }

    private fun openInBrowser(url: String) {
        openInBrowser(url, forceDefaultBrowser = true)
    }

    private fun clearCookies(url: String) {
        val cleared = network.cookieJar.remove(url.toHttpUrl())
        logcat { "Cleared $cleared cookies for: $url" }
    }

    companion object {
        private const val URL_KEY = "url_key"
        private const val SOURCE_KEY = "source_key"
        private const val TITLE_KEY = "title_key"

        // KMK -->
        private const val BOT_CHECK_KEY = "bot_check_key"
        private const val USER_AGENT_KEY = "user_agent_key"

        /**
         * KMK (r116): WebView for you to pass a source's bot check (BotChecks.solve, the notification): watches for
         * the pass and closes by itself once it's seen. [userAgent]: the one the waiting requests used.
         */
        fun newBotCheckIntent(
            context: Context,
            url: String,
            sourceId: Long?,
            title: String?,
            userAgent: String?,
        ): Intent = newIntent(context, url, sourceId, title).apply {
            putExtra(BOT_CHECK_KEY, true)
            putExtra(USER_AGENT_KEY, userAgent)
        }
        // KMK <--

        fun newIntent(context: Context, url: String, sourceId: Long? = null, title: String? = null): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(URL_KEY, url)
                putExtra(SOURCE_KEY, sourceId)
                putExtra(TITLE_KEY, title)
            }
        }
    }
}
