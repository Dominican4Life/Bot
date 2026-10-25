package eu.kanade.tachiyomi.ui.browse.extension.unified

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.animeextension.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** KMK: The result of testing a source (Kototoro's "test sources") */
@Immutable
sealed interface SourceTest {
    data object Running : SourceTest

    data class Working(val millis: Long, val items: Int) : SourceTest

    data class Failed(val reason: String) : SourceTest
}

/** KMK: Tries a source's first popular page, as opening it in Browse would */
object SourceTester {

    private const val TIMEOUT_MS = 25_000L

    suspend fun test(source: UnifiedSource): SourceTest {
        val start = System.currentTimeMillis()
        return try {
            val items = withTimeout(TIMEOUT_MS) {
                if (source.isAnime) {
                    val anime = Injekt.get<AnimeSourceManager>().get(source.id) as? AnimeCatalogueSource
                        ?: return@withTimeout null
                    anime.getPopularAnime(1).animes.size
                } else {
                    val manga = Injekt.get<SourceManager>().get(source.id) as? CatalogueSource
                        ?: return@withTimeout null
                    manga.getPopularManga(1).mangas.size
                }
            }
            when {
                items == null -> SourceTest.Failed(if (source.enabled) "Not loaded" else "Switched off")
                items == 0 -> SourceTest.Failed("Opened, but listed nothing")
                else -> SourceTest.Working(System.currentTimeMillis() - start, items)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            SourceTest.Failed("No answer in ${TIMEOUT_MS / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SourceTest.Failed(e.message?.lineSequence()?.firstOrNull()?.take(120) ?: e.javaClass.simpleName)
        }
    }
}
