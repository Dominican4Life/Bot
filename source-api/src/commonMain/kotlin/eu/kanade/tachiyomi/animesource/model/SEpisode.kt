@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

import java.io.Serializable

interface SEpisode : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var episode_number: Float

    // AY -->
    var fillermark: Boolean
    // <-- AY

    var scanlator: String?

    // AY -->
    var summary: String?

    var preview_url: String?
    // <-- AY

    /** Extra data a source keeps on the episode (extensions-lib 17) */
    var memo: kotlinx.serialization.json.JsonObject

    fun copyFrom(other: SEpisode) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        episode_number = other.episode_number
        // AY -->
        fillermark = other.fillermark
        // <-- AY
        scanlator = other.scanlator
        // AY -->
        summary = other.summary
        preview_url = other.preview_url
        // <-- AY
        memo = other.memo
    }

    companion object {
        fun create(): SEpisode {
            return SEpisodeImpl()
        }
    }

    var chapter_number: Float
        get() = episode_number
        set(value) {
            episode_number = value
        }
}
