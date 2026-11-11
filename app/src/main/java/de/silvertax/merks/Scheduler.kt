package de.silvertax.merks

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import de.silvertax.merks.parse.Kind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val dateTime = DateTimeFormatter.ofPattern("EEE, d. MMM, HH:mm 'Uhr'", Locale.GERMAN)
    private val dateOnly = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm 'Uhr'", Locale.GERMAN)

    fun whenText(e: Entry): String {
        val start = e.start ?: return ""
        val z = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        return if (e.allDay) dateOnly.format(z) else dateTime.format(z)
    }

    fun timeText(millis: Long): String = timeOnly.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMAN)
    private val shortDateTime = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm 'Uhr'", Locale.GERMAN)

    /** One line for the confirmation, e.g. "13.11.26 Zahnarzt bei Neupert". */
    fun oneLine(e: Entry): String {
        val start = e.start ?: return e.title
        val z = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        return (if (e.allDay) shortDate.format(z) else shortDateTime.format(z)) + " " + e.title
    }

    fun savedLabel(e: Entry): Int = when {
        e.kind == Kind.REMINDER -> R.string.saved_reminder
        e.kind == Kind.NOTE -> R.string.saved_note
        e.calendarEventId != null -> R.string.saved_appointment
        else -> R.string.saved_appointment_local
    }
}

object Scheduler {
    private const val HOUR = 60 * 60 * 1000L
    private const val CHANNEL = "reminders"
    private const val CONFIRM_CHANNEL = "confirm"

    /** When to notify: reminders on time, appointments one hour ahead, all-day entries at 8 in the morning. */
    fun triggerTime(e: Entry, now: Long): Long? {
        val start = e.start ?: return null
        val candidates = when {
            e.kind == Kind.NOTE -> return null
            e.kind == Kind.REMINDER -> listOf(start)
            e.allDay -> listOf(start + 8 * HOUR)
            else -> listOf(start - HOUR, start - HOUR / 4)
        }
        return candidates.firstOrNull { it > now }
    }

    private fun pending(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context, e: Entry) {
        val at = triggerTime(e, System.currentTimeMillis()) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, e.id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(id.toInt())
        nm.cancel(confirmId(id))
    }

    private fun confirmId(id: Long): Int = (id + 1_000_000).toInt()

    fun canConfirmByNotification(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Tells the user what was just saved, with a way to take it back. */
    fun confirm(ctx: Context, e: Entry) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CONFIRM_CHANNEL, ctx.getString(R.string.channel_confirm), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
        val undo = PendingIntent.getBroadcast(
            ctx, confirmId(e.id), Intent(ctx, UndoReceiver::class.java).putExtra("id", e.id), flags,
        )
        val text = Format.oneLine(e)
        val n = Notification.Builder(ctx, CONFIRM_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ctx.getString(Format.savedLabel(e)))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_mic), ctx.getString(R.string.undo), undo,
                ).build(),
            )
            .build()
        nm.notify(confirmId(e.id), n)
    }

    fun rescheduleAll(ctx: Context) {
        for (e in Db(ctx).all()) schedule(ctx, e)
    }

    fun notify(ctx: Context, e: Entry) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            e.kind == Kind.REMINDER -> ctx.getString(R.string.notif_reminder)
            e.allDay -> ctx.getString(R.string.notif_today)
            else -> ctx.getString(R.string.notif_at, Format.timeText(e.start!!))
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(e.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(e.id.toInt(), n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val e = Db(ctx).get(intent.getLongExtra("id", -1)) ?: return
        Scheduler.notify(ctx, e)
    }
}

/** "Rückgängig" on the confirmation notification. */
class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val e = Db(ctx).get(id)
        if (e != null) Store.remove(ctx, e) else Scheduler.cancel(ctx, id)
    }
}

/** Alarms are lost on reboot and on app updates, so they are set again here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}
