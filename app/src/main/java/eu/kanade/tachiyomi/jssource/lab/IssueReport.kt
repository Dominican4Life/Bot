package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.jssource.JsPreferences
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AppLog
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * KMK (r96): the Source Lab's Issue Report: one line that says what failed, and a zip of the files the app already
 * has about the source (D§2.8): the code, meta.json, lint.json, this and the previous Test Run, the failing step's
 * requests, the console, the detection fingerprint, device and app, optionally the source's settings (secrets as
 * `<set>`). Cookies and Authorization values are removed always; with [Options.redactHosts] every host but the
 * source's own and the failing hoster's becomes `host-<n>`. Written to `cacheDir/komikku-issue-<name>-<time>.zip`,
 * the last five kept, shared through `getUriCompat` → `toShareIntent`, like the editor's console.
 */
internal object IssueReport {

    private const val KEEP = 5

    data class Options(val redactHosts: Boolean = true, val includeSettings: Boolean = false)

    /** One planned entry of the bundle, for the checklist (sizes are of the un-redacted text) */
    data class Entry(val name: String, val bytes: Int, val note: String? = null)

    // ---------- the one line ----------

    object Summary {
        /** `<step> failed: <subject> <what happened>[, <most likely cause>]`, or "no failing step" words */
        fun line(run: TestRun?, lint: LintResult? = null): String {
            run ?: return "no Test Run yet: run the test first, then report"
            val red = run.steps.firstOrNull { it.status == StepStatus.Red }
            if (red == null) {
                val amber = run.steps.firstOrNull { it.status == StepStatus.Amber }
                return if (amber != null) "${amber.name.lowercase()} step passed with a warning: ${amber.evidence}" else "all ${run.counted} steps passed"
            }
            val subject = subject(red)
            val cause = cause(red, run, lint)
            return "${red.name.lowercase()} step failed: $subject${red.evidence.take(160)}${cause?.let { ", $it" }.orEmpty()}"
        }

        private fun subject(step: TestStep): String = when (step.id) {
            StepId.VIDEO -> step.evidence.substringBefore(':', "").takeIf { it.isNotBlank() && ' ' !in it.trim() }?.let { "hoster '$it' " }.orEmpty()
            else -> ""
        }

        /** Only facts the run measured: a missing Referer, a bot check, 0 items after 200, a relative URL, a timeout */
        private fun cause(step: TestStep, run: TestRun, lint: LintResult?): String? {
            val evidence = step.evidence.lowercase()
            val failed = step.requests.lastOrNull { it.status == null || it.status >= 400 }
            return when {
                "refuses that referer" in evidence -> "referer refused"
                "no referer" in evidence -> "referer missing"
                step.requests.any { it.viaBrowser } || run.console.any { "bot check" in it } -> "bot check in the way"
                step.count == 0 && (failed == null) && step.requests.lastOrNull()?.status == 200 -> "0 items after 200"
                lint?.findings?.any { !it.ignored && it.ruleId == "relative-url-returned" } == true -> "relative URL returned"
                "timed out" in evidence || "timeout" in evidence -> "timeout"
                failed?.status == 403 -> "403 from ${hostOf(failed.url) ?: "the server"}"
                else -> null
            }
        }
    }

    // ---------- redaction ----------

    /**
     * Hosts outside [allow] → `host-<n>` (the same n for the same host); cookies, tokens and e-mails always.
     *
     * A host is a dotted name right after `://` or `@`, or one whose last label is a known TLD: `extension.getPopular`
     * or `doc.select` in a console line is code, not a host. Header secrets are removed to the end of the line
     * only in line-shaped text ([line] with `headerLines`: requests.txt, console.txt); JSON entries are
     * pretty-printed (one key a line) and only the secret's own string value goes, so one `Cookie` key can't wipe
     * a whole single-line file.
     */
    class Redactor(private val allow: Set<String>, private val hosts: Boolean) {
        private val seen = LinkedHashMap<String, String>()

        fun line(text: String, headerLines: Boolean): String {
            var out = if (headerLines) {
                SECRET_HEADER.replace(text) { m -> "${m.groupValues[1]}<removed>" }
            } else {
                SECRET_JSON_VALUE.replace(text) { m -> "${m.groupValues[1]}\"<removed>\"" }
            }
            out = SECRET_QUERY.replace(out) { m -> "${m.groupValues[1]}…" }
            out = EMAIL.replace(out, "<email>")
            if (hosts) {
                val input = out
                out = HOST.replace(input) { m ->
                    val host = m.value.lowercase()
                    val before = input.substring((m.range.first - 3).coerceAtLeast(0), m.range.first)
                    val inUrl = before.endsWith("://") || before.endsWith("@")
                    when {
                        !inUrl && host.substringAfterLast('.') !in TLDS -> m.value
                        host.substringAfterLast('.') in FILE_EXTENSIONS -> m.value
                        allow.any { host == it || host.endsWith(".$it") } -> m.value
                        else -> seen.getOrPut(host) { "host-${seen.size + 1}" }
                    }
                }
            }
            return out
        }

        val mapped: Int get() = seen.size
    }

    private val SECRET_HEADER = Regex("""(?i)((?:set-)?cookie|authorization|x-csrf-token|x-api-key)\s*[:=]\s*[^\n]*""")
    /** `"Cookie": "…"` inside pretty-printed JSON: only the value */
    private val SECRET_JSON_VALUE = Regex("""(?i)("(?:set-)?(?:cookie|authorization|x-csrf-token|x-api-key)"\s*:\s*)"(?:\\.|[^"\\])*"""")
    private val SECRET_QUERY = Regex("""(?i)([?&](?:token|key|apikey|api_key|auth|session|sid|sig|signature|expires?|hash)=)[^&\s"']+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val FILE_EXTENSIONS = setOf(
        "js", "json", "txt", "html", "htm", "php", "jpg", "jpeg", "png", "webp", "gif", "avif", "svg", "mp4", "mkv",
        "m3u8", "ts", "mp3", "css", "xml", "md", "kt", "java", "py", "zip", "apk", "woff", "woff2", "ico",
    )
    private val HOST = Regex("""\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,24})\b""", RegexOption.IGNORE_CASE)
    /** Last labels that make a bare dotted name a host (not preceded by `://` or `@`) */
    private val TLDS = setOf(
        "com", "net", "org", "io", "co", "to", "tv", "me", "cc", "xyz", "site", "online", "info", "biz", "us", "uk",
        "de", "fr", "es", "it", "nl", "ru", "br", "jp", "kr", "cn", "in", "id", "vn", "th", "ph", "tw", "hk", "sg",
        "pl", "cz", "tr", "ua", "mx", "ar", "cl", "ca", "au", "nz", "eu", "app", "dev", "cloud", "live", "stream",
        "video", "club", "pro", "top", "fun", "vip", "lol", "one", "zone", "world", "life", "space", "ws", "su",
        "is", "la", "ly", "sh", "st", "gg", "so", "ai", "mobi", "moe", "wiki", "red", "blue", "pw", "icu", "cfd",
        "sbs", "quest", "run", "watch", "art", "best", "cyou", "rest", "store", "shop", "news", "today", "cam",
    )

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()?.removePrefix("www.")

    // ---------- the bundle ----------

    private val context: Application by lazy { Injekt.get<Application>() }
    private val manager: JsSourceManager by lazy { Injekt.get<JsSourceManager>() }

    /** What the zip of [key] would hold, with sizes, for the Report pane's checklist */
    fun plan(key: String, options: Options): List<Entry> = runCatching { entries(key, options).map { Entry(it.first, it.second.length, it.third) } }
        .getOrDefault(emptyList())

    /** The zip for [key]; throws when the source is gone */
    suspend fun build(key: String, options: Options): File = withContext(Dispatchers.IO) {
        val info = manager.get(key) ?: throw IllegalStateException("The source is gone")
        val run = TestRuns.latest(key)
        val red = run?.steps?.firstOrNull { it.status == StepStatus.Red }
        val allow = buildSet {
            hostOf(info.baseUrl)?.let { add(it) }
            red?.requests?.lastOrNull { it.status == null || it.status >= 400 }?.url?.let { hostOf(it) }?.let { add(it) }
            red?.items?.firstOrNull { it.kind == "video" }?.url?.let { hostOf(it) }?.let { add(it) }
        }
        val redactor = Redactor(allow, options.redactHosts)
        val safe = info.name.ifBlank { "source" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(context.cacheDir, "komikku-issue-$safe-$stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries(key, options).forEach { (name, text, _) ->
                zip.putNextEntry(ZipEntry(name))
                val writer = zip.bufferedWriter()
                // KMK: redaction per line while writing (no whole-file strings a second time)
                val raw = name == "source.${if (info.json) "json" else "js"}" || name == "README.txt"
                val headerLines = name == "requests.txt" || name == "console.txt"
                text.lineSequence().forEach { line ->
                    writer.write(if (raw) line else redactor.line(line, headerLines))
                    writer.newLine()
                }
                writer.flush()
                zip.closeEntry()
            }
        }
        context.cacheDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("komikku-issue-") && it.name.endsWith(".zip") }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
        AppLog.add('I', "lab", "Issue report of ${info.name}: ${file.name} (${file.length() / 1024} KB, ${redactor.mapped} hosts redacted)")
        file
    }

    /** The same three calls as the editor's shareConsole */
    fun share(context: Context, file: File) {
        runCatching {
            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, type = "application/zip"))
        }.onFailure { context.toast(it.message ?: "Couldn't share the report") }
    }

    /** (entry name, text, note) in bundle order; README first */
    private fun entries(key: String, options: Options): List<Triple<String, String, String?>> {
        val info = manager.get(key) ?: return emptyList()
        val code = manager.code(key).orEmpty()
        val runs = TestRuns.list(key)
        val now = runs.getOrNull(0)
        val previous = runs.getOrNull(1)
        val lint = Lint.read(key)
        val red = now?.steps?.firstOrNull { it.status == StepStatus.Red }
        val out = ArrayList<Triple<String, String, String?>>()
        out.add(Triple("README.txt", readme(info, now, previous, lint, red, options), "the one line first"))
        out.add(Triple("source.${if (info.json) "json" else "js"}", code, "the code as saved"))
        out.add(Triple("meta.json", prettyJson.encodeToString(JsSourceInfo.serializer(), info), null))
        lint?.let { out.add(Triple("lint.json", prettyJson.encodeToString(LintResult.serializer(), it), "rules v${it.rulesVersion}")) }
        now?.let { out.add(Triple("testrun-now.json", prettyJson.encodeToString(TestRun.serializer(), it), "${it.passed} of ${it.counted} passed")) }
        previous?.let { out.add(Triple("testrun-previous.json", prettyJson.encodeToString(TestRun.serializer(), it), null)) }
        out.add(Triple("requests.txt", requests(red ?: now?.steps?.lastOrNull()), red?.let { "the ${it.name.lowercase()} step" } ?: "the last step"))
        // r109: a source made by Record and convert: the recording's step-by-step summary from the code's header
        // (requests, what the extension returned, why a part is a TODO), redacted like the other text entries
        recordingOf(code)?.let { out.add(Triple("recording.txt", it, "Record and convert, step by step")) }
        out.add(Triple("console.txt", now?.console.orEmpty().joinToString("\n"), "${now?.console?.size ?: 0} lines"))
        out.add(Triple("fingerprint.json", info.lab?.let { prettyJson.encodeToString(JsonElement.serializer(), it) } ?: "{}", if (info.lab == null) "hand-written or imported" else null))
        out.add(Triple("device.txt", device(), null))
        if (options.includeSettings) out.add(Triple("settings.json", settings(info), "secrets as <set>"))
        out.add(Triple("traces/README.txt", "not captured in this build", null))
        return out
    }

    private fun readme(info: JsSourceInfo, now: TestRun?, previous: TestRun?, lint: LintResult?, red: TestStep?, options: Options): String = buildString {
        appendLine(Summary.line(now, lint))
        appendLine()
        appendLine("source: ${info.name} (${if (info.anime) "anime" else "manga"} · ${if (info.json) "JSON" else "JS"}) key ${info.key} id ${info.id}")
        appendLine("source version: v${now?.version ?: History.latestVersion(info.key)}")
        appendLine("rules version: ${lint?.rulesVersion ?: now?.rulesVersion ?: "none"}")
        appendLine("app release: r${BuildConfig.RELEASE_NUMBER.ifBlank { "local" }} (${BuildConfig.COMMIT_SHA})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine("when: ${now?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it.startedAt)) } ?: "no run"}")
        if (red != null && previous != null) {
            val before = previous.steps.firstOrNull { it.id == red.id }
            if (before?.status == StepStatus.Green) {
                appendLine("the same step passed in the previous run (${ago((now?.startedAt ?: 0L) - previous.startedAt)} earlier)")
            }
        }
        appendLine()
        appendLine("redaction: hosts ${if (options.redactHosts) "other than the source's and the failing hoster's replaced by host-<n>" else "kept"}; cookies, authorization and tokens removed always")
        appendLine("settings: ${if (options.includeSettings) "included, secrets as <set>" else "not included"}")
        appendLine("traces: not captured in this build")
    }

    private fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "$minutes min"
            minutes < 60 * 48 -> "${minutes / 60} h"
            else -> "${minutes / 60 / 24} days"
        }
    }

    private fun requests(step: TestStep?): String {
        step ?: return "no step"
        return buildString {
            appendLine("step: ${step.name} (${step.status.name.lowercase()}) · ${step.ms} ms")
            appendLine("evidence: ${step.evidence}")
            appendLine()
            if (step.requests.isEmpty()) appendLine("no requests logged for this step")
            step.requests.forEach { r ->
                appendLine("${r.method} ${r.url} -> ${r.status ?: "no reply"} in ${r.ms} ms${if (r.viaBrowser) " (through the browser)" else ""}${r.error?.let { " · $it" }.orEmpty()}")
            }
            if (step.items.isNotEmpty()) {
                appendLine()
                appendLine("items (first ${step.items.size.coerceAtMost(10)}):")
                step.items.take(10).forEach { appendLine("  ${it.kind} ${it.title.take(60)} ${it.url}") }
            }
        }
    }

    /**
     * r109: the "Recording, step by step" block RecordedConverter writes into the header of a converted source, without
     * the comment marks; plus each part's "What the converter found" lines; null when the code has none
     */
    private fun recordingOf(code: String): String? {
        val lines = code.lines()
        val start = lines.indexOfFirst { RECORDING_MARK in it }
        if (start < 0) return null
        val out = ArrayList<String>()
        out.add(lines[start].trim().removePrefix("//").trim())
        for (line in lines.drop(start + 1)) {
            val t = line.trim()
            if (!t.startsWith("// - ") && !t.startsWith("//     ")) break
            out.add(t.removePrefix("//").removePrefix(" "))
        }
        // Each TODO part's reason and findings, after the summary
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            if (!t.startsWith("// TODO (not converted):")) return@forEachIndexed
            out.add("")
            out.add(t.removePrefix("//").trim())
            for (next in lines.drop(i + 1)) {
                val n = next.trim()
                if (n != "// What the converter found:" && !n.startsWith("//   - ")) break
                out.add(n.removePrefix("//").trimEnd())
            }
        }
        return out.joinToString("\n")
    }

    private const val RECORDING_MARK = "Recording, step by step"

    private fun device(): String = buildString {
        appendLine("manufacturer: ${Build.MANUFACTURER}")
        appendLine("model: ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("app: ${BuildConfig.VERSION_NAME} release r${BuildConfig.RELEASE_NUMBER.ifBlank { "local" }} commit ${BuildConfig.COMMIT_SHA}")
    }

    private fun settings(info: JsSourceInfo): String {
        val all = runCatching { JsPreferences.of(context, info.id).all }.getOrDefault(emptyMap<String, Any?>())
        val obj = buildJsonObject {
            all.forEach { (k, v) ->
                put(k, if (SECRET_KEY.containsMatchIn(k)) JsonPrimitive("<set>") else JsonPrimitive(v?.toString() ?: "null"))
            }
        }
        return prettyJson.encodeToString(JsonElement.serializer(), obj)
    }

    /** One key a line, so a redacted value is the only thing its line loses */
    private val prettyJson = Json(jsJson) { prettyPrint = true }

    private val SECRET_KEY = Regex("""(?i)pass|token|key|secret|cookie|auth|session""")
}
