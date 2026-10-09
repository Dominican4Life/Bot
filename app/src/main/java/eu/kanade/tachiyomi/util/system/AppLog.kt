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

/**
 * KMK: The app's own log, shown in the Logs screen (debug button on the navigation rail), like Sumizuri's.
 *
 * Everything logged through logcat, XLog and the crash handler lands here with its time, level and tag. The last
 * [MAX_LINES] lines are kept on disk, so lines from earlier runs are still there after a restart (they show with a
 * negative time, counted back from this run's start).
 */
object AppLog {

    const val MAX_LINES = 4000

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
        // Earlier runs, trimmed to the newest lines
        runCatching {
            if (logFile.exists()) {
                val old = logFile.readLines().takeLast(MAX_LINES).mapNotNull(::decode)
                synchronized(lines) { lines.addAll(0, old) }
                logFile.writeText(old.joinToString("") { encode(it) })
            }
        }
        scope.launch {
            for (text in pending) {
                runCatching { logFile.appendText(text) }
            }
        }
        add(
            'I',
            "app",
            "start ${context.packageName} ${appVersion(context)} on Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    fun add(level: Char, tag: String, message: String) {
        val line = Line(System.currentTimeMillis(), level, tag.ifBlank { "app" }, message.trimEnd())
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        if (level == 'E') {
            // A crash may end the process before the writer runs: write problems right away
            runCatching { file?.appendText(encode(line)) }
        } else {
            pending.trySend(encode(line))
        }
        _version.value++
    }

    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        scope.launch { runCatching { file?.writeText("") } }
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
