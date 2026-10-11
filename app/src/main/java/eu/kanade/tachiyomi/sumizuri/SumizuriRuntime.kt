package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import android.content.SharedPreferences
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/**
 * KMK: Runs one Sumizuri JS / JSON source in QuickJS, the same way Sumizuri does: its prelude and standard library
 * first, then the source, and every host call (`sendMessage(channel, json)`) answered here with a JSON string.
 *
 * Calls are made one at a time per runtime. The console and every request also go to the app's Logs screen.
 */
class SumizuriRuntime private constructor(
    private val qjs: QuickJs,
    private val host: Host,
) {

    private val mutex = Mutex()

    /** Lines from console.log and requests, for the editor's Console */
    val console: List<String> get() = host.console.toList()

    fun clearConsole() = host.console.clear()

    /** name, lang, iconUrl, baseUrl, hasDetails, hasFilters, hasTags, hasCatalogs, hasPreferences, rateLimitMs, headers */
    suspend fun metadata(): JsonObject = mutex.withLock {
        val text = qjs.evaluate<String>(METADATA_SCRIPT, "<metadata>")
        sumizuriJson.parseToJsonElement(text).jsonObject.also { meta ->
            host.rateLimitMs = meta.long("rateLimitMs") ?: 0L
        }
    }

    /** Which of the main methods the source defines */
    suspend fun has(method: String): Boolean = mutex.withLock {
        qjs.evaluate<Boolean>("typeof extension.$method === 'function'", "<has>")
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
        val text = try {
            withContext(Dispatchers.Default) { qjs.evaluate<String?>(code, "<$method>") }
        } catch (e: Throwable) {
            // The caller stopped waiting (e.g. the user left Browse): let the cancellation through instead of
            // turning it into a source error, which was logged as "getLatest failed: Job was cancelled"
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            val message = e.message.orEmpty()
            if ("__NOT_IMPLEMENTED__" in message) throw NotImplementedError("This source has no $method")
            host.log("error", "$method failed: $message")
            throw SumizuriException(message.substringBefore("\n    at ").ifBlank { e.toString() }, e)
        }
        sumizuriJson.parseToJsonElement(text ?: "null")
    }

    fun close() {
        runCatching { qjs.close() }
    }

    class SumizuriException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Everything the source can ask of the app */
    internal class Host(
        private val context: Context,
        private val tag: String,
        private val storageDir: File,
        private val preferences: SharedPreferences,
        val testing: Boolean,
    ) {
        private val network: NetworkHelper by injectLazy()
        private val pages = SumizuriHtmlPages()
        val console = ArrayDeque<String>()
        var rateLimitMs: Long = 0L
        private var lastRequestAt = 0L

        /** Default values from getSourcePreferences(), used until the user changes one */
        var preferenceDefaults: Map<String, JsonElement> = emptyMap()

        // The app's connection without its Cloudflare helper: a bot check is passed in a browser page instead
        // (SumizuriBrowserFetch), which gets through where the helper can't
        private val client by lazy {
            network.nonCloudflareClient.newBuilder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun log(level: String, text: String) {
            synchronized(console) {
                console.addLast("[$level] $text")
                while (console.size > 500) console.removeFirst()
            }
            val l = when (level) {
                "error" -> 'E'
                "warn" -> 'W'
                "debug" -> 'D'
                else -> 'I'
            }
            AppLog.add(l, tag, text)
        }

        suspend fun handle(channel: String, payloadJson: String): String {
            val payload = runCatching { sumizuriJson.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
            val result: Any? = try {
                when (channel) {
                    "fetchUrl" -> fetch(payload)
                    "queryHtml" -> pages.query(payload.str("html").orEmpty(), payload.str("selector").orEmpty())
                    "parseHtml" -> mapOf("id" to pages.parse(payload.str("html").orEmpty()))
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
                    "render" -> SumizuriRender.render(context, payload)
                    "hash" -> mapOf("hash" to SumizuriCrypto.hash(payload.str("algorithm").orEmpty(), payload.str("input").orEmpty()))
                    "aes" -> mapOf(
                        "value" to SumizuriCrypto.aes(
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
                        "value" to SumizuriCrypto.hmac(
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
                    "gzipDecode" -> bytesOp(payload, "base64", null) { SumizuriCompress.gzipDecode(it) }
                    "gzipEncode" -> bytesOp(payload, "utf8", "base64") { SumizuriCompress.gzipEncode(it) }
                    "deflateDecode" -> bytesOp(payload, "base64", null) {
                        SumizuriCompress.deflateDecode(it, payload.bool("raw") ?: false)
                    }
                    "deflateEncode" -> bytesOp(payload, "utf8", "base64") { SumizuriCompress.deflateEncode(it) }
                    "rsaEncrypt" -> mapOf(
                        "value" to SumizuriCrypto.rsaEncrypt(
                            payload.str("data").orEmpty(),
                            payload.str("publicKeyPem").orEmpty(),
                            payload.str("padding") ?: "oaep",
                            payload.str("hash") ?: "sha256",
                            payload.str("dataEncoding"),
                            payload.str("output"),
                        ),
                    )
                    "rsaVerify" -> mapOf(
                        "ok" to SumizuriCrypto.rsaVerify(
                            payload.str("data").orEmpty(),
                            payload.str("signature").orEmpty(),
                            payload.str("publicKeyPem").orEmpty(),
                            payload.str("hash") ?: "sha256",
                            payload.str("dataEncoding"),
                            payload.str("signatureEncoding"),
                        ),
                    )
                    "zipEntries" -> mapOf(
                        "entries" to SumizuriCompress.zipEntries(
                            SumizuriBytes.decode(payload.str("data").orEmpty(), payload.str("encoding") ?: "base64"),
                        ),
                    )
                    "zipRead" -> mapOf(
                        "value" to SumizuriBytes.encode(
                            SumizuriCompress.zipRead(
                                SumizuriBytes.decode(payload.str("data").orEmpty(), payload.str("encoding") ?: "base64"),
                                payload.str("name").orEmpty(),
                            ),
                            payload.str("output"),
                        ),
                    )
                    "storageGet" -> mapOf("value" to storage()[payload.str("key").orEmpty()])
                    "storageSet" -> {
                        editStorage { it[payload.str("key").orEmpty()] = payload["value"] ?: JsonNull }
                        mapOf("ok" to true)
                    }
                    "storageDelete" -> {
                        editStorage { it.remove(payload.str("key").orEmpty()) }
                        mapOf("ok" to true)
                    }
                    "storageClear" -> {
                        editStorage { it.clear() }
                        mapOf("ok" to true)
                    }
                    "storageAll" -> mapOf("values" to storage())
                    "preferenceGet" -> mapOf("value" to preference(payload.str("key").orEmpty()))
                    "preferenceSet" -> {
                        SumizuriPreferences.write(preferences, payload.str("key").orEmpty(), payload["value"])
                        mapOf("ok" to true)
                    }
                    "preferenceAll" -> mapOf(
                        "values" to (preferenceDefaults.keys + preferences.all.keys).associateWith { preference(it) },
                    )
                    else -> mapOf("error" to "Unknown host call \"$channel\"")
                }
            } catch (e: Throwable) {
                if (channel == "fetchUrl") log("warn", "fetch failed: ${e.message}")
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
            val bytes = SumizuriBytes.decode(payload.str("data").orEmpty(), payload.str("encoding") ?: inputEncoding)
            return mapOf("value" to SumizuriBytes.encode(op(bytes), payload.str("output") ?: outputEncoding))
        }

        // Storage: one JSON file per source, like Sumizuri's storage.json

        private val storageFile get() = File(storageDir, "storage.json")
        private var storageCache: MutableMap<String, JsonElement>? = null

        @Synchronized
        private fun storage(): MutableMap<String, JsonElement> = storageCache ?: runCatching {
            (sumizuriJson.parseToJsonElement(storageFile.readText()) as JsonObject).toMutableMap()
        }.getOrElse { mutableMapOf() }.also { storageCache = it }

        @Synchronized
        private fun editStorage(change: (MutableMap<String, JsonElement>) -> Unit) {
            val map = storage()
            change(map)
            runCatching {
                storageDir.mkdirs()
                storageFile.writeText(JsonObject(map).toString())
            }
        }

        // Requests

        private suspend fun fetch(payload: JsonObject): Map<String, Any?> = withContext(Dispatchers.IO) {
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

            val origin = SumizuriBrowserFetch.originOf(url)
            val plainHeaders = headers.build().toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            suspend fun viaBrowser(): Map<String, Any?> {
                val result = SumizuriBrowserFetch.fetch(context, url, method, plainHeaders, body, binary)
                if (testing) log("net", "$method $url -> ${result["statusCode"]} (through the browser)")
                return result
            }
            // Binary replies (images, archives) never go through the browser page
            if (!binary && origin != null && origin in SumizuriBrowserFetch.browserFirst) {
                return@withContext viaBrowser()
            }

            val started = System.currentTimeMillis()
            val request = Request.Builder().url(url).headers(headers.build()).method(method, requestBody).build()
            val plain = client.newCall(request).execute().use { response ->
                val took = System.currentTimeMillis() - started
                if (testing) log("net", "$method $url -> ${response.code} in ${took}ms")
                val responseHeaders = response.headers.names().associate { name ->
                    name.lowercase() to response.headers.values(name).joinToString(",")
                }
                val bytes = response.body.bytes()
                val text = if (binary) "" else String(bytes, response.body.contentType()?.charset() ?: Charsets.UTF_8)
                if (!binary && origin != null &&
                    SumizuriBrowserFetch.isChallenge(response.code, response.header("Server"), text)
                ) {
                    null
                } else {
                    buildMap<String, Any?> {
                        put("statusCode", response.code)
                        put("url", response.request.url.toString())
                        if (binary) put("bodyBase64", SumizuriBytes.encode(bytes, "base64")) else put("body", text)
                        put("headers", responseHeaders)
                    }
                }
            }
            if (plain != null) return@withContext plain
            // A bot check answered: ask from inside a browser page on the site, and keep doing so for this site
            log("warn", "A bot check answered $url: sending it from a browser page on the site instead")
            val result = viaBrowser()
            SumizuriBrowserFetch.browserFirst += origin!!
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
        private fun loadNativeLibrary() {
            if (nativeLoaded) return
            System.loadLibrary("quickjskt")
            nativeLoaded = true
        }

        private fun asset(context: Context, name: String): String =
            context.assets.open("sumizuri/$name").bufferedReader().use { it.readText() }

        /**
         * Starts a runtime for [code], a JS source or (when [json]) a JSON declarative one. Throws with Sumizuri's
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
        ): SumizuriRuntime {
            val host = Host(
                context = context.applicationContext,
                tag = "js:$name",
                storageDir = File(context.filesDir, "sumizuri_storage/$key"),
                preferences = SumizuriPreferences.of(context, sourceId),
                testing = testing,
            )
            loadNativeLibrary()
            val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
            try {
                qjs.maxStackSize = 2L shl 20
                qjs.memoryLimit = 128L shl 20
                qjs.defineBinding(
                    "sendMessage",
                    AsyncFunctionBinding<String> { args ->
                        host.handle(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString() ?: "{}")
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
                    sumizuriJson.parseToJsonElement(config)
                    "var __sourceConfig = $config;\n" + asset(context, "json_engine.js")
                } else {
                    code
                }
                qjs.evaluate<Any?>(source, if (json) "source.json" else "source.js")
                val problem = qjs.evaluate<String>(CONTRACT_SCRIPT, "<contract>")
                if (problem.isNotEmpty()) throw SumizuriException(problem)
            } catch (e: Throwable) {
                qjs.close()
                throw if (e is SumizuriException) e else SumizuriException(e.message ?: e.toString(), e)
            }
            return SumizuriRuntime(qjs, host).also { runtime ->
                // Defaults for host.preference.get before the user has changed anything
                if (runtime.has("getSourcePreferences") || runCatching {
                        qjs.evaluate<Boolean>("typeof getSourcePreferences === 'function'", "<prefs>")
                    }.getOrDefault(false)
                ) {
                    runCatching {
                        val defs = SumizuriPreferences.parse(runtime.call("getSourcePreferences"))
                        host.preferenceDefaults = defs.mapNotNull { def -> def.defaultValue?.let { def.key to it } }.toMap()
                    }
                }
            }
        }

        private val CONTRACT_SCRIPT = """
            (function () {
              if (typeof extension !== 'object' || extension === null) return 'no global "extension" object is defined';
              var required = ['search', 'getPopular', 'getLatest', 'getChapterList'];
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
                hasDetails: typeof extension.getDetails === 'function',
                hasFilters: typeof extension.getFilters === 'function',
                hasTags: typeof extension.getTags === 'function',
                hasCatalogs: typeof extension.getCatalogs === 'function' && typeof extension.getCatalog === 'function',
                hasPreferences: typeof extension.getSourcePreferences === 'function' || typeof getSourcePreferences === 'function',
                hasVideos: typeof extension.getVideoList === 'function',
                hasPages: typeof extension.getPageList === 'function',
                rateLimitMs: typeof extension.rateLimitMs === 'number' ? extension.rateLimitMs
                  : (typeof RATE_LIMIT_MS === 'number' ? RATE_LIMIT_MS : null),
                headers: headers,
              });
            })()
        """.trimIndent()
    }
}
