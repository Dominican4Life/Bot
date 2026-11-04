package eu.kanade.tachiyomi.jssource

import android.content.Context
import android.content.SharedPreferences
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import eu.kanade.tachiyomi.diagnostics.JsTrace
import eu.kanade.tachiyomi.diagnostics.JsTraceEvent
import eu.kanade.tachiyomi.diagnostics.Perf
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.SourceTag
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * KMK: Runs one JS / JSON source in QuickJS: its prelude and standard library
 * first, then the source, and every host call (`sendMessage(channel, json)`) answered here with a JSON string.
 *
 * Calls are made one at a time per runtime. The console and every request also go to the app's Logs screen.
 */
class JsRuntime private constructor(
    private val qjs: QuickJs,
    private val host: Host,
    private val watchdog: Watchdog,
) {

    private val mutex = Mutex()

    // KMK (r97) -->
    /** QuickJS memory in use: taken only when no call holds the engine (tryLock), else the last figure */
    @Volatile
    private var lastMemory: Long = 0L

    /** True when the last [memoryBytes] figure is from an earlier read because the runtime was busy */
    @Volatile
    var memoryStale: Boolean = false
        private set

    fun memoryBytes(): Long? {
        if (!mutex.tryLock()) {
            memoryStale = true
            return lastMemory.takeIf { it > 0L }
        }
        try {
            memoryStale = false
            lastMemory = qjs.memoryUsage.memoryUsedSize
            return lastMemory
        } catch (e: Throwable) {
            return lastMemory.takeIf { it > 0L }
        } finally {
            mutex.unlock()
        }
    }

    /** Parsed pages kept, their text bytes and a rough DOM size */
    fun pageStats(): Triple<Int, Long, Long> = host.pageStats()

    /** A call is running */
    val isBusy: Boolean get() = mutex.isLocked

    /** When [call] last ran (epoch ms); 0 = never */
    @Volatile
    var lastCallAt: Long = 0L
        private set
    // KMK <--

    /** Lines from console.log and requests, for the editor's Console */
    val console: List<String> get() = synchronized(host.console) { host.console.map { it.line } }

    // KMK (r102): the same lines with their time and, for a test copy's requests, the headers / failed body
    val consoleEntries: List<JsConsoleEntry> get() = synchronized(host.console) { host.console.toList() }

    fun clearConsole() = synchronized(host.console) { host.console.clear() }

    /** KMK (r102): told of every console line as it is written (the Source Lab's Test Run console) */
    var onConsole: ((JsConsoleEntry) -> Unit)?
        get() = host.onConsole
        set(value) {
            host.onConsole = value
        }

    // KMK (r96): the Source Lab's Test Run names the step it is on; console lines then read `[net][popular] …`
    var stepTag: String?
        get() = host.stepTag
        set(value) {
            host.stepTag = value
        }

    /** name, lang, iconUrl, baseUrl, hasPopular, hasLatest, hasDetails, hasFilters, hasTags, hasRelated, hasCatalogs, hasPreferences, rateLimitMs, headers */
    suspend fun metadata(): JsonObject = mutex.withLock {
        val text = watchdog.guard { qjs.evaluate<String>(METADATA_SCRIPT, "<metadata>") }
        jsJson.parseToJsonElement(text).jsonObject.also { meta ->
            host.rateLimitMs = meta.long("rateLimitMs") ?: 0L
        }
    }

    /** Which of the main methods the source defines */
    suspend fun has(method: String): Boolean = mutex.withLock {
        watchdog.guard { qjs.evaluate<Boolean>("typeof extension.$method === 'function'", "<has>") }
    }

    /**
     * Calls `extension[method](args...)` and returns what it returned, as JSON. A method the source doesn't have
     * throws [NotImplementedError].
     */
    suspend fun call(method: String, vararg args: Any?): JsonElement = mutex.withLock {
        val argList = args.joinToString(", ") { it.toJsonElement().toString() }
        val code = """
            await (async function () {
              var fn = typeof extension.$method === 'function' ? extension.$method.bind(extension) : null;
              if (!fn && '$method' === 'getSourcePreferences' && typeof getSourcePreferences === 'function') {
                fn = getSourcePreferences;
              }
              if (!fn) throw new Error('__NOT_IMPLEMENTED__:$method');
              var result = await fn($argList);
              return JSON.stringify(result === undefined ? null : result);
            })()
        """.trimIndent()
        // KMK (r97) -->
        // Diagnostics: the js trace / probes (one volatile read when off), a Perfetto section around the call
        lastCallAt = System.currentTimeMillis()
        val traced = JsTrace.wanted()
        val startedNs = if (traced) System.nanoTime() else 0L
        val memBefore = if (traced) runCatching { qjs.memoryUsage.memoryUsedSize }.getOrDefault(0L) else 0L
        val pagesBefore = if (traced) host.pagesParsed.get() else 0
        val callsBefore = if (traced) host.hostCalls.get() else 0
        if (traced) JsTrace.before(host.sourceName, method, argList.take(120))
        // KMK <--
        val text = try {
            Perf.traced({ "js:${host.sourceName}.$method" }) { // the name is built only while a trace records
                withContext(Dispatchers.Default) { watchdog.guard { qjs.evaluate<String?>(code, "<$method>") } }
            }
        } catch (e: Throwable) {
            // The caller stopped waiting (e.g. the user left Browse): let the cancellation through instead of
            // turning it into a source error, which was logged as "getLatest failed: Job was cancelled"
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            val message = e.message.orEmpty()
            if ("__NOT_IMPLEMENTED__" in message) throw NotImplementedError("This source has no $method")
            host.log("error", "$method failed: $message")
            runCatching { host.health?.error(method, message) } // KMK (r96): Source Lab health counters
            // KMK (r97) -->
            if (traced) {
                JsTrace.after(
                    traceEvent(method, argList, startedNs, memBefore, pagesBefore, callsBefore, null, message),
                )
            }
            // KMK <--
            throw JsException(message.substringBefore("\n    at ").ifBlank { e.toString() }, e)
        }
        // KMK (r97) -->
        if (traced) JsTrace.after(traceEvent(method, argList, startedNs, memBefore, pagesBefore, callsBefore, text, null))
        // KMK <--
        jsJson.parseToJsonElement(text ?: "null")
    }

    // KMK (r97) -->
    /** One js trace record for a call that just ended (under the mutex, so memoryUsage needs no tryLock) */
    private fun traceEvent(
        method: String,
        argList: String,
        startedNs: Long,
        memBefore: Long,
        pagesBefore: Int,
        callsBefore: Int,
        result: String?,
        error: String?,
    ): JsTraceEvent {
        // Items of a top-level array, counted with a small scanner (not a second JSON parse of a result that can be MBs)
        val items = result?.let { r -> countTopLevelItems(r) } ?: -1
        return JsTraceEvent(
            t = System.currentTimeMillis(),
            source = host.sourceName,
            fn = method,
            args = argList.take(120),
            ms = (System.nanoTime() - startedNs) / 1_000_000,
            hostCalls = host.hostCalls.get() - callsBefore,
            pagesParsed = host.pagesParsed.get() - pagesBefore,
            parseMs = host.parseNanos.getAndSet(0L) / 1_000_000,
            memBefore = memBefore,
            memAfter = runCatching { qjs.memoryUsage.memoryUsedSize }.getOrDefault(0L).also { lastMemory = it },
            items = items,
            error = error?.substringBefore("\n    at "),
            jsStack = error?.lines()?.filter { it.trimStart().startsWith("at ") }?.take(6).orEmpty(),
        )
    }

    /** Elements of a top-level JSON array in [json], or -1 when it isn't one; one pass, no allocation */
    private fun countTopLevelItems(json: String): Int {
        var i = 0
        val n = json.length
        while (i < n && json[i].isWhitespace()) i++
        if (i >= n || json[i] != '[') return -1
        var depth = 0
        var count = 0
        var inString = false
        var sawValue = false
        while (i < n) {
            val c = json[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> { inString = true; if (depth == 1) sawValue = true }
                    '[', '{' -> { if (depth == 1) sawValue = true; depth++ }
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return if (sawValue) count + 1 else count
                    }
                    ',' -> if (depth == 1) { count++; sawValue = false }
                    ' ', '\n', '\r', '\t' -> Unit
                    else -> if (depth == 1) sawValue = true
                }
            }
            i++
        }
        return -1
    }
    // KMK <--

    fun close() {
        runCatching { qjs.close() }
        // KMK: and its parsed pages go with it (a tag download's copy kept up to 24 big pages after it finished)
        runCatching { host.clearPages() }
        runCatching { host.health?.flush() } // KMK (r96)
        // KMK (r103 fix pass): unsaved host.storage changes are written (now off the main thread, else on the writer)
        runCatching {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) JsStorage.flushAllSoon() else JsStorage.flushAllNow()
        }
    }

    class JsException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * KMK: A time limit on JS that runs without stopping. A source whose code loops without awaiting held its only
     * engine forever (Browse, details, player and downloads of that source all hung until a restart). Only time
     * spent running JS counts: while a host call (fetch, render, sleep…) is being answered the clock doesn't run,
     * so a slow but working source (a tag download of several minutes) isn't stopped. Past [LIMIT_MS] of JS without
     * a host call answered, the evaluation is interrupted through QuickJS's interrupt handler
     * ([QuickJs.interruptEvaluation]), which stops even `while (true) {}`, and the engine stays usable.
     *
     * quickjs-kt's own evaluationTimeoutMillis isn't used: it is a deadline from the start of the evaluation, so it
     * would also stop a long download as soon as its JS ran again after the deadline.
     */
    internal class Watchdog(private val name: String) {

        @Volatile
        var qjs: QuickJs? = null

        private val hostCalls = AtomicInteger(0)

        /** When JS last got control back (an evaluation started or a host call was answered), System.nanoTime */
        @Volatile
        private var lastReturn = 0L

        @Volatile
        private var fired = false

        val message: String get() = "$name: its code ran for ${LIMIT_MS / 1000} s without stopping, so it was stopped"

        fun hostCallStarted() {
            hostCalls.incrementAndGet()
        }

        fun hostCallFinished() {
            lastReturn = System.nanoTime()
            hostCalls.decrementAndGet()
        }

        /**
         * Runs [block] (one evaluation) with the time limit watched. When the limit stopped it, it fails with a
         * [JsException] that says so (and an E "js" line in Logs).
         */
        suspend fun <T> guard(block: suspend () -> T): T = coroutineScope {
            fired = false
            lastReturn = System.nanoTime()
            val watcher = launch(Dispatchers.Default) {
                while (true) {
                    delay(CHECK_EVERY_MS)
                    val runningMs = (System.nanoTime() - lastReturn) / 1_000_000
                    if (hostCalls.get() <= 0 && runningMs >= LIMIT_MS) {
                        fired = true
                        runCatching { qjs?.interruptEvaluation() }
                        break
                    }
                }
            }
            try {
                block()
            } catch (e: Throwable) {
                if (e !is CancellationException && fired) {
                    fired = false
                    AppLog.add('E', "js", message)
                    throw JsException(message, e)
                }
                throw e
            } finally {
                watcher.cancel()
            }
        }

        companion object {
            const val LIMIT_MS = 30_000L
            private const val CHECK_EVERY_MS = 1_000L
        }
    }

    /** Everything the source can ask of the app */
    internal class Host(
        private val context: Context,
        private val tag: String,
        storageDir: File,
        private val preferences: SharedPreferences,
        val testing: Boolean,
        // KMK (r97): who this runtime is, for the Diagnostics network attribution (SourceTag on every request)
        val sourceId: Long = 0L,
        val key: String? = null,
    ) {
        private val network: NetworkHelper by injectLazy()
        private val pages = JsHtmlPages()

        // KMK (r97) -->
        /** The source's name (the tag without its `js:` prefix) */
        val sourceName: String get() = tag.removePrefix("js:")

        /** Pages parsed, host calls answered and time spent parsing, for the js trace */
        val pagesParsed = AtomicInteger(0)
        val hostCalls = AtomicInteger(0)
        val parseNanos = java.util.concurrent.atomic.AtomicLong(0L)

        fun pageStats(): Triple<Int, Long, Long> = pages.stats()
        // KMK <--

        /** KMK: lets go of the parsed pages (the runtime shutting down) */
        fun clearPages() = pages.clear()
        val console = ArrayDeque<JsConsoleEntry>()

        // KMK (r102): see JsRuntime.onConsole
        @Volatile
        var onConsole: ((JsConsoleEntry) -> Unit)? = null
        var rateLimitMs: Long = 0L
        private var lastRequestAt = 0L

        // KMK --> (r96) Source Lab: health counters of the real source (null for test copies and probes), and the
        // Test Run's step tag, written into every console line while set
        var health: SourceHealth.Sink? = null

        @Volatile
        var stepTag: String? = null
        // KMK <--

        /** Default values from getSourcePreferences(), used until the user changes one */
        var preferenceDefaults: Map<String, JsonElement> = emptyMap()

        // The app's connection without its Cloudflare helper: a bot check is passed in a browser page instead
        // (JsBrowserFetch), which gets through where the helper can't
        private val client by lazy {
            network.nonCloudflareClient.newBuilder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun log(level: String, text: String, detail: String? = null) {
            val entry = JsConsoleEntry(System.currentTimeMillis(), level, stepTag, text, detail)
            synchronized(console) {
                console.addLast(entry)
                while (console.size > 500) console.removeFirst()
            }
            runCatching { onConsole?.invoke(entry) }
            val l = when (level) {
                "error" -> 'E'
                "warn" -> 'W'
                "debug" -> 'D'
                else -> 'I'
            }
            // KMK (r102): the global "JS sources: debug logs" switch; warnings and errors always reach Logs
            if (l == 'W' || l == 'E' || JsDebugLogs.enabled()) AppLog.add(l, tag, text)
        }

        /** KMK (r102): what a test copy's request sent and got, for the Test Run console (cookies hidden) */
        private fun netDetail(sent: Map<String, String>, received: Headers?, failedBody: String?): String = buildString {
            append("Request headers\n")
            sent.forEach { (k, v) -> append("  ").append(k).append(": ").append(hideSecret(k, v)).append('\n') }
            if (received != null) {
                append("Response headers\n")
                received.names().forEach { name ->
                    append("  ").append(name).append(": ")
                        .append(hideSecret(name, received.values(name).joinToString(", "))).append('\n')
                }
            }
            if (failedBody != null) append("Body (first 2 KB)\n").append(failedBody)
        }.take(8_000)

        private fun hideSecret(name: String, value: String): String {
            val n = name.lowercase()
            return if (n == "cookie" || n == "set-cookie" || n == "authorization" || n == "proxy-authorization") {
                "<set>"
            } else {
                value.take(300)
            }
        }

        suspend fun handle(channel: String, payloadJson: String): String {
            hostCalls.incrementAndGet() // KMK (r97)
            val payload = runCatching { jsJson.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
            val result: Any? = try {
                when (channel) {
                    "fetchUrl" -> fetch(payload)
                    "queryHtml" -> pages.query(payload.str("html").orEmpty(), payload.str("selector").orEmpty())
                    "parseHtml" -> {
                        // KMK (r97): counted and timed for the js trace
                        val t0 = System.nanoTime()
                        val id = pages.parse(payload.str("html").orEmpty())
                        pagesParsed.incrementAndGet()
                        parseNanos.addAndGet(System.nanoTime() - t0)
                        mapOf("id" to id)
                    }
                    "selectHtml" -> pages.select(
                        pageId = payload.int("page") ?: 0,
                        from = payload.int("from"),
                        css = payload.str("css"),
                        xpath = payload.str("xpath"),
                        withHtml = payload.bool("html") ?: true,
                        limit = payload.int("limit"),
                    )
                    "log" -> {
                        log(payload.str("level") ?: "log", payload.str("text").orEmpty())
                        mapOf("ok" to true)
                    }
                    "trace" -> mapOf("ok" to true)
                    "render" -> JsRender.render(context, payload)
                    "findVideo" -> VideoFinder.find( // KMK (r100)
                        context,
                        payload.str("url") ?: throw IllegalArgumentException("host.findVideo needs a url"),
                        payload.str("frame"),
                        payload.str("referer"),
                        payload.long("timeout") ?: 45_000L,
                        // KMK (r103 fix pass): kept results are per source; a test runtime (Source Lab, Code
                        // editor, Re-test) or `{ fresh: true }` always finds anew
                        sourceKey = key ?: "id:$sourceId",
                        fresh = testing || payload.bool("fresh") == true,
                    ).toMap()
                    "hash" -> mapOf("hash" to JsCrypto.hash(payload.str("algorithm").orEmpty(), payload.str("input").orEmpty()))
                    "aes" -> mapOf(
                        "value" to JsCrypto.aes(
                            encrypt = payload.bool("encrypt") ?: false,
                            data = payload.str("data").orEmpty(),
                            mode = payload.str("mode") ?: "cbc",
                            key = payload.str("key"),
                            iv = payload.str("iv"),
                            passphrase = payload.str("passphrase"),
                            keyEncoding = payload.str("keyEncoding"),
                            ivEncoding = payload.str("ivEncoding"),
                            dataEncoding = payload.str("dataEncoding"),
                            output = payload.str("output"),
                            padding = payload.str("padding") ?: "pkcs7",
                        ),
                    )
                    "hmac" -> mapOf(
                        "value" to JsCrypto.hmac(
                            payload.str("algorithm").orEmpty(),
                            payload.str("key").orEmpty(),
                            payload.str("input").orEmpty(),
                            payload.str("keyEncoding"),
                            payload.str("output"),
                        ),
                    )
                    "sleep" -> {
                        delay((payload.long("ms") ?: 0L).coerceIn(0L, 10_000L))
                        mapOf("ok" to true)
                    }
                    "cookiesGet" -> {
                        val url = payload.str("url").orEmpty().toHttpUrl()
                        mapOf("cookies" to network.cookieJar.get(url).map { mapOf("name" to it.name, "value" to it.value) })
                    }
                    "cookiesSet" -> {
                        val url = payload.str("url").orEmpty().toHttpUrl()
                        val cookie = Cookie.Builder()
                            .name(payload.str("name").orEmpty())
                            .value(payload.str("value").orEmpty())
                            .apply {
                                val domain = payload.str("domain")?.trimStart('.') ?: url.host
                                domain(domain)
                                path(payload.str("path") ?: "/")
                                payload.long("maxAge")?.let { expiresAt(System.currentTimeMillis() + it * 1000) }
                                if (payload.bool("secure") == true) secure()
                                if (payload.bool("httpOnly") == true) httpOnly()
                            }
                            .build()
                        network.cookieJar.saveFromResponse(url, listOf(cookie))
                        mapOf("ok" to true)
                    }
                    "gzipDecode" -> bytesOp(payload, "base64", null) { JsCompress.gzipDecode(it) }
                    "gzipEncode" -> bytesOp(payload, "utf8", "base64") { JsCompress.gzipEncode(it) }
                    "deflateDecode" -> bytesOp(payload, "base64", null) {
                        JsCompress.deflateDecode(it, payload.bool("raw") ?: false)
                    }
                    "deflateEncode" -> bytesOp(payload, "utf8", "base64") { JsCompress.deflateEncode(it) }
                    "rsaEncrypt" -> mapOf(
                        "value" to JsCrypto.rsaEncrypt(
                            payload.str("data").orEmpty(),
                            payload.str("publicKeyPem").orEmpty(),
                            payload.str("padding") ?: "oaep",
                            payload.str("hash") ?: "sha256",
                            payload.str("dataEncoding"),
                            payload.str("output"),
                        ),
                    )
                    "rsaVerify" -> mapOf(
                        "ok" to JsCrypto.rsaVerify(
                            payload.str("data").orEmpty(),
                            payload.str("signature").orEmpty(),
                            payload.str("publicKeyPem").orEmpty(),
                            payload.str("hash") ?: "sha256",
                            payload.str("dataEncoding"),
                            payload.str("signatureEncoding"),
                        ),
                    )
                    "zipEntries" -> mapOf(
                        "entries" to JsCompress.zipEntries(
                            JsBytes.decode(payload.str("data").orEmpty(), payload.str("encoding") ?: "base64"),
                        ),
                    )
                    "zipRead" -> mapOf(
                        "value" to JsBytes.encode(
                            JsCompress.zipRead(
                                JsBytes.decode(payload.str("data").orEmpty(), payload.str("encoding") ?: "base64"),
                                payload.str("name").orEmpty(),
                            ),
                            payload.str("output"),
                        ),
                    )
                    "storageGet" -> mapOf("value" to storage.get(payload.str("key").orEmpty()))
                    "storageSet" -> {
                        storage.edit { it[payload.str("key").orEmpty()] = payload["value"] ?: JsonNull }
                        mapOf("ok" to true)
                    }
                    "storageDelete" -> {
                        storage.edit { it.remove(payload.str("key").orEmpty()) }
                        mapOf("ok" to true)
                    }
                    "storageClear" -> {
                        storage.edit { it.clear() }
                        mapOf("ok" to true)
                    }
                    "storageAll" -> mapOf("values" to storage.all())
                    "preferenceGet" -> mapOf("value" to preference(payload.str("key").orEmpty()))
                    "preferenceSet" -> {
                        JsPreferences.write(preferences, payload.str("key").orEmpty(), payload["value"])
                        mapOf("ok" to true)
                    }
                    "preferenceAll" -> mapOf(
                        "values" to (preferenceDefaults.keys + preferences.all.keys).associateWith { preference(it) },
                    )
                    else -> mapOf("error" to "Unknown host call \"$channel\"")
                }
            } catch (e: Throwable) {
                if (channel == "fetchUrl") {
                    log("warn", "fetch failed: ${e.message}", payload.str("url")?.let { "Address: $it" })
                }
                mapOf("error" to (e.message ?: e.toString()))
            }
            return result.toJsonElement().toString()
        }

        private fun preference(key: String): JsonElement? =
            if (preferences.contains(key)) preferences.all[key].toJsonElement() else preferenceDefaults[key]

        private fun bytesOp(
            payload: JsonObject,
            inputEncoding: String,
            outputEncoding: String?,
            op: (ByteArray) -> ByteArray,
        ): Map<String, Any?> {
            val bytes = JsBytes.decode(payload.str("data").orEmpty(), payload.str("encoding") ?: inputEncoding)
            return mapOf("value" to JsBytes.encode(op(bytes), payload.str("output") ?: outputEncoding))
        }

        // Storage: one JSON file per source, as storage.json. KMK: one shared copy in memory per folder, so every
        // runtime of the same source sees what the others wrote (JsStorage)
        private val storage = JsStorage.of(storageDir)

        // Requests

        private suspend fun fetch(payload: JsonObject): Map<String, Any?> = withContext(Dispatchers.IO) {
            runCatching { health?.request() } // KMK (r96)
            val url = payload.str("url").orEmpty().trim()
            val options = payload.obj("options") ?: JsonObject(emptyMap())
            val method = (options.str("method") ?: "GET").uppercase()
            val binary = options.bool("binary") ?: false
            val headers = Headers.Builder()
            options.obj("headers")?.forEach { (key, value) ->
                (value as? JsonPrimitive)?.content?.let { runCatching { headers.set(key, it) } }
            }
            if (headers["User-Agent"] == null) headers.set("User-Agent", network.defaultUserAgentProvider())
            val body = options.str("body")
            val requestBody = when {
                body != null -> body.toRequestBody(headers["Content-Type"]?.toMediaTypeOrNull())
                method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT") -> ByteArray(0).toRequestBody()
                else -> null
            }

            if (rateLimitMs > 0) {
                val wait = lastRequestAt + rateLimitMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastRequestAt = System.currentTimeMillis()
            }

            val origin = JsBrowserFetch.originOf(url)
            val plainHeaders = headers.build().toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            suspend fun viaBrowser(): Map<String, Any?> {
                val result = JsBrowserFetch.fetch(context, url, method, plainHeaders, body, binary, sourceId = sourceId) // KMK (r116): waits show for this source
                if (testing) {
                    val code = (result["statusCode"] as? Number)?.toInt() ?: result["statusCode"]?.toString()?.toIntOrNull()
                    val failed = if (code == null || code >= 400) (result["body"] as? String)?.take(2048) else null
                    // KMK (r106): the reply's size for the Test Run (`· N bytes`, parsed by TestRuns.parseNetLine)
                    val size = (result["body"] as? String)?.toByteArray()?.size
                        ?: (result["bodyBase64"] as? String)?.let { it.length / 4 * 3 }
                    log(
                        "net",
                        "$method $url -> ${result["statusCode"]} (through the browser)" + (size?.let { " · $it bytes" }.orEmpty()),
                        netDetail(plainHeaders, null, failed),
                    )
                }
                return result
            }
            // Binary replies (images, archives) never go through the browser page
            if (!binary && origin != null && origin in JsBrowserFetch.browserFirst) {
                return@withContext viaBrowser()
            }

            val started = System.currentTimeMillis()
            val request = Request.Builder().url(url).headers(headers.build()).method(method, requestBody)
                .tag(SourceTag::class.java, SourceTag(sourceId, sourceName, key)) // KMK (r97): Diagnostics attribution
                .build()
            val plain = client.newCall(request).execute().use { response ->
                val took = System.currentTimeMillis() - started
                // KMK (r102): logged after the body is read, so a failed request's first 2 KB go with its line
                val read = runCatching { response.body.bytes() }
                if (testing) {
                    val failed = if (response.code >= 400) {
                        read.getOrNull()?.let { b ->
                            String(b, 0, minOf(b.size, 2048), response.body.contentType()?.charset() ?: Charsets.UTF_8)
                        }
                    } else {
                        null
                    }
                    // KMK (r106): the reply's size for the Test Run: the body as read, else Content-Length
                    val size = read.getOrNull()?.size?.toLong() ?: response.body.contentLength().takeIf { it >= 0 }
                    log(
                        "net",
                        "$method $url -> ${response.code} in ${took}ms" + (size?.let { " · $it bytes" }.orEmpty()),
                        netDetail(plainHeaders, response.headers, failed),
                    )
                }
                val responseHeaders = response.headers.names().associate { name ->
                    name.lowercase() to response.headers.values(name).joinToString(",")
                }
                val bytes = read.getOrThrow()
                val text = if (binary) "" else String(bytes, response.body.contentType()?.charset() ?: Charsets.UTF_8)
                if (!binary && origin != null &&
                    JsBrowserFetch.isChallenge(response.code, response.header("Server"), text)
                ) {
                    null
                } else {
                    buildMap<String, Any?> {
                        put("statusCode", response.code)
                        put("url", response.request.url.toString())
                        if (binary) put("bodyBase64", JsBytes.encode(bytes, "base64")) else put("body", text)
                        put("headers", responseHeaders)
                    }
                }
            }
            if (plain != null) return@withContext plain
            // A bot check answered: ask from inside a browser page on the site, and keep doing so for this site
            log("warn", "A bot check answered $url: sending it from a browser page on the site instead")
            val result = viaBrowser()
            JsBrowserFetch.browserFirst += origin!!
            result
        }
    }

    companion object {

        @Volatile
        private var nativeLoaded = false

        /**
         * The engine's native library is packaged as libquickjskt.so (libquickjs.so is the one extensions use),
         * so it is loaded here, before the engine's own classes look for their native methods.
         */
        @Synchronized
        internal fun loadNativeLibrary() { // KMK (r97): internal, the Diagnostics probe runtime loads it too
            if (nativeLoaded) return
            System.loadLibrary("quickjskt")
            nativeLoaded = true
        }

        /** KMK: where a source's host.storage is kept */
        internal fun storageDir(context: Context, key: String): File = File(context.filesDir, "js_storage/$key")

        /**
         * KMK: the storage folder of the Code editor's test copy of [key]. A "." can't be in a key (KEY_PATTERN), so
         * this never meets a real source's folder; r86's "<key>_editor" could (a source keyed "abc_editor")
         */
        internal fun testStorageKey(key: String): String = "$key.editor"

        /** KMK: r86's test copy folder name, only to clean it up */
        internal fun legacyTestStorageKey(key: String): String = "${key}_editor"

        private fun asset(context: Context, name: String): String =
            context.assets.open("jssource/$name").bufferedReader().use { it.readText() }

        /**
         * Starts a runtime for [code], a JS source or (when [json]) a JSON declarative one. Throws with the
         * message when the source doesn't define what a source needs.
         */
        suspend fun create(
            context: Context,
            key: String,
            sourceId: Long,
            name: String,
            code: String,
            json: Boolean,
            testing: Boolean = false,
            // KMK: the Code editor's test copy: its own settings and storage (testStorageKey, JsPreferences.testOf),
            // so a test doesn't change the saved source
            testCopy: Boolean = false,
            // KMK (r96): false for a runtime that isn't the source serving the app (the save-time describe() run,
            // JsTags' suggestions), so its requests and errors don't land in the Source Lab's health counters
            health: Boolean = true,
        ): JsRuntime {
            val host = Host(
                context = context.applicationContext,
                tag = "js:$name",
                storageDir = storageDir(context, if (testCopy) testStorageKey(key) else key),
                preferences = if (testCopy) {
                    JsPreferences.testOf(context, sourceId)
                } else {
                    JsPreferences.of(context, sourceId)
                },
                testing = testing,
                // KMK (r97)
                sourceId = sourceId,
                key = key,
            )
            // KMK (r96): only the real source counts towards its health (not the editor's test copy, a probe, the
            // describe run of a save or the tag suggestions)
            if (health && !testing && !testCopy) host.health = runCatching { SourceHealth.of(context).sink(key) }.getOrNull()
            loadNativeLibrary()
            val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
            val watchdog = Watchdog(name).also { it.qjs = qjs }
            try {
                qjs.maxStackSize = 2L shl 20
                qjs.memoryLimit = 128L shl 20
                qjs.defineBinding(
                    "sendMessage",
                    AsyncFunctionBinding<String> { args ->
                        // KMK: time spent answering doesn't count towards the JS time limit
                        watchdog.hostCallStarted()
                        try {
                            host.handle(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString() ?: "{}")
                        } finally {
                            watchdog.hostCallFinished()
                        }
                    },
                )
                qjs.evaluate<Any?>(asset(context, "prelude.js"), "prelude.js")
                runCatching { qjs.evaluate<Any?>(asset(context, "stdlib.js"), "stdlib.js") }
                    .onFailure { host.log("warn", "Standard library failed to load: ${it.message}") }
                qjs.evaluate<Any?>(
                    "globalThis.host.isTesting = $testing; globalThis.host.cacheExtensionDataEnabled = true;",
                    "<flags>",
                )
                val source = if (json) {
                    val config = stripJsonComments(code).trim()
                    // Checked here so a typo is reported as a JSON error, not a JS one
                    jsJson.parseToJsonElement(config)
                    "var __sourceConfig = $config;\n" + asset(context, "json_engine.js")
                } else {
                    code
                }
                // KMK: the source's top-level code is watched too (a loop there hung the save or the first Browse)
                watchdog.guard { qjs.evaluate<Any?>(source, if (json) "source.json" else "source.js") }
                val problem = qjs.evaluate<String>(CONTRACT_SCRIPT, "<contract>")
                if (problem.isNotEmpty()) throw JsException(problem)
            } catch (e: Throwable) {
                qjs.close()
                throw if (e is JsException) e else JsException(e.message ?: e.toString(), e)
            }
            return JsRuntime(qjs, host, watchdog).also { runtime ->
                // Defaults for host.preference.get before the user has changed anything
                if (runtime.has("getSourcePreferences") || runCatching {
                        qjs.evaluate<Boolean>("typeof getSourcePreferences === 'function'", "<prefs>")
                    }.getOrDefault(false)
                ) {
                    runCatching {
                        val defs = JsPreferences.parse(runtime.call("getSourcePreferences"))
                        host.preferenceDefaults = defs.mapNotNull { def -> def.defaultValue?.let { def.key to it } }.toMap()
                    }
                }
            }
        }

        private val CONTRACT_SCRIPT = """
            (function () {
              if (typeof extension !== 'object' || extension === null) return 'no global "extension" object is defined';
              // getPopular and getLatest may be left out by a source whose tab catalogs replace them
              var hasCatalogs = typeof extension.getCatalogs === 'function' && typeof extension.getCatalog === 'function';
              var required = hasCatalogs ? ['search', 'getChapterList'] : ['search', 'getPopular', 'getLatest', 'getChapterList'];
              var missing = required.filter(function (n) { return typeof extension[n] !== 'function'; });
              if (missing.length) return 'missing required method(s): ' + missing.join(', ');
              var hasContent = ['getPageList', 'getVideoList'].some(function (n) { return typeof extension[n] === 'function'; });
              return hasContent ? '' : 'define getPageList (manga, novels) or getVideoList (anime)';
            })()
        """.trimIndent()

        private val METADATA_SCRIPT = """
            (function () {
              function pick(onExtension, globalValue) {
                if (typeof extension[onExtension] === 'string') return extension[onExtension];
                if (typeof globalValue === 'string') return globalValue;
                return null;
              }
              var cfg = typeof __sourceConfig === 'object' && __sourceConfig !== null ? __sourceConfig : null;
              var headers = (typeof extension.headers === 'object' && extension.headers !== null && !Array.isArray(extension.headers))
                ? extension.headers
                : (typeof HEADERS === 'object' && HEADERS !== null && !Array.isArray(HEADERS))
                  ? HEADERS
                  : (cfg && typeof cfg.headers === 'object' && !Array.isArray(cfg.headers)) ? cfg.headers : null;
              return JSON.stringify({
                name: pick('name', typeof NAME === 'undefined' ? undefined : NAME) || (cfg && cfg.name) || null,
                lang: pick('lang', typeof LANG === 'undefined' ? undefined : LANG) || (cfg && cfg.lang) || null,
                iconUrl: pick('iconUrl', typeof ICON_URL === 'undefined' ? undefined : ICON_URL) || (cfg && cfg.iconUrl) || null,
                baseUrl: pick('baseUrl', typeof BASE_URL === 'undefined' ? undefined : BASE_URL) || (cfg && cfg.baseUrl) || null,
                popularName: pick('popularName', typeof POPULAR_NAME === 'undefined' ? undefined : POPULAR_NAME),
                latestName: pick('latestName', typeof LATEST_NAME === 'undefined' ? undefined : LATEST_NAME),
                hasPopular: typeof extension.getPopular === 'function',
                hasLatest: typeof extension.getLatest === 'function',
                hasDetails: typeof extension.getDetails === 'function',
                hasFilters: typeof extension.getFilters === 'function',
                hasTags: typeof extension.getTags === 'function',
                hasRelated: typeof extension.getRelated === 'function',
                hasCatalogs: typeof extension.getCatalogs === 'function' && typeof extension.getCatalog === 'function',
                hasPreferences: typeof extension.getSourcePreferences === 'function' || typeof getSourcePreferences === 'function',
                hasVideos: typeof extension.getVideoList === 'function',
                hasPages: typeof extension.getPageList === 'function',
                rateLimitMs: typeof extension.rateLimitMs === 'number' ? extension.rateLimitMs
                  : (typeof RATE_LIMIT_MS === 'number' ? RATE_LIMIT_MS : null),
                headers: headers,
                // a source id is 64 bits, more than a JS number holds exactly: written in quotes it stays exact
                id: (extension.id === undefined || extension.id === null) ? null : String(extension.id),
              });
            })()
        """.trimIndent()
    }
}

/**
 * KMK (r102): one console line of a runtime: when it was written, its level (log, info, debug, warn, error, net),
 * the Test Run step it belongs to, its text and, for a test copy's request, the headers and a failed reply's body.
 */
data class JsConsoleEntry(
    val at: Long,
    val level: String,
    val step: String?,
    val text: String,
    val detail: String? = null,
) {
    /** The editor's form: `[net][popular] GET … -> 200 in 85ms` */
    val line: String get() = "[$level]${step?.let { "[$it]" }.orEmpty()} $text"
}
