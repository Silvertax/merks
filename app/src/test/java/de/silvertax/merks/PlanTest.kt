package de.silvertax.merks

import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun next(e: Entry, now: String, eve: Boolean = true): Long? =
        Plan.nextTrigger(e, at(now), if (eve) Lead.DEFAULT else setOf(Lead.HOUR), zone)

    private fun next(e: Entry, now: String, leads: Set<Lead>): Long? = Plan.nextTrigger(e, at(now), leads, zone)

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

    /** An all-day entry stays on top, but makes way for an appointment from two hours before until it is over. */
    @Test fun allDayMakesWayForWhatIsNear() {
        val entries = listOf(
            item(1, Kind.APPOINTMENT, "2026-11-13T00:00", allDay = true),
            item(2, Kind.APPOINTMENT, "2026-11-13T14:00"),
            item(3, Kind.REMINDER, "2026-11-13T18:00"),
            item(4, Kind.APPOINTMENT, "2026-11-14T09:00"),
        )
        fun first(now: String) = Plan.nextUp(entries, at(now), zone).first().entry.title
        assertEquals("e1", first("2026-11-13T08:00"))
        assertEquals(listOf("e1", "e2", "e3"), titles(Plan.nextUp(entries, at("2026-11-13T08:00"), zone)))
        assertEquals("e1", first("2026-11-13T11:59"))
        assertEquals(listOf("e2", "e1", "e3"), titles(Plan.nextUp(entries, at("2026-11-13T12:00"), zone)))
        // Going on (until 15:00), then the all-day entry is back on top.
        assertEquals("e2", first("2026-11-13T14:30"))
        assertEquals("e1", first("2026-11-13T15:00"))
        assertEquals("e3", first("2026-11-13T16:00"))
        assertEquals("e1", first("2026-11-13T18:00"))
        // Without an all-day entry nothing changes: soonest first.
        assertEquals(listOf("e2", "e3", "e4"), titles(Plan.nextUp(entries.drop(1), at("2026-11-13T08:00"), zone)))
        // The widget is drawn again when the appointment comes near, and when it is over.
        assertEquals(at("2026-11-13T12:00"), Plan.nextChange(entries, at("2026-11-13T08:00"), zone))
        assertEquals(at("2026-11-13T14:00"), Plan.nextChange(entries, at("2026-11-13T12:00"), zone))
        assertEquals(at("2026-11-13T15:00"), Plan.nextChange(entries, at("2026-11-13T14:00"), zone))
        assertEquals(at("2026-11-13T16:00"), Plan.nextChange(entries, at("2026-11-13T15:00"), zone))
    }

    /** The parts of the list: next Thursday is "next week", after tomorrow's appointments. */
    @Test fun theListIsSplitIntoParts() {
        val now = at("2026-11-19T18:20") // a Thursday
        fun part(start: String?, done: Boolean = false, firstDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY) =
            Plan.section(item(1, Kind.APPOINTMENT, start, done = done), start?.let { at(it) }, now, zone, firstDay)
        assertEquals(Plan.Section.TODAY, part("2026-11-19T20:00"))
        assertEquals(Plan.Section.TODAY, part("2026-11-17T00:00")) // began before today, still going on
        assertEquals(Plan.Section.TOMORROW, part("2026-11-20T13:30"))
        assertEquals(Plan.Section.THIS_WEEK, part("2026-11-22T10:00"))
        assertEquals(Plan.Section.NEXT_WEEK, part("2026-11-23T08:00"))
        assertEquals(Plan.Section.NEXT_WEEK, part("2026-11-26T09:00"))
        assertEquals(Plan.Section.LATER, part("2026-11-30T09:00"))
        assertEquals(Plan.Section.NOTES, part(null))
        assertEquals(Plan.Section.DONE, part("2026-11-20T09:00", done = true))
        // Where the week begins on Sunday, Sunday is next week already.
        assertEquals(Plan.Section.NEXT_WEEK, part("2026-11-22T10:00", firstDay = java.time.DayOfWeek.SUNDAY))
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

    @Test fun whatBelongsToThePast() {
        val now = at("2026-11-14T15:00")
        fun past(e: Entry) = Plan.isPast(e, now, zone)
        // An appointment without end takes an hour; with an end it lasts until then.
        assertTrue(past(entry(Kind.APPOINTMENT, "2026-11-14T13:00", "2026-11-14T08:00")))
        assertFalse(past(entry(Kind.APPOINTMENT, "2026-11-14T14:30", "2026-11-14T08:00")))
        // A reminder stays an hour after it rang, or after its new time when it was put off.
        assertFalse(past(entry(Kind.REMINDER, "2026-11-14T14:30", "2026-11-14T08:00")))
        assertTrue(past(entry(Kind.REMINDER, "2026-11-14T13:30", "2026-11-14T08:00")))
        assertFalse(past(entry(Kind.REMINDER, "2026-11-14T12:00", "2026-11-14T08:00").copy(snooze = at("2026-11-14T14:15"))))
        // All day lasts until midnight; done changes nothing about the time.
        assertFalse(past(entry(Kind.APPOINTMENT, "2026-11-14T00:00", "2026-11-14T08:00").copy(allDay = true)))
        assertTrue(past(entry(Kind.APPOINTMENT, "2026-11-13T00:00", "2026-11-13T08:00").copy(allDay = true, done = true)))
        assertFalse(past(entry(Kind.APPOINTMENT, "2026-11-15T10:00", "2026-11-14T08:00").copy(done = true)))
        // A series goes on, a note has no time.
        assertFalse(past(entry(Kind.APPOINTMENT, "2026-10-01T18:00", "2026-10-01T08:00", repeat = Repeat(RepeatUnit.WEEK, 1))))
        assertFalse(past(Entry(9, Kind.NOTE, "Notiz", "x", null, null, false, null, at("2026-11-01T08:00"))))
    }

    // ---------- Chosen reminder times ----------

    @Test fun aDayAndAnHourAhead() {
        val e = entry(Kind.APPOINTMENT, "2026-11-17T14:30", "2026-11-11T20:00")
        val leads = setOf(Lead.DAY, Lead.HOUR)
        assertEquals(at("2026-11-16T14:30"), next(e, "2026-11-11T20:00", leads))
        assertEquals(at("2026-11-17T13:30"), next(e, "2026-11-16T14:30", leads))
        assertNull(next(e, "2026-11-17T13:30", leads))
    }

    @Test fun everyLeadCountsBackFromTheStart() {
        val e = entry(Kind.APPOINTMENT, "2026-11-25T10:00", "2026-11-11T20:00")
        val expected = mapOf(
            Lead.WEEK to "2026-11-18T10:00",
            Lead.DAY to "2026-11-24T10:00",
            Lead.EVE to "2026-11-24T18:00",
            Lead.HOURS_2 to "2026-11-25T08:00",
            Lead.HOUR to "2026-11-25T09:00",
            Lead.MIN_30 to "2026-11-25T09:30",
            Lead.MIN_15 to "2026-11-25T09:45",
            Lead.START to "2026-11-25T10:00",
        )
        assertEquals(Lead.entries.toSet(), expected.keys)
        for ((lead, time) in expected) assertEquals(lead.name, at(time), next(e, "2026-11-11T20:00", setOf(lead)))
    }

    @Test fun allDayRemindsInTheMorning() {
        val e = entry(Kind.APPOINTMENT, "2026-12-03T00:00", "2026-11-11T20:00", allDay = true)
        assertEquals(at("2026-11-26T08:00"), next(e, "2026-11-11T20:00", setOf(Lead.WEEK)))
        assertEquals(at("2026-12-02T08:00"), next(e, "2026-11-11T20:00", setOf(Lead.DAY)))
        // Hours before a whole day would be in the night: the morning of the day instead, once.
        val hours = setOf(Lead.HOUR, Lead.MIN_15, Lead.START)
        assertEquals(at("2026-12-03T08:00"), next(e, "2026-11-11T20:00", hours))
        assertNull(next(e, "2026-12-03T08:00", hours))
    }

    @Test fun noReminderChosen() {
        val e = entry(Kind.APPOINTMENT, "2026-11-17T14:30", "2026-11-11T20:00")
        assertNull(next(e, "2026-11-11T20:00", emptySet()))
        // A reminder still rings: that is what it is for.
        val r = entry(Kind.REMINDER, "2026-11-17T14:30", "2026-11-11T20:00")
        assertEquals(at("2026-11-17T14:30"), next(r, "2026-11-11T20:00", emptySet()))
    }

    @Test fun shortNoticeWithADayAhead() {
        // Entered in the morning for the afternoon: a day ahead is over already, an hour ahead is not.
        val e = entry(Kind.APPOINTMENT, "2026-11-11T16:00", "2026-11-11T09:00")
        assertEquals(at("2026-11-11T15:00"), next(e, "2026-11-11T09:00", setOf(Lead.DAY, Lead.HOUR)))
        // Only a day ahead chosen: a quarter of an hour ahead instead, so it is not missed.
        assertEquals(at("2026-11-11T15:45"), next(e, "2026-11-11T09:00", setOf(Lead.DAY)))
    }

    @Test fun aDayAheadByTheClockAcrossSummerTime() {
        // Summer time ends on 25 October 2026: a day before 10:00 on the 26th is 10:00 on the 25th.
        val e = entry(Kind.APPOINTMENT, "2026-10-26T10:00", "2026-10-20T08:00")
        assertEquals(at("2026-10-25T10:00"), next(e, "2026-10-20T08:00", setOf(Lead.DAY)))
    }

    @Test fun leadsAsText() {
        assertEquals("DAY,HOUR", Lead.write(setOf(Lead.HOUR, Lead.DAY)))
        assertEquals(setOf(Lead.DAY, Lead.HOUR), Lead.parse("DAY,HOUR"))
        assertEquals(emptySet<Lead>(), Lead.parse(""))
        assertNull(Lead.parse(null))
        // A name this version does not know is skipped.
        assertEquals(setOf(Lead.HOUR), Lead.parse("HOUR,FORTNIGHT"))
    }
}
