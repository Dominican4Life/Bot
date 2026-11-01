package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.reader.ChapterTransition
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.util.system.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderTransitionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) :
    AbstractComposeView(context, attrs) {

    private var data: Data? by mutableStateOf(null)

    /**
     * KMK (r103 fix pass): runs the storage check of [bind] (main thread; the check itself on IO). r103 fix: made
     * when needed and cancelled when the view leaves the window (one MainScope per view was never cancelled); a
     * check cut short that way runs again when the view comes back ([rebind])
     */
    private var scope: CoroutineScope? = null

    /** KMK (r103 fix pass): the storage check of the last [bind], cancelled by the next one */
    private var storageCheck: Job? = null

    /** KMK (r103 fix): the last [bind] while its storage check hasn't answered (null once it has) */
    private var rebind: (() -> Unit)? = null

    init {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val again = rebind
                    if (again != null && storageCheck == null) again()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    scope?.cancel()
                    scope = null
                    storageCheck = null
                }
            },
        )
    }

    fun bind(transition: ChapterTransition, downloadManager: DownloadManager, manga: Manga?) {
        storageCheck?.cancel()
        storageCheck = null
        rebind = null
        if (manga == null) {
            data = null
            return
        }
        val currDownloaded = transition.from.pageLoader?.isLocal == true
        val goingToChapter = transition.to?.chapter
        if (manga.isLocal() || goingToChapter == null) {
            data = Data(transition, currDownloaded, goingToChapterDownloaded = manga.isLocal())
            return
        }
        fun check(skipCache: Boolean): Boolean = downloadManager.isChapterDownloaded(
            chapterName = goingToChapter.name,
            chapterScanlator = goingToChapter.scanlator,
            chapterUrl = goingToChapter.url,
            mangaTitle = /* SY --> */ manga.ogTitle, /* SY <-- */
            sourceId = manga.source,
            skipCache = skipCache,
        )
        // KMK (r103 fix pass): this runs on the main thread. The download index (kept up to date by every download)
        // is trusted when it says "downloaded"; when it says no, the storage is checked on IO (as up to r102, then
        // on this thread) before "not downloaded" (and the offline warning) can show
        if (downloadManager.isIndexLoaded && check(skipCache = false)) {
            data = Data(transition, currDownloaded, goingToChapterDownloaded = true)
            return
        }
        // Nothing shown until the storage answered (a moment), so a wrong "not downloaded" never flashes
        data = null
        rebind = { bind(transition, downloadManager, manga) }
        val runIn = scope ?: MainScope().also { scope = it }
        storageCheck = runIn.launch {
            val onStorage = withContext(Dispatchers.IO) { runCatching { check(skipCache = true) }.getOrDefault(false) }
            if (onStorage && downloadManager.isIndexLoaded) {
                AppLog.add(
                    'D',
                    "reader",
                    "transition: ${goingToChapter.name} is downloaded but not in the download index (found on storage)",
                )
            }
            data = Data(transition, currDownloaded, goingToChapterDownloaded = onStorage)
            rebind = null
        }
    }

    @Composable
    override fun Content() {
        data?.let {
            // KMK -->
            val uiPreferences = Injekt.get<UiPreferences>()
            val themeCoverBased = uiPreferences.themeCoverBased().get()
            // KMK <--
            TachiyomiTheme(
                // KMK -->
                seedColor = seedColor?.let { Color(seedColor) }.takeIf { themeCoverBased },
                // KMK <--
            ) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodySmall,
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                    ChapterTransition(
                        transition = it.transition,
                        currChapterDownloaded = it.currChapterDownloaded,
                        goingToChapterDownloaded = it.goingToChapterDownloaded,
                    )
                }
            }
        }
    }

    private data class Data(
        val transition: ChapterTransition,
        val currChapterDownloaded: Boolean,
        val goingToChapterDownloaded: Boolean,
    )
}
