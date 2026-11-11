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
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    private val dateTime = DateTimeFormatter.ofPattern("EEE, d. MMM, HH:mm 'Uhr'", Locale.GERMAN)
    private val dateOnly = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm 'Uhr'", Locale.GERMAN)
    private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMAN)
    private val shortDateTime = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm 'Uhr'", Locale.GERMAN)

    private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

    fun repeatText(r: Repeat): String = when {
        r.every == 1 -> when (r.unit) {
            RepeatUnit.DAY -> "täglich"
            RepeatUnit.WEEK -> "wöchentlich"
            RepeatUnit.MONTH -> "monatlich"
            RepeatUnit.YEAR -> "jährlich"
        }
        else -> "alle ${r.every} " + when (r.unit) {
            RepeatUnit.DAY -> "Tage"
            RepeatUnit.WEEK -> "Wochen"
            RepeatUnit.MONTH -> "Monate"
            RepeatUnit.YEAR -> "Jahre"
        }
    }

    /** Date line for the list; [start] is the occurrence to show (the next one for a series). */
    fun whenText(e: Entry, start: Long? = e.start): String {
        if (start == null) return ""
        val text = if (e.allDay) dateOnly.format(zoned(start)) else dateTime.format(zoned(start))
        return if (e.repeat != null) "$text · ${repeatText(e.repeat)}" else text
    }

    /** One line for the confirmation, e.g. "13.11.26 Zahnarzt bei Neupert". */
    fun oneLine(e: Entry): String {
        val start = e.start ?: return e.title
        val date = if (e.allDay) shortDate.format(zoned(start)) else shortDateTime.format(zoned(start))
        return "$date ${e.title}" + if (e.repeat != null) " (${repeatText(e.repeat)})" else ""
    }

    /** "Heute um 14:30 Uhr", "Morgen", ... for the reminder notification. */
    fun dueText(e: Entry, start: Long, now: Long): String {
        val day = zoned(start).toLocalDate()
        val days = ChronoUnit.DAYS.between(zoned(now).toLocalDate(), day)
        val dayWord = when (days) {
            0L -> "Heute"
            1L -> "Morgen"
            else -> dateOnly.format(zoned(start))
        }
        return if (e.allDay) dayWord else "$dayWord um ${timeOnly.format(zoned(start))}"
    }

    fun stamp(millis: Long): String = shortDateTime.format(zoned(millis))

    fun savedLabel(e: Entry): Int = when {
        e.kind == Kind.REMINDER -> R.string.saved_reminder
        e.kind == Kind.NOTE -> R.string.saved_note
        e.calendarEventId != null -> R.string.saved_appointment
        else -> R.string.saved_appointment_local
    }
}

object Scheduler {
    private const val CHANNEL = "reminders"
    private const val CONFIRM_CHANNEL = "confirm"
    const val ACTION_DONE = "de.silvertax.merks.DONE"
    const val ACTION_SNOOZE = "de.silvertax.merks.SNOOZE"

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun pending(ctx: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(ctx, id.toInt(), Intent(ctx, AlarmReceiver::class.java).putExtra("id", id), FLAGS)

    /** Sets the alarm for the entry's next notification, or clears it when nothing is left. */
    fun schedule(ctx: Context, e: Entry) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, e.id)
        val at = Plan.nextTrigger(e, System.currentTimeMillis(), Prefs.eveBefore(ctx), ZoneId.systemDefault())
        if (at == null) {
            am.cancel(pi)
            return
        }
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

    fun dismiss(ctx: Context, id: Long) = ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())

    fun rescheduleAll(ctx: Context) {
        for (e in Db(ctx).all()) schedule(ctx, e)
    }

    private fun action(ctx: Context, e: Entry, action: String, minutes: Int, label: Int): Notification.Action {
        val intent = Intent(ctx, ActionReceiver::class.java)
            .setAction(if (minutes > 0) "$action.$minutes" else action)
            .putExtra("id", e.id).putExtra("minutes", minutes)
        return Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_mic),
            ctx.getString(label),
            PendingIntent.getBroadcast(ctx, e.id.toInt(), intent, FLAGS),
        ).build()
    }

    /** The reminder itself, with "Erledigt" and two "later" buttons. */
    fun notify(ctx: Context, e: Entry) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
        val now = System.currentTimeMillis()
        val start = Plan.currentStart(e, now, ZoneId.systemDefault()) ?: return
        val text = if (e.kind == Kind.REMINDER) ctx.getString(R.string.notif_reminder) else Format.dueText(e, start, now)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(e.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action(ctx, e, ACTION_DONE, 0, R.string.action_done))
            .addAction(action(ctx, e, ACTION_SNOOZE, 15, R.string.action_snooze_15))
            .addAction(action(ctx, e, ACTION_SNOOZE, 60, R.string.action_snooze_60))
            .build()
        nm.notify(e.id.toInt(), n)
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
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val undo = PendingIntent.getBroadcast(
            ctx, confirmId(e.id), Intent(ctx, UndoReceiver::class.java).putExtra("id", e.id), FLAGS,
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
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val e = Db(ctx).get(intent.getLongExtra("id", -1)) ?: return
        if (!e.done) Scheduler.notify(ctx, e)
        // Line up the next one: the hour-before after the evening-before, or the next date of a series.
        Scheduler.schedule(ctx, e)
    }
}

/** "Erledigt" and "Später erinnern" on the reminder notification. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val db = Db(ctx)
        val e = db.get(id) ?: return
        val minutes = intent.getIntExtra("minutes", 0)
        if (minutes > 0) {
            db.setSnooze(id, System.currentTimeMillis() + minutes * 60_000L)
        } else {
            db.setSnooze(id, null)
            // A series goes on; "Erledigt" only settles this one date.
            if (e.repeat == null) db.setDone(id, true)
        }
        Scheduler.dismiss(ctx, id)
        db.get(id)?.let { Scheduler.schedule(ctx, it) }
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
