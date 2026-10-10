package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.model.icon
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.R
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.source.local.LocalSource

@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        Badge(text = "$count")
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
    // SY -->
    useLangIcon: Boolean = false,
    // SY <--
) {
    if (isLocal) {
        Badge(
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        // SY -->
        if (useLangIcon) {
            Badge(
                painter = painterResource(getLanguageIconID(sourceLanguage) ?: R.drawable.flag_globe),
                color = Color.Transparent,
                modifier = Modifier
                    .width(25.dp)
                    .height(18.dp),
            )
            return
        }
        // SY <--
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

// SY -->
@Composable
internal fun SourceIconBadge(source: Source?) {
    // Local source is already shown by the local source badge
    if (source == null || source.id == LocalSource.ID) return
    val icon = remember(source.id) { source.icon }

    when {
        icon != null -> {
            Badge(
                imageBitmap = icon,
                modifier = Modifier
                    .scale(1.3f)
                    .height(18.dp),
            )
        }
        source.isStub -> {
            Badge(
                imageVector = Icons.Filled.Warning,
                iconColor = MaterialTheme.colorScheme.error,
                color = MaterialTheme.colorScheme.errorContainer,
            )
        }
        else -> {
            Badge(
                imageVector = Icons.Outlined.LocalLibrary,
                color = MaterialTheme.colorScheme.tertiary,
                iconColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}
// SY <--

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "en", useLangIcon = true)
        }
    }
}
