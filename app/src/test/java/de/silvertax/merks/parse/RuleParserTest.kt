package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class RuleParserTest {
    // A Wednesday evening.
    private val now = LocalDateTime.of(2026, 11, 11, 20, 0)

    private fun check(
        text: String, kind: Kind, start: String?, title: String, allDay: Boolean = false,
        end: String? = null, repeat: Repeat? = null, at: LocalDateTime = now,
    ) {
        val p = RuleParser.parse(text, at)
        assertEquals("kind of '$text'", kind, p.kind)
        assertEquals("start of '$text'", start?.let { LocalDateTime.parse(it) }, p.start)
        assertEquals("end of '$text'", end?.let { LocalDateTime.parse(it) }, p.end)
        assertEquals("title of '$text'", title, p.title)
        assertEquals("allDay of '$text'", allDay, p.allDay)
        if (repeat != null) assertEquals("repeat of '$text'", repeat, p.repeat)
    }

    private val mondayMorning = LocalDateTime.of(2026, 11, 9, 9, 0)
    private val saturdayMorning = LocalDateTime.of(2026, 11, 14, 10, 0)

    @Test fun tomorrowAtTen() = check("Morgen um 10 Zahnarzt", Kind.APPOINTMENT, "2026-11-12T10:00", "Zahnarzt")

    @Test fun weekdayHalf() = check(
        "Ich habe am Dienstag um halb drei einen Termin beim Zahnarzt",
        Kind.APPOINTMENT, "2026-11-17T14:30", "Termin beim Zahnarzt",
    )

    @Test fun reminderMinutes() = check(
        "Erinnere mich in 20 Minuten an die Wäsche", Kind.REMINDER, "2026-11-11T20:20", "Die Wäsche",
    )

    @Test fun monthNameAllDay() = check(
        "Am 3. Dezember Geburtstag von Oma", Kind.APPOINTMENT, "2026-12-03T00:00", "Geburtstag von Oma", allDay = true,
    )

    @Test fun uhrWithMinutes() = check("Übermorgen 14 Uhr 30 Friseur", Kind.APPOINTMENT, "2026-11-13T14:30", "Friseur")

    @Test fun plainNote() = check("Milch und Brot kaufen", Kind.NOTE, null, "Milch und Brot kaufen")

    @Test fun noteWithUm() = check("Ich kümmere mich um ein Geschenk", Kind.NOTE, null, "Ich kümmere mich um ein Geschenk")

    @Test fun eveningWord() = check("Freitag Abend Kino mit Peter", Kind.APPOINTMENT, "2026-11-13T19:00", "Kino mit Peter")

    @Test fun numericDate() = check(
        "am 24.12. um 18 Uhr Essen bei Mama", Kind.APPOINTMENT, "2026-12-24T18:00", "Essen bei Mama",
    )

    @Test fun clockTime() = check("Montag 9:15 Uhr Werkstatt", Kind.APPOINTMENT, "2026-11-16T09:15", "Werkstatt")

    @Test fun pastDateRollsToNextYear() = check(
        "Am 5. März Steuer abgeben", Kind.APPOINTMENT, "2027-03-05T00:00", "Steuer abgeben", allDay = true,
    )

    @Test fun reminderTomorrowMorning() = check(
        "Erinner mich bitte morgen früh daran, dass ich die Mülltonne rausstelle",
        Kind.REMINDER, "2026-11-12T08:00", "Ich die Mülltonne rausstelle",
    )

    @Test fun quarterTo() = check("Donnerstag viertel vor acht abends Chor", Kind.APPOINTMENT, "2026-11-12T19:45", "Chor")

    @Test fun timeOnlyToday() = check("um 22 Uhr Tabletten nehmen", Kind.APPOINTMENT, "2026-11-11T22:00", "Tabletten nehmen")

    @Test fun timeOnlyPastRollsToTomorrow() = check("um 3 Anruf bei Klaus", Kind.APPOINTMENT, "2026-11-12T15:00", "Anruf bei Klaus")

    private fun series(text: String, start: String, title: String, unit: RepeatUnit, every: Int, kind: Kind, allDay: Boolean) {
        check(text, kind, start, title, allDay)
        assertEquals("repeat of '$text'", Repeat(unit, every), RuleParser.parse(text, now).repeat)
    }

    @Test fun everyMonday() = series(
        "Jeden Montag Mülltonne rausstellen", "2026-11-16T00:00", "Mülltonne rausstellen",
        RepeatUnit.WEEK, 1, Kind.APPOINTMENT, allDay = true,
    )

    @Test fun everyDayAtEight() = series(
        "Jeden Tag um 8 Uhr Tabletten nehmen", "2026-11-12T08:00", "Tabletten nehmen",
        RepeatUnit.DAY, 1, Kind.APPOINTMENT, allDay = false,
    )

    @Test fun everyTwoWeeks() = series(
        "Alle zwei Wochen freitags um 15 Uhr Fußpflege", "2026-11-13T15:00", "Fußpflege",
        RepeatUnit.WEEK, 2, Kind.APPOINTMENT, allDay = false,
    )

    @Test fun everyYear() = series(
        "Jedes Jahr am 3. Dezember Geburtstag von Oma", "2026-12-03T00:00", "Geburtstag von Oma",
        RepeatUnit.YEAR, 1, Kind.APPOINTMENT, allDay = true,
    )

    @Test fun everyEveningReminder() = series(
        "Erinnere mich jeden Abend an die Tabletten", "2026-11-12T19:00", "Die Tabletten",
        RepeatUnit.DAY, 1, Kind.REMINDER, allDay = false,
    )

    @Test fun everyMonthOnTheFirst() = series(
        "Jeden Monat am 1. Miete überweisen", "2026-12-01T00:00", "Miete überweisen",
        RepeatUnit.MONTH, 1, Kind.APPOINTMENT, allDay = true,
    )

    @Test fun singleDatesHaveNoRepeat() {
        assertEquals(null, RuleParser.parse("Morgen um 10 Zahnarzt", now).repeat)
        assertEquals(null, RuleParser.parse("Freitag Abend Kino mit Peter", now).repeat)
    }

    @Test fun nextWeek() = check(
        "Nächste Woche Auto zum TÜV", Kind.APPOINTMENT, "2026-11-18T00:00", "Auto zum TÜV", allDay = true,
    )

    // Zeiträume

    @Test fun rangeVonBis() = check(
        "Morgen von 10 bis 12 Uhr Handwerker", Kind.APPOINTMENT, "2026-11-12T10:00", "Handwerker", end = "2026-11-12T12:00",
    )

    @Test fun rangeAfternoon() = check(
        "Freitag von 14 bis 16 Uhr Kartenrunde", Kind.APPOINTMENT, "2026-11-13T14:00", "Kartenrunde", end = "2026-11-13T16:00",
    )

    @Test fun rangeZwischenSpoken() = check(
        "Morgen zwischen 2 und 4 Handwerker", Kind.APPOINTMENT, "2026-11-12T14:00", "Handwerker", end = "2026-11-12T16:00",
    )

    @Test fun rangeWithoutVon() = check(
        "Montag 9:30 bis 11 Uhr Physio", Kind.APPOINTMENT, "2026-11-16T09:30", "Physio", end = "2026-11-16T11:00",
    )

    @Test fun rangeWithoutDateStaysInTheMorning() = check(
        "Von 8 bis 10 Uhr Maler", Kind.APPOINTMENT, "2026-11-12T08:00", "Maler", end = "2026-11-12T10:00",
    )

    @Test fun severalDays() = check(
        "Vom 20. bis 23. Dezember Oma zu Besuch", Kind.APPOINTMENT, "2026-12-20T00:00", "Oma zu Besuch",
        allDay = true, end = "2026-12-23T00:00",
    )

    @Test fun severalDaysNumeric() = check(
        "Vom 28.12. bis 3.1. Skiurlaub", Kind.APPOINTMENT, "2026-12-28T00:00", "Skiurlaub", allDay = true, end = "2027-01-03T00:00",
    )

    @Test fun severalWeekdays() = check(
        "Von Montag bis Freitag Urlaub", Kind.APPOINTMENT, "2026-11-16T00:00", "Urlaub", allDay = true, end = "2026-11-20T00:00",
    )

    // Uhrzeiten ohne "abends"

    @Test fun todayAtNineInTheEvening() = check("Heute um 9 Tabletten", Kind.APPOINTMENT, "2026-11-11T21:00", "Tabletten")

    @Test fun bareHourTakesTheComingEvening() = check("Um 9 Tabletten", Kind.APPOINTMENT, "2026-11-11T21:00", "Tabletten")

    @Test fun bareTenStaysTomorrowMorning() = check("Um 10 Zahnarzt", Kind.APPOINTMENT, "2026-11-12T10:00", "Zahnarzt")

    @Test fun todayWithUhr() = check("Heute um 9 Uhr zurückrufen", Kind.APPOINTMENT, "2026-11-11T21:00", "Zurückrufen")

    @Test fun midnight() = check("Um Mitternacht Tabletten", Kind.APPOINTMENT, "2026-11-11T23:59", "Tabletten")

    // Bald: Erinnerungen

    @Test fun inTwoHoursIsAReminder() = check("In 2 Stunden Tabletten nehmen", Kind.REMINDER, "2026-11-11T22:00", "Tabletten nehmen")

    @Test fun inHalfAnHourIsAReminder() = check("In einer halben Stunde Herd aus", Kind.REMINDER, "2026-11-11T20:30", "Herd aus")

    // Serien

    @Test fun everySecondWednesday() = check(
        "Jeden zweiten Mittwoch Chor um 18 Uhr", Kind.APPOINTMENT, "2026-11-18T18:00", "Chor",
        repeat = Repeat(RepeatUnit.WEEK, 2),
    )

    @Test fun everySecondWeek() = check(
        "Jede zweite Woche Fenster putzen", Kind.APPOINTMENT, "2026-11-11T00:00", "Fenster putzen", allDay = true,
        repeat = Repeat(RepeatUnit.WEEK, 2),
    )

    @Test fun workdays() = check(
        "Werktags um 7 Uhr Hund ausführen", Kind.APPOINTMENT, "2026-11-12T07:00", "Hund ausführen",
        repeat = Repeat(RepeatUnit.WORKDAY, 1),
    )

    @Test fun workdaysStartOnMonday() = check(
        "Montags bis freitags um 8 Uhr Tabletten", Kind.APPOINTMENT, "2026-11-16T08:00", "Tabletten",
        repeat = Repeat(RepeatUnit.WORKDAY, 1), at = saturdayMorning,
    )

    // Datum und Titel

    @Test fun nextWeekWednesday() = check(
        "Nächste Woche Mittwoch Fußpflege um 11 Uhr", Kind.APPOINTMENT, "2026-11-18T11:00", "Fußpflege", at = mondayMorning,
    )

    @Test fun untilFriday() = check(
        "Bis Freitag Steuererklärung abgeben", Kind.APPOINTMENT, "2026-11-13T00:00", "Steuererklärung abgeben", allDay = true,
    )

    @Test fun dayOfMonthAlone() = check("Am 15. Zahnarzt", Kind.APPOINTMENT, "2026-11-15T00:00", "Zahnarzt", allDay = true)

    @Test fun firstOfNextMonth() = check(
        "Am 1. des nächsten Monats Miete", Kind.APPOINTMENT, "2026-12-01T00:00", "Miete", allDay = true,
    )

    @Test fun weekdayAndDay() = check("Freitag, den 20. Kino", Kind.APPOINTMENT, "2026-11-20T00:00", "Kino", allDay = true)

    @Test fun weekFromFriday() = check("Freitag in einer Woche Kino", Kind.APPOINTMENT, "2026-11-20T00:00", "Kino", allDay = true)

    @Test fun weekend() = check("Am Wochenende Auto waschen", Kind.APPOINTMENT, "2026-11-14T00:00", "Auto waschen", allDay = true)

    @Test fun firstMondayOfTheMonth() = check(
        "Am ersten Montag im Monat Seniorentreff", Kind.APPOINTMENT, "2026-12-07T00:00", "Seniorentreff", allDay = true,
    )

    @Test fun tomorrowAndWeekday() = check("Morgen, Donnerstag, Zahnarzt", Kind.APPOINTMENT, "2026-11-12T00:00", "Zahnarzt", allDay = true)

    @Test fun numberWithDotIsNoDate() = check("Zum 3. Mal Tabletten vergessen", Kind.NOTE, null, "Zum 3. Mal Tabletten vergessen")
}
