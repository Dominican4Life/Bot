package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

/**
 * KMK: "Start from APK" in the Code editor. A Mihon / Aniyomi extension is compiled code, so it can't be turned into
 * a working source by itself, but most of what a source needs is written in it as text: the site's address, the
 * paths it asks for and the CSS selectors it reads pages with. This reads those out of the APK's dex files, guesses
 * which is which, and fills a JS or JSON source with them, with every other find listed in a comment. Sites built on
 * a known theme (Madara, MangaThemesia) get that theme's full definition.
 */
object ApkImport {

    data class SourceGuess(val name: String, val lang: String, val baseUrl: String)

    data class Scan(
        val label: String,
        val pkgName: String,
        val version: String,
        val anime: Boolean,
        val sources: List<SourceGuess>,
        val iconUrl: String?,
        val theme: String?,
        val urls: List<String>,
        val paths: List<String>,
        val selectors: List<String>,
        val headers: List<String>,
    )

    /** Reads an APK file the user picked */
    fun scanFile(context: Context, apk: File): Scan {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA)
        val app = info?.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        val pkg = info?.packageName.orEmpty()
        val label = app?.loadLabel(pm)?.toString().orEmpty()
            .removePrefix("Tachiyomi: ").removePrefix("Aniyomi: ").removePrefix("Mihon: ")
            .ifBlank { pkg.substringAfterLast('.').ifBlank { apk.nameWithoutExtension } }
        val anime = pkg.contains(".animeextension.") ||
            app?.metaData?.keySet()?.any { it.startsWith("tachiyomi.animeextension") } == true
        return scan(
            apk = apk,
            label = label,
            pkgName = pkg,
            version = info?.versionName.orEmpty(),
            anime = anime,
            known = emptyList(),
            iconUrl = null,
        )
    }

    /** Reads an installed extension, with the names and addresses its sources report */
    fun scan(
        apk: File,
        label: String,
        pkgName: String,
        version: String,
        anime: Boolean,
        known: List<SourceGuess>,
        iconUrl: String?,
    ): Scan {
        val strings = dexStrings(apk)
        val urls = strings.filter(::isUrl).distinct()
        val siteUrls = urls.filterNot { url -> NOISE_HOSTS.any { it in url } }
        val lang = langFromPackage(pkgName)
        val sources = known.ifEmpty {
            val base = siteUrls.firstOrNull { it.count { c -> c == '/' } <= 3 }?.trimEnd('/')
                ?: siteUrls.firstOrNull()?.let { u -> u.substring(0, u.indexOf('/', 8).takeIf { it > 0 } ?: u.length) }
                ?: ""
            listOf(SourceGuess(label, lang, base))
        }
        return Scan(
            label = label,
            pkgName = pkgName,
            version = version,
            anime = anime,
            sources = sources,
            iconUrl = iconUrl,
            theme = THEMES.firstOrNull { (marker, _) -> strings.any { marker in it } }?.second,
            urls = siteUrls.take(40),
            paths = strings.filter(::isPath).distinct().take(80),
            selectors = strings.filter(::isSelector).distinct().take(150),
            headers = strings.filter { it in HEADER_NAMES }.distinct(),
        )
    }

    private fun langFromPackage(pkg: String): String {
        // eu.kanade.tachiyomi.extension.en.mangadex -> en
        val parts = pkg.split('.')
        val index = parts.indexOfFirst { it == "extension" || it == "animeextension" }
        return parts.getOrNull(index + 1)?.takeIf { it.length in 2..5 } ?: "en"
    }

    // Reading the dex files

    /** Every text constant in the APK's code */
    fun dexStrings(apk: File): List<String> {
        val out = LinkedHashSet<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { Regex("""classes\d*\.dex""").matches(it.name) }
                .forEach { entry -> zip.getInputStream(entry).use { readDexStrings(it.readBytes(), out) } }
        }
        return out.toList()
    }

    private fun readDexStrings(dex: ByteArray, out: MutableSet<String>) {
        fun u32(at: Int) = (dex[at].toInt() and 0xff) or ((dex[at + 1].toInt() and 0xff) shl 8) or
            ((dex[at + 2].toInt() and 0xff) shl 16) or ((dex[at + 3].toInt() and 0xff) shl 24)
        if (dex.size < 0x70 || String(dex, 0, 3) != "dex") return
        val count = u32(0x38)
        val idsOffset = u32(0x3C)
        for (i in 0 until count) {
            var at = u32(idsOffset + i * 4)
            if (at <= 0 || at >= dex.size) continue
            // uleb128 length in UTF-16 units, then MUTF-8 bytes ending with 0
            while (at < dex.size && dex[at].toInt() and 0x80 != 0) at++
            at++
            val end = (at until dex.size).firstOrNull { dex[it].toInt() == 0 } ?: continue
            val length = end - at
            if (length < 2 || length > 400) continue
            val text = String(dex, at, length, Charsets.UTF_8)
            if (text.isNotBlank()) out += text
        }
    }

    // Telling the finds apart

    private fun isUrl(text: String) = Regex("""^https?://[^\s"'<>{}]+$""").matches(text) && text.length < 200

    private fun isPath(text: String): Boolean {
        if (text.length !in 2..120 || text.any { it.isWhitespace() }) return false
        if (text.startsWith("/")) {
            return Regex("""^/[\w\-./?=&%{}:+,~]*$""").matches(text) &&
                !text.endsWith(".kt") && !text.endsWith(".java") && !text.startsWith("//") &&
                PATH_NOISE.none { text.startsWith(it) } && text.any { it.isLetter() }
        }
        // Query parts such as "?s=", "&page=" or "page/"
        return Regex("""^[?&][\w\-]+=[\w\-]*$|^[\w\-]+/$|^[\w\-]+=$""").matches(text) && text.length < 40
    }

    private fun isSelector(text: String): Boolean {
        if (text.length !in 2..160 || text.endsWith(".") || text.first().isDigit() || ".." in text) return false
        if (!Regex("""^[\w .#\[\]="'\-:>+~*,()^$|]+$""").matches(text)) return false
        // Package and class names look like selectors ("java.lang.String")
        if (Regex("""^[a-z]+(\.[A-Za-z_$]+){2,}$""").matches(text)) return false
        val hasSelectorChar = text.any { it in ".#[>" } || ":contains(" in text || ":has(" in text
        val tokens = text.split(Regex("""\s*[,>+~]\s*|\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val tokensOk = tokens.all { token ->
            val head = token.takeWhile { it.isLetterOrDigit() }
            token.first() in ".#[*:" || (head.isNotEmpty() && head in TAGS)
        }
        if (!tokensOk) return false
        // A bare tag ("a", "img") or two bare words are too common to mean anything
        return hasSelectorChar || tokens.size >= 2
    }

    private val TAGS = setOf(
        "a", "abbr", "article", "aside", "b", "body", "br", "button", "center", "code", "dd", "div", "dl", "dt", "em",
        "figure", "figcaption", "font", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "html",
        "i", "iframe", "img", "input", "label", "li", "link", "main", "meta", "nav", "noscript", "ol", "option",
        "p", "picture", "script", "section", "select", "small", "source", "span", "strong", "sup", "table", "tbody",
        "td", "textarea", "th", "thead", "time", "title", "tr", "track", "u", "ul", "video",
    )

    private val NOISE_HOSTS = listOf(
        "schemas.android.com", "www.w3.org", "apache.org", "schema.org", "kotlinlang.org", "jsoup.org",
        "googleapis.com/auth", "xmlpull.org", "ns.adobe.com", "json-schema.org", "example.com", "localhost",
    )

    private val PATH_NOISE = listOf("/data/", "/system/", "/proc/", "/dev/", "/sdcard", "/storage/", "/META-INF", "/res/")

    private val HEADER_NAMES = setOf(
        "Referer", "Origin", "User-Agent", "X-Requested-With", "Accept", "Accept-Language", "Authorization", "Cookie",
    )

    private val THEMES = listOf(
        "multisrc/madara/Madara" to "Madara",
        "multisrc.madara.Madara" to "Madara",
        "multisrc/mangathemesia/MangaThemesia" to "MangaThemesia",
        "multisrc.mangathemesia.MangaThemesia" to "MangaThemesia",
    )

    // Writing the source

    private fun pick(list: List<String>, vararg words: String): String? =
        list.firstOrNull { item -> words.any { item.contains(it, ignoreCase = true) } }

    /** A JS or JSON source for [source] of [scan], filled with what was found */
    fun template(scan: Scan, source: SourceGuess, json: Boolean): String {
        if (json && !scan.anime) {
            when (scan.theme) {
                "Madara" -> return SourceThemes.madara(source, scan.iconUrl)
                "MangaThemesia" -> return SourceThemes.mangaThemesia(source, scan.iconUrl)
            }
        }
        val guess = Guess.from(scan)
        return if (json) jsonTemplate(scan, source, guess) else jsTemplate(scan, source, guess)
    }

    private class Guess(
        val popular: String,
        val latest: String,
        val search: String,
        val item: String,
        val chapter: String,
        val page: String,
    ) {
        companion object {
            fun from(scan: Scan): Guess {
                val paths = scan.paths
                val selectors = scan.selectors
                return Guess(
                    popular = pick(paths, "popular", "views", "rank", "trending", "top", "hot") ?: "/popular?page={page}",
                    latest = pick(paths, "latest", "update", "recent", "new") ?: "/latest?page={page}",
                    search = (pick(paths, "search", "?s=", "keyword", "query", "q=") ?: "/search?q={query}&page={page}")
                        .let { if ("{query}" in it) it else if (it.endsWith("=")) it + "{query}" else it },
                    item = pick(selectors, "item", "manga", "anime", "post", "card", "bsx", "book", "film", "movie", "list")
                        ?: "div.item",
                    chapter = pick(selectors, "chapter", "episode", "eplister", "ep-", "chap") ?: "ul.chapters li",
                    page = selectors.firstOrNull { s ->
                        "img" in s && listOf("page", "reader", "chapter", "read", "viewer", "break", "content").any { it in s }
                    } ?: "div.reader img",
                )
            }
        }
    }

    private fun findsComment(scan: Scan, prefix: String): String = buildString {
        fun section(title: String, items: List<String>) {
            if (items.isEmpty()) return
            append(prefix).append(' ').append(title).append(":\n")
            items.forEach { append(prefix).append("   ").append(it.replace("*/", "* /")).append('\n') }
        }
        append(prefix).append(" Started from ").append(scan.label)
        if (scan.version.isNotBlank()) append(' ').append(scan.version)
        if (scan.pkgName.isNotBlank()) append(" (").append(scan.pkgName).append(')')
        append(".\n")
        append(prefix).append(" The APK is compiled code, so these are guesses from the text inside it. The finds\n")
        append(prefix).append(" are listed below: swap them in where the guesses are wrong, then test on the Test tab.\n")
        scan.theme?.let { append(prefix).append(" The extension is built on the ").append(it).append(" theme.\n") }
        section("Addresses", scan.urls)
        section("Paths and query parts", scan.paths)
        section("CSS selectors", scan.selectors)
        section("Headers it sets", scan.headers)
    }

    private fun jsTemplate(scan: Scan, source: SourceGuess, g: Guess): String {
        val listing = if (scan.anime) "getVideoList" else "getPageList"
        val content = if (scan.anime) {
            """
              // Videos of an episode, best first. Many sites hide the stream in an iframe or a script:
              // host.render(url, { capture: /\.m3u8|\.mp4/ }) gives the addresses the page loads.
              getVideoList: async function (url) {
                var doc = await page(url);
                var sources = await doc.select("video source, video");
                return sources.map(function (s) {
                  return { url: abs(s.attr("src")), quality: s.attr("label") || "Video", headers: { Referer: extension.baseUrl + "/" } };
                });
              },
            """.trimIndent()
        } else {
            """
              getPageList: async function (url) {
                var doc = await page(url);
                var images = await doc.select(${g.page.jsQuoted()});
                return images.map(function (img, i) {
                  return { index: i, imageUrl: abs(img.attr("data-src") || img.attr("data-lazy-src") || img.attr("src")) };
                });
              },
            """.trimIndent()
        }
        return buildString {
            append(findsComment(scan, "//"))
            append('\n')
            append(
                """
var extension = {
  name: ${source.name.jsQuoted()},
  lang: ${source.lang.jsQuoted()},
  baseUrl: ${source.baseUrl.jsQuoted()},
  iconUrl: ${scan.iconUrl.orEmpty().jsQuoted()},

  getPopular: async function (page) {
    return list(${path(source, g.popular)});
  },
  getLatest: async function (page) {
    return list(${path(source, g.latest)});
  },
  search: async function (query, page, filters) {
    return list(${path(source, g.search)});
  },

  getDetails: async function (url) {
    var doc = await page(url);
    return {
      url: url,
      title: (await doc.text("h1")) || "",
      coverUrl: abs(await doc.attr("meta[property=og:image]", "content")),
      description: await doc.attr("meta[property=og:description]", "content"),
    };
  },

  getChapterList: async function (url) {
    var doc = await page(url);
    var rows = await doc.select(${g.chapter.jsQuoted()});
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var link = (await rows[i].selectOne("a")) || rows[i];
      var title = rows[i].text;
      var number = parseFloat((title.match(/(\d+(\.\d+)?)/) || [])[1]);
      out.push({ url: abs(link.attr("href")), title: title, number: isNaN(number) ? undefined : number });
    }
    return out;
  },

${content.prependIndent("  ")}
};

// One page of titles: each match of the item selector, with its link, title and cover.
async function list(url) {
  var doc = await page(url);
  var items = await doc.select(${g.item.jsQuoted()});
  var out = [];
  for (var i = 0; i < items.length; i++) {
    var link = (await items[i].selectOne("a")) || items[i];
    var img = await items[i].selectOne("img");
    out.push({
      url: abs(link.attr("href")),
      title: link.attr("title") || link.text || items[i].text,
      coverUrl: img ? abs(img.attr("data-src") || img.attr("data-lazy-src") || img.attr("src")) : undefined,
    });
  }
  return out;
}

// A page of the site, parsed.
async function page(url) {
  var response = await fetch(abs(url), { headers: { Referer: extension.baseUrl + "/" } });
  if (!response.ok) throw new Error("The site answered " + response.status + " for " + url);
  return host.parse(await response.text());
}

// A full address from one the site wrote.
function abs(url) {
  if (!url) return url;
  return require("url").resolve(extension.baseUrl, url);
}
""".trimStart(),
            )
        }
    }

    /** A path guess as JS: the base address, the path, and the page / query put in */
    private fun path(source: SourceGuess, path: String): String {
        val full = if (path.startsWith("http")) path else source.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        return full.jsQuoted()
            .replace("{page}", "\" + page + \"")
            .replace("{query}", "\" + encodeURIComponent(query) + \"")
            .replace(" + \"\"", "")
    }

    private fun jsonTemplate(scan: Scan, source: SourceGuess, g: Guess): String {
        fun url(path: String) = when {
            path.startsWith("http") -> path
            else -> "{baseUrl}/" + path.trimStart('/')
        }.let { if ("{page}" in it || "page" in it) it else it + (if ('?' in it) "&" else "?") + "page={page}" }
        fun listSection(path: String, search: Boolean = false): String {
            var address = url(path)
            if (search && "{query}" !in address) address += "&q={query}"
            return """
    {
      "url": ${address.jsQuoted()},
      "itemSelector": ${g.item.jsQuoted()},
      "fields": {
        "url": { "selector": "a", "attr": "href", "resolve": true },
        "title": { "or": [ { "selector": "a", "attr": "title" }, { "selector": "a" } ] },
        "coverUrl": { "or": [ { "selector": "img", "attr": "data-src" }, { "selector": "img", "attr": "data-lazy-src" }, { "selector": "img", "attr": "src" } ], "resolve": true }
      }
    }""".trimStart()
        }
        val content = if (scan.anime) {
            """
  "videos": {
    "url": "{chapterUrl}",
    "itemSelector": "video source, video",
    "fields": {
      "url": { "attr": "src", "resolve": true },
      "quality": { "attr": "label", "default": "Video" }
    },
    "videoHeaders": { "Referer": "{baseUrl}/" }
  }"""
        } else {
            """
  "pages": {
    "url": "{chapterUrl}",
    "itemSelector": ${g.page.jsQuoted()},
    "fields": {
      "imageUrl": { "or": [ { "attr": "data-src" }, { "attr": "data-lazy-src" }, { "attr": "src" } ], "transform": "trim", "resolve": true }
    }
  }"""
        }
        return findsComment(scan, "//") + """
{
  "name": ${source.name.jsQuoted()},
  "lang": ${source.lang.jsQuoted()},
  "baseUrl": ${source.baseUrl.jsQuoted()},
  "iconUrl": ${scan.iconUrl.orEmpty().jsQuoted()},
  "headers": { "Referer": "{baseUrl}/" },
  "popular": ${listSection(g.popular)},
  "latest": ${listSection(g.latest)},
  "search": ${listSection(g.search, search = true)},
  "details": {
    "url": "{entryUrl}",
    "fields": {
      "title": { "selector": "h1" },
      "coverUrl": { "selector": "meta[property=og:image]", "attr": "content", "resolve": true },
      "description": { "selector": "meta[property=og:description]", "attr": "content" }
    }
  },
  "chapters": {
    "url": "{entryUrl}",
    "itemSelector": ${g.chapter.jsQuoted()},
    "fields": {
      "url": { "selector": "a", "attr": "href", "resolve": true },
      "title": { "selector": "a" },
      "number": { "selector": "a", "regex": "(\\d+(?:\\.\\d+)?)", "transform": "number" }
    }
  },
$content
}
"""
    }
}
