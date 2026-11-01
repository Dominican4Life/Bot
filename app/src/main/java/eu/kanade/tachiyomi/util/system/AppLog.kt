package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.os.Build
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.printer.Printer
import eu.kanade.tachiyomi.diagnostics.DiagBudget
import eu.kanade.tachiyomi.diagnostics.DiagCategory
import eu.kanade.tachiyomi.diagnostics.DiagPaths
import eu.kanade.tachiyomi.diagnostics.DiagRing
import eu.kanade.tachiyomi.diagnostics.LogCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.LogcatLogger
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Writer
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK: The app's own log, shown in the Logs screen (debug button on the navigation rail).
 *
 * Everything logged through logcat, XLog and the crash handler lands here with its time, level and tag. The file is
 * kept between about 1 and 2 MB, and the newest [MAX_LINES] lines of earlier runs are loaded (off the main thread) at
 * start, so they are still there after a restart (they show with a negative time, counted back from this run's start).
 */
object AppLog {

    const val MAX_LINES = 4000

    // KMK: file size limits; the file is cut back to TRIM_TO_BYTES once it passes MAX_BYTES
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val TRIM_TO_BYTES = 1L * 1024 * 1024
    private const val TRIM_CHECK_EVERY = 200

    /** KMK: longer messages are cut, so one huge line can't fill the file */
    private const val MAX_MESSAGE_CHARS = 16_000

    data class Line(
        val time: Long,
        /** V, D, I, W, E */
        val level: Char,
        val tag: String,
        val message: String,
    ) {
        val isProblem: Boolean get() = level == 'W' || level == 'E'
        val isInfo: Boolean get() = level == 'I' || isProblem
    }

    /** When this run started: line times are shown relative to it */
    var sessionStart: Long = System.currentTimeMillis()
        private set

    private val lines = ArrayDeque<Line>()
    private val _version = MutableStateFlow(0)

    /** Bumps whenever lines are added or cleared */
    val version: StateFlow<Int> = _version.asStateFlow()

    private var file: File? = null
    private val pending = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        sessionStart = System.currentTimeMillis()
        // KMK (r97) -->
        // Capture levels (the gate in add) are read before the first line; the ring mirror is mapped (a stat and an
        // mmap, no read) so every line of this run lands in it from the start line on
        LogCapture.init(context)
        ring = DiagRing.open(
            File(context.cacheDir, DiagPaths.LOG_RING),
            DiagBudget.LOG_RING_BYTES,
            sessionStart = sessionStart,
        )
        // KMK <--
        val dir = File(context.filesDir, "app_log").apply { mkdirs() }
        val logFile = File(dir, "log.txt")
        // KMK: only a stat on the main thread; reading and trimming happen in the writer below. The stat comes before
        // `file` is set, so an E line written straight to the file can't fall before startLength (it was read back
        // as an earlier run's line and shown twice)
        val startLength = runCatching { logFile.length() }.getOrDefault(0L)
        file = logFile
        scope.launch {
            // Earlier runs: only the file's tail is read, then the file is rewritten with it
            runCatching {
                if (startLength > 0L) {
                    // KMK: read without the lock (nothing changes the file before startLength until the rewrite
                    // below), so E lines aren't held up for the whole read
                    val old = readTail(logFile, startLength, MAX_BYTES).takeLast(MAX_LINES).mapNotNull(::decode)
                    synchronized(fileLock) {
                        // E lines written straight to the file since init
                        val sinceStart = readRange(logFile, startLength, logFile.length())
                        logFile.writeText(old.joinToString("") { encode(it) } + sinceStart)
                    }
                    synchronized(lines) {
                        // KMK: not when the log was cleared meanwhile
                        if (pendingClears.get() == 0) lines.addAll(0, old)
                        while (lines.size > MAX_LINES) lines.removeFirst()
                    }
                    _version.value++
                }
            }
            // KMK (r95): one stream kept open in append mode (O_APPEND, so a truncation by clear or trim doesn't
            // move it), flushed when the queue drains, after FLUSH_LINES lines, and at once for W/E lines. Before,
            // every line opened, wrote and closed the file.
            synchronized(fileLock) { openOut(logFile) }
            var writes = 0
            var unflushed = 0
            for (text in pending) {
                // KMK: Clear goes through this queue, so lines queued before it aren't appended after it
                if (text == CLEAR_MARK) {
                    runCatching { synchronized(fileLock) { flushOut(); logFile.writeText("") } }
                    writes = 0
                    unflushed = 0
                    pendingClears.decrementAndGet()
                    continue
                }
                runCatching {
                    synchronized(fileLock) {
                        val out = out ?: openOut(logFile)
                        out.write(text)
                        unflushed++
                        val problem = text.substringAfter('\t', "").firstOrNull().let { it == 'W' || it == 'E' }
                        if (problem || unflushed >= FLUSH_LINES || pending.isEmpty) {
                            out.flush()
                            unflushed = 0
                        }
                        // KMK: keep the file small during a run too (it used to grow until the next start)
                        if (++writes >= TRIM_CHECK_EVERY) {
                            writes = 0
                            out.flush()
                            unflushed = 0
                            if (logFile.length() > MAX_BYTES) trim(logFile)
                        }
                    }
                }
            }
        }
        add(
            'I',
            "app",
            "start ${context.packageName} ${appVersion(context)} on Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        )
        // KMK (r97) -->
        // One line that says the mirror ring works and what it kept (counted off the main thread)
        ring?.let { r ->
            scope.launch {
                val previous = runCatching {
                    var n = 0
                    r.forEachLine { line ->
                        val time = line.substringBefore('\t').toLongOrNull() ?: Long.MAX_VALUE
                        if (time < sessionStart) n++
                    }
                    n
                }.getOrDefault(0)
                add('I', "diag", "Log ring opened: ${"%,d".format(previous)} lines from the previous run")
            }
        } ?: add('W', "diag", "Log ring couldn't be opened: lines of this run won't survive a process kill")
        // KMK <--
    }

    // KMK (r97) -->
    /** The memory-mapped mirror of every line (survives a process kill); null when it couldn't be mapped */
    internal var ring: DiagRing? = null
        private set

    /**
     * A line with a structured tail: ` · k=v k=v` after the message (design §8). Values with spaces are URL-encoded
     * so the tail stays one token per field; null values are left out.
     */
    fun add(level: Char, tag: String, message: String, vararg fields: Pair<String, Any?>) {
        if (fields.isEmpty()) return add(level, tag, message)
        val tail = buildString {
            for ((k, v) in fields) {
                if (v == null) continue
                append(if (isEmpty()) " · " else " ")
                append(k).append('=')
                val s = v.toString()
                val spaced = s.any { it == ' ' || it == '\t' || it == '\n' }
                append(if (spaced) java.net.URLEncoder.encode(s, "UTF-8") else s)
            }
        }
        add(level, tag, message + tail)
    }

    /** A Verbose line whose text is only built while [category] is Verbose */
    internal inline fun v(category: DiagCategory, tag: String, message: () -> String) {
        if (LogCapture.isVerbose(category)) add('V', tag, message())
    }

    /** A Verbose line with a structured tail, both built only while [category] is Verbose */
    internal inline fun vFields(
        category: DiagCategory,
        tag: String,
        message: () -> Pair<String, Array<Pair<String, Any?>>>,
    ) {
        if (LogCapture.isVerbose(category)) {
            val (text, fields) = message()
            add('V', tag, text, *fields)
        }
    }

    /** `=== MARK: note ===` (or `=== MARK ===`), the owner's anchor in the log */
    fun mark(note: String?) {
        val text = note?.trim().orEmpty()
        add('I', "app", if (text.isEmpty()) "=== MARK ===" else "=== MARK: $text ===")
    }

    /** The `key=value` tail of a message (after the last ` · `), empty when there is none */
    fun fieldsOf(message: String): Map<String, String> {
        val at = message.lastIndexOf(" · ")
        if (at < 0) return emptyMap()
        val tail = message.substring(at + 3)
        if (tail.isEmpty() || tail.none { it == '=' }) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (token in tail.split(' ')) {
            val eq = token.indexOf('=')
            if (eq <= 0) return emptyMap() // not a field tail after all (a sentence with " · " in it)
            val key = token.substring(0, eq)
            if (key.any { !(it.isLetterOrDigit() || it == '_') }) return emptyMap()
            out[key] = token.substring(eq + 1)
        }
        return out
    }

    /** Lines newer than [time] (inclusive), oldest first */
    fun linesSince(time: Long): List<Line> = synchronized(lines) { lines.filter { it.time >= time } }

    /** The newest [n] lines that pass [filter], oldest first; walks back from the end and stops at [n] */
    fun lastLines(n: Int, filter: (Line) -> Boolean): List<Line> = synchronized(lines) {
        val out = ArrayList<Line>(n)
        var i = lines.size - 1
        while (i >= 0 && out.size < n) {
            val line = lines[i]
            if (filter(line)) out.add(line)
            i--
        }
        out.reverse()
        out
    }

    /** Lines in [from, to], oldest first */
    fun linesBetween(from: Long, to: Long): List<Line> = synchronized(lines) {
        lines.filter { it.time in from..to }
    }
    // KMK <--

    /** Lock for the log file: the writer, trimming, clear and the direct E lines */
    private val fileLock = Any()

    /** KMK (r95): the open append stream; null until the writer opened it. Use with [fileLock] held */
    private var out: Writer? = null

    /** Lines written since the last flush when the queue hadn't drained */
    private const val FLUSH_LINES = 64

    /** Opens the append stream. Call with [fileLock] held */
    private fun openOut(logFile: File): Writer {
        out?.let { runCatching { it.close() } }
        return BufferedWriter(
            OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8),
            16 * 1024,
        ).also { out = it }
    }

    /** Pushes buffered lines to the file. Call with [fileLock] held */
    private fun flushOut() {
        runCatching { out?.flush() }
    }

    /** Writes everything buffered so far; for paths where the process may end (crash, going to the background) */
    fun flush() {
        synchronized(fileLock) { flushOut() }
    }

    /** KMK: queued in [pending] by [clear]; never equal to an encoded line (those always hold tabs) */
    private const val CLEAR_MARK = "\u0000clear"

    /** KMK: clears queued but not done yet: E lines go through the queue meanwhile, so the clear can't wipe them */
    private val pendingClears = AtomicInteger(0)

    /** Keeps the newest [TRIM_TO_BYTES] of the file, starting at a whole line. Call with [fileLock] held */
    private fun trim(logFile: File) {
        flushOut()
        val length = logFile.length()
        val tail = readRange(logFile, (length - TRIM_TO_BYTES).coerceAtLeast(0L), length)
        val text = if (length > TRIM_TO_BYTES) tail.substringAfter('\n', "") else tail
        logFile.writeText(text)
    }

    /** The whole lines in the last [maxBytes] before [end] */
    private fun readTail(logFile: File, end: Long, maxBytes: Long): List<String> {
        val start = (end - maxBytes).coerceAtLeast(0L)
        val text = readRange(logFile, start, end)
        val whole = if (start > 0L) text.substringAfter('\n', "") else text
        return whole.split('\n').filter { it.isNotEmpty() }
    }

    private fun readRange(logFile: File, start: Long, end: Long): String {
        if (end <= start) return ""
        return RandomAccessFile(logFile, "r").use { raf ->
            val realEnd = end.coerceAtMost(raf.length())
            if (realEnd <= start) return@use ""
            val bytes = ByteArray((realEnd - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    fun add(level: Char, tag: String, message: String) {
        // KMK (r97): the capture gate (design §3.2): V only while the tag's category is Verbose, D/I not while it
        // is Off; W/E always. One hash lookup and two volatile reads
        val category = LogCapture.categoryOf(tag)
        if (level == 'V' && !LogCapture.isVerbose(category)) return
        if ((level == 'D' || level == 'I') && LogCapture.isOff(category)) return
        val text = message.trimEnd().let {
            if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS) + " …" else it
        }
        val line = Line(System.currentTimeMillis(), level, tag.ifBlank { "app" }, text)
        // KMK (r97): encoded once; the file queue takes the String, the ring its bytes
        val encoded = encode(line)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        ring?.append(encoded.toByteArray(Charsets.UTF_8)) // KMK (r97): the mirror ring
        if (level == 'E' && pendingClears.get() == 0) {
            // A crash may end the process before the writer runs: write problems right away (through the open
            // stream when there is one, after what it buffered, so the file keeps its order)
            runCatching {
                file?.let { f ->
                    synchronized(fileLock) {
                        val o = out
                        if (o != null) {
                            o.write(encoded)
                            o.flush()
                        } else {
                            f.appendText(encoded)
                        }
                    }
                }
            }
        } else {
            pending.trySend(encoded)
        }
        _version.value++
        LogCapture.onAdded(level) // KMK (r97): recording line count, floating bubble badge
    }

    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    fun clear() {
        // KMK: counted inside the lock before clearing, so the writer's restore of older lines (which checks the
        // count under the same lock) can't slip in between and put them back
        synchronized(lines) {
            pendingClears.incrementAndGet()
            lines.clear()
        }
        // KMK: in order with the queued lines (the writer empties the file when it gets here)
        pending.trySend(CLEAR_MARK)
        _version.value++
    }

    /** One line of the file: time, level, tag and message, with new lines escaped */
    private fun encode(line: Line): String =
        "${line.time}\t${line.level}\t${line.tag.replace('\t', ' ')}\t" +
            line.message.replace("\\", "\\\\").replace("\n", "\\n") + "\n"

    private fun decode(text: String): Line? {
        val parts = text.split('\t', limit = 4)
        if (parts.size < 4) return null
        val time = parts[0].toLongOrNull() ?: return null
        val message = buildString {
            var i = 0
            val raw = parts[3]
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    append(if (raw[i + 1] == 'n') '\n' else raw[i + 1])
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
        return Line(time, parts[1].firstOrNull() ?: 'I', parts[2], message)
    }

    fun format(line: Line): String {
        val seconds = (line.time - sessionStart) / 1000.0
        val sign = if (seconds >= 0) "+" else ""
        return "$sign${"%.3f".format(seconds)}s ${line.level} ${line.tag} ${line.message}"
    }

    /** Receives everything logged with logcat { } */
    class Logger : LogcatLogger {
        // KMK (r97): VERBOSE reaches the gate only while a category is Verbose (the lambda isn't built otherwise)
        override fun isLoggable(priority: LogPriority): Boolean =
            priority >= (if (LogCapture.anyVerbose) LogPriority.VERBOSE else LogPriority.DEBUG)

        override fun log(priority: LogPriority, tag: String, message: String) {
            val level = when (priority) {
                LogPriority.VERBOSE -> 'V'
                LogPriority.DEBUG -> 'D'
                LogPriority.INFO -> 'I'
                LogPriority.WARN -> 'W'
                else -> 'E'
            }
            lastFromLogcat.set(message)
            add(level, tag, message)
        }
    }

    /** Receives what goes through XLog directly (xLogD, xLogE ...) */
    class XLogPrinter : Printer {
        override fun println(logLevel: Int, tag: String?, msg: String?) {
            val level = when (logLevel) {
                LogLevel.VERBOSE -> 'V'
                LogLevel.DEBUG -> 'D'
                LogLevel.INFO -> 'I'
                LogLevel.WARN -> 'W'
                else -> 'E'
            }
            // logcat lines also reach XLog right after Logger took them: skip those
            if (msg != null && lastFromLogcat.get() == msg) {
                lastFromLogcat.remove()
                return
            }
            add(level, tag.orEmpty(), msg.orEmpty())
        }
    }

    /** The message logcat just handed over on this thread (XLog sees it again through XLogLogcatLogger) */
    private val lastFromLogcat = ThreadLocal<String?>()
}
