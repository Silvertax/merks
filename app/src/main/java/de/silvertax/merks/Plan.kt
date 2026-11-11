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
