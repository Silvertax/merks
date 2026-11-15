package de.silvertax.merks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

/**
 * A home screen widget that is one big button. Three shapes depending on the space: icon with label
 * below, icon with label beside it, icon only (down to 1x1).
 */
abstract class ButtonWidget : AppWidgetProvider() {
    protected abstract val label: Int
    protected abstract val icon: Int

    /** What a tap opens. */
    protected abstract fun target(ctx: Context): Intent

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, manager, id)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(ctx, manager, id)
    }

    private fun views(ctx: Context, layout: Int): RemoteViews {
        val open = PendingIntent.getActivity(
            ctx, 0,
            target(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = Lang.context(ctx).getString(label)
        return RemoteViews(ctx.packageName, layout).apply {
            setOnClickPendingIntent(R.id.widget_root, open)
            setContentDescription(R.id.widget_root, text)
            setImageViewResource(R.id.widget_icon, icon)
            if (layout != R.layout.widget_speak_small) setTextViewText(R.id.widget_label, text)
        }
    }

    private fun render(ctx: Context, manager: AppWidgetManager, id: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            // The launcher picks the layout that fits the real size, in both orientations.
            manager.updateAppWidget(
                id,
                RemoteViews(
                    mapOf(
                        SizeF(40f, 40f) to views(ctx, R.layout.widget_speak_small),
                        SizeF(WIDE_MIN_W, 40f) to views(ctx, R.layout.widget_speak_wide),
                        SizeF(TALL_MIN_W, TALL_MIN_H) to views(ctx, R.layout.widget_speak),
                    ),
                ),
            )
            return
        }
        // Older Android: the options hold portrait as min width x max height, landscape as max width x min height.
        val o = manager.getAppWidgetOptions(id)
        val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        manager.updateAppWidget(id, views(ctx, layoutFor(w.toFloat(), h.toFloat())))
    }

    companion object {
        const val TALL_MIN_W = 90f
        const val TALL_MIN_H = 90f
        const val WIDE_MIN_W = 150f

        /** Sizes in dp; 0 means the launcher did not report a size, then the default 2x2 look is used. */
        fun layoutFor(w: Float, h: Float): Int = when {
            w <= 0f || h <= 0f -> R.layout.widget_speak
            w >= TALL_MIN_W && h >= TALL_MIN_H -> R.layout.widget_speak
            w >= WIDE_MIN_W -> R.layout.widget_speak_wide
            else -> R.layout.widget_speak_small
        }

        /** Redraws all placed button widgets, e.g. after the language was changed. */
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            for (widget in listOf(SpeakWidget(), PhotoWidget())) {
                val ids = manager.getAppWidgetIds(ComponentName(ctx, widget.javaClass))
                if (ids.isNotEmpty()) widget.onUpdate(ctx, manager, ids)
            }
        }
    }
}

/** One tap starts listening. */
class SpeakWidget : ButtonWidget() {
    override val label = R.string.speak
    override val icon = R.drawable.ic_mic
    override fun target(ctx: Context) = Intent(ctx, RecordActivity::class.java)
}

/** One tap opens the camera, for a letter or an appointment card. */
class PhotoWidget : ButtonWidget() {
    override val label = R.string.photo
    override val icon = R.drawable.ic_camera
    override fun target(ctx: Context) = Intent(ctx, PhotoActivity::class.java)
}
