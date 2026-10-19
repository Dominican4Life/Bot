package eu.kanade.tachiyomi.data.track.jellyfin

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import okhttp3.Dns
import okhttp3.OkHttpClient
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * KMK: Jellyfin (from Anikku). It only tracks anime from the Jellyfin extension, matched by the anime's URL, and
 * signs in with the extension's own server login, so "logging in" here just switches it on. The tracking itself is
 * done by [eu.kanade.tachiyomi.data.anime.track.JellyfinAnime]; it isn't in
 * [eu.kanade.tachiyomi.data.track.TrackerManager.trackers], so manga screens never list it.
 */
class Jellyfin(id: Long) : BaseTracker(id, "Jellyfin") {

    companion object {
        const val UNSEEN = 1L
        const val WATCHING = 2L
        const val COMPLETED = 3L

        /** The Jellyfin anime extension's source class */
        const val SOURCE_CLASS = "eu.kanade.tachiyomi.animeextension.all.jellyfin.Jellyfin"
    }

    /** Plain client: no DNS over HTTPS (it breaks servers reached by IP or a local name) */
    val animeClient: OkHttpClient by lazy {
        networkService.client.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Komikku v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
                        .build(),
                )
            }
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun getLogo() = R.drawable.brand_jellyfin

    override fun getStatusList(): List<Long> = listOf(UNSEEN, WATCHING, COMPLETED)

    override fun getStatus(status: Long): StringResource? = when (status) {
        UNSEEN -> MR.strings.plan_to_read
        WATCHING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        else -> null
    }

    override fun getReadingStatus(): Long = WATCHING

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = persistentListOf()

    override fun displayScore(track: DomainTrack): String = ""

    // Manga isn't tracked on Jellyfin
    override suspend fun update(track: Track, didReadChapter: Boolean): Track = track

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track

    override suspend fun search(query: String): List<TrackSearch> = emptyList()

    override suspend fun refresh(track: Track): Track = track

    override suspend fun login(username: String, password: String) {
        loginNoop()
    }

    fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == UNSEEN
}
