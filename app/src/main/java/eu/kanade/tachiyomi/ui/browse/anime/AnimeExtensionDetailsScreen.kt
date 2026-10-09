package eu.kanade.tachiyomi.ui.browse.anime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.extension.interactor.ExtensionSourceItem
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.ExtensionDetailsScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Details of an installed anime extension. Same screen as the manga extension details (header with
 * version, language, age rating and uninstall; one row per source with its settings and on/off switch).
 */
class AnimeExtensionDetailsScreen(
    private val pkgName: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val manager = remember { Injekt.get<AnimeExtensionManager>() }
        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val installed by manager.installedExtensionsFlow.collectAsState()
        val disabledPref = remember { sourcePreferences.animeDisabledSources() }
        val disabled by disabledPref.collectAsState()
        val extension = installed.find { it.pkgName == pkgName }

        if (extension == null) {
            // Uninstalled while open
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }

        val asManga = remember(extension) { extension.toInstalledExtension() }
        val sameName = extension.sources.map { it.name }.distinct().size == 1
        val items = remember(extension, disabled) {
            extension.sources.map { source ->
                ExtensionSourceItem(
                    source = StubSource(source.id, source.lang, source.name),
                    enabled = source.id.toString() !in disabled,
                    labelAsName = extension.sources.size > 1 && !sameName,
                )
            }.toImmutableList()
        }
        val configurableIds = remember(extension) {
            extension.sources.filterIsInstance<ConfigurableAnimeSource>().map { it.id }.toSet()
        }
        val firstHttp = extension.sources.filterIsInstance<AnimeHttpSource>().firstOrNull()

        fun setAll(enabled: Boolean) {
            val ids = extension.sources.map { it.id.toString() }.toSet()
            disabledPref.set(if (enabled) disabled - ids else disabled + ids)
        }

        ExtensionDetailsScreen(
            navigateUp = navigator::pop,
            state = ExtensionDetailsScreenModel.State(extension = asManga, _sources = items),
            onClickSourcePreferences = { navigator.push(SourcePreferencesScreen(it, anime = true)) },
            onOpenWebView = firstHttp?.let { source ->
                { navigator.push(WebViewScreen(url = source.baseUrl, initialTitle = source.name)) }
            },
            onClickEnableAll = { setAll(true) },
            onClickDisableAll = { setAll(false) },
            onClickClearCookies = {
                val network = Injekt.get<NetworkHelper>()
                extension.sources.filterIsInstance<AnimeHttpSource>()
                    .map { it.baseUrl }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach { url -> runCatching { network.cookieJar.remove(url.toHttpUrl()) } }
                context.toast(MR.strings.cookies_cleared)
            },
            onClickUninstall = { manager.uninstallExtension(extension.pkgName) },
            onClickSource = { id ->
                val key = id.toString()
                disabledPref.set(if (key in disabled) disabled - key else disabled + key)
            },
            onClickIncognito = {},
            isConfigurable = { it.id in configurableIds },
        )
    }
}

/** The manga details screen only needs the shared fields; anime sources are shown through stubs */
private fun AnimeExtension.toInstalledExtension() = Extension.Installed(
    name = name,
    pkgName = pkgName,
    versionName = versionName,
    versionCode = versionCode,
    libVersion = libVersion,
    lang = lang,
    isNsfw = isNsfw,
    signatureHash = signatureHash,
    storeName = storeName,
    pkgFactory = pkgFactory,
    sources = sources.map { StubSource(it.id, it.lang, it.name) },
    icon = icon,
    hasUpdate = hasUpdate,
    isObsolete = isObsolete,
    isShared = isShared,
    store = store,
)
