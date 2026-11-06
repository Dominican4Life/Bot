package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.GlassChoiceSwitch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * KMK (r133): the Display tab's AniList Background switch: on = the wide AniList picture behind the page whenever
 * AniList has one, with Just This Title / All From This Source like the thumbnail and summary choices. [kind] is
 * "manga" or "anime".
 */
@Composable
fun AniListBackgroundRows(kind: String, id: Long, sourceId: Long) {
    val version by AniListBanner.changes.collectAsState()
    val on = remember(version, id) { AniListBanner.isOn(kind, id, sourceId) }
    val own = remember(version, id) { AniListBanner.isOwn(kind, id) }
    Column {
        HeadingItem(KMR.strings.anilist_bg_heading)
        CheckboxItem(
            label = stringResource(KMR.strings.anilist_bg_title),
            checked = on,
            onClick = { AniListBanner.set(kind, id, sourceId, !on, allFromSource = !own) },
        )
        GlassChoiceSwitch(
            choices = listOf(
                true to stringResource(KMR.strings.anilist_bg_just_title),
                false to stringResource(KMR.strings.anilist_bg_all_source),
            ),
            selected = own,
            onSelect = { justThis -> AniListBanner.set(kind, id, sourceId, on, allFromSource = !justThis) },
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(KMR.strings.anilist_bg_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}
