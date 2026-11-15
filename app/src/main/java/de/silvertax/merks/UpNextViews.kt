package de.silvertax.merks

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import de.silvertax.merks.parse.Kind

/**
 * The part of a widget that shows what comes next (layouts widget_next_head and widget_next_more),
 * shared by the "Up next" and the all-in-one widget.
 */
object UpNextViews {
    /**
     * How the part is drawn. [tile]: the date tile in front, as in the app's list; it needs width.
     * [label]: "Up next" above the title. [titleLines]: 1 or 2. [rows]: how many of the following
     * entries are listed below. [low]: too little height for three lines, so the day and the time
     * share one line, written short if [shortDay]; [oneLine]: even the title shares it. [compact]:
     * narrow, so slightly smaller print, and the following entries with their day above the title
     * instead of beside it.
     */
    data class Look(
        val tile: Boolean,
        val label: Boolean = false,
        val titleLines: Int = 2,
        val rows: Int = 0,
        val low: Boolean = false,
        val oneLine: Boolean = false,
        val shortDay: Boolean = false,
        val compact: Boolean = false,
    )

    /** A following entry: in one line with day, title and time, or narrow with the day above the title. */
    private class Row(val row: Int, val day: Int, val title: Int, val time: Int, val stack: Int, val stackDay: Int, val stackTitle: Int)

    private val rowViews = listOf(
        Row(R.id.next_row2, R.id.next_row2_day, R.id.next_line2, R.id.next_row2_time, R.id.next_stack2, R.id.next_stack2_day, R.id.next_stack2_title),
        Row(R.id.next_row3, R.id.next_row3_day, R.id.next_line3, R.id.next_row3_time, R.id.next_stack3, R.id.next_stack3_day, R.id.next_stack3_title),
    )

    fun fill(rv: RemoteViews, ctx: Context, next: List<Plan.Upcoming>, now: Long, look: Look) {
        val texts = Lang.context(ctx)
        val first = next.firstOrNull()

        rv.setViewVisibility(R.id.next_badge, visible(look.tile))
        if (look.tile) {
            val dated = first != null
            for (id in listOf(R.id.next_badge_weekday, R.id.next_badge_day, R.id.next_badge_month)) {
                rv.setViewVisibility(id, visible(dated))
            }
            rv.setViewVisibility(R.id.next_badge_icon, visible(!dated))
            // Amber for reminders, teal for appointments, as in the list (see drawable/w_badge.xml).
            rv.setBoolean(R.id.next_badge, "setEnabled", first?.entry?.kind != Kind.REMINDER)
            if (first != null) {
                rv.setTextViewText(R.id.next_badge_weekday, Format.tileWeekday(ctx, first.start))
                rv.setTextViewText(R.id.next_badge_day, Format.tileDay(first.start))
                rv.setTextViewText(R.id.next_badge_month, Format.tileMonth(ctx, first.start))
            }
        }

        // Without the tile the day goes on top, where it is looked for first; when even that is too
        // high, it shares the line with the time.
        val dayOnTop = first != null && !look.tile && !look.low && !look.oneLine
        rv.setViewVisibility(R.id.next_day, visible(dayOnTop))
        if (dayOnTop) rv.setTextViewText(R.id.next_day, Format.compactDay(ctx, first!!.entry, first.start, now))
        // With nothing planned and no tile, the heading says what this is.
        val label = !dayOnTop && !look.low && !look.oneLine && (look.label || (first == null && !look.tile))
        rv.setViewVisibility(R.id.next_label, visible(label))
        rv.setTextViewText(R.id.next_label, texts.getString(R.string.next_up_label))

        val whenText = when {
            first == null -> null
            look.tile -> Format.tileWhen(ctx, first.entry, first.start, now)
            look.low || look.oneLine -> Format.compactWhen(ctx, first.entry, first.start, now, short = look.shortDay && !first.entry.allDay)
            else -> Format.compactTime(ctx, first.entry, first.start)
        }
        val title = first?.entry?.title ?: texts.getString(R.string.next_up_none)
        // In one line the time comes first, so a long title cannot push it out.
        rv.setTextViewText(R.id.next_title, if (look.oneLine && whenText != null) "$whenText · $title" else title)
        rv.setInt(R.id.next_title, "setMaxLines", if (look.oneLine) 1 else look.titleLines)
        rv.setTextViewText(R.id.next_when, whenText.orEmpty())
        rv.setViewVisibility(R.id.next_when, visible(whenText != null && !look.oneLine))

        val rest = next.drop(1).take(look.rows)
        rv.setViewVisibility(R.id.next_more, visible(rest.isNotEmpty()))
        // The day column as wide as the widest day shown ("Tomorrow"), at least as wide as the date
        // tile above, so the titles of the following entries line up.
        val days = rest.map { Format.rowDay(ctx, it.start, now) }
        val dayColumn = maxOf(
            (54 * ctx.resources.displayMetrics.density).toInt(),
            days.maxOfOrNull { kotlin.math.ceil(rowDayPaint(ctx).measureText(it)).toInt() + 1 } ?: 0,
        )
        for ((i, row) in rowViews.withIndex()) {
            val u = rest.getOrNull(i)
            rv.setViewVisibility(row.row, visible(u != null && !look.compact))
            rv.setViewVisibility(row.stack, visible(u != null && look.compact))
            if (u == null) continue
            val day = days[i]
            if (look.compact) {
                rv.setTextViewText(row.stackDay, listOfNotNull(day, Format.rowTime(ctx, u.entry, u.start)).joinToString(", "))
                rv.setTextViewText(row.stackTitle, u.entry.title)
                continue
            }
            rv.setTextViewText(row.day, day)
            rv.setInt(row.day, "setMinWidth", dayColumn)
            rv.setTextViewText(row.title, u.entry.title)
            val time = Format.rowTime(ctx, u.entry, u.start)
            rv.setTextViewText(row.time, time.orEmpty())
            rv.setViewVisibility(row.time, visible(time != null))
        }

        val sp = TypedValue.COMPLEX_UNIT_SP
        rv.setTextViewTextSize(R.id.next_title, sp, if (look.oneLine) 14f else if (look.low) 15f else if (look.compact) 16f else 17f)
        rv.setTextViewTextSize(R.id.next_when, sp, if (look.low || look.compact) 13f else 14f)
    }

    private fun visible(yes: Boolean) = if (yes) View.VISIBLE else View.GONE

    /** Measures the day in front of a following entry as the home screen will draw it (layout widget_next_more). */
    private fun rowDayPaint(ctx: Context) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, ctx.resources.displayMetrics)
    }

    /** A tap on the widget opens the list with everything. */
    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Layouts for Android 12 and newer, one for each cell of the grid that [widths] and [heights]
     * span (in dp, each list starting with the smallest size). The home screen takes the one whose
     * size fits and comes closest, which on a full grid is the one at the largest width and the
     * largest height that still fit: so [make] decides by the same steps.
     */
    @RequiresApi(31)
    fun responsive(widths: List<Float>, heights: List<Float>, make: (Float, Float) -> RemoteViews): RemoteViews {
        val sizes = widths.flatMap { w -> heights.map { h -> SizeF(w, h) } }
        require(sizes.size <= 16) { "Android takes at most 16 layouts" }
        return RemoteViews(sizes.associateWith { make(it.width, it.height) })
    }
}
