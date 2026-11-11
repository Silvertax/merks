package de.silvertax.merks

import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PlanTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(s: String): Long = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    private fun entry(
        kind: Kind, start: String, created: String, allDay: Boolean = false, repeat: Repeat? = null,
        done: Boolean = false, snooze: String? = null,
    ) = Entry(1, kind, "x", "x", at(start), null, allDay, null, at(created), done, snooze?.let { at(it) }, repeat)

    private fun next(e: Entry, now: String, eve: Boolean = true): Long? = Plan.nextTrigger(e, at(now), eve, zone)

    @Test fun appointmentEveThenHourBefore() {
        val e = entry(Kind.APPOINTMENT, "2026-11-17T14:30", "2026-11-11T20:00")
        assertEquals(at("2026-11-16T18:00"), next(e, "2026-11-11T20:00"))
        assertEquals(at("2026-11-17T13:30"), next(e, "2026-11-16T18:00"))
        assertNull(next(e, "2026-11-17T13:30"))
    }

    @Test fun eveSwitchedOff() {
        val e = entry(Kind.APPOINTMENT, "2026-11-17T14:30", "2026-11-11T20:00")
        assertEquals(at("2026-11-17T13:30"), next(e, "2026-11-11T20:00", eve = false))
    }

    @Test fun enteredLessThanAnHourAhead() {
        val e = entry(Kind.APPOINTMENT, "2026-11-11T20:40", "2026-11-11T20:00")
        assertEquals(at("2026-11-11T20:25"), next(e, "2026-11-11T20:00"))
    }

    @Test fun reminderFiresOnTime() {
        val e = entry(Kind.REMINDER, "2026-11-11T20:20", "2026-11-11T20:00")
        assertEquals(at("2026-11-11T20:20"), next(e, "2026-11-11T20:00"))
        assertNull(next(e, "2026-11-11T20:20"))
    }

    @Test fun allDayEveAndMorning() {
        val e = entry(Kind.APPOINTMENT, "2026-12-03T00:00", "2026-11-11T20:00", allDay = true)
        assertEquals(at("2026-12-02T18:00"), next(e, "2026-11-11T20:00"))
        assertEquals(at("2026-12-03T08:00"), next(e, "2026-12-02T18:00"))
    }

    @Test fun weeklySeriesKeepsGoing() {
        val weekly = Repeat(RepeatUnit.WEEK, 1)
        val e = entry(Kind.APPOINTMENT, "2026-11-16T00:00", "2026-11-11T20:00", allDay = true, repeat = weekly)
        assertEquals(at("2026-11-15T18:00"), next(e, "2026-11-11T20:00"))
        assertEquals(at("2026-11-16T08:00"), next(e, "2026-11-15T18:00"))
        assertEquals(at("2026-11-22T18:00"), next(e, "2026-11-16T08:00"))
        // Still going a year later, across the daylight saving change.
        assertEquals(at("2027-07-04T18:00"), next(e, "2027-06-28T09:00"))
    }

    @Test fun everyTwoWeeksSkipsTheWeekBetween() {
        val e = entry(Kind.REMINDER, "2026-11-13T15:00", "2026-11-11T20:00", repeat = Repeat(RepeatUnit.WEEK, 2))
        assertEquals(at("2026-11-27T15:00"), next(e, "2026-11-13T15:00"))
    }

    @Test fun doneStopsEverything() {
        val e = entry(Kind.APPOINTMENT, "2026-11-17T14:30", "2026-11-11T20:00", done = true)
        assertNull(next(e, "2026-11-11T20:00"))
    }

    @Test fun snoozeComesFirstAndWorksAfterTheStart() {
        val soon = entry(Kind.APPOINTMENT, "2026-11-17T14:30", "2026-11-11T20:00", snooze = "2026-11-16T18:15")
        assertEquals(at("2026-11-16T18:15"), next(soon, "2026-11-16T18:01"))
        val late = entry(Kind.REMINDER, "2026-11-11T20:20", "2026-11-11T20:00", snooze = "2026-11-11T21:20")
        assertEquals(at("2026-11-11T21:20"), next(late, "2026-11-11T20:21"))
    }

    @Test fun currentStartOfSeriesIsTheNextDate() {
        val e = entry(Kind.APPOINTMENT, "2026-11-16T00:00", "2026-11-11T20:00", allDay = true, repeat = Repeat(RepeatUnit.WEEK, 1))
        assertEquals(at("2026-11-16T00:00"), Plan.currentStart(e, at("2026-11-11T20:00"), zone))
        assertEquals(at("2026-11-16T00:00"), Plan.currentStart(e, at("2026-11-16T12:00"), zone))
        assertEquals(at("2026-11-23T00:00"), Plan.currentStart(e, at("2026-11-17T09:00"), zone))
    }

    @Test fun monthlyOnThe31stDoesNotDrift() {
        val e = entry(Kind.REMINDER, "2027-01-31T09:00", "2026-11-11T20:00", repeat = Repeat(RepeatUnit.MONTH, 1))
        assertEquals(at("2027-02-28T09:00"), next(e, "2027-01-31T09:00"))
        assertEquals(at("2027-03-31T09:00"), next(e, "2027-02-28T09:00"))
    }
}
