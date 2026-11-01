package eu.kanade.tachiyomi.jssource.lab

import android.app.Application
import eu.kanade.tachiyomi.jssource.jsJson
import eu.kanade.tachiyomi.jssource.stripJsonComments
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * KMK (r96): the Source Lab's files you update by file, the way the player's script editor takes a .lua: the rules
 * file, the engine fingerprints and the templates. Each import is kept as `v<n>.<ext>` under
 * `filesDir/sourcelab/<kind>/` with a `current` pointer; the asset in `assets/sourcelab/` is the fallback when
 * nothing was imported (or after a roll back to version 0). Templates have one folder per file stem, so
 * `generic-list.js`, `generic-list.json` and `generic-list.explain.json` sit together, each with its own pointer
 * (`current.js`, `current.json`, `current.explain`); the pointer `current` of the first r96 builds (shared by a
 * stem's .js and .json) is still read when a file has no pointer of its own.
 *
 * Callers run on IO; the stores are synchronised so an import and a read can't tear a pointer.
 */
internal object LabFiles {

    private val stores = HashMap<LabFileKind, Store>()

    @Synchronized
    fun store(kind: LabFileKind): LabFileStore = stores.getOrPut(kind) { Store(kind, Injekt.get<Application>()) }

    /** The version a text declares (rules front matter, fingerprints `"version"`, template `@version`); 1 when none */
    fun versionOf(kind: LabFileKind, name: String, text: String): Int = when (kind) {
        LabFileKind.Rules -> FRONT_VERSION.find(text.take(4000))?.groupValues?.get(1)?.toIntOrNull() ?: 1
        LabFileKind.Fingerprints -> runCatching {
            ((jsJson.parseToJsonElement(text) as? JsonObject)?.get("version") as? JsonPrimitive)?.intOrNull
        }.getOrNull() ?: 1
        LabFileKind.Templates -> when {
            name.endsWith(".json", ignoreCase = true) -> JSON_VERSION.find(text.take(4000))?.groupValues?.get(1)?.toIntOrNull() ?: 1
            else -> JS_VERSION.find(text.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
    }

    private val FRONT_VERSION = Regex("""^version:\s*(\d+)\s*$""", RegexOption.MULTILINE)
    private val JSON_VERSION = Regex(""""__version"\s*:\s*(\d+)""")
    private val JS_VERSION = Regex("""^\s*//\s*@version\s+(\d+)""")

    private class Store(override val kind: LabFileKind, private val context: Application) : LabFileStore {

        private val root: File get() = LabPaths.kindDir(context.filesDir, kind)

        /** Where [name]'s versions live and what its pointer file is called */
        private fun place(name: String): Pair<File, String> {
            require(NAME_PATTERN.matches(name)) { "Not a valid file name: $name" }
            return if (kind == LabFileKind.Templates) {
                val stem = name.substringBefore('.')
                val ext = name.substringAfter('.')
                // `madara.explain.json` → current.explain; `generic-list.js` → current.js, `.json` → current.json
                File(root, stem) to "current." + ext.substringBefore('.')
            } else {
                root to "current"
            }
        }

        /** The shared `current` pointer of the first r96 builds, read only when the file has none of its own */
        private fun legacyPointerFile(name: String): File? =
            if (kind == LabFileKind.Templates && !name.substringAfter('.').contains('.')) File(place(name).first, "current") else null

        private fun ext(name: String): String = name.substringAfter('.')

        private fun versionFile(name: String, version: Int): File = File(place(name).first, "v$version.${ext(name)}")

        private fun metaFile(name: String, version: Int): File = File(place(name).first, "v$version.${ext(name)}.meta")

        private fun pointerFile(name: String): File = place(name).let { (dir, pointer) -> File(dir, pointer) }

        @Synchronized
        private fun pointedVersion(name: String): Int? {
            val (pointed, builtInThen) = pointedImport(name) ?: return null
            // KMK (r100): an app update whose built-in file is newer than the imported one wins (the owner had
            // imported rules v12 by hand; r100 ships v13 built in). The imported versions stay listed.
            // Fix pass: the pointer remembers the built-in version of the moment it was written (`12@13`), so a
            // deliberate roll back to an older import holds until an app update brings a newer built-in file; a
            // pointer without `@` (written before) counts as `@0`.
            val builtIn = assetVersion(name)
            if (builtIn != null && builtIn > builtInThen && builtIn > pointed) {
                if (loggedNewer.add(name)) {
                    AppLog.add('I', "lab", "$name: the built-in v$builtIn is newer than the imported v$pointed, using it")
                }
                return null
            }
            // An explain sidecar follows its code file: when the built-in code wins over an import, so does the
            // built-in sidecar (an older imported generic-list.explain.json has no `updates` and misses the defaults
            // the built-in v3 code needs)
            if (kind == LabFileKind.Templates && name.endsWith(EXPLAIN_SUFFIX)) {
                val stem = name.removeSuffix(EXPLAIN_SUFFIX)
                val codeOverridden = listOf("$stem.js", "$stem.json").any { code ->
                    NAME_PATTERN.matches(code) && pointedImport(code) != null && pointedVersion(code) == null
                }
                if (codeOverridden) {
                    if (loggedNewer.add(name)) {
                        AppLog.add('I', "lab", "$name: follows the built-in $stem code (newer than the import), using the built-in one")
                    }
                    return null
                }
            }
            return pointed
        }

        /** The pointer text for [version]: the version, then `@` and the built-in version known now */
        private fun pointerText(name: String, version: Int): String = "$version@${assetVersion(name) ?: 0}"

        private val assetVersions = HashMap<String, Int?>()
        private val loggedNewer = HashSet<String>()

        /** The built-in file's version (assets don't change while the app runs, so read once) */
        private fun assetVersion(name: String): Int? = assetVersions.getOrPut(name) {
            assetText(name)?.let { text -> runCatching { LabFiles.versionOf(kind, name, text) }.getOrNull() }
        }

        /** The imported version the pointer names and the built-in version recorded with it (0 when none) */
        private fun pointedImport(name: String): Pair<Int, Int>? {
            val own = runCatching { parsePointer(pointerFile(name).readText()) }.getOrNull()
            // "0" written by a roll back to the asset: don't fall back to the legacy pointer
            if (own != null) return own.takeIf { it.first > 0 && versionFile(name, it.first).exists() }
            val legacy = legacyPointerFile(name) ?: return null
            return runCatching { parsePointer(legacy.readText()) }.getOrNull()
                ?.takeIf { it.first > 0 && versionFile(name, it.first).exists() }
        }

        /** `12@13` → (12, 13); `12` → (12, 0); anything else → null */
        private fun parsePointer(text: String): Pair<Int, Int>? {
            val trimmed = text.trim()
            val version = trimmed.substringBefore('@').trim().toIntOrNull() ?: return null
            val builtInThen = trimmed.substringAfter('@', "0").trim().toIntOrNull() ?: 0
            return version to builtInThen
        }

        private fun assetText(name: String): String? {
            val path = kind.assetPath ?: return null
            val full = if (kind == LabFileKind.Templates) "$path/$name" else path
            return runCatching { context.assets.open(full).bufferedReader().use { it.readText() } }.getOrNull()
        }

        private fun assetNames(): List<String> = when (kind) {
            LabFileKind.Rules -> listOf("rules.md")
            LabFileKind.Fingerprints -> listOf("fingerprints.json")
            LabFileKind.Templates -> runCatching { context.assets.list("sourcelab/templates")?.toList() }
                .getOrNull().orEmpty().filter { NAME_PATTERN.matches(it) }
        }

        private fun importedNames(): List<String> = when (kind) {
            LabFileKind.Templates -> root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { dir ->
                dir.listFiles().orEmpty()
                    .filter { it.isFile && VERSION_FILE.matches(it.name) && !it.name.endsWith(".meta") }
                    .map { dir.name + "." + it.name.substringAfter('.') }
            }
            else -> {
                val name = assetNames().first()
                if (root.listFiles().orEmpty().any { VERSION_FILE.matches(it.name) && it.name.endsWith("." + ext(name)) }) {
                    listOf(name)
                } else {
                    emptyList()
                }
            }
        }

        @Synchronized
        override fun names(): List<String> = (assetNames() + importedNames()).distinct().sorted()

        @Synchronized
        override fun current(name: String): String? {
            val version = pointedVersion(name)
            if (version != null) {
                runCatching { versionFile(name, version).readText() }.getOrNull()?.let { return it }
            }
            return assetText(name)
        }

        @Synchronized
        override fun currentVersion(name: String): Int? {
            pointedVersion(name)?.let { return it }
            val asset = assetText(name) ?: return null
            return LabFiles.versionOf(kind, name, asset)
        }

        @Synchronized
        override fun versions(name: String): List<LabFileVersion> {
            val (dir, _) = place(name)
            val suffix = "." + ext(name)
            return dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("v") && it.name.endsWith(suffix) }
                .mapNotNull { file ->
                    val n = file.name.removePrefix("v").removeSuffix(suffix).toIntOrNull() ?: return@mapNotNull null
                    val meta = runCatching { metaFile(name, n).readLines() }.getOrDefault(emptyList())
                    LabFileVersion(
                        name = name,
                        version = n,
                        importedAt = meta.getOrNull(0)?.trim()?.toLongOrNull() ?: file.lastModified(),
                        fileName = meta.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
                        file = file,
                    )
                }
                .sortedByDescending { it.version }
        }

        @Synchronized
        override fun import(name: String, text: String, fileName: String?): Int {
            validate(name, text)
            val version = LabFiles.versionOf(kind, name, text)
            val current = currentVersion(name)
            if (current != null && version <= current) {
                throw IllegalArgumentException(
                    "line 1: this file is version $version, but version $current is already current; " +
                        "raise `version` in the file to import it",
                )
            }
            val file = versionFile(name, version)
            file.parentFile?.mkdirs()
            if (file.exists()) throw IllegalArgumentException("line 1: version $version was imported before")
            file.writeText(text)
            metaFile(name, version).writeText("${System.currentTimeMillis()}\n${fileName.orEmpty()}")
            pointerFile(name).writeText(pointerText(name, version))
            AppLog.add('I', "lab", "Imported ${kind.name.lowercase()} $name v$version${fileName?.let { " from $it" }.orEmpty()}")
            return version
        }

        /** Throws IllegalArgumentException with a line number when the text can't be used */
        private fun validate(name: String, text: String) {
            when (kind) {
                LabFileKind.Rules -> RulesParser.parse(text, null, null)
                LabFileKind.Fingerprints -> jsonCheck(text)
                LabFileKind.Templates -> if (name.endsWith(".json", ignoreCase = true)) jsonCheck(stripJsonComments(text))
            }
        }

        private fun jsonCheck(text: String) {
            try {
                jsJson.parseToJsonElement(text)
            } catch (e: Exception) {
                val line = Regex("""line (\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)
                    ?: Regex("""offset (\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { offset -> text.take(offset).count { it == '\n' } + 1 }?.toString()
                    ?: "1"
                throw IllegalArgumentException("line $line: not valid JSON (${e.message?.lineSequence()?.firstOrNull()})")
            }
        }

        @Synchronized
        override fun rollBack(name: String, version: Int): Boolean {
            val pointer = pointerFile(name)
            if (version <= 0) {
                if (assetText(name) == null) return false
                if (legacyPointerFile(name)?.exists() == true) {
                    // the shared pointer may still serve the stem's other file: mask it for this one instead
                    pointer.parentFile?.mkdirs()
                    pointer.writeText("0")
                } else {
                    pointer.delete()
                }
            } else {
                if (!versionFile(name, version).exists()) return false
                pointer.parentFile?.mkdirs()
                pointer.writeText(pointerText(name, version))
            }
            AppLog.add('I', "lab", "$name rolled back to ${if (version <= 0) "the built-in version" else "v$version"}")
            return true
        }

        @Synchronized
        override fun export(name: String): File {
            val text = current(name) ?: throw IllegalStateException("There is no $name to export")
            val dir = File(context.cacheDir, "sourcelab-export").apply { mkdirs() }
            return File(dir, name).apply { writeText(text) }
        }
    }

    private const val EXPLAIN_SUFFIX = ".explain.json"

    /** `rules.md`, `madara.js`, `madara.explain.json`: a stem, then one or two extensions */
    private val NAME_PATTERN = Regex("""[A-Za-z0-9_-]{1,64}(\.[A-Za-z0-9]{1,16}){1,2}""")
    private val VERSION_FILE = Regex("""v\d+\..+""")
}
