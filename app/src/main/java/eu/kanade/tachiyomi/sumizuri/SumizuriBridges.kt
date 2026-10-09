package eu.kanade.tachiyomi.sumizuri

import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.IdentityHashMap
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * KMK: The host side of Sumizuri's JS bridge that needs no network: bytes, crypto, compression, archives and HTML.
 * Ported from not-akari/sumizuri-flutter (crypto_bridge, rsa_bridge, html_bridge) so sources behave the same.
 */
internal object SumizuriBytes {

    fun decode(value: String, encoding: String?): ByteArray = when ((encoding ?: "utf8").lowercase()) {
        "utf8", "utf-8" -> value.toByteArray(Charsets.UTF_8)
        "hex" -> {
            val clean = value.filterNot { it.isWhitespace() }
            require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Not valid hex" }
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        "base64" -> {
            // Base64 as sites write it: with or without padding, standard or URL-safe
            val text = value.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
            Base64.decode(text.padEnd((text.length + 3) / 4 * 4, '='), Base64.DEFAULT)
        }
        else -> throw IllegalArgumentException("Use utf8, hex or base64")
    }

    fun encode(bytes: ByteArray, encoding: String?): String = when ((encoding ?: "utf8").lowercase()) {
        "utf8", "utf-8" -> String(bytes, Charsets.UTF_8)
        "hex" -> bytes.joinToString("") { "%02x".format(it) }
        "base64" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
        else -> throw IllegalArgumentException("Use utf8, hex or base64")
    }
}

internal object SumizuriCrypto {

    private fun digestName(algorithm: String, what: String): String = when (algorithm.lowercase()) {
        "md5" -> "MD5"
        "sha1" -> "SHA-1"
        "sha224" -> "SHA-224"
        "sha256" -> "SHA-256"
        "sha384" -> "SHA-384"
        "sha512" -> "SHA-512"
        else -> throw IllegalArgumentException(
            "$what: unsupported algorithm '$algorithm'. Supported: md5, sha1, sha224, sha256, sha384, sha512.",
        )
    }

    fun hash(algorithm: String, input: String): String =
        SumizuriBytes.encode(
            MessageDigest.getInstance(digestName(algorithm, "host.hash")).digest(input.toByteArray()),
            "hex",
        )

    fun hmac(algorithm: String, key: String, input: String, keyEncoding: String?, output: String?): String {
        val name = "Hmac" + digestName(algorithm, "host.crypto.hmac").replace("-", "")
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(SumizuriBytes.decode(key, keyEncoding), name))
        return SumizuriBytes.encode(mac.doFinal(input.toByteArray()), output ?: "hex")
    }

    fun aes(
        encrypt: Boolean,
        data: String,
        mode: String,
        key: String?,
        iv: String?,
        passphrase: String?,
        keyEncoding: String?,
        ivEncoding: String?,
        dataEncoding: String?,
        output: String?,
        padding: String,
    ): String {
        var input = SumizuriBytes.decode(data, dataEncoding ?: if (encrypt) "utf8" else "base64")
        val outputEncoding = output ?: if (encrypt) "base64" else "utf8"
        val keyBytes: ByteArray
        var ivBytes: ByteArray
        var salt: ByteArray? = null
        if (passphrase != null) {
            if (encrypt) {
                salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
            } else {
                require(input.size >= 16 && String(input, 0, 8, Charsets.ISO_8859_1) == "Salted__") {
                    "The data is not in the \"Salted__\" password format"
                }
                salt = input.copyOfRange(8, 16)
                input = input.copyOfRange(16, input.size)
            }
            val derived = evpBytesToKey(passphrase.toByteArray(), salt, 48)
            keyBytes = derived.copyOfRange(0, 32)
            ivBytes = derived.copyOfRange(32, 48)
        } else {
            requireNotNull(key) { "An aes call needs a key or a passphrase" }
            keyBytes = SumizuriBytes.decode(key, keyEncoding)
            ivBytes = if (iv == null) ByteArray(16) else SumizuriBytes.decode(iv, ivEncoding)
        }
        require(keyBytes.size in listOf(16, 24, 32)) { "The AES key must be 16, 24 or 32 bytes, got ${keyBytes.size}" }
        val secret = SecretKeySpec(keyBytes, "AES")
        val opmode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        val pad = padding.lowercase() != "none"
        val result = when (mode.lowercase()) {
            "cbc" -> {
                require(ivBytes.size == 16) { "The iv for cbc must be 16 bytes, got ${ivBytes.size}" }
                Cipher.getInstance(if (pad) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding")
                    .apply { init(opmode, secret, IvParameterSpec(ivBytes)) }
                    .doFinal(input)
            }
            "ecb" -> Cipher.getInstance(if (pad) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
                .apply { init(opmode, secret) }
                .doFinal(input)
            "ctr" -> {
                if (ivBytes.size != 16) ivBytes = ivBytes.copyOf(16)
                Cipher.getInstance("AES/CTR/NoPadding")
                    .apply { init(opmode, secret, IvParameterSpec(ivBytes)) }
                    .doFinal(input)
            }
            "gcm" -> Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(opmode, secret, GCMParameterSpec(128, ivBytes)) }
                .doFinal(input)
            else -> throw IllegalArgumentException("Use cbc, ecb, ctr or gcm")
        }
        if (passphrase != null && encrypt) {
            return SumizuriBytes.encode("Salted__".toByteArray() + salt!! + result, outputEncoding)
        }
        return SumizuriBytes.encode(result, outputEncoding)
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        val md5 = MessageDigest.getInstance("MD5")
        while (out.size() < length) {
            md5.reset()
            block = md5.digest(block + password + salt)
            out.write(block)
        }
        return out.toByteArray().copyOf(length)
    }

    // RSA (public keys only: a source never holds a site's private key)

    private fun publicKey(pem: String): RSAPublicKey {
        val der = SumizuriBytes.decode(pem.replace(Regex("-----[^-]+-----"), ""), "base64")
        val factory = KeyFactory.getInstance("RSA")
        return if (pem.contains("BEGIN RSA PUBLIC KEY")) {
            // PKCS#1: SEQUENCE { modulus, exponent }
            val reader = Der(der)
            val seq = Der(reader.next().second)
            val modulus = BigInteger(1, seq.next().second)
            val exponent = BigInteger(1, seq.next().second)
            factory.generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        } else {
            factory.generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        }
    }

    private class Der(private val bytes: ByteArray) {
        private var pos = 0
        fun next(): Pair<Int, ByteArray> {
            val tag = bytes[pos++].toInt() and 0xff
            var len = bytes[pos++].toInt() and 0xff
            if (len >= 0x80) {
                val count = len and 0x7f
                len = 0
                repeat(count) { len = (len shl 8) or (bytes[pos++].toInt() and 0xff) }
            }
            val content = bytes.copyOfRange(pos, pos + len)
            pos += len
            return tag to content
        }
    }

    fun rsaEncrypt(data: String, pem: String, padding: String, hash: String, dataEncoding: String?, output: String?): String {
        val key = publicKey(pem)
        val cipher = if (padding.lowercase() == "pkcs1") {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
        } else {
            val digest = digestName(hash, "host.crypto.rsa")
            val mgf = when (digest) {
                "SHA-1" -> MGF1ParameterSpec.SHA1
                "SHA-384" -> MGF1ParameterSpec.SHA384
                "SHA-512" -> MGF1ParameterSpec.SHA512
                else -> MGF1ParameterSpec.SHA256
            }
            Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec(digest, "MGF1", mgf, PSource.PSpecified.DEFAULT))
            }
        }
        return SumizuriBytes.encode(cipher.doFinal(SumizuriBytes.decode(data, dataEncoding)), output ?: "base64")
    }

    fun rsaVerify(data: String, signature: String, pem: String, hash: String, dataEncoding: String?, signatureEncoding: String?): Boolean {
        val name = digestName(hash, "host.crypto.rsa").replace("-", "") + "withRSA"
        return Signature.getInstance(name).run {
            initVerify(publicKey(pem))
            update(SumizuriBytes.decode(data, dataEncoding))
            verify(SumizuriBytes.decode(signature, signatureEncoding ?: "base64"))
        }
    }
}

internal object SumizuriCompress {

    fun gzipDecode(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    fun gzipEncode(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    fun deflateDecode(bytes: ByteArray, raw: Boolean): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes), Inflater(raw)).use { it.readBytes() }

    fun deflateEncode(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, Deflater()).use { it.write(bytes) } }.toByteArray()

    fun zipEntries(bytes: ByteArray): List<Map<String, Any>> {
        val entries = mutableListOf<Map<String, Any>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val size = if (entry.size >= 0) entry.size else zip.readBytes().size.toLong()
                    entries += mapOf("name" to entry.name, "size" to size)
                }
            }
        }
        return entries
    }

    fun zipRead(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == name) return zip.readBytes()
            }
        }
        throw IllegalStateException("No entry named \"$name\" in the archive")
    }
}

/**
 * Parsed pages for host.parse(html): the 24 most recent are kept, and every element handed to JS gets a number
 * so it can be searched again from there (el.select(...)).
 */
internal class SumizuriHtmlPages(private val maxPages: Int = 24) {

    private class Page(val html: String, val document: Document) {
        val elements = mutableListOf<Element>()
        val ids = IdentityHashMap<Element, Int>()

        fun register(element: Element): Int = ids.getOrPut(element) {
            elements += element
            elements.size - 1
        }
    }

    private val pages = LinkedHashMap<Int, Page>(16, 0.75f, true)
    private val idByHtml = HashMap<String, Int>()
    private var nextId = 1

    @Synchronized
    fun parse(html: String, baseUrl: String = ""): Int {
        idByHtml[html]?.let { known -> if (pages[known] != null) return known }
        val id = nextId++
        pages[id] = Page(html, Jsoup.parse(html, baseUrl))
        idByHtml[html] = id
        while (pages.size > maxPages) {
            val oldest = pages.keys.first()
            pages.remove(oldest)?.let { idByHtml.remove(it.html) }
        }
        return id
    }

    @Synchronized
    fun select(pageId: Int, from: Int?, css: String?, xpath: String?, withHtml: Boolean, limit: Int?): Map<String, Any?> {
        require((css == null) != (xpath == null)) { "Give either a CSS selector or an XPath" }
        val page = pages[pageId] ?: throw IllegalStateException(
            "That parsed page is gone (only the $maxPages most recent are kept). Call host.parse(html) again.",
        )
        val root: Element = if (from == null) {
            page.document
        } else {
            page.elements.getOrNull(from) ?: throw IllegalStateException("No element numbered $from in this page")
        }
        fun describe(element: Element) = buildMap<String, Any?> {
            put("id", page.register(element))
            put("tag", element.tagName())
            put("text", element.text().trim())
            if (withHtml) put("html", element.html())
            put("attributes", element.attributes().associate { it.key to it.value })
        }
        fun <T> List<T>.limited() = if (limit == null) this else take(limit)

        if (css != null) {
            val found = try {
                root.select(css)
            } catch (e: Exception) {
                throw IllegalArgumentException("Bad selector \"$css\": ${e.message}")
            }
            return mapOf("items" to found.toList().limited().map(::describe))
        }

        // An XPath that names an attribute (//a/@href) or text (//p/text()) answers with values
        val expr = xpath!!.trim()
        val attr = Regex("""/@([\w:-]+)$""").find(expr)
        if (attr != null) {
            val elements = root.selectXpath(expr.substring(0, attr.range.first))
            val values = elements.mapNotNull { el -> el.attr(attr.groupValues[1]).takeIf { el.hasAttr(attr.groupValues[1]) } }
            return mapOf("values" to values.limited())
        }
        if (expr.endsWith("/text()")) {
            val texts = root.selectXpath(expr, TextNode::class.java).map { it.text().trim() }
            return mapOf("values" to texts.limited())
        }
        return mapOf("items" to root.selectXpath(expr).toList().limited().map(::describe))
    }

    /** host.query(html, selector): elements as text, html and attributes */
    fun query(html: String, selector: String): List<Map<String, Any?>> {
        val id = parse(html)
        @Suppress("UNCHECKED_CAST")
        val items = select(id, null, selector, null, true, null)["items"] as List<Map<String, Any?>>
        return items.map { mapOf("text" to it["text"], "html" to it["html"], "attributes" to it["attributes"]) }
    }
}
