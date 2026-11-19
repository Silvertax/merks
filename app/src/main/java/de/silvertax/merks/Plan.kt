package de.silvertax.merks

import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class Entry(
    val id: Long,
    val kind: Kind,
    val title: String,
    val raw: String,
    val start: Long?,
    val end: Long?,
    val allDay: Boolean,
    val calendarEventId: Long?,
    val created: Long,
    val done: Boolean = false,
    /** "Später erinnern": notify again at this time. */
    val snooze: Long? = null,
    val repeat: Repeat? = null,
    /** Added afterwards, e.g. what to bring; one line per addition. */
    val note: String? = null,
)

/**
 * When an appointment reminds before it starts; any number of them can be chosen. A whole-day
 * appointment has no hour to count back from: it reminds at 8 in the morning instead, on the day
 * itself or on the day (or week) before.
 */
enum class Lead(val minutes: Long) {
    WEEK(7 * 24 * 60L),
    DAY(24 * 60L),

    /** The evening before at 6 pm, whatever time the appointment is. */
    EVE(0),
    HOURS_2(120),
    HOUR(60),
    MIN_30(30),
    MIN_15(15),
    START(0),
    ;

    companion object {
        /** What it was before there was a choice: the evening before, and an hour ahead. */
        val DEFAULT = setOf(EVE, HOUR)

        fun parse(text: String?): Set<Lead>? =
            text?.split(',')?.mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }?.toSet()

        fun write(leads: Set<Lead>): String = leads.sortedBy { it.ordinal }.joinToString(",") { it.name }
    }
}

/** Works out when an entry happens next and when to notify. Pure Kotlin so it can be unit-tested. */
object Plan {
    private const val MINUTE = 60 * 1000L
    private const val HOUR = 60 * MINUTE
    private const val MAX_OCCURRENCES = 20000

    private fun occurrences(e: Entry, zone: ZoneId): Sequence<ZonedDateTime> {
        val start = e.start ?: return emptySequence()
        val base = Instant.ofEpochMilli(start).atZone(zone)
        val r = e.repeat ?: return sequenceOf(base)
        if (r.unit == RepeatUnit.WORKDAY) {
            return generateSequence(0L) { it + 1 }.map { base.plusDays(it) }
                .filter { it.dayOfWeek.value <= 5 }.take(MAX_OCCURRENCES)
        }
        // Always counted from the first date, so "31st of every month" does not drift.
        return generateSequence(0L) { it + 1 }.take(MAX_OCCURRENCES).map { k ->
            val n = k * r.every
            when (r.unit) {
                RepeatUnit.DAY -> base.plusDays(n)
                RepeatUnit.WEEK -> base.plusWeeks(n)
                RepeatUnit.MONTH -> base.plusMonths(n)
                RepeatUnit.YEAR -> base.plusYears(n)
                RepeatUnit.WORKDAY -> base.plusDays(n)
            }
        }
    }

    /** Notification times for one occurrence of an appointment, with the chosen [leads]; a reminder rings on time. */
    private fun alerts(e: Entry, occ: ZonedDateTime, leads: Set<Lead>): List<Long> {
        val at = occ.toInstant().toEpochMilli()
        if (e.kind == Kind.NOTE) return emptyList()
        if (e.kind == Kind.REMINDER) return listOf(at)
        if (leads.isEmpty()) return emptyList()
        val day = occ.toLocalDate()
        fun morning(d: LocalDate, hour: Int = 8) = d.atTime(hour, 0).atZone(occ.zone).toInstant().toEpochMilli()
        val times = leads.map { lead ->
            when {
                lead == Lead.EVE -> morning(day.minusDays(1), 18)
                e.allDay && lead == Lead.WEEK -> morning(day.minusWeeks(1))
                e.allDay && lead == Lead.DAY -> morning(day.minusDays(1))
                e.allDay -> morning(day)
                // By the clock on the wall, also across a change to or from summer time.
                lead == Lead.WEEK -> occ.minusWeeks(1).toInstant().toEpochMilli()
                lead == Lead.DAY -> occ.minusDays(1).toInstant().toEpochMilli()
                else -> at - lead.minutes * MINUTE
            }
        }.distinct()
        // Entered at short notice: what would have been before that is left out, and when nothing is
        // left, a quarter of an hour ahead (a whole day: its morning).
        return times.filter { it > e.created }.ifEmpty { listOf(if (e.allDay) morning(day) else at - HOUR / 4) }
    }

    /** The next moment to notify, or null when nothing is left. */
    fun nextTrigger(e: Entry, now: Long, leads: Set<Lead>, zone: ZoneId): Long? {
        if (e.done) return null
        val snooze = e.snooze?.takeIf { it > now }
        for (occ in occurrences(e, zone)) {
            val next = alerts(e, occ, leads).filter { it > now }.minOrNull() ?: continue
            return if (snooze != null && snooze < next) snooze else next
        }
        return snooze
    }

    /** One thing coming up: the entry and the start of the occurrence that counts. */
    data class Upcoming(val entry: Entry, val start: Long)

    /** How many days an all-day entry lasts beyond its first one ("vom 20. bis 23." has three). */
    fun extraDays(e: Entry, zone: ZoneId): Long {
        val start = e.start ?: return 0
        val end = e.end ?: return 0
        if (!e.allDay || end <= start) return 0
        val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(first, last).coerceIn(0, 365)
    }

    /** Until when an occurrence counts as coming up: its end, the end of its day, or an hour after it began. */
    private fun over(e: Entry, occ: ZonedDateTime): Long {
        val at = occ.toInstant().toEpochMilli()
        return when {
            e.kind == Kind.REMINDER -> at
            e.allDay -> occ.toLocalDate().plusDays(1 + extraDays(e, occ.zone)).atStartOfDay(occ.zone).toInstant().toEpochMilli()
            e.end != null && e.start != null && e.end > e.start -> at + (e.end - e.start)
            else -> at + HOUR
        }
    }

    /** How long a reminder stays in the list after its time, so one that just rang can still be found. */
    const val REMINDER_GRACE = 60 * 60 * 1000L

    /**
     * Whether [e] belongs to the past: a single appointment or reminder whose time is over (a reminder
     * one hour after it rang), done or not. Notes and series never are.
     */
    fun isPast(e: Entry, now: Long, zone: ZoneId): Boolean {
        if (e.start == null || e.kind == Kind.NOTE || e.repeat != null) return false
        val t = if (e.kind == Kind.REMINDER) now - REMINDER_GRACE else now
        if ((e.snooze ?: Long.MIN_VALUE) > t) return false
        return occurrences(e, zone).none { over(e, it) > t }
    }

    /** The occurrence of [e] that is next or still going on at [now], or null. Notes and done entries have none. */
    fun upcoming(e: Entry, now: Long, zone: ZoneId): Upcoming? {
        if (e.done || e.kind == Kind.NOTE || e.start == null) return null
        // A snoozed reminder stays on the list until its new time.
        e.snooze?.takeIf { it > now && e.kind == Kind.REMINDER }?.let { return Upcoming(e, it) }
        for (occ in occurrences(e, zone)) {
            if (over(e, occ) > now) return Upcoming(e, occ.toInstant().toEpochMilli())
        }
        return null
    }

    /** What comes next, soonest first. */
    fun nextUp(entries: List<Entry>, now: Long, zone: ZoneId, limit: Int = 3): List<Upcoming> {
        val all = entries.mapNotNull { upcoming(it, now, zone) }.sortedBy { it.start }
        // An all-day entry that has begun would stay on top all day and hide what happens at a time.
        // So it is shown first only while nothing with a time is near or going on.
        val (allDayNow, rest) = all.partition { it.entry.allDay && it.start <= now }
        val (pressing, later) = rest.partition { pressing(it, now) }
        return (pressing + allDayNow + later).take(limit)
    }

    /** How long before its time an appointment or reminder takes the place of an all-day entry in "Up next". */
    const val SOON = 2 * 60 * 60 * 1000L

    private fun pressing(u: Upcoming, now: Long) = !u.entry.allDay && u.start - now <= SOON

    /**
     * When the "next up" display has to be drawn again: when one of the shown things is over,
     * when the next one starts, and at midnight, when "tomorrow" turns into "today".
     */
    fun nextChange(entries: List<Entry>, now: Long, zone: ZoneId): Long {
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var next = midnight
        val coming = entries.mapNotNull { upcoming(it, now, zone) }
        for (u in coming) {
            val e = u.entry
            val occ = Instant.ofEpochMilli(u.start).atZone(zone)
            val end = if (e.snooze != null && u.start == e.snooze) u.start else over(e, occ)
            if (end in (now + 1) until next) next = end
            if (u.start in (now + 1) until next) next = u.start
        }
        // And when something with a time comes near enough to take the place of an all-day entry.
        val allDay = coming.filter { it.entry.allDay }.map { it.start to over(it.entry, Instant.ofEpochMilli(it.start).atZone(zone)) }
        for (u in coming) {
            if (u.entry.allDay) continue
            val at = u.start - SOON
            if (at in (now + 1) until next && allDay.any { (from, until) -> at in from until until }) next = at
        }
        return next
    }

    /** The parts of the list, in their order: what is going on today, tomorrow, ..., notes, ticked off. */
    enum class Section { TODAY, TOMORROW, THIS_WEEK, NEXT_WEEK, LATER, NOTES, DONE }

    /**
     * Where an entry goes in the list. [start] is the date shown for it; something that began before
     * today and still goes on counts as today. Weeks begin on [firstDay].
     */
    fun section(e: Entry, start: Long?, now: Long, zone: ZoneId, firstDay: java.time.DayOfWeek): Section {
        if (e.done) return Section.DONE
        if (start == null) return Section.NOTES
        val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val week = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDay))
        return when {
            !day.isAfter(today) -> Section.TODAY
            day == today.plusDays(1) -> Section.TOMORROW
            day.isBefore(week.plusWeeks(1)) -> Section.THIS_WEEK
            day.isBefore(week.plusWeeks(2)) -> Section.NEXT_WEEK
            else -> Section.LATER
        }
    }

    /** Start of the occurrence that matters now: the first one today or later, else the last one. */
    fun currentStart(e: Entry, now: Long, zone: ZoneId): Long? {
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var last: ZonedDateTime? = null
        for (occ in occurrences(e, zone)) {
            last = occ
            if (!occ.toLocalDate().isBefore(today)) break
        }
        return last?.toInstant()?.toEpochMilli()
    }
}
