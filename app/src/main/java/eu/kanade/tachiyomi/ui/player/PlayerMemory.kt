package eu.kanade.tachiyomi.ui.player

import android.app.Application
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KMK (r119): what the player learned per source, from videos that played well (or didn't):
 * - servers by NAME (hoster name, or the video title without its quality when the source has no hoster list), with a
 *   Sub / Dub / Raw kind taken from the name or the video title. "HD-2 · Sub" and "HD-2 · Dub" are two servers, so a
 *   Sub pick never moves to Dub. Each counts played (30 s without an error) and failed (load error, 403, timeout,
 *   self-heal gave up).
 * - the quality label ("1080p") last played,
 * - decoding: "hw" (hardware worked) or "sw" (hardware failed for this source, so software from then on),
 * - connection: "direct" (mpv's own request worked) or "app" (a plain file got 403, so files go through the
 *   app's connection, HlsRelay.wrapFile, from then on).
 *
 * One small JSON per source: filesDir/player_memory/<sourceId>.json. Reset deletes only that file. Player settings,
 * other sources, history and downloads are never touched here.
 */
object PlayerMemory {

    @Serializable
    data class Server(
        val name: String,
        val kind: String = "",
        val played: Int = 0,
        val failed: Int = 0,
        val lastError: String? = null,
        val lastAt: Long = 0L,
    )

    @Serializable
    data class Memory(
        val servers: List<Server> = emptyList(),
        val lastKind: String? = null,
        val quality: String? = null,
        val decoding: String? = null,
        val connection: String? = null,
    ) {
        val isEmpty: Boolean
            get() = servers.isEmpty() && quality == null && decoding == null && connection == null
    }

    /** A server's identity: its name and its Sub / Dub / Raw kind ("" when neither says) */
    data class Slot(val name: String, val kind: String) {
        val label: String get() = if (kind.isEmpty()) name else "$name · $kind"
    }

    /** What the player tries first: a hoster of this title, the server inside it and the quality */
    data class Target(val hosterIndex: Int, val slot: Slot, val quality: String?, val played: Int)

    enum class Dot { Played, Mixed, Failed, NotTried }

    /** A server row's dot and counts in the Quality sheet */
    data class Chip(
        val dot: Dot,
        val played: Int,
        val failed: Int,
        val remembered: Boolean,
        val lastError: String?,
    )

    const val DECODING_HARDWARE = "hw"
    const val DECODING_SOFTWARE = "sw"
    const val CONNECTION_DIRECT = "direct"
    const val CONNECTION_APP = "app"

    private const val MAX_SERVERS = 40

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val cache = ConcurrentHashMap<Long, Memory>()
    private val lock = Any()

    private val _version = MutableStateFlow(0)

    /** Changes whenever a source's memory changes (the Quality sheet and the dialog follow it) */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun dir(): File = File(Injekt.get<Application>().filesDir, "player_memory")

    private fun file(sourceId: Long): File = File(dir(), "$sourceId.json")

    /** The source's memory, read from its file the first time. IO threads only (a small file read). */
    fun load(sourceId: Long): Memory {
        cache[sourceId]?.let { return it }
        val read = runCatching {
            file(sourceId).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Memory.serializer(), it) }
        }.getOrNull() ?: Memory()
        return cache.putIfAbsent(sourceId, read) ?: read
    }

    /** The source's memory when it was read already, else null (never reads the disk: safe on the main thread) */
    fun peek(sourceId: Long): Memory? = cache[sourceId]

    private fun change(sourceId: Long, block: (Memory) -> Memory) {
        launchIO {
            synchronized(lock) {
                val old = load(sourceId)
                val next = block(old)
                if (next == old) return@synchronized
                cache[sourceId] = next
                runCatching {
                    val target = file(sourceId)
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "${target.name}.tmp")
                    tmp.writeText(json.encodeToString(Memory.serializer(), next))
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        tmp.renameTo(target)
                    }
                }.onFailure { PlayerDebugLog.add("memory", "Couldn't save: ${it.message}") }
            }
            _version.update { it + 1 }
        }
    }

    // --- Names, kinds and qualities ---

    // ICU-safe (no bare braces): "Sub", "SUB", "Subbed", "Softsub", "Hardsub", "Dub", "Dubbed", "Raw"
    private val KIND = Regex("""(?i)(?<![a-z])(?:soft|hard)?(sub|dub|raw)(?:bed|s|titles?|titled)?(?![a-z])""")
    private val HEIGHT = Regex("""(?<![\d.])(\d{3,4})\s?[pP](?![a-zA-Z])""")
    private val UHD = Regex("""(?i)(?<![a-z])(4k|uhd)(?![a-z])""")
    private val AUTO = Regex("""(?i)(?<![a-z])auto(?![a-z])""")
    private val EDGES = Regex("""^[\s\-·|:,/()\[\]]+|[\s\-·|:,/()\[\]]+$""")
    private val GAPS = Regex("""\s*[\-·|]\s*[\-·|]\s*""")
    private val SPACES = Regex("""\s+""")

    /** "Sub", "Dub", "Raw" or "" */
    fun kindOf(text: String): String =
        when (KIND.find(text)?.groupValues?.get(1)?.lowercase()) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "raw" -> "Raw"
            else -> ""
        }

    /** "1080p" (from the video's resolution or its title), or null when the video doesn't say */
    fun qualityOf(video: Video): String? {
        video.resolution?.takeIf { it > 0 }?.let { return "${it}p" }
        HEIGHT.findAll(video.videoTitle).lastOrNull()?.groupValues?.get(1)?.let { return "${it}p" }
        return if (UHD.containsMatchIn(video.videoTitle)) "2160p" else null
    }

    private fun serverNameFromTitle(title: String): String =
        title.replace(HEIGHT, " ")
            .replace(UHD, " ")
            .replace(AUTO, " ")
            .replace(GAPS, " - ")
            .replace(SPACES, " ")
            .replace(EDGES, "")
            .trim()
            .ifEmpty { title.trim() }

    /** The server a video of [hosterName] belongs to */
    fun slotOf(hosterName: String, video: Video?): Slot {
        val title = video?.videoTitle.orEmpty()
        if (hosterName == Hoster.NO_HOSTER_LIST) return Slot(serverNameFromTitle(title), kindOf(title))
        return Slot(hosterName.trim(), kindOf(hosterName).ifEmpty { kindOf(title) })
    }

    private fun Server.slot() = Slot(name, kind)

    private fun Server.score() = played - 2 * failed

    private fun Server.usable() = played > 0 && score() > 0

    private fun better(a: Server, b: Server): Boolean =
        a.score() > b.score() || (a.score() == b.score() && a.lastAt > b.lastAt)

    /** The best-scored server of [kind] (any kind when null) that [present] accepts */
    private fun best(memory: Memory, kind: String?, present: (Server) -> Boolean): Server? {
        var pick: Server? = null
        for (s in memory.servers) {
            if (!s.usable() || (kind != null && s.kind != kind) || !present(s)) continue
            if (pick == null || better(s, pick)) pick = s
        }
        return pick
    }

    /** The server the dialog shows as "Server first": the best one of the kind played last */
    fun firstServer(memory: Memory): Server? =
        best(memory, memory.lastKind) { true } ?: best(memory, null) { true }

    // --- Choosing the first video ---

    /**
     * The hoster of [hosters] to try first: [sessionPick] (the user's own pick in this player session) when this
     * title has it, else the best-scored remembered server of the kind played last. Null = the normal order.
     * Only the hoster NAME is known here; [videoIndexIn] checks the kind on the loaded videos.
     */
    fun target(memory: Memory, hosters: List<Hoster>, sessionPick: Pair<Slot, String?>?): Target? {
        fun hosterFor(slot: Slot): Int {
            if (hosters.size == 1 && hosters[0].hosterName == Hoster.NO_HOSTER_LIST) return 0
            val exact = hosters.indexOfFirst { it.hosterName.trim() == slot.name && kindOf(it.hosterName) == slot.kind }
            if (exact >= 0) return exact
            return hosters.indexOfFirst { it.hosterName.trim() == slot.name && kindOf(it.hosterName).isEmpty() }
        }
        if (sessionPick != null) {
            val idx = hosterFor(sessionPick.first)
            if (idx >= 0) {
                val played = memory.servers.firstOrNull { it.slot() == sessionPick.first }?.played ?: 0
                return Target(idx, sessionPick.first, sessionPick.second, played)
            }
        }
        val kind = memory.lastKind ?: return null
        val server = best(memory, kind) { hosterFor(it.slot()) >= 0 } ?: return null
        return Target(hosterFor(server.slot()), server.slot(), memory.quality, server.played)
    }

    /**
     * The video of [state] to play for [target]: one of its server (same name AND kind), at the remembered quality
     * when it's there, else the source's preferred one, else the first. -1 when this hoster has none of that server.
     */
    fun videoIndexIn(target: Target, state: HosterState.Ready): Int {
        val candidates = state.videoList.indices.filter { idx ->
            val s = state.videoState.getOrNull(idx)
            (s == Video.State.READY || s == Video.State.QUEUE) &&
                slotOf(state.name, state.videoList[idx]) == target.slot
        }
        if (candidates.isEmpty()) return -1
        target.quality?.let { wanted ->
            candidates.firstOrNull { qualityOf(state.videoList[it]) == wanted }?.let { return it }
        }
        return candidates.firstOrNull { state.videoList[it].preferred } ?: candidates.first()
    }

    // --- The Quality sheet's server rows ---

    /**
     * The server a hoster row stands for. A name without a kind takes it from the videos: the kind played last when
     * one of them has it, else the first video's. Not loaded yet: the stored server of that name (kind played last
     * first), else the name alone.
     */
    private fun slotForRow(memory: Memory, state: HosterState): Slot {
        val nameKind = kindOf(state.name)
        if (nameKind.isNotEmpty()) return Slot(state.name.trim(), nameKind)
        val videos = (state as? HosterState.Ready)?.videoList.orEmpty()
        if (videos.isNotEmpty()) {
            val kinds = videos.map { kindOf(it.videoTitle) }
            val kind = memory.lastKind?.takeIf { it in kinds } ?: kinds.first()
            return Slot(state.name.trim(), kind)
        }
        val stored = memory.servers.filter { it.name == state.name.trim() }
        return (stored.firstOrNull { it.kind == memory.lastKind } ?: stored.firstOrNull())?.slot()
            ?: Slot(state.name.trim(), "")
    }

    /** Dot + counts per hoster index (not for the one pseudo-hoster of a source without a hoster list) */
    fun chips(memory: Memory?, hosterState: List<HosterState>): Map<Int, Chip> {
        if (memory == null) return emptyMap()
        val slots = hosterState.mapIndexedNotNull { idx, state ->
            if (state.name == Hoster.NO_HOSTER_LIST) return@mapIndexedNotNull null
            idx to slotForRow(memory, state)
        }
        val present = slots.map { it.second }.toSet()
        val remembered = (
            best(memory, memory.lastKind) { it.slot() in present } ?: best(memory, null) { it.slot() in present }
            )?.slot()
        return slots.associate { (idx, slot) ->
            val s = memory.servers.firstOrNull { it.slot() == slot }
            val played = s?.played ?: 0
            val failed = s?.failed ?: 0
            val dot = when {
                played > 0 && failed == 0 -> Dot.Played
                played > 0 -> Dot.Mixed
                failed > 0 -> Dot.Failed
                else -> Dot.NotTried
            }
            idx to Chip(dot, played, failed, slot == remembered, s?.lastError)
        }
    }

    /** True when this title lists servers of more than one kind (the sheet then says Sub and Dub stay apart) */
    fun hasSeveralKinds(hosterState: List<HosterState>): Boolean =
        hosterState.mapNotNull { state ->
            slotOf(state.name, (state as? HosterState.Ready)?.videoList?.firstOrNull()).kind.ifEmpty { null }
        }.toSet().size > 1

    // --- Recording ---

    private fun List<Server>.upsert(slot: Slot, block: (Server) -> Server): List<Server> {
        val old = firstOrNull { it.slot() == slot } ?: Server(slot.name, slot.kind)
        val updated = filterNot { it.slot() == slot } + block(old)
        return if (updated.size <= MAX_SERVERS) updated else updated.sortedByDescending { it.lastAt }.take(MAX_SERVERS)
    }

    /**
     * 30 s played without an error. [hardware]: whether hardware decoding was in use (null = unknown);
     * [connection]: [CONNECTION_DIRECT] / [CONNECTION_APP] for a plain file, null for a stream.
     */
    fun recordPlayed(sourceId: Long, slot: Slot, quality: String?, hardware: Boolean?, connection: String?) =
        change(sourceId) { m ->
            m.copy(
                servers = m.servers.upsert(slot) {
                    it.copy(played = it.played + 1, lastAt = System.currentTimeMillis(), lastError = null)
                },
                lastKind = slot.kind,
                quality = quality ?: m.quality,
                // software stays once hardware failed; a user's own software choice isn't learned
                decoding = if (m.decoding == DECODING_SOFTWARE || hardware != true) m.decoding else DECODING_HARDWARE,
                // the app's connection stays once a direct request got 403
                connection = if (m.connection == CONNECTION_APP || connection == null) m.connection else connection,
            )
        }

    /**
     * A load error, 403, timeout or self-heal giving up. [drop]: the video was the remembered pick, so it stops being
     * remembered (its played count goes back to 0; the remembered quality too when it was that one).
     */
    fun recordFailed(sourceId: Long, slot: Slot, reason: String, drop: Boolean, quality: String?) =
        change(sourceId) { m ->
            m.copy(
                servers = m.servers.upsert(slot) {
                    it.copy(
                        played = if (drop) 0 else it.played,
                        failed = it.failed + 1,
                        lastError = reason,
                        lastAt = System.currentTimeMillis(),
                    )
                },
                quality = if (drop && quality != null && quality == m.quality) null else m.quality,
            )
        }

    /** Hardware decoding failed for this source: software from now on */
    fun rememberSoftware(sourceId: Long) = change(sourceId) { it.copy(decoding = DECODING_SOFTWARE) }

    /** A plain file of this source got 403 from mpv's own request: its files go through the app from now on */
    fun rememberThroughApp(sourceId: Long) = change(sourceId) { it.copy(connection = CONNECTION_APP) }

    /** Clears ONLY this source's memory */
    fun reset(sourceId: Long) {
        launchIO {
            synchronized(lock) {
                cache[sourceId] = Memory()
                runCatching { file(sourceId).delete() }
            }
            _version.update { it + 1 }
        }
    }

    /** "403", "timeout" or "error", for a server's chip */
    fun shortReason(message: String): String = when {
        message.contains("403") -> "403"
        message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) -> "timeout"
        else -> "error"
    }
}
