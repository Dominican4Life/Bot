package eu.kanade.tachiyomi.data.backup.create

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR

data class BackupOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val tracking: Boolean = true,
    val history: Boolean = true,
    val readEntries: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,
    // SY -->
    val customInfo: Boolean = true,
    val savedSearchesFeeds: Boolean = true,
    // SY <--
    // KMK -->
    /** Sources made in the Code editor: code, details, on/off switch, custom id and settings */
    val jsSources: Boolean = true,
    /** Only titles in these categories ([CategoryChoice] keys); null for all */
    val categoryKeys: Set<String>? = null,
    // KMK <--
    // KMK (r115) -->
    /** The Source Lab's files: each JS source's history, test runs, lint, health, explain, and sourcelab/ */
    val sourceLabFiles: Boolean = true,
    /** What JS sources keep for themselves (js_storage/); off by default, it may hold logins */
    val jsStorage: Boolean = false,
    /** Custom cover pictures of manga and anime (each up to 5 MB) */
    val customCovers: Boolean = true,
    // KMK (r115) <--
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        chapters,
        tracking,
        history,
        readEntries,
        appSettings,
        extensionStores,
        sourceSettings,
        privateSettings,
        // SY -->
        customInfo,
        savedSearchesFeeds,
        // SY <--
        // KMK -->
        jsSources,
        // KMK <--
        // KMK (r115): only ever added at the end (stored by position)
        sourceLabFiles,
        jsStorage,
        customCovers,
    )

    fun canCreate() =
        libraryEntries || categories || appSettings || extensionStores || sourceSettings || savedSearchesFeeds ||
            /* KMK --> */ jsSources /* KMK <-- */ || /* KMK (r115) */ sourceLabFiles || jsStorage

    companion object {
        val libraryOptions = persistentListOf(
            Entry(
                label = MR.strings.manga,
                getter = BackupOptions::libraryEntries,
                chip = "Library",
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.chapters,
                getter = BackupOptions::chapters,
                chip = "Chapters & episodes",
                setter = { options, enabled -> options.copy(chapters = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.track,
                getter = BackupOptions::tracking,
                chip = "Tracking",
                setter = { options, enabled -> options.copy(tracking = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.history,
                getter = BackupOptions::history,
                chip = "History",
                setter = { options, enabled -> options.copy(history = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.categories,
                getter = BackupOptions::categories,
                chip = "Categories",
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.non_library_settings,
                getter = BackupOptions::readEntries,
                // KMK (r115): also anime watched outside the library
                chip = "All read & watched",
                setter = { options, enabled -> options.copy(readEntries = enabled) },
                enabled = { it.libraryEntries },
            ),
            // SY -->
            Entry(
                label = SYMR.strings.custom_entry_info,
                getter = BackupOptions::customInfo,
                chip = "Custom entry info",
                setter = { options, enabled -> options.copy(customInfo = enabled) },
                enabled = { it.libraryEntries },
            ),
            // KMK (r115): manga and anime custom covers, matched by source + url on restore
            Entry(
                label = MR.strings.custom_cover,
                getter = BackupOptions::customCovers,
                chip = "Custom covers",
                setter = { options, enabled -> options.copy(customCovers = enabled) },
                enabled = { it.libraryEntries },
                backupOnly = true,
            ),
            Entry(
                // KMK-->
                label = KMR.strings.saved_searches_feeds,
                // KMK <--
                getter = BackupOptions::savedSearchesFeeds,
                chip = "Saved searches & feeds",
                setter = { options, enabled -> options.copy(savedSearchesFeeds = enabled) },
            ),
            // SY <--
        )

        val settingsOptions = persistentListOf(
            Entry(
                label = MR.strings.app_settings,
                getter = BackupOptions::appSettings,
                chip = "App settings",
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionStores,
                getter = BackupOptions::extensionStores,
                chip = "Extension repos",
                setter = { options, enabled -> options.copy(extensionStores = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = BackupOptions::sourceSettings,
                chip = "Source settings",
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
            // KMK -->
            Entry(
                label = KMR.strings.backup_js_sources,
                getter = BackupOptions::jsSources,
                chip = "JS sources",
                setter = { options, enabled -> options.copy(jsSources = enabled) },
            ),
            // KMK <--
            // KMK (r115): the Source Lab's files of each JS source, and JS source storage (off: may hold logins)
            Entry(
                label = KMR.strings.backup_js_sources,
                getter = BackupOptions::sourceLabFiles,
                chip = "Source Lab files",
                setter = { options, enabled -> options.copy(sourceLabFiles = enabled) },
                backupOnly = true,
            ),
            Entry(
                label = KMR.strings.backup_js_sources,
                getter = BackupOptions::jsStorage,
                chip = "JS source storage (may hold logins)",
                setter = { options, enabled -> options.copy(jsStorage = enabled) },
                enabled = { it.jsSources },
                backupOnly = true,
            ),
            // KMK (r114): "Logins & tokens" listed last (the chip groups show it after JS sources); off by default
            Entry(
                label = MR.strings.private_settings,
                getter = BackupOptions::privateSettings,
                chip = "Logins & tokens",
                setter = { options, enabled -> options.copy(privateSettings = enabled) },
                enabled = { it.appSettings || it.sourceSettings },
            ),
        )

        fun fromBooleanArray(array: BooleanArray) = BackupOptions(
            libraryEntries = array[0],
            categories = array[1],
            chapters = array[2],
            tracking = array[3],
            history = array[4],
            readEntries = array[5],
            appSettings = array[6],
            extensionStores = array[7],
            sourceSettings = array[8],
            privateSettings = array[9],
            // SY -->
            customInfo = array[10],
            savedSearchesFeeds = array[11],
            // SY <--
            // KMK: older saved choices are shorter
            jsSources = array.getOrElse(12) { true },
            // KMK (r115): older saved choices are shorter still
            sourceLabFiles = array.getOrElse(13) { true },
            jsStorage = array.getOrElse(14) { false },
            customCovers = array.getOrElse(15) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (BackupOptions) -> Boolean,
        val setter: (BackupOptions, Boolean) -> BackupOptions,
        val enabled: (BackupOptions) -> Boolean = { true },
        // KMK (r114): the short name on the backup screen's chips; null shows [label]
        val chip: String? = null,
        // KMK (r115): a part only backups have (device sync's settings don't keep it, so its screen leaves it out)
        val backupOnly: Boolean = false,
    )
}
