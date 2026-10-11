package eu.kanade.presentation.category.components.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.presentation.core.components.material.padding

@Composable
fun SourceCategoryListItem(
    modifier: Modifier,
    category: String,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    // KMK -->
    onClick: () -> Unit = onRename,
    summary: String? = null,
    // KMK <--
) {
    ElevatedCard(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(
                    start = MaterialTheme.padding.medium,
                    top = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = "")
            // KMK -->
            Column(modifier = Modifier.padding(start = MaterialTheme.padding.medium)) {
                Text(text = category)
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // KMK <--
        }
        Row {
            Spacer(modifier = Modifier.weight(1f))
            // KMK: pick the manga and anime sources in this category
            IconButton(onClick = onClick) {
                Icon(imageVector = Icons.Outlined.Checklist, contentDescription = "")
            }
            IconButton(onClick = onRename) {
                Icon(imageVector = Icons.Outlined.Edit, contentDescription = "")
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Outlined.Delete, contentDescription = "")
            }
        }
    }
}
