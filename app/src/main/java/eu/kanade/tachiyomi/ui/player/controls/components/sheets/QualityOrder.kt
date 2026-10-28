package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import eu.kanade.tachiyomi.animesource.model.Video

/**
 * KMK: the order the Qualities menu shows a server's videos in: highest quality first (1080p, 720p, 480p, 360p),
 * like AniZen. Videos that differ only by quality stay together ("SUB - …" apart from "DUB - …", in the order the
 * source gave them), and videos with no quality in their name ("Auto") come last in their group.
 *
 * Only the menu changes: which video starts is still the source's (or the preferred quality's) pick, and every
 * index used for playing stays the list's own.
 */
internal object QualityOrder {

    private val HEIGHT = Regex("""(?<![\d.])(\d{3,4})\s?[pP](?![a-zA-Z])""")
    private val AUTO = Regex("""\bauto\b""", RegexOption.IGNORE_CASE)
    private val EDGES = Regex("""^[\s\-·|:,/]+|[\s\-·|:,/]+$""")
    private val GAPS = Regex("""\s*[\-·|]\s*[\-·|]\s*""")

    private fun heightOf(video: Video): Int? =
        video.resolution?.takeIf { it > 0 }
            ?: HEIGHT.findAll(video.videoTitle).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private fun groupOf(video: Video): String =
        video.videoTitle
            .replace(HEIGHT, " ")
            .replace(AUTO, " ")
            .replace(GAPS, " - ")
            .replace(EDGES, "")
            .trim()
            .lowercase()

    /** Positions of [videos] in menu order (each value is an index into [videos]) */
    fun of(videos: List<Video>): List<Int> {
        if (videos.size < 2) return videos.indices.toList()
        val groups = LinkedHashMap<String, MutableList<Int>>()
        videos.forEachIndexed { index, video ->
            groups.getOrPut(groupOf(video)) { mutableListOf() } += index
        }
        return groups.values.flatMap { members ->
            // stable: equal qualities, and those without one, keep the source's order
            members.sortedByDescending { heightOf(videos[it]) ?: -1 }
        }
    }
}
