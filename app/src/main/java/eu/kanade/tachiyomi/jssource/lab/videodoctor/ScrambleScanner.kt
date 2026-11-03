package eu.kanade.tachiyomi.jssource.lab.videodoctor

import eu.kanade.tachiyomi.jssource.lab.RecordedConverter
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KMK (r115): finds a video address a page or player script hides, the common ways:
 * - CryptoJS / OpenSSL AES ("U2FsdGVkX1…" = base64 of "Salted__"), with a passphrase written in the same script:
 *   each string literal near the blob is tried (OpenSSL EVP_BytesToKey, MD5, AES-256-CBC);
 * - AES with a raw key (and IV) written as hex or 16/24/32-letter literals, only in scripts that speak of AES;
 * - base64 (plain or reversed) that decodes to a video address;
 * - Dean Edwards' p.a.c.k.e.r, undone with [RecordedConverter.Unpacker].
 * A finding counts only when the result is an http(s) address; one written plainly in the same text isn't hidden.
 * Pure Kotlin (no Android): blocking, CPU only; callers run it on IO.
 */
internal object ScrambleScanner {

    // No bare braces anywhere (ICU regex)
    private val SALTED = Regex("""U2FsdGVkX1[A-Za-z0-9+/=]{16,}""")
    private val LITERAL = Regex("""(["'`])([^"'`\\\s]{4,128})\1""")
    private val B64_LITERAL = Regex("""(["'`])([A-Za-z0-9+/_-]{20,}={0,2})\1""")
    private val MEDIA_URL = Regex(
        """https?:\\?/\\?/[^"'\s<>\\]+?\.(?:m3u8|mpd|mp4|mkv|webm)(?:[?#][^"'\s<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ANY_URL = Regex("""https?:\\?/\\?/[^"'\s<>\\]+""")
    private val HEX = Regex("""^[0-9a-fA-F]+$""")
    private val AES_WORDS = Regex("""CryptoJS|\bAES\b|decrypt|createDecipher|crypto\.subtle""", RegexOption.IGNORE_CASE)
    private val PACKED_HEAD = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)""")

    private const val MAX_SALTED = 5
    private const val MAX_PASSPHRASES = 80
    private const val MAX_B64 = 200
    private const val MAX_RAW_TRIES = 300

    /**
     * Every hidden address in [text] (the page or script at [where]); [browserPaths] the host + path of what the
     * hidden browser asked for (a finding with the same one is `confirmed`). Best first: confirmed, then AES, then
     * packed, then base64.
     */
    fun scan(text: String, where: String, browserPaths: Set<String>): List<Scrambled> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Scrambled>()
        runCatching { out.addAll(salted(text, where, browserPaths)) }
        if (out.none { it.kind == ScrambleKind.AesPassphrase }) runCatching { out.addAll(rawAes(text, where, browserPaths)) }
        runCatching { out.addAll(packed(text, where, browserPaths)) }
        runCatching { out.addAll(base64(text, where, browserPaths)) }
        return out.distinctBy { it.result }.sortedWith(
            compareByDescending<Scrambled> { it.confirmed }.thenBy { it.kind.ordinal },
        )
    }

    /** host + path in lower case ("cdn.example.net/v/1/master.m3u8"), the way findings and requests are compared */
    fun pathKey(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val hostAndPath = noScheme.substringBefore('?').substringBefore('#')
        return hostAndPath.lowercase().trimEnd('/')
    }

    // ---------- AES with a passphrase (CryptoJS default / openssl enc) ----------

    private fun salted(text: String, where: String, browserPaths: Set<String>): List<Scrambled> {
        val blobs = SALTED.findAll(text).take(MAX_SALTED).toList()
        if (blobs.isEmpty()) return emptyList()
        val literals = LITERAL.findAll(text).map { it.groups[2]!!.value to it.groups[2]!!.range.first }.toList()
        val out = ArrayList<Scrambled>()
        for (blob in blobs) {
            val at = blob.range.first
            val candidates = literals
                .filter { (value, _) -> !value.startsWith("U2FsdGVkX1") && value != blob.value }
                .sortedBy { (_, pos) -> kotlin.math.abs(pos - at) }
                .distinctBy { it.first }
                .take(MAX_PASSPHRASES)
            for ((passphrase, pos) in candidates) {
                val plain = decryptSalted(blob.value, passphrase) ?: continue
                val url = urlIn(plain) ?: continue
                if (url in text) continue
                out.add(
                    Scrambled(
                        kind = ScrambleKind.AesPassphrase,
                        blob = blob.value,
                        where = where,
                        key = passphrase,
                        keyLine = lineOf(text, pos),
                        iv = null,
                        result = url,
                        pattern = patternFor(text, at, blob.value, """(U2FsdGVkX1[A-Za-z0-9+/=]+)"""),
                        confirmed = pathKey(url) in browserPaths,
                    ),
                )
                break
            }
        }
        return out
    }

    /** OpenSSL "Salted__" + salt + AES-256-CBC, key and IV from EVP_BytesToKey(MD5, 1 round); null when it fails */
    fun decryptSalted(blob: String, passphrase: String): String? {
        val data = decodeBase64(blob) ?: return null
        if (data.size < 32 || String(data, 0, 8, Charsets.ISO_8859_1) != "Salted__") return null
        val salt = data.copyOfRange(8, 16)
        val derived = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 48)
        return aesCbc(data.copyOfRange(16, data.size), derived.copyOfRange(0, 32), derived.copyOfRange(32, 48))
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        val md5 = MessageDigest.getInstance("MD5")
        while (out.size() < length) {
            md5.reset()
            block = md5.digest(block + password + salt)
            out.write(block)
        }
        return out.toByteArray().copyOf(length)
    }

    /** AES-CBC with PKCS#7 padding; the answer only when it is readable text (a wrong key gives bytes) */
    private fun aesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): String? = try {
        if (data.isEmpty() || data.size % 16 != 0 || key.size !in listOf(16, 24, 32) || iv.size != 16) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            readable(cipher.doFinal(data))
        }
    } catch (e: Exception) {
        null
    }

    private fun readable(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        val bad = text.count { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }
        return if (bad * 20 > text.length) null else text
    }

    // ---------- AES with a raw key ----------

    private class KeyGuess(val value: String, val bytes: ByteArray, val encoding: String, val pos: Int)

    private fun rawAes(text: String, where: String, browserPaths: Set<String>): List<Scrambled> {
        if (!AES_WORDS.containsMatchIn(text)) return emptyList()
        val literals = LITERAL.findAll(text).map { it.groups[2]!!.value to it.groups[2]!!.range.first }.toList()
        val keys = ArrayList<KeyGuess>()
        val ivs = ArrayList<KeyGuess?>()
        literals.distinctBy { it.first }.forEach { (value, pos) ->
            val hex = HEX.matches(value)
            when {
                hex && value.length in listOf(32, 48, 64) -> keys.add(KeyGuess(value, hexBytes(value), "hex", pos))
                !hex && value.length in listOf(16, 24, 32) -> keys.add(KeyGuess(value, value.toByteArray(Charsets.UTF_8), "utf8", pos))
            }
            when {
                hex && value.length == 32 -> ivs.add(KeyGuess(value, hexBytes(value), "hex", pos))
                !hex && value.length == 16 -> ivs.add(KeyGuess(value, value.toByteArray(Charsets.UTF_8), "utf8", pos))
            }
        }
        if (keys.isEmpty()) return emptyList()
        ivs.add(null) // an IV of zeros
        val blobs = B64_LITERAL.findAll(text)
            .map { it.groups[2]!! }
            .filter { !it.value.startsWith("U2FsdGVkX1") }
            .mapNotNull { g -> decodeBase64(g.value)?.takeIf { it.size >= 32 && it.size % 16 == 0 }?.let { g to it } }
            .take(10)
            .toList()
        var tries = 0
        for ((group, data) in blobs) {
            for (key in keys.sortedBy { kotlin.math.abs(it.pos - group.range.first) }) {
                for (iv in ivs) {
                    if (++tries > MAX_RAW_TRIES) return emptyList()
                    if (iv != null && iv.value == key.value && key.bytes.size != 16) continue
                    val plain = aesCbc(data, key.bytes, iv?.bytes ?: ByteArray(16)) ?: continue
                    val url = urlIn(plain) ?: continue
                    if (url in text) continue
                    return listOf(
                        Scrambled(
                            kind = ScrambleKind.AesKey,
                            blob = group.value,
                            where = where,
                            key = key.value,
                            keyLine = lineOf(text, key.pos),
                            iv = iv?.value,
                            result = url,
                            pattern = patternFor(text, group.range.first, group.value, """["']([A-Za-z0-9+/=_-]{20,})["']"""),
                            confirmed = pathKey(url) in browserPaths,
                        ),
                    )
                }
            }
        }
        return emptyList()
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---------- packed scripts ----------

    private fun packed(text: String, where: String, browserPaths: Set<String>): List<Scrambled> {
        val head = PACKED_HEAD.find(text) ?: return emptyList()
        val unpacked = RecordedConverter.Unpacker.unpackAll(text)
        for (script in unpacked) {
            val url = script.replace("\\/", "/").let { MEDIA_URL.find(it)?.value } ?: continue
            if (url in text) continue
            return listOf(
                Scrambled(
                    kind = ScrambleKind.Packed,
                    blob = text.substring(head.range.first, (head.range.first + 80).coerceAtMost(text.length)),
                    where = where,
                    key = null,
                    keyLine = lineOf(text, head.range.first),
                    iv = null,
                    result = url,
                    pattern = """(eval\(function\(p,a,c,k,e,[dr]\)[\s\S]*?\.split\(['"]\|['"]\)[^)]*\)\))""",
                    confirmed = pathKey(url) in browserPaths,
                ),
            )
        }
        return emptyList()
    }

    // ---------- base64 ----------

    private fun base64(text: String, where: String, browserPaths: Set<String>): List<Scrambled> {
        val out = ArrayList<Scrambled>()
        for (m in B64_LITERAL.findAll(text).take(MAX_B64)) {
            val group = m.groups[2]!!
            val value = group.value
            if (value.startsWith("U2FsdGVkX1")) continue
            for ((index, candidate) in listOf(value, value.reversed()).withIndex()) {
                val decoded = decodeBase64(candidate)?.let { readable(it) }?.trim() ?: continue
                if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) continue
                val url = urlIn(decoded) ?: continue
                val confirmed = pathKey(url) in browserPaths
                // Any address but a video one only when the browser asked for it (an analytics beacon isn't it)
                if (!confirmed && !MEDIA_URL.containsMatchIn(url)) continue
                if (url in text) continue
                out.add(
                    Scrambled(
                        kind = ScrambleKind.Base64,
                        blob = value,
                        where = where,
                        key = if (index == 0) null else "reversed",
                        keyLine = lineOf(text, group.range.first),
                        iv = null,
                        result = url,
                        pattern = patternFor(text, group.range.first, value, """["']([A-Za-z0-9+/=_-]{20,})["']"""),
                        confirmed = confirmed,
                    ),
                )
                break
            }
            if (out.size >= 3) break
        }
        return out
    }

    // ---------- small helpers ----------

    /** Base64 as sites write it: with or without padding, standard or URL-safe; null when it isn't */
    fun decodeBase64(value: String): ByteArray? {
        val clean = value.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        return try {
            Base64.getDecoder().decode(clean.padEnd((clean.length + 3) / 4 * 4, '='))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The first video address in [plain] (else any http address), JSON escapes undone */
    private fun urlIn(plain: String): String? {
        val text = plain.replace("\\/", "/")
        return (MEDIA_URL.find(text) ?: ANY_URL.find(text))?.value?.trimEnd(',', ';', ')')
    }

    private fun lineOf(text: String, pos: Int): Int {
        var line = 1
        for (i in 0 until pos.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}/"

    /**
     * A JS regular expression whose group 1 is [blob] in [text]: the (up to 16) characters before it on its line,
     * escaped, then the blob's alphabet. Checked against [text]; when it doesn't find the same blob, [fallback].
     */
    fun patternFor(text: String, at: Int, blob: String, fallback: String): String {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        var from = maxOf(lineStart, at - 16)
        // Start on a word's start, not inside one ("ayer\"" of "player\"")
        if (from > lineStart && text[from - 1].isLetterOrDigit()) {
            while (from < at && text[from].isLetterOrDigit()) from++
        }
        val prefix = text.substring(from, at)
        val escaped = buildString {
            var space = false
            for (c in prefix) {
                if (c.isWhitespace()) {
                    if (!space) append("\\s*")
                    space = true
                    continue
                }
                space = false
                if (c in REGEX_SPECIAL) append('\\')
                append(c)
            }
        }
        val alphabet = if (blob.startsWith("U2FsdGVkX1")) "(U2FsdGVkX1[A-Za-z0-9+/=]+)" else "([A-Za-z0-9+/=_-]{20,})"
        val pattern = escaped + alphabet
        val ok = escaped.isNotEmpty() && runCatching { Regex(pattern).find(text)?.groupValues?.get(1) == blob }.getOrDefault(false)
        return if (ok) pattern else fallback
    }
}
