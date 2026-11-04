package eu.kanade.tachiyomi.ui.sourcelab.newsource.dir

import android.app.Application
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.lab.LabPaths
import eu.kanade.tachiyomi.ui.sourcelab.newsource.BuilderListReader
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** KMK (r119): one entry of a remembered directory (short keys: a long directory is thousands of these) */
@Serializable
internal data class DirCacheItem(
    val t: String,
    val h: String,
    val n: String = "",
    val v: String = "",
    val w: String = "",
    val l: String = "",
    val p: String = "",
)

/**
 * KMK (r119): a directory read for a site, remembered: [kind] "list" (r106 names) or "dir" (r107 scanned entries),
 * when it was read ([readAt]), the pages and why the read ended, [sig] (the scan's selectors + page range for "dir"),
 * [limit] the page limit asked and [complete] = it ended by itself (no next page / an empty or repeated page)
 */
@Serializable
internal data class DirCacheFile(
    val version: Int = 1,
    val host: String,
    val url: String,
    val kind: String,
    val readAt: Long,
    val pages: Int,
    val reason: String,
    val sig: String = "",
    val limit: Int = 0,
    val complete: Boolean = false,
    val items: List<DirCacheItem> = emptyList(),
)

/**
 * KMK (r119): directories read in the builder, remembered by site host + directory address in
 * `filesDir/sourcelab/dir_cache/<hash>.json`. Reopening uses them at once ("Read 2 h ago · Refresh"); one older than
 * [MAX_AGE_MS] (7 days) is dropped, so it is read again by itself. Small and best-effort: any failure = not remembered.
 */
internal object DirCache {

    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** At most this many remembered directories (the oldest go first), and entries per directory */
    private const val MAX_FILES = 60
    private const val MAX_ITEMS = 6_000

    private val dir: File? get() = runCatching {
        File(LabPaths.labRoot(Injekt.get<Application>().filesDir), "dir_cache")
    }.getOrNull()

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()

    private fun fileFor(url: String, kind: String): File? {
        val d = dir ?: return null
        val key = hostOf(url) + "\n" + url.trim().substringBefore('#') + "\n" + kind
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return File(d, "$hash.json")
    }

    /** The remembered read of [url] ([kind]), null when none, unreadable or older than 7 days (then dropped). Do on IO */
    fun get(url: String, kind: String): DirCacheFile? {
        val f = fileFor(url, kind) ?: return null
        if (!f.isFile) return null
        val read = runCatching { jsJson.decodeFromString(DirCacheFile.serializer(), f.readText()) }.getOrNull()
        if (read == null || read.kind != kind || read.url != url.trim().substringBefore('#')) {
            runCatching { f.delete() }
            return null
        }
        val age = System.currentTimeMillis() - read.readAt
        if (age > MAX_AGE_MS || age < -60_000L) {
            runCatching { f.delete() }
            AppLog.add('D', "lab", "extras directory cache: $url older than 7 days, read again")
            return null
        }
        return read
    }

    /** Remembers a read (do on IO); the oldest files beyond [MAX_FILES] are dropped */
    fun put(
        url: String,
        kind: String,
        pages: Int,
        reason: String,
        items: List<DirCacheItem>,
        sig: String = "",
        limit: Int = 0,
        complete: Boolean = false,
    ) {
        val f = fileFor(url, kind) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val data = DirCacheFile(
                host = hostOf(url),
                url = url.trim().substringBefore('#'),
                kind = kind,
                readAt = System.currentTimeMillis(),
                pages = pages,
                reason = reason,
                sig = sig,
                limit = limit,
                complete = complete,
                items = items.take(MAX_ITEMS),
            )
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(jsJson.encodeToString(DirCacheFile.serializer(), data))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            AppLog.add('D', "lab", "extras directory cache: $url ($kind) remembered, ${data.items.size} entries")
            prune()
        }.onFailure { AppLog.add('W', "lab", "extras directory cache: $url not remembered (${it.message})") }
    }

    private fun prune() {
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    // ---------- the builder's two kinds of directory reads ----------

    /** Whether a read is worth remembering: it ended by itself, or at the page limit / range asked */
    fun keeps(ended: String): Boolean = ended == "end" || ended == "limit"

    /** A r106 list read of [url] with up to [limit] pages, remembered when it ended by itself or at its limit */
    fun putList(url: String, r: BuilderListReader.Result, limit: Int) {
        if (!keeps(r.ended) || r.items.isEmpty()) return
        put(
            url = url,
            kind = "list",
            pages = r.pages,
            reason = r.reason,
            items = r.items.map { DirCacheItem(t = it.text, h = it.href) },
            sig = r.shape.orEmpty(),
            limit = limit,
            complete = r.ended == "end",
        )
    }

    /** The remembered list of [url] for [limit] pages (one that ended by itself, or read with as many pages or more) */
    fun getList(url: String, limit: Int): Pair<BuilderListReader.Result, Long>? {
        val c = get(url, "list") ?: return null
        if (!c.complete && c.limit < limit) return null
        val items = c.items.map { BuilderListReader.Item(it.t, it.h) }
        if (items.isEmpty()) return null
        return BuilderListReader.Result(items, c.pages, c.reason, c.sig.ifEmpty { null }, if (c.complete) "end" else "limit") to c.readAt
    }

    /** What a scanned directory's read depends on: its cards and parts, its page address and the page range */
    fun sigOf(scan: BuilderListReader.DirScan, from: Int, to: Int): String =
        listOf(
            scan.item,
            scan.link,
            scan.parts.joinToString(";") { "${it.kind}=${it.selector}@${it.attr}${if (it.own) "!" else ""}${if (it.bare) "#" else ""}" },
            scan.pageTemplate.orEmpty(),
            "$from-$to",
        ).joinToString("|")

    fun putDir(scan: BuilderListReader.DirScan, from: Int, to: Int, r: BuilderListReader.DirRead) {
        if (!keeps(r.ended) || r.entries.isEmpty()) return
        put(
            url = scan.url,
            kind = "dir",
            pages = r.pages,
            reason = r.reason,
            items = r.entries.map { DirCacheItem(it.label, it.href, it.number, it.videos, it.views, it.likes, it.picture) },
            sig = sigOf(scan, from, to),
            limit = to - from + 1,
            complete = r.ended == "end",
        )
    }

    /** The remembered read of [scan]'s pages [from]..[to] (same cards, parts and range), with when it was read */
    fun getDir(scan: BuilderListReader.DirScan, from: Int, to: Int): Pair<BuilderListReader.DirRead, Long>? {
        val c = get(scan.url, "dir") ?: return null
        if (c.sig != sigOf(scan, from, to) || c.items.isEmpty()) return null
        val entries = c.items.map { BuilderListReader.DirEntry(it.t, it.h, it.n, it.v, it.w, it.l, it.p) }
        return BuilderListReader.DirRead(entries, c.pages, c.reason, if (c.complete) "end" else "limit") to c.readAt
    }

    /** "Read 2 h ago" */
    fun agoText(readAt: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((now - readAt).coerceAtLeast(0L) / 60_000L)
        return when {
            min < 1 -> "Read just now"
            min < 60 -> "Read $min min ago"
            min < 48 * 60 -> "Read ${min / 60} h ago"
            else -> "Read ${min / (24 * 60)} days ago"
        }
    }
}
