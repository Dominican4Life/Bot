package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK: Icons of the sources made in the Code editor, so they show in Browse, the source sheets and the source
 * pages like an extension's icon does. Each is downloaded once (its iconUrl, else the site's touch icon or favicon)
 * and kept next to the source; extensions' icon lookups fall back to these.
 *
 * KMK: Each icon is also saved as "<source name>.png" in downloads/Icon, which survives a reinstall: after one
 * (or a restore) the icon is copied back from there instead of being downloaded again.
 */
object SumizuriIcons {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = ConcurrentHashMap<Long, Bitmap>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()
    private val files = ConcurrentHashMap<Long, File>()
    private val lock = Mutex()

    private fun fileFor(context: Context, info: SumizuriSourceInfo): File {
        val tag = (info.iconUrl.orEmpty() + "|" + info.baseUrl).hashCode().toUInt().toString(16)
        return File(context.filesDir, "sumizuri_sources/${info.key}/icon-$tag.img")
    }

    /** Makes sure the icon of each source is on disk, downloading the ones that aren't */
    fun prepare(context: Context, sources: List<SumizuriSourceInfo>) {
        sources.forEach { info ->
            val file = fileFor(context, info)
            files[info.id] = file
            if (!file.exists()) {
                memory.remove(info.id)
                missing.remove(info.id)
                // An older icon of this source here means its icon address changed: download the new one. None means
                // a fresh install or a restore: take the saved one from downloads/Icon if it's there
                val changed = file.parentFile?.listFiles { f -> f.name.startsWith("icon-") }?.isNotEmpty() == true
                scope.launch {
                    lock.withLock { if (changed || !fromIconFolder(info, file)) download(info, file) }
                }
            } else {
                // Icons kept from before the Icon folder existed: put a copy there too
                scope.launch { lock.withLock { toIconFolder(info, file.readBytes(), replace = false) } }
            }
        }
    }

    /** The icon of a Code editor source, or null (not one, or no icon yet) */
    fun bitmap(sourceId: Long): Bitmap? {
        memory[sourceId]?.let { return it }
        if (sourceId in missing) return null
        val file = files[sourceId] ?: return null
        if (!file.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap == null) {
            missing += sourceId
            return null
        }
        memory[sourceId] = bitmap
        return bitmap
    }

    fun drawable(context: Context, sourceId: Long): Drawable? =
        bitmap(sourceId)?.let { BitmapDrawable(context.resources, it) }

    private fun download(info: SumizuriSourceInfo, file: File) {
        if (file.exists()) return
        val network = Injekt.get<NetworkHelper>()
        val base = info.baseUrl.trimEnd('/')
        val candidates = listOfNotNull(
            info.iconUrl?.takeIf { it.startsWith("http") },
            base.takeIf { it.startsWith("http") }?.let { "$it/apple-touch-icon.png" },
            base.takeIf { it.startsWith("http") }?.let { "$it/favicon.ico" },
        ).distinct()
        for (url in candidates) {
            val bytes = runCatching {
                val request = Request.Builder().url(url)
                    .header("User-Agent", network.defaultUserAgentProvider())
                    .apply { if (base.isNotBlank()) header("Referer", "$base/") }
                    .build()
                network.client.newCall(request).execute().use { if (it.isSuccessful) it.body.bytes() else null }
            }.getOrNull() ?: continue
            // Only keep what Android can draw (an SVG icon can't be)
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: continue
            runCatching {
                file.parentFile?.listFiles { f -> f.name.startsWith("icon-") }?.forEach { it.delete() }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
            memory[info.id] = bitmap
            missing.remove(info.id)
            toIconFolder(info, bytes, replace = true)
            return
        }
    }

    private fun iconName(info: SumizuriSourceInfo): String =
        DiskUtil.buildValidFilename(info.name.ifBlank { info.key }) + ".png"

    /** Copies the source's icon back from downloads/Icon. False when it isn't there */
    private fun fromIconFolder(info: SumizuriSourceInfo, file: File): Boolean {
        val saved = runCatching {
            Injekt.get<StorageManager>().getIconsDirectory(create = false)?.findFile(iconName(info))
        }.getOrNull() ?: return false
        val bytes = runCatching { saved.openInputStream().use { it.readBytes() } }.getOrNull() ?: return false
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return false
        runCatching {
            file.parentFile?.listFiles { f -> f.name.startsWith("icon-") }?.forEach { it.delete() }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        memory[info.id] = bitmap
        missing.remove(info.id)
        return true
    }

    /** Saves the icon as a PNG in downloads/Icon (replacing an older one of the same source) */
    private fun toIconFolder(info: SumizuriSourceInfo, bytes: ByteArray, replace: Boolean) {
        runCatching {
            val dir = Injekt.get<StorageManager>().getIconsDirectory() ?: return
            val name = iconName(info)
            val existing = dir.findFile(name)
            if (!replace && existing != null && existing.length() > 0) return
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            // A new file rather than writing over the old one (some storage keeps the old file's tail)
            existing?.delete()
            val target = dir.createFile(name) ?: return
            target.openOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
