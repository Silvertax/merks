package de.silvertax.merks.parse

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class Kind { APPOINTMENT, REMINDER, NOTE }

data class Parsed(
    val kind: Kind,
    val title: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val allDay: Boolean,
)

/**
 * Offline fallback: recognises common German date and time phrases with fixed rules.
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
 */
object RuleParser {
    private val months = listOf(
        "januar", "februar", "märz", "april", "mai", "juni", "juli",
        "august", "september", "oktober", "november", "dezember",
    )
    private val weekdays = mapOf(
        "montag" to 1, "dienstag" to 2, "mittwoch" to 3, "donnerstag" to 4,
        "freitag" to 5, "samstag" to 6, "sonnabend" to 6, "sonntag" to 7,
    )
    private val numWords = mapOf(
        "ein" to 1, "eins" to 1, "eine" to 1, "einem" to 1, "einer" to 1, "zwei" to 2,
        "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8,
        "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
    )

    // Word boundaries that also work with umlauts.
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private const val PRE = "(?:(?:so\\s+)?(?:am|um|den|gegen|ab|für|vom)\\s+)*"
    private const val HOUR = "(\\d{1,2}|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf)"
    private const val COUNT = "(\\d{1,3}|ein|eine|einem|einer|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf)"
    private val MONTHS = months.joinToString("|")
    private val DAYS = weekdays.keys.joinToString("|")

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun num(s: String): Int? = s.toIntOrNull() ?: numWords[s.lowercase()]

    private class Work(var s: String) {
        fun take(r: Regex): MatchResult? {
            val m = r.find(s) ?: return null
            s = s.replaceRange(m.range, " ")
            return m
        }
    }

    private val tClock = re("$L$PRE(\\d{1,2})(?::(\\d{2})(?:\\s*uhr)?|\\.(\\d{2})\\s*uhr)$R")
    private val tUhr = re("$L$PRE$HOUR\\s*uhr(?:\\s+(\\d{1,2})$R)?$R")
    private val tHalb = re("$L${PRE}halb\\s+$HOUR$R")
    private val tViertel = re("$L${PRE}viertel\\s+(nach|vor)\\s+$HOUR$R")
    private val tDreiviertel = re("$L${PRE}dreiviertel\\s+$HOUR$R")
    private val tUm = re("${L}(?:um|gegen)\\s+$HOUR$R")

    private val dHalfHour = re("${L}in\\s+einer\\s+halben\\s+stunde$R")
    private val dQuarterHour = re("${L}in\\s+einer\\s+viertelstunde$R")
    private val dRelative = re("${L}in\\s+$COUNT\\s+(minuten?|stunden?|tagen?|wochen?|monat(?:en)?)$R")
    private val dNumeric = re("$L$PRE(\\d{1,2})\\.\\s*(\\d{1,2})\\.(?:\\s*(\\d{4}|\\d{2})$R)?")
    private val dMonthName = re("$L$PRE(\\d{1,2})\\.?\\s+($MONTHS)(?:\\s+(\\d{4}))?$R")
    private val dDayAfter = re("$L${PRE}übermorgen$R")
    private val dToday = re("$L${PRE}heute$R")
    private val dTomorrow = re("$L${PRE}morgen$R")
    private val dWeekday = re("$L$PRE(?:(übernächsten?|nächsten?|kommenden?|diesen?)\\s+)?($DAYS)s?$R")
    private val dNextWeek = re("$L(?:in\\s+der\\s+)?nächsten?\\s+woche$R")
    private val dNextMonth = re("$L(?:im\\s+)?nächsten\\s+monat$R")

    private val todWord = re("$L(?:am\\s+)?(früh|morgens|vormittags?|mittags?|nachmittags?|abends?|nachts?)$R")
    private val morningHint = re("$L(früh|morgens|vormittags?|nachts?)$R")
    private val eveningHint = re("$L(abends?|nachmittags?)$R")

    private val reminderHint = re("erinner|${L}denk\\p{L}*\\s+(?:bitte\\s+)?(?:dran|daran)$R|nicht\\s+vergessen|vergiss\\s+nicht")
    private val reminderPhrases = listOf(
        re("${L}erinner\\p{L}*\\s+mich(?:\\s+bitte)?(?:\\s+(?:daran|dran|an)$R)?(?:,?\\s*dass$R)?"),
        re("$L(?:bitte\\s+)?nicht\\s+vergessen$R"),
        re("${L}vergiss\\s+nicht$R"),
        re("${L}denk\\p{L}*\\s+(?:bitte\\s+)?(?:dran|daran)(?:,?\\s*dass$R)?"),
    )
    private val leadFiller = re(
        "^(?:also|ja|ähm|äh|und|bitte|notiz|notiere|schreib\\s+auf|merk\\p{L}*\\s+dir|da\\s+ist|" +
            "ich\\s+(?:habe|hab|muss|soll|möchte|will)|wir\\s+haben)$R[\\s,:]*",
    )
    private val leadArticle = re("^(?:einen|eine|ein|dass|an|ans|daran)$R\\s+")
    private val tailFiller = re("\\s(?:um|am|an|den|gegen|ab|und)$")

    fun parse(text: String, now: LocalDateTime): Parsed {
        val w = Work(text.trim())
        val morning = morningHint.containsMatchIn(text)
        val evening = eveningHint.containsMatchIn(text)
        val isReminder = reminderHint.containsMatchIn(text)

        // Colloquial hours ("um 3", "halb drei") usually mean the afternoon.
        fun colloquial(h: Int): Int = when {
            h in 1..7 && !morning -> h + 12
            h in 8..11 && evening -> h + 12
            else -> h
        }

        var time: LocalTime? = null
        var date: LocalDate? = null
        var exact: LocalDateTime? = null

        fun setTime(h: Int, m: Int) {
            if (time == null && h in 0..23 && m in 0..59) time = LocalTime.of(h, m)
        }

        w.take(tClock)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toInt()
            setTime(if (evening && h in 1..11) h + 12 else h, min)
        }
        w.take(tViertel)?.let { m ->
            val h = num(m.groupValues[2]) ?: return@let
            if (m.groupValues[1].lowercase() == "nach") setTime(colloquial(h), 15)
            else setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 45)
        }
        w.take(tDreiviertel)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 45)
        }
        w.take(tHalb)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 30)
        }
        w.take(tUhr)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            setTime(if (evening && h in 1..11) h + 12 else h, min)
        }

        if (w.take(dHalfHour) != null) exact = now.plusMinutes(30)
        if (exact == null && w.take(dQuarterHour) != null) exact = now.plusMinutes(15)
        if (exact == null) w.take(dRelative)?.let { m ->
            val n = (num(m.groupValues[1]) ?: return@let).toLong()
            val unit = m.groupValues[2].lowercase()
            when {
                unit.startsWith("minute") -> exact = now.plusMinutes(n)
                unit.startsWith("stunde") -> exact = now.plusHours(n)
                unit.startsWith("tag") -> date = now.toLocalDate().plusDays(n)
                unit.startsWith("woche") -> date = now.toLocalDate().plusWeeks(n)
                else -> date = now.toLocalDate().plusMonths(n)
            }
        }

        val today = now.toLocalDate()
        fun dayMonth(d: Int, mo: Int, y: Int?): LocalDate? = try {
            if (y != null) LocalDate.of(if (y < 100) 2000 + y else y, mo, d)
            else LocalDate.of(today.year, mo, d).let { if (it.isBefore(today)) it.plusYears(1) else it }
        } catch (e: Exception) {
            null
        }

        if (date == null) w.take(dNumeric)?.let { m ->
            date = dayMonth(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
        }
        if (date == null) w.take(dMonthName)?.let { m ->
            date = dayMonth(
                m.groupValues[1].toInt(),
                months.indexOf(m.groupValues[2].lowercase()) + 1,
                m.groupValues[3].toIntOrNull(),
            )
        }
        if (date == null && w.take(dDayAfter) != null) date = today.plusDays(2)
        if (date == null && w.take(dToday) != null) date = today
        var morningWord = false
        if (dTomorrow.containsMatchIn(w.s)) {
            w.take(dTomorrow)
            if (date == null) date = today.plusDays(1) else morningWord = true // "heute morgen"
        }
        if (date == null) w.take(dWeekday)?.let { m ->
            val target = weekdays[m.groupValues[2].lowercase()] ?: return@let
            var diff = (target - today.dayOfWeek.value + 7) % 7
            if (diff == 0) diff = 7
            if (m.groupValues[1].lowercase().startsWith("übernächst")) diff += 7
            date = today.plusDays(diff.toLong())
        }
        if (date == null && w.take(dNextWeek) != null) date = today.plusWeeks(1)
        if (date == null && w.take(dNextMonth) != null) date = today.plusMonths(1)

        // "um 3" without "Uhr": only after dates are out, so "um 10" is not confused with a day.
        if (time == null) w.take(tUm)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 0)
        }

        // Time-of-day words only count when there is a date or a reminder to attach them to.
        if (date != null || time != null || isReminder) {
            val m = w.take(todWord)
            if (time == null && exact == null) {
                val word = m?.groupValues?.get(1)?.lowercase()
                time = when {
                    word == null -> if (morningWord) LocalTime.of(9, 0) else null
                    word == "früh" -> LocalTime.of(8, 0)
                    word == "morgens" || word.startsWith("vormittag") -> LocalTime.of(9, 0)
                    word.startsWith("mittag") -> LocalTime.of(12, 0)
                    word.startsWith("nachmittag") -> LocalTime.of(15, 0)
                    word.startsWith("abend") -> LocalTime.of(19, 0)
                    else -> LocalTime.of(22, 0)
                }
            }
        }

        var allDay = false
        var start: LocalDateTime? = exact
        if (start == null && (date != null || time != null)) {
            val t = time
            val d = date ?: if (t != null && t.isAfter(now.toLocalTime())) today else today.plusDays(1)
            if (t == null) {
                allDay = true
                start = d.atStartOfDay()
            } else {
                start = d.atTime(t)
            }
        }

        var kind = when {
            start == null -> Kind.NOTE
            isReminder -> Kind.REMINDER
            else -> Kind.APPOINTMENT
        }
        if (kind == Kind.REMINDER && allDay) {
            allDay = false
            start = start!!.toLocalDate().atTime(9, 0)
        }
        if (kind == Kind.NOTE) {
            return Parsed(Kind.NOTE, tidy(text, removeFillers = false).ifEmpty { text.trim() }, null, null, false)
        }

        for (r in reminderPhrases) w.take(r)
        val fallback = if (kind == Kind.REMINDER) "Erinnerung" else "Termin"
        val title = tidy(w.s, removeFillers = true).ifEmpty { fallback }
        return Parsed(kind, title, start, null, allDay)
    }

    private fun tidy(s: String, removeFillers: Boolean): String {
        var t = s.replace(Regex("\\s+"), " ").trim(' ', ',', '.', ';', ':', '!', '?', '-')
        if (removeFillers) {
            repeat(4) {
                t = leadFiller.replaceFirst(t, "").trim(' ', ',', '.', ';', ':', '-')
            }
            t = leadArticle.replaceFirst(t, "")
            repeat(2) { t = tailFiller.replaceFirst(t, "").trim(' ', ',', '.', ';', ':', '-') }
        }
        t = t.replace(Regex("\\s+,"), ",").replace(Regex("\\s+"), " ").trim()
        return t.replaceFirstChar { it.uppercase() }
    }
}
