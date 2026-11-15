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
import android.widget.RemoteViews
import java.time.ZoneId

/**
 * What comes next, at a glance: the "Up next" and the all-in-one widget and, if switched on, a quiet
 * notification that also shows on the lock screen. They are redrawn whenever entries change and
 * whenever the next thing is over, a new one starts or the day changes.
 */
object Glance {
    private const val CHANNEL = "next_up"
    const val NOTIFICATION_ID = 1_999_999
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun nextUp(ctx: Context, limit: Int = 3): List<Plan.Upcoming> =
        Plan.nextUp(Db(ctx).all(), System.currentTimeMillis(), ZoneId.systemDefault(), limit)

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val nextWidgets = NextWidget.ids(app)
        val allWidgets = AllInOneWidget.ids(app)
        val notification = Prefs.nextUpNotification(app)
        val entries = Db(app).all()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val next = Plan.nextUp(entries, now, zone, 3)
        if (nextWidgets.isNotEmpty()) NextWidget.renderAll(app, next, now)
        if (allWidgets.isNotEmpty()) AllInOneWidget.renderAll(app, next, now)
        if (notification) show(app, next, now) else app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        // Only keep a wake-up when something shows what comes next.
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(app, 7, Intent(app, GlanceReceiver::class.java), FLAGS)
        if (nextWidgets.isEmpty() && allWidgets.isEmpty() && !notification) {
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
 * Home screen widget "Up next": the next appointment or reminder with its date tile; a taller widget
 * also lists the two after it. Tapping it opens the app, so at two cells wide it doubles as a big
 * button to the list, next to "Speak".
 */
class NextWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = Glance.refresh(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        Glance.refresh(ctx)

    override fun onDisabled(ctx: Context) = Glance.refresh(ctx)

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, NextWidget::class.java))

        /** From this width (dp) the date tile fits in front of the title. */
        const val TILE_MIN_W = 200f
        private const val MIN = 40f

        // Heights (dp) from which each look fits without cutting a line off. Larger print needs more
        // room, though less than in proportion. Measured on the layouts with long titles, plus 2 dp;
        // WidgetSizeTest checks them. A narrow widget mostly uses the same steps: its day, title and
        // time need a little less than the date tile, its title in two lines a little less than "Up next".
        // Below the lowest step everything shares one line.

        /** The title, and below it day and time in one line. Below this, all goes into one line. */
        internal fun lowMinH(fs: Float) = 38.5f * fs + 19f

        /** The date tile with the title beside it; narrow: the day, the title and the time. */
        internal fun tileMinH(fs: Float) = 44f * fs + 50f

        /** "Up next" above the title; narrow: the title in two lines. */
        internal fun labelMinH(fs: Float) = 72f * fs + 36f

        /** One more entry below, in one line. */
        internal fun list1MinH(fs: Float) = 95f * fs + 51f

        /** Two more entries below. */
        internal fun list2MinH(fs: Float) = 118.5f * fs + 50.5f

        /** Narrow: one more entry below, its day above its title. */
        internal fun stackList1MinH(fs: Float) = 108f * fs + 50f

        /** Narrow: two more. */
        internal fun stackList2MinH(fs: Float) = 145f * fs + 54.5f

        /** The steps of height at which the look changes; with the two widths 16 layouts, as many as Android takes. */
        internal fun heights(fs: Float) =
            listOf(MIN, lowMinH(fs), tileMinH(fs), labelMinH(fs), list1MinH(fs), list2MinH(fs), stackList1MinH(fs), stackList2MinH(fs))

        /** [w] and [h] in dp; an unknown size (0) gets the look of the preview. */
        fun lookFor(w: Float, h: Float, fontScale: Float = 1f): UpNextViews.Look {
            val fs = fontScale
            val wide = w <= 0f || w >= TILE_MIN_W
            if (h <= 0f) return UpNextViews.Look(tile = wide, label = wide, compact = !wide)
            if (h < lowMinH(fs)) return UpNextViews.Look(tile = false, oneLine = true, shortDay = !wide, compact = !wide)
            if (wide) {
                return when {
                    h < tileMinH(fs) -> UpNextViews.Look(tile = false, titleLines = 1, low = true)
                    h < labelMinH(fs) -> UpNextViews.Look(tile = true)
                    h < list1MinH(fs) -> UpNextViews.Look(tile = true, label = true)
                    h < list2MinH(fs) -> UpNextViews.Look(tile = true, label = true, rows = 1)
                    else -> UpNextViews.Look(tile = true, label = true, rows = 2)
                }
            }
            return when {
                h < tileMinH(fs) -> UpNextViews.Look(tile = false, titleLines = 1, low = true, shortDay = true, compact = true)
                h < labelMinH(fs) -> UpNextViews.Look(tile = false, titleLines = 1, compact = true)
                h < stackList1MinH(fs) -> UpNextViews.Look(tile = false, compact = true)
                h < stackList2MinH(fs) -> UpNextViews.Look(tile = false, rows = 1, compact = true)
                else -> UpNextViews.Look(tile = false, rows = 2, compact = true)
            }
        }

        fun views(ctx: Context, next: List<Plan.Upcoming>, now: Long, look: UpNextViews.Look): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_next).apply {
                setOnClickPendingIntent(android.R.id.background, UpNextViews.openApp(ctx))
                UpNextViews.fill(this, ctx, next, now, look)
                val dp = ctx.resources.displayMetrics.density
                val side = ((if (look.compact) 12 else 14) * dp).toInt()
                val vertical = ((if (look.oneLine) 5 else if (look.low) 8 else 12) * dp).toInt()
                setViewPadding(android.R.id.background, side, vertical, side, vertical)
            }

        fun renderAll(ctx: Context, next: List<Plan.Upcoming>, now: Long) {
            val manager = AppWidgetManager.getInstance(ctx)
            val fs = ctx.resources.configuration.fontScale
            for (id in ids(ctx)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // The home screen picks what fits the real size, in both orientations.
                    manager.updateAppWidget(
                        id,
                        UpNextViews.responsive(listOf(MIN, TILE_MIN_W), heights(fs)) { w, h -> views(ctx, next, now, lookFor(w, h, fs)) },
                    )
                } else {
                    val (w, h) = size(ctx, manager.getAppWidgetOptions(id))
                    manager.updateAppWidget(id, views(ctx, next, now, lookFor(w, h, fs)))
                }
            }
        }

        /** Older Android: the options hold portrait as min width x max height, landscape as max width x min height. */
        fun size(ctx: Context, o: Bundle): Pair<Float, Float> {
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return w.toFloat() to h.toFloat()
        }
    }
}
