package eu.kanade.tachiyomi.data.track.simkl

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.simkl.dto.SimklOAuth
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * KMK: Simkl (from Anikku). It only tracks anime: the tracking itself is done by
 * [eu.kanade.tachiyomi.data.anime.track.SimklAnime]; the manga side of [BaseTracker] is a no-op, and it isn't in
 * [eu.kanade.tachiyomi.data.track.TrackerManager.trackers], so manga screens never list it.
 */
class Simkl(id: Long) : BaseTracker(id, "Simkl") {

    companion object {
        const val WATCHING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val NOT_INTERESTING = 4L
        const val PLAN_TO_WATCH = 5L

        private val SCORE_LIST = IntRange(0, 10)
            .map(Int::toString)
            .toImmutableList()
    }

    private val json: Json by injectLazy()

    private val interceptor by lazy { SimklInterceptor(this) }

    private val api by lazy { SimklApi(client, interceptor) }

    /** Signed-in client for anime tracking (SimklAnime) */
    val animeClient by lazy { client.newBuilder().addInterceptor(interceptor).build() }

    override fun getLogo() = R.drawable.brand_simkl

    override fun getStatusList(): List<Long> = listOf(WATCHING, COMPLETED, ON_HOLD, NOT_INTERESTING, PLAN_TO_WATCH)

    override fun getStatus(status: Long): StringResource? = when (status) {
        WATCHING -> MR.strings.reading
        PLAN_TO_WATCH -> MR.strings.plan_to_read
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        NOT_INTERESTING -> MR.strings.dropped
        else -> null
    }

    override fun getReadingStatus(): Long = WATCHING

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun displayScore(track: DomainTrack): String = track.score.toInt().toString()

    // Manga isn't tracked on Simkl
    override suspend fun update(track: Track, didReadChapter: Boolean): Track = track

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track

    override suspend fun search(query: String): List<TrackSearch> = emptyList()

    override suspend fun refresh(track: Track): Track = track

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(code: String) {
        try {
            val oauth = api.accessToken(code)
            interceptor.newAuth(oauth)
            val user = api.getCurrentUser()
            saveCredentials(user.toString(), oauth.accessToken)
        } catch (_: Throwable) {
            logout()
        }
    }

    fun saveToken(oauth: SimklOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(oauth))
    }

    fun restoreToken(): SimklOAuth? {
        return try {
            json.decodeFromString<SimklOAuth>(trackPreferences.trackToken(this).get())
        } catch (_: Exception) {
            null
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        interceptor.newAuth(null)
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_WATCH
}
