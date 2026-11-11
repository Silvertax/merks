package de.silvertax.merks.parse

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Sorts a dictated memo with the Anthropic API. Returns null on any failure so the caller can fall back. */
object AiParser {
    private const val MODEL = "claude-haiku-5-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    fun parse(apiKey: String, text: String, now: LocalDateTime, zone: ZoneId): Parsed? = try {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val system = """
            You sort a dictated voice memo into a calendar appointment, a reminder or a plain note.
            Current local time: ${now.withSecond(0).withNano(0)} ($weekday), time zone ${zone.id}.
            The memo comes from speech recognition, so expect filler words and small errors.
            Reply with one JSON object and nothing else:
            {"type":"appointment"|"reminder"|"note","title":string,"start":string|null,"end":string|null,"all_day":boolean}
            - appointment: an event at a date or time. reminder: the speaker wants to be reminded of something at a time. note: everything else, or no usable date.
            - title: short and clear, in the memo's language, without the date or time.
            - start/end: local time as YYYY-MM-DDTHH:MM, resolved from relative phrases. end only if stated.
            - all_day: true when a date is given without a time (start then uses T00:00).
            - Never pick a start in the past; move it to the next matching day.
        """.trimIndent()
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) {
            null
        } else {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val reply = JSONObject(response).getJSONArray("content").getJSONObject(0).getString("text")
            fromJson(reply)
        }
    } catch (e: Exception) {
        null
    }

    private fun fromJson(reply: String): Parsed? {
        val from = reply.indexOf('{')
        val to = reply.lastIndexOf('}')
        if (from < 0 || to <= from) return null
        val o = JSONObject(reply.substring(from, to + 1))
        val start = dateTime(o, "start")
        val kind = when (o.optString("type")) {
            "appointment" -> Kind.APPOINTMENT
            "reminder" -> Kind.REMINDER
            else -> Kind.NOTE
        }
        val title = o.optString("title").trim()
        if (title.isEmpty()) return null
        if (kind != Kind.NOTE && start == null) return Parsed(Kind.NOTE, title, null, null, false)
        return Parsed(kind, title, start, dateTime(o, "end"), kind == Kind.APPOINTMENT && o.optBoolean("all_day"))
    }

    private fun dateTime(o: JSONObject, key: String): LocalDateTime? {
        if (o.isNull(key)) return null
        return try {
            LocalDateTime.parse(o.getString(key))
        } catch (e: Exception) {
            null
        }
    }
}
