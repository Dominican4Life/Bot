package eu.kanade.tachiyomi.ui.browse.anime

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.SourcesFilterToggle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.Scroller.STICKY_HEADER_KEY_PREFIX
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Anime version of the manga sources filter (Anikku had one): turn whole languages or
 * single anime sources off so they disappear from the anime sources list.
 */
class AnimeSourcesFilterScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
        val extensionManager = remember { Injekt.get<AnimeExtensionManager>() }
        val preferences = remember { Injekt.get<SourcePreferences>() }
        val sources by sourceManager.catalogueSources.collectAsState(initial = emptyList())
        val disabledLangsPref = remember { preferences.animeDisabledLanguages() }
        val disabledSourcesPref = remember { preferences.animeDisabledSources() }
        val disabledLangs by disabledLangsPref.collectAsState()
        val disabledSources by disabledSourcesPref.collectAsState()

        val byLanguage = remember(sources) {
            sources.groupBy { it.lang }
                .toSortedMap(compareBy<String>({ it == "other" }, { it != "all" }, { it }))
                .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (byLanguage.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.source_filter_empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            val context = LocalContext.current
            FastScrollLazyColumn(modifier = Modifier.padding(contentPadding)) {
                byLanguage.forEach { (lang, langSources) ->
                    val enabled = lang !in disabledLangs
                    stickyHeader(key = "$STICKY_HEADER_KEY_PREFIX-$lang", contentType = "header") {
                        SwitchPreferenceWidget(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .padding(end = MaterialTheme.padding.small),
                            title = LocaleHelper.getSourceDisplayName(lang, context) +
                                " (${FlagEmoji.getEmojiLangFlag(lang)})",
                            checked = enabled,
                            onCheckedChanged = {
                                disabledLangsPref.set(if (enabled) disabledLangs + lang else disabledLangs - lang)
                            },
                        )
                    }
                    if (enabled) {
                        stickyHeader(key = "$STICKY_HEADER_KEY_PREFIX-toggle-$lang", contentType = "toggle") {
                            val allOn = langSources.none { it.id.toString() in disabledSources }
                            SourcesFilterToggle(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(end = MaterialTheme.padding.small),
                                isEnabled = allOn,
                                language = lang,
                                onClickItem = {
                                    val ids = langSources.map { it.id.toString() }.toSet()
                                    disabledSourcesPref.set(if (allOn) disabledSources + ids else disabledSources - ids)
                                },
                            )
                        }
                        items(langSources, key = { "anime-filter-${it.id}" }) { source ->
                            val on = source.id.toString() !in disabledSources
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val id = source.id.toString()
                                        disabledSourcesPref.set(if (on) disabledSources + id else disabledSources - id)
                                    }
                                    .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small)
                                    .padding(vertical = MaterialTheme.padding.small),
                            ) {
                                val icon = remember(source.id) { extensionManager.getAppIconForSource(source.id) }
                                val iconModifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                if (icon != null) {
                                    val bitmap = remember(icon) { icon.toBitmap().asImageBitmap() }
                                    Image(bitmap = bitmap, contentDescription = null, modifier = iconModifier)
                                } else {
                                    Icon(Icons.Outlined.Extension, contentDescription = null, modifier = iconModifier)
                                }
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = MaterialTheme.padding.medium),
                                )
                                Checkbox(checked = on, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}
