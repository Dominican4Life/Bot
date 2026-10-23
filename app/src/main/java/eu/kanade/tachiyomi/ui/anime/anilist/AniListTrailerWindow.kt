package eu.kanade.tachiyomi.ui.anime.anilist

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** KMK: A YouTube or Dailymotion trailer for the floating window */
data class AniListTrailerVideo(val site: String, val id: String) {
    val embedUrl: String
        get() = when (site.lowercase()) {
            "dailymotion" -> "https://www.dailymotion.com/embed/video/$id?autoplay=1"
            else -> "https://www.youtube-nocookie.com/embed/$id?autoplay=1&rel=0&cc_lang_pref=ja&playsinline=1"
        }
}

/**
 * KMK: The trailer in a small window over the page, like Shiru and Hayase (an embedded YouTube player). Drag it
 * around, make it bigger, or close it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AniListTrailerWindow(video: AniListTrailerVideo, onClose: () -> Unit) {
    var big by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(12.dp),
        contentAlignment = if (big) Alignment.Center else Alignment.BottomEnd,
    ) {
        val width = if (big) maxWidth else minOf(maxWidth, 360.dp)
        Surface(
            modifier = Modifier
                .offset { if (big) IntOffset.Zero else IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                .width(width),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black,
            shadowElevation = 12.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Color(0xFF1E1E1E))
                        .pointerInput(big) {
                            if (!big) {
                                detectDragGestures { change, amount ->
                                    change.consume()
                                    drag += amount
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = { big = !big }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (big) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                val html = remember(video) {
                    """
                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
                    iframe{border:0;width:100%;height:100%}</style></head>
                    <body><iframe src="${video.embedUrl}" allow="autoplay; encrypted-media; picture-in-picture"
                    allowfullscreen></iframe></body></html>
                    """.trimIndent()
                }
                var webView by remember { mutableStateOf<WebView?>(null) }
                DisposableEffect(Unit) {
                    onDispose {
                        webView?.apply {
                            stopLoading()
                            loadUrl("about:blank")
                            destroy()
                        }
                    }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            // YouTube's embed wants to know which site it's on
                            loadDataWithBaseURL("https://anilist.co/", html, "text/html", "utf-8", null)
                            webView = this
                        }
                    },
                )
            }
        }
    }
}
