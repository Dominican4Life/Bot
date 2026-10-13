package mihon.feature.common.utils

import dev.icerock.moko.resources.StringResource
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR

fun MigrationFlag.getLabel(): StringResource {
    return when (this) {
        MigrationFlag.CHAPTER -> MR.strings.chapters
        MigrationFlag.CATEGORY -> MR.strings.categories
        // KMK -->
        MigrationFlag.TRACK -> MR.strings.track
        // KMK <--
        // KMK: shorter, so all the chips fit on one row
        MigrationFlag.CUSTOM_COVER -> KMR.strings.migrate_covers
        MigrationFlag.NOTES -> MR.strings.action_notes
        MigrationFlag.REMOVE_DOWNLOAD -> MR.strings.delete_downloaded
        // KMK -->
        MigrationFlag.EXTRA -> SYMR.strings.log_extra
        // KMK <--
    }
}
