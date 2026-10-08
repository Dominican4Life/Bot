package eu.kanade.tachiyomi.ui.browse.anime

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Details of an installed anime extension: its sources (with their settings), WebView and uninstall.
 */
class AnimeExtensionDetailsScreen(
    private val pkgName: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val manager = remember { Injekt.get<AnimeExtensionManager>() }
        val installed by manager.installedExtensionsFlow.collectAsState()
        val extension = installed.find { it.pkgName == pkgName }

        Scaffold(
            topBar = {
                AppBar(
                    title = extension?.name.orEmpty(),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            if (extension == null) {
                // Uninstalled while open
                LaunchedEffect(Unit) { navigator.pop() }
                return@Scaffold
            }
            LazyColumn(contentPadding = contentPadding) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        extension.icon?.let { icon ->
                            val bitmap = remember(icon) { icon.toBitmap().asImageBitmap() }
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)),
                            )
                        }
                        Text(text = extension.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = listOfNotNull(
                                extension.versionName,
                                LocaleHelper.getSourceDisplayName(extension.lang, context),
                                extension.storeName,
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.secondaryItemAlpha(),
                        )
                        OutlinedButton(onClick = {
                            manager.uninstallExtension(extension.pkgName)
                        }) {
                            Text(stringResource(MR.strings.ext_uninstall))
                        }
                    }
                    HorizontalDivider()
                }
                items(extension.sources, key = { it.id }) { source ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = source is ConfigurableAnimeSource) {
                                navigator.push(SourcePreferencesScreen(source.id, anime = true))
                            }
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = source.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = LocaleHelper.getSourceDisplayName(source.lang, context),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.secondaryItemAlpha(),
                            )
                        }
                        if (source is AnimeHttpSource) {
                            IconButton(onClick = {
                                navigator.push(WebViewScreen(url = source.baseUrl, initialTitle = source.name))
                            }) {
                                Icon(
                                    Icons.Outlined.Public,
                                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                                )
                            }
                        }
                        if (source is ConfigurableAnimeSource) {
                            IconButton(onClick = {
                                navigator.push(SourcePreferencesScreen(source.id, anime = true))
                            }) {
                                Icon(Icons.Outlined.Settings, contentDescription = stringResource(MR.strings.action_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}
