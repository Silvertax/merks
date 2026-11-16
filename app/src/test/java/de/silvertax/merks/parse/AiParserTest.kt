package de.silvertax.merks.parse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AiParserTest {
    @Test fun plainTextBlock() {
        val r = JSONObject("""{"content":[{"type":"text","text":"{\"type\":\"note\"}"}]}""")
        assertEquals("""{"type":"note"}""", AiParser.replyText(r))
    }

    /** Models that think first put a block without "text" in front; this used to fail with "No value for text". */
    @Test fun thinkingBlockFirst() {
        val r = JSONObject(
            """{"content":[{"type":"thinking","thinking":"","signature":"abc"},{"type":"text","text":" {\"a\":1} "}]}""",
        )
        assertEquals("""{"a":1}""", AiParser.replyText(r))
    }

    @Test fun onlyThinkingGivesEmptyText() {
        val r = JSONObject("""{"content":[{"type":"thinking","thinking":"","signature":"abc"}],"stop_reason":"max_tokens"}""")
        assertEquals("", AiParser.replyText(r))
    }

    @Test fun missingContent() = assertEquals("", AiParser.replyText(JSONObject("{}")))

    @Test fun spellingsBecomeModelIds() {
        assertEquals("claude-sonnet-5-5", AiParser.normalizeModel("Sonnet 5.5"))
        assertEquals("claude-sonnet-5-5", AiParser.normalizeModel("Claude Sonnet 5.5"))
        assertEquals("claude-sonnet-5-5", AiParser.normalizeModel("claude_sonnet_5.5"))
        assertEquals("claude-opus-5-5", AiParser.normalizeModel("  claude-opus-5-5 "))
        assertEquals("claude-3-5-haiku-20241022", AiParser.normalizeModel("claude-3-5-haiku-20241022"))
        assertEquals(AiParser.DEFAULT_MODEL, AiParser.normalizeModel("   "))
    }

    @Test fun plausibleNames() {
        assertTrue(AiParser.isPlausibleModelName("claude-haiku-5-5"))
        assertTrue(AiParser.isPlausibleModelName("claude-3-5-haiku-20241022"))
        assertFalse(AiParser.isPlausibleModelName("claude"))
        assertFalse(AiParser.isPlausibleModelName("gpt-5"))
        assertFalse(AiParser.isPlausibleModelName("claude-sonnet ä"))
    }

    /** A name that cannot exist is reported as unknown without any network request. */
    @Test fun implausibleNameIsUnknownOffline() {
        val r = AiParser.parse("key", "claude", "Morgen um 10", LocalDateTime.of(2026, 11, 12, 8, 0), ZoneId.of("Europe/Berlin"))
        assertTrue(r.modelUnknown)
    }

    @Test fun errorsAreSorted() {
        val notFound = """{"type":"error","error":{"type":"not_found_error","message":"model: claude-sonet-5-5"}}"""
        assertEquals(AiProblem.MODEL_NOT_FOUND, AiParser.classify(404, notFound).problem)
        assertEquals(AiProblem.KEY, AiParser.classify(401, "{}").problem)
        val credit = """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""
        assertEquals(AiError(AiProblem.NO_CREDIT, 400, "Your credit balance is too low to access the Anthropic API."), AiParser.classify(400, credit))
        assertEquals(AiProblem.OVERLOADED, AiParser.classify(529, "{}").problem)
        assertEquals(AiProblem.HTTP, AiParser.classify(400, """{"error":{"type":"x","message":"odd"}}""").problem)
    }

    // ---------- Photos read by the AI ----------

    private val photoNow = LocalDateTime.of(2026, 11, 15, 1, 30)

    @Test fun photoReplyBecomesDates() {
        val reply = """Here it is:
            {"title":"Physiotherapie Lößnitz","note":"Handtuch mitbringen","appointments":[
            {"start":"2026-11-24T08:15","end":null,"all_day":false,"source":"Di 24.11. 08:15 Uhr"},
            {"start":"2026-11-10T08:15","end":null,"all_day":false,"source":"over"},
            {"start":"2026-12-20T00:00","end":"2026-12-23T00:00","all_day":true,"source":"20.-23.12."},
            {"start":"2026-11-26T14:00","end":"2026-11-26T15:30","all_day":false,"source":"Do 26.11. 14-15:30"}]}"""
        val f = AiParser.photoFromJson(reply, photoNow)!!
        assertEquals("Physiotherapie Lößnitz", f.title)
        assertEquals("Handtuch mitbringen", f.note)
        assertTrue(f.byAi)
        assertEquals("", f.text)
        // What is over stays out; the rest comes in time order.
        assertEquals(listOf(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 20)), f.dates.map { it.day })
        assertEquals(LocalTime.of(8, 15), f.dates[0].time)
        assertEquals("Di 24.11. 08:15 Uhr", f.dates[0].source)
        assertEquals(LocalTime.of(15, 30), f.dates[1].end)
        assertNull(f.dates[2].time)
        assertEquals(LocalDate.of(2026, 12, 23), f.dates[2].lastDay)
        assertTrue(f.dates.all { it.chosen })
    }

    /**
     * A weekly plan of courses: every entry has its own title and room, the reader marked some of
     * them, and there are more than a photo of a letter would ever hold.
     */
    @Test fun aWeeklyPlanKeepsEveryEntryWithItsOwnTitle() {
        val entries = (0 until 20).joinToString(",\n") { i ->
            val day = 16 + i / 4
            val hour = 8 + 2 * (i % 4)
            val marked = i % 3 == 0
            """{"title":"Kurs ${i + 1}","start":"2026-11-${day}T${"%02d".format(hour)}:00","end":"2026-11-${day}T${"%02d".format(hour)}:45","all_day":false,"detail":"Raum ${100 + i}","marked":$marked,"source":"Kurs ${i + 1} $hour:00"}"""
        }
        val reply = """{"title":"Kursplan Familienzentrum","note":"Hallenschuhe mitbringen","appointments":[
            {"title":"Kursplan Familienzentrum","start":"2026-11-15T09:00","end":null,"all_day":false,"detail":null,"marked":true,"source":"Sonntag 9:00"},
            $entries]}"""
        val f = AiParser.photoFromJson(reply, photoNow)!!
        assertEquals("Kursplan Familienzentrum", f.title)
        // All of them, not just the first twelve.
        assertEquals(21, f.dates.size)
        // An entry named like the plan takes the plan's title.
        assertNull(f.dates[0].title)
        assertEquals("Kurs 1", f.dates[1].title)
        assertEquals("Raum 100", f.dates[1].detail)
        assertEquals(LocalTime.of(8, 45), f.dates[1].end)
        // Only the marked entries are ticked; the others are there to tick by hand.
        assertTrue(f.dates[1].chosen)
        assertFalse(f.dates[2].chosen)
        assertEquals(1 + 7, f.dates.count { it.chosen })
        assertEquals("Kurs 1", f.dates[1].toParsed(f.title).title)
        assertEquals(f.title, f.dates[0].toParsed(f.title).title)
    }

    /** A plan where the reader marked nothing: everything is ticked. */
    @Test fun nothingMarkedMeansAll() {
        val reply = """{"title":"Plan","note":null,"appointments":[
            {"title":"A","start":"2026-11-16T08:00","end":null,"all_day":false,"detail":null,"marked":false,"source":"A"},
            {"title":"B","start":"2026-11-17T08:00","end":null,"all_day":false,"detail":null,"marked":false,"source":"B"}]}"""
        assertTrue(AiParser.photoFromJson(reply, photoNow)!!.dates.all { it.chosen })
    }

    @Test fun photoReplyWithoutAppointments() {
        val f = AiParser.photoFromJson("""{"title":"","note":null,"appointments":[]}""", photoNow)!!
        assertTrue(f.dates.isEmpty())
        assertNull(f.note)
        assertNull(AiParser.photoFromJson("Sorry, I cannot read this photo.", photoNow))
        assertNull(AiParser.photoFromJson("""{"appointments": [{"start": }""", photoNow))
    }

    @Test fun photoDateBecomesAnAppointment() {
        val timed = PhotoDate(LocalDate.of(2026, 11, 26), LocalTime.of(14, 0), LocalTime.of(15, 30)).toParsed("Friseur")
        assertEquals(Kind.APPOINTMENT, timed.kind)
        assertEquals(LocalDateTime.of(2026, 11, 26, 14, 0), timed.start)
        assertEquals(LocalDateTime.of(2026, 11, 26, 15, 30), timed.end)
        assertFalse(timed.allDay)
        val days = PhotoDate(LocalDate.of(2026, 12, 20), lastDay = LocalDate.of(2026, 12, 23)).toParsed("Weihnachtsmarkt")
        assertTrue(days.allDay)
        assertEquals(LocalDateTime.of(2026, 12, 23, 0, 0), days.end)
    }
}
