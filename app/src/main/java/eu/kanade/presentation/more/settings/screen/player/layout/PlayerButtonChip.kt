package eu.kanade.presentation.more.settings.screen.player.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.getIcon
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PlayerButtonChip(
    button: PlayerButton,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    badgeIcon: ImageVector? = null,
    badgeColor: Color? = null,
) {
    Box(
        modifier = Modifier.padding(4.dp),
    ) {
        Card(
            modifier = Modifier,
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 1.dp else 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
                contentColor = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            ),
            onClick = { onClick?.invoke() },
            enabled = enabled && onClick != null,
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (button) {
                    PlayerButton.VideoTitle -> {
                        Text(
                            text = stringResource(button.titleRes),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp,
                        )
                    }
                    PlayerButton.CurrentChapter -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = button.getIcon(),
                                contentDescription = stringResource(button.titleRes),
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                text = "1:06 • Chapter 1",
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = button.getIcon(),
                            contentDescription = stringResource(button.titleRes),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        if (badgeIcon != null && badgeColor != null) {
            Icon(
                imageVector = badgeIcon,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}
