package eu.kanade.tachiyomi.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * KMK (r97): A byte ring in a memory-mapped file (design §6). The kernel owns the pages, so what is written reaches
 * the file even when the process is killed, without an fsync on the writing thread. Used for the log mirror and the
 * trace rings.
 *
 * Layout: a 64-byte header (magic `KMKR`, version, write offset, wrap flag, session start, logical capacity) and
 * the data. A record is never split: when it doesn't fit before the end, the rest is padded with zero bytes and the
 * record goes to the start. Readers skip the padding. A `MappedByteBuffer` can't be unmapped before API 33, so a
 * ring is opened once per process and emptied through its header ([clear]); the logical [capacity] may be lowered
 * or raised up to the mapped size ([setCapacity]) without a remap.
 */
internal class DiagRing private constructor(
    val file: File,
    private val map: MappedByteBuffer,
    /** Bytes mapped after the header */
    val mapped: Int,
    initialCapacity: Int,
) {

    private val lock = Any()

    /** Set by every write; [force] does nothing while it is clear (no msync of clean pages every 5 s) */
    @Volatile
    private var dirty = false

    /** Logical data size in use (≤ [mapped]) */
    @Volatile
    var capacity: Int = initialCapacity.coerceIn(1, mapped)
        private set

    @Volatile
    var writeOffset: Int = 0
        private set

    @Volatile
    var wrapped: Boolean = false
        private set

    /** The run (AppLog.sessionStart) that last wrote the header */
    @Volatile
    var session: Long = 0L
        private set

    /** Written bytes since the last clear, capped at the capacity */
    val used: Int get() = if (wrapped) capacity else writeOffset

    /** Appends [len] bytes of [bytes] from [off]; a record larger than the capacity is dropped. */
    fun append(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (len <= 0 || len > capacity) return
        synchronized(lock) {
            var at = writeOffset
            if (at + len > capacity) {
                // Pad the tail so the record stays whole, then wrap
                var i = at
                while (i < capacity) {
                    map.put(HEADER + i, 0.toByte())
                    i++
                }
                at = 0
                wrapped = true
            }
            // Bulk copy through a duplicate positioned at the record (the shared buffer's position stays 0)
            val view = map.duplicate()
            view.position(HEADER + at)
            view.put(bytes, off, len)
            at += len
            if (at >= capacity) {
                at = 0
                wrapped = true
            }
            writeOffset = at
            map.putInt(OFF_WRITE, at)
            map.putInt(OFF_WRAPPED, if (wrapped) 1 else 0)
            dirty = true
        }
    }

    /** Every byte written, oldest first (padding removed by [forEachLine]; raw here). */
    fun readAll(): ByteArray = synchronized(lock) {
        val view = map.duplicate()
        if (!wrapped) {
            val out = ByteArray(writeOffset)
            view.position(HEADER)
            view.get(out, 0, writeOffset)
            out
        } else {
            val out = ByteArray(capacity)
            val tail = capacity - writeOffset
            view.position(HEADER + writeOffset)
            view.get(out, 0, tail)
            view.position(HEADER)
            view.get(out, tail, writeOffset)
            out
        }
    }

    /** [len] bytes around ring offset [offset] (as [writeOffset] was at some moment), for the Health excerpts */
    fun readAround(offset: Int, len: Int): ByteArray = synchronized(lock) {
        val half = len / 2
        val start = (offset - half).coerceAtLeast(0)
        val end = (offset + half).coerceAtMost(if (wrapped) capacity else writeOffset)
        if (end <= start) return ByteArray(0)
        val out = ByteArray(end - start)
        val view = map.duplicate()
        view.position(HEADER + start)
        view.get(out)
        out
    }

    /** Calls [block] for each whole line (UTF-8, without the newline), oldest first; padding and partial lines skipped */
    fun forEachLine(block: (String) -> Unit) {
        val all = readAll()
        var start = 0
        // A wrapped ring starts in the middle of a record: skip to the first newline
        if (wrapped) {
            while (start < all.size && all[start] != NL) start++
            start++
        }
        var i = start
        while (i < all.size) {
            if (all[i] == NL) {
                if (i > start && all[start] != 0.toByte()) block(String(all, start, i - start, Charsets.UTF_8))
                start = i + 1
            } else if (all[i] == 0.toByte() && i == start) {
                // Padding run
                while (i < all.size && all[i] == 0.toByte()) i++
                start = i
                continue
            }
            i++
        }
    }

    fun lines(): List<String> = buildList { forEachLine { add(it) } }

    /** The newest [n] lines, oldest first */
    fun tail(n: Int): List<String> {
        val all = lines()
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    /** Pushes dirty pages to the file (only when something was written since the last call). IO worker only */
    fun force() {
        if (!dirty) return
        dirty = false
        runCatching { map.force() }
    }

    /** Empties the ring (header only; the data is left as it is) */
    fun clear() {
        synchronized(lock) {
            writeOffset = 0
            wrapped = false
            map.putInt(OFF_WRITE, 0)
            map.putInt(OFF_WRAPPED, 0)
            dirty = true
        }
    }

    /** Raises or lowers the logical capacity (the ring is cleared when it shrinks below what is written) */
    fun setCapacity(bytes: Long) {
        synchronized(lock) {
            val next = bytes.coerceIn(1L, mapped.toLong()).toInt()
            if (next == capacity) return
            val shrinking = next < capacity
            capacity = next
            map.putInt(OFF_CAPACITY, next)
            dirty = true
            if (shrinking && (wrapped || writeOffset > next)) clear()
        }
    }

    fun writeSession(sessionStart: Long) {
        synchronized(lock) {
            session = sessionStart
            map.putLong(OFF_SESSION, sessionStart)
            dirty = true
        }
    }

    companion object {
        const val HEADER = 64
        private const val MAGIC = 0x4B4D4B52 // "KMKR"
        private const val VERSION = 1
        private const val OFF_MAGIC = 0
        private const val OFF_VERSION = 4
        private const val OFF_WRITE = 8
        private const val OFF_WRAPPED = 12
        private const val OFF_SESSION = 16
        private const val OFF_CAPACITY = 24
        private const val NL = '\n'.code.toByte()

        /**
         * Maps [file] with [bytes] of data after the header (the file grows to that size). A header whose magic or
         * version doesn't match renames the file to `<name>.old` and starts fresh. Returns null when the file
         * can't be mapped (the ring is then simply absent).
         */
        fun open(file: File, bytes: Long, capacity: Long = bytes, sessionStart: Long): DiagRing? = runCatching {
            file.parentFile?.mkdirs()
            val total = HEADER + bytes
            var fresh = !file.isFile || file.length() != total
            if (!fresh) {
                RandomAccessFile(file, "r").use { raf ->
                    val head = ByteArray(8)
                    raf.readFully(head)
                    val bb = ByteBuffer.wrap(head)
                    if (bb.getInt(0) != MAGIC || bb.getInt(4) != VERSION) fresh = true
                }
                if (fresh) {
                    val old = File(file.parentFile, file.name + ".old")
                    old.delete()
                    file.renameTo(old)
                }
            }
            val map = RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() != total) {
                    // Write real zeros instead of setLength(): a sparse file on a full disk fails later with SIGBUS
                    // on the first page touch; writing here fails with an IOException that runCatching turns into
                    // "no ring".
                    raf.setLength(0L)
                    val zeros = ByteArray(64 * 1024)
                    var left = total
                    while (left > 0L) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                    raf.fd.sync()
                }
                raf.channel.map(FileChannel.MapMode.READ_WRITE, 0L, total)
            }
            val ring = DiagRing(file, map, bytes.toInt(), capacity.toInt())
            if (fresh) {
                map.putInt(OFF_MAGIC, MAGIC)
                map.putInt(OFF_VERSION, VERSION)
                map.putInt(OFF_WRITE, 0)
                map.putInt(OFF_WRAPPED, 0)
                map.putInt(OFF_CAPACITY, ring.capacity)
            } else {
                val storedCapacity = map.getInt(OFF_CAPACITY).takeIf { it in 1..bytes.toInt() } ?: bytes.toInt()
                ring.capacity = storedCapacity
                ring.writeOffset = map.getInt(OFF_WRITE).coerceIn(0, storedCapacity - 1)
                ring.wrapped = map.getInt(OFF_WRAPPED) == 1
                ring.session = map.getLong(OFF_SESSION)
            }
            ring.writeSession(sessionStart)
            ring
        }.getOrNull()
    }
}
