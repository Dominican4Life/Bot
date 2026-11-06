package eu.kanade.presentation.manga.components

import eu.kanade.presentation.browse.components.liquidGlass
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import eu.kanade.presentation.components.GlassIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.material.padding

@Composable
fun OutlinedButtonWithArrow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = GlassIcons.Arrow,
) {
    // KMK (r132): one glass capsule: the words, and the new arrow on its own at the end
    val shape = RoundedCornerShape(25.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            )
            .height(50.dp)
            .clip(shape)
            // KMK (r136, W): cover-tinted glass like the other panels
            .liquidGlass(shape, tachiyomi.presentation.core.components.tintedGlass(1f, androidx.compose.ui.graphics.Color(0xFF12141B)))
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun OutlinedButtonWithArrowPreview() {
    OutlinedButtonWithArrow(
        text = "Click Me",
        onClick = { /* Do nothing */ },
    )
}
