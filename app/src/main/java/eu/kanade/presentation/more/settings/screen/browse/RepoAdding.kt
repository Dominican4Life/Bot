package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import mihon.data.extension.service.ExtensionStoreUrls
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: What a repository is added as. Auto goes by its packages (both kinds when it has both). */
enum class RepoKind(val label: String) {
    AUTO("Auto"),
    MIHON("Mihon (manga)"),
    ANIYOMI("Aniyomi (anime)"),
}

/** KMK: A repository found at an address, before it's added */
@Immutable
data class FoundRepo(
    val store: ExtensionStore,
    val input: String,
    val mangaPackages: Int,
    val animePackages: Int,
    /** "Mihon" / "Aniyomi" when the same repository is already added */
    val alreadyAddedAs: String?,
)

/** KMK: Repository addresses in what was typed, pasted or read from a file */
object RepoInput {
    private val urlPattern = Regex("""https?://[^\s"'<>,;)\]]+""")
    private val v2Pattern = Regex(""""index_v2"\s*:\s*"([^"]+)"""")

    /**
     * Every address in [text]. A pasted repo.json gives its index_v2 address; a plain address without https is kept
     * as typed. Addresses of the same repository count once.
     */
    fun urls(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        v2Pattern.find(trimmed)?.let { return listOf(it.groupValues[1]) }
        val found = urlPattern.findAll(trimmed).map { it.value.trimEnd('.', '/') }.toList()
        if (found.isEmpty()) return listOf(trimmed)
        // A repo.json/index file pasted whole lists its apk/icon links too: those aren't repositories
        val repos = found.filterNot { it.contains("/apk/") || it.contains("/icon/") || it.endsWith(".apk") || it.endsWith(".png") }
        return repos.ifEmpty { found.take(1) }.distinctBy { ExtensionStoreUrls.key(it) }
    }
}

/**
 * KMK: Every repository added on this device, kept after it's removed: the add dialog offers the ones that aren't
 * added any more. (Like Kototoro, the app ships no list of its own.)
 */
object RepoMemory {
    private val pref by lazy { Injekt.get<PreferenceStore>().getStringSet("known_extension_repos", emptySet()) }

    data class Known(val name: String, val url: String, val kinds: String)

    fun remember(name: String, url: String, kinds: List<String>) {
        val key = ExtensionStoreUrls.key(url)
        val kept = pref.get().filterNot { line -> line.split('\t').getOrNull(1)?.let(ExtensionStoreUrls::key) == key }
        pref.set(kept.toSet() + "${name.replace('\t', ' ')}\t$url\t${kinds.joinToString(" + ")}")
    }

    fun all(): List<Known> = pref.get().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 2) null else Known(parts[0], parts[1], parts.getOrElse(2) { "" })
    }.sortedBy { it.name.lowercase() }

    fun forget(url: String) {
        val key = ExtensionStoreUrls.key(url)
        pref.set(pref.get().filterNot { line -> line.split('\t').getOrNull(1)?.let(ExtensionStoreUrls::key) == key }.toSet())
    }
}
