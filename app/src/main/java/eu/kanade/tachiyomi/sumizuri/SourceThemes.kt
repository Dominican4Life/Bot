package eu.kanade.tachiyomi.sumizuri

/**
 * KMK: Full JSON definitions for site themes that many extensions share, used by "Start from APK" when the APK is
 * built on one. They follow the themes' usual markup; a site that changed its theme may need a selector adjusted.
 */
internal object SourceThemes {

    private fun header(source: ApkImport.SourceGuess, iconUrl: String?, theme: String) = """
// Made from a $theme extension. $theme sites share their layout, so this should work with few changes.
{
  "name": ${source.name.jsQuoted()},
  "lang": ${source.lang.jsQuoted()},
  "baseUrl": ${source.baseUrl.trimEnd('/').jsQuoted()},
  "iconUrl": ${iconUrl.orEmpty().jsQuoted()},
  "headers": { "Referer": "{baseUrl}/" },"""

    private const val COVER =
        """{ "or": [ { "selector": "img", "attr": "data-src" }, { "selector": "img", "attr": "data-lazy-src" }, """ +
            """{ "selector": "img", "attr": "srcset", "regex": "^(\\S+)" }, { "selector": "img", "attr": "src" } ], "resolve": true }"""

    fun madara(source: ApkImport.SourceGuess, iconUrl: String?): String {
        fun list(url: String) = """{
    "url": "$url",
    "itemSelector": "div.page-item-detail, div.manga__item",
    "fields": {
      "url": { "selector": "div.post-title a, .post-title a", "attr": "href", "resolve": true },
      "title": { "selector": "div.post-title a, .post-title a" },
      "coverUrl": $COVER
    }
  }"""
        return header(source, iconUrl, "Madara") + """
  "popular": ${list("{baseUrl}/manga/page/{page}/?m_orderby=views")},
  "latest": ${list("{baseUrl}/manga/page/{page}/?m_orderby=latest")},
  "search": {
    "url": "{baseUrl}/page/{page}/?s={query}&post_type=wp-manga",
    "itemSelector": "div.c-tabs-item__content",
    "fields": {
      "url": { "selector": "div.post-title a", "attr": "href", "resolve": true },
      "title": { "selector": "div.post-title a" },
      "coverUrl": $COVER
    }
  },
  "details": {
    "url": "{entryUrl}",
    "fields": {
      "title": { "selector": "div.post-title h1, #manga-title h1" },
      "coverUrl": { "or": [ { "selector": "div.summary_image img", "attr": "data-src" }, { "selector": "div.summary_image img", "attr": "src" } ], "resolve": true },
      "author": { "selector": "div.author-content a", "all": true, "separator": ", " },
      "description": { "selector": "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.manga-excerpt" },
      "genres": { "selector": "div.genres-content a", "all": true },
      "status": { "selector": "div.post-status div.summary-content" }
    }
  },
  "chapters": {
    "url": "{entryUrl}ajax/chapters/",
    "method": "POST",
    "headers": { "X-Requested-With": "XMLHttpRequest" },
    "itemSelector": "li.wp-manga-chapter",
    "fields": {
      "url": { "selector": "a", "attr": "href", "resolve": true },
      "title": { "selector": "a" },
      "number": { "selector": "a", "regex": "(\\d+(?:\\.\\d+)?)", "transform": "number" },
      "dateUploaded": { "selector": "span.chapter-release-date", "transform": "parseDate" }
    }
  },
  "pages": {
    "url": "{chapterUrl}",
    "itemSelector": "div.page-break img, li.blocks-gallery-item img, div.reading-content img",
    "fields": {
      "imageUrl": { "or": [ { "attr": "data-src" }, { "attr": "data-lazy-src" }, { "attr": "src" } ], "transform": "trim", "resolve": true }
    }
  }
}
"""
    }

    fun mangaThemesia(source: ApkImport.SourceGuess, iconUrl: String?): String {
        fun list(url: String) = """{
    "url": "$url",
    "itemSelector": "div.bs div.bsx, div.listupd div.bs",
    "fields": {
      "url": { "selector": "a", "attr": "href", "resolve": true },
      "title": { "or": [ { "selector": "a", "attr": "title" }, { "selector": "div.tt" } ] },
      "coverUrl": $COVER
    }
  }"""
        return header(source, iconUrl, "MangaThemesia") + """
  "popular": ${list("{baseUrl}/manga/?page={page}&order=popular")},
  "latest": ${list("{baseUrl}/manga/?page={page}&order=update")},
  "search": ${list("{baseUrl}/page/{page}/?s={query}")},
  "details": {
    "url": "{entryUrl}",
    "fields": {
      "title": { "selector": "h1.entry-title" },
      "coverUrl": { "or": [ { "selector": "div.thumb img", "attr": "data-src" }, { "selector": "div.thumb img", "attr": "src" } ], "resolve": true },
      "author": { "selector": "div.imptdt:contains(Author) i, div.fmed:contains(Author) span" },
      "description": { "selector": "div.entry-content[itemprop=description], div.desc" },
      "genres": { "selector": "div.wd-full span.mgen a, div.seriestugenre a", "all": true },
      "status": { "selector": "div.imptdt:contains(Status) i" }
    }
  },
  "chapters": {
    "url": "{entryUrl}",
    "itemSelector": "div.eplister li, #chapterlist li",
    "fields": {
      "url": { "selector": "a", "attr": "href", "resolve": true },
      "title": { "or": [ { "selector": ".chapternum" }, { "selector": "a" } ] },
      "number": { "attr": "data-num", "transform": "number" },
      "dateUploaded": { "selector": ".chapterdate", "transform": "parseDate" }
    }
  },
  "pages": {
    "url": "{chapterUrl}",
    "jsonRegex": "ts_reader\\.run\\((\\{.*?\\})\\);",
    "itemsPath": "sources.0.images",
    "fields": {
      "imageUrl": { "template": "{value}" }
    }
  }
}
"""
    }
}
