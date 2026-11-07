package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.GlassIcons
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * KMK (r142): the SH3 button's glass menu for a streamed HLS episode: the normal hand-off (skipping works), or the
 * stream as one plain video for simple players that can't read HLS. [onPick] gets true for the plain video.
 */
@Composable
fun OpenWithMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (plain: Boolean) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            Triple(false, KMR.strings.open_with_player, KMR.strings.open_with_stream_hint),
            Triple(true, KMR.strings.open_with_plain, KMR.strings.open_with_plain_hint),
        ).forEach { (plain, title, hint) ->
            DropdownMenuItem(
                leadingIcon = { Icon(if (plain) GlassIcons.Play else GlassIcons.OpenWith, contentDescription = null) },
                text = {
                    Column(modifier = Modifier.widthIn(max = 240.dp)) {
                        Text(stringResource(title))
                        Text(
                            text = stringResource(hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.62f),
                        )
                    }
                },
                onClick = {
                    onDismiss()
                    onPick(plain)
                },
            )
        }
    }
}
