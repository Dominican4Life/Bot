package eu.kanade.tachiyomi.jssource.lab.heal

import android.app.Application
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** KMK (r116): `js_sources/<key>/fingerprints.json` */
@Serializable
data class PickFingerprintFile(
    val version: Int = 1,
    /** label ("item", "title", "cover", "bigCover"…) → its fingerprint */
    val picks: Map<String, Fingerprint> = emptyMap(),
)

/**
 * KMK (r116): the fingerprints of a source's labelled picks, one per label, saved by the builder whenever a pick is set
 * or the source is written, read by the Test Run's self-healing. Stored in `filesDir/js_sources/<key>/fingerprints.json`
 * (in backups with the other Source Lab files). Small file IO: call off the main thread.
 */
object PickFingerprints {

    const val FILE_NAME = "fingerprints.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val lock = Any()

    private fun file(key: String): File? {
        if (!JsSourceManager.KEY_PATTERN.matches(key)) return null
        val filesDir = Injekt.get<Application>().filesDir
        return File(filesDir, "js_sources/$key/$FILE_NAME")
    }

    private fun read(file: File): PickFingerprintFile = runCatching {
        if (file.isFile) json.decodeFromString(PickFingerprintFile.serializer(), file.readText()) else PickFingerprintFile()
    }.getOrElse {
        AppLog.add('W', "lab", "Couldn't read ${file.parentFile?.name}/$FILE_NAME: ${it.message}")
        PickFingerprintFile()
    }

    /**
     * Fingerprints of a source whose folder doesn't exist yet (a new source before its first write), by key: kept in
     * memory and written with the next save once the folder is there (never makes the folder: JsSourceManager reads
     * every folder under js_sources as a source)
     */
    private val pending = HashMap<String, Map<String, Fingerprint>>()

    /** Saves (replaces) [label]'s fingerprint of source [key] */
    fun save(key: String, label: String, fp: Fingerprint) {
        if (label.isBlank()) return
        val file = file(key) ?: return
        synchronized(lock) {
            pending[key] = pending[key].orEmpty() + (label to fp)
            flush(key, file)
        }
    }

    /** Writes [key]'s pending fingerprints once its folder exists (under [lock]) */
    private fun flush(key: String, file: File) {
        val waiting = pending[key] ?: return
        if (file.parentFile?.isDirectory != true) return
        runCatching {
            val now = read(file)
            val next = now.copy(picks = now.picks + waiting)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(PickFingerprintFile.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            pending.remove(key)
        }.onFailure { AppLog.add('W', "lab", "Couldn't save the pick fingerprints of $key: ${it.message}") }
    }

    /** [label]'s fingerprint of source [key], null when none was saved */
    fun load(key: String, label: String): Fingerprint? = loadAll(key)[label]

    /** Every saved fingerprint of source [key], by label */
    fun loadAll(key: String): Map<String, Fingerprint> {
        val file = file(key) ?: return emptyMap()
        return synchronized(lock) {
            flush(key, file)
            read(file).picks + pending[key].orEmpty()
        }
    }

    /** Forgets [label] (all labels when null) */
    fun remove(key: String, label: String? = null) {
        val file = file(key) ?: return
        synchronized(lock) {
            if (label == null) pending.remove(key) else pending[key]?.let { pending[key] = it - label }
            runCatching {
                if (label == null) {
                    file.delete()
                } else {
                    val now = read(file)
                    if (label in now.picks) {
                        file.writeText(json.encodeToString(PickFingerprintFile.serializer(), now.copy(picks = now.picks - label)))
                    }
                }
            }
        }
    }
}
