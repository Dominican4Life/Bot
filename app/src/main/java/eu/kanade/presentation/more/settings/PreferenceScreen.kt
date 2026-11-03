package eu.kanade.presentation.more.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import kotlin.time.Duration.Companion.seconds

/**
 * Preference Screen composable which contains a list of [Preference] items
 * @param items [Preference] items which should be displayed on the preference screen. An item can be a single [PreferenceItem] or a group ([Preference.PreferenceGroup])
 * @param modifier [Modifier] to be applied to the preferenceScreen layout
 */
@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = items.findHighlightedIndex(highlightKey)
            if (i >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }

    // KMK (r115): every settings page shows its groups as rounded cards (title as a small primary heading inside the
    // card, thin dividers between rows). The lazy list keeps one item per header / row / end, as before, so the
    // search highlight's index math is unchanged; each item draws its own slice of the card.
    val cardColor = MaterialTheme.colorScheme.surfaceContainer
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        items.fastForEachIndexed { i, preference ->
            when (preference) {
                // Create Preference Group
                is Preference.PreferenceGroup -> {
                    if (!preference.enabled) return@fastForEachIndexed
                    val firstShown = preference.preferenceItems.indexOfFirst { it.enabled }

                    item {
                        Column {
                            if (i == 0) Spacer(modifier = Modifier.height(SettingsCardGap / 2))
                            Column(modifier = Modifier.settingsCardSlice(top = true, bottom = false, cardColor)) {
                                PreferenceGroupHeader(title = preference.title)
                            }
                        }
                    }
                    itemsIndexed(preference.preferenceItems) { index, item ->
                        Column(modifier = Modifier.settingsCardSlice(top = false, bottom = false, cardColor)) {
                            if (item.enabled && firstShown in 0..<index) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                                    thickness = 1.dp,
                                    color = dividerColor,
                                )
                            }
                            PreferenceItem(
                                item = item,
                                highlightKey = highlightKey,
                            )
                        }
                    }
                    item {
                        Column {
                            Spacer(
                                modifier = Modifier
                                    .settingsCardSlice(top = false, bottom = true, cardColor)
                                    .height(SettingsCardEnd),
                            )
                            if (i < items.lastIndex) {
                                Spacer(modifier = Modifier.height(SettingsCardGap))
                            }
                        }
                    }
                }

                // Create Preference Item
                is Preference.PreferenceItem<*, *> -> item {
                    // KMK (r115): loose items next to each other share one card
                    val top = i == 0 || items[i - 1] !is Preference.PreferenceItem<*, *>
                    val bottom = i == items.lastIndex || items[i + 1] !is Preference.PreferenceItem<*, *>
                    Column {
                        if (i == 0) Spacer(modifier = Modifier.height(SettingsCardGap / 2))
                        Column(modifier = Modifier.settingsCardSlice(top = top, bottom = bottom, cardColor)) {
                            if (!top && preference.enabled) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                                    thickness = 1.dp,
                                    color = dividerColor,
                                )
                            }
                            if (top) Spacer(modifier = Modifier.height(SettingsCardEnd))
                            PreferenceItem(
                                item = preference,
                                highlightKey = highlightKey,
                            )
                            if (bottom) Spacer(modifier = Modifier.height(SettingsCardEnd))
                        }
                        if (bottom && i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(SettingsCardGap))
                        }
                    }
                }
            }
        }
    }
}

// KMK (r115): card look of the settings pages
private val SettingsCardOuterPadding = 12.dp
private val SettingsCardCorner = 18.dp
private val SettingsCardGap = 12.dp
private val SettingsCardEnd = 6.dp

/** One slice of a settings card: rounded at the top for the header, at the bottom for the end, flat in between */
private fun Modifier.settingsCardSlice(top: Boolean, bottom: Boolean, color: Color): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (top) SettingsCardCorner else 0.dp,
        topEnd = if (top) SettingsCardCorner else 0.dp,
        bottomStart = if (bottom) SettingsCardCorner else 0.dp,
        bottomEnd = if (bottom) SettingsCardCorner else 0.dp,
    )
    return this
        .padding(horizontal = SettingsCardOuterPadding)
        .fillMaxWidth()
        .clip(shape)
        .background(color)
}

private fun List<Preference>.findHighlightedIndex(highlightKey: String): Int {
    return flatMap {
        if (it is Preference.PreferenceGroup) {
            buildList<String?> {
                add(null) // Header
                addAll(it.preferenceItems.map { groupItem -> groupItem.title })
                add(null) // Spacer
            }
        } else {
            listOf(it.title)
        }
    }.indexOfFirst { it == highlightKey }
}
