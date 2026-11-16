package de.silvertax.merks.parse

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What went wrong with an AI request; the app turns it into a text in its language. */
enum class AiProblem {
    BAD_MODEL_NAME, KEY, FORBIDDEN, MODEL_NOT_FOUND, NO_CREDIT, RATE_LIMIT, OVERLOADED, SERVER, HTTP,
    NO_CONNECTION, ONLY_THINKING, NO_TEXT, UNREADABLE,
}

data class AiError(val problem: AiProblem, val code: Int = 0, val detail: String = "")

/** What the app needs to know of any AI request. [modelUnknown]: the API does not know the model name. */
interface AiOutcome {
    val error: AiError?
    val modelUnknown: Boolean
    val ok: Boolean
}

/** Outcome of an AI request: either [parsed] or an [error] to show in the settings. */
data class AiResult(val parsed: Parsed?, override val error: AiError?, override val modelUnknown: Boolean = false) : AiOutcome {
    override val ok get() = parsed != null
}

/** Outcome of reading a photo with the AI: what it found, or an [error]. */
data class AiPhotoResult(val find: PhotoFind?, override val error: AiError?, override val modelUnknown: Boolean = false) : AiOutcome {
    override val ok get() = find != null
}

enum class ModelCheck { VALID, INVALID, KEY_INVALID, UNKNOWN }

/** Sorts a dictated memo with the Anthropic API. Never throws, so the caller can fall back to the rules. */
object AiParser {
    /** The settings texts name this model as "Haiku 5.5". */
    const val DEFAULT_MODEL = "claude-haiku-5-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODELS_ENDPOINT = "https://api.anthropic.com/v1/models"

    // Room for models that think before they answer; the JSON itself is tiny.
    private const val MAX_TOKENS = 4000

    /** A weekly plan with a few dozen appointments is a long answer; cut off, it would be lost. */
    private const val MAX_TOKENS_LIST = 8000
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

    /** The reply to [body]: its text, or what went wrong. Never throws. */
    private class Reply(val text: String?, val error: AiError?, val modelUnknown: Boolean = false)

    private fun ask(apiKey: String, body: JSONObject, readTimeout: Int): Reply = try {
        val conn = post(apiKey, body, readTimeout)
        val code = conn.responseCode
        if (code != 200) {
            val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Reply(null, classify(code, detail), modelUnknown = code == 404 && isNotFound(detail))
        } else {
            val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val reply = replyText(response)
            when {
                reply.isEmpty() && response.optString("stop_reason") == "max_tokens" -> Reply(null, AiError(AiProblem.ONLY_THINKING))
                reply.isEmpty() -> Reply(null, AiError(AiProblem.NO_TEXT))
                else -> Reply(reply, null)
            }
        }
    } catch (e: Exception) {
        Reply(null, AiError(AiProblem.NO_CONNECTION, detail = e.message ?: e.javaClass.simpleName))
    }

    /**
     * Reads photos of an appointment card, a letter or a poster, handwriting included, or the pages
     * of a letter, and finds the appointments on them. [jpegs] are the pictures; they go to the API
     * and nowhere else.
     */
    fun readPhoto(apiKey: String, model: String, jpegs: List<ByteArray>, now: LocalDateTime, zone: ZoneId): AiPhotoResult {
        if (!isPlausibleModelName(model)) return AiPhotoResult(null, AiError(AiProblem.BAD_MODEL_NAME), modelUnknown = true)
        val content = JSONArray()
        for (jpeg in jpegs) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", java.util.Base64.getEncoder().encodeToString(jpeg)),
                ),
            )
        }
        val question = if (jpegs.size > 1) "Which appointments are on these pictures? They belong together, e.g. the pages of one letter." else "Which appointments are on this photo?"
        content.put(JSONObject().put("type", "text").put("text", question))
        return findAppointments(apiKey, model, system("a photo of an appointment card, a letter, a weekly plan, a poster or a handwritten note", "photo", now, zone), content, now)
    }

    /** Finds the appointments in a text: a message, an e-mail, a letter. It goes to the API and nowhere else. */
    fun readText(apiKey: String, model: String, text: String, now: LocalDateTime, zone: ZoneId): AiPhotoResult {
        if (!isPlausibleModelName(model)) return AiPhotoResult(null, AiError(AiProblem.BAD_MODEL_NAME), modelUnknown = true)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Which appointments are in this text?\n\n$text"))
        return findAppointments(apiKey, model, system("a text (a message, an e-mail or a letter)", "text", now, zone), content, now)
    }

    private fun system(what: String, it: String, now: LocalDateTime, zone: ZoneId): String {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return """
            You read $what and find the appointments in it, for a calendar.
            Current local time: ${now.withSecond(0).withNano(0)} ($weekday), time zone ${zone.id}.
            Reply with one JSON object and nothing else, compact on one line, without indentation:
            {"title":string,"note":string|null,"appointments":[{"title":string,"start":string,"end":string,"all_day":boolean,"detail":string,"marked":boolean,"source":string}]}
            In an appointment "start" and "marked" are required: leave out every other field that is null or false.
            - appointments: each date the reader is to attend (a visit, a treatment, a group, a course, an event), in time order. List every one, also in a table, a timetable or a weekly plan with dozens of entries; never move an appointment into the note. Several treatment dates on one card are several appointments. Leave out the letter's own date, birth dates, validity or expiry dates, opening hours and dates that are over. A deadline ("register by ...") is no appointment: put it into the note.
            - A weekly plan names weekdays: take the dates from the calendar week written on it (e.g. "KW 12" is ISO week 12), otherwise from the current or coming week. What is over today is left out, what is still to come today is kept.
            - start/end: local time as YYYY-MM-DDTHH:MM. A date without a year is the next such date from now; words like "tomorrow" or "next Tuesday" count from now. end only if an end time is written. all_day: true when no time is given (start then uses T00:00); over several days, end is the last day at T00:00.
            - title (top level): short and clear, in the language of the $it: what, and with whom or where, e.g. "Zahnarzt Dr. Neupert" or "Physiotherapie"; for a plan its heading. No date or time.
            - title (of an appointment): when the appointments differ, as in a plan of several groups or treatments, each one's own short title as written, e.g. "Yoga" or "Töpfern"; null when it is the same as the top-level title.
            - detail: the room or place written with this appointment and a short remark, e.g. "Raum 12" or "Turnschuhe mitbringen", in at most 60 characters; null if there is none.
            - marked: look at each entry on its own. true if the reader marked it by hand: a coloured highlighter stroke over or behind its words (often yellow, green, blue or pink), a circle around it, a tick or an underline. false if it is plain print on plain paper. On a plan people mark the entries that are theirs, usually only some of them, so check every entry; if nothing at all is marked, true for all.
            - note: what to bring or to know for all of them, e.g. to come fasting, in the language of the $it, at most two short lines; null if there is nothing.
            - source: the words in the $it the date was read from, as written, at most 60 characters. Leave it out for an appointment with its own title.
            - If nothing is readable or there is no appointment in it, reply with an empty list.
        """.trimIndent()
    }

    private fun findAppointments(apiKey: String, model: String, system: String, content: JSONArray, now: LocalDateTime): AiPhotoResult {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS_LIST)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val reply = ask(apiKey, body, readTimeout = 120000)
        val text = reply.text ?: return AiPhotoResult(null, reply.error, reply.modelUnknown)
        return photoFromJson(text, now)?.let { AiPhotoResult(it, null) } ?: AiPhotoResult(null, AiError(AiProblem.UNREADABLE))
    }

    /** The photo's appointments from the reply; what is over or unreadable is left out. */
    internal fun photoFromJson(reply: String, now: LocalDateTime): PhotoFind? = try {
        val from = reply.indexOf('{')
        val to = reply.lastIndexOf('}')
        if (from < 0 || to <= from) {
            null
        } else {
            val o = try {
                JSONObject(reply.substring(from, to + 1))
            } catch (e: Exception) {
                // A long list cut off in the middle: the appointments that are complete still count.
                JSONObject(reply.substring(from, to + 1) + "]}")
            }
            val list = o.optJSONArray("appointments") ?: JSONArray()
            val top = o.optString("title").trim().take(80)
            val dates = (0 until list.length()).mapNotNull { i ->
                val a = list.optJSONObject(i) ?: return@mapNotNull null
                val start = dateTime(a, "start") ?: return@mapNotNull null
                val end = dateTime(a, "end")
                val allDay = a.optBoolean("all_day")
                val date = if (allDay) {
                    PhotoDate(start.toLocalDate(), lastDay = end?.toLocalDate()?.takeIf { it.isAfter(start.toLocalDate()) })
                } else {
                    PhotoDate(start.toLocalDate(), start.toLocalTime(), end?.takeIf { it.toLocalDate() == start.toLocalDate() && it.isAfter(start) }?.toLocalTime())
                }
                val over = if (allDay) date.day.isBefore(now.toLocalDate()) else start.isBefore(now)
                if (over) return@mapNotNull null
                date.copy(
                    source = a.optString("source").trim().take(100),
                    title = text(a, "title")?.take(80)?.takeIf { it != top },
                    detail = text(a, "detail")?.take(100),
                    // Unmarked entries next to marked ones are shown, but not ticked.
                    chosen = a.optBoolean("marked", true),
                )
            }.distinct().sortedWith(compareBy({ it.day }, { it.time })).take(PhotoReader.MAX_LISTED)
                // Nothing marked as the reader's: then all of them are.
                .let { found -> if (found.none { it.chosen }) found.map { it.copy(chosen = true) } else found }
            PhotoFind(
                title = top,
                note = if (o.isNull("note")) null else o.optString("note").trim().ifEmpty { null },
                dates = dates,
                byAi = true,
            )
        }
    } catch (e: Exception) {
        null
    }

    fun parse(apiKey: String, model: String, text: String, now: LocalDateTime, zone: ZoneId): AiResult {
        if (!isPlausibleModelName(model)) return AiResult(null, AiError(AiProblem.BAD_MODEL_NAME), modelUnknown = true)
        return try {
            val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val system = """
                You sort a dictated voice memo into a calendar appointment, a reminder or a plain note.
                Current local time: ${now.withSecond(0).withNano(0)} ($weekday), time zone ${zone.id}.
                The memo comes from speech recognition, so expect filler words and small errors.
                Reply with one JSON object and nothing else:
                {"type":"appointment"|"reminder"|"note","title":string,"start":string|null,"end":string|null,"all_day":boolean,"repeat":null|{"unit":"day"|"week"|"month"|"year"|"workday","every":number}}
                - appointment: an event at a date or time. reminder: the speaker wants to be reminded of something at a time. note: everything else, or no usable date.
                - title: short and clear, in the memo's language, without the date, time or repetition.
                - start/end: local time as YYYY-MM-DDTHH:MM, resolved from relative phrases. end only if stated.
                - all_day: true when a date is given without a time (start then uses T00:00). Over several days ("from the 20th to the 23rd"), end is the last day at T00:00.
                - repeat: only when the memo says it recurs ("every Monday" -> unit week, every 1; "every two weeks" -> unit week, every 2; "every weekday" or "Monday to Friday" -> unit workday, every 1). start is then the first occurrence.
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
                AiResult(null, classify(code, detail), modelUnknown = code == 404 && isNotFound(detail))
            } else {
                val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val reply = replyText(response)
                when {
                    reply.isEmpty() && response.optString("stop_reason") == "max_tokens" ->
                        AiResult(null, AiError(AiProblem.ONLY_THINKING))
                    reply.isEmpty() -> AiResult(null, AiError(AiProblem.NO_TEXT))
                    else -> fromJson(reply)?.let { AiResult(it, null) } ?: AiResult(null, AiError(AiProblem.UNREADABLE))
                }
            }
        } catch (e: Exception) {
            AiResult(null, AiError(AiProblem.NO_CONNECTION, detail = e.message ?: e.javaClass.simpleName))
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

    /** Sorts an API error into the cases the app explains in plain words. */
    internal fun classify(code: Int, body: String): AiError {
        val message = apiMessage(body)
        val problem = when {
            code == 401 -> AiProblem.KEY
            code == 403 -> AiProblem.FORBIDDEN
            code == 404 && isNotFound(body) -> AiProblem.MODEL_NOT_FOUND
            message.contains("credit balance", ignoreCase = true) -> AiProblem.NO_CREDIT
            code == 429 -> AiProblem.RATE_LIMIT
            code == 529 -> AiProblem.OVERLOADED
            code >= 500 -> AiProblem.SERVER
            else -> AiProblem.HTTP
        }
        return AiError(problem, code, message)
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
            "workday", "weekday" -> return Repeat(RepeatUnit.WORKDAY, 1)
            else -> return null
        }
        val every = r.optInt("every", 1)
        return if (every in 1..1000) Repeat(unit, every) else null
    }

    /** A text value, or null when it is missing, empty or JSON null. */
    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).trim().ifEmpty { null }

    private fun dateTime(o: JSONObject, key: String): LocalDateTime? {
        if (o.isNull(key)) return null
        return try {
            LocalDateTime.parse(o.getString(key))
        } catch (e: Exception) {
            null
        }
    }
}
