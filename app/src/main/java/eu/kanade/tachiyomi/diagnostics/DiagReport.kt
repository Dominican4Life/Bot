package eu.kanade.tachiyomi.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.ui.player.PlayerDebugLog
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * KMK (r97): The report bundle (design §4, §8): `cacheDir/komikku-report-<yyyyMMdd-HHmm>.zip`, written on IO
 * through a streaming [ZipOutputStream], every text line through the [Redactor]. Three reports kept; the mapping
 * the redactor used goes to a local-only `-keys.json` next to the zip.
 */
internal object DiagReport {

    private const val MB = 1024.0 * 1024

    /** Sizes before zipping (the Report tab's checklist) */
    fun sizes(context: Context): List<ReportPartSize> {
        val ring = AppLog.ring?.used?.toLong() ?: 0L
        val logFile = File(context.filesDir, DiagPaths.APP_LOG_FILE).length()
        val traces = TraceWriter.bytesMap().filterValues { it > 0L }
        val anr = File(context.cacheDir, DiagPaths.HEALTH_DIR).listFiles()?.sumOf { it.length() } ?: 0L
        return listOf(
            // KMK (r103): log.txt is only written when the ring couldn't be mapped
            ReportPartSize(
                ReportPart.LOG,
                ring + logFile,
                if (logFile > 0L) "ring ${fmt(ring)} · log.txt ${fmt(logFile)}" else "ring ${fmt(ring)}",
            ),
            ReportPartSize(ReportPart.LIVE, 40_000L, "5-min rings and the last snapshot"),
            ReportPartSize(ReportPart.DEVICE, 2_000L, "build, Android, model, memory, network"),
            ReportPartSize(ReportPart.SOURCES, 20_000L, "extensions and JS sources"),
            ReportPartSize(
                ReportPart.PROBE_CONSOLE,
                ProbeRuntime.consoleText().length.toLong(),
                "${ProbeRuntime.console.value.size} lines",
            ),
            ReportPartSize(ReportPart.SETTINGS, 10_000L, "changed settings · secrets as <set>"),
            ReportPartSize(
                ReportPart.TRACES, traces.values.sum() / 8,
                if (traces.isEmpty()) "no trace is on" else traces.entries.joinToString(" · ") { "${it.key.fileStem} ${fmt(it.value)}" },
            ),
            ReportPartSize(
                ReportPart.HEALTH,
                Health.json().length + anr,
                "${Health.events().size} events" + if (anr > 0) " · ANR traces" else "",
            ),
            ReportPartSize(
                ReportPart.PLAYER_LOG,
                PlayerDebugLog.text().length.toLong(),
                "mpv lines from the last playback",
            ),
        )
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / MB)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Builds the zip; [progress] gets the entry being written. Call on IO */
    fun build(
        context: Context,
        options: ReportOptions,
        progress: (String) -> Unit,
    ): ReportResult = Perf.traced("report-build") {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        var file = File(context.cacheDir, "${DiagPaths.REPORT_PREFIX}$stamp.zip")
        var n = 1
        while (file.exists()) file = File(context.cacheDir, "${DiagPaths.REPORT_PREFIX}$stamp-${n++}.zip")
        // Redaction off still removes Cookie / Authorization values (fix pass: the writer never passes raw text)
        val redactor = Redactor.build(context, full = options.redact)
        val parts = options.parts
        ZipOutputStream(file.outputStream().buffered(64 * 1024)).use { zip ->
            zip.setLevel(6)
            fun entry(name: String, block: (Writer) -> Unit) {
                progress(name)
                zip.putNextEntry(ZipEntry(name))
                val writer = RedactingWriter(OutputStreamWriter(zip, Charsets.UTF_8), redactor)
                block(writer)
                writer.flush()
                zip.closeEntry()
            }
            if (ReportPart.LOG in parts) {
                entry("log.txt") { w ->
                    for (line in AppLog.snapshot()) w.write(AppLog.format(line) + "\n")
                }
                // KMK (r103): the app's log file exists only when the ring couldn't be mapped (the ring is the store)
                val raw = File(context.filesDir, DiagPaths.APP_LOG_FILE)
                if (raw.isFile) {
                    entry("log-raw.tsv") { w ->
                        raw.bufferedReader().useLines { lines -> lines.forEach { w.write(it + "\n") } }
                    }
                }
                // The ring holds up to 4 MB of lines of this and the previous run, more than log.txt's tail
                entry("log-ring.tsv") { w -> AppLog.ring?.forEachLine { w.write(it + "\n") } }
            }
            if (ReportPart.LIVE in parts) entry("live.json") { w -> w.write(liveJson().toString()) }
            if (ReportPart.DEVICE in parts) entry("device.json") { w -> w.write(deviceJson(context).toString()) }
            if (ReportPart.SOURCES in parts) entry("sources.json") { w -> w.write(sourcesJson().toString()) }
            if (ReportPart.SETTINGS in parts) entry("settings.json") { w -> w.write(settingsJson().toString()) }
            if (options.includeCapture) entry("capture.json") { w -> w.write(captureJson().toString()) }
            if (ReportPart.HEALTH in parts) {
                entry("health.json") { w -> w.write(Health.json()) }
                File(context.cacheDir, DiagPaths.HEALTH_DIR).listFiles()?.filter { it.isFile }?.forEach { f ->
                    entry("health/${f.name}") { w -> f.bufferedReader().useLines { lines -> lines.forEach { w.write(it + "\n") } } }
                }
            }
            if (ReportPart.PROBE_CONSOLE in parts) {
                entry("probes/console.txt") { w -> w.write(ProbeRuntime.consoleText()) }
                val store = runCatching { ProbeStore(context).also { it.load() } }.getOrNull()
                store?.let { st ->
                    st.probes.value.forEach { p -> entry("probes/${p.id}-${safeName(p.name)}.js") { w -> w.write(st.code(p.id)) } }
                }
            }
            if (ReportPart.PLAYER_LOG in parts) entry("player-log.txt") { w -> w.write(PlayerDebugLog.text()) }
            if (ReportPart.TRACES in parts) {
                for (kind in TraceKind.entries) {
                    if (TraceWriter.bytes(kind) <= 0L) continue
                    progress("traces/${kind.fileStem}.jsonl.gz")
                    zip.putNextEntry(ZipEntry("traces/${kind.fileStem}.jsonl.gz"))
                    val gz = GZIPOutputStream(NoCloseStream(zip))
                    val w = RedactingWriter(OutputStreamWriter(gz, Charsets.UTF_8), redactor)
                    for (line in TraceWriter.lines(kind)) w.write(line + "\n")
                    w.flush()
                    gz.close() // finishes the gzip stream; NoCloseStream keeps the zip open
                    zip.closeEntry()
                }
            }
            entry("summary.txt") { w -> w.write(summary()) }
            entry("README.txt") { w -> w.write(readme(context, options, parts)) }
        }
        // Three kept
        context.cacheDir.listFiles { f -> f.name.startsWith(DiagPaths.REPORT_PREFIX) && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }?.drop(DiagBudget.REPORTS_KEPT)?.forEach { old ->
                old.delete()
                File(old.path.removeSuffix(".zip") + DiagPaths.REPORT_KEYS_SUFFIX).delete()
            }
        var keys: String? = null
        if (options.redact) {
            val keysFile = File(file.path.removeSuffix(".zip") + DiagPaths.REPORT_KEYS_SUFFIX)
            runCatching { keysFile.writeText(redactor.keysJson()) }
            keys = keysFile.path
        }
        val result = ReportResult(file.path, file.length(), System.currentTimeMillis(), keys)
        AppLog.add(
            'I',
            "diag",
            "Report built: ${file.name} (${fmt(result.bytes)}${if (options.redact) ", redacted" else ""})",
        )
        result
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

    /** A stream wrapper so GZIPOutputStream.close() can't close the zip */
    private class NoCloseStream(private val target: java.io.OutputStream) : java.io.FilterOutputStream(target) {
        override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
        override fun close() = flush()
    }

    // --- entries -----------------------------------------------------------------------------------------------------

    private fun liveJson(): JsonObject = buildJsonObject {
        val now = System.currentTimeMillis()
        put("takenAt", now)
        put(
            "strip",
            jsJson.encodeToJsonElement(StatusStripJson.serializer(), StatusStripJson.of(LiveSampler.strip.value)),
        )
        val m = LiveSampler.memory.value
        put(
            "memory",
            buildJsonObject {
                put("javaMb", m.javaMb)
                put("javaMaxMb", m.javaMaxMb)
                put("imagesMb", m.imagesMb)
                put("jsMb", m.jsMb)
                put("nativeMb", m.nativeMb)
                put("diagMb", m.diagMb)
                put("rssMb", m.rssMb)
                put("browseTabs", m.browseTabs)
                put("folderListings", m.folderListings)
                put("jsPages", m.jsPages)
                put(
                    "engines",
                    buildJsonArray {
                        m.engines.forEach { e ->
                            add(
                                buildJsonObject {
                                    put("name", e.name)
                                    put("mb", e.mb)
                                    put("pages", e.pages)
                                    put("busy", e.busy)
                                },
                            )
                        }
                    },
                )
            },
        )
        val w = LiveSampler.workers.value
        put(
            "workers",
            buildJsonObject {
                put("ioBusy", w.ioBusy)
                put("ioParked", w.ioParked)
                put("ioBlocked", w.ioBlocked)
                put("threads", w.threads)
                put("starved", w.starved)
            },
        )
        put("rings", buildJsonObject {
            for (r in Resource.entries) {
                val win = LiveSampler.buildWindow(r, TimeWindow.M5, now)
                put(r.name, buildJsonObject {
                    put("startMs", win.startMs)
                    put("stepMs", win.stepMs)
                    for (s in win.series) put(
                        s.label,
                        JsonArray(s.values.map { v -> if (v.isNaN()) JsonNull else JsonPrimitive(v) }),
                    )
                })
            }
        })
        put("requests", buildJsonArray {
            NetStats.rows().takeLast(100).forEach { r ->
                add(
                    buildJsonObject {
                        put("t", r.timeMs)
                        put("method", r.method)
                        put("host", r.host)
                        put("path", r.path)
                        put("status", r.status)
                        put("ms", r.ms)
                        put("bytes", r.bytes)
                        put("source", r.source)
                    },
                )
            }
        })
        put(
            "running",
            buildJsonArray {
                LiveSampler.tables.value.running.forEach { j ->
                    add(
                        buildJsonObject {
                            put("name", j.name)
                            put("progress", j.progress)
                            put("state", j.state.name)
                        },
                    )
                }
            },
        )
    }

    @kotlinx.serialization.Serializable
    private class StatusStripJson(
        val health: String,
        val sentence: String,
        val workersBusy: Int,
        val heapMb: Int,
        val nativeMb: Int,
        val netMbps: Float,
    ) {
        companion object {
            fun of(s: StatusStrip) = StatusStripJson(
                s.health.name,
                s.sentence,
                s.workersBusy,
                s.heapMb,
                s.nativeMb,
                s.netMbps,
            )
        }
    }

    private fun deviceJson(context: Context): JsonObject = buildJsonObject {
        put("package", context.packageName)
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            put("versionName", info.versionName)
            put("versionCode", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
        }
        put("commit", BuildConfig.COMMIT_SHA)
        put("commitCount", BuildConfig.COMMIT_COUNT)
        put("buildType", BuildConfig.BUILD_TYPE)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("sdk", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("abis", JsonArray(Build.SUPPORTED_ABIS.map { JsonPrimitive(it) }))
        put("locale", Locale.getDefault().toString())
        put("timezone", TimeZone.getDefault().id)
        val dm = context.resources.displayMetrics
        put("screen", "${dm.widthPixels}×${dm.heightPixels} @ ${dm.densityDpi} dpi")
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        put("nightMode", uiMode == Configuration.UI_MODE_NIGHT_YES)
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            put("totalMemMb", mi.totalMem / (1024 * 1024))
            put("availMemMb", mi.availMem / (1024 * 1024))
            put("lowMemory", mi.lowMemory)
            put("thresholdMb", mi.threshold / (1024 * 1024))
            put("isLowRamDevice", am.isLowRamDevice)
            put("memoryClassMb", am.memoryClass)
            put("largeMemoryClassMb", am.largeMemoryClass)
        }
        put("maxMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            put("batterySaver", pm.isPowerSaveMode)
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            put(
                "network",
                when {
                    caps == null -> "none"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                },
            )
            put("metered", cm.isActiveNetworkMetered)
            put("dataSaver", cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        }
        put("uptimeMs", System.currentTimeMillis() - AppLog.sessionStart)
    }

    private fun sourcesJson(): JsonObject = buildJsonObject {
        put(
            "extensions",
            buildJsonArray {
                runCatching {
                    val em = Injekt.get<ExtensionManager>()
                    em.installedExtensionsFlow.value.forEach { e ->
                        add(
                            buildJsonObject {
                                put("name", e.name)
                                put("pkg", e.pkgName)
                                put("versionName", e.versionName)
                                put("versionCode", e.versionCode)
                                put("lang", e.lang)
                                put("nsfw", e.isNsfw)
                                put("signature", e.signatureHash.take(12))
                                put("hasUpdate", e.hasUpdate)
                                put("sources", JsonArray(e.sources.map { s -> JsonPrimitive("${s.id} ${s.name}") }))
                            },
                        )
                    }
                }
            },
        )
        put(
            "jsSources",
            buildJsonArray {
                runCatching {
                    val jsm = Injekt.get<JsSourceManager>()
                    // The sampler's latest rows (no runBlocking on the IO pool, guide "Mistakes" r93 → r94)
                    val running = LiveSampler.memory.value.engines.associateBy { it.key }
                    jsm.savedFlow.value.forEach { s ->
                        val code = jsm.code(s.key).orEmpty()
                        add(
                            buildJsonObject {
                                put("key", s.key)
                                put("name", s.name)
                                put("id", s.id)
                                put("customId", s.customId)
                                put("anime", s.anime)
                                put("json", s.json)
                                put("enabled", s.enabled)
                                put("updatedAt", s.updatedAt)
                                put("codeLength", code.length)
                                put("sha256", sha256(code).take(16))
                                put("baseUrl", s.baseUrl)
                                running[s.key]?.let { r ->
                                    put("running", true)
                                    put("memoryMb", r.mb)
                                    put("pages", r.pages)
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    private val secretKeyWords = listOf(
        "token",
        "password",
        "pass",
        "secret",
        "cookie",
        "auth",
        "credential",
        "key",
        "api",
        "username",
        "user",
        "login",
        "email",
        "mail",
        "eh_",
        "session",
        "passphrase",
        "pin",
    )

    private fun settingsJson(): JsonObject = buildJsonObject {
        runCatching {
            val all = Injekt.get<PreferenceStore>().getAll()
            for ((key, value) in all.toSortedMap()) {
                if (key.startsWith("__")) continue
                val lower = key.lowercase()
                if (secretKeyWords.any { lower.contains(it) } || key.contains("pair_sync")) {
                    val unset = value == null || (value is String && value.isEmpty()) || (value is Set<*> && value.isEmpty())
                    put(key, if (unset) "<unset>" else "<set>")
                } else {
                    put(key, plainJson(value))
                }
            }
        }
    }

    private fun plainJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Set<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
        else -> JsonPrimitive(value.toString())
    }

    private fun captureJson(): JsonObject = buildJsonObject {
        val s = LogCapture.state.value
        put("levels", buildJsonObject { s.levels.forEach { (c, l) -> put(c.name, l.name) } })
        put("verboseUntil", buildJsonObject { s.verboseUntil.forEach { (c, t) -> put(c.name, t) } })
        put("traces", JsonArray(s.traces.map { JsonPrimitive(it.name) }))
        put("netTraceSources", JsonArray(s.netTraceSources.map { JsonPrimitive(it) }))
        put("netTraceHeaders", s.netTraceHeaders)
        put("netTraceBodyHead", s.netTraceBodyHead)
        put("sampleMs", s.sampleMs)
        put("preset", s.preset?.name)
        put("recording", LogCapture.recording.value?.let { "${it.duration.name} since ${it.startedAt}" })
        put(
            "probes",
            JsonArray(
                ProbeRuntime.snapshot.value.probes.map { p ->
                    JsonPrimitive("${p.name} ${if (p.enabled) "on" else "off"} ${p.hooks.joinToString(",")}")
                },
            ),
        )
    }

    private fun readme(context: Context, options: ReportOptions, parts: Set<ReportPart>): String = buildString {
        append("Komikku diagnostics report · ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).append('\n')
        append(context.packageName).append(' ')
        runCatching { append(context.packageManager.getPackageInfo(context.packageName, 0).versionName) }
        append(" (").append(BuildConfig.COMMIT_SHA).append(") on Android ").append(Build.VERSION.RELEASE)
        append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        append("Parts: ").append(parts.joinToString(", ") { it.label }).append('\n')
        append("Redaction: ").append(
            if (options.redact) {
                "on (source names → source-<id>, hosts → host-<n>, query values → …, e-mails and the device name replaced; " +
                    "the mapping is in the -keys.json next to the zip, which is not part of the report)"
            } else {
                "off (source names, hosts and query values are left as they are)"
            },
        ).append('\n')
        append("Always: Cookie / Set-Cookie / Authorization header values are removed from every file, and settings.json shows ")
        append("secret-looking preferences (token, password, cookie, auth, key, user, mail, …) only as <set> / <unset>.\n")
        append("Capture settings: ").append(if (options.includeCapture) "capture.json" else "left out").append("\n\n")
        append("Files: log.txt (what the Logs screen showed), log-ring.tsv (the log ring: this and the previous run; ")
        append("log-raw.tsv, the old log file, only when the ring couldn't be mapped),\n")
        append("live.json, device.json, sources.json, settings.json, health.json (+ health/anr-*.txt), probes/, traces/*.jsonl.gz, summary.txt.\n")
    }

    // --- summary.txt (design §8): from the field tails of the whole ring, one pass ------------------------------------

    fun summary(): String {
        class Agg { var n = 0; var ms = 0L; var maxMs = 0L; var errors = 0; var bytes = 0L; var parseMs = 0L }
        val netBySource = HashMap<String, Agg>()
        val slowest = ArrayList<Pair<Long, String>>()
        val jsBySource = HashMap<String, Agg>()
        val memByScreen = HashMap<String, Triple<Long, Long, Long>>()
        var pagesDecoded = 0
        var firstTime = Long.MAX_VALUE
        var lastTime = 0L
        val syncPhases = ArrayList<String>()
        var updates = 0
        val lines = AppLog.snapshot()
        for (line in lines) {
            if (line.time < firstTime) firstTime = line.time
            if (line.time > lastTime) lastTime = line.time
            val f = AppLog.fieldsOf(line.message)
            when (line.tag) {
                "net.v" -> {
                    val ms = f["duration_ms"]?.toLongOrNull() ?: continue
                    val source = f["source"] ?: "other"
                    val a = netBySource.getOrPut(source) { Agg() }
                    a.n++
                    a.ms += ms
                    if (ms > a.maxMs) a.maxMs = ms
                    a.bytes += f["bytes"]?.toLongOrNull() ?: 0L
                    val status = f["status"]?.toIntOrNull() ?: 0
                    if (status == 0 || status >= 400) a.errors++
                    slowest.add(ms to line.message.substringBefore(" · ").take(100))
                }
                "js.call" -> {
                    val ms = f["duration_ms"]?.toLongOrNull() ?: continue
                    val a = jsBySource.getOrPut(f["source"] ?: "?") { Agg() }
                    a.n++
                    a.ms += ms
                    if (ms > a.maxMs) a.maxMs = ms
                    if (line.message.contains(": ")) a.errors++
                }
                "reader.page" -> if (line.message.contains("decoded")) pagesDecoded++
                "Memory" -> {
                    val used = f["used_mb"]?.toLongOrNull() ?: continue
                    val screen = f["screen"] ?: "-"
                    val cur = memByScreen[screen]
                    memByScreen[screen] = if (cur == null) Triple(used, used, used) else Triple(
                        minOf(cur.first, used),
                        maxOf(cur.second, used),
                        used,
                    )
                }
                "sync" -> if (line.message.startsWith("Synced with") || line.message.startsWith("Sync error")) syncPhases.add(line.message.take(120))
                "LibraryUpdateJob", "anime" -> if (line.message.contains("update", ignoreCase = true) && f["count"] != null) updates++
            }
        }
        return buildString {
            append("Summary of ${lines.size} lines")
            if (lastTime > firstTime) append(" over ${(lastTime - firstTime) / 60_000} min")
            append('\n')
            append("\nSlowest requests (net.v):\n")
            slowest.sortedByDescending { it.first }.take(10).forEach { (ms, text) -> append("  ${ms} ms  $text\n") }
            if (slowest.isEmpty()) append("  (Network wasn't Verbose)\n")
            append("\nRequests per source:\n")
            netBySource.entries.sortedByDescending { it.value.n }.forEach { (s, a) ->
                append("  $s: ${a.n} requests, ${"%,d".format(a.bytes)} B, mean ${a.ms / a.n} ms, max ${a.maxMs} ms, ${a.errors} errors\n")
            }
            append("\nJS calls per source:\n")
            jsBySource.entries.sortedByDescending { it.value.n }.forEach { (s, a) ->
                append("  $s: ${a.n} calls, mean ${a.ms / a.n} ms, max ${a.maxMs} ms, ${a.errors} failed\n")
            }
            if (jsBySource.isEmpty()) append("  (JS wasn't Verbose)\n")
            append("\nPages decoded: $pagesDecoded")
            if (lastTime > firstTime && pagesDecoded > 0) append(" (${pagesDecoded * 60_000 / (lastTime - firstTime)} per minute)")
            append('\n')
            append("\nMemory per screen (min / max / last MB):\n")
            memByScreen.entries.sortedByDescending { it.value.second }.forEach { (s, t) -> append("  $s: ${t.first} / ${t.second} / ${t.third}\n") }
            if (syncPhases.isNotEmpty()) {
                append("\nPair sync:\n")
                syncPhases.takeLast(10).forEach { append("  $it\n") }
            }
            append("\nHealth events in 7 days: ${Health.events().size}")
            Health.events().groupBy { it.kind }.entries.sortedByDescending { it.value.size }.forEach { (k, v) -> append("\n  ${k.label}: ${v.size}") }
            append('\n')
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * KMK (r97): Streaming redaction (design §4): source names → `source-<id>`, hosts → `host-<n>` (the same n for the
 * same host in every file), query values → `…`, e-mail-looking strings and the device name replaced; Cookie /
 * Authorization values always removed. Compiled once per report, applied per line.
 */
internal class Redactor private constructor(
    private val sources: Map<String, String>,
    private val hostNumbers: MutableMap<String, Int>,
    private val device: String?,
    /** Hosts the app has really talked to (NetStats' table + the sources' base URLs): always hosts */
    private val knownHosts: Set<String>,
    /** False = only the secret header values go (redaction switched off in the Report tab) */
    private val full: Boolean,
) {
    /** Exact source names, whole words, case-sensitive; short names (≤ 4 chars) are common words and are skipped */
    private val sourcePattern: Regex? = sources.keys.filter { it.length > 4 }.sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }?.let { names -> Regex("""\b(?:${names.joinToString("|") { Regex.escape(it) }})\b""") }

    /** Candidate dotted names, lowercase labels only; whether one is a host is decided in [isHost] */
    private val hostPattern = Regex("""\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,23}\b""")
    private val queryPattern = Regex("""([?&][A-Za-z0-9_\-\[\]%.]+=)[^&\s"]*""")
    private val emailPattern = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val secretHeaderPattern = Regex(
        """("?(?:Cookie|Set-Cookie|Authorization|Proxy-Authorization|X-Auth-Token)"?\s*[:=]\s*"?)[^"\n,\}]*""",
        RegexOption.IGNORE_CASE,
    )
    private val keepHosts = setOf("127.0.0.1", "localhost", "schemas.android.com", "www.w3.org")

    /** Engine tags and dotted names of ours that look like hosts but aren't */
    private val notHosts = setOf(
        "net.v", "js.call", "player.ev", "player.hls", "player.hoster", "sync.frame", "reader.page",
        "net.request", "net.response", "js.result", "player.event", "hls.segment", "hoster.step", "sync.phase",
    )

    fun redactLine(line: String): String {
        var s = secretHeaderPattern.replace(line) { m -> m.groupValues[1] + "<removed>" }
        if (!full) return s
        s = queryPattern.replace(s) { m -> m.groupValues[1] + "…" }
        if (sourcePattern != null) s = sourcePattern.replace(s) { m -> sources[m.value] ?: m.value }
        val input = s
        s = hostPattern.replace(input) { m ->
            val host = m.value
            if (!isHost(input, m.range.first, m.range.last + 1, host)) {
                host
            } else {
                "host-" + synchronized(hostNumbers) { hostNumbers.getOrPut(host) { hostNumbers.size + 1 } }
            }
        }
        s = emailPattern.replace(s, "<email>")
        if (device != null && device.length >= 3) s = s.replace(device, "<device>", ignoreCase = true)
        return s
    }

    /**
     * A dotted name is a host only when it is preceded by `://`, whitespace, a quote, `(` or the start of the line,
     * isn't followed by `(` (a method call), isn't one of our tags, and either ends in a known TLD or was really
     * requested (NetStats' host table / a source's base URL).
     */
    private fun isHost(input: String, start: Int, end: Int, host: String): Boolean {
        if (host in keepHosts || host in notHosts) return false
        if (end < input.length && input[end] == '(') return false
        if (start > 0) {
            val before = input[start - 1]
            val ok = before.isWhitespace() || before == '"' || before == '\'' || before == '(' ||
                (before == '/' && start >= 3 && input.regionMatches(start - 3, "://", 0, 3))
            if (!ok) return false
        }
        if (host in knownHosts || host.removePrefix("www.") in knownHosts) return true
        return host.substringAfterLast('.') in TLDS
    }

    /** The mapping, for the local-only `-keys.json` */
    fun keysJson(): String = buildJsonObject {
        put("sources", buildJsonObject { sources.forEach { (k, v) -> put(v, k) } })
        put(
            "hosts",
            buildJsonObject { synchronized(hostNumbers) { hostNumbers.forEach { (k, v) -> put("host-$v", k) } } },
        )
        device?.let { put("device", it) }
    }.toString()

    companion object {
        /** Last labels that make a bare dotted name a host (the same list the Source Lab's IssueReport uses) */
        private val TLDS = setOf(
            "com", "net", "org", "io", "co", "to", "tv", "me", "cc", "xyz", "site", "online", "info", "biz", "us", "uk",
            "de", "fr", "es", "it", "nl", "ru", "br", "jp", "kr", "cn", "in", "id", "vn", "th", "ph", "tw", "hk", "sg",
            "pl", "cz", "tr", "ua", "mx", "ar", "cl", "ca", "au", "nz", "eu", "app", "dev", "cloud", "live", "stream",
            "video", "club", "pro", "top", "fun", "vip", "lol", "one", "zone", "world", "life", "space", "ws", "su",
            "is", "la", "ly", "sh", "st", "gg", "so", "ai", "mobi", "moe", "wiki", "red", "blue", "pw", "icu", "cfd",
            "sbs", "quest", "run", "watch", "art", "best", "cyou", "rest", "store", "shop", "news", "today", "cam",
        )

        fun build(context: Context, full: Boolean = true): Redactor {
            val sources = LinkedHashMap<String, String>()
            val hosts = HashSet<String>()
            fun hostOf(url: String?) = runCatching { url?.toHttpUrlOrNull()?.host?.lowercase() }.getOrNull()
            runCatching {
                Injekt.get<JsSourceManager>().savedFlow.value.forEach {
                    sources[it.name] = "source-${it.id}"
                    hostOf(it.baseUrl)?.let(hosts::add)
                }
            }
            runCatching {
                Injekt.get<tachiyomi.domain.source.service.SourceManager>().getOnlineSources().forEach { s ->
                    if (s.name !in sources) sources[s.name] = "source-${s.id}"
                    hostOf(s.baseUrl)?.let(hosts::add)
                }
            }
            runCatching {
                Injekt.get<eu.kanade.tachiyomi.animeextension.AnimeSourceManager>().getCatalogueSources().forEach { s ->
                    if (s.name !in sources) sources[s.name] = "source-${s.id}"
                    if (s is eu.kanade.tachiyomi.animesource.online.AnimeHttpSource) hostOf(s.baseUrl)?.let(hosts::add)
                }
            }
            runCatching { hosts.addAll(NetStats.knownHosts()) }
            val device = runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL.takeIf { it.isNotBlank() }
            return Redactor(sources, LinkedHashMap(), device, hosts, full)
        }
    }
}

/** A writer that redacts line by line (text may arrive in pieces; it buffers up to the last newline) */
internal class RedactingWriter(private val out: Writer, private val redactor: Redactor?) : Writer() {
    private val pending = StringBuilder()
    private val buffered = BufferedWriter(out, 32 * 1024)

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        if (redactor == null) {
            buffered.write(cbuf, off, len)
            return
        }
        pending.append(cbuf, off, len)
        var nl = pending.indexOf("\n")
        while (nl >= 0) {
            buffered.write(redactor.redactLine(pending.substring(0, nl)))
            buffered.write("\n")
            pending.delete(0, nl + 1)
            nl = pending.indexOf("\n")
        }
    }

    override fun flush() {
        if (redactor != null && pending.isNotEmpty()) {
            buffered.write(redactor.redactLine(pending.toString()))
            pending.setLength(0)
        }
        buffered.flush()
    }

    override fun close() = flush()
}
