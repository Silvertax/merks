package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Calendar files as booking pages, mail programs and other calendars write them. */
class IcsReaderTest {
    private val now = LocalDateTime.of(2026, 11, 16, 15, 0) // a Monday
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun calendar(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + events.joinToString("") + "END:VCALENDAR\r\n"

    private fun event(vararg lines: String) = "BEGIN:VEVENT\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    private fun read(text: String) = IcsReader.read(text, now, berlin)

    @Test fun aBookingConfirmation() {
        val f = read(
            calendar(
                event(
                    "UID:4711@praxis.example",
                    "SUMMARY:Zahnarzt Dr. Neupert",
                    "DTSTART;TZID=Europe/Berlin:20261125T103000",
                    "DTEND;TZID=Europe/Berlin:20261125T110000",
                    "LOCATION:Meißner Str. 12\\, Radebeul",
                    "DESCRIPTION:Bitte Versichertenkarte mitbringen.\\nBei Verhinderung absagen.",
                    "BEGIN:VALARM",
                    "TRIGGER:-PT1H",
                    "DESCRIPTION:Erinnerung",
                    "END:VALARM",
                ),
            ),
        )
        assertEquals("Zahnarzt Dr. Neupert", f.title)
        assertEquals("Meißner Str. 12, Radebeul\nBitte Versichertenkarte mitbringen.", f.note)
        assertEquals(listOf(PhotoDate(LocalDate.of(2026, 11, 25), LocalTime.of(10, 30), LocalTime.of(11, 0), source = "Zahnarzt Dr. Neupert, Meißner Str. 12, Radebeul")), f.dates)
        assertFalse(f.byAi)
        assertTrue(f.text.contains("Bitte Versichertenkarte mitbringen."))
    }

    @Test fun timesInUtcAndOtherZonesComeOutInTheLocalTime() {
        val utc = read(calendar(event("SUMMARY:Video call", "DTSTART:20261125T093000Z", "DTEND:20261125T100000Z")))
        assertEquals(PhotoDate(LocalDate.of(2026, 11, 25), LocalTime.of(10, 30), LocalTime.of(11, 0), source = "Video call"), utc.dates.single())
        val outlook = read(calendar(event("SUMMARY:Besprechung", "DTSTART;TZID=\"W. Europe Standard Time\":20261125T140000")))
        assertEquals(LocalTime.of(14, 0), outlook.dates.single().time)
        val newYork = read(calendar(event("SUMMARY:Call", "DTSTART;TZID=America/New_York:20261125T090000")))
        assertEquals(LocalTime.of(15, 0), newYork.dates.single().time)
    }

    @Test fun allDayAndStaysOverSeveralDays() {
        val f = read(calendar(event("SUMMARY:Urlaub an der Ostsee", "DTSTART;VALUE=DATE:20261220", "DTEND;VALUE=DATE:20261223")))
        val d = f.dates.single()
        assertNull(d.time)
        assertEquals(LocalDate.of(2026, 12, 20), d.day)
        assertEquals(LocalDate.of(2026, 12, 22), d.lastDay)
        // A single day ends the day after, as all-day events do.
        val one = read(calendar(event("SUMMARY:Geburtstag Oma", "DTSTART;VALUE=DATE:20261203", "DTEND;VALUE=DATE:20261204"))).dates.single()
        assertNull(one.lastDay)
    }

    @Test fun whatIsOverOrCancelledStaysOut() {
        val f = read(
            calendar(
                event("SUMMARY:Vorbei", "DTSTART:20261110T080000Z"),
                event("SUMMARY:Abgesagt", "STATUS:CANCELLED", "DTSTART:20261130T080000Z"),
                event("SUMMARY:Bald", "DTSTART;TZID=Europe/Berlin:20261130T090000"),
            ),
        )
        assertEquals(listOf("Bald"), f.dates.map { it.source })
        // An appointment going on right now is not over.
        val now = read(calendar(event("SUMMARY:Läuft", "DTSTART;TZID=Europe/Berlin:20261116T143000", "DTEND;TZID=Europe/Berlin:20261116T160000")))
        assertEquals(1, now.dates.size)
    }

    @Test fun anEndlessSeriesBecomesASeries() {
        val weekly = read(calendar(event("SUMMARY:Chor", "DTSTART;TZID=Europe/Berlin:20260902T180000", "RRULE:FREQ=WEEKLY;INTERVAL=2")))
        val d = weekly.dates.single()
        // The next date from now on, every other week counted from the first.
        assertEquals(LocalDate.of(2026, 11, 25), d.day)
        assertEquals(Repeat(RepeatUnit.WEEK, 2), d.repeat)
        assertEquals(Repeat(RepeatUnit.WORKDAY, 1), read(calendar(event("SUMMARY:Gassi", "DTSTART:20261116T060000Z", "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))).dates.single().repeat)
        val yearly = read(calendar(event("SUMMARY:Geburtstag Oma", "DTSTART;VALUE=DATE:19481203", "RRULE:FREQ=YEARLY"))).dates.single()
        assertEquals(LocalDate.of(2026, 12, 3), yearly.day)
        assertEquals(Repeat(RepeatUnit.YEAR, 1), yearly.repeat)
        // As a series it is saved as one.
        assertEquals(Repeat(RepeatUnit.YEAR, 1), yearly.toParsed("Geburtstag Oma").repeat)
    }

    @Test fun aSeriesWithAnEndBecomesItsDates() {
        val f = read(calendar(event("SUMMARY:Physiotherapie", "DTSTART;TZID=Europe/Berlin:20261118T090000", "DTEND;TZID=Europe/Berlin:20261118T093000", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4")))
        assertEquals(listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 30)), f.dates.map { it.day })
        assertTrue(f.dates.all { it.repeat == null && it.time == LocalTime.of(9, 0) && it.end == LocalTime.of(9, 30) })
        val until = read(calendar(event("SUMMARY:Kurs", "DTSTART;TZID=Europe/Berlin:20261117T190000", "RRULE:FREQ=DAILY;INTERVAL=7;UNTIL=20261201T235959Z")))
        assertEquals(listOf(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1)), until.dates.map { it.day })
        // Dates that are over count towards COUNT, but are not shown.
        val begun = read(calendar(event("SUMMARY:Kurs", "DTSTART;TZID=Europe/Berlin:20261102T190000", "RRULE:FREQ=WEEKLY;COUNT=4")))
        assertEquals(listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23)), begun.dates.map { it.day })
    }

    @Test fun severalDifferentAppointmentsKeepTheirOwnTitles() {
        val f = read(
            calendar(
                event("SUMMARY:Friseur", "DTSTART;TZID=Europe/Berlin:20261120T100000"),
                event("SUMMARY:Augenarzt", "DTSTART;TZID=Europe/Berlin:20261118T083000"),
            ),
        )
        assertEquals(listOf("Augenarzt", "Friseur"), f.dates.map { it.title })
        assertEquals("Augenarzt", f.dates[0].toParsed("Termin").title)
    }

    @Test fun foldedLinesAndOddWriting() {
        val text = "\uFEFFBEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Ein sehr langer Titel\n  der weitergeht\nDTSTART:20261125T090000\nDURATION:PT1H30M\nEND:VEVENT\nEND:VCALENDAR\n"
        assertTrue(IcsReader.isCalendar(text))
        val d = read(text)
        assertEquals("Ein sehr langer Titel der weitergeht", d.title)
        assertEquals(LocalTime.of(10, 30), d.dates.single().end)
        assertFalse(IcsReader.isCalendar("Termin am 25.11. um 9 Uhr"))
        assertTrue(read("BEGIN:VCALENDAR\nEND:VCALENDAR").dates.isEmpty())
    }
}
