package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import eu.kanade.tachiyomi.sumizuri.ReaderVideos
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * KMK: The play button over a video page in the reader (a Sumizuri page with a `videoUrl`). Tapping it plays the
 * video in the app's player; closing the player comes back to the same page.
 */
object ReaderVideoButton {

    private const val TAG = "reader_video_button"

    /** Adds the button to [frame] when [page] is a video, and removes a leftover one when it isn't */
    fun update(frame: FrameLayout, page: ReaderPage?) {
        val existing = frame.findViewWithTag<View>(TAG)
        if (page == null || !ReaderVideos.isVideo(page.url)) {
            existing?.let(frame::removeView)
            return
        }
        val size = 72.dpToPx
        val button = (existing as? ImageView) ?: ImageView(frame.context).apply {
            tag = TAG
            setImageResource(android.R.drawable.ic_media_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 0, 0, 0))
                setStroke(2.dpToPx, Color.argb(200, 255, 255, 255))
            }
            val padding = 18.dpToPx
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Play video"
            elevation = 6.dpToPx.toFloat()
            frame.addView(this, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        button.setOnClickListener { view ->
            view.isEnabled = false
            MainScope().launch {
                try {
                    ReaderVideos.play(view.context, page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    view.context.toast("Couldn't play this video: ${e.message}")
                } finally {
                    view.isEnabled = true
                }
            }
        }
        button.bringToFront()
    }
}
