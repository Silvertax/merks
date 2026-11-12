package de.silvertax.merks.parse

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Outcome of an AI request: either [parsed] or a short [error] to show in the settings.
 * [modelUnknown] is set when the API does not know the model name.
 */
data class AiResult(val parsed: Parsed?, val error: String?, val modelUnknown: Boolean = false)

enum class ModelCheck { VALID, INVALID, KEY_INVALID, UNKNOWN }

/** Sorts a dictated memo with the Anthropic API. Never throws, so the caller can fall back to the rules. */
object AiParser {
    /** The settings texts name this model as "Haiku 5.5". */
    const val DEFAULT_MODEL = "claude-haiku-5-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODELS_ENDPOINT = "https://api.anthropic.com/v1/models"

    // Room for models that think before they answer; the JSON itself is tiny.
    private const val MAX_TOKENS = 4000
    private val MODEL_NAME = Regex("claude-[a-z0-9][a-z0-9._:@-]{0,90}")

    /** Tidies common spellings: "Sonnet 5.5" and "claude_sonnet_5.5" both become "claude-sonnet-5-5". */
    fun normalizeModel(input: String): String {
        val m = input.trim().lowercase(Locale.ROOT)
            .replace(Regex("[\\s._]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return when {
            m.isEmpty() -> DEFAULT_MODEL
            m.startsWith("claude") -> m
            else -> "claude-$m"
        }
    }

    /** Rules out names that cannot be a model at all, without asking the network. */
    fun isPlausibleModelName(model: String): Boolean = MODEL_NAME.matches(model)

    fun parse(apiKey: String, model: String, text: String, now: LocalDateTime, zone: ZoneId): AiResult {
        if (!isPlausibleModelName(model)) return AiResult(null, "Ungültiger Modellname.", modelUnknown = true)
        return try {
            val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val system = """
                You sort a dictated voice memo into a calendar appointment, a reminder or a plain note.
                Current local time: ${now.withSecond(0).withNano(0)} ($weekday), time zone ${zone.id}.
                The memo comes from speech recognition, so expect filler words and small errors.
                Reply with one JSON object and nothing else:
                {"type":"appointment"|"reminder"|"note","title":string,"start":string|null,"end":string|null,"all_day":boolean,"repeat":null|{"unit":"day"|"week"|"month"|"year","every":number}}
                - appointment: an event at a date or time. reminder: the speaker wants to be reminded of something at a time. note: everything else, or no usable date.
                - title: short and clear, in the memo's language, without the date, time or repetition.
                - start/end: local time as YYYY-MM-DDTHH:MM, resolved from relative phrases. end only if stated.
                - all_day: true when a date is given without a time (start then uses T00:00).
                - repeat: only when the memo says it recurs ("every Monday" -> unit week, every 1; "every two weeks" -> unit week, every 2). start is then the first occurrence.
                - Never pick a start in the past; move it to the next matching day.
            """.trimIndent()
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", MAX_TOKENS)
                .put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))

            val conn = post(apiKey, body, readTimeout = 30000)
            val code = conn.responseCode
            if (code != 200) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                AiResult(null, friendlyError(code, detail), modelUnknown = code == 404 && isNotFound(detail))
            } else {
                val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val reply = replyText(response)
                when {
                    reply.isEmpty() && response.optString("stop_reason") == "max_tokens" ->
                        AiResult(null, "Das Modell hat nur nachgedacht und keine Antwort geliefert.")
                    reply.isEmpty() -> AiResult(null, "Die Antwort enthielt keinen Text.")
                    else -> fromJson(reply)?.let { AiResult(it, null) } ?: AiResult(null, "Antwort nicht lesbar.")
                }
            }
        } catch (e: Exception) {
            AiResult(null, "Keine Verbindung zu Anthropic (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /**
     * The answer text. Newer models put a "thinking" block first, so the text is not always block 0:
     * every block of type "text" is collected.
     */
    fun replyText(response: JSONObject): String {
        val content = response.optJSONArray("content") ?: return ""
        val out = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") out.append(block.optString("text"))
        }
        return out.toString().trim()
    }

    /**
     * Whether key and model work. The model list answers for free; anything but a clear yes there is
     * decided by a tiny message on the same route the app really uses, so a valid model is never
     * reported as invalid by mistake.
     */
    fun checkModel(apiKey: String, model: String): ModelCheck {
        if (!isPlausibleModelName(model)) return ModelCheck.INVALID
        return try {
            val get = URL("$MODELS_ENDPOINT/$model").openConnection() as HttpURLConnection
            get.connectTimeout = 6000
            get.readTimeout = 10000
            get.setRequestProperty("x-api-key", apiKey)
            get.setRequestProperty("anthropic-version", "2023-06-01")
            if (get.responseCode == 200) ModelCheck.VALID else confirmWithMessage(apiKey, model)
        } catch (e: Exception) {
            ModelCheck.UNKNOWN
        }
    }

    private fun confirmWithMessage(apiKey: String, model: String): ModelCheck {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ok")))
        val conn = post(apiKey, body, readTimeout = 20000)
        val code = conn.responseCode
        val detail = if (code == 200) "" else conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return when {
            code == 200 -> ModelCheck.VALID
            code == 404 && isNotFound(detail) -> ModelCheck.INVALID
            code == 401 || code == 403 -> ModelCheck.KEY_INVALID
            else -> ModelCheck.UNKNOWN
        }
    }

    private fun post(apiKey: String, body: JSONObject, readTimeout: Int): HttpURLConnection {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6000
        conn.readTimeout = readTimeout
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return conn
    }

    private fun isNotFound(body: String): Boolean = try {
        JSONObject(body).getJSONObject("error").getString("type") == "not_found_error"
    } catch (e: Exception) {
        false
    }

    /** A short German text instead of the raw API error. */
    internal fun friendlyError(code: Int, body: String): String {
        val message = apiMessage(body)
        return when {
            code == 401 -> "Schlüssel ungültig (HTTP 401)."
            code == 403 -> "Der Schlüssel hat keine Berechtigung (HTTP 403)."
            code == 404 && isNotFound(body) -> "Modell nicht gefunden (HTTP 404)."
            message.contains("credit balance", ignoreCase = true) -> "Kein Guthaben im Anthropic-Konto (HTTP $code)."
            code == 429 -> "Zu viele Anfragen, bitte kurz warten (HTTP 429)."
            code == 529 -> "Anthropic ist gerade überlastet (HTTP 529)."
            code >= 500 -> "Störung bei Anthropic (HTTP $code)."
            else -> "HTTP $code: $message"
        }
    }

    /** The human-readable part of an API error body. */
    private fun apiMessage(body: String): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        body.take(200)
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
        if (kind == Kind.NOTE || start == null) return Parsed(Kind.NOTE, title, null, null, false)
        return Parsed(kind, title, start, dateTime(o, "end"), kind == Kind.APPOINTMENT && o.optBoolean("all_day"), repeat(o))
    }

    private fun repeat(o: JSONObject): Repeat? {
        val r = o.optJSONObject("repeat") ?: return null
        val unit = when (r.optString("unit")) {
            "day" -> RepeatUnit.DAY
            "week" -> RepeatUnit.WEEK
            "month" -> RepeatUnit.MONTH
            "year" -> RepeatUnit.YEAR
            else -> return null
        }
        val every = r.optInt("every", 1)
        return if (every in 1..1000) Repeat(unit, every) else null
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
