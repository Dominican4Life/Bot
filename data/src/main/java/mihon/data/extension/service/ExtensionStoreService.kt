package mihon.data.extension.service

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.data.extension.model.AniyomiNetworkExtensionStore
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

class ExtensionStoreService(
    private val network: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {

    /**
     * KMK: Finds the repository at whatever address was typed or pasted: a folder, its repo.json, index.min.json or
     * index.pb, with or without https, a trailing slash or a query. The first form that reads as a repository wins.
     */
    suspend fun resolve(input: String): Result<ExtensionStore> {
        val candidates = ExtensionStoreUrls.candidates(input)
        if (candidates.isEmpty()) return Result.failure(IllegalArgumentException("Enter the repository's address"))
        var firstError: Throwable? = null
        for (url in candidates) {
            val result = fetch(url, quiet = true)
            if (result.isSuccess) return result
            if (firstError == null) firstError = result.exceptionOrNull()
        }
        logcat(LogPriority.ERROR, firstError) { "No repository found at '$input' (tried ${candidates.joinToString()})" }
        return Result.failure(firstError ?: IllegalStateException("No repository found at this address"))
    }

    suspend fun fetch(indexUrl: String): Result<ExtensionStore> = fetch(indexUrl, quiet = false)

    private suspend fun fetch(indexUrl: String, quiet: Boolean, depth: Int = 0): Result<ExtensionStore> {
        var updatedIndexUrl: String = indexUrl
        return try {
            // KMK -->
            val store = network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().use { response ->
                val source = response.body.source().decompressIfGzipped()
                // KMK <--
                when (source.peek().readByte()) {
                    // "[..." a legacy index.min.json: its details are in repo.json next to it
                    0x5B.toByte() -> {
                        val base = ExtensionStoreUrls.repositoryBase(indexUrl)
                        updatedIndexUrl = "$base/repo.json"
                        // KMK: some old repos have no repo.json: named after their address then
                        val repo = runCatching {
                            network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().use {
                                json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it.body.source().decompressIfGzipped())
                            }
                        }.getOrNull()
                        repo?.let { resolveV2(it, updatedIndexUrl, quiet, depth)?.let { v2 -> return v2 } }
                        repo?.toExtensionStore(updatedIndexUrl) ?: ExtensionStore(
                            indexUrl = "$base/index.min.json",
                            name = ExtensionStoreUrls.nameFromUrl(base),
                            badgeLabel = ExtensionStoreUrls.nameFromUrl(base),
                            signingKey = NO_SIGNING_KEY,
                            contact = ExtensionStore.Contact(website = base, discord = null),
                            isLegacy = true,
                            extensionListUrl = null,
                        ).also { updatedIndexUrl = it.indexUrl }
                    }
                    // "{..."
                    0x7B.toByte() -> {
                        val bytes = source.readByteArray()
                        val legacy = runCatching {
                            json.decodeFromString<NetworkLegacyExtensionRepo>(bytes.decodeToString())
                        }.getOrNull()
                        if (legacy != null) {
                            resolveV2(legacy, updatedIndexUrl, quiet, depth)?.let { return it }
                            legacy.toExtensionStore(updatedIndexUrl)
                        } else {
                            json.decodeFromString<NetworkExtensionStore>(bytes.decodeToString())
                                .toExtensionStore(updatedIndexUrl)
                        }
                    }
                    else -> decodeStore(source.readByteArray()).toExtensionStore(updatedIndexUrl)
                }
            }
            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) {
                logcat(LogPriority.ERROR, e) {
                    "Failed to add extension store '$updatedIndexUrl'"
                }
            }
            Result.failure(e)
        }
    }

    /** KMK: repo.json pointing at a newer index (index_v2), read relative to repo.json when it isn't a full address */
    private suspend fun resolveV2(
        repo: NetworkLegacyExtensionRepo,
        repoUrl: String,
        quiet: Boolean,
        depth: Int,
    ): Result<ExtensionStore>? {
        val v2 = repo.indexV2?.takeIf { it.isNotBlank() } ?: return null
        if (depth > 2) return null
        return fetch(ExtensionStoreUrls.resolve(repoUrl, v2), quiet, depth + 1).takeIf { it.isSuccess }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> {
        return try {
            val extensions = if (store.extensionListUrl != null) {
                // KMK -->
                val listUrl = ExtensionStoreUrls.resolve(store.indexUrl, store.extensionListUrl!!)
                network.client.newCall(GET(listUrl)).awaitSuccess().use { response ->
                    val source = response.body.source().decompressIfGzipped()
                    // KMK <--
                    when (source.peek().readByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
                        else -> decodeList(source.readByteArray())
                    }
                        .toAvailableExtensions(store)
                }
            } else if (!store.isLegacy) {
                // KMK -->
                network.client.newCall(GET(store.indexUrl)).awaitSuccess().use { response ->
                    val source = response.body.source().decompressIfGzipped()
                    // KMK <--
                    when (source.peek().readByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                        else -> decodeStore(source.readByteArray())
                    }
                        .extensionList!!
                        .toAvailableExtensions(store)
                }
            } else {
                // KMK: the index sits in the repository's folder, whichever file the store was saved as
                val storeBaseUrl = ExtensionStoreUrls.repositoryBase(store.indexUrl)
                network.client.newCall(GET("$storeBaseUrl/index.min.json")).awaitSuccess().use { response ->
                    val source = response.body.source().decompressIfGzipped()
                    json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                        .map { it.toAvailableExtension(store, storeBaseUrl) }
                }
            }
            Result.success(extensions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // KMK --> Mihon and Aniyomi protobuf indexes differ in one field: read with Mihon's, and with Aniyomi's when that
    // fails or finds no sources
    private fun decodeStore(bytes: ByteArray): NetworkExtensionStore = decodeEither(
        mihon = { protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) },
        aniyomi = { protoBuf.decodeFromByteArray<AniyomiNetworkExtensionStore>(bytes).toMihon() },
        hasSources = { store -> store.extensionList?.extensions.orEmpty().any { it.sources.isNotEmpty() } },
    )

    private fun decodeList(bytes: ByteArray): NetworkExtensionStore.ExtensionList = decodeEither(
        mihon = { protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes) },
        aniyomi = { protoBuf.decodeFromByteArray<AniyomiNetworkExtensionStore.ExtensionList>(bytes).toMihon() },
        hasSources = { list -> list.extensions.any { it.sources.isNotEmpty() } },
    )

    private inline fun <T> decodeEither(mihon: () -> T, aniyomi: () -> T, hasSources: (T) -> Boolean): T {
        val first = try {
            mihon()
        } catch (e: SerializationException) {
            return aniyomi()
        } catch (e: IllegalArgumentException) {
            return aniyomi()
        }
        if (hasSources(first)) return first
        val second = runCatching { aniyomi() }.getOrNull() ?: return first
        return if (hasSources(second)) second else first
    }
    // KMK <--

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }

    companion object {
        const val NO_SIGNING_KEY = "NO_SIGNING_KEY"
    }
}

/** KMK: Repository addresses, the way Kototoro reads them */
object ExtensionStoreUrls {
    private val indexFileNames = listOf("index.min.json", "index.json", "index.pb", "repo.json")

    /** The repository's folder: the address without its file name, query or trailing slashes */
    fun repositoryBase(url: String): String {
        var clean = url.trim().substringBefore('#').substringBefore('?')
        for (name in indexFileNames) {
            if (clean.endsWith("/$name", ignoreCase = true)) clean = clean.dropLast(name.length + 1)
        }
        while (clean.endsWith('/') && clean.length > 8) clean = clean.dropLast(1)
        return clean
    }

    /** A full address stays as it is; anything else is read next to [base]'s file */
    fun resolve(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return repositoryBase(base) + "/" + path.removePrefix("./").removePrefix("/")
    }

    /** The forms worth trying for what was typed, most likely first */
    fun candidates(input: String): List<String> {
        var url = input.trim().replace(" ", "")
        if (url.isEmpty()) return emptyList()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        // GitHub page links to a file: the raw file
        url = url.replace(Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/"), "https://raw.githubusercontent.com/$1/$2/")
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val base = repositoryBase(url)
        val list = mutableListOf<String>()
        val isFile = indexFileNames.any { withoutQuery.endsWith("/$it", ignoreCase = true) }
        if (isFile) list += withoutQuery
        list += "$base/repo.json"
        list += "$base/index.min.json"
        list += "$base/index.pb"
        if (url != withoutQuery) list += url
        return list.distinct()
    }

    /** One address for each repository, for telling whether two addresses are the same one */
    fun key(url: String): String = repositoryBase(url).lowercase()
        .removePrefix("https://").removePrefix("http://")
        .replace("raw.githubusercontent.com/", "github.com/")
        .replace("/raw/", "/").replace("/refs/heads/", "/")

    private val FINGERPRINT = Regex("^[0-9A-Fa-f]{2}(:?[0-9A-Fa-f]{2}){31}$")

    /**
     * KMK: True when [signingKey] is a real signing fingerprint (SHA-256 as hex). Repos without one (no repo.json,
     * or a placeholder like "no-info" in it) can't have their packages matched by signature, only by package name.
     */
    fun hasFingerprint(signingKey: String?): Boolean = signingKey != null && FINGERPRINT.matches(signingKey)

    /** "user/repo" from a GitHub address, else the host */
    fun nameFromUrl(url: String): String {
        val path = url.removePrefix("https://").removePrefix("http://").split('/').filter { it.isNotBlank() }
        return when {
            path.size >= 3 && (path[0].contains("github")) -> "${path[1]}/${path[2]}"
            else -> path.firstOrNull() ?: url
        }
    }
}
