package eu.kanade.tachiyomi.devicefiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** KMK: The ids of the two hidden sources behind Device files (the title is a folder, its files the chapters) */
object DeviceFilesIds {
    const val MANGA = 7_301_844_225_610_480_101L
    const val ANIME = 7_301_844_225_610_480_102L
}

private fun naturalByName(a: File, b: File) = a.name.compareToCaseInsensitiveNaturalOrder(b.name)

/**
 * KMK: Opens comics and pictures from the device's storage in the reader. A folder is the title; each comic archive in
 * it is a chapter, and the pictures lying loose in it are one more chapter. Not listed with the other sources.
 */
class DeviceFilesMangaSource : Source {
    override val id: Long = DeviceFilesIds.MANGA
    override val name: String = "Device files"
    override val supportsLatest: Boolean = false
    override val disableRelatedMangas: Boolean = true

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (fetchDetails) manga.initialized = true
        val list = if (fetchChapters) chaptersOf(File(manga.url)) else chapters
        return SMangaUpdate(manga, list)
    }

    // The reader loads these chapters straight from the files (see ChapterLoader)
    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("Device files are read from storage")

    override fun toString(): String = name

    companion object {
        fun imagesIn(dir: File): List<File> =
            dir.listFiles().orEmpty()
                .filter { it.isFile && FileTypes.kindOf(it.name) == FileKind.Image }
                .sortedWith(::naturalByName)

        fun chaptersOf(dir: File): List<SChapter> {
            val archives = dir.listFiles().orEmpty()
                .filter { it.isFile && FileTypes.kindOf(it.name) == FileKind.Archive }
                .sortedWith(::naturalByName)
            val chapters = archives.map { file ->
                SChapter.create().apply {
                    url = file.path
                    name = file.nameWithoutExtension
                    date_upload = file.lastModified()
                }
            }.toMutableList()
            if (imagesIn(dir).isNotEmpty()) {
                chapters += SChapter.create().apply {
                    url = dir.path
                    name = "Pictures in ${dir.name}"
                    date_upload = dir.lastModified()
                }
            }
            // Newest chapter first, the way sources list them
            return chapters.reversed()
        }
    }
}

/**
 * KMK: Plays videos from the device's storage in the app's player. A folder is the title and its videos the episodes,
 * so the player's episode list and next / previous go through the folder. Not listed with the other sources.
 */
class DeviceFilesAnimeSource : AnimeSource {
    override val id: Long = DeviceFilesIds.ANIME
    override val name: String = "Device files"

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply { initialized = true }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = episodesOf(File(anime.url))

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val file = File(episode.url)
        return listOf(
            Video(
                videoUrl = Uri.fromFile(file).toString(),
                videoTitle = file.extension.uppercase().ifBlank { "Video" },
                initialized = true,
            ),
        )
    }

    override fun toString(): String = name

    companion object {
        fun episodesOf(dir: File): List<SEpisode> =
            dir.listFiles().orEmpty()
                .filter { it.isFile && FileTypes.kindOf(it.name) == FileKind.Video }
                .sortedWith(::naturalByName)
                .mapIndexed { index, file ->
                    SEpisode.create().apply {
                        url = file.path
                        name = file.nameWithoutExtension
                        episode_number = (index + 1).toFloat()
                        date_upload = file.lastModified()
                    }
                }
                // Newest (highest number) first, the way sources list them
                .reversed()
    }
}

/** KMK: What happens when a file is tapped in Device files */
object DeviceFilesOpener {

    private val prefs get() = DeviceFilesPreferences.instance

    /** Opens [file]: videos in the player, comics and pictures in the reader, the rest in another app */
    suspend fun open(context: Context, file: File) {
        val kind = FileTypes.kindOf(file.name, file.isDirectory)
        val handled = runCatching {
            when {
                kind == FileKind.Video && prefs.playVideosInApp.get() -> play(context, file)
                (kind == FileKind.Archive || kind == FileKind.Image) && prefs.readComicsInApp.get() -> read(context, file)
                else -> false
            }
        }.onFailure { e ->
            withUIContext { context.toast("Couldn't open ${file.name} in the app: ${e.message}") }
        }.getOrDefault(false)
        if (!handled) withUIContext { openWith(context, file, chooser = false) }
    }

    private suspend fun play(context: Context, file: File): Boolean {
        val dir = file.parentFile ?: return false
        val repository = Injekt.get<AnimeRepository>()
        val (animeId, episodeId) = withIOContext {
            val sAnime = SAnime.create().apply {
                url = dir.path
                title = dir.name.ifBlank { "Device" }
                initialized = true
            }
            val anime = repository.networkToLocal(sAnime, DeviceFilesIds.ANIME)
            repository.syncEpisodes(anime, DeviceFilesAnimeSource.episodesOf(dir))
            val episode = repository.getEpisodes(anime.id).firstOrNull { it.url == file.path }
            anime.id to episode?.id
        }
        if (episodeId == null) return false
        withUIContext { context.startActivity(PlayerActivity.newIntent(context, animeId, episodeId)) }
        return true
    }

    private suspend fun read(context: Context, file: File): Boolean {
        val dir = file.parentFile ?: return false
        val source = Injekt.get<SourceManager>().get(DeviceFilesIds.MANGA) ?: DeviceFilesMangaSource()
        val (mangaId, chapterId, page) = withIOContext {
            val images = DeviceFilesMangaSource.imagesIn(dir)
            val sManga = SManga.create().apply {
                url = dir.path
                title = dir.name.ifBlank { "Device" }
                thumbnail_url = images.firstOrNull()?.let { Uri.fromFile(it).toString() }
                initialized = true
            }
            val manga = Injekt.get<NetworkToLocalManga>()(sManga.toDomainManga(DeviceFilesIds.MANGA))
            Injekt.get<SyncChaptersWithSource>().await(DeviceFilesMangaSource.chaptersOf(dir), manga, source)
            val isPicture = FileTypes.kindOf(file.name) == FileKind.Image
            val target = if (isPicture) dir.path else file.path
            val chapter = Injekt.get<GetChaptersByMangaId>().await(manga.id).firstOrNull { it.url == target }
            val page = if (isPicture) images.indexOfFirst { it.path == file.path }.takeIf { it >= 0 } else null
            Triple(manga.id, chapter?.id, page)
        }
        if (chapterId == null) return false
        withUIContext { context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId, page)) }
        return true
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)

    fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    /** Hands [file] to another app; [chooser] always asks which one */
    fun openWith(context: Context, file: File, chooser: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val launch = if (chooser) {
                Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                intent
            }
            context.startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            context.toast("No app can open ${file.name}")
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
