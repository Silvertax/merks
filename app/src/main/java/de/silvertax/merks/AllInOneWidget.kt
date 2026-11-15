package de.silvertax.merks

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * Home screen widget "All in one": what comes next, and the buttons to speak and to photograph, on
 * one card. Two rows or taller, long buttons with their words sit below what comes next; in a single
 * row, two round buttons sit beside it. A tap anywhere else opens the list with everything.
 */
class AllInOneWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = Glance.refresh(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        Glance.refresh(ctx)

    override fun onDisabled(ctx: Context) = Glance.refresh(ctx)

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, AllInOneWidget::class.java))

        /**
         * How the widget is drawn. [tall]: long buttons below what comes next, otherwise one row with
         * round buttons. [head]: what comes next, or null when only the buttons fit. [words]: the long
         * buttons have room for their words.
         */
        data class Look(val tall: Boolean, val head: UpNextViews.Look?, val words: Boolean = false)

        private const val MIN = 40f

        /** From this width (dp) what comes next fits beside the round buttons, and the date tile above the long ones. */
        const val HEAD_MIN_W = 200f

        /** From this width the date tile fits in a single row too, and the long buttons have room for their words: four cells of most home screens. */
        const val WIDE_W = 310f

        // Heights (dp) from which each look fits without cutting a line off, measured like those of
        // the "Up next" widget; WidgetSizeTest checks them.

        /**
         * One row: the title, and day and time below it, beside the round buttons. Lower, only the
         * buttons fit; Android then also takes the smallest layout, the buttons alone.
         */
        internal fun rowLowMinH(fs: Float) = maxOf(60f, 38.5f * fs + 13f)

        /** One row: the date tile, or without it the day, the title in two lines and the time. */
        internal fun rowFullMinH(fs: Float) = 72f * fs + 23.5f

        /** What comes next, with "Up next" above it, over the long buttons. */
        internal fun tallMinH(fs: Float) = 72f * fs + 104f

        /** And one more entry. */
        internal fun tallList1MinH(fs: Float) = 95f * fs + 119f

        /** And two. */
        internal fun tallList2MinH(fs: Float) = 118.5f * fs + 118.5f

        /** The steps of height at which the look changes; with the three widths 15 layouts, one fewer than Android takes. */
        internal fun heights(fs: Float) = listOf(rowLowMinH(fs), rowFullMinH(fs), tallMinH(fs), tallList1MinH(fs), tallList2MinH(fs))

        /** [w] and [h] in dp; an unknown size (0) gets the look of the preview. */
        fun lookFor(w: Float, h: Float, fontScale: Float = 1f): Look {
            val fs = fontScale
            if (w <= 0f || h <= 0f) return Look(tall = true, head = UpNextViews.Look(tile = true, label = true), words = true)
            if (h >= tallMinH(fs)) {
                // Narrow, the buttons below leave no room for the entries after the next one.
                val tile = w >= HEAD_MIN_W
                val rows = when {
                    !tile -> 0
                    h >= tallList2MinH(fs) -> 2
                    h >= tallList1MinH(fs) -> 1
                    else -> 0
                }
                val head = UpNextViews.Look(tile = tile, label = tile, rows = rows, compact = !tile)
                return Look(tall = true, head = head, words = w >= WIDE_W)
            }
            if (w < HEAD_MIN_W || h < rowLowMinH(fs)) return Look(tall = false, head = null)
            val narrow = w < WIDE_W
            // In a single row the print is a little smaller, so the time beside the tile is not cut off.
            val head = when {
                h < rowFullMinH(fs) -> UpNextViews.Look(tile = false, titleLines = 1, low = true, shortDay = narrow, compact = true)
                narrow -> UpNextViews.Look(tile = false, compact = true)
                else -> UpNextViews.Look(tile = true, compact = true)
            }
            return Look(tall = false, head = head)
        }

        fun views(ctx: Context, next: List<Plan.Upcoming>, now: Long, look: Look): RemoteViews {
            val texts = Lang.context(ctx)
            val layout = if (look.tall) R.layout.widget_all else R.layout.widget_all_row
            return RemoteViews(ctx.packageName, layout).apply {
                setOnClickPendingIntent(android.R.id.background, UpNextViews.openApp(ctx))
                setOnClickPendingIntent(R.id.all_speak, ButtonWidget.open(ctx, Intent(ctx, RecordActivity::class.java)))
                setOnClickPendingIntent(R.id.all_photo, ButtonWidget.open(ctx, Intent(ctx, PhotoActivity::class.java)))
                setContentDescription(R.id.all_speak, texts.getString(R.string.speak))
                setContentDescription(R.id.all_photo, texts.getString(R.string.photo))
                if (look.tall) {
                    setTextViewText(R.id.all_speak_label, texts.getString(R.string.speak))
                    setTextViewText(R.id.all_photo_label, texts.getString(R.string.photo))
                    val words = if (look.words) View.VISIBLE else View.GONE
                    setViewVisibility(R.id.all_speak_label, words)
                    setViewVisibility(R.id.all_photo_label, words)
                }
                val head = look.head
                setViewVisibility(R.id.next_head, if (head == null) View.GONE else View.VISIBLE)
                if (head != null) UpNextViews.fill(this, ctx, next, now, head)
            }
        }

        fun renderAll(ctx: Context, next: List<Plan.Upcoming>, now: Long) {
            val manager = AppWidgetManager.getInstance(ctx)
            val fs = ctx.resources.configuration.fontScale
            for (id in ids(ctx)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // The home screen picks what fits the real size, in both orientations.
                    manager.updateAppWidget(
                        id,
                        UpNextViews.responsive(listOf(MIN, HEAD_MIN_W, WIDE_W), heights(fs)) { w, h -> views(ctx, next, now, lookFor(w, h, fs)) },
                    )
                } else {
                    val (w, h) = NextWidget.size(ctx, manager.getAppWidgetOptions(id))
                    manager.updateAppWidget(id, views(ctx, next, now, lookFor(w, h, fs)))
                }
            }
        }
    }
}
