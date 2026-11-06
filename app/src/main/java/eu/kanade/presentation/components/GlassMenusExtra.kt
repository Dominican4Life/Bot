package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.liquidGlass

/**
 * KMK (r132): a message as a glass pill (instead of the dark bar): the tracker icon, the text, and its action (Undo …)
 * as a small glass button. Use as `SnackbarHost(state) { GlassSnackbar(it) }`.
 */
@Composable
fun GlassSnackbar(data: SnackbarData) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .liquidGlass(shape)
                .background(Color(0xFF16181F).copy(alpha = 0.55f))
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = GlassIcons.Tracker,
                contentDescription = null,
                tint = tachiyomi.presentation.core.components.LocalGlassAccent.current,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = data.visuals.message,
                color = Color(0xFFF1F2F5),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp, end = 8.dp).weight(1f, fill = false),
            )
            data.visuals.actionLabel?.let { label ->
                Text(
                    text = label,
                    color = Color(0xFFF1F2F5),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable { data.performAction() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
