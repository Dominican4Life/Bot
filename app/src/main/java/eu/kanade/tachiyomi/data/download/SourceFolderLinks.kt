package eu.kanade.tachiyomi.data.download

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jssource.JsAnimeSource
import eu.kanade.tachiyomi.jssource.JsSourceInfo
import eu.kanade.tachiyomi.jssource.JsSourceManager
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r99): which download folders belong to a source, **by source id**, so renaming a source never hides its
 * downloads. Up to r98 a folder was found only by the source's name ("Mitaku JS (ALL)"), so when a JS source's
 * `name` changed to "Mitaku" its downloads disappeared from the app while the files stayed on disk.
 *
 * Kept in `filesDir/download_source_folders.json` (not in backups: after a reinstall the links are made again from
 * the folders' names, [recoverAndSeed]):
 * `{"manga": {"<id>": ["Mitaku JS (ALL)", "Mitaku (ALL)"]}, "anime": {…},`
 * `"tried": {"manga": [ids], "anime": [ids]}}`.
 * The first linked folder that exists is where new downloads go; every linked folder is looked in. Folders are
 * never moved, merged, renamed or deleted here.
 */
object SourceFolderLinks {

    enum class Kind(val key: String) { MANGA("manga"), ANIME("anime") }

    private class State(
        val links: Map<Kind, LinkedHashMap<Long, MutableList<String>>>,
        val tried: Map<Kind, MutableSet<Long>>,
    )

    private val lock = Any()
    private var state: State? = null

    private val file: File get() = File(Injekt.get<Application>().filesDir, "download_source_folders.json")

    private fun state(): State {
        state?.let { return it }
        val links = Kind.entries.associateWith { LinkedHashMap<Long, MutableList<String>>() }
        val tried = Kind.entries.associateWith { mutableSetOf<Long>() }
        runCatching {
            val f = file
            if (f.exists()) {
                val root = JSONObject(f.readText())
                Kind.entries.forEach { kind ->
                    root.optJSONObject("tried")?.optJSONArray(kind.key)?.let { arr ->
                        (0 until arr.length()).forEach { i -> tried.getValue(kind) += arr.optLong(i) }
                    }
                    val obj = root.optJSONObject(kind.key) ?: return@forEach
                    obj.keys().forEach { k ->
                        val id = k.toLongOrNull() ?: return@forEach
                        val arr = obj.optJSONArray(k) ?: return@forEach
                        val names = (0 until arr.length()).mapNotNull { i ->
                            arr.optString(i).takeIf { it.isNotBlank() }
                        }
                        if (names.isNotEmpty()) links.getValue(kind)[id] = names.distinct().toMutableList()
                    }
                }
            }
        }.onFailure { AppLog.add('W', "download", "Couldn't read the source folder links: ${it.message}") }
        return State(links, tried).also { state = it }
    }

    private fun write(s: State) {
        runCatching {
            val root = JSONObject()
            val tried = JSONObject()
            Kind.entries.forEach { kind ->
                val obj = JSONObject()
                s.links.getValue(kind).forEach { (id, names) -> obj.put(id.toString(), JSONArray(names)) }
                root.put(kind.key, obj)
                tried.put(kind.key, JSONArray(s.tried.getValue(kind).toList()))
            }
            root.put("tried", tried)
            val f = file
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { AppLog.add('W', "download", "Couldn't save the source folder links: ${it.message}") }
    }

    /** The folder names linked to source [id], first = where new downloads go */
    fun folders(kind: Kind, id: Long): List<String> = synchronized(lock) {
        state().links.getValue(kind)[id]?.toList().orEmpty()
    }

    /** Folder name → the source ids linked to it */
    fun owners(kind: Kind): Map<String, List<Long>> = synchronized(lock) {
        val out = HashMap<String, MutableList<Long>>()
        state().links.getValue(kind).forEach { (id, names) ->
            names.forEach { out.getOrPut(it) { mutableListOf() } += id }
        }
        out
    }

    /**
     * Links [folder] to source [id] (added after the folders already linked). Returns true when it is new. [log] is
     * written as an `I download` line when the link is new.
     */
    fun link(kind: Kind, id: Long, folder: String?, log: String? = null): Boolean {
        if (folder.isNullOrBlank()) return false
        val added = synchronized(lock) {
            val s = state()
            val list = s.links.getValue(kind).getOrPut(id) { mutableListOf() }
            if (list.any { it == folder }) {
                false
            } else {
                list += folder
                write(s)
                true
            }
        }
        if (added && log != null) AppLog.add('I', "download", log)
        return added
    }

    private fun wasTried(kind: Kind, id: Long): Boolean = synchronized(lock) { id in state().tried.getValue(kind) }

    private fun markTried(kind: Kind, ids: Collection<Long>) {
        synchronized(lock) {
            val s = state()
            if (s.tried.getValue(kind).addAll(ids)) write(s)
        }
    }

    /** The linked folders of [id] that exist among [children] (one listing of the downloads folder), in link order */
    fun existing(kind: Kind, id: Long, children: List<UniFile>): List<UniFile> =
        folders(kind, id).mapNotNull { name ->
            children.firstOrNull { it.name == name } ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }.distinctBy { it.name }

    /** The linked folders of [id] that exist under [parent], in link order (one lookup per linked name) */
    fun existing(kind: Kind, id: Long, parent: UniFile?): List<UniFile> {
        parent ?: return emptyList()
        return folders(kind, id).mapNotNull { name -> parent.findFile(name)?.takeIf { it.isDirectory } }
            .distinctBy { it.name }
    }

    /**
     * KMK (r99): an upstream source rename ([DownloadManager.renameSource]) renamed the folder [oldName] to
     * [newName]: the links of [id] follow it. Nothing happens when [id] didn't have [oldName].
     */
    fun renameFolder(kind: Kind, id: Long, oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        synchronized(lock) {
            val s = state()
            val list = s.links.getValue(kind)[id] ?: return
            val i = list.indexOfFirst { it.equals(oldName, ignoreCase = true) }
            if (i < 0) return
            list[i] = newName
            val distinct = list.distinct()
            list.clear()
            list += distinct
            write(s)
        }
        AppLog.add('I', "download", "Source id $id: linked folder $oldName renamed to $newName")
    }

    /** A source that can claim folders by name: its id and today's name rules */
    class Owner(val id: Long, val match: SourceFolders.Match)

    /**
     * A source [recoverAndSeed] looks at. [match] = today's name rules, [jsKey] its Source Lab key (null for an APK
     * source; older names come from the Lab history), [sanitize] makes a name safe for a folder.
     */
    class Candidate(
        val id: Long,
        val displayName: String,
        val baseName: String,
        val match: SourceFolders.Match,
        val jsKey: String?,
        val sanitize: (String) -> String,
    )

    /**
     * KMK (r99, conservative): every source the app knows of [kind], also switched off, hidden or not in the library,
     * so a folder of one of them is never taken as unclaimed. Waits for the source managers to load
     * ([LOAD_TIMEOUT_MS]); null when they didn't (the caller then skips linking this time and marks nothing tried).
     *
     * Manga: the SourceManager's sources and stubs, every stub source in the database (uninstalled extensions), every
     * saved JS source. Anime: the anime extensions' sources, the AnimeSourceManager's catalogue, every saved JS
     * source.
     */
    suspend fun knownSources(kind: Kind): List<Owner>? = try {
        when (kind) {
            Kind.MANGA -> knownManga()
            Kind.ANIME -> knownAnime()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AppLog.add('W', "download", "Couldn't list the ${kind.key} sources: ${e.message}")
        null
    }

    private suspend fun knownManga(): List<Owner>? {
        val sourceManager = Injekt.get<SourceManager>()
        val extensionManager = Injekt.get<ExtensionManager>()
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            extensionManager.isInitialized.first { it }
            sourceManager.isInitialized.first { it }
        } ?: return notLoaded(Kind.MANGA)
        val provider = Injekt.get<DownloadProvider>()
        val stubs = Injekt.get<StubSourceRepository>().subscribeAll().first()
        val sources = (sourceManager.getAll() + sourceManager.getStubSources() + stubs)
            .filter { it.name.isNotBlank() }
        val js = Injekt.get<JsSourceManager>().savedFlow.value.filter { !it.anime }
        return sources.map { Owner(it.id, provider.sourceMatch(it)) } +
            js.map { info ->
                val tag = if (info.json) "JSON" else "JS"
                Owner(
                    info.id,
                    SourceFolders.match(
                        exact = provider.validFolderName("${info.name} ($tag)"),
                        baseName = provider.validFolderName(info.name),
                        lang = info.displayLang,
                        js = true,
                    ),
                )
            }
    }

    private suspend fun knownAnime(): List<Owner>? {
        val extensionManager = Injekt.get<AnimeExtensionManager>()
        val sourceManager = Injekt.get<AnimeSourceManager>()
        val jsSources = Injekt.get<JsSourceManager>()
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            extensionManager.isInitialized.first { it }
            // The catalogue is built from the extensions a moment later: wait until it has them all
            val want = extensionManager.installedNow()
                .flatMap { it.sources }
                .filterIsInstance<AnimeCatalogueSource>()
                .mapTo(HashSet()) { it.id } - jsSources.apkOffFlow.value
            sourceManager.catalogueSources.first { list -> list.mapTo(HashSet()) { it.id }.containsAll(want) }
        } ?: return notLoaded(Kind.ANIME)
        val sources = (extensionManager.installedNow().flatMap { it.sources } + sourceManager.getCatalogueSources())
            .filter { it.name.isNotBlank() }
        val js = jsSources.savedFlow.value.filter { it.anime }
        return sources.map { Owner(it.id, animeMatch(it.name, it.lang, it is JsAnimeSource)) } +
            js.map { Owner(it.id, animeMatch(it.name, it.displayLang, js = true)) }
    }

    private fun notLoaded(kind: Kind): List<Owner>? {
        AppLog.add(
            'D',
            "download",
            "The ${kind.key} sources didn't load in time: download folders not linked this time",
        )
        return null
    }

    /** The anime download manager's name rules: "<name> (<LANG>)" */
    fun animeMatch(name: String, lang: String, js: Boolean): SourceFolders.Match = SourceFolders.match(
        exact = DiskUtil.buildValidFilename("$name (${lang.uppercase()})"),
        baseName = DiskUtil.buildValidFilename(name),
        lang = lang,
        js = js,
    )

    /**
     * KMK (r99, made conservative after a tester's report): run on IO when the download folders are listed
     * ([DownloadCache] for manga, the anime download manager's start for anime), only with every source loaded
     * ([knownSources] gives [known]). A link is permanent and each source is tried once, so when in doubt nothing is
     * linked: a missed link only means the old behaviour (folders found by name).
     *
     * 1. **Recovery, once per source id** (ids are remembered in `tried` when this pass ran to the end): only a JS
     *    source (it has a Lab key) with no linked folder and **no folder under its current name** (any folder its name
     *    rules accept). It gets a folder named after its name ± " JS" or after a name its Lab history had, under one
     *    of its tags, only when **exactly one** such folder exists, **no known source** (other id) claims it by its
     *    exact name or name rules, and it isn't linked to another id. Logged
     *    `I download Source <name> (id N) uses folder <folder>`.
     * 2. **Seeding**: each candidate is linked to the one folder its current exact name names (else its best match,
     *    when that is unique), when no other known source claims that folder as well or better and it isn't linked
     *    to another id.
     *
     * Returns how many folders step 1 linked (the caller lists the folders again when it's more than 0).
     */
    fun recoverAndSeed(kind: Kind, dirs: List<UniFile>, candidates: List<Candidate>, known: List<Owner>): Int {
        val dirNames = dirs.filter { it.isDirectory && !it.name.isNullOrBlank() }.map { it.name!! }.distinct()
        if (dirNames.isEmpty() || candidates.isEmpty()) return 0
        val allOwners = known + candidates.map { Owner(it.id, it.match) }
        // Every source that claims each folder by name, with its rank (0 = its exact name)
        val claims = HashMap<String, MutableList<Pair<Long, Int>>>()
        dirNames.forEach { name ->
            allOwners.forEach { o ->
                o.match.rank(name)?.let { rank -> claims.getOrPut(name) { mutableListOf() } += o.id to rank }
            }
        }
        // Folder (any case) → linked ids; kept up to date as this pass links
        val linked = HashMap<String, MutableSet<Long>>()
        owners(kind).forEach { (name, ids) -> linked.getOrPut(name.lowercase()) { HashSet() } += ids }

        fun linkedToOther(name: String, id: Long) = linked[name.lowercase()].orEmpty().any { it != id }
        fun claimedByOther(name: String, id: Long) = claims[name].orEmpty().any { it.first != id }
        fun linkNow(c: Candidate, name: String, log: String?): Boolean {
            val added = link(kind, c.id, name, log)
            linked.getOrPut(name.lowercase()) { HashSet() } += c.id
            return added
        }

        // 1. Recovery (JS sources only)
        var recovered = 0
        val needing = candidates.filter {
            it.jsKey != null && folders(kind, it.id).isEmpty() && !wasTried(kind, it.id)
        }
        needing.forEach { c ->
            // A folder under its current name: seeding links that one, an older name is never looked for
            if (dirNames.any { c.match.rank(it) != null }) return@forEach
            val found = byOtherName(c, dirNames) ?: return@forEach
            when {
                claimedByOther(found, c.id) -> AppLog.add(
                    'D',
                    "download",
                    "Source ${c.displayName} (id ${c.id}): folder $found has an older name of it but another source " +
                        "claims it; not linked",
                )
                linkedToOther(found, c.id) -> AppLog.add(
                    'D',
                    "download",
                    "Source ${c.displayName} (id ${c.id}): folder $found is linked to another source; not linked",
                )
                else -> if (linkNow(c, found, "Source ${c.displayName} (id ${c.id}) uses folder $found")) recovered++
            }
        }
        // Only now: the pass ran to the end with every source loaded
        if (needing.isNotEmpty()) markTried(kind, needing.map { it.id })

        // 2. Seeding: the one folder today's rules point the source to
        var seeded = 0
        candidates.forEach { c ->
            val target = seedTarget(c, dirNames) ?: return@forEach
            val (name, rank) = target
            if (claims[name].orEmpty().any { (id, r) -> id != c.id && r <= rank }) return@forEach
            if (linkedToOther(name, c.id)) return@forEach
            if (linkNow(c, name, null)) seeded++
        }
        if (seeded > 0) {
            AppLog.add('I', "download", "Linked $seeded ${kind.key} download folders to their source ids")
        }
        return recovered
    }

    /** The folder named exactly after the source today (rank 0), else its unique best match; null when none or a tie */
    private fun seedTarget(c: Candidate, folders: List<String>): Pair<String, Int>? {
        (folders.firstOrNull { it == c.match.exact } ?: folders.firstOrNull { it.equals(c.match.exact, true) })
            ?.let { return it to 0 }
        val ranked = folders.mapNotNull { name -> c.match.rank(name)?.let { name to it } }
        val best = ranked.minOfOrNull { it.second } ?: return null
        return ranked.filter { it.second == best }.singleOrNull()
    }

    /**
     * The one folder named after the source's name ± " JS" or an older name from its Lab history (± " JS"), under one
     * of its tags. Null when there is none, or more than one (which one isn't known: nothing is guessed).
     */
    private fun byOtherName(c: Candidate, folders: List<String>): String? {
        val names = buildList {
            add(c.baseName)
            c.jsKey?.let { addAll(labNames(it).map(c.sanitize)) }
        }
            .flatMap { listOf(it, jsVariant(it)) }
            .filter { it.isNotBlank() && !it.equals(c.baseName, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        if (names.isEmpty()) return null
        val tag = c.match.tags.firstOrNull().orEmpty()
        val found = names.flatMap { alt ->
            val match = SourceFolders.Match("$alt ($tag)", alt, c.match.tags)
            folders.filter { match.rank(it) != null }
        }
            .distinctBy { it.lowercase() }
        if (found.size > 1) {
            AppLog.add(
                'D',
                "download",
                "Source ${c.displayName} (id ${c.id}): ${found.size} folders have older names of it " +
                    "(${found.joinToString()}); none linked",
            )
        }
        return found.singleOrNull()
    }

    private fun jsVariant(name: String): String =
        if (name.endsWith(" JS", ignoreCase = true)) name.dropLast(3).trimEnd() else "$name JS"

    private val nameLine = Regex("""(?m)^\s*["']?name["']?\s*:\s*(?:"([^"\n]{1,200})"|'([^'\n]{1,200})')""")

    /** The `name` of every version the Source Lab history keeps for [key] (its first `name:` line) */
    private fun labNames(key: String): Set<String> = runCatching {
        val history = eu.kanade.tachiyomi.jssource.lab.History
        history.index(key).versions.mapNotNull { v ->
            history.code(key, v.n)?.let { code ->
                nameLine.find(code)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.trim()
            }
        }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * KMK (r99): JS source [info] is being saved, maybe under a new name. Links its current download folder to its id
     * first, so a rename can't hide it, but only the folder named **exactly** after its current (old) name, and only
     * when no other known source claims that folder by name and it isn't linked to another id. Returns the folder
     * its downloads stay in (an already linked one, else the one linked now), or null. Run on IO.
     */
    suspend fun keepOnRename(info: JsSourceInfo): String? = try {
        keepOnRenameNow(info)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AppLog.add('W', "download", "Couldn't link the download folder of ${info.name}: ${e.message}")
        null
    }

    private suspend fun keepOnRenameNow(info: JsSourceInfo): String? {
        val kind = if (info.anime) Kind.ANIME else Kind.MANGA
        val id = info.id
        val storage = Injekt.get<StorageManager>()
        val root = when {
            info.anime -> storage.getAnimeDownloadsDirectory(false)
            else -> storage.getMangaDownloadsDirectory(false)
        } ?: return null
        existing(kind, id, root).firstOrNull()?.name?.let { return it }
        val exact = if (info.anime) {
            animeMatch(info.name, info.displayLang, js = true).exact
        } else {
            val ascii = Injekt.get<LibraryPreferences>().disallowNonAsciiFilenames().get()
            DiskUtil.buildValidFilename("${info.name} (${if (info.json) "JSON" else "JS"})", disallowNonAscii = ascii)
        }
        val children = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.isNullOrBlank() }
        val folder = (children.firstOrNull { it.name == exact } ?: children.firstOrNull { it.name.equals(exact, true) })
            ?.name
            ?: return null
        val known = knownSources(kind) ?: return null
        if (known.any { it.id != id && it.match.rank(folder) != null }) {
            AppLog.add('D', "download", "Folder $folder is claimed by another source too: not linked to ${info.name}")
            return null
        }
        if (owners(kind).any { (name, ids) -> name.equals(folder, true) && ids.any { it != id } }) return null
        link(kind, id, folder, "Source ${info.name} (id $id) uses folder $folder")
        return folder
    }

    private const val LOAD_TIMEOUT_MS = 30_000L
}
