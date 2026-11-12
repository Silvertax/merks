package de.silvertax.merks.parse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
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
}
