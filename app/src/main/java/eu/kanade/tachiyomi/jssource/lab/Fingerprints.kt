package eu.kanade.tachiyomi.jssource.lab

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.jssource.arr
import eu.kanade.tachiyomi.jssource.bool
import eu.kanade.tachiyomi.jssource.int
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.obj
import eu.kanade.tachiyomi.jssource.str
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * KMK (r96, Source Lab): the engine fingerprints the Detector tests a site against. Read from
 * `fingerprints.json` through `LabFiles.store(LabFileKind.Fingerprints)` (an imported file wins over the asset),
 * the shape of CONTRACT-LAB §8.4. Every field has a default, so a hand-edited file never fails to load: a marker
 * the parser doesn't understand is kept and simply never matches.
 */
@Immutable
internal data class FingerprintFile(val version: Int, val engines: List<EngineFingerprint>)

@Immutable
internal data class EngineFingerprint(
    val id: String,
    val name: String,
    val kind: String,                 // manga | anime | either
    val template: String,
    /** Selector of links from the home page to a title page; the Detector follows the first one */
    val entryLink: String,
    val markers: List<Marker>,
    val fill: List<FillSpec>,
) {
    val totalWeight: Int get() = markers.sumOf { it.weight }
}

@Immutable
internal data class Marker(
    val id: String,
    val weight: Int,
    /** html (home page) · title (first title page) · url · script (inline scripts of the home page) · request */
    val where: String,
    val test: MarkerTest,
    val evidence: String,
)

/** The test kinds of §8.4: contains, regex, selectorCount (+min), post / get (+status, bodyContains) */
@Immutable
internal data class MarkerTest(
    val contains: String? = null,
    val regex: String? = null,
    val selectorCount: String? = null,
    val min: Int = 1,
    val post: String? = null,
    /** the JSON key is `get`; named so no reader mistakes it for an accessor */
    val getPath: String? = null,
    val body: String? = null,
    val status: Int? = null,
    val bodyContains: String? = null,
) {
    val isRequest: Boolean get() = post != null || getPath != null
}

/** One value the Detector lifts from the pages for the template (`fill` block of §8.4) */
@Immutable
internal data class FillSpec(
    val key: String,
    /** regex · firstAttrWithHttp · probe · head */
    val from: String,
    val where: String = "html",
    val pattern: String? = null,
    val selector: String? = null,
    val attrs: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val withoutReferer403: Boolean = false,
    val default: String = "",
)

internal object Fingerprints {

    const val FILE_NAME = "fingerprints.json"

    /** The current fingerprints (imported file, else the asset); an empty file on any failure, logged */
    fun load(): FingerprintFile {
        val text = runCatching { LabFiles.store(LabFileKind.Fingerprints).current(FILE_NAME) }
            .onFailure { AppLog.add('W', "lab", "Couldn't read $FILE_NAME: ${it.message}") }
            .getOrNull()
            ?: return FingerprintFile(0, emptyList())
        return parse(text)
    }

    fun parse(text: String): FingerprintFile {
        val root = runCatching { jsJson.parseToJsonElement(text) as? JsonObject }
            .onFailure { AppLog.add('W', "lab", "$FILE_NAME isn't valid JSON: ${it.message}") }
            .getOrNull()
            ?: return FingerprintFile(0, emptyList())
        val engines = root.arr("engines")?.mapNotNull { it as? JsonObject }?.mapNotNull { engine(it) }.orEmpty()
        return FingerprintFile(version = root.int("version") ?: 1, engines = engines)
    }

    /** The version a fingerprints text declares (top-level "version"), 1 when missing */
    fun versionOf(text: String): Int =
        runCatching { (jsJson.parseToJsonElement(text) as? JsonObject)?.int("version") }.getOrNull() ?: 1

    private fun engine(o: JsonObject): EngineFingerprint? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return EngineFingerprint(
            id = id,
            name = o.str("name") ?: id,
            kind = o.str("kind") ?: "either",
            template = o.str("template") ?: id,
            entryLink = o.str("entryLink").orEmpty(),
            markers = o.arr("markers")?.mapNotNull { it as? JsonObject }?.mapNotNull { marker(it) }.orEmpty(),
            fill = o.obj("fill")?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { fill(key, it) } }.orEmpty(),
        )
    }

    private fun marker(o: JsonObject): Marker? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val t = o.obj("test") ?: JsonObject(emptyMap())
        return Marker(
            id = id,
            weight = (o.int("weight") ?: 1).coerceIn(1, 3),
            where = o.str("where") ?: "html",
            test = MarkerTest(
                contains = t.str("contains"),
                regex = t.str("regex"),
                selectorCount = t.str("selectorCount"),
                min = t.int("min") ?: 1,
                post = t.str("post"),
                getPath = t.str("get"),
                body = t.str("body"),
                status = t.int("status"),
                bodyContains = t.str("bodyContains"),
            ),
            evidence = o.str("evidence") ?: id,
        )
    }

    private fun fill(key: String, o: JsonObject): FillSpec = FillSpec(
        key = key,
        from = o.str("from") ?: "regex",
        where = o.str("where") ?: "html",
        pattern = o.str("pattern"),
        selector = o.str("selector"),
        attrs = o.arr("attrs")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        candidates = o.arr("candidates")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        withoutReferer403 = o.bool("withoutReferer403") ?: false,
        default = o.str("default").orEmpty(),
    )
}
