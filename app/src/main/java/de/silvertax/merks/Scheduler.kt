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
import java.time.ZoneId

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

    fun dismissConfirm(ctx: Context, id: Long) = ctx.getSystemService(NotificationManager::class.java).cancel(confirmId(id))

    fun rescheduleAll(ctx: Context) {
        for (e in Db(ctx).all()) schedule(ctx, e)
    }

    private fun action(ctx: Context, e: Entry, action: String, minutes: Int, label: Int): Notification.Action {
        val texts = Lang.context(ctx)
        val intent = Intent(ctx, ActionReceiver::class.java)
            .setAction(if (minutes > 0) "$action.$minutes" else action)
            .putExtra("id", e.id).putExtra("minutes", minutes)
        return Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_mic),
            texts.getString(label),
            PendingIntent.getBroadcast(ctx, e.id.toInt(), intent, FLAGS),
        ).build()
    }

    /** The reminder itself, with "Erledigt" and two "later" buttons. */
    fun notify(ctx: Context, e: Entry) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val texts = Lang.context(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, texts.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
        val now = System.currentTimeMillis()
        val start = Plan.currentStart(e, now, ZoneId.systemDefault()) ?: return
        val text = if (e.kind == Kind.REMINDER) texts.getString(R.string.notif_reminder) else Format.dueText(ctx, e, start, now)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        // The note is what matters now, e.g. what to bring: shown right away, the time below it when opened.
        val note = e.note?.takeIf { it.isNotBlank() }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_merks)
            .setColor(ctx.getColor(R.color.brand_teal))
            .setContentTitle(e.title)
            .setContentText(note ?: text)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(note, text).joinToString("\n")))
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

    /** What a confirmation is about: a new entry, a new date for one, or a note added to one. */
    enum class Confirm { NEW, CHANGED, NOTED }

    /**
     * Tells the user what was just saved, with a way to take it back, one to change the date by voice and
     * one to add a note by voice. For a change or a note, "Undo" puts the old version back instead of
     * deleting; [added] is the note that was just added.
     */
    fun confirm(ctx: Context, e: Entry, how: Confirm = Confirm.NEW, added: String? = null) {
        val changed = how != Confirm.NEW
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val texts = Lang.context(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CONFIRM_CHANNEL, texts.getString(R.string.channel_confirm), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val undo = PendingIntent.getBroadcast(
            ctx, confirmId(e.id), Intent(ctx, UndoReceiver::class.java).putExtra("id", e.id).putExtra("changed", changed), FLAGS,
        )
        val change = PendingIntent.getActivity(
            ctx, confirmId(e.id),
            RecordActivity.changeIntent(ctx, e.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            FLAGS,
        )
        // Its own request code: two intents for the same screen that differ only in extras would be one.
        val addNote = PendingIntent.getActivity(
            ctx, (e.id + 2_000_000).toInt(),
            RecordActivity.noteIntent(ctx, e.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            FLAGS,
        )
        val text = if (how == Confirm.NOTED) e.title + ": " + Store.tidyNote(added.orEmpty()) else Format.oneLine(ctx, e)
        val title = when (how) {
            Confirm.NEW -> Format.savedLabel(e)
            Confirm.CHANGED -> R.string.changed_label
            Confirm.NOTED -> R.string.note_added
        }
        fun button(label: Int, intent: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_mic), texts.getString(label), intent).build()
        val n = Notification.Builder(ctx, CONFIRM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_merks)
            .setColor(ctx.getColor(R.color.brand_teal))
            .setContentTitle(texts.getString(title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(button(R.string.undo, undo))
            .addAction(button(R.string.change, change))
            // A note goes with an appointment or a reminder, e.g. what to bring.
            .apply { if (e.kind != Kind.NOTE) addAction(button(R.string.note_add_short, addNote)) }
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
        Glance.refresh(ctx)
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
        Glance.refresh(ctx)
    }
}

/** "Rückgängig" on the confirmation notification. */
class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val e = Db(ctx).get(id)
        val before = if (intent.getBooleanExtra("changed", false)) Prefs.takeBefore(ctx, id) else null
        when {
            e != null && before != null -> {
                Store.restore(ctx, e, before)
                Scheduler.dismissConfirm(ctx, id)
            }
            e != null -> Store.remove(ctx, e)
            else -> Scheduler.cancel(ctx, id)
        }
    }
}

/** Alarms are lost on reboot and on app updates, so they are set again here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.cleanUp(ctx)
        Scheduler.rescheduleAll(ctx)
        Glance.refresh(ctx)
    }
}
