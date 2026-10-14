package eu.kanade.tachiyomi.source

import eu.kanade.domain.source.service.SourcePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: Sources turned off (the source, or its language, switched off in Extensions or the sources filter), manga and
 * anime. A turned-off source is left alone as if it weren't on the device: no bot-check checks or notifications, no
 * feeds, and it isn't searched when migrating. Its titles can still be migrated away from it.
 */
object DisabledSources {

    private val preferences by lazy { Injekt.get<SourcePreferences>() }

    fun ids(): Set<Long> = runCatching {
        (preferences.disabledSources().get() + preferences.animeDisabledSources().get())
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

    fun isDisabled(sourceId: Long): Boolean = sourceId in ids()
}
