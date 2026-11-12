package de.silvertax.merks

import android.content.Context
import android.text.format.DateFormat
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Dates and times the way people read them in the app's language. */
object Format {
    private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

    private fun pattern(ctx: Context, pattern: String) = DateTimeFormatter.ofPattern(pattern, Lang.locale(ctx))

    /** "14:30 Uhr" in German; "2:30 PM" or "14:30" in English, as the phone is set. */
    private fun time(ctx: Context, t: ZonedDateTime): String = when {
        Lang.isGerman(ctx) -> pattern(ctx, "HH:mm 'Uhr'")
        DateFormat.is24HourFormat(ctx) -> pattern(ctx, "HH:mm")
        else -> pattern(ctx, "h:mm a")
    }.format(t)

    /** "Freitag, 13. November" / "Friday, November 13". */
    private fun longDate(ctx: Context, t: ZonedDateTime): String =
        pattern(ctx, if (Lang.isGerman(ctx)) "EEEE, d. MMMM" else "EEEE, MMMM d").format(t)

    /** "Fr., 13. Nov., 14:30 Uhr" / "Fri, Nov 13, 2:30 PM". */
    private fun dayAndTime(ctx: Context, t: ZonedDateTime): String =
        pattern(ctx, if (Lang.isGerman(ctx)) "EEE, d. MMM" else "EEE, MMM d").format(t) + ", " + time(ctx, t)

    /** "13.11.26" / "11/13/26", as usual in the language and region. */
    private fun shortDate(ctx: Context, t: ZonedDateTime): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Lang.locale(ctx)).format(t)

    fun repeatText(ctx: Context, r: Repeat): String {
        val c = Lang.context(ctx)
        return if (r.every == 1) {
            c.getString(
                when (r.unit) {
                    RepeatUnit.DAY -> R.string.repeat_daily
                    RepeatUnit.WEEK -> R.string.repeat_weekly
                    RepeatUnit.MONTH -> R.string.repeat_monthly
                    RepeatUnit.YEAR -> R.string.repeat_yearly
                },
            )
        } else {
            c.getString(
                when (r.unit) {
                    RepeatUnit.DAY -> R.string.repeat_every_days
                    RepeatUnit.WEEK -> R.string.repeat_every_weeks
                    RepeatUnit.MONTH -> R.string.repeat_every_months
                    RepeatUnit.YEAR -> R.string.repeat_every_years
                },
                r.every,
            )
        }
    }

    /** Date line for the list; [start] is the occurrence to show (the next one for a series). */
    fun whenText(ctx: Context, e: Entry, start: Long? = e.start): String {
        if (start == null) return ""
        val t = zoned(start)
        val text = if (e.allDay) longDate(ctx, t) else dayAndTime(ctx, t)
        return if (e.repeat != null) "$text · ${repeatText(ctx, e.repeat)}" else text
    }

    /** One line for the confirmation, e.g. "13.11.26, 10:00 Uhr Zahnarzt bei Neupert". */
    fun oneLine(ctx: Context, e: Entry): String {
        val start = e.start ?: return e.title
        val t = zoned(start)
        val date = if (e.allDay) shortDate(ctx, t) else shortDate(ctx, t) + ", " + time(ctx, t)
        return "$date ${e.title}" + if (e.repeat != null) " (${repeatText(ctx, e.repeat)})" else ""
    }

    /** "Heute um 14:30 Uhr", "Tomorrow at 2:30 PM", ... for the reminder notification. */
    fun dueText(ctx: Context, e: Entry, start: Long, now: Long): String {
        val c = Lang.context(ctx)
        val t = zoned(start)
        val dayWord = when (ChronoUnit.DAYS.between(zoned(now).toLocalDate(), t.toLocalDate())) {
            0L -> c.getString(R.string.due_today)
            1L -> c.getString(R.string.due_tomorrow)
            else -> longDate(ctx, t)
        }
        return if (e.allDay) dayWord else c.getString(R.string.due_at, dayWord, time(ctx, t))
    }

    /** Short "when" for the "Up next" display: "Heute, 10:00 Uhr", "Morgen", "Fr., 14. Nov., 10:00 Uhr". */
    fun compactWhen(ctx: Context, e: Entry, start: Long, now: Long): String {
        val c = Lang.context(ctx)
        val t = zoned(start)
        val days = ChronoUnit.DAYS.between(zoned(now).toLocalDate(), t.toLocalDate())
        val day = when (days) {
            0L -> c.getString(R.string.due_today)
            1L -> c.getString(R.string.due_tomorrow)
            else -> pattern(ctx, if (Lang.isGerman(ctx)) "EEE, d. MMM" else "EEE, MMM d").format(t)
        }
        return if (e.allDay) day else day + ", " + time(ctx, t)
    }

    fun stamp(ctx: Context, millis: Long): String = zoned(millis).let { shortDate(ctx, it) + ", " + time(ctx, it) }

    fun savedLabel(e: Entry): Int = when {
        e.kind == Kind.REMINDER -> R.string.saved_reminder
        e.kind == Kind.NOTE -> R.string.saved_note
        e.calendarEventId != null -> R.string.saved_appointment
        else -> R.string.saved_appointment_local
    }
}
