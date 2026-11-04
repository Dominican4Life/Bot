package eu.kanade.tachiyomi.data.backup

import android.content.Context
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.anime.AnimeCustomInfo
import eu.kanade.tachiyomi.data.anime.AnimeRepository
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCustomCover
import eu.kanade.tachiyomi.data.backup.models.BackupFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.JsStorage
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.LabStore
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r115): the parts of a backup that are files in the app's own folder (`filesDir`), kept as (relative path,
 * bytes), and custom covers kept per title (source + url).
 *
 * - Source Lab files: per saved JS source `js_sources/<key>/history/`, `testruns/`, `lint.json`, `health.json`,
 *   `explain.json`, `fingerprints.json` (r116: the builder's pick fingerprints), and the whole `sourcelab/` folder
 *   (imported rules, fingerprints, templates with their `current` pointers, rule overrides). The source's own code
 *   and `meta.json` are not here (they are the "JS sources" part).
 * - JS source storage: `js_storage/<key>/` of saved sources (never the Code editor's `<key>.editor` test copies).
 * - Custom covers: manga ([CoverCache] custom cover files) and anime (`anime_covers/`).
 *
 * A restore only writes a path that [allowedSourceLab] / [allowedJsStorage] accepts (no "..", no absolute path,
 * only under those folders, and the resolved file still inside them), and only for JS sources on this device.
 * Files over [MAX_FILE_BYTES] are skipped both ways. Callers run on IO.
 */
class BackupFiles(
    private val context: Context,
) {

    private val filesDir: File get() = context.filesDir

    // ---------- create ----------

    /** Source Lab files of every saved JS source, and sourcelab/ */
    fun createSourceLabFiles(): List<BackupFile> = runCatching {
        val keys = savedKeys()
        val out = mutableListOf<BackupFile>()
        keys.forEach { key ->
            val dir = File(filesDir, "$JS_SOURCES/$key")
            SOURCE_DIRS.forEach { name -> collect(File(dir, name), out) }
            SOURCE_FILES.forEach { name -> collect(File(dir, name), out) }
        }
        // KMK (r119): the builder's remembered directory reads (sourcelab/dir_cache) are a cache, not kept in backups
        val labFiles = mutableListOf<BackupFile>()
        collect(File(filesDir, SOURCE_LAB), labFiles)
        labFiles.filterNotTo(out) { it.path.replace('\\', '/').contains("$SOURCE_LAB/dir_cache/") }
        log("Source Lab files: ${out.size} (${keys.size} sources, ${kb(out.sumOf { it.bytes.size.toLong() })})")
        out
    }.getOrElse {
        AppLog.add('W', "Backup", "Source Lab files left out: ${it.message}")
        emptyList()
    }

    /** What saved JS sources keep for themselves (js_storage/<key>/), pending writes flushed first */
    fun createJsStorageFiles(): List<BackupFile> = runCatching {
        // What runtimes changed in the last second is still only in memory
        runCatching { JsStorage.flushAllNow() }
        val out = mutableListOf<BackupFile>()
        savedKeys().forEach { key -> collect(File(filesDir, "$JS_STORAGE/$key"), out) }
        log("JS source storage: ${out.size} files (${kb(out.sumOf { it.bytes.size.toLong() })})")
        out
    }.getOrElse {
        AppLog.add('W', "Backup", "JS source storage left out: ${it.message}")
        emptyList()
    }

    /**
     * Custom covers of [manga] (only those also in the backup: [keep] by source + url) and of [anime] (the backup's
     * anime). At most [MAX_COVERS_BYTES] in all, so a huge library can't make the backup run out of memory.
     */
    suspend fun createCustomCovers(
        manga: List<Manga>,
        keep: Set<Pair<Long, String>>,
        anime: List<BackupAnime>,
    ): List<BackupCustomCover> {
        val out = mutableListOf<BackupCustomCover>()
        var total = 0L
        var skipped = 0
        fun add(source: Long, url: String, isAnime: Boolean, file: File) {
            val length = file.length()
            if (length <= 0L || length > MAX_FILE_BYTES || total + length > MAX_COVERS_BYTES) {
                skipped++
                return
            }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
            total += bytes.size
            out.add(BackupCustomCover(source = source, url = url, anime = isAnime, bytes = bytes))
        }
        runCatching {
            val coverCache = Injekt.get<CoverCache>()
            manga.distinctBy { it.id }.forEach { m ->
                if ((m.source to m.url) !in keep) return@forEach
                val file = coverCache.getCustomCoverFile(m.id)
                if (file.isFile) add(m.source, m.url, false, file)
            }
        }.onFailure { AppLog.add('W', "Backup", "Manga custom covers left out: ${it.message}") }
        runCatching {
            if (anime.isEmpty()) return@runCatching
            val repository = Injekt.get<AnimeRepository>()
            anime.distinctBy { it.source to it.url }.forEach { a ->
                val id = repository.getAnimeByUrl(a.url, a.source)?.id ?: return@forEach
                animeCoverFile(id, repository.getCustomInfo(id))?.let { add(a.source, a.url, true, it) }
            }
        }.onFailure { AppLog.add('W', "Backup", "Anime custom covers left out: ${it.message}") }
        val skippedText = if (skipped > 0) ", $skipped too big or over the limit" else ""
        log("Custom covers: ${out.size} (${kb(total)}$skippedText)")
        return out
    }

    /** The anime's custom cover file: the one its Edit info points at, else the newest `<id>_*` one */
    private fun animeCoverFile(animeId: Long, info: AnimeCustomInfo?): File? {
        val dir = File(filesDir, ANIME_COVERS)
        val pointed = info?.thumbnailUrl?.takeIf { it.startsWith("file://") }
            ?.let { File(it.removePrefix("file://")) }
            ?.takeIf { it.isFile && inside(it, dir) }
        if (pointed != null) return pointed
        // Without Edit info pointing at a file, the picture isn't shown; nothing to back up
        if (info?.thumbnailUrl?.startsWith("file://") != true) return null
        return dir.listFiles { f -> f.isFile && f.name.startsWith("${animeId}_") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun savedKeys(): List<String> = Injekt.get<JsSourceManager>().savedFlow.value
        .map { it.key }
        .filter { JsSourceManager.KEY_PATTERN.matches(it) }
        .distinct()

    /** Every file under [root] (or [root] itself), as paths relative to filesDir */
    private fun collect(root: File, out: MutableList<BackupFile>) {
        if (!root.exists()) return
        val base = filesDir.canonicalFile
        root.walkTopDown()
            .filter { it.isFile && it.length() in 0..MAX_FILE_BYTES }
            .forEach { file ->
                runCatching {
                    val canonical = file.canonicalFile
                    if (!canonical.path.startsWith(base.path + File.separator)) return@runCatching
                    val relative = canonical.relativeTo(base).invariantSeparatorsPath
                    out.add(BackupFile(path = relative, bytes = canonical.readBytes()))
                }
            }
    }

    // ---------- restore ----------

    /**
     * Writes the backup's Source Lab files. Files of a source that came back from this backup ([restoredKeys]: this
     * device had no copy or an older one) replace what's here; for other sources, and for sourcelab/, only files
     * missing here are written (this device's own history, runs and imports stay). Only for JS sources on this
     * device after the restore.
     *
     * @return files written and files skipped
     */
    fun restoreSourceLabFiles(files: List<BackupFile>, restoredKeys: Set<String>): Pair<Int, Int> {
        if (files.isEmpty()) return 0 to 0
        val here = savedKeys().toSet()
        var written = 0
        var skipped = 0
        // A source whose copy came back from the backup: its history and runs are the backup's, whole
        val replaced = mutableSetOf<String>()
        files.forEach { entry ->
            val path = normalized(entry.path)
            val allowed = path?.let { allowedSourceLab(it) }
            if (path == null || allowed == null || entry.bytes.size > MAX_FILE_BYTES) {
                skipped++
                return@forEach
            }
            val key = allowed
            if (key.isNotEmpty() && key !in here) {
                skipped++
                return@forEach
            }
            val target = File(filesDir, path)
            if (!inside(target, filesDir)) {
                skipped++
                return@forEach
            }
            val replace = key.isNotEmpty() && key in restoredKeys
            if (replace && replaced.add(key)) {
                // Folders the backup has for this source start empty, so no version from the older copy is mixed in
                SOURCE_DIRS.forEach { name ->
                    if (files.any { normalized(it.path)?.startsWith("$JS_SOURCES/$key/$name/") == true }) {
                        runCatching { File(filesDir, "$JS_SOURCES/$key/$name").deleteRecursively() }
                    }
                }
            }
            if (!replace && target.exists()) {
                skipped++
                return@forEach
            }
            if (write(target, entry.bytes)) written++ else skipped++
        }
        runCatching { LabStore.invalidate() }
        log("Source Lab files: $written written, $skipped kept or skipped (${files.size} in the backup)")
        return written to skipped
    }

    /**
     * Writes the backup's JS source storage for sources on this device. `storage.json` goes through [JsStorage]
     * (the copy running sources share), so a source running now sees it and doesn't write its old copy back. A
     * source that kept this device's own (same or newer) copy keeps its storage when it has one.
     */
    fun restoreJsStorageFiles(files: List<BackupFile>, restoredKeys: Set<String>): Pair<Int, Int> {
        if (files.isEmpty()) return 0 to 0
        val here = savedKeys().toSet()
        var written = 0
        var skipped = 0
        files.forEach { entry ->
            val path = normalized(entry.path)
            val key = path?.let { allowedJsStorage(it) }
            if (path == null || key == null || key !in here || entry.bytes.size > MAX_FILE_BYTES) {
                skipped++
                return@forEach
            }
            val target = File(filesDir, path)
            val dir = File(filesDir, "$JS_STORAGE/$key")
            if (!inside(target, dir)) {
                skipped++
                return@forEach
            }
            if (key !in restoredKeys && target.exists()) {
                skipped++
                return@forEach
            }
            val ok = if (target.name == STORAGE_FILE && target.parentFile?.canonicalPath == dir.canonicalPath) {
                runCatching {
                    val values = jsJson.parseToJsonElement(entry.bytes.decodeToString()) as JsonObject
                    val store = JsStorage.of(dir)
                    store.replaceAll(values)
                    store.flush()
                    true
                }.getOrDefault(false)
            } else {
                write(target, entry.bytes)
            }
            if (ok) written++ else skipped++
        }
        log("JS source storage: $written written, $skipped kept or skipped (${files.size} in the backup)")
        return written to skipped
    }

    /**
     * Puts back custom covers of titles that are on this device (matched by source + url). A title that already
     * has its own custom cover here keeps it.
     */
    suspend fun restoreCustomCovers(covers: List<BackupCustomCover>): Pair<Int, Int> {
        if (covers.isEmpty()) return 0 to 0
        var written = 0
        var skipped = 0
        val getManga = Injekt.get<GetManga>()
        val coverCache = Injekt.get<CoverCache>()
        val updateManga = Injekt.get<UpdateManga>()
        val repository = Injekt.get<AnimeRepository>()
        covers.forEach { cover ->
            if (cover.bytes.isEmpty() || cover.bytes.size > MAX_FILE_BYTES) {
                skipped++
                return@forEach
            }
            val ok = runCatching {
                if (cover.anime) {
                    val anime = repository.getAnimeByUrl(cover.url, cover.source) ?: return@runCatching false
                    val info = repository.getCustomInfo(anime.id)
                    if (animeCoverFile(anime.id, info) != null) return@runCatching false
                    val dir = File(filesDir, ANIME_COVERS).apply { mkdirs() }
                    // Like the anime page's "custom cover": a new name each time (the image cache keys on it)
                    dir.listFiles { f -> f.name.startsWith("${anime.id}_") }?.forEach { it.delete() }
                    val file = File(dir, "${anime.id}_${System.currentTimeMillis()}.jpg")
                    if (!write(file, cover.bytes)) return@runCatching false
                    repository.setCustomInfo(
                        (info ?: AnimeCustomInfo(anime.id)).copy(thumbnailUrl = "file://" + file.absolutePath),
                    )
                    MangaCover.coverRatioMap.remove(-anime.id)
                    true
                } else {
                    val manga = getManga.await(cover.url, cover.source) ?: return@runCatching false
                    val file = coverCache.getCustomCoverFile(manga.id)
                    if (file.exists()) return@runCatching false
                    if (!write(file, cover.bytes)) return@runCatching false
                    updateManga.awaitUpdateCoverLastModified(manga.id)
                    true
                }
            }.getOrDefault(false)
            if (ok) written++ else skipped++
        }
        log("Custom covers: $written put back, $skipped kept or not found (${covers.size} in the backup)")
        return written to skipped
    }

    private fun write(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".restoring")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                error("rename failed")
            }
        }
        true
    }.getOrElse {
        AppLog.add('W', "Restore", "Couldn't write ${target.name}: ${it.message}")
        false
    }

    private fun inside(file: File, dir: File): Boolean = runCatching {
        file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun log(message: String) = AppLog.add('I', "Backup", message)

    private fun kb(bytes: Long) = "${(bytes + 1023) / 1024} KB"

    companion object {
        private const val JS_SOURCES = "js_sources"
        private const val JS_STORAGE = "js_storage"
        private const val SOURCE_LAB = "sourcelab"
        private const val ANIME_COVERS = "anime_covers"
        private const val STORAGE_FILE = "storage.json"

        /** A source's Source Lab folders and files (relative to js_sources/<key>/) */
        private val SOURCE_DIRS = listOf("history", "testruns")
        // KMK (r116): fingerprints.json = the builder's pick fingerprints (PickHealer's self-healing)
        private val SOURCE_FILES = listOf("lint.json", "health.json", "explain.json", "fingerprints.json")

        /** Larger files (a cover, a test run) are skipped */
        const val MAX_FILE_BYTES = 5L * 1024 * 1024

        /** All custom covers together at most */
        private const val MAX_COVERS_BYTES = 100L * 1024 * 1024

        private val SEGMENT = Regex("[A-Za-z0-9._-]{1,128}")

        /**
         * [path] with "/" separators when every part is a plain name (no "", ".", "..", no drive or leading "/"),
         * else null
         */
        internal fun normalized(path: String): String? {
            if (path.isEmpty() || path.length > 512 || path.startsWith("/") || path.contains('\\')) return null
            val parts = path.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." || !SEGMENT.matches(it) }) return null
            return parts.joinToString("/")
        }

        /**
         * The JS source key a Source Lab path belongs to ("" for sourcelab/), or null when the path isn't one a
         * restore may write
         */
        internal fun allowedSourceLab(path: String): String? {
            val parts = path.split('/')
            if (parts.size >= 2 && parts[0] == SOURCE_LAB) return ""
            if (parts.size < 3 || parts[0] != JS_SOURCES) return null
            val key = parts[1]
            if (!JsSourceManager.KEY_PATTERN.matches(key)) return null
            return when {
                parts.size == 3 && parts[2] in SOURCE_FILES -> key
                parts.size >= 4 && parts[2] in SOURCE_DIRS -> key
                else -> null
            }
        }

        /** The JS source key of a js_storage/<key>/… path, or null */
        internal fun allowedJsStorage(path: String): String? {
            val parts = path.split('/')
            if (parts.size < 3 || parts[0] != JS_STORAGE) return null
            val key = parts[1]
            return key.takeIf { JsSourceManager.KEY_PATTERN.matches(it) }
        }
    }
}
