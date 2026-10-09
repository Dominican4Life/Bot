package eu.kanade.tachiyomi.jarextension

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/*
 * KMK: A neutral view of a Kotatsu-family parser, so one Komikku source adapter ([JarSource]) can drive parsers
 * from either plugin ABI (Kotatsu `org.koitharu.kotatsu.parsers.*` or Usagi's Tsuki `tsuki.*`). Each bridge maps
 * its own model classes to these, and keeps the original object in `raw` to hand back unchanged.
 */

/** The plugin ABI a JAR was built against */
enum class JarArchitecture { KOTATSU, TSUKI }

class JTag(val title: String, val key: String)

class JManga(
    val url: String,
    val title: String,
    val altTitles: Set<String> = emptySet(),
    val publicUrl: String = "",
    val coverUrl: String? = null,
    val largeCoverUrl: String? = null,
    val description: String? = null,
    val authors: Set<String> = emptySet(),
    val tags: List<JTag> = emptyList(),
    /** Name of the MangaState enum (ONGOING, FINISHED, ...) */
    val state: String? = null,
    val isNsfw: Boolean = false,
    val chapters: List<JChapter>? = null,
    val raw: Any? = null,
)

class JChapter(
    val url: String,
    val title: String?,
    val number: Float,
    val volume: Int,
    val scanlator: String?,
    val uploadDate: Long,
    val branch: String?,
    val raw: Any? = null,
)

class JPage(val url: String, val preview: String?, val raw: Any? = null)

class JCapabilities(
    val multipleTags: Boolean,
    val tagsExclusion: Boolean,
    val search: Boolean,
    val searchWithFilters: Boolean,
    val year: Boolean,
    val yearRange: Boolean,
    val author: Boolean,
)

class JFilterOptions(
    val tags: List<JTag> = emptyList(),
    val states: List<String> = emptyList(),
    val contentRatings: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val demographics: List<String> = emptyList(),
    val locales: List<Locale> = emptyList(),
)

data class JFilter(
    val query: String? = null,
    val tags: Set<String> = emptySet(),
    val tagsExclude: Set<String> = emptySet(),
    val locale: Locale? = null,
    val states: Set<String> = emptySet(),
    val contentRatings: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val demographics: Set<String> = emptySet(),
    val year: Int = 0,
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val author: String? = null,
)

/** One parser instance of a JAR source */
interface JParser {
    val sortOrders: List<String>
    val capabilities: JCapabilities
    val domain: String
    val requestHeaders: Headers

    /** Tags known so far (from the last [filterOptions] call), by key */
    fun tag(key: String): JTag?

    suspend fun list(offset: Int, order: String, filter: JFilter): List<JManga>
    suspend fun details(manga: JManga): JManga
    suspend fun pages(chapter: JChapter): List<JPage>
    suspend fun pageUrl(page: JPage): String
    suspend fun filterOptions(): JFilterOptions
    suspend fun related(manga: JManga): List<JManga>
    fun intercept(chain: Interceptor.Chain): Response
}

/** What a JAR declares about one of its sources (read from its source enum) */
class JarSourceInfo(
    val name: String,
    val title: String,
    val lang: String,
    val contentType: String,
    val isNsfw: Boolean,
    val isBroken: Boolean,
    /** The enum constant the parser factory takes */
    val raw: Any,
)
