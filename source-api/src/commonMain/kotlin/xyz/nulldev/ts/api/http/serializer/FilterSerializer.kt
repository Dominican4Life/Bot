package xyz.nulldev.ts.api.http.serializer

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.isSubclassOf

class FilterSerializer {
    private val serializers = listOf<Serializer<*>>(
        // SY -->
        AutoCompleteSerializer(this),
        // SY <--
        HeaderSerializer(this),
        SeparatorSerializer(this),
        SelectSerializer(this),
        TextSerializer(this),
        CheckboxSerializer(this),
        TriStateSerializer(this),
        GroupSerializer(this),
        SortSerializer(this),
    )

    fun serialize(filters: FilterList) = buildJsonArray {
        filters.filterIsInstance<Filter<Any?>>().forEach {
            add(serialize(it))
        }
    }

    fun serialize(filter: Filter<Any?>): JsonObject {
        return serializers
            .filterIsInstance<Serializer<Filter<Any?>>>()
            .firstOrNull {
                filter::class.isSubclassOf(it.clazz)
            }?.let { serializer ->
                buildJsonObject {
                    with(serializer) { serialize(filter) }

                    val classMappings = mutableListOf<Pair<String, Any>>()

                    serializer.mappings().forEach {
                        val res = it.second.get(filter)
                        put(it.first, res.toString())
                        classMappings += it.first to (res?.javaClass?.name ?: "null")
                    }

                    putJsonObject(CLASS_MAPPINGS) {
                        classMappings.forEach { (t, u) ->
                            put(t, u.toString())
                        }
                    }

                    put(TYPE, serializer.type)
                }
            } ?: throw IllegalArgumentException("Cannot serialize this Filter object!")
    }

    /**
     * KMK: Puts the saved values back into [filters]. A saved value that doesn't fit the filter at its place (the
     * source's filters changed since, or another source with the same id is installed) is left at its default.
     * Those are counted and written to Logs as one short line per saved set, once, instead of a full error for each
     * filter every time. [label] names the saved set in that line. Returns how many didn't fit.
     */
    fun deserialize(filters: FilterList, json: JsonArray, label: String? = null): Int {
        var failed = 0
        var first: Exception? = null
        val pairs = filters.filterIsInstance<Filter<Any?>>().zip(json)
        pairs.forEach { (filter, obj) ->
            try {
                deserialize(filter, obj.jsonObject)
            } catch (e: Exception) {
                failed++
                if (first == null) first = e
            }
        }
        if (failed > 0 && reported.add("${label.orEmpty()}#${json.hashCode()}")) {
            val reason = first?.let { "${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            logcat(LogPriority.WARN) {
                "${label ?: "Saved filters"}: $failed of ${pairs.size} saved filters don't fit this source's filters " +
                    "now (it changed, or another source with the same id is installed); left at their defaults. " +
                    "First: $reason"
            }
        }
        return failed
    }

    /** KMK: saved sets already reported in Logs this run */
    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    fun deserialize(filter: Filter<Any?>, json: JsonObject) {
        val serializer = serializers
            .filterIsInstance<Serializer<Filter<Any?>>>()
            .firstOrNull {
                it.type == json[TYPE]!!.jsonPrimitive.content
            } ?: throw IllegalArgumentException("Cannot deserialize this type!")

        serializer.deserialize(json, filter)

        serializer.mappings().forEach {
            if (it.second is KMutableProperty1) {
                val obj = json[it.first]!!.jsonPrimitive
                val res: Any? = when (json[CLASS_MAPPINGS]!!.jsonObject[it.first]!!.jsonPrimitive.content) {
                    java.lang.Integer::class.java.name -> obj.int
                    java.lang.Long::class.java.name -> obj.long
                    java.lang.Float::class.java.name -> obj.float
                    java.lang.Double::class.java.name -> obj.double
                    java.lang.String::class.java.name -> obj.content
                    java.lang.Boolean::class.java.name -> obj.boolean
                    java.lang.Byte::class.java.name -> obj.content.toByte()
                    java.lang.Short::class.java.name -> obj.content.toShort()
                    java.lang.Character::class.java.name -> obj.content[0]
                    "null" -> null
                    else -> throw IllegalArgumentException("Cannot deserialize this type!")
                }
                @Suppress("UNCHECKED_CAST")
                (it.second as KMutableProperty1<in Filter<Any?>, in Any?>).set(filter, res)
            }
        }
    }

    companion object {
        const val TYPE = "_type"
        const val CLASS_MAPPINGS = "_cmaps"
    }
}
