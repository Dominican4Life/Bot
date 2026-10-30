package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.os.Build
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.printer.Printer
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
import java.io.File
import java.io.RandomAccessFile

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
        val dir = File(context.filesDir, "app_log").apply { mkdirs() }
        val logFile = File(dir, "log.txt")
        file = logFile
        // KMK: only a stat on the main thread; reading and trimming happen in the writer below
        val startLength = runCatching { logFile.length() }.getOrDefault(0L)
        scope.launch {
            // Earlier runs: only the file's tail is read, then the file is rewritten with it
            runCatching {
                synchronized(fileLock) {
                    if (startLength > 0L) {
                        val old = readTail(logFile, startLength, MAX_BYTES).takeLast(MAX_LINES).mapNotNull(::decode)
                        // E lines written straight to the file since init
                        val sinceStart = readRange(logFile, startLength, logFile.length())
                        logFile.writeText(old.joinToString("") { encode(it) } + sinceStart)
                        synchronized(lines) {
                            lines.addAll(0, old)
                            while (lines.size > MAX_LINES) lines.removeFirst()
                        }
                        _version.value++
                    }
                }
            }
            var writes = 0
            for (text in pending) {
                runCatching {
                    synchronized(fileLock) {
                        logFile.appendText(text)
                        // KMK: keep the file small during a run too (it used to grow until the next start)
                        if (++writes >= TRIM_CHECK_EVERY) {
                            writes = 0
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
    }

    /** Lock for the log file: the writer, trimming, clear and the direct E lines */
    private val fileLock = Any()

    /** Keeps the newest [TRIM_TO_BYTES] of the file, starting at a whole line. Call with [fileLock] held */
    private fun trim(logFile: File) {
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
        val text = message.trimEnd().let {
            if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS) + " …" else it
        }
        val line = Line(System.currentTimeMillis(), level, tag.ifBlank { "app" }, text)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        if (level == 'E') {
            // A crash may end the process before the writer runs: write problems right away
            runCatching { file?.let { f -> synchronized(fileLock) { f.appendText(encode(line)) } } }
        } else {
            pending.trySend(encode(line))
        }
        _version.value++
    }

    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        scope.launch { runCatching { file?.let { f -> synchronized(fileLock) { f.writeText("") } } } }
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
        override fun isLoggable(priority: LogPriority): Boolean = priority >= LogPriority.DEBUG

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
