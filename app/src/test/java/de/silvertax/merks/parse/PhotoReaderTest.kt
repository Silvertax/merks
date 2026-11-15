package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The texts in src/test/resources/photos are what the text recognition read from photographed
 * appointment cards, letters and a poster: crooked, unevenly lit and a little blurred, with the
 * mistakes that come with it ("\hr" for "Ihr", "pi" for "Di", "08,30" for "08:30").
 */
class PhotoReaderTest {
    private val now = LocalDateTime.of(2026, 11, 15, 1, 30) // a Sunday

    private fun photo(name: String): String =
        javaClass.getResource("/photos/$name.txt")!!.readText()

    private fun read(text: String, monthFirst: Boolean = false) = PhotoReader.read(text, now, monthFirst)

    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun appointmentCard() {
        val f = read(photo("de_card"))
        assertEquals(1, f.dates.size)
        assertEquals(at(2026, 11, 23), f.dates[0].day)
        assertEquals(t(10, 30), f.dates[0].time)
        assertTrue(f.dates[0].chosen)
        assertEquals("Zahnarztpraxis Dr. Anna Neupert", f.title)
        assertEquals("Bitte bringen Sie Ihre Versichertenkarte mit.", f.note)
        assertEquals("Mo, 23.11.2026 um 10:30 Uhr", f.dates[0].source)
    }

    @Test
    fun cardReadInColumns() {
        // The fallback reading splits the letterhead into pieces; what is left of it still makes a title.
        val f = read(photo("de_card_columns"))
        assertEquals(listOf(at(2026, 11, 23)), f.dates.map { it.day })
        assertEquals(t(10, 30), f.dates[0].time)
        assertEquals("Zahnarztpraxis", f.title)
    }

    @Test
    fun letterWithTheTimeOnTheNextLine() {
        val f = read(photo("de_letter"))
        // The letter's own date (10.11.) is neither an appointment nor in the future.
        assertEquals(1, f.dates.size)
        assertEquals(at(2026, 11, 30), f.dates[0].day)
        assertEquals(t(9, 30), f.dates[0].time)
        assertEquals("Stadtverwaltung Radebeul", f.title)
        assertEquals("In Zimmer 2.14 zur Abholung Ihres Ausweises\nBitte bringen Sie Ihren alten Ausweis mit.", f.note)
    }

    @Test
    fun severalTreatments() {
        for (name in listOf("de_physio", "de_physio_columns")) {
            val f = read(photo(name))
            assertEquals(name, listOf(at(2026, 11, 24), at(2026, 11, 26), at(2026, 12, 1)), f.dates.map { it.day })
            assertEquals(name, listOf(t(8, 15), t(8, 15), t(8, 30)), f.dates.map { it.time })
            assertTrue(name, f.dates.all { it.chosen })
            assertEquals(name, "Physiotherapie Lößnitz", f.title)
            assertNull(name, f.note)
        }
    }

    @Test
    fun englishCard() {
        val f = read(photo("en_card"))
        assertEquals(1, f.dates.size)
        assertEquals(at(2026, 11, 23), f.dates[0].day)
        assertEquals(t(10, 30), f.dates[0].time)
        assertEquals("Riverside Dental Care", f.title)
        assertEquals("Please arrive 10 minutes early", f.note)
    }

    @Test
    fun hairdresserWithEndTime() {
        val f = read(photo("de_hair"))
        assertEquals(1, f.dates.size)
        assertEquals(at(2026, 12, 2), f.dates[0].day)
        assertEquals(t(14), f.dates[0].time)
        assertEquals(t(15), f.dates[0].end)
        assertEquals("Friseur Haarscharf", f.title)
    }

    @Test
    fun doctorWithDateAndTimeLabels() {
        val f = read(photo("de_hausarzt"))
        assertEquals(1, f.dates.size)
        assertEquals(at(2026, 12, 8), f.dates[0].day)
        assertEquals(t(9, 15), f.dates[0].time)
        assertEquals("Hausarztpraxis Dr. Thomas Berger", f.title)
        assertEquals("Bitte nüchtern erscheinen (Blutabnahme).", f.note)
    }

    @Test
    fun posterWithDeadline() {
        val f = read(photo("de_poster"))
        assertEquals(listOf(at(2026, 12, 4), at(2026, 12, 9)), f.dates.map { it.day })
        val (deadline, event) = f.dates
        // "Anmeldung bis 4. Dezember" is shown, but only the afternoon itself is ticked.
        assertFalse(deadline.chosen)
        assertNull(deadline.time)
        assertTrue(event.chosen)
        assertEquals(t(14, 30), event.time)
        assertEquals(t(17), event.end)
        assertEquals("Seniorentreff Radebeul-Ost", f.title)
        assertEquals("Im Gemeindesaal\nAnmeldung bis 4. Dezember bei Frau Krause", f.note)
    }

    @Test
    fun whatTheAppointmentIsFor() {
        val f = read(photo("de_optiker"))
        assertEquals(listOf(at(2026, 12, 3)), f.dates.map { it.day })
        assertEquals(t(16), f.dates[0].time)
        assertEquals("Sehstärkenbestimmung – Augenoptik Lindner", f.title)
    }

    @Test
    fun wholePageLetter() {
        val f = read(photo("de_a4"))
        // "Dresden, 9. November 2026" is the letter's date; the appointment is in the text.
        assertEquals(1, f.dates.size)
        assertEquals(at(2026, 12, 17), f.dates[0].day)
        assertEquals(t(11, 20), f.dates[0].time)
        assertEquals("Herzsprechstunde – Universitätsklinikum Dresden", f.title)
        assertEquals(
            "Ort: Haus 25, Ebene 1, Anmeldung Kardiologie\nBitte bringen Sie Ihre Medikamentenliste und den Überweisungsschein mit.",
            f.note,
        )
    }

    @Test
    fun datesThatAreNoAppointments() {
        val f = read(
            """
            Radebeul, 16.11.2026
            Patientin: Gisela L., geb. 12.03.1948
            Versichertenkarte gültig bis 31.12.2027
            Stand: 01.11.2026
            Ihr Termin: 20.11.2026, 9 Uhr
            """.trimIndent(),
        )
        assertEquals(listOf(at(2026, 11, 20)), f.dates.map { it.day })
        assertEquals(t(9), f.dates[0].time)
    }

    @Test
    fun numbersThatAreNoTimes() {
        val f = read("Bitte in Zimmer 2.14, Raum 1 am Ende des Flurs.\nTel. 0351 458-1234, Fax 0351/12 34\nKundennummer 2024-118")
        assertTrue(f.dates.isEmpty())
    }

    @Test
    fun pastAppointmentsStayOut() {
        assertTrue(read("Ihr Termin war am 10.11.2026 um 10 Uhr.").dates.isEmpty())
        // Without a year, a day a few weeks ago is an old card, not one for next year.
        assertTrue(read("Termin: Di 10.11. 10:00 Uhr").dates.isEmpty())
        // Long ago means next year.
        assertEquals(listOf(at(2027, 1, 12)), read("Termin: 12.01. 10:00 Uhr").dates.map { it.day })
    }

    @Test
    fun weekdayDecidesTheYear() {
        // 4 January is a Monday in 2027, not in 2026.
        assertEquals(listOf(at(2027, 1, 4)), read("Mo 04.01. 9:00 Uhr").dates.map { it.day })
    }

    @Test
    fun dateWithoutItsLastDot() {
        val f = read("Ihr Termin am 3.12 um 16 Uhr")
        assertEquals(listOf(at(2026, 12, 3)), f.dates.map { it.day })
        assertEquals(t(16), f.dates[0].time)
    }

    @Test
    fun severalDays() {
        val f = read("Kur vom 4.1. bis 25.1.2027 in Bad Elster")
        assertEquals(1, f.dates.size)
        assertEquals(at(2027, 1, 4), f.dates[0].day)
        assertEquals(at(2027, 1, 25), f.dates[0].lastDay)
        assertNull(f.dates[0].time)
        val g = read("Weihnachtsmarkt 20. bis 23. Dezember")
        assertEquals(at(2026, 12, 20), g.dates.single().day)
        assertEquals(at(2026, 12, 23), g.dates.single().lastDay)
    }

    @Test
    fun todayAndTomorrowOnlyWithATime() {
        val f = read("Guten Morgen!\nHeute 15:30 Uhr Kaffee bei Inge")
        assertEquals(listOf(at(2026, 11, 15)), f.dates.map { it.day })
        assertEquals(t(15, 30), f.dates[0].time)
        assertTrue(read("Morgen wird es kalt.").dates.isEmpty())
    }

    @Test
    fun americanDates() {
        val f = read("Appointment: 11/23/2026 2:30 PM", monthFirst = true)
        assertEquals(listOf(at(2026, 11, 23)), f.dates.map { it.day })
        assertEquals(t(14, 30), f.dates[0].time)
        val g = read("Your appointment is on 23/11/2026 at 9 am.")
        assertEquals(listOf(at(2026, 11, 23)), g.dates.map { it.day })
        assertEquals(t(9), g.dates[0].time)
    }

    @Test
    fun germanAmIsNoMorning() {
        val f = read("Termin: 30.11.2026 12:30 am Eingang B")
        assertEquals(t(12, 30), f.dates.single().time)
    }

    @Test
    fun misreadDigits() {
        val f = read("Termin: 1O.12.2O26 um 1l:3O Uhr")
        assertEquals(listOf(at(2026, 12, 10)), f.dates.map { it.day })
        assertEquals(t(11, 30), f.dates[0].time)
    }

    @Test
    fun nothingOnThePhoto() {
        val f = read("")
        assertTrue(f.dates.isEmpty())
        assertEquals("", f.title)
        assertNull(f.note)
    }

    @Test
    fun recipientIsNoTitle() {
        val f = read("Frau\nGisela Lukomski\nMeißner Straße 101\n01445 Radebeul\nIhr Termin: 17.12.2026, 11:20 Uhr")
        assertEquals("", f.title)
        val g = read("Herrn Paul Krause\nAugenarztpraxis Dr. Weiß\nTermin 17.12.2026 11:20 Uhr")
        assertEquals("Augenarztpraxis Dr. Weiß", g.title)
    }
}
