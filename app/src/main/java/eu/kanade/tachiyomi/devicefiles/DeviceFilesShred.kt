package eu.kanade.tachiyomi.devicefiles

import java.io.File
import java.io.RandomAccessFile
import java.util.Random

/**
 * KMK (r97): overwrites a file's bytes before it's deleted for good ("Overwrite before deleting" in the settings).
 *
 * Best effort only, and the setting says so: flash storage with wear levelling writes the new bytes to other cells
 * and the old ones stay until the controller erases them, and the FUSE layer over shared storage adds its own
 * caching. On the other hand the phone's storage is encrypted, so a normally deleted file is already unreadable
 * without the device's keys. This exists for owners who want the extra pass anyway.
 */
object DeviceFilesShred {

    private const val CHUNK = 1024 * 1024

    /**
     * Overwrites [file] with random bytes up to its length, flushes, truncates to zero and flushes again. Returns
     * false (and leaves the file for a normal delete) when the file is bigger than [DeviceFilesDelete.SHRED_MAX_BYTES],
     * isn't a regular file, or anything fails on the way.
     */
    fun shred(file: File): Boolean {
        if (!file.isFile || DeviceFilesTrash.isLink(file)) return false
        val length = file.length()
        if (length > DeviceFilesDelete.SHRED_MAX_BYTES) return false
        return runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val random = Random()
                val buffer = ByteArray(CHUNK)
                var written = 0L
                raf.seek(0)
                while (written < length) {
                    random.nextBytes(buffer)
                    val n = minOf(CHUNK.toLong(), length - written).toInt()
                    raf.write(buffer, 0, n)
                    written += n
                }
                raf.fd.sync()
                raf.setLength(0)
                raf.fd.sync()
            }
            true
        }.getOrDefault(false)
    }
}
