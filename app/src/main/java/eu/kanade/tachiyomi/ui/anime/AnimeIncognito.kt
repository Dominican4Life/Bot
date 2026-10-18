package eu.kanade.tachiyomi.ui.anime

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: incognito for one anime source, like the manga sources' per-extension incognito. Kept in the same
 * "incognito extensions" set, as "anime:<source id>". The app-wide Incognito mode counts too.
 * While it's on, the player saves no watch history or progress.
 */
object AnimeIncognito {

    private fun key(sourceId: Long) = "anime:$sourceId"

    private val basePreferences get() = Injekt.get<BasePreferences>()
    private val sourcePreferences get() = Injekt.get<SourcePreferences>()

    /** True when this source, or the whole app, is in incognito mode */
    fun isOn(sourceId: Long?): Boolean {
        if (basePreferences.incognitoMode().get()) return true
        return sourceId != null && key(sourceId) in sourcePreferences.incognitoExtensions().get()
    }

    fun subscribe(sourceId: Long): Flow<Boolean> = combine(
        basePreferences.incognitoMode().changes(),
        sourcePreferences.incognitoExtensions().changes(),
    ) { app, sources -> app || key(sourceId) in sources }
        .distinctUntilChanged()

    /** Turns this source's own incognito on or off */
    fun toggle(sourceId: Long) {
        val key = key(sourceId)
        sourcePreferences.incognitoExtensions().getAndSet { if (key in it) it - key else it + key }
    }
}
