package eu.kanade.presentation.manga.components

import androidx.compose.runtime.staticCompositionLocalOf
import eu.kanade.presentation.components.TitleSheet
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK (r132): the AniList banner shown behind the title page (null = none; the page uses the cover's colours) */
val LocalTitleBanner = staticCompositionLocalOf<String?> { null }

/**
 * KMK (r132): finds the wide AniList banner picture for a title, like other readers show behind the details page.
 * Uses the AniList entry the title is tracked with; otherwise searches AniList by title and keeps only a close name
 * match. The answer (also "none") is saved per title, so each title is looked up once. Off in Settings › Glass.
 */
object AniListBanner {
    private val store get() = Injekt.get<PreferenceStore>()
    private val json = Json { ignoreUnknownKeys = true }
    private const val NONE = "-"
    private const val URL = "https://graphql.anilist.co/"

    /** KMK (r133): bumps when a background switch changes, so open pages look again */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * KMK (r133): is the AniList background on for this title? Its own choice, else its source's, else the switch in
     * Settings › Glass. [kind] is "manga" or "anime" (they're kept apart).
     */
    fun isOn(kind: String, id: Long, sourceId: Long): Boolean {
        val own = store.getInt("anilist_bg_${kind}_$id", -1).get()
        if (own != -1) return own == 1
        val src = store.getInt("anilist_bg_src_${kind}_$sourceId", -1).get()
        if (src != -1) return src == 1
        return TitleSheet.anilistBanner().get()
    }

    /** KMK (r133): set it for this title, or for every title of its source ([allFromSource]) */
    fun set(kind: String, id: Long, sourceId: Long, on: Boolean, allFromSource: Boolean) {
        val v = if (on) 1 else 0
        if (allFromSource) {
            store.getInt("anilist_bg_src_${kind}_$sourceId", -1).set(v)
            store.getInt("anilist_bg_${kind}_$id", -1).delete()
        } else {
            store.getInt("anilist_bg_${kind}_$id", -1).set(v)
        }
        changes.value++
    }

    /** KMK (r133): whether this title's choice is its own (true) or comes from its source / the app (false) */
    fun isOwn(kind: String, id: Long): Boolean = store.getInt("anilist_bg_${kind}_$id", -1).get() != -1

    suspend fun find(manga: Manga): String? {
        if (!isOn("manga", manga.id, manga.source)) return null
        val key = "anilist_banner_${manga.id}"
        val pref = store.getString(key, "")
        pref.get().takeIf { it.isNotEmpty() }?.let { return it.takeIf { v -> v != NONE } }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val tracked = Injekt.get<GetTracks>().await(manga.id)
                    .firstOrNull { it.trackerId == TrackerManager.ANILIST }?.remoteId
                if (tracked != null && tracked > 0) byId(tracked) else bySearch(manga.title)
            }.getOrNull()
        }
        // A failed request (null) is not saved, so it's tried again next time
        if (found != null) pref.set(found.ifEmpty { NONE })
        return found?.takeIf { it.isNotEmpty() }
    }

    /** KMK (r133): the same for an anime page: its AniList id when known, else a close title match */
    suspend fun findAnime(id: Long, sourceId: Long, title: String, anilistId: Long?): String? {
        if (!isOn("anime", id, sourceId)) return null
        val key = "anilist_banner_anime_$id"
        val pref = store.getString(key, "")
        pref.get().takeIf { it.isNotEmpty() }?.let { return it.takeIf { v -> v != NONE } }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                if (anilistId != null && anilistId > 0) byId(anilistId, "ANIME") else bySearch(title, "ANIME")
            }.getOrNull()
        }
        if (found != null) pref.set(found.ifEmpty { NONE })
        return found?.takeIf { it.isNotEmpty() }
    }

    private fun byId(id: Long, type: String = "MANGA"): String? {
        val media = query("query(\$id:Int){Media(id:\$id,type:$type){bannerImage}}") { put("id", id) }
            ?.get("Media")?.jsonObject ?: return ""
        return media["bannerImage"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun bySearch(title: String, type: String = "MANGA"): String? {
        val page = query(
            "query(\$s:String){Page(perPage:5){media(search:\$s,type:$type){bannerImage " +
                "title{romaji english native} synonyms}}}",
        ) { put("s", title) }?.get("Page")?.jsonObject ?: return null
        val wanted = normalize(title)
        val match = page["media"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { media ->
            val titles = media["title"]?.jsonObject?.values?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty() +
                media["synonyms"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            titles.any { normalize(it) == wanted }
        } ?: return ""
        return match["bannerImage"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The `data` object of an AniList GraphQL answer; null when the request failed */
    private fun query(q: String, vars: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject? {
        val body = buildJsonObject {
            put("query", q)
            putJsonObject("variables", vars)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(URL).post(body).header("Accept", "application/json").build()
        Injekt.get<NetworkHelper>().client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body.string()
            return json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
        }
    }
}
