package eu.kanade.presentation.browse.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK (r125): the tablet preview panel in glass. One skinny panel floats over the covers (they don't move): the cover
 * with the title, status and four round buttons on top, then Chapters / Preview / Details tabs and a Play button.
 * Settings › Appearance › "Glass preview panel" turns it off (the old left and right panels).
 */
object PreviewGlass {
    private val store get() = Injekt.get<PreferenceStore>()

    fun enabled(): Preference<Boolean> = store.getBoolean("preview_glass_panel", true)

    fun isOn(): Boolean = enabled().get()
}

enum class PreviewPanelTab { LIST, PREVIEW, DETAILS }

/** Which part of [BrowsePreviewInfoHeader] to draw: the whole thing (old panel), the glass top, or the details */
enum class InfoHeaderPart { FULL, TOP, DETAILS }

val LocalInfoHeaderPart = compositionLocalOf { InfoHeaderPart.FULL }

internal val GlassPanelColor = Color(0xFF12141B).copy(alpha = 0.80f)

fun Modifier.panelGlassEdge(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), shape)
    .background(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.4f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.04f),
        ),
        shape,
    )

/** The glass top: cover, title, author, status and the four round buttons (no labels) */
@Composable
internal fun GlassPanelTop(
    manga: Manga,
    statusLine: String,
    onAddToLibrary: () -> Unit,
    onWebView: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // The cover's own colours, blurred, behind the top
        AsyncImage(
            model = manga.asMangaCover(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.35f,
            modifier = Modifier
                .matchParentSize()
                .blur(28.dp),
        )
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = manga.asMangaCover(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(92.dp)
                    .height(132.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenDetails),
            )
            Column(modifier = Modifier.weight(1f).height(132.dp)) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenDetails),
                )
                val author = listOfNotNull(manga.author, manga.artist?.takeIf { it != manga.author })
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                if (author.isNotEmpty()) {
                    SmallLine(Icons.Outlined.PersonOutline, author)
                }
                if (statusLine.isNotEmpty()) SmallLine(Icons.Outlined.Schedule, statusLine)
                Spacer(modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIcon(
                        if (manga.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        stringResource(MR.strings.add_to_library),
                        selected = manga.favorite,
                        onClick = onAddToLibrary,
                    )
                    RoundIcon(Icons.Outlined.HourglassEmpty, null, onClick = onOpenDetails)
                    RoundIcon(Icons.Outlined.Sync, stringResource(MR.strings.manga_tracking_tab), onClick = onOpenDetails)
                    RoundIcon(Icons.Outlined.Public, stringResource(MR.strings.action_web_view), onClick = onWebView)
                }
            }
        }
    }
}

@Composable
private fun SmallLine(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String?, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(19.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Chapters (or Episodes) / Preview / Details */
@Composable
internal fun GlassPanelTabs(
    tab: PreviewPanelTab,
    onTab: (PreviewPanelTab) -> Unit,
    listLabel: String,
    showPreview: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val tabs = buildList {
            add(Triple(PreviewPanelTab.LIST, Icons.AutoMirrored.Outlined.ViewList, listLabel))
            if (showPreview) {
                add(Triple(PreviewPanelTab.PREVIEW, Icons.Outlined.GridView, stringResource(KMR.strings.title_sheet_preview)))
            }
            add(Triple(PreviewPanelTab.DETAILS, Icons.Outlined.Info, stringResource(KMR.strings.preview_glass_details)))
        }
        tabs.forEach { (value, icon, label) ->
            val on = value == tab
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onTab(value) }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** The Play button at the bottom of the glass panel */
@Composable
internal fun GlassPanelPlay(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(CircleShape)
            .background(Color(0xFF14161E).copy(alpha = 0.7f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), CircleShape)
            .padding(5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
