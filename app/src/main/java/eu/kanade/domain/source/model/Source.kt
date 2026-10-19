package eu.kanade.domain.source.model

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val Source.icon: ImageBitmap?
    get() {
        return (
            Injekt.get<ExtensionManager>().getAppIconForSource(id)
                // KMK: an anime source shown with the manga source rows (anime migration)
                ?: Injekt.get<eu.kanade.tachiyomi.animeextension.AnimeExtensionManager>().getAppIconForSource(id)
            )
            ?.toBitmap()
            ?.asImageBitmap()
    }

// AM (BROWSE) -->
// Add an extra property to Source for it to get access to ExtensionManager
val Source.installedExtension: Extension.Installed?
    get() {
        return Injekt.get<ExtensionManager>()
            .installedExtensionsFlow
            .value
            .find { ext -> ext.sources.any { it.id == id } }
    }
// <-- AM (BROWSE)
