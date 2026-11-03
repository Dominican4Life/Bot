package eu.kanade.tachiyomi.jssource.lab.videodoctor

/**
 * KMK (r115): what Source Lab's "Why won't it play" ([VideoDoctor]) found for one episode. Plain data: the panel
 * ([eu.kanade.tachiyomi.ui.sourcelab.VideoDoctorPanel]) shows it, the host screen applies a [VideoFix].
 */

/** The four ways a video doesn't play. More than one can apply to the same episode */
internal enum class VideoCause { NoStream, ScrambledLink, BlockedRequest, Drm }

/** What a request was for, as the panel names it */
internal enum class AskedKind(val words: String) {
    Page("player page"),
    Stream("stream"),
    Key("key"),
    Segment("piece"),
    License("licence"),
    Other("other"),
}

/** Who sent a request: the source's own code, the source through `host.render`, the hidden browser, or a replay */
internal enum class AskedSide(val words: String) {
    Source("from the source"),
    SourceBrowser("from the source (browser)"),
    Browser("from the browser"),
    ReplaySource("replayed with the source's headers"),
    ReplayBrowser("replayed with the browser's headers"),
}

/**
 * One request of the episode. [status] null with no [error]: the hidden browser reported no error for it (a
 * WebView only reports failed answers); [detail] a short note ("3 qualities", "AES-128 key").
 */
internal data class AskedRequest(
    val url: String,
    val kind: AskedKind,
    val side: AskedSide,
    val status: Int?,
    val headers: Map<String, String> = emptyMap(),
    val detail: String? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = status != null && status in 200..399
    val refused: Boolean get() = status == 401 || status == 403 || status == 404
}

/** One header the two sides sent differently (null = not sent) */
internal data class HeaderDiff(val name: String, val source: String?, val browser: String?)

internal enum class ScrambleKind(val words: String) {
    AesPassphrase("AES (passphrase)"),
    AesKey("AES (key)"),
    Base64("base64"),
    Packed("packed script"),
}

/**
 * A hidden address found in a page or a player script: the [blob] (shortened for the screen in [blobShort]),
 * where it was ([where]: the page or script address), the key literal and its line (AES), and the address it
 * turned into. [pattern] is a JS regular expression whose group 1 is the blob in that text.
 */
internal data class Scrambled(
    val kind: ScrambleKind,
    val blob: String,
    val where: String,
    val key: String?,
    val keyLine: Int?,
    val iv: String?,
    val result: String,
    val pattern: String,
    // true when the hidden browser asked for this same address (host + path)
    val confirmed: Boolean,
) {
    val blobShort: String get() = if (blob.length <= 36) blob else blob.take(22) + "…" + blob.takeLast(8)
}

/** A DRM sign: the key systems named, the licence address when one was seen, and where it was seen */
internal data class DrmSign(val systems: List<String>, val licenseUrl: String?, val via: String)

internal data class CauseVerdict(val cause: VideoCause, val hit: Boolean, val line: String)

/**
 * A fix the host screen applies (the panel only hands it over; it never edits a source).
 * - [UseHiddenBrowser]: the source's video part calls `host.findVideo(episodePage)`.
 * - [AddHeaders]: these headers go on every video the source returns (the fewest that made the replay work).
 * - [DecryptWithKey]: the source reads the blob with [pattern] (group 1) and turns it into the address:
 *   [method] "aes-passphrase" (CryptoJS / OpenSSL "Salted__", [key] the passphrase), "aes-key" ([key] and [iv]
 *   in [keyEncoding]), "base64" or "packed" (no key). [DecryptSnippet.forFix] writes the JS for it.
 */
internal sealed class VideoFix {
    data object UseHiddenBrowser : VideoFix()

    data class AddHeaders(val headers: Map<String, String>) : VideoFix()

    data class DecryptWithKey(
        val pattern: String,
        val key: String,
        val iv: String? = null,
        val method: String = "aes-passphrase",
        val keyEncoding: String = "utf8",
    ) : VideoFix()
}

internal data class VideoDiagnosis(
    val episodeUrl: String,
    // the page the hidden browser opened (null: it wasn't needed or no http page was known)
    val playerPage: String?,
    val causes: List<CauseVerdict>,
    val requests: List<AskedRequest>,
    val headerDiffs: List<HeaderDiff>,
    // "The browser sent Referer: … and a cookie; the source sent neither." (null: no difference that mattered)
    val headerLine: String?,
    val scrambled: Scrambled?,
    val drm: DrmSign?,
    val fixes: List<VideoFix>,
    // the address and headers "Try play" uses (the fixes applied), null when nothing playable was found
    val tryUrl: String?,
    val tryHeaders: Map<String, String>,
    val sourcePlayed: Boolean,
    val browserUsed: Boolean,
    // VideoFinder's reason when the browser caught nothing
    val browserReason: String?,
    val steps: List<String>,
    val tookMs: Long,
) {
    fun hit(cause: VideoCause): Boolean = causes.any { it.cause == cause && it.hit }
    val anyHit: Boolean get() = causes.any { it.hit }
}
