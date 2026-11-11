package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class RuleParserTest {
    // A Wednesday evening.
    private val now = LocalDateTime.of(2026, 11, 11, 20, 0)

    private fun check(text: String, kind: Kind, start: String?, title: String, allDay: Boolean = false) {
        val p = RuleParser.parse(text, now)
        assertEquals("kind of '$text'", kind, p.kind)
        assertEquals("start of '$text'", start?.let { LocalDateTime.parse(it) }, p.start)
        assertEquals("title of '$text'", title, p.title)
        assertEquals("allDay of '$text'", allDay, p.allDay)
    }

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

    @Test fun nextWeek() = check(
        "Nächste Woche Auto zum TÜV", Kind.APPOINTMENT, "2026-11-18T00:00", "Auto zum TÜV", allDay = true,
    )
}
