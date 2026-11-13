package de.silvertax.merks

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.ZoneId

/**
 * What comes next, at a glance: the "Up next" widget and, if switched on, a quiet notification
 * that also shows on the lock screen. Both are redrawn whenever entries change and whenever the
 * next thing is over, a new one starts or the day changes.
 */
object Glance {
    private const val CHANNEL = "next_up"
    const val NOTIFICATION_ID = 1_999_999
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun nextUp(ctx: Context, limit: Int = 3): List<Plan.Upcoming> =
        Plan.nextUp(Db(ctx).all(), System.currentTimeMillis(), ZoneId.systemDefault(), limit)

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val widgets = NextWidget.ids(app)
        val notification = Prefs.nextUpNotification(app)
        val entries = Db(app).all()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val next = Plan.nextUp(entries, now, zone, 3)
        if (widgets.isNotEmpty()) NextWidget.renderAll(app, next, now)
        if (notification) show(app, next, now) else app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        // Only keep a wake-up when something shows what comes next.
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(app, 7, Intent(app, GlanceReceiver::class.java), FLAGS)
        if (widgets.isEmpty() && !notification) {
            am.cancel(pi)
            return
        }
        val at = Plan.nextChange(entries, now, zone)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        }
    }

    private fun show(ctx: Context, next: List<Plan.Upcoming>, now: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val first = next.firstOrNull()
        if (first == null) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val texts = Lang.context(ctx)
        nm.createNotificationChannel(
            // Not "low": many phones hide silent notifications on the lock screen, the very place
            // this is meant for. Default importance, but without sound or vibration.
            NotificationChannel(CHANNEL, texts.getString(R.string.channel_next_up), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val text = Format.compactWhen(ctx, first.entry, first.start, now)
        val more = next.drop(1).joinToString("\n") { Format.compactWhen(ctx, it.entry, it.start, now) + " · " + it.entry.title }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_merks)
            .setColor(ctx.getColor(R.color.brand_teal))
            .setSubText(texts.getString(R.string.next_up_label))
            .setContentTitle(first.entry.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(if (more.isEmpty()) text else "$text\n\n$more"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}

/** Redraws after the time was changed, after the planned wake-up, and when the language changes. */
class GlanceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // The planned wake-up comes without an action; everything else must be one of these.
        if (intent.action == null || intent.action in SYSTEM) Glance.refresh(ctx)
    }

    private companion object {
        val SYSTEM = setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)
    }
}

/**
 * Home screen widget "Up next": the next appointment or reminder; a taller widget also lists the two after it.
 * Tapping it opens the app, so at two cells wide it doubles as a big button to the list, next to "Speak".
 */
class NextWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = Glance.refresh(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        Glance.refresh(ctx)

    override fun onDisabled(ctx: Context) = Glance.refresh(ctx)

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, NextWidget::class.java))

        /** One line, two-line block, or block plus the two after it. */
        enum class Shape { LINE, BLOCK, LIST }

        /**
         * How a widget of some size is drawn. A [narrow] one, two cells wide like the "Speak" button, has no
         * room for "Fr., 14. Nov., 10:00 Uhr" in one line: the day goes on top and the time below the title.
         * Where even that is too high ([low]), the day is written short instead.
         */
        data class Look(val shape: Shape, val narrow: Boolean = false, val low: Boolean = false)

        const val WIDE_MIN_W = 200f
        private const val NARROW_W = 100f
        private const val LOW_H = 40f

        // Heights in dp from which each look fits without cutting a line off. Larger print needs more room.
        private fun stackMinH(fontScale: Float) = 58f * fontScale + 12f
        private fun blockMinH(fontScale: Float, narrow: Boolean) = (if (narrow) 80f else 82f) * fontScale + 20f
        private fun listMinH(fontScale: Float, narrow: Boolean) = (if (narrow) 130f else 132f) * fontScale + 20f

        /** [w] and [h] in dp; an unknown size (0) counts as wide and as a block. */
        fun lookFor(w: Float, h: Float, fontScale: Float = 1f): Look {
            val narrow = w > 0f && w < WIDE_MIN_W
            return when {
                h <= 0f -> Look(Shape.BLOCK, narrow)
                narrow && h < stackMinH(fontScale) -> Look(Shape.LINE, narrow, low = true)
                h < blockMinH(fontScale, narrow) -> Look(Shape.LINE, narrow)
                h < listMinH(fontScale, narrow) -> Look(Shape.BLOCK, narrow)
                else -> Look(Shape.LIST, narrow)
            }
        }

        fun views(ctx: Context, next: List<Plan.Upcoming>, now: Long, shape: Shape): RemoteViews =
            views(ctx, next, now, Look(shape))

        fun views(ctx: Context, next: List<Plan.Upcoming>, now: Long, look: Look): RemoteViews {
            val texts = Lang.context(ctx)
            val shape = look.shape
            val narrow = look.narrow
            // Day on top, time below the title.
            val stacked = narrow && !look.low
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(ctx.packageName, R.layout.widget_next).apply {
                setOnClickPendingIntent(R.id.next_root, open)
                val first = next.firstOrNull()
                val label = if (stacked && first != null) Format.compactDay(ctx, first.entry, first.start, now) else texts.getString(R.string.next_up_label)
                setTextViewText(R.id.next_label, label)
                // As the day it is what is looked for first, so it stands out more than the heading does.
                setTextColor(R.id.next_label, if (stacked && first != null) 0xFFE6F7F4.toInt() else 0xFFCCEFE9.toInt())
                setViewVisibility(R.id.next_label, if (stacked || (shape != Shape.LINE && !narrow)) View.VISIBLE else View.GONE)
                val whenText = when {
                    first == null -> null
                    stacked -> Format.compactTime(ctx, first.entry, first.start)
                    else -> Format.compactWhen(ctx, first.entry, first.start, now, short = narrow && !first.entry.allDay)
                }
                setTextViewText(R.id.next_title, first?.entry?.title ?: texts.getString(R.string.next_up_none))
                setTextViewText(R.id.next_when, whenText.orEmpty())
                setViewVisibility(R.id.next_when, if (whenText == null) View.GONE else View.VISIBLE)
                // In one row, a title without a time below it may use that line too.
                setInt(R.id.next_title, "setMaxLines", if (shape == Shape.LINE && !(stacked && whenText == null)) 1 else 2)
                val rest = if (shape == Shape.LIST) next.drop(1) else emptyList()
                setViewVisibility(R.id.next_more, if (rest.isEmpty()) View.GONE else View.VISIBLE)
                for ((i, row) in listOf(R.id.next_line2, R.id.next_line3).withIndex()) {
                    val u = rest.getOrNull(i)
                    setViewVisibility(row, if (u == null) View.GONE else View.VISIBLE)
                    if (u == null) continue
                    val day = if (narrow) Format.compactDay(ctx, u.entry, u.start, now, short = true) else Format.compactWhen(ctx, u.entry, u.start, now)
                    setTextViewText(row, day + " · " + u.entry.title)
                }
                // A narrow widget gets slightly smaller print and margins, so three lines fit in one row of the home screen.
                val sp = android.util.TypedValue.COMPLEX_UNIT_SP
                setTextViewTextSize(R.id.next_label, sp, if (narrow) 13f else 12f)
                setTextViewTextSize(R.id.next_title, sp, if (narrow) 16f else 17f)
                setTextViewTextSize(R.id.next_when, sp, if (look.low) 12f else if (narrow) 13f else 14f)
                val dp = ctx.resources.displayMetrics.density
                val side = ((if (narrow) 12 else 16) * dp).toInt()
                val top = ((if (narrow && shape == Shape.LINE) 6 else 10) * dp).toInt()
                setViewPadding(R.id.next_root, side, top, side, top)
            }
        }

        fun renderAll(ctx: Context, next: List<Plan.Upcoming>, now: Long) {
            val manager = AppWidgetManager.getInstance(ctx)
            val fontScale = ctx.resources.configuration.fontScale
            for (id in ids(ctx)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // The launcher picks what fits the real size.
                    val sizes = listOf(false, true).flatMap { narrow ->
                        val heights = listOfNotNull(LOW_H, stackMinH(fontScale).takeIf { narrow }, blockMinH(fontScale, narrow), listMinH(fontScale, narrow))
                        heights.map { SizeF(if (narrow) NARROW_W else WIDE_MIN_W, it) }
                    }
                    manager.updateAppWidget(
                        id,
                        RemoteViews(sizes.associateWith { views(ctx, next, now, lookFor(it.width, it.height, fontScale)) }),
                    )
                } else {
                    val o = manager.getAppWidgetOptions(id)
                    val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                    val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                    val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    manager.updateAppWidget(id, views(ctx, next, now, lookFor(w.toFloat(), h.toFloat(), fontScale)))
                }
            }
        }
    }
}
