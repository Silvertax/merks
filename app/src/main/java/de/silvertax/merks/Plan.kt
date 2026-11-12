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
)

/** Works out when an entry happens next and when to notify. Pure Kotlin so it can be unit-tested. */
object Plan {
    private const val HOUR = 60 * 60 * 1000L
    private const val MAX_OCCURRENCES = 20000

    private fun occurrences(e: Entry, zone: ZoneId): Sequence<ZonedDateTime> {
        val start = e.start ?: return emptySequence()
        val base = Instant.ofEpochMilli(start).atZone(zone)
        val r = e.repeat ?: return sequenceOf(base)
        // Always counted from the first date, so "31st of every month" does not drift.
        return generateSequence(0L) { it + 1 }.take(MAX_OCCURRENCES).map { k ->
            val n = k * r.every
            when (r.unit) {
                RepeatUnit.DAY -> base.plusDays(n)
                RepeatUnit.WEEK -> base.plusWeeks(n)
                RepeatUnit.MONTH -> base.plusMonths(n)
                RepeatUnit.YEAR -> base.plusYears(n)
            }
        }
    }

    /** Notification times for one occurrence. */
    private fun alerts(e: Entry, occ: ZonedDateTime, eveBefore: Boolean): List<Long> {
        val at = occ.toInstant().toEpochMilli()
        if (e.kind == Kind.NOTE) return emptyList()
        if (e.kind == Kind.REMINDER) return listOf(at)
        val eve = occ.toLocalDate().minusDays(1).atTime(18, 0).atZone(occ.zone).toInstant().toEpochMilli()
        val main = when {
            e.allDay -> occ.toLocalDate().atTime(8, 0).atZone(occ.zone).toInstant().toEpochMilli()
            // One hour ahead; a quarter of an hour when it was entered later than that.
            at - HOUR > e.created -> at - HOUR
            else -> at - HOUR / 4
        }
        return if (eveBefore) listOf(eve, main) else listOf(main)
    }

    /** The next moment to notify, or null when nothing is left. */
    fun nextTrigger(e: Entry, now: Long, eveBefore: Boolean, zone: ZoneId): Long? {
        if (e.done) return null
        val snooze = e.snooze?.takeIf { it > now }
        for (occ in occurrences(e, zone)) {
            val next = alerts(e, occ, eveBefore).filter { it > now }.minOrNull() ?: continue
            return if (snooze != null && snooze < next) snooze else next
        }
        return snooze
    }

    /** One thing coming up: the entry and the start of the occurrence that counts. */
    data class Upcoming(val entry: Entry, val start: Long)

    /** Until when an occurrence counts as coming up: its end, the end of its day, or an hour after it began. */
    private fun over(e: Entry, occ: ZonedDateTime): Long {
        val at = occ.toInstant().toEpochMilli()
        return when {
            e.kind == Kind.REMINDER -> at
            e.allDay -> occ.toLocalDate().plusDays(1).atStartOfDay(occ.zone).toInstant().toEpochMilli()
            e.end != null && e.start != null && e.end > e.start -> at + (e.end - e.start)
            else -> at + HOUR
        }
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
    fun nextUp(entries: List<Entry>, now: Long, zone: ZoneId, limit: Int = 3): List<Upcoming> =
        entries.mapNotNull { upcoming(it, now, zone) }.sortedBy { it.start }.take(limit)

    /**
     * When the "next up" display has to be drawn again: when one of the shown things is over,
     * when the next one starts, and at midnight, when "tomorrow" turns into "today".
     */
    fun nextChange(entries: List<Entry>, now: Long, zone: ZoneId): Long {
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var next = midnight
        for (e in entries) {
            val u = upcoming(e, now, zone) ?: continue
            val occ = Instant.ofEpochMilli(u.start).atZone(zone)
            val end = if (e.snooze != null && u.start == e.snooze) u.start else over(e, occ)
            if (end in (now + 1) until next) next = end
            if (u.start in (now + 1) until next) next = u.start
        }
        return next
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
