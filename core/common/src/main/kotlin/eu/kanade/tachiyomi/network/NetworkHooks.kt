package eu.kanade.tachiyomi.network

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * KMK (r97): The seam between the network client and the app's Diagnostics engine (contract §3.8). `core/common`
 * can't see `app`, so the engine installs a [Listener] here once; the `net` interceptor in [NetworkHelper] calls it
 * around every request. Null listener = nothing happens (one volatile read per request).
 */
object NetworkHooks {

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var lastFailureLogAt = 0L

    /**
     * Runs [block] and returns [fallback] if it throws: a bug in the Diagnostics engine must never break a request.
     * Failures are logged at W at most once a minute.
     */
    inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        reportFailure(e)
        fallback
    }

    fun reportFailure(e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastFailureLogAt < 60_000L) return
        lastFailureLogAt = now
        logcat.logcat("net", logcat.LogPriority.WARN) {
            "Diagnostics hook failed (ignored): ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    interface Listener {
        /** After the response (or the failure): counters, the request ring, the net trace, the probes */
        fun onCall(tag: SourceTag?, request: Request, response: Response?, error: Throwable?, ms: Long)

        /** True while bytes should be counted as the body is consumed ([wrapBody] is then called) */
        fun bodyCounter(): Boolean

        /** Wraps the body in a [CountingBody]; returns the response to hand on */
        fun wrapBody(tag: SourceTag?, response: Response): Response = response

        /** Before `proceed`: the `net.request` probe hook */
        fun beforeProceed(tag: SourceTag?, request: Request) {}

        /** After `proceed`: the synchronous `net.response` probe path may hand back a rebuilt response */
        fun afterResponse(tag: SourceTag?, request: Request, response: Response): Response = response
    }
}

/**
 * KMK (r97): Request tag that says which source made a request: `Request.Builder().tag(SourceTag::class.java, tag)`.
 * JS sources set it in `Host.fetch`; APK sources are matched by host instead. [key] is the JS source key (null for
 * APK sources).
 */
class SourceTag(val id: Long, val name: String, val key: String?)

/**
 * KMK (r97): A response body that reports how many bytes were read from it, as they are read (a streamed piece
 * counts as it arrives). [onBytes] gets each chunk's size; [onClose] the final count once.
 */
class CountingBody(
    private val delegate: ResponseBody,
    private val onBytes: (Long) -> Unit,
    private val onClose: (Long) -> Unit = {},
) : ResponseBody() {

    private var total = 0L
    private var closed = false

    private val counting: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) {
                    total += n
                    onBytes(n)
                }
                return n
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = counting

    override fun close() {
        if (!closed) {
            closed = true
            onClose(total)
        }
        delegate.close()
    }
}
