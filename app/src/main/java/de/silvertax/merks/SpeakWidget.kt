package de.silvertax.merks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/**
 * A home screen widget that is one big round button on a card. Four shapes depending on the space:
 * the button with its word below, the same small in a single tall cell, the button with the word
 * beside it, the button alone (down to 1x1).
 */
abstract class ButtonWidget : AppWidgetProvider() {
    protected abstract val label: Int

    /** The round button as one picture, so it grows and shrinks with the widget. */
    protected abstract val button: Int

    /** The shape with the word below; it is also the preview in the widget list. */
    protected abstract val tallLayout: Int

    /** What a tap opens. */
    protected abstract fun target(ctx: Context): Intent

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, manager, id)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(ctx, manager, id)
    }

    internal fun views(ctx: Context, shape: Shape): RemoteViews {
        val text = Lang.context(ctx).getString(label)
        val layout = when (shape) {
            Shape.TALL -> tallLayout
            Shape.CELL -> R.layout.widget_button_cell
            Shape.WIDE -> R.layout.widget_button_wide
            Shape.SMALL -> R.layout.widget_button_small
        }
        return RemoteViews(ctx.packageName, layout).apply {
            setOnClickPendingIntent(android.R.id.background, open(ctx, target(ctx)))
            setContentDescription(android.R.id.background, text)
            setImageViewResource(R.id.widget_icon, button)
            if (shape != Shape.SMALL) setTextViewText(R.id.widget_label, text)
        }
    }

    private fun render(ctx: Context, manager: AppWidgetManager, id: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            // The home screen picks the shape that fits the real size, in both orientations.
            manager.updateAppWidget(
                id,
                UpNextViews.responsive(listOf(MIN, CELL_MIN_W, TALL_MIN_W, WIDE_MIN_W), listOf(MIN, TALL_MIN_H)) { w, h -> views(ctx, shapeFor(w, h)) },
            )
            return
        }
        val (w, h) = NextWidget.size(ctx, manager.getAppWidgetOptions(id))
        manager.updateAppWidget(id, views(ctx, shapeFor(w, h)))
    }

    companion object {
        enum class Shape { SMALL, CELL, WIDE, TALL }

        private const val MIN = 40f

        /** From here a small word fits below the button: a single cell of many home screens is that large. */
        const val CELL_MIN_W = 72f
        const val TALL_MIN_H = 96f

        /** From here the word fits below the button in full size. */
        const val TALL_MIN_W = 100f

        /** From here the word fits beside the button. */
        const val WIDE_MIN_W = 130f

        /** Sizes in dp; 0 means the home screen did not tell, then the 2x2 shape is used. */
        fun shapeFor(w: Float, h: Float): Shape = when {
            w <= 0f || h <= 0f -> Shape.TALL
            w >= TALL_MIN_W && h >= TALL_MIN_H -> Shape.TALL
            w >= CELL_MIN_W && h >= TALL_MIN_H -> Shape.CELL
            w >= WIDE_MIN_W -> Shape.WIDE
            else -> Shape.SMALL
        }

        /** Opens a screen from a widget, the same way for every widget, so the same tap gives the same screen. */
        fun open(ctx: Context, target: Intent): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Redraws all placed widgets, e.g. after the language was changed. */
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            for (widget in listOf(SpeakWidget(), PhotoWidget())) {
                val ids = manager.getAppWidgetIds(ComponentName(ctx, widget.javaClass))
                if (ids.isNotEmpty()) widget.onUpdate(ctx, manager, ids)
            }
            Glance.refresh(ctx)
        }
    }
}

/** One tap starts listening. */
class SpeakWidget : ButtonWidget() {
    override val label = R.string.speak
    override val button = R.drawable.w_speak_button
    override val tallLayout = R.layout.widget_speak
    override fun target(ctx: Context) = Intent(ctx, RecordActivity::class.java)
}

/** One tap opens the camera, for a letter or an appointment card. */
class PhotoWidget : ButtonWidget() {
    override val label = R.string.photo
    override val button = R.drawable.w_photo_button
    override val tallLayout = R.layout.widget_photo
    override fun target(ctx: Context) = Intent(ctx, PhotoActivity::class.java)
}
