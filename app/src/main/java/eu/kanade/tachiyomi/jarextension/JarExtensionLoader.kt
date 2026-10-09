package eu.kanade.tachiyomi.jarextension

import android.content.Context
import dalvik.system.DexClassLoader
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.lang.reflect.Method

/**
 * KMK: Loads a JAR plugin with the shared parser ABI (models, context, parser interface) taken from the app, so
 * plugin objects can be used directly, while each plugin keeps its own site and helper classes (as in Kototoro).
 */
internal class JarPluginClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, null, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Each plugin's factory and source list are its own
        if (name in OWN_CLASSES) {
            return findLoadedClass(name) ?: findClass(name)
        }
        // The shared ABI always comes from the app
        if (SHARED_PREFIXES.any { name.startsWith(it) } || name in SHARED_CLASSES) {
            return super.loadClass(name, resolve)
        }
        // Site parsers and helpers: the plugin's copy first
        if (PLUGIN_FIRST_PREFIXES.any { name.startsWith(it) }) {
            try {
                return findLoadedClass(name) ?: findClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        return super.loadClass(name, resolve)
    }

    private companion object {
        val OWN_CLASSES = setOf(
            "org.koitharu.kotatsu.parsers.MangaParserFactoryKt",
            "org.koitharu.kotatsu.parsers.model.MangaParserSource",
            "tsuki.MangaParserFactoryKt",
            "tsuki.model.MangaParserSource",
        )
        val SHARED_PREFIXES = listOf(
            "org.koitharu.kotatsu.parsers.model.",
            "org.koitharu.kotatsu.parsers.config.",
            "org.koitharu.kotatsu.parsers.webview.",
            "org.koitharu.kotatsu.parsers.util.LinkResolver",
        )
        val SHARED_CLASSES = setOf(
            "org.koitharu.kotatsu.parsers.MangaLoaderContext",
            "org.koitharu.kotatsu.parsers.MangaParser",
        )
        val PLUGIN_FIRST_PREFIXES = listOf(
            "org.koitharu.kotatsu.parsers.site.",
            "org.koitharu.kotatsu.parsers.core.",
            "org.koitharu.kotatsu.parsers.util.",
            "org.koitharu.kotatsu.parsers.network.",
            "org.koitharu.kotatsu.parsers.exception.",
            "org.koitharu.kotatsu.parsers.MangaParserFactory",
            "org.koitharu.kotatsu.core.",
            "tsuki.site.",
        )
    }
}

/** One loaded JAR: its sources, and a way to get a parser for each */
class LoadedJar internal constructor(
    val file: File,
    val architecture: JarArchitecture,
    val sources: List<JarSourceInfo>,
    private val newParser: (JarSourceInfo) -> JParser,
) {
    private val parsers = HashMap<String, JParser>()

    @Synchronized
    fun parser(source: JarSourceInfo): JParser = parsers.getOrPut(source.name) { newParser(source) }
}

object JarExtensionLoader {

    /** Why the last load of each file failed, for the UI */
    val errors = HashMap<String, String>()

    fun load(context: Context, file: File): LoadedJar? {
        errors.remove(file.name)
        // Android 14+ refuses to load writable dex files
        file.setReadOnly()
        val loader = JarPluginClassLoader(file.absolutePath, context.codeCacheDir.absolutePath, context.classLoader)

        runCatching { loadKotatsu(loader, file) }
            .onFailure { logcat(LogPriority.DEBUG, it) { "Kotatsu probe failed for ${file.name}" } }
            .getOrNull()?.let { return it }

        runCatching { loadTsuki(loader, file) }
            .onFailure { logcat(LogPriority.DEBUG, it) { "Tsuki probe failed for ${file.name}" } }
            .getOrNull()?.let { return it }

        errors[file.name] = "Not a Kotatsu or Tsuki parser JAR (no parser factory found)"
        logcat(LogPriority.WARN) { "Unsupported JAR: ${file.name}" }
        return null
    }

    private fun findFactory(factoryClass: Class<*>, contextClass: Class<*>): Method? =
        factoryClass.declaredMethods.firstOrNull { method ->
            method.name.startsWith("newParser") &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == contextClass
        }

    private fun enumConstants(factory: Method?, loader: ClassLoader, fallback: String): List<Any> {
        val cls = factory?.parameterTypes?.firstOrNull()
            ?: runCatching { loader.loadClass(fallback) }.getOrNull()
            ?: return emptyList()
        return cls.enumConstants?.toList().orEmpty()
    }

    private fun loadKotatsu(loader: ClassLoader, file: File): LoadedJar? {
        val factoryClass = loader.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
        val factory = findFactory(factoryClass, org.koitharu.kotatsu.parsers.MangaLoaderContext::class.java)
            ?: return null
        val constants = enumConstants(factory, loader, "org.koitharu.kotatsu.parsers.model.MangaParserSource")
            .filterIsInstance<org.koitharu.kotatsu.parsers.model.MangaSource>()
        if (constants.isEmpty()) return null
        val context = KotatsuJarContext(factory, constants)
        return LoadedJar(
            file = file,
            architecture = JarArchitecture.KOTATSU,
            sources = constants.map { kotatsuSourceInfo(it, emptySet()) },
        ) { info ->
            KotatsuParserBridge(context.parser(info.raw as org.koitharu.kotatsu.parsers.model.MangaSource))
        }
    }

    private fun loadTsuki(loader: ClassLoader, file: File): LoadedJar? {
        val factoryClass = loader.loadClass("tsuki.MangaParserFactoryKt")
        val factory = findFactory(factoryClass, tsuki.MangaLoaderContext::class.java) ?: return null
        val constants = enumConstants(factory, loader, "tsuki.model.MangaParserSource")
            .filterIsInstance<tsuki.model.MangaSource>()
        if (constants.isEmpty()) return null
        val context = TsukiJarContext(factory, constants)
        return LoadedJar(
            file = file,
            architecture = JarArchitecture.TSUKI,
            sources = constants.map { tsukiSourceInfo(it, emptySet()) },
        ) { info ->
            TsukiParserBridge(context.parser(info.raw as tsuki.model.MangaSource))
        }
    }
}
