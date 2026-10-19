package eu.kanade.tachiyomi.ui.player.cast

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.ui.player.CastManager
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: the [CastMiniController] shown over the home screen while a Chromecast session is playing,
 * so casting can be controlled (and the expanded controls opened) after leaving the player.
 */
@Composable
fun HomeCastMiniController(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() } ?: return
    val enableCastPref = remember { Injekt.get<PlayerPreferences>().enableCast() }
    val enableCastChanges = remember(enableCastPref) { enableCastPref.changes() }
    val enableCast by enableCastChanges.collectAsState(enableCastPref.get())
    val castAvailable = remember(context) { CastManager.isCastApiAvailable(context) }
    if (!enableCast || !castAvailable) return

    val castManager = remember(activity) { CastManager(activity, Injekt.get()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, castManager) {
        val observer = LifecycleEventObserver { _, event ->
            // Pick up a session started in the player (or by another app); reconnect() also
            // registers a media callback, so only when not already following one
            if (event == Lifecycle.Event.ON_RESUME && castManager.castSession?.isConnected != true) {
                castManager.reconnect()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            castManager.cleanup()
        }
    }

    val castState by castManager.castState.collectAsState()
    val currentMedia by castManager.currentMedia.collectAsState()
    AnimatedVisibility(
        visible = castState == CastManager.CastState.CONNECTED && currentMedia != null,
        modifier = modifier,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        CastMiniController(
            castManager = castManager,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
