package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class RuleParserEnTest {
    // A Wednesday evening, as in the German tests.
    private val now = LocalDateTime.of(2026, 11, 11, 20, 0)

    private fun check(
        text: String, kind: Kind, start: String?, title: String, allDay: Boolean = false, repeat: Repeat? = null,
    ) {
        val p = RuleParserEn.parse(text, now)
        assertEquals("kind of '$text'", kind, p.kind)
        assertEquals("start of '$text'", start?.let { LocalDateTime.parse(it) }, p.start)
        assertEquals("title of '$text'", title, p.title)
        assertEquals("allDay of '$text'", allDay, p.allDay)
        assertEquals("repeat of '$text'", repeat, p.repeat)
    }

    @Test fun tomorrowAtTen() = check("Tomorrow at 10 dentist", Kind.APPOINTMENT, "2026-11-12T10:00", "Dentist")

    @Test fun weekdayWithPm() = check(
        "Dentist appointment on Tuesday at 2:30 pm", Kind.APPOINTMENT, "2026-11-17T14:30", "Dentist appointment",
    )

    @Test fun reminderInMinutes() = check(
        "Remind me in 20 minutes to take the laundry out", Kind.REMINDER, "2026-11-11T20:20", "Take the laundry out",
    )

    @Test fun monthNameAllDay() = check(
        "Grandma's birthday on December 3rd", Kind.APPOINTMENT, "2026-12-03T00:00", "Grandma's birthday", allDay = true,
    )

    @Test fun dayAfterTomorrow() = check(
        "Day after tomorrow at 2:30 pm hairdresser", Kind.APPOINTMENT, "2026-11-13T14:30", "Hairdresser",
    )

    @Test fun plainNote() = check("Buy milk and bread", Kind.NOTE, null, "Buy milk and bread")

    @Test fun noteWithoutTime() = check("I'll take care of a present", Kind.NOTE, null, "I'll take care of a present")

    @Test fun eveningWord() = check("Friday evening cinema with Peter", Kind.APPOINTMENT, "2026-11-13T19:00", "Cinema with Peter")

    @Test fun clockTime() = check("Monday at 9:15 car repair shop", Kind.APPOINTMENT, "2026-11-16T09:15", "Car repair shop")

    @Test fun pmToday() = check("At 10 pm take pills", Kind.APPOINTMENT, "2026-11-11T22:00", "Take pills")

    @Test fun spokenHourRollsToTomorrow() = check("At 3 call Klaus", Kind.APPOINTMENT, "2026-11-12T15:00", "Call Klaus")

    @Test fun quarterTo() = check(
        "Thursday quarter to eight in the evening choir", Kind.APPOINTMENT, "2026-11-12T19:45", "Choir",
    )

    @Test fun halfPast() = check("Half past two tomorrow doctor", Kind.APPOINTMENT, "2026-11-12T14:30", "Doctor")

    @Test fun nextWeek() = check(
        "Next week car inspection", Kind.APPOINTMENT, "2026-11-18T00:00", "Car inspection", allDay = true,
    )

    @Test fun pastDateRollsToNextYear() = check(
        "March 5th file the taxes", Kind.APPOINTMENT, "2027-03-05T00:00", "File the taxes", allDay = true,
    )

    @Test fun everyMonday() = check(
        "Every Monday take out the trash", Kind.APPOINTMENT, "2026-11-16T00:00", "Take out the trash",
        allDay = true, repeat = Repeat(RepeatUnit.WEEK, 1),
    )

    @Test fun everyDayAt8am() = check(
        "Every day at 8 am take pills", Kind.APPOINTMENT, "2026-11-12T08:00", "Take pills",
        repeat = Repeat(RepeatUnit.DAY, 1),
    )

    @Test fun everyTwoWeeks() = check(
        "Every two weeks on Friday at 3 pm foot care", Kind.APPOINTMENT, "2026-11-13T15:00", "Foot care",
        repeat = Repeat(RepeatUnit.WEEK, 2),
    )

    @Test fun everyOtherWeek() = check(
        "Every other week on Saturday cleaning", Kind.APPOINTMENT, "2026-11-14T00:00", "Cleaning",
        allDay = true, repeat = Repeat(RepeatUnit.WEEK, 2),
    )

    @Test fun everyYear() = check(
        "Every year on December 3rd grandma's birthday", Kind.APPOINTMENT, "2026-12-03T00:00", "Grandma's birthday",
        allDay = true, repeat = Repeat(RepeatUnit.YEAR, 1),
    )

    @Test fun everyEveningReminder() = check(
        "Remind me every evening to take my pills", Kind.REMINDER, "2026-11-12T19:00", "Take my pills",
        repeat = Repeat(RepeatUnit.DAY, 1),
    )

    @Test fun everyMonthOnTheFirst() = check(
        "Pay rent every month on the 1st", Kind.APPOINTMENT, "2026-12-01T00:00", "Pay rent",
        allDay = true, repeat = Repeat(RepeatUnit.MONTH, 1),
    )

    @Test fun tonight() = check("Tonight at 8 party at Anna's", Kind.APPOINTMENT, "2026-11-11T20:00", "Party at Anna's")

    @Test fun inAnHour() = check("Remind me in an hour to call mum", Kind.REMINDER, "2026-11-11T21:00", "Call mum")
}
