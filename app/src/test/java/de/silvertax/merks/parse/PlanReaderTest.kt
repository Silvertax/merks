package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Weekly plans read from the words on their picture and the places of the words. */
class PlanReaderTest {
    private val mondayMorning = LocalDateTime.of(2026, 11, 16, 7, 0)
    private val wednesdayNoon = LocalDateTime.of(2026, 11, 18, 12, 0)

    private fun List<PhotoDate>.described() = map { "${it.day} ${it.time}-${it.end} ${it.title} | ${it.detail}" }

    private val wholeWeek = listOf(
        "2026-11-16 07:45-null Frühsport | Turnhalle",
        "2026-11-16 09:00-10:30 Töpfern | Werkraum",
        "2026-11-16 14:00-15:00 Chor | Noten mitbringen",
        "2026-11-17 08:30-10:00 Offene Werkstatt | null",
        "2026-11-17 11:00-12:30 Kochgruppe | max. 8 P, Raum 2",
        "2026-11-18 07:45-null Frühsport | Turnhalle",
        "2026-11-18 10:00-11:30 Malkurs | Raum 12",
        "2026-11-18 16:45-17:30 Schwimmen | Hallenbad",
        "2026-11-19 08:30-10:00 Offene Werkstatt | null",
        "2026-11-19 10:15-11:00 Gedächtnistraining | Bücherei, 1. Stock",
        "2026-11-19 18:00-null Spieleabend | null",
        "2026-11-20 07:45-null Frühsport | Turnhalle",
        "2026-11-20 13:00-14:30 Offene Werkstatt | null",
        "2026-11-20 15:30-16:30 Lesecafé | Raum 5",
    )

    @Test fun aWeeklyPlanBecomesItsAppointmentsWithTitlesAndRooms() {
        val find = PlanReader.read(PlanSheet.courses().words, mondayMorning)!!
        // Titles above the time or on its line, rooms and remarks around it, an end on the next line;
        // the note under the plan ("every first Tuesday ... 18:00") is none of them.
        assertEquals(wholeWeek, find.dates.described())
        assertEquals("Kursplan Familienzentrum Lindenhof", find.title)
        assertTrue(find.dates.all { it.chosen })
        assertEquals("Töpfern 09:00 – 10:30 Uhr Werkraum", find.dates[1].source)
    }

    @Test fun aDayWhoseNameWasNotReadStillHasItsColumn() {
        val find = PlanReader.read(PlanSheet.courses(monday = null).words, mondayMorning)!!
        assertEquals(wholeWeek, find.dates.described())
    }

    @Test fun shortDayNamesWorkToo() {
        val sheet = PlanSheet().above("KW 47", 1700)
            .column(0, "Mo", "Töpfern", "09:00 – 10:30")
            .column(1, "Di", "Chor", "14:00 – 15:00")
            .column(2, "Mi", "Malkurs", "10:00 – 11:30")
            .column(3, "Do", "Yoga", "18:00 Uhr")
        val find = PlanReader.read(sheet.words, mondayMorning)!!
        assertEquals(
            listOf(
                "2026-11-16 09:00-10:30 Töpfern | null",
                "2026-11-17 14:00-15:00 Chor | null",
                "2026-11-18 10:00-11:30 Malkurs | null",
                "2026-11-19 18:00-null Yoga | null",
            ),
            find.dates.described(),
        )
    }

    @Test fun anEnglishPlan() {
        val sheet = PlanSheet().above("Week 47", 1700)
            .column(0, "Monday", "Yoga", "2:30 pm – 3:15 pm", "Room 4")
            .column(1, "Tuesday", "Pottery", "10:00 am", "Hall B")
            .column(2, "Wednesday", "Choir", "6 pm")
        val find = PlanReader.read(sheet.words, mondayMorning)!!
        assertEquals(
            listOf(
                "2026-11-16 14:30-15:15 Yoga | Room 4",
                "2026-11-17 10:00-null Pottery | Hall B",
                "2026-11-18 18:00-null Choir | null",
            ),
            find.dates.described(),
        )
    }

    @Test fun whatIsOverIsLeftOut() {
        val find = PlanReader.read(PlanSheet.courses().words, wednesdayNoon)!!
        assertEquals(wholeWeek.filter { it >= "2026-11-18 12:00" }, find.dates.described())
    }

    @Test fun withoutAWeekEachDayIsTheNextOneToCome() {
        val find = PlanReader.read(PlanSheet.courses(week = null).words, wednesdayNoon)!!
        val days = find.dates.map { "${it.day} ${it.time} ${it.title}" }
        assertEquals(14, days.size)
        assertEquals("2026-11-18 16:45 Schwimmen", days.first())
        assertTrue("2026-11-23 07:45 Frühsport" in days)
        // This morning's is over: next week's.
        assertTrue("2026-11-25 10:00 Malkurs" in days)
        assertTrue("2026-11-20 15:30 Lesecafé" in days)
    }

    @Test fun aDateOverAColumnSetsTheWeek() {
        val sheet = PlanSheet()
            .column(0, "Montag 23.11.", "Töpfern", "09:00 – 10:30")
            .column(1, "Dienstag 24.11.", "Chor", "14:00 – 15:00")
            .column(2, "Mittwoch 25.11.", "Malkurs", "10:00 – 11:30")
        val find = PlanReader.read(sheet.words, wednesdayNoon)!!
        assertEquals(listOf("2026-11-23", "2026-11-24", "2026-11-25"), find.dates.map { it.day.toString() })
    }

    @Test fun misreadTitlesTakeTheSpellingThePlanHasMoreOften() {
        val sheet = PlanSheet().above("KW 47", 1700)
            .column(0, "Montag", "Offene Werkstatt", "08:30 – 10:00", "Raum 1")
            .column(1, "Dienstag", "0ffene Werkstatt", "08:30 – 10:00", "Raum 1")
            .column(2, "Mittwoch", "Offene Werkstatt", "08:30 – 10:00", "Raum 1")
            .column(3, "Donnerstag", "Offene Werkstatl", "08:30 – 10:00", "Raum 7")
            .column(4, "Freitag", "Offene Werkstatt", "08:30 – 10:00", "Raum 1")
        val find = PlanReader.read(sheet.words, mondayMorning)!!
        assertEquals(List(5) { "Offene Werkstatt" }, find.dates.map { it.title })
        // Another number is another room, however much more often "Raum 1" is written.
        assertEquals(listOf("Raum 1", "Raum 1", "Raum 1", "Raum 7", "Raum 1"), find.dates.map { it.detail })
    }

    @Test fun splitTimesAreJoinedAndALineReadTwiceIsOneAppointment() {
        val sheet = PlanSheet().above("KW 47", 1700)
            .column(0, "Montag", "Töpfern", "10:15 -1 ~1:30")
            .column(1, "Dienstag", "Offene Werkstatt", "0 ~8:30 – 10:00")
            .column(2, "Mittwoch", "Chor", "14:00 – 15:00")
            // A time without anything to go with it is no appointment.
            .column(3, "Donnerstag", "12:00")
        // The recognition read the time on Tuesday once more, a little lower.
        sheet.line("88:30-10:00", sheet.centre(1), 344)
        val find = PlanReader.read(sheet.words, mondayMorning)!!
        assertEquals(
            listOf(
                "2026-11-16 10:15-11:30 Töpfern | null",
                "2026-11-17 08:30-10:00 Offene Werkstatt | null",
                "2026-11-18 14:00-15:00 Chor | null",
            ),
            find.dates.described(),
        )
    }

    // ---------- Marked with a highlighter ----------

    private val cream = 0xFFE8DCC0.toInt()
    private val teal = 0xFF78BEAA.toInt()
    private val yellow = 0xFFEBEB78.toInt()
    private val ink = 0xFF303030.toInt()

    /** The plan's paper, a third of its size, with ink in each word and [marks] behind some. */
    private fun paper(sheet: PlanSheet, marks: List<Pair<List<OcrWord>, Int>>): Pixels {
        val w = 800
        val h = 300
        val argb = IntArray(w * h) { cream }
        for ((words, colour) in marks) {
            for (y in (words.minOf { it.top } - 3) / 3..(words.maxOf { it.bottom } + 3) / 3) {
                for (x in (words.minOf { it.left } - 4) / 3..(words.maxOf { it.right } + 4) / 3) argb[y * w + x] = colour
            }
        }
        for (word in sheet.words) {
            val y = (word.top + word.bottom) / 6
            for (x in word.left / 3 until word.right / 3 step 2) argb[y * w + x] = ink
        }
        return Pixels(w, h, argb, 3f)
    }

    @Test fun whatIsMarkedIsTicked() {
        val sheet = PlanSheet.courses()
        val pixels = paper(sheet, listOf(sheet.at(0, "Töpfern") to teal, sheet.at(2, "Malkurs") to teal, sheet.at(2, "07:45 Frühsport") to yellow))
        val find = PlanReader.read(sheet.words, mondayMorning, pixels)!!
        assertEquals(
            listOf("2026-11-16 Töpfern", "2026-11-18 Frühsport", "2026-11-18 Malkurs"),
            find.dates.filter { it.chosen }.map { "${it.day} ${it.title}" },
        )
    }

    @Test fun nothingMarkedTicksAll() {
        val sheet = PlanSheet.courses()
        val find = PlanReader.read(sheet.words, mondayMorning, paper(sheet, emptyList()))!!
        assertTrue(find.dates.all { it.chosen })
    }

    @Test fun whenOnlyWhatIsOverIsMarkedAllIsTicked() {
        val sheet = PlanSheet.courses()
        val pixels = paper(sheet, listOf(sheet.at(0, "Töpfern") to teal))
        val find = PlanReader.read(sheet.words, wednesdayNoon, pixels)!!
        assertTrue(find.dates.all { it.chosen })
    }

    // ---------- No plan ----------

    @Test fun aLetterIsNoPlan() {
        val sheet = PlanSheet()
        listOf(
            "Sehr geehrte Frau Muster,",
            "Ihr nächster Termin ist am Montag, 23.11.2026 um 10:30 Uhr.",
            "Bitte sagen Sie bis Freitag ab, wenn Sie verhindert sind.",
            "Mit freundlichen Grüßen",
            "Ihre Praxis am Markt",
        ).forEachIndexed { i, l -> sheet.line(l, 900, 200 + 40 * i) }
        assertNull(PlanReader.read(sheet.words, mondayMorning))
    }

    @Test fun whatLooksLikeAPlan() {
        assertTrue(PlanReader.looksLikePlan("Kursplan\nMontag Dienstag Mittwoch\n07:45 Frühsport"))
        assertTrue(PlanReader.looksLikePlan("Stundenplan\nMo Di Mi Do Fr\n08:00 Mathe"))
        assertTrue(PlanReader.looksLikePlan("KW 47\nMontag Donnerstag"))
        // Misread a little: still the days.
        assertTrue(PlanReader.looksLikePlan("Mortag Dienstag Mittwcch Donnerstag"))
        assertFalse(PlanReader.looksLikePlan("Ihr Termin am Montag, 23.11. um 10:30 Uhr. Bitte bis Freitag absagen."))
        assertFalse(PlanReader.looksLikePlan("So sind wir am Do und Fr erreichbar."))
    }
}
