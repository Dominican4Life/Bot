package eu.kanade.tachiyomi.jssource.lab

import android.content.Context
import eu.kanade.tachiyomi.jssource.JsRuntime
import eu.kanade.tachiyomi.jssource.int
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * KMK (r96, Source Lab): fetches for detection, made through a [JsRuntime] over a fixed probe source, so they
 * behave exactly like a source's own requests: the Cloudflare retry through JsBrowserFetch, the shared cookie
 * jar, the app's user agent, and the `[net]` console lines with the timing (CONTRACT-LAB §5.5).
 *
 * One runtime per detection: [close] it in a `finally`. The method name handed to `call` is always the constant
 * [METHOD]; user text only ever travels as an argument.
 */
internal class LabProbe(private val context: Context) {

    /** One reply, with the timing and the way it went taken from the runtime's console */
    data class ProbeReply(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val headers: Map<String, String>,
        val bytes: Int,
        val ms: Long,
        val viaBrowser: Boolean,
    )

    private var runtime: JsRuntime? = null
    private var consoleRead = 0

    /** GET [url] (or another method through [method]) with [headers]; throws the runtime's own error text */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: String? = null): ProbeReply =
        request(url, method, headers, body)

    /** HEAD [url]: the status and headers only (the body is empty) */
    suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ProbeReply =
        request(url, "HEAD", headers, null)

    fun close() {
        runtime?.let { rt -> runCatching { rt.close() } }
        runtime = null
    }

    private suspend fun runtimeOrCreate(): JsRuntime = runtime ?: JsRuntime.create(
        context = context,
        key = KEY,
        sourceId = 0L,
        name = "lab",
        code = PROBE_SOURCE,
        json = false,
        testing = true,
        testCopy = true,
    ).also {
        runtime = it
        consoleRead = it.console.size
    }

    private suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): ProbeReply {
        val rt = runtimeOrCreate()
        val options = buildMap<String, Any?> {
            put("method", method)
            put("headers", headers)
            if (body != null) put("body", body)
        }
        // A failed request throws the runtime's own error text (a bot check that needs the user included)
        val result = rt.call(METHOD, url, options)
        val reply = result as? JsonObject ?: JsonObject(emptyMap())
        val (ms, viaBrowser) = timing(rt, method, url)
        val headersOut = reply.obj("headers")?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val text = reply.str("body").orEmpty()
        return ProbeReply(
            status = reply.int("status") ?: 0,
            finalUrl = reply.str("url") ?: url,
            body = text,
            headers = headersOut,
            bytes = reply.int("bytes") ?: text.length,
            ms = ms,
            viaBrowser = viaBrowser,
        )
    }

    /**
     * The timing of the request just made, from the console lines added since the last call:
     * `[net] GET url -> 200 in 85ms`, `[net][step] …`, or `… -> 200 (through the browser)`.
     */
    private fun timing(rt: JsRuntime, method: String, url: String): Pair<Long, Boolean> {
        val lines = rt.console
        val fresh = if (consoleRead <= lines.size) lines.subList(consoleRead, lines.size) else lines
        consoleRead = lines.size
        val line = fresh.lastOrNull { NET_LINE.containsMatchIn(it) && "$method $url" in it }
            ?: fresh.lastOrNull { NET_LINE.containsMatchIn(it) }
            ?: return 0L to false
        val ms = MS.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return ms to ("(through the browser)" in line)
    }

    companion object {
        const val KEY = "sourcelab-probe"

        /** The only method name ever pasted into the runtime's call script */
        const val METHOD = "probe"

        private val NET_LINE = Regex("""^\[net](\[[^\]]*])?\s""")
        private val MS = Regex("""\sin\s(\d+)\s?ms\b""")

        /** The probe source (CONTRACT-LAB §5.5); `probe` is the only method that does anything */
        val PROBE_SOURCE = """
            var extension = {
              name: "Source Lab probe", baseUrl: "https://example.invalid",
              search: function () { return []; }, getPopular: function () { return []; }, getLatest: function () { return []; },
              getChapterList: function () { return []; }, getPageList: function () { return []; },
              probe: async function (url, options) {
                var r = await host.fetch(url, options || {});
                var body = r.body || "";
                return { status: r.statusCode, url: r.url, body: body.slice(0, 400000), headers: r.headers, bytes: body.length };
              }
            };
        """.trimIndent()
    }
}
