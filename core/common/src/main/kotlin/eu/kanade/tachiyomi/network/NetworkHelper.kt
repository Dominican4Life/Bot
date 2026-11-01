package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import exh.log.EHLogLevel
import exh.pref.DelegateSourcePreferences
import logcat.LogPriority
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

/* SY --> */ open /* SY <-- */ class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
    // KMK -->
    val delegateSourcePreferences: DelegateSourcePreferences,
    // KMK <--
) {

    /* SY --> */ open /* SY <-- */val cookieJar = AndroidCookieJar()

    // KMK -->
    private val delegatedSourcesEnabled = delegateSourcePreferences.delegateSources().get()
    // KMK <--

    // KMK (r95): one Cache for every client built here. OkHttp wants one Cache instance per directory; before, each
    // clientBuilder() call made its own over "network_cache", so three journals fought over the same 5 MiB.
    private val httpCache by lazy {
        Cache(
            directory = File(context.cacheDir, "network_cache"),
            maxSize = 5L * 1024 * 1024, // 5 MiB
        )
    }

    /**
     * Timeout in unit of seconds.
     */
    private /* KMK --> */ fun /* KMK <-- */ clientBuilder(
        // KMK -->
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
        // KMK <--
    ): OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // KMK -->
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)
            // KMK <--
            .cache(httpCache)
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            // KMK: each request in the in-app Logs screen (r95: unless tagged NoNetLog, as HLS stream pieces are)
            .addInterceptor { chain ->
                val request = chain.request()
                // KMK (r97) -->
                // Diagnostics: byte counters, per-source attribution, the net trace and the probes hang off
                // NetworkHooks. NoNetLog requests (HLS pieces) skip the Logs line but still reach onCall.
                val hooks = NetworkHooks.listener
                val quiet = request.tag(NoNetLog::class.java) != null
                if (quiet && hooks == null) return@addInterceptor chain.proceed(request)
                val tag = request.tag(SourceTag::class.java)
                // Every hook call is guarded: an engine bug must never break a request (r97 fix pass).
                if (hooks != null) NetworkHooks.guarded(Unit) { hooks.beforeProceed(tag, request) }
                // KMK <--
                val started = System.nanoTime()
                try {
                    var response = chain.proceed(request)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    // KMK (r97) -->
                    if (hooks != null) {
                        // afterResponse first (a probe may replace the body), then the counting wrapper, so a
                        // replaced body never closes a CountingBody and the record is finished once, complete.
                        response = NetworkHooks.guarded(response) { hooks.afterResponse(tag, request, response) }
                        if (NetworkHooks.guarded(false) { hooks.bodyCounter() }) {
                            response = NetworkHooks.guarded(response) { hooks.wrapBody(tag, response) }
                        }
                    }
                    if (!quiet) {
                        // KMK <--
                        logcat.logcat("net", if (response.isSuccessful) logcat.LogPriority.DEBUG else logcat.LogPriority.WARN) {
                            "${request.method} ${request.url} -> ${response.code} in $ms ms"
                        }
                    }
                    if (hooks != null) NetworkHooks.guarded(Unit) { hooks.onCall(tag, request, response, null, ms) } // KMK (r97)
                    response
                } catch (e: java.io.IOException) {
                    if (!quiet) { // KMK (r97)
                        logcat.logcat("net", logcat.LogPriority.WARN) {
                            "${request.method} ${request.url} failed: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                    // KMK (r97) -->
                    if (hooks != null) {
                        val failedMs = (System.nanoTime() - started) / 1_000_000
                        NetworkHooks.guarded(Unit) { hooks.onCall(tag, request, null, e, failedMs) }
                    }
                    // KMK <--
                    throw e
                }
            }

        // KMK -->
        if (EHLogLevel.isExtraLogging()) {
            // KMK <--
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        when (preferences.dohProvider().get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            else -> builder
        }
        // KMK: IPv4 addresses tried first. On networks where IPv6 doesn't get through, a site that also has an IPv6
        // address used to wait out the connect timeout on it first (jikan.moe: 5 s, then failed)
        val resolver = builder.build().dns
        builder.dns(
            okhttp3.Dns { host ->
                resolver.lookup(host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            },
        )
    }

    // KMK --> used by anime extensions
    val nonCloudflareClient by lazy { clientBuilder().build() }
    // KMK <--

    /* SY --> */ open /* SY <-- */ val client /* KMK --> */ by lazy /* KMK <-- */ {
        clientBuilder()
            // KMK: added before the Cloudflare helper, so it only sees bot checks the helper couldn't pass
            .addInterceptor(BotCheckInterceptor())
            .addInterceptor(
                CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
            )
            // KMK -->
            // FIXME (KMK): Dirty hack to fetch MangaDex covers
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val url = originalRequest.url

                if (!delegatedSourcesEnabled ||
                    url.host != "uploads.mangadex.org" ||
                    !url.encodedPath.startsWith("/covers/")
                ) {
                    return@addInterceptor chain.proceed(originalRequest)
                }

                val newRequest = originalRequest
                    .newBuilder()
                    .header("Referer", "https://mangadex.org/")
                    .header("Origin", "https://mangadex.org")
                    .header("sec-fetch-dest", "image")
                    .header("sec-fetch-mode", "no-cors")
                    .build()

                chain.proceed(newRequest)
            }
            // KMK <--
            .build()
    }

    // ANZ -->
    /**
     * Client for the embedded torrent server. TorrServer only starts producing a response
     * once the torrent swarm connects, so header waits and mid-stream stalls can legitimately
     * take minutes. Reads are given 10 minutes and there is no overall call deadline.
     */
    val torrentClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    // ANZ <--

    // KMK -->
    /**
     * Timeout in unit of seconds.
     */
    private fun clientWithTimeOut(
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
    ) = clientBuilder(connectTimeout, readTimeout, callTimeout)
        .addInterceptor(BotCheckInterceptor())
        .addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
        )
        .build()

    /**
     * Allow to download a big file with retry & resume capability because
     * normally it would get a Timeout exception.
     */
    fun downloadFileWithResume(url: String, outputFile: File, progressListener: ProgressListener) {
        val client = clientWithTimeOut(
            callTimeout = 120,
        )

        var downloadedBytes: Long

        var attempt = 0

        while (attempt < MAX_RETRY) {
            try {
                // Check how much has already been downloaded
                downloadedBytes = outputFile.length()
                // Set up request with Range header to resume from the last byte
                val request = GET(
                    url = url,
                    headers = Headers.Builder()
                        .add("Range", "bytes=$downloadedBytes-")
                        .build(),
                )

                var failed = false
                client.newCachelessCallWithProgress(request, progressListener).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) { // 206 indicates partial content
                        saveResponseToFile(response, outputFile, downloadedBytes)
                        if (response.isSuccessful) {
                            return
                        }
                    } else {
                        attempt++
                        logcat(LogPriority.ERROR) { "Unexpected response code: ${response.code}. Retrying..." }
                        if (response.code == 416) {
                            // 416: Range Not Satisfiable
                            outputFile.delete()
                        }
                        failed = true
                    }
                }
                if (failed) exponentialBackoff(attempt - 1)
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Download interrupted: ${e.message}. Retrying..." }
                // Wait or handle as needed before retrying
                attempt++
                exponentialBackoff(attempt - 1)
            }
        }
        throw IOException("Max retry attempts reached.")
    }

    // Helper function to save data incrementally
    private fun saveResponseToFile(response: Response, outputFile: File, startPosition: Long) {
        val body = response.body

        // Use RandomAccessFile to write from specific position
        RandomAccessFile(outputFile, "rw").use { file ->
            file.seek(startPosition)
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    file.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    // Increment attempt and apply exponential backoff
    private fun exponentialBackoff(attempt: Int) {
        val backoffDelay = calculateExponentialBackoff(attempt)
        Thread.sleep(backoffDelay)
    }

    // Helper function to calculate exponential backoff with jitter
    private fun calculateExponentialBackoff(attempt: Int, baseDelay: Long = 1000L, maxDelay: Long = 32000L): Long {
        // Calculate the exponential delay
        val delay = baseDelay * 2.0.pow(attempt).toLong()
        logcat(LogPriority.ERROR) { "Exponential backoff delay: $delay ms" }
        // Apply jitter by adding a random value to avoid synchronized retries in distributed systems
        return (delay + Random.nextLong(0, 1000)).coerceAtMost(maxDelay)
    }
    // KMK <--

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default", ReplaceWith("client"))
    @Suppress("UNUSED")
    /* SY --> */
    open /* SY <-- */val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent().get().trim()

    companion object {
        // KMK -->
        private const val MAX_RETRY = 5
        // KMK <--
    }
}

// KMK (r95) -->
/**
 * Request tag that keeps a request out of the per-request "net" Logs line: `Request.Builder().tag(NoNetLog::class.java,
 * NoNetLog)`. Used for HLS stream pieces, which arrive every few seconds for a whole episode.
 */
object NoNetLog
// KMK <--
