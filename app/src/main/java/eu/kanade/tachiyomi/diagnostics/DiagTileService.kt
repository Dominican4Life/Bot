package eu.kanade.tachiyomi.diagnostics

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.kanade.tachiyomi.ui.main.MainActivity

/**
 * KMK (r97): Quick Settings tile "Komikku mark" (design §3.5): a tap writes `=== MARK ===` so the owner can flag the
 * moment a bug shows without opening the app; a tap while the device is locked opens Diagnostics › Capture instead
 * (the mark still goes in). Declared in the manifest with `BIND_QUICK_SETTINGS_TILE`.
 */
internal class DiagTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Komikku mark"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (Diag.engine?.recording?.value != null) "recording" else "tap to mark"
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val engine = Diag.engine
        if (engine != null) engine.mark(null) else eu.kanade.tachiyomi.util.system.AppLog.mark(null)
        // The lock screen can't show a toast and the tile has no long-press callback: a locked tap opens Capture
        if (isLocked) openCapture()
    }

    @Suppress("DEPRECATION")
    private fun openCapture() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(DiagActions.OPEN)
            .putExtra(DiagActions.EXTRA_TAB, DiagTab.CAPTURE.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 7_100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
