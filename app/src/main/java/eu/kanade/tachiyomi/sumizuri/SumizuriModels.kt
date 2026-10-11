package eu.kanade.tachiyomi.sumizuri

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** KMK: A saved Sumizuri source: what's known about it without running it */
@Serializable
data class SumizuriSourceInfo(
    val key: String,
    val name: String,
    val lang: String = "en",
    val iconUrl: String? = null,
    val baseUrl: String = "",
    /** JSON declarative (true) or JavaScript (false) */
    val json: Boolean = false,
    /** Anime (videos) rather than manga (pages) */
    val anime: Boolean = false,
    val hasDetails: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** Saved from the last run, so the sync filter / settings screens can show them */
    val filters: JsonElement? = null,
    val catalogs: JsonElement? = null,
    /** KMK: `tabNames: { popular, latest }`, the source's own names for those buttons. Optional */
    val tabNames: JsonElement? = null,
    val preferences: JsonElement? = null,
    /** The source has getTags(), which gives the suggestions of its autocomplete filters */
    val hasTags: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val id: Long get() = idFor(key)

    val displayLang: String get() = lang.ifBlank { "all" }.let { if (it == "multi" || it == "mul") "all" else it }

    companion object {
        /** A stable source id from the source's key, the way Mihon sources get theirs from name and language */
        fun idFor(key: String): Long {
            val bytes = MessageDigest.getInstance("MD5").digest("sumizuri/$key/1".toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}

internal object SumizuriMapping {

    fun list(element: JsonElement): List<JsonObject> =
        (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun genres(entry: JsonObject): String? {
        val value = entry["genres"] ?: entry["tags"] ?: return null
        val list = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> value.content.split(',').map { it.trim() }
            else -> emptyList()
        }
        return list.filter { it.isNotBlank() }.joinToString().ifBlank { null }
    }

    fun mangaStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        null, "" -> SManga.UNKNOWN
        "ongoing", "releasing", "publishing", "airing", "on going" -> SManga.ONGOING
        "completed", "complete", "finished", "ended" -> SManga.COMPLETED
        "hiatus", "on hiatus", "paused" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped", "abandoned", "discontinued" -> SManga.CANCELLED
        "licensed" -> SManga.LICENSED
        else -> SManga.UNKNOWN
    }

    fun animeStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        null, "" -> SAnime.UNKNOWN
        "ongoing", "releasing", "airing", "on going", "currently airing" -> SAnime.ONGOING
        "completed", "complete", "finished", "ended", "finished airing" -> SAnime.COMPLETED
        "hiatus", "on hiatus", "paused" -> SAnime.ON_HIATUS
        "cancelled", "canceled", "dropped", "abandoned" -> SAnime.CANCELLED
        "licensed" -> SAnime.LICENSED
        "upcoming", "not yet aired", "not yet released" -> SAnime.UPCOMING
        else -> SAnime.UNKNOWN
    }

    private fun description(entry: JsonObject): String? {
        val text = entry.str("description")?.trim().orEmpty()
        val rating = entry.double("rating")
        return buildString {
            append(text)
            if (rating != null && rating > 0) {
                if (isNotEmpty()) append("\n\n")
                append("Rating: ").append(if (rating % 1.0 == 0.0) rating.toInt() else rating)
            }
        }.ifBlank { null }
    }

    fun toSManga(entry: JsonObject, fallback: SManga? = null): SManga? {
        val url = entry.str("url") ?: fallback?.url ?: return null
        return SManga.create().apply {
            this.url = url
            title = entry.str("title") ?: fallback?.title.orEmpty()
            thumbnail_url = entry.str("coverUrl") ?: fallback?.thumbnail_url
            author = entry.str("author") ?: fallback?.author
            artist = entry.str("artist") ?: fallback?.artist
            description = description(entry) ?: fallback?.description
            genre = genres(entry) ?: fallback?.genre
            status = entry.str("status")?.let(::mangaStatus) ?: fallback?.status ?: SManga.UNKNOWN
        }
    }

    fun toSAnime(entry: JsonObject, fallback: SAnime? = null): SAnime? {
        val url = entry.str("url") ?: fallback?.url ?: return null
        return SAnime.create().apply {
            this.url = url
            title = entry.str("title") ?: fallback?.title.orEmpty()
            thumbnail_url = entry.str("coverUrl") ?: fallback?.thumbnail_url
            background_url = entry.str("bannerUrl") ?: fallback?.background_url
            author = entry.str("author") ?: fallback?.author
            artist = entry.str("artist") ?: fallback?.artist
            description = description(entry) ?: fallback?.description
            genre = genres(entry) ?: fallback?.genre
            status = entry.str("status")?.let(::animeStatus) ?: fallback?.status ?: SAnime.UNKNOWN
        }
    }

    fun date(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        text.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        return runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(text).toEpochMilli() }
            .recoverCatching { java.time.LocalDate.parse(text.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun number(chapter: JsonObject): Float? =
        chapter.double("number")?.toFloat() ?: chapter.str("number")?.toFloatOrNull()

    private fun numberText(number: Float) = if (number % 1f == 0f) number.toInt().toString() else number.toString()

    /** Newest first, as the app lists them; Sumizuri sorts by number */
    private fun <T> ordered(chapters: List<JsonObject>, map: (JsonObject, Int) -> T?): List<T> {
        val numbered = chapters.all { number(it) != null }
        val sorted = if (numbered) {
            chapters.sortedByDescending { number(it) }
        } else {
            val first = date(chapters.firstOrNull()?.str("dateUploaded"))
            val last = date(chapters.lastOrNull()?.str("dateUploaded"))
            if (first in 1..<last) chapters.asReversed() else chapters
        }
        return sorted.mapIndexedNotNull { index, chapter -> map(chapter, index) }
    }

    fun toSChapters(chapters: List<JsonObject>): List<SChapter> = ordered(chapters) { chapter, _ ->
        val url = chapter.str("url") ?: return@ordered null
        val number = number(chapter)
        SChapter.create().apply {
            this.url = url
            name = chapter.str("title")?.takeIf { it.isNotBlank() }
                ?: number?.let { "Chapter ${numberText(it)}" }
                ?: url
            chapter.int("volume")?.takeIf { it > 0 }?.let { volume ->
                if (!name.contains("vol", ignoreCase = true)) name = "Vol. $volume $name"
            }
            if (chapter.bool("locked") == true) name = "🔒 $name"
            chapter_number = number ?: -1f
            scanlator = chapter.str("scanlator")
            date_upload = date(chapter.str("dateUploaded"))
        }
    }

    fun toSEpisodes(episodes: List<JsonObject>): List<SEpisode> {
        val seasons = episodes.mapNotNull { it["season"]?.let { s -> (s as? JsonPrimitive)?.content } }.distinct()
        val seasonNames = episodes.mapNotNull { ep ->
            val season = (ep["season"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            ep.str("seasonName")?.let { season to it }
        }.toMap()
        return ordered(episodes) { episode, _ ->
            val url = episode.str("url") ?: return@ordered null
            val number = number(episode)
            SEpisode.create().apply {
                this.url = url
                var title = episode.str("title")?.takeIf { it.isNotBlank() }
                    ?: number?.let { "Episode ${numberText(it)}" }
                    ?: url
                // Several seasons in one title: say which one each episode is from
                val season = (episode["season"] as? JsonPrimitive)?.content
                if (seasons.size > 1 && season != null) {
                    val label = seasonNames[season] ?: season.toIntOrNull()?.let { "S$it" } ?: season
                    title = "$label · $title"
                }
                if (episode.bool("locked") == true) title = "🔒 $title"
                name = title
                episode_number = number ?: -1f
                scanlator = episode.str("scanlator")
                date_upload = date(episode.str("dateUploaded"))
                summary = episode.str("description") ?: episode.str("summary")
                preview_url = episode.str("thumbnailUrl") ?: episode.str("previewUrl") ?: episode.str("coverUrl")
            }
        }
    }
}
