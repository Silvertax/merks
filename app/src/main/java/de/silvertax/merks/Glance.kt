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
 * Tapping it opens the app.
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

        const val BLOCK_MIN_H = 90f
        const val LIST_MIN_H = 150f

        fun shapeFor(h: Float): Shape = when {
            h <= 0f -> Shape.BLOCK
            h < BLOCK_MIN_H -> Shape.LINE
            h < LIST_MIN_H -> Shape.BLOCK
            else -> Shape.LIST
        }

        fun views(ctx: Context, next: List<Plan.Upcoming>, now: Long, shape: Shape): RemoteViews {
            val texts = Lang.context(ctx)
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(ctx.packageName, R.layout.widget_next).apply {
                setOnClickPendingIntent(R.id.next_root, open)
                setTextViewText(R.id.next_label, texts.getString(R.string.next_up_label))
                setViewVisibility(R.id.next_label, if (shape == Shape.LINE) View.GONE else View.VISIBLE)
                val first = next.firstOrNull()
                if (first == null) {
                    setTextViewText(R.id.next_title, texts.getString(R.string.next_up_none))
                    setViewVisibility(R.id.next_when, View.GONE)
                } else {
                    setTextViewText(R.id.next_title, first.entry.title)
                    setTextViewText(R.id.next_when, Format.compactWhen(ctx, first.entry, first.start, now))
                    setViewVisibility(R.id.next_when, View.VISIBLE)
                }
                setInt(R.id.next_title, "setMaxLines", if (shape == Shape.LINE) 1 else 2)
                val rest = if (shape == Shape.LIST) next.drop(1) else emptyList()
                setViewVisibility(R.id.next_more, if (rest.isEmpty()) View.GONE else View.VISIBLE)
                for ((i, row) in listOf(R.id.next_line2, R.id.next_line3).withIndex()) {
                    val u = rest.getOrNull(i)
                    setViewVisibility(row, if (u == null) View.GONE else View.VISIBLE)
                    if (u != null) setTextViewText(row, Format.compactWhen(ctx, u.entry, u.start, now) + " · " + u.entry.title)
                }
            }
        }

        fun renderAll(ctx: Context, next: List<Plan.Upcoming>, now: Long) {
            val manager = AppWidgetManager.getInstance(ctx)
            for (id in ids(ctx)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // The launcher picks what fits the real size.
                    manager.updateAppWidget(
                        id,
                        RemoteViews(
                            mapOf(
                                SizeF(100f, 40f) to views(ctx, next, now, Shape.LINE),
                                SizeF(100f, BLOCK_MIN_H) to views(ctx, next, now, Shape.BLOCK),
                                SizeF(100f, LIST_MIN_H) to views(ctx, next, now, Shape.LIST),
                            ),
                        ),
                    )
                } else {
                    val o = manager.getAppWidgetOptions(id)
                    val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                    val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    manager.updateAppWidget(id, views(ctx, next, now, shapeFor(h.toFloat())))
                }
            }
        }
    }
}
