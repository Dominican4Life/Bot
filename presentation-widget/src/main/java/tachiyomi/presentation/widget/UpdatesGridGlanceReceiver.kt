package tachiyomi.presentation.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class UpdatesGridGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = UpdatesGridGlanceWidget()

    // KMK (r95): the app's Updates subscription runs only while a widget is placed
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetManager.placementChanged.tryEmit(Unit)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetManager.placementChanged.tryEmit(Unit)
    }
}
