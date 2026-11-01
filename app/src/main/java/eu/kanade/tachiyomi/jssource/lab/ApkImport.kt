package eu.kanade.tachiyomi.jssource.lab

import android.content.Context
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.SideloadFolder
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * KMK (r96, Source Lab): "Import from extension". Lists the installed manga and anime extensions (one row per
 * loaded source, with the live id, name, language and address), reads the chosen extension's APK as a zip and
 * walks the string tables of its classes*.dex to tell which multisrc theme it was built on and to collect
 * candidate values (same-host addresses, date formats, selector-shaped strings, chapter paths). The source is
 * then written from the matching template with the live values; what the APK only hinted at is listed in a
 * comment block and in the first Explain entry, never guessed into the code.
 *
 * The loaded source's id travels as [GeneratedSource.sourceId], so library titles saved under the APK's source
 * keep working (Half A writes it as the custom id).
 *
 * The DEX reader is defensive: bounds-checked everywhere, at most 64 MB per dex, and any failure comes back as
 * "couldn't read the APK: <why>" rather than an exception.
 */
internal object ApkImport {

    /** One installed source as the picker shows it */
    data class ExtensionSourceRow(
        val pkgName: String,
        val extensionName: String,
        val versionName: String,
        val anime: Boolean,
        val sideloaded: Boolean,
        val sourceId: Long,
        val sourceName: String,
        val lang: String,
        val baseUrl: String,
    )

    /** What the APK's string tables gave */
    data class ApkFacts(
        val theme: String?,              // "madara", "mangathemesia", … (the multisrc folder name), null when none
        val themeClass: String?,         // the descriptor, e.g. "Leu/kanade/tachiyomi/multisrc/madara/Madara;"
        val sameHostUrls: List<String>,
        val paths: List<String>,
        val dateFormats: List<String>,
        val selectors: List<String>,
        val chapterPaths: List<String>,
        val attrs: List<String>,         // cover-attribute names seen as strings
        val tokens: List<String>,        // theme words: wp-manga post types, madara_load_more, manga_get_chapters…
        val dexFiles: Int,
        val strings: Int,
        val error: String?,              // "couldn't read the APK: <why>" when the zip or dex failed
    )

    data class ImportResult(
        val generated: GeneratedSource,
        val facts: ApkFacts,
        val themeRecognised: Boolean,    // a template exists for the theme
        val themeKnown: Boolean,         // the theme name is known even without a template
        val templateId: String,
    )

    /** Theme folder → template id */
    private val THEME_TEMPLATES = mapOf(
        "madara" to "madara",
        "mangathemesia" to "mangathemesia",
        "wpmangastream" to "mangathemesia",
        "wpmangareader" to "mangathemesia",
        "mangareader" to "mangareader",
        "foolslide" to "foolslide",
        "keyoapp" to "keyoapp",
    )

    /** Themes the Lab knows the name of but has no template for (manga unless listed as anime) */
    private val KNOWN_THEMES = setOf(
        "mmrcms", "zeistmanga", "heancms", "iken", "mangabox", "mangahub", "wpcomics", "peachscan", "libgroup",
        "gattsu", "otakusanctuary", "readerfront", "speedbinb", "sinmh", "etoshore", "fuzzydoodle", "galleryadults",
        "bakamanga", "flixscans", "lectormonline", "mangaesp", "mangaraw", "mangaworld", "monochrome", "paprika",
        "pizzareader", "terrascan", "webtoons", "hentaihand", "mangadventure", "nepnep", "comicake", "gigaviewer",
        "a3manga", "bilibili", "colamanga", "kemono", "zbulu", "grouple", "multichan", "pizzareader", "comicgamma",
    )
    private val ANIME_THEMES = setOf(
        "dooplay", "animestream", "zorotheme", "datalifeengine", "dopeflix", "anilist", "animeplayer", "yugenanime",
    )

    private val THEME_DESCRIPTOR = Regex("""^L(?:eu/kanade/tachiyomi|keiyoushi)/multisrc/([a-z0-9]+)/([A-Za-z0-9_$]+);$""")

    // ---------- installed extensions ----------

    /** Every loaded HTTP source of every installed extension, manga and anime, grouped by package in the result's order */
    suspend fun installed(): List<ExtensionSourceRow> {
        val manga = runCatching {
            val manager = Injekt.get<ExtensionManager>()
            withTimeoutOrNull(15_000) { manager.isInitialized.first { it } }
            manager.installedExtensionsFlow.value.flatMap { ext ->
                ext.sources.filterIsInstance<HttpSource>().map { source ->
                    ExtensionSourceRow(
                        pkgName = ext.pkgName,
                        extensionName = ext.name,
                        versionName = ext.versionName,
                        anime = false,
                        sideloaded = !ext.isShared || SideloadFolder.isSideloaded(SideloadFolder.Kind.MANGA, ext.pkgName),
                        sourceId = source.id,
                        sourceName = source.name,
                        lang = source.lang,
                        baseUrl = source.baseUrl,
                    )
                }
            }
        }.onFailure { if (it is CancellationException) throw it else AppLog.add('W', "lab", "Manga extensions not listed: ${it.message}") }
            .getOrDefault(emptyList())
        val anime = runCatching {
            val manager = Injekt.get<AnimeExtensionManager>()
            withTimeoutOrNull(15_000) { manager.isInitialized.first { it } }
            manager.installedExtensionsFlow.value.flatMap { ext ->
                ext.sources.filterIsInstance<AnimeHttpSource>().map { source ->
                    ExtensionSourceRow(
                        pkgName = ext.pkgName,
                        extensionName = ext.name,
                        versionName = ext.versionName,
                        anime = true,
                        sideloaded = !ext.isShared || SideloadFolder.isSideloaded(SideloadFolder.Kind.ANIME, ext.pkgName),
                        sourceId = source.id,
                        sourceName = source.name,
                        lang = source.lang,
                        baseUrl = source.baseUrl,
                    )
                }
            }
        }.onFailure { if (it is CancellationException) throw it else AppLog.add('W', "lab", "Anime extensions not listed: ${it.message}") }
            .getOrDefault(emptyList())
        return (manga + anime).sortedWith(compareBy<ExtensionSourceRow> { it.extensionName.lowercase() }.thenBy { it.anime }.thenBy { it.sourceName.lowercase() })
    }

    // ---------- the import ----------

    /** Reads the APK and writes the source; runs on IO (zip and dex reads) */
    fun import(context: Context, row: ExtensionSourceRow): ImportResult {
        val facts = readApk(context, row.pkgName, row.anime, row.baseUrl)
        val theme = facts.theme
        val templateId = when {
            theme != null && theme in THEME_TEMPLATES -> THEME_TEMPLATES.getValue(theme)
            row.anime -> "anime-player"
            else -> "generic-list"
        }
        val recognised = theme != null && theme in THEME_TEMPLATES
        val known = theme != null && (theme in KNOWN_THEMES || theme in ANIME_THEMES || recognised)
        val template = Templates.get(templateId)
            ?: throw IllegalStateException("Template $templateId is missing from the app")

        val values = LinkedHashMap<String, String>()
        values["name"] = row.sourceName
        values["lang"] = row.lang
        values["baseUrl"] = row.baseUrl.trimEnd('/')
        values["iconUrl"] = "https://www.google.com/s2/favicons?domain=${row.baseUrl.toHttpUrlOrNull()?.host.orEmpty()}&sz=128"
        val fromApk = ArrayList<String>()     // "key = value" lines of what the APK gave
        val guessed = ArrayList<String>()
        if (recognised) fillFromStrings(templateId, facts, values, fromApk, guessed)

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val header = ArrayList<String>()
        header.add("Imported from the extension ${row.extensionName} ${row.versionName} (${row.pkgName}) on $stamp.")
        header.add(
            when {
                recognised -> "Theme: ${facts.themeClass} → the ${template.name} template."
                known -> "Theme: ${facts.themeClass} (known, no template) → the ${template.name} template as the closest."
                facts.error != null -> "Theme: unknown (${facts.error}) → the ${template.name} template."
                else -> "Theme: none found in ${facts.dexFiles} dex file(s), ${facts.strings} strings → the ${template.name} template."
            },
        )
        header.add("Live from the loaded source: id ${row.sourceId}, name, lang, baseUrl. From the APK: ${fromApk.ifEmpty { listOf("nothing used") }.joinToString("; ")}.")
        if (guessed.isNotEmpty()) header.add("Guessed (not in the APK): ${guessed.joinToString("; ")}.")
        candidateLines(facts).forEach { header.add(it) }

        val parseParts = if (row.anime) {
            setOf("getPopular", "getLatest", "search", "getDetails", "getChapterList", "getVideoList")
        } else {
            setOf("getPopular", "getLatest", "search", "getDetails", "getChapterList", "getPageList")
        }
        val leading = ExplainEntry(
            id = "imported",
            title = "Imported from extension",
            text = buildString {
                append("${row.extensionName} ${row.versionName} (${row.pkgName}). ")
                append("From the loaded source: id ${row.sourceId}, name \"${row.sourceName}\", lang ${row.lang}, address ${row.baseUrl}. ")
                append(
                    when {
                        recognised -> "The APK was built on ${facts.themeClass}, so the ${template.name} template was used"
                        known -> "The APK was built on ${facts.themeClass}; the Lab has no template for it, so the ${template.name} template is the closest"
                        facts.error != null -> "The APK could not be read (${facts.error}); the ${template.name} template was used"
                        else -> "No multisrc theme in the APK (a custom extension); the ${template.name} template keeps the id, name and address"
                    },
                )
                if (fromApk.isNotEmpty()) append(". From the APK's strings: ${fromApk.joinToString("; ")}")
                if (guessed.isNotEmpty()) append(". Guessed: ${guessed.joinToString("; ")}")
                if (!recognised) append(". The parts that read pages are not written: use Build by tapping or the extension's code on GitHub")
                append(".")
            },
        )
        val lab = LabMeta(
            engine = if (recognised) templateId else null,
            templateId = template.id,
            templateVersion = template.version,
            detectedAt = System.currentTimeMillis(),
            siteUrl = values["baseUrl"],
            fromExtension = row.pkgName,
            fromTheme = facts.themeClass,
        )
        val themeWord = theme ?: if (facts.error != null) "APK not read" else "custom extension"
        val generated = Generator.generate(
            template = template,
            values = values,
            lab = lab,
            drop = if (template.id == "generic-list") setOf(if (row.anime) "getPageList" else "getVideoList") else emptySet(),
            note = "Imported from ${row.extensionName} ($themeWord)",
            suggestedName = row.sourceName,
            sourceId = row.sourceId,
            notWritten = if (recognised) emptySet() else parseParts,
            header = header,
            leading = listOf(leading),
            anime = row.anime,
        )
        AppLog.add('I', "lab", "Imported ${row.sourceName} from ${row.pkgName}: theme ${theme ?: "none"} → $templateId (id ${row.sourceId})")
        return ImportResult(generated, facts, recognised, known, template.id)
    }

    /** Template-specific values the APK's strings can settle; the rest keep the sidecar's defaults */
    private fun fillFromStrings(
        templateId: String,
        facts: ApkFacts,
        values: MutableMap<String, String>,
        fromApk: MutableList<String>,
        guessed: MutableList<String>,
    ) {
        val attr = Derive.COVER_ATTRS.firstOrNull { it in facts.attrs }
        if (attr != null) {
            values["coverAttr"] = attr
            fromApk.add("coverAttr $attr")
        } else {
            guessed.add("coverAttr (template default)")
        }
        when (templateId) {
            "madara" -> {
                val postType = facts.tokens.firstOrNull { Regex("""^wp-(manga|comic|novel|webtoon)[a-z-]*$""").matches(it) }
                if (postType != null) {
                    values["postType"] = postType
                    fromApk.add("postType $postType")
                } else {
                    guessed.add("postType wp-manga")
                }
                val via = when {
                    facts.chapterPaths.any { "ajax/chapters" in it } -> "ajax/chapters/"
                    "manga_get_chapters" in facts.tokens -> "admin-ajax.php"
                    else -> null
                }
                if (via != null) {
                    values["chaptersVia"] = via
                    fromApk.add("chaptersVia $via")
                } else {
                    guessed.add("chaptersVia ajax/chapters/")
                }
                values["imageReferer"] = "unknown (not checked on import)"
            }
            "mangathemesia" -> {
                val listPath = facts.paths.firstOrNull { Regex("""^/?(manga|series|komik|comics|webtoons?)/?$""").matches(it) }
                if (listPath != null) {
                    values["listPath"] = listPath.trim('/')
                    fromApk.add("listPath ${listPath.trim('/')}")
                } else {
                    guessed.add("listPath manga")
                }
            }
            "mangareader", "keyoapp" -> values["imageReferer"] = "unknown (not checked on import)"
        }
        if (facts.dateFormats.isNotEmpty()) fromApk.add("date format ${facts.dateFormats.first()} (in the comments)")
    }

    private fun candidateLines(facts: ApkFacts): List<String> {
        val out = ArrayList<String>()
        if (facts.error != null) {
            out.add("APK: ${facts.error}")
            return out
        }
        fun list(label: String, items: List<String>) {
            if (items.isEmpty()) return
            out.add("Candidates read from the APK, $label (not used unless named above):")
            items.chunked(4).forEach { chunk -> out.add("  " + chunk.joinToString("  ")) }
        }
        list("same-host addresses", facts.sameHostUrls.take(12))
        list("paths", facts.paths.take(16))
        list("date formats", facts.dateFormats.take(6))
        list("selectors", facts.selectors.take(24))
        list("chapter paths", facts.chapterPaths.take(8))
        return out
    }

    // ---------- the APK ----------

    /** The APK files of [pkgName]: the app's private copy (sideloaded) first, else the installed package and its splits */
    fun apkFiles(context: Context, pkgName: String, anime: Boolean): List<File> {
        val privateCopy = File(context.filesDir, (if (anime) "anime_exts" else "exts") + "/$pkgName.ext")
        if (privateCopy.isFile) return listOf(privateCopy)
        val info = runCatching { context.packageManager.getApplicationInfo(pkgName, 0) }.getOrNull() ?: return emptyList()
        val files = ArrayList<File>()
        info.sourceDir?.let { files.add(File(it)) }
        info.splitSourceDirs?.forEach { files.add(File(it)) }
        return files.filter { it.isFile }
    }

    fun readApk(context: Context, pkgName: String, anime: Boolean, baseUrl: String): ApkFacts {
        val files = apkFiles(context, pkgName, anime)
        if (files.isEmpty()) return empty("couldn't read the APK: no APK file for $pkgName")
        val strings = ArrayList<String>()
        var dexFiles = 0
        val failure = runCatching {
            for (file in files) {
                ZipFile(file).use { zip ->
                    val entries = zip.entries().toList().filter { Regex("""^classes\d*\.dex$""").matches(it.name) }
                    for (entry in entries) {
                        if (entry.size > MAX_DEX_BYTES) throw IllegalStateException("${entry.name} is ${entry.size / (1024 * 1024)} MB, more than the 64 MB the Lab reads")
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (bytes.size > MAX_DEX_BYTES) throw IllegalStateException("${entry.name} is larger than 64 MB")
                        dexFiles++
                        DexStrings.read(bytes, strings, MAX_STRINGS - strings.size)
                    }
                }
            }
        }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) {
            AppLog.add('W', "lab", "APK of $pkgName not read: ${failure.message}")
            return empty("couldn't read the APK: ${failure.message ?: failure.toString()}")
        }
        if (dexFiles == 0) return empty("couldn't read the APK: no classes.dex inside")
        return classify(strings, baseUrl, dexFiles)
    }

    private fun empty(error: String?) =
        ApkFacts(null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, error)

    private val DATE_FORMAT = Regex("""^(?=.*[dD])(?=.*M)(?=.*y)[dDMyYHhmsSEaz ,./:'\-T]{5,32}$""")
    private val SELECTOR = Regex("""^[a-zA-Z#.\[][a-zA-Z0-9.#\[\]=:*'"\s>,_()+~^$|\-]{2,120}$""")
    private val PATH = Regex("""^/[A-Za-z0-9_./?=&%\-]{1,80}$""")
    private val TOKEN = Regex("""^(wp-[a-z-]{3,24}|madara_load_more|manga_get_chapters|ts_reader|wp_manga_views|_latest_update)$""")

    private fun classify(strings: List<String>, baseUrl: String, dexFiles: Int): ApkFacts {
        val host = baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
        val themes = HashMap<String, Int>()
        var themeClass: String? = null
        var themeBest = 0
        val sameHost = LinkedHashSet<String>()
        val paths = LinkedHashSet<String>()
        val dates = LinkedHashSet<String>()
        val selectors = LinkedHashSet<String>()
        val chapterPaths = LinkedHashSet<String>()
        val attrs = LinkedHashSet<String>()
        val tokens = LinkedHashSet<String>()
        for (s in strings) {
            if (s.length < 2 || s.length > 300) continue
            val descriptor = THEME_DESCRIPTOR.find(s)
            if (descriptor != null) {
                val theme = descriptor.groupValues[1]
                val n = (themes[theme] ?: 0) + 1
                themes[theme] = n
                // The class seen most often names the theme (a library the theme uses shows up less)
                if (n > themeBest) {
                    themeBest = n
                    themeClass = s
                }
                continue
            }
            when {
                s.startsWith("http://") || s.startsWith("https://") -> {
                    val h = s.toHttpUrlOrNull()?.host?.removePrefix("www.")
                    if (host != null && h != null && (h == host || h.endsWith(".$host") || host.endsWith(".$h"))) sameHost.add(s)
                }
                s in Derive.COVER_ATTRS || s == "abs:src" || s == "abs:data-src" -> attrs.add(s.removePrefix("abs:"))
                TOKEN.matches(s) -> tokens.add(s)
                PATH.matches(s) && !s.endsWith(".js") && !s.endsWith(".css") && !s.contains("//") -> {
                    paths.add(s)
                    if (Regex("""chapter|episode|ajax/chapters""", RegexOption.IGNORE_CASE).containsMatchIn(s)) chapterPaths.add(s)
                }
                DATE_FORMAT.matches(s) -> dates.add(s)
                SELECTOR.matches(s) && (s.contains('.') || s.contains('#') || s.contains('[') || s.contains('>')) &&
                    !s.contains("/") && !s.contains("\\") && !s.endsWith(".kt") && !s.endsWith(".java") &&
                    !Regex("""^[a-z]+\.[a-z]+(\.[a-z]+)+$""").matches(s) -> selectors.add(s)
                Regex("""^\??(ajax/chapters/?|style=list|chapter-?|-chapter-)$""").matches(s) -> chapterPaths.add(s)
            }
        }
        val theme = themes.maxByOrNull { it.value }?.key
        return ApkFacts(
            theme = theme,
            themeClass = themeClass,
            sameHostUrls = sameHost.toList(),
            paths = paths.toList(),
            dateFormats = dates.toList(),
            selectors = selectors.toList(),
            chapterPaths = chapterPaths.toList(),
            attrs = attrs.toList(),
            tokens = tokens.toList(),
            dexFiles = dexFiles,
            strings = strings.size,
            error = null,
        )
    }

    private const val MAX_DEX_BYTES = 64 * 1024 * 1024
    private const val MAX_STRINGS = 600_000

    /**
     * A small DEX string-table reader: header → string_ids_size / string_ids_off → each string_data_off →
     * ULEB128 length + MUTF-8 bytes to the 0 byte. Every offset is checked against the buffer; anything odd
     * ends that string, not the read.
     */
    object DexStrings {
        fun read(dex: ByteArray, out: MutableList<String>, budget: Int) {
            if (budget <= 0 || dex.size < 0x70) return
            if (dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte() || dex[3] != 0x0A.toByte()) {
                throw IllegalStateException("not a dex file (bad magic)")
            }
            val size = u32(dex, 0x38)
            val off = u32(dex, 0x3C)
            if (size <= 0 || off <= 0 || off >= dex.size) return
            val count = minOf(size.toLong(), budget.toLong(), ((dex.size - off) / 4).toLong()).toInt()
            for (i in 0 until count) {
                val dataOff = u32(dex, off + 4 * i)
                if (dataOff <= 0 || dataOff >= dex.size) continue
                var p = dataOff
                // ULEB128: the string's length in UTF-16 units (ignored beyond the bounds check)
                var shift = 0
                var utf16 = 0
                while (p < dex.size && shift < 35) {
                    val b = dex[p].toInt() and 0xFF
                    utf16 = utf16 or ((b and 0x7F) shl shift)
                    p++
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (utf16 < 0 || utf16 > 4096) continue
                val text = mutf8(dex, p, minOf(dex.size, p + utf16 * 3 + 1))
                if (text != null) out.add(text)
            }
        }

        private fun u32(b: ByteArray, at: Int): Int {
            if (at < 0 || at + 4 > b.size) return -1
            val v = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
            return if (v > Int.MAX_VALUE) -1 else v.toInt()
        }

        /** Modified UTF-8 to a String: 1–3 byte forms, surrogates as two 3-byte units; stops at the 0 byte */
        private fun mutf8(b: ByteArray, from: Int, to: Int): String? {
            val sb = StringBuilder()
            var p = from
            while (p < to) {
                val c = b[p].toInt() and 0xFF
                when {
                    c == 0 -> return sb.toString()
                    c < 0x80 -> {
                        sb.append(c.toChar())
                        p += 1
                    }
                    c and 0xE0 == 0xC0 -> {
                        if (p + 1 >= to) return null
                        val d = b[p + 1].toInt() and 0xFF
                        sb.append((((c and 0x1F) shl 6) or (d and 0x3F)).toChar())
                        p += 2
                    }
                    c and 0xF0 == 0xE0 -> {
                        if (p + 2 >= to) return null
                        val d = b[p + 1].toInt() and 0xFF
                        val e = b[p + 2].toInt() and 0xFF
                        sb.append((((c and 0x0F) shl 12) or ((d and 0x3F) shl 6) or (e and 0x3F)).toChar())
                        p += 3
                    }
                    else -> return null
                }
                if (sb.length > 4096) return null
            }
            return sb.toString()
        }
    }
}
