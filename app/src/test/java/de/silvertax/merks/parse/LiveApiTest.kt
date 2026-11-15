package de.silvertax.merks.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Talks to the real Anthropic API. Skipped unless MERKS_LIVE=1, so normal builds never use the network.
 * With MERKS_API_KEY set as well, the paths that need a working key run too.
 */
class LiveApiTest {
    private val now = LocalDateTime.now()
    private val zone = ZoneId.of("Europe/Berlin")

    @Before fun onlyWhenAsked() = assumeTrue("set MERKS_LIVE=1 to run", System.getenv("MERKS_LIVE") == "1")

    @Test fun wrongKeyIsReportedAsSuch() {
        assertEquals(ModelCheck.KEY_INVALID, AiParser.checkModel("sk-ant-invalid", AiParser.DEFAULT_MODEL))
        val r = AiParser.parse("sk-ant-invalid", AiParser.DEFAULT_MODEL, "Morgen um 10 Zahnarzt", now, zone)
        assertEquals(AiProblem.KEY, r.error?.problem)
        assertFalse(r.modelUnknown)
    }

    @Test fun workingKey() {
        val key = System.getenv("MERKS_API_KEY")
        assumeTrue("set MERKS_API_KEY to run", !key.isNullOrBlank())
        assertEquals(ModelCheck.VALID, AiParser.checkModel(key!!, AiParser.DEFAULT_MODEL))
        assertEquals(ModelCheck.INVALID, AiParser.checkModel(key, "claude-sonet-5-5"))
        assertNotNull(AiParser.parse(key, AiParser.DEFAULT_MODEL, "Morgen um 10 Zahnarzt", now, zone).parsed)
    }

    /** A photographed appointment card, read by the real API. */
    @Test fun photoIsRead() {
        val key = System.getenv("MERKS_API_KEY")
        assumeTrue("set MERKS_API_KEY for this one", !key.isNullOrBlank())
        val jpeg = javaClass.getResource("/photos/de_card.jpg")!!.readBytes()
        val before = LocalDateTime.of(2026, 11, 15, 10, 0)
        val r = AiParser.readPhoto(key, AiParser.DEFAULT_MODEL, jpeg, before, zone)
        assertNotNull(r.error?.toString(), r.find)
        val date = r.find!!.dates.single()
        assertEquals(java.time.LocalDate.of(2026, 11, 23), date.day)
        assertEquals(java.time.LocalTime.of(10, 30), date.time)
    }
}
