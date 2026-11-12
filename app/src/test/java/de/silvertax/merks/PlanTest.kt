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

    // ---------- What comes next ----------

    private fun item(
        id: Long, kind: Kind, start: String?, allDay: Boolean = false, end: String? = null,
        repeat: Repeat? = null, done: Boolean = false, snooze: String? = null,
    ) = Entry(id, kind, "e$id", "e$id", start?.let { at(it) }, end?.let { at(it) }, allDay, null, at("2026-11-01T08:00"), done, snooze?.let { at(it) }, repeat)

    private fun titles(list: List<Plan.Upcoming>) = list.map { it.entry.title }

    @Test fun nextUpIsSoonestFirstWithoutNotesDoneOrPast() {
        val entries = listOf(
            item(1, Kind.APPOINTMENT, "2026-11-14T10:00"),
            item(2, Kind.NOTE, null),
            item(3, Kind.REMINDER, "2026-11-13T09:00"),
            item(4, Kind.APPOINTMENT, "2026-11-12T08:00"), // over
            item(5, Kind.APPOINTMENT, "2026-11-13T12:00", done = true),
            item(6, Kind.APPOINTMENT, "2026-11-20T10:00"),
            item(7, Kind.APPOINTMENT, "2026-11-25T10:00"),
        )
        assertEquals(listOf("e3", "e1", "e6"), titles(Plan.nextUp(entries, at("2026-11-12T20:00"), zone)))
    }

    @Test fun anAppointmentStaysUntilItIsOver() {
        val timed = item(1, Kind.APPOINTMENT, "2026-11-13T10:00")
        assertEquals(at("2026-11-13T10:00"), Plan.upcoming(timed, at("2026-11-13T10:30"), zone)?.start)
        assertNull(Plan.upcoming(timed, at("2026-11-13T11:00"), zone))
        val long = item(2, Kind.APPOINTMENT, "2026-11-13T10:00", end = "2026-11-13T14:00")
        assertEquals(at("2026-11-13T10:00"), Plan.upcoming(long, at("2026-11-13T13:00"), zone)?.start)
        val allDay = item(3, Kind.APPOINTMENT, "2026-11-13T00:00", allDay = true)
        assertEquals(at("2026-11-13T00:00"), Plan.upcoming(allDay, at("2026-11-13T23:30"), zone)?.start)
        assertNull(Plan.upcoming(allDay, at("2026-11-14T00:00"), zone))
        // A reminder is gone once its time has come; snoozed, it shows its new time.
        val reminder = item(4, Kind.REMINDER, "2026-11-13T09:00")
        assertNull(Plan.upcoming(reminder, at("2026-11-13T09:00"), zone))
        val snoozed = item(5, Kind.REMINDER, "2026-11-13T09:00", snooze = "2026-11-13T09:15")
        assertEquals(at("2026-11-13T09:15"), Plan.upcoming(snoozed, at("2026-11-13T09:05"), zone)?.start)
    }

    @Test fun aSeriesShowsItsNextDate() {
        val weekly = item(1, Kind.APPOINTMENT, "2026-11-02T07:00", repeat = Repeat(RepeatUnit.WEEK, 1))
        assertEquals(at("2026-11-16T07:00"), Plan.upcoming(weekly, at("2026-11-12T20:00"), zone)?.start)
    }

    @Test fun redrawnWhenSomethingEndsStartsOrAtMidnight() {
        val now = at("2026-11-13T09:30")
        // Nothing today: at midnight "tomorrow" becomes "today".
        assertEquals(at("2026-11-14T00:00"), Plan.nextChange(listOf(item(1, Kind.APPOINTMENT, "2026-11-15T10:00")), now, zone))
        // A reminder at 11:00 today: then it is gone from the list.
        assertEquals(at("2026-11-13T11:00"), Plan.nextChange(listOf(item(2, Kind.REMINDER, "2026-11-13T11:00")), now, zone))
        // An appointment from 9:00 is shown until 10:00.
        assertEquals(at("2026-11-13T10:00"), Plan.nextChange(listOf(item(3, Kind.APPOINTMENT, "2026-11-13T09:00")), now, zone))
    }

    @Test fun workdaySeriesSkipsTheWeekend() {
        val e = entry(Kind.REMINDER, "2026-11-12T07:00", "2026-11-11T20:00", repeat = Repeat(RepeatUnit.WORKDAY, 1))
        assertEquals(at("2026-11-12T07:00"), next(e, "2026-11-11T20:00"))
        assertEquals(at("2026-11-13T07:00"), next(e, "2026-11-12T07:00"))
        assertEquals(at("2026-11-16T07:00"), next(e, "2026-11-13T07:00"))
    }

    @Test fun severalDaysStayUpUntilTheLastDay() {
        val e = Entry(
            1, Kind.APPOINTMENT, "Urlaub", "x", at("2026-11-16T00:00"), at("2026-11-20T00:00"), true, null,
            at("2026-11-11T20:00"),
        )
        assertEquals(4L, Plan.extraDays(e, zone))
        assertEquals(at("2026-11-16T00:00"), Plan.upcoming(e, at("2026-11-18T12:00"), zone)?.start)
        assertEquals(at("2026-11-16T00:00"), Plan.upcoming(e, at("2026-11-20T23:00"), zone)?.start)
        assertNull(Plan.upcoming(e, at("2026-11-21T00:00"), zone))
    }
}
