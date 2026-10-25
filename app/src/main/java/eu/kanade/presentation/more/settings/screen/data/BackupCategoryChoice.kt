package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.CategoryChoice
import eu.kanade.tachiyomi.data.backup.models.Backup
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: A category that can be picked in backup or restore: its [CategoryChoice] key and its name (null for none) */
@Immutable
data class CategoryChoiceEntry(val key: String, val name: String?, val anime: Boolean)

/** KMK: The manga and anime categories in the library, plus "no category" of each kind */
suspend fun libraryCategoryChoices(): List<CategoryChoiceEntry> {
    val manga = runCatching { Injekt.get<GetCategories>().await() }.getOrDefault(emptyList())
        .filterNot { it.isSystemCategory }
        .map { CategoryChoiceEntry(CategoryChoice.manga(it.name), it.name, anime = false) }
    val anime = runCatching { Injekt.get<AnimeRepository>().getCategories() }.getOrDefault(emptyList())
        .map { CategoryChoiceEntry(CategoryChoice.anime(it.name), it.name, anime = true) }
    return listOf(CategoryChoiceEntry(CategoryChoice.manga(null), null, anime = false)) + manga +
        listOf(CategoryChoiceEntry(CategoryChoice.anime(null), null, anime = true)) + anime
}

/** KMK: The manga and anime categories inside a backup file, plus "no category" of each kind */
fun backupCategoryChoices(backup: Backup): List<CategoryChoiceEntry> {
    val manga = backup.backupCategories
        .map { CategoryChoiceEntry(CategoryChoice.manga(it.name), it.name, anime = false) }
    val anime = backup.backupAnime.flatMap { it.categories }.distinct()
        .map { CategoryChoiceEntry(CategoryChoice.anime(it), it, anime = true) }
    return listOf(CategoryChoiceEntry(CategoryChoice.manga(null), null, anime = false)) + manga +
        listOf(CategoryChoiceEntry(CategoryChoice.anime(null), null, anime = true)) + anime
}

/** KMK: Ticks for which categories' titles go into a backup or come out of one */
@Composable
fun LazyItemScope.CategoryChoiceCard(
    entries: List<CategoryChoiceEntry>,
    picked: Set<String>,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    /** Pick only this one (restore); null hides the button */
    onOnly: ((String) -> Unit)? = null,
) {
    SectionCard(KMR.strings.backup_categories_title) {
        Text(
            text = stringResource(KMR.strings.backup_categories_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entries.forEach { entry ->
            val kind = stringResource(if (entry.anime) KMR.strings.backup_category_anime else KMR.strings.backup_category_manga)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledCheckbox(
                    label = "$kind · " + (entry.name ?: stringResource(KMR.strings.backup_no_category)),
                    checked = entry.key in picked,
                    onCheckedChange = { onToggle(entry.key, it) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                if (onOnly != null) {
                    TextButton(onClick = { onOnly(entry.key) }, enabled = enabled) { Text("Only this") }
                }
            }
        }
    }
}
