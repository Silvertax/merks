package de.silvertax.merks

import android.content.Context
import android.text.format.DateFormat
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

    /** "Fr., 13. Nov., 14:30 Uhr" / "Fri, Nov 13, 2:30 PM", with the end when there is one. */
    private fun dayAndTime(ctx: Context, e: Entry, t: ZonedDateTime): String =
        pattern(ctx, if (Lang.isGerman(ctx)) "EEE, d. MMM" else "EEE, MMM d").format(t) + ", " + clock(ctx, e, t)

    /** The last day of an all-day entry that runs over several days, for the occurrence starting at [t]. */
    private fun lastDay(e: Entry, t: ZonedDateTime): ZonedDateTime? =
        Plan.extraDays(e, t.zone).takeIf { it > 0 }?.let { t.plusDays(it) }

    /** When this occurrence of an appointment ends, if it was said and ends the same day. */
    private fun endOf(e: Entry, t: ZonedDateTime): ZonedDateTime? {
        if (e.allDay || e.kind != Kind.APPOINTMENT) return null
        val start = e.start ?: return null
        val end = e.end ?: return null
        if (end <= start) return null
        return t.plus(Duration.ofMillis(end - start)).takeIf { it.toLocalDate() == t.toLocalDate() }
    }

    /** "14:00 Uhr", or "14:00–16:00 Uhr" / "2:00 PM – 4:00 PM" when the end is known. */
    private fun clock(ctx: Context, e: Entry, t: ZonedDateTime): String {
        val end = endOf(e, t) ?: return time(ctx, t)
        return when {
            Lang.isGerman(ctx) -> pattern(ctx, "HH:mm").format(t) + "–" + time(ctx, end)
            DateFormat.is24HourFormat(ctx) -> time(ctx, t) + "–" + time(ctx, end)
            else -> time(ctx, t) + " – " + time(ctx, end)
        }
    }

    /** "13.11.26" / "11/13/26", as usual in the language and region. */
    private fun shortDate(ctx: Context, t: ZonedDateTime): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Lang.locale(ctx)).format(t)

    fun repeatText(ctx: Context, r: Repeat): String {
        val c = Lang.context(ctx)
        if (r.unit == RepeatUnit.WORKDAY) return c.getString(R.string.repeat_workdays)
        return if (r.every == 1) {
            c.getString(
                when (r.unit) {
                    RepeatUnit.DAY -> R.string.repeat_daily
                    RepeatUnit.WEEK -> R.string.repeat_weekly
                    RepeatUnit.MONTH -> R.string.repeat_monthly
                    RepeatUnit.YEAR, RepeatUnit.WORKDAY -> R.string.repeat_yearly
                },
            )
        } else {
            c.getString(
                when (r.unit) {
                    RepeatUnit.DAY -> R.string.repeat_every_days
                    RepeatUnit.WEEK -> R.string.repeat_every_weeks
                    RepeatUnit.MONTH -> R.string.repeat_every_months
                    RepeatUnit.YEAR, RepeatUnit.WORKDAY -> R.string.repeat_every_years
                },
                r.every,
            )
        }
    }

    /** Date line for the list; [start] is the occurrence to show (the next one for a series). */
    fun whenText(ctx: Context, e: Entry, start: Long? = e.start): String {
        if (start == null) return ""
        val t = zoned(start)
        val text = when {
            !e.allDay -> dayAndTime(ctx, e, t)
            lastDay(e, t) != null -> longDate(ctx, t) + " – " + longDate(ctx, lastDay(e, t)!!)
            else -> longDate(ctx, t)
        }
        return if (e.repeat != null) "$text · ${repeatText(ctx, e.repeat)}" else text
    }

    /** One line for the confirmation, e.g. "13.11.26, 10:00 Uhr Zahnarzt bei Neupert". */
    fun oneLine(ctx: Context, e: Entry): String {
        val start = e.start ?: return e.title
        val t = zoned(start)
        val date = when {
            !e.allDay -> shortDate(ctx, t) + ", " + clock(ctx, e, t)
            lastDay(e, t) != null -> shortDate(ctx, t) + " – " + shortDate(ctx, lastDay(e, t)!!)
            else -> shortDate(ctx, t)
        }
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

    /** The day for the "Up next" display: "Heute", "Morgen", "Fr., 14. Nov."; [short] gives "14.11." / "Nov 14". */
    fun compactDay(ctx: Context, e: Entry, start: Long, now: Long, short: Boolean = false): String {
        val c = Lang.context(ctx)
        val t = zoned(start)
        val days = ChronoUnit.DAYS.between(zoned(now).toLocalDate(), t.toLocalDate())
        val date = when {
            !short -> pattern(ctx, if (Lang.isGerman(ctx)) "EEE, d. MMM" else "EEE, MMM d")
            Lang.isGerman(ctx) -> pattern(ctx, "d.M.")
            else -> pattern(ctx, "MMM d")
        }
        // A holiday that has already begun: "bis Fr., 20. Nov." / "until Fri, Nov 20".
        lastDay(e, t)?.takeIf { days < 0 }?.let { last -> return c.getString(R.string.until_day, date.format(last)) }
        return when (days) {
            0L -> c.getString(R.string.due_today)
            1L -> c.getString(R.string.due_tomorrow)
            else -> date.format(t)
        }
    }

    /** The time of day for the "Up next" display; nothing for all-day entries. */
    fun compactTime(ctx: Context, e: Entry, start: Long): String? = if (e.allDay) null else time(ctx, zoned(start))

    /** Short "when" for the "Up next" display: "Heute, 10:00 Uhr", "Morgen", "Fr., 14. Nov., 10:00 Uhr". */
    fun compactWhen(ctx: Context, e: Entry, start: Long, now: Long, short: Boolean = false): String =
        listOfNotNull(compactDay(ctx, e, start, now, short), compactTime(ctx, e, start)).joinToString(", ")

    /** "Freitag, 20. November 2026" / "Friday, November 20, 2026", for choosing a day. */
    fun fullDate(ctx: Context, day: LocalDate): String =
        pattern(ctx, if (Lang.isGerman(ctx)) "EEEE, d. MMMM yyyy" else "EEEE, MMMM d, yyyy").format(day)

    /** "14:30 Uhr" / "2:30 PM", for choosing a time. */
    fun clockTime(ctx: Context, t: LocalTime): String = when {
        Lang.isGerman(ctx) -> pattern(ctx, "HH:mm 'Uhr'")
        DateFormat.is24HourFormat(ctx) -> pattern(ctx, "HH:mm")
        else -> pattern(ctx, "h:mm a")
    }.format(t)

    /** Day and month for the date tile of a list entry: "18" and "Nov". */
    fun tileDay(start: Long): String = zoned(start).dayOfMonth.toString()

    fun tileMonth(ctx: Context, start: Long): String = pattern(ctx, "MMM").format(zoned(start)).trimEnd('.')

    fun stamp(ctx: Context, millis: Long): String = zoned(millis).let { shortDate(ctx, it) + ", " + time(ctx, it) }

    fun savedLabel(e: Entry): Int = when {
        e.kind == Kind.REMINDER -> R.string.saved_reminder
        e.kind == Kind.NOTE -> R.string.saved_note
        e.calendarEventId != null -> R.string.saved_appointment
        else -> R.string.saved_appointment_local
    }
}
