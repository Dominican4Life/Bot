package eu.kanade.tachiyomi.ui.codeeditor

/** KMK: Starting points for a new source */
object SourceTemplates {

    private val js = """
        var extension = {
          name: "",
          lang: "en",
          baseUrl: "",
          iconUrl: "",
          search: async function (query, page, filters) {
            return [];
          },
          getPopular: async function (page) {
            return [];
          },
          getLatest: async function (page) {
            return [];
          },
          getChapterList: async function (entryUrl) {
            return [];
          },
          getPageList: async function (chapterUrl) {
            return [];
          },
        };
    """.trimIndent() + "\n"

    private val animeJs = """
        var extension = {
          name: "",
          lang: "en",
          baseUrl: "",
          iconUrl: "",
          search: async function (query, page, filters) {
            return [];
          },
          getPopular: async function (page) {
            return [];
          },
          getLatest: async function (page) {
            return [];
          },
          getChapterList: async function (entryUrl) {
            // Episodes: one Chapter each, with season, seasonName and seasonCoverUrl for seasons.
            return [];
          },
          getVideoList: async function (chapterUrl) {
            // Best first. Each: { url, quality, headers, subtitles: [{ url, label, language }] }
            return [];
          },
        };
    """.trimIndent() + "\n"

    private fun listSection(path: String) = """
        {
          "url": "{baseUrl}/$path",
          "itemSelector": "div.item",
          "fields": {
            "url": { "selector": "a", "attr": "href" },
            "title": { "selector": "a", "attr": "text" },
            "coverUrl": { "selector": "img", "attr": "src" }
          }
        }
    """.trimIndent().prependIndent("  ").trimStart()

    private val json = """
{
  "name": "",
  "lang": "en",
  "baseUrl": "",
  "iconUrl": "",
  "popular": ${listSection("popular?page={page}")},
  "latest": ${listSection("latest?page={page}")},
  "search": ${listSection("search?q={query}&page={page}")},
  "chapters": {
    "url": "{entryUrl}",
    "itemSelector": "ul.chapters li a",
    "fields": {
      "url": { "attr": "href" },
      "title": { "attr": "text" }
    }
  },
  "pages": {
    "url": "{chapterUrl}",
    "itemSelector": "img.page-image",
    "fields": {
      "imageUrl": { "attr": "src" }
    }
  }
}
"""

    private val animeJson = """
{
  "name": "",
  "lang": "en",
  "baseUrl": "",
  "iconUrl": "",
  "popular": ${listSection("popular?page={page}")},
  "latest": ${listSection("latest?page={page}")},
  "search": ${listSection("search?q={query}&page={page}")},
  "chapters": {
    "url": "{entryUrl}",
    "itemSelector": "ul.episodes li a",
    "fields": {
      "url": { "attr": "href" },
      "title": { "attr": "text" }
    }
  },
  "videos": {
    "url": "{chapterUrl}",
    "itemSelector": "video source",
    "fields": {
      "url": { "attr": "src" },
      "quality": { "attr": "label" }
    },
    "videoHeaders": { "Referer": "{baseUrl}/" }
  }
}
"""

    fun templateFor(json: Boolean, anime: Boolean): String = when {
        json && anime -> animeJson.trimStart()
        json -> this.json.trimStart()
        anime -> animeJs
        else -> js
    }

    fun isTemplate(text: String): Boolean =
        listOf(true, false).any { j -> listOf(true, false).any { a -> templateFor(j, a).trim() == text.trim() } }
}
