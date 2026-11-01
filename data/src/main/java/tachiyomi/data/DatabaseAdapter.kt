package tachiyomi.data

import app.cash.sqldelight.ColumnAdapter
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Date

object DateColumnAdapter : ColumnAdapter<Date, Long> {
    override fun decode(databaseValue: Long): Date = Date(databaseValue)
    override fun encode(value: Date): Long = value.time
}

private const val LIST_OF_STRINGS_SEPARATOR = ", "
object StringListColumnAdapter : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String) = if (databaseValue.isEmpty()) {
        emptyList()
    } else {
        databaseValue.split(LIST_OF_STRINGS_SEPARATOR)
    }
    override fun encode(value: List<String>) = value.joinToString(
        separator = LIST_OF_STRINGS_SEPARATOR,
    )
}

object UpdateStrategyColumnAdapter : ColumnAdapter<UpdateStrategy, Long> {
    override fun decode(databaseValue: Long): UpdateStrategy =
        UpdateStrategy.entries.getOrElse(databaseValue.toInt()) { UpdateStrategy.ALWAYS_UPDATE }

    override fun encode(value: UpdateStrategy): Long = value.ordinal.toLong()
}

object MemoColumnAdapter : ColumnAdapter<JsonObject, ByteArray> {
    // KMK (r96): nearly every row's memo is "{}"; no JSON parser run for those (one per library row per emission)
    private val emptyMemo = JsonObject(emptyMap())

    override fun decode(databaseValue: ByteArray): JsonObject {
        if (isEmptyObject(databaseValue)) return emptyMemo
        return Json.decodeFromString<JsonObject>(databaseValue.decodeToString())
    }

    private fun isEmptyObject(bytes: ByteArray): Boolean =
        bytes.size == 2 && bytes[0] == '{'.code.toByte() && bytes[1] == '}'.code.toByte()

    override fun encode(value: JsonObject): ByteArray {
        return value.toString().encodeToByteArray()
    }
}
