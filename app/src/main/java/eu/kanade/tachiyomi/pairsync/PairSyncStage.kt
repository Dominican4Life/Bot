package eu.kanade.tachiyomi.pairsync

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** KMK (r93): staged data from a sync, waiting to be applied (or being applied) on this device */
data class PendingApply(val peerName: String, val receivedAt: Long, val bytes: Long)

/** KMK (r93): an APK the other device sent (saved in the cache, see [PairSyncData.saveApk]), kept for the apply */
@Serializable
internal class StagedApk(
    val pkgName: String,
    val name: String = "",
    val isAnime: Boolean = false,
    val path: String,
    val signatureHash: String = "",
    val versionCode: Long = 0L,
)

/**
 * KMK (r93): what one sync staged here and how far its apply got. Written next to the staged files
 * (journal.json); [ready] once the other device's library payload is here in full (before that the folder is
 * dropped). The apply marks [libraryDone] after the merge; each staged cover / download is deleted once it's moved
 * into place, so what's left in the folder is what's left to do.
 */
@Serializable
internal class StageJournal(
    val id: String,
    val peerAddress: String,
    val peerName: String,
    val via: String = "bluetooth",
    val startTime: Long = 0L,
    var receivedAt: Long = 0L,
    var ready: Boolean = false,
    var hasBackup: Boolean = false,
    var apks: List<StagedApk> = emptyList(),
    // The repos merged while connected (the device pressing Sync merges them before building its export)
    var reposMerged: Boolean = false,
    var reposAdded: Int = 0,
    var reposManga: Boolean = false,
    var reposAnime: Boolean = false,
    // What this device sent
    var sentManga: Int = 0,
    var sentAnime: Int = 0,
    var sentHistoryEntries: Int = 0,
    var sentSources: Int = 0,
    var sentRepos: Int = 0,
    var sentExtensions: Int = 0,
    var sentApks: Int = 0,
    var sentCovers: Int = 0,
    var sentItems: Int = 0,
    var sentExtraBytes: Long = 0L,
    /** Why covers and downloads didn't go (or stopped), for the result card */
    var extrasSkipped: String? = null,
    var noRoom: Int = 0,
    /** The covers and downloads staged here, in order (item n is in items/n, complete when items/n/ok exists) */
    var items: List<ExtraItem> = emptyList(),
    /** Bytes moved over the link (both ways) and the staged bytes */
    var bytes: Long = 0L,
    var stagedBytes: Long = 0L,
    // The apply
    var attempts: Int = 0,
    var libraryDone: Boolean = false,
    var manga: Int = 0,
    var anime: Int = 0,
    var historyEntries: Int = 0,
    var sourcesAdded: Int = 0,
    var errors: Int = 0,
    var appliedReposAdded: Int = 0,
)

/** KMK (r93): one sync's staging folder (filesDir/pair_sync_stage/<id>) and its journal */
internal class Stage(val dir: File, val journal: StageJournal) {
    val payloadFile: File get() = File(dir, "payload.bin")
    val backupFile: File get() = File(dir, "backup.bin")

    fun itemDir(index: Int): File = File(dir, "items/$index")

    /**
     * Written to a temporary name, then renamed (a kill while writing leaves the last good journal). KMK (r93): true
     * when it's on disk; a ready journal that couldn't be written is kept in memory (see [PairSyncStage.unsaved]), so
     * this run still applies it and doesn't clean it as unready
     */
    fun save(): Boolean {
        val saved = runCatching {
            dir.mkdirs()
            val tmp = File(dir, PairSyncStage.JOURNAL_TMP)
            tmp.writeText(PairSyncStage.json.encodeToString(StageJournal.serializer(), journal))
            if (!tmp.renameTo(File(dir, PairSyncStage.JOURNAL))) {
                File(dir, PairSyncStage.JOURNAL).delete()
                tmp.renameTo(File(dir, PairSyncStage.JOURNAL))
            } else {
                true
            }
        }.getOrDefault(false)
        if (saved) {
            PairSyncStage.unsaved.remove(journal.id)
        } else if (journal.ready) {
            PairSyncStage.unsaved[journal.id] = this
        }
        return saved
    }

    fun delete() {
        PairSyncStage.unsaved.remove(journal.id)
        runCatching { dir.deleteRecursively() }
    }
}

/** KMK (r93): the staging folders (see [Stage]) */
internal object PairSyncStage {
    private const val DIR = "pair_sync_stage"
    const val JOURNAL = "journal.json"
    const val JOURNAL_TMP = "journal.json.tmp"

    /** KMK (r93): ready stages whose journal couldn't be written (by folder name = journal id), see [Stage.save] */
    val unsaved = ConcurrentHashMap<String, Stage>()

    /** An apply that failed (or was killed) this many times is dropped, so a bad stage can't fail every start */
    const val MAX_ATTEMPTS = 3

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val random = SecureRandom()

    fun root(context: Context): File = File(context.filesDir, DIR)

    fun create(context: Context, peerAddress: String, peerName: String, via: SyncTransport, startTime: Long): Stage {
        val id = "${System.currentTimeMillis()}_${random.nextInt(1_000_000)}"
        val dir = File(root(context), id)
        dir.mkdirs()
        val stage = Stage(dir, StageJournal(id, peerAddress, peerName, via.key, startTime))
        stage.save()
        return stage
    }

    /**
     * The journal in [dir] (KMK (r93): a ready one kept in memory first; the temporary copy when a kill came between
     * the two renames of [Stage.save]); null when there's none or it can't be read
     */
    private fun load(dir: File): Stage? {
        unsaved[dir.name]?.let { return it }
        return runCatching {
            val file = File(dir, JOURNAL).takeIf { it.isFile } ?: File(dir, JOURNAL_TMP).takeIf { it.isFile }
            val text = file?.readText() ?: return null
            Stage(dir, json.decodeFromString(StageJournal.serializer(), text))
        }.getOrNull()
    }

    /** The stages whose library payload came in full, oldest first */
    fun ready(context: Context): List<Stage> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { dir -> load(dir)?.takeIf { it.journal.ready } }

    /**
     * Deletes the stages that never got their library payload (a sync that stopped early), except [keep]. KMK (r93):
     * one whose journal is there but can't be read is logged (it may have held a whole library)
     */
    fun cleanUnready(context: Context, keep: File? = null) {
        runCatching {
            root(context).listFiles().orEmpty()
                .filter { it.isDirectory && it != keep }
                .forEach { dir ->
                    val stage = load(dir)
                    if (stage?.journal?.ready == true) return@forEach
                    if (stage == null && (File(dir, JOURNAL).isFile || File(dir, JOURNAL_TMP).isFile)) {
                        val size = File(dir, "payload.bin").length() + File(dir, "backup.bin").length()
                        PairSync.log(
                            "A sync's staged data (${PairSyncData.sizeText(size)}) couldn't be read: deleted, " +
                                "sync again to get it",
                        )
                    }
                    dir.deleteRecursively()
                }
        }
    }

    /** What [ready] holds, in one line for the pop-up; null when nothing waits */
    fun pending(context: Context): PendingApply? {
        val stages = runCatching { ready(context) }.getOrDefault(emptyList())
        val first = stages.firstOrNull() ?: return null
        return PendingApply(
            peerName = first.journal.peerName,
            receivedAt = first.journal.receivedAt,
            bytes = stages.sumOf { it.journal.stagedBytes },
        )
    }
}
