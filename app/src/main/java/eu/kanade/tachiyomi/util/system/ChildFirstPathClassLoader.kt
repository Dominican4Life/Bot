package eu.kanade.tachiyomi.util.system

import dalvik.system.PathClassLoader
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Enumeration

/**
 * A parent-last class loader that will try in order:
 * - the system class loader
 * - the child class loader
 * - the parent class loader.
 */
class ChildFirstPathClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    private val systemClassLoader: ClassLoader? = getSystemClassLoader()

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        var c = findLoadedClass(name)

        // KMK: the pieces the app and an extension pass between them always come from the app. An extension that
        // packs its own Kotlin (Continuation, Unit, Function...) or its own copy of the source API otherwise got
        // those from its APK, and Android refused it: "LinkageError ... resolves differently in interface
        // AnimeSource: Parameter 1 type mismatch". Anything the app doesn't have still comes from the extension.
        if (c == null && name != null && SHARED_PREFIXES.any { name.startsWith(it) }) {
            c = try {
                parent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
        }

        if (c == null && systemClassLoader != null) {
            try {
                c = systemClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        // KMK: what the app and its extensions pass to each other (Kotlin itself, coroutines, the source API and the
        // libraries in its signatures) always comes from the app. An extension that packed its own copy of, say,
        // kotlin.coroutines.Continuation otherwise failed to load with a LinkageError: its getAnimeDetails took
        // its own Continuation, not the app's, so it no longer matched the app's source interface.
        if (c == null && name != null && SHARED_PREFIXES.any { name.startsWith(it) }) {
            c = try {
                parent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
        }

        if (c == null) {
            c = try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }

        if (resolve) {
            resolveClass(c)
        }

        return c
    }

    override fun getResource(name: String?): URL? {
        return systemClassLoader?.getResource(name)
            ?: findResource(name)
            ?: super.getResource(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        val systemUrls = systemClassLoader?.getResources(name)
        val localUrls = findResources(name)
        val parentUrls = parent?.getResources(name)
        val urls = buildList {
            while (systemUrls?.hasMoreElements() == true) {
                add(systemUrls.nextElement())
            }

            while (localUrls?.hasMoreElements() == true) {
                add(localUrls.nextElement())
            }

            while (parentUrls?.hasMoreElements() == true) {
                add(parentUrls.nextElement())
            }
        }

        return object : Enumeration<URL> {
            val iterator = urls.iterator()

            override fun hasMoreElements() = iterator.hasNext()
            override fun nextElement() = iterator.next()
        }
    }

    override fun getResourceAsStream(name: String?): InputStream? {
        return try {
            getResource(name)?.openStream()
        } catch (_: IOException) {
            return null
        }
    }

    private companion object {
        val SHARED_PREFIXES = listOf(
            "kotlin.",
            "kotlinx.coroutines.",
            "eu.kanade.tachiyomi.source.",
            "eu.kanade.tachiyomi.animesource.",
            "eu.kanade.tachiyomi.network.",
            "okhttp3.",
            "okio.",
            "rx.",
            "uy.kohesive.injekt.",
            "androidx.preference.",
        )
    }
}
