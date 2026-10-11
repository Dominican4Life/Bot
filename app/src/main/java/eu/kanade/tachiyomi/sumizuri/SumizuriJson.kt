package eu.kanade.tachiyomi.sumizuri

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** KMK: Small JSON helpers shared by the Sumizuri host, sources and editor */
internal val sumizuriJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** A JS value (string, number, bool, list or object) as Kotlin, for preferences */
internal fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toPlain() }
    is JsonObject -> mapValues { it.value.toPlain() }
}

/** A Kotlin value as JSON */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

/** A string in JS source form, quoted and escaped */
internal fun String.jsQuoted(): String = JsonPrimitive(this).toString()

/**
 * Removes // and /* */ comments outside strings, so a JSON source can carry notes (as Sumizuri allows).
 */
internal fun stripJsonComments(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    var inString = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            out.append(c)
            if (c == '\\' && i + 1 < text.length) {
                out.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '"') inString = false
            i++
            continue
        }
        when {
            c == '"' -> {
                inString = true
                out.append(c)
                i++
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                while (i < text.length && text[i] != '\n') i++
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0) text.length else end + 2
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/** KMK: [method]'s list, or an empty one when it is getPopular / getLatest and the source doesn't have it */
internal suspend fun optionalListing(rt: SumizuriRuntime, method: String, vararg args: Any?): kotlinx.serialization.json.JsonElement =
    try {
        rt.call(method, *args)
    } catch (e: NotImplementedError) {
        if (method == "getPopular" || method == "getLatest") kotlinx.serialization.json.JsonArray(emptyList()) else throw e
    }
