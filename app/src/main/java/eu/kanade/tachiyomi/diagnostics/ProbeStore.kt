package eu.kanade.tachiyomi.diagnostics

import android.content.Context
import eu.kanade.tachiyomi.jssource.jsJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.SecureRandom

/**
 * KMK (r97): The saved probes: `filesDir/diag/probes/probes.json` (the descriptors) and `<id>.js` next to it (the
 * code). Ids are 12 hex characters of a random long. New / import / paste all go through [save].
 */
internal class ProbeStore(context: Context) {

    private val dir = File(context.filesDir, DiagPaths.PROBES_DIR)
    private val index = File(context.filesDir, DiagPaths.PROBES_INDEX)
    private val lock = Any()

    private val _probes = MutableStateFlow<List<ProbeDescriptor>>(emptyList())
    val probes: StateFlow<List<ProbeDescriptor>> = _probes.asStateFlow()

    /** Reads the index (IO worker at start); a probe saved before the read finished stays as saved */
    fun load() {
        val list = runCatching {
            if (index.isFile) jsJson.decodeFromString(ListSerializer(ProbeDescriptor.serializer()), index.readText()) else emptyList()
        }.getOrDefault(emptyList())
        synchronized(lock) {
            _probes.value = (_probes.value + list.filter { File(dir, "${it.id}.js").isFile }).distinctBy { it.id }
        }
    }

    private fun persist() {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "probes.json.tmp")
            tmp.writeText(jsJson.encodeToString(ListSerializer(ProbeDescriptor.serializer()), _probes.value))
            if (!tmp.renameTo(index)) {
                index.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun get(id: String): ProbeDescriptor? = _probes.value.firstOrNull { it.id == id }

    fun code(id: String): String = runCatching { File(dir, "$id.js").readText() }.getOrDefault("")

    /** Saves a draft (new when `draft.id` is null); the hook list comes from a static scan of the code */
    fun save(draft: ProbeDraft): ProbeDescriptor = synchronized(lock) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val hooks = hooksOf(draft.code)
        val existing = draft.id?.let { get(it) }
        val descriptor = existing?.copy(name = draft.name.ifBlank { existing.name }, hooks = hooks, updatedAt = now)
            ?: ProbeDescriptor(
                id = newId(),
                name = draft.name.ifBlank { nameOf(draft.code) ?: "probe" },
                hooks = hooks,
                createdAt = now,
                updatedAt = now,
            )
        File(dir, "${descriptor.id}.js").writeText(draft.code)
        _probes.value = _probes.value.filterNot { it.id == descriptor.id } + descriptor
        persist()
        descriptor
    }

    fun update(id: String, change: (ProbeDescriptor) -> ProbeDescriptor): ProbeDescriptor? = synchronized(lock) {
        val current = get(id) ?: return null
        val next = change(current)
        _probes.value = _probes.value.map { if (it.id == id) next else it }
        persist()
        next
    }

    fun delete(id: String) = synchronized(lock) {
        File(dir, "$id.js").delete()
        _probes.value = _probes.value.filterNot { it.id == id }
        persist()
    }

    /** A `.js` file's text as a new probe; the name from `// @name` or the file name */
    fun import(fileName: String, code: String): ProbeDescriptor =
        save(ProbeDraft(null, nameOf(code) ?: fileName.substringBeforeLast('.').ifBlank { "probe" }, code))

    private fun newId(): String {
        val random = SecureRandom()
        while (true) {
            val id = "%012x".format(random.nextLong() and 0xFFFFFFFFFFFFL)
            if (get(id) == null) return id
        }
    }

    companion object {
        private val HOOK_CALL = Regex("""probe\s*\.\s*on\s*\(\s*['"]([a-zA-Z.]+)['"]""")
        private val NAME_LINE = Regex("""^\s*//\s*@name\s+(.+)$""", RegexOption.MULTILINE)

        /** Hook ids the code declares with `probe.on('…')`, known or not (lint reports the unknown ones) */
        fun hooksOf(code: String): List<String> = HOOK_CALL.findAll(code).map { it.groupValues[1] }.distinct().toList()

        fun nameOf(code: String): String? = NAME_LINE.find(code)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        /** The "New script" template for [hook]: its event fields, a filter, one log line */
        fun template(hook: ProbeHook): String {
            val fields = hook.reference.joinToString("\n") { (k, v) -> "//   $k${if (v.isNotEmpty()) " · $v" else ""}" }
            val variable = if (hook == ProbeHook.NET_REQUEST || hook == ProbeHook.NET_RESPONSE) "r" else "e"
            val body = when (hook) {
                ProbeHook.NET_REQUEST -> "  log.info('request', { method: r.method, url: r.url, source: r.source });"
                ProbeHook.NET_RESPONSE -> "  log.info('response', { status: r.status, ms: r.ms, size: r.size, url: r.url });\n" +
                    "  // var head = r.text(2048); // the body's first 2 KB (only when this call is in the script)"
                ProbeHook.JS_CALL -> "  log.info(e.source + ' ' + e.fn, { args: e.args });"
                ProbeHook.JS_RESULT -> "  if (e.ms > 1000 || e.error) log.warn(e.source + ' ' + e.fn, { ms: e.ms, items: e.items, error: e.error });"
                ProbeHook.PLAYER_EVENT -> "  log.info(e.event, { pos: e.posS, cache: e.demuxerCacheS, dropped: e.dropped });"
                ProbeHook.HLS_SEGMENT -> "  if (e.status >= 400 || e.ms > 2000) log.warn(e.kind + ' ' + e.host, { status: e.status, ms: e.ms });"
                ProbeHook.HOSTER_STEP -> "  log.info(e.step, { hoster: e.hoster, ms: e.ms, count: e.count, error: e.error });"
                ProbeHook.READER_PAGE -> "  log.info('page ' + e.page, { fetchMs: e.fetchMs, decodeMs: e.decodeMs, cacheHit: e.cacheHit });"
                ProbeHook.SYNC_PHASE -> "  log.info(e.phase + ' ' + e.direction, { bytes: e.bytes, ms: e.ms, peer: e.peer });"
            }
            return """
                |// @name my-${hook.id.replace('.', '-')}-probe
                |// Fields of $variable on ${hook.id}:
                |$fields
                |// Filter: { source: 'name', host: 'example.com', fn: 'getLatest', event: 'FILE_LOADED' } or null
                |probe.on('${hook.id}', null, function ($variable) {
                |$body
                |});
                |
            """.trimMargin()
        }
    }
}
