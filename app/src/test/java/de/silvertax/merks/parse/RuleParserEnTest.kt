package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class RuleParserEnTest {
    // A Wednesday evening, as in the German tests.
    private val now = LocalDateTime.of(2026, 11, 11, 20, 0)

    private fun check(
        text: String, kind: Kind, start: String?, title: String, allDay: Boolean = false, repeat: Repeat? = null,
        end: String? = null, at: LocalDateTime = now,
    ) {
        val p = RuleParserEn.parse(text, at, monthFirst = true)
        assertEquals("kind of '$text'", kind, p.kind)
        assertEquals("start of '$text'", start?.let { LocalDateTime.parse(it) }, p.start)
        assertEquals("end of '$text'", end?.let { LocalDateTime.parse(it) }, p.end)
        assertEquals("title of '$text'", title, p.title)
        assertEquals("allDay of '$text'", allDay, p.allDay)
        assertEquals("repeat of '$text'", repeat, p.repeat)
    }

    private val wednesdayMorning = LocalDateTime.of(2026, 11, 11, 9, 0)
    private val mondayMorning = LocalDateTime.of(2026, 11, 9, 9, 0)
    private val saturdayMorning = LocalDateTime.of(2026, 11, 14, 10, 0)

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

    // Ranges

    @Test fun rangeFromTo() = check(
        "Tomorrow from 10 to 12 handyman", Kind.APPOINTMENT, "2026-11-12T10:00", "Handyman", end = "2026-11-12T12:00",
    )

    @Test fun rangeAmPm() = check(
        "Handyman tomorrow from 10 a.m. to 12 p.m.", Kind.APPOINTMENT, "2026-11-12T10:00", "Handyman", end = "2026-11-12T12:00",
    )

    @Test fun rangePmOnlyAtTheEnd() = check(
        "Friday from 2 to 4 pm card club", Kind.APPOINTMENT, "2026-11-13T14:00", "Card club", end = "2026-11-13T16:00",
    )

    @Test fun rangeBetween() = check(
        "Meeting tomorrow between 2 and 4", Kind.APPOINTMENT, "2026-11-12T14:00", "Meeting", end = "2026-11-12T16:00",
    )

    @Test fun rangeAcrossNoon() = check(
        "Between 11 and 1 lunch with Tom", Kind.APPOINTMENT, "2026-11-12T11:00", "Lunch with Tom", end = "2026-11-12T13:00",
    )

    @Test fun rangeWithMinutesStaysInTheMorning() = check(
        "9:30 to 11 physio", Kind.APPOINTMENT, "2026-11-12T09:30", "Physio", end = "2026-11-12T11:00",
    )

    @Test fun tenToTwelveIsNoRange() {
        // "ten to twelve" can be 11:50, so without "from" or am/pm no range is made of it.
        assertEquals(null, RuleParserEn.parse("ten to twelve", now).end)
    }

    @Test fun severalDaysWeekdays() = check(
        "Monday to Friday vacation", Kind.APPOINTMENT, "2026-11-16T00:00", "Vacation", allDay = true, end = "2026-11-20T00:00",
    )

    @Test fun severalDaysMonth() = check(
        "From December 20 to 23 vacation", Kind.APPOINTMENT, "2026-12-20T00:00", "Vacation", allDay = true,
        end = "2026-12-23T00:00",
    )

    @Test fun severalDaysOverNewYear() = check(
        "Dec 28 - Jan 3 skiing", Kind.APPOINTMENT, "2026-12-28T00:00", "Skiing", allDay = true, end = "2027-01-03T00:00",
    )

    // Hours without am/pm

    @Test fun todayAtNineInTheEvening() = check("Today at 9 take pills", Kind.APPOINTMENT, "2026-11-11T21:00", "Take pills")

    @Test fun bareHourTakesTheComingEvening() = check("At 9 call Peter", Kind.APPOINTMENT, "2026-11-11T21:00", "Call Peter")

    @Test fun bareTenStaysTomorrowMorning() = check("At 10 dentist", Kind.APPOINTMENT, "2026-11-12T10:00", "Dentist")

    @Test fun bareHourStillAheadToday() = check(
        "At 10 dentist", Kind.APPOINTMENT, "2026-11-11T10:00", "Dentist", at = wednesdayMorning,
    )

    @Test fun clockTimeIsAfternoon() = check("Pick up the kids at 3:15", Kind.APPOINTMENT, "2026-11-12T15:15", "Pick up the kids")

    @Test fun midnight() = check("At midnight take the medicine", Kind.APPOINTMENT, "2026-11-11T23:59", "Take the medicine")

    @Test fun thisAfternoon() = check(
        "This afternoon call the plumber", Kind.APPOINTMENT, "2026-11-11T15:00", "Call the plumber", at = wednesdayMorning,
    )

    // Soon: reminders

    @Test fun inTwoHoursIsAReminder() = check("In 2 hours take pills", Kind.REMINDER, "2026-11-11T22:00", "Take pills")

    @Test fun inHalfAnHourIsAReminder() = check(
        "In half an hour turn off the oven", Kind.REMINDER, "2026-11-11T20:30", "Turn off the oven",
    )

    // Series

    @Test fun everyOtherWednesday() = check(
        "Every other Wednesday choir at 6 pm", Kind.APPOINTMENT, "2026-11-18T18:00", "Choir",
        repeat = Repeat(RepeatUnit.WEEK, 2),
    )

    @Test fun everySecondWednesday() = check(
        "Every second Wednesday choir at 6 pm", Kind.APPOINTMENT, "2026-11-18T18:00", "Choir",
        repeat = Repeat(RepeatUnit.WEEK, 2),
    )

    @Test fun everyWeekday() = check(
        "Every weekday at 7 am walk the dog", Kind.APPOINTMENT, "2026-11-12T07:00", "Walk the dog",
        repeat = Repeat(RepeatUnit.WORKDAY, 1),
    )

    @Test fun weekdaySeriesStartsOnMonday() = check(
        "On weekdays at 7 am walk the dog", Kind.APPOINTMENT, "2026-11-16T07:00", "Walk the dog",
        repeat = Repeat(RepeatUnit.WORKDAY, 1), at = saturdayMorning,
    )

    @Test fun dayOfEveryMonth() = check(
        "On the 15th of every month pay rent", Kind.APPOINTMENT, "2026-11-15T00:00", "Pay rent",
        allDay = true, repeat = Repeat(RepeatUnit.MONTH, 1),
    )

    // Dates

    @Test fun weekdayAndDay() = check("Friday the 20th cinema", Kind.APPOINTMENT, "2026-11-20T00:00", "Cinema", allDay = true)

    @Test fun aWeekFromFriday() = check("A week from Friday cinema", Kind.APPOINTMENT, "2026-11-20T00:00", "Cinema", allDay = true)

    @Test fun dayOfMonthAlone() = check("On the 15th dentist", Kind.APPOINTMENT, "2026-11-15T00:00", "Dentist", allDay = true)

    @Test fun firstOfNextMonth() = check(
        "On the 1st of next month rent", Kind.APPOINTMENT, "2026-12-01T00:00", "Rent", allDay = true,
    )

    @Test fun ordinalBeforeANounIsNoDate() = check("Take the 2nd dose", Kind.NOTE, null, "Take the 2nd dose")

    @Test fun birthdayNumberStaysInTitle() = check(
        "Anna's 30th birthday on Saturday at 7", Kind.APPOINTMENT, "2026-11-14T19:00", "Anna's 30th birthday",
    )

    @Test fun weekdayNextWeek() = check(
        "Wednesday next week foot care at 11", Kind.APPOINTMENT, "2026-11-18T11:00", "Foot care", at = mondayMorning,
    )

    @Test fun nextWeekWeekday() = check(
        "Next week Wednesday foot care at 11", Kind.APPOINTMENT, "2026-11-18T11:00", "Foot care", at = mondayMorning,
    )

    @Test fun weekend() = check("This weekend wash the car", Kind.APPOINTMENT, "2026-11-14T00:00", "Wash the car", allDay = true)

    @Test fun firstMondayOfTheMonth() = check(
        "The first Monday of the month seniors club", Kind.APPOINTMENT, "2026-12-07T00:00", "Seniors club", allDay = true,
    )

    @Test fun weekdayWithDateLeavesTitle() = check(
        "Friday, November 20th at 2 pm haircut", Kind.APPOINTMENT, "2026-11-20T14:00", "Haircut",
    )

    @Test fun slashDateFollowsTheRegion() {
        assertEquals(LocalDateTime.parse("2026-12-03T00:00"), RuleParserEn.parse("On 12/3 doctor", now, monthFirst = true).start)
        assertEquals(LocalDateTime.parse("2027-03-12T00:00"), RuleParserEn.parse("On 12/3 doctor", now, monthFirst = false).start)
        // A day above 12 is clear either way.
        assertEquals(LocalDateTime.parse("2026-12-25T00:00"), RuleParserEn.parse("12/25 Christmas party", now, monthFirst = false).start)
    }

    @Test fun plainNumbersStayNotes() {
        check("Buy 2 apples", Kind.NOTE, null, "Buy 2 apples")
        check("The 3 kids need new shoes", Kind.NOTE, null, "The 3 kids need new shoes")
    }
}
