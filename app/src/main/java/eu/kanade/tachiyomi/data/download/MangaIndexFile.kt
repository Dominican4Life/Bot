package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: An `index.json` in each downloaded title's folder (like Kotatsu's), next to its cover.jpg and chapters: the
 * title's details (title, author, artist, description, tags, status, cover address) and its whole chapter list.
 * Suggestions aren't kept. It's written whenever the title's details and chapters are got from the source and it
 * has a download folder. After a reinstall or a restore, a title with nothing saved yet fills in from it instead of
 * asking the source.
 */
object MangaIndexFile {

    const val NAME = "index.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Serializable
    data class Index(
        val version: Int = 1,
        val source: Long,
        val url: String,
        val title: String,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val status: Long = 0,
        val thumbnailUrl: String? = null,
        val chapters: List<IndexChapter> = emptyList(),
    )

    @Serializable
    data class IndexChapter(
        val url: String,
        val name: String,
        val number: Double = -1.0,
        val scanlator: String? = null,
        val dateUpload: Long = 0,
    )

    private val provider: DownloadProvider by lazy { Injekt.get() }

    /** Writes the title's index.json, when it has a download folder. Chapters in the source's order (newest first) */
    fun write(manga: Manga, chapters: List<Chapter>, source: Source) {
        runCatching {
            val dir = provider.findMangaDir(manga.ogTitle, source) ?: return
            val index = Index(
                source = manga.source,
                url = manga.url,
                title = manga.ogTitle,
                author = manga.ogAuthor,
                artist = manga.ogArtist,
                description = manga.ogDescription,
                genres = manga.ogGenre.orEmpty(),
                status = manga.ogStatus,
                thumbnailUrl = manga.ogThumbnailUrl,
                chapters = chapters.sortedBy { it.sourceOrder }.map {
                    IndexChapter(it.url, it.name, it.chapterNumber, it.scanlator, it.dateUpload)
                },
            )
            val text = json.encodeToString(Index.serializer(), index)
            // A new file each time (some storage keeps the old file's tail when one is written over)
            dir.findFile("$NAME.tmp")?.delete()
            val tmp = dir.createFile("$NAME.tmp") ?: return
            tmp.openOutputStream().use { it.write(text.toByteArray()) }
            dir.findFile(NAME)?.delete()
            tmp.renameTo(NAME)
        }.onFailure { logcat(LogPriority.WARN, it) { "Couldn't write $NAME for ${manga.title}" } }
    }

    /** The details and chapters saved in the title's index.json, or null when there's none (or it's another title's) */
    fun read(manga: Manga, source: Source): SMangaUpdate? {
        return runCatching {
            val file = provider.findMangaDir(manga.ogTitle, source)?.findFile(NAME) ?: return null
            val text = file.openInputStream().use { it.readBytes().decodeToString() }
            val index = json.decodeFromString(Index.serializer(), text)
            if (index.source != manga.source || index.url != manga.url) return null
            val details = SManga.create().apply {
                url = index.url
                title = index.title
                author = index.author
                artist = index.artist
                description = index.description
                genre = index.genres.joinToString(", ").ifEmpty { null }
                status = index.status.toInt()
                thumbnail_url = index.thumbnailUrl
                initialized = true
            }
            val chapters = index.chapters.map { c ->
                SChapter.create().apply {
                    url = c.url
                    name = c.name
                    chapter_number = c.number.toFloat()
                    scanlator = c.scanlator
                    date_upload = c.dateUpload
                }
            }
            SMangaUpdate(details, chapters)
        }.onFailure { logcat(LogPriority.WARN, it) { "Couldn't read $NAME for ${manga.title}" } }.getOrNull()
    }
}
