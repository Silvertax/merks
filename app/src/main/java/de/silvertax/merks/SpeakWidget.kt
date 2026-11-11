package de.silvertax.merks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/** Home screen widget: one big button that starts listening. Shrinks to an icon-only button down to 1x1. */
class SpeakWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, manager, id)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(ctx, manager, id)
    }

    private fun render(ctx: Context, manager: AppWidgetManager, id: Int) {
        val options = manager.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        // No room for the label below about two cells in either direction.
        val small = (width in 1 until LABEL_MIN_DP) || (height in 1 until LABEL_MIN_DP)

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, RecordActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val views = RemoteViews(ctx.packageName, if (small) R.layout.widget_speak_small else R.layout.widget_speak)
        views.setOnClickPendingIntent(R.id.widget_root, open)
        manager.updateAppWidget(id, views)
    }

    private companion object {
        const val LABEL_MIN_DP = 100
    }
}
