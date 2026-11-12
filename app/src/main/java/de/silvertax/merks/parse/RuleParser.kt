package de.silvertax.merks.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class Kind { APPOINTMENT, REMINDER, NOTE }

data class Parsed(
    val kind: Kind,
    val title: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val allDay: Boolean,
    val repeat: Repeat? = null,
)

enum class RepeatUnit { DAY, WEEK, MONTH, YEAR, WORKDAY }

/** A series: every [every] days, weeks, months or years, starting at the entry's start. [RepeatUnit.WORKDAY] is Monday to Friday. */
data class Repeat(val unit: RepeatUnit, val every: Int)

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
        "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12, "vierzehn" to 14,
    )
    private val everyWords = mapOf(
        "zweite" to 2, "zweiten" to 2, "zweites" to 2, "zweitem" to 2, "2." to 2,
        "dritte" to 3, "dritten" to 3, "drittes" to 3, "drittem" to 3, "3." to 3,
        "vierte" to 4, "vierten" to 4, "viertes" to 4, "viertem" to 4, "4." to 4,
    )
    private val nthWords = mapOf(
        "ersten" to 1, "1." to 1, "zweiten" to 2, "2." to 2, "dritten" to 3, "3." to 3,
        "vierten" to 4, "4." to 4, "letzten" to -1,
    )

    // Word boundaries that also work with umlauts.
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private const val PRE = "(?:(?:so\\s+)?(?:am|um|den|gegen|ab|für|vom|bis)\\s+)*"
    private const val HOUR = "(\\d{1,2}|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf)"
    private const val COUNT = "(\\d{1,3}|ein|eine|einem|einer|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf)"
    private const val EVERY_N = "(zweite[nms]?|dritte[nms]?|vierte[nms]?|[234]\\.)"

    /** One end of a time range: hour, minutes, "Uhr". */
    private const val SPAN =
        "(\\d{1,2}|eins|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf)(?:[:.](\\d{2}))?(\\s*uhr)?"
    private val MONTHS = months.joinToString("|")
    private val DAYS = weekdays.keys.joinToString("|")

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun num(s: String): Int? = s.toIntOrNull() ?: numWords[s.lowercase()]
    private fun weekday(s: String): Int? = weekdays[s.lowercase()]

    private class Work(var s: String) {
        /** Finds the first match that [ok] accepts and cuts it out of the text. */
        fun take(r: Regex, ok: (MatchResult) -> Boolean = { true }): MatchResult? {
            val m = r.findAll(s).firstOrNull(ok) ?: return null
            s = s.replaceRange(m.range, " ")
            return m
        }
    }

    /** How sure an hour is. A spoken "um 8" may well mean 20 Uhr once 8 Uhr is over. */
    private enum class Loose { NONE, CLOCK, SPOKEN }

    private val tSpan = re("$L(?:von|zwischen)\\s+$SPAN\\s*(?:-|–|bis|und)\\s*$SPAN$R")
    private val tSpanBare = re("$L$SPAN\\s*(?:-|–|bis)\\s*$SPAN$R")
    private val tClock = re("$L$PRE(\\d{1,2})(?::(\\d{2})(?:\\s*uhr)?|\\.(\\d{2})\\s*uhr)$R")
    private val tUhr = re("$L$PRE$HOUR\\s*uhr(?:\\s+(\\d{1,2})$R)?$R")
    private val tHalb = re("$L${PRE}halb\\s+$HOUR$R")
    private val tViertel = re("$L${PRE}viertel\\s+(nach|vor)\\s+$HOUR$R")
    private val tDreiviertel = re("$L${PRE}dreiviertel\\s+$HOUR$R")
    private val tMidnight = re("$L${PRE}mitternacht$R")
    private val tUm = re("${L}(?:um|gegen)\\s+$HOUR$R")

    private val dHalfHour = re("${L}in\\s+einer\\s+halben\\s+stunde$R")
    private val dQuarterHour = re("${L}in\\s+einer\\s+viertelstunde$R")
    private val dRelative = re("${L}in\\s+$COUNT\\s+(minuten?|stunden?|tagen?|wochen?|monat(?:en)?)$R")
    private val dWeekFrom = re("$L(?:(heute|morgen)|$PRE($DAYS))\\s+in\\s+(einer|einem|1|zwei|2|drei|3|acht|8|vierzehn|14)\\s+(wochen?|tagen)$R")
    private val dNumeric = re("$L$PRE(\\d{1,2})\\.\\s*(\\d{1,2})\\.(?:\\s*(\\d{4}|\\d{2})$R)?")
    private val dMonthName = re("$L$PRE(\\d{1,2})\\.?\\s+($MONTHS)(?:\\s+(\\d{4}))?$R")
    private val dDayRange = re("$L(?:von\\s+)?($DAYS)\\s+bis\\s+(?:zum\\s+)?($DAYS)$R")
    private val dMonthRange = re(
        "$L(?:vom\\s+|von\\s+)?(\\d{1,2})\\.(?:\\s*($MONTHS))?\\s*(?:-|–|bis)\\s*(?:zum\\s+)?(\\d{1,2})\\.\\s*($MONTHS)(?:\\s+(\\d{4}))?$R",
    )
    private val dNumericRange = re(
        "$L(?:vom\\s+|von\\s+)?(\\d{1,2})\\.\\s*(\\d{1,2})\\.(?:\\s*\\d{2,4})?\\s*(?:-|–|bis)\\s*(?:zum\\s+)?" +
            "(\\d{1,2})\\.\\s*(\\d{1,2})\\.(?:\\s*(\\d{4}|\\d{2})$R)?",
    )
    private val dDayAfter = re("$L${PRE}übermorgen$R")
    private val dToday = re("$L${PRE}heute$R")
    private val dTomorrow = re("$L${PRE}morgen$R")
    private val dWeekdayNextWeek = re(
        "$L$PRE(?:(?:in\\s+der\\s+)?(?:nächsten?|kommenden?)\\s+woche\\s+(?:am\\s+)?($DAYS)|" +
            "($DAYS)\\s+(?:in\\s+der\\s+)?(?:nächsten?|kommenden?)\\s+woche)$R",
    )
    private val dWeekdayDom = re("$L$PRE($DAYS),?\\s+(?:den\\s+)?(\\d{1,2})\\.(?!\\d)")
    private val dWeekday =
        re("$L$PRE(?:(übernächsten?|nächsten?|kommenden?|diesen?|jeden|immer)\\s+)?(?:am\\s+)?($DAYS)(s?)$R")
    private val dWeekend = re("$L(?:(am|dieses|diesem|nächstes|nächsten|kommendes|kommenden|übers)\\s+)?wochenende$R")
    private val dDayOfMonth =
        re("$L((?:am|den|bis\\s+zum|ab\\s+dem)\\s+)?(\\d{1,2})\\.(?!\\d)(?:\\s+(?:des\\s+)?(nächsten|kommenden|diesen)\\s+monats$R)?")
    private val dNthWeekday = re(
        "$L$PRE(?:(?:jeden|immer)\\s+)?(?:am\\s+)?(ersten|zweiten|dritten|vierten|letzten|[1-4]\\.)\\s+($DAYS)\\s+" +
            "(?:im|des|jeden|eines)\\s+(nächsten\\s+)?monats?$R",
    )

    private val rDaily = re("$L(?:jeden\\s+tag|täglich|jeden\\s+(morgen|abend))$R")
    private val rEveryN = re("${L}alle\\s+$COUNT\\s+(tage|wochen|monate|jahre)$R")
    private val rEveryNth = re("${L}jede[nms]?\\s+$EVERY_N\\s+(tag|woche|monat|jahr)$R")
    private val rEveryNDay = re("$L(?:jeden|jede|immer)\\s+$EVERY_N\\s+($DAYS)$R")
    private val rWorkdays = re(
        "$L(?:werktags|wochentags|jeden\\s+werktag|(?:an\\s+)?(?:allen\\s+)?(?:werktagen|wochentagen)|" +
            "(?:von\\s+)?montags\\s+bis\\s+freitags|unter\\s+der\\s+woche)$R",
    )
    private val rWeekly = re("$L(?:jede\\s+woche|wöchentlich)$R")
    private val rMonthly = re("$L(?:jeden\\s+monat|monatlich)$R")
    private val rYearly = re("$L(?:jedes\\s+jahr|jährlich)$R")
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
    private val tailFiller = re("\\s(?:um|am|an|den|gegen|ab|und|bis|von|zwischen)$")

    /** The [n]th (or with -1 the last) [weekday] of the month that [first] starts. */
    private fun nthInMonth(first: LocalDate, n: Int, weekday: Int): LocalDate =
        if (n < 0) first.with(TemporalAdjusters.lastInMonth(DayOfWeek.of(weekday)))
        else first.with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.of(weekday)))

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
        var endTime: LocalTime? = null
        var date: LocalDate? = null
        var endDate: LocalDate? = null
        var exact: LocalDateTime? = null
        var loose = Loose.NONE
        val today = now.toLocalDate()
        val spokenLoose = if (morning || evening) Loose.NONE else Loose.SPOKEN
        val clockLoose = if (morning || evening) Loose.NONE else Loose.CLOCK

        fun setTime(h: Int, m: Int, how: Loose = Loose.NONE) {
            if (time == null && h in 0..23 && m in 0..59) {
                time = LocalTime.of(h, m)
                loose = how
            }
        }

        /** The coming [target] weekday, a week ahead when it is today. */
        fun nextWeekday(target: Int): LocalDate {
            var diff = (target - today.dayOfWeek.value + 7) % 7
            if (diff == 0) diff = 7
            return today.plusDays(diff.toLong())
        }

        /** The next [day] of a month from [from] on; when [weekday] is given and fits one of the next two, that one. */
        fun dayOfMonth(day: Int, weekday: Int? = null, from: LocalDate = today.withDayOfMonth(1)): LocalDate? {
            if (day !in 1..31) return null
            val found = mutableListOf<LocalDate>()
            var month = from
            while (found.size < 2 && month.isBefore(today.plusYears(1))) {
                if (day <= month.lengthOfMonth()) month.withDayOfMonth(day).takeIf { !it.isBefore(today) }?.let { found += it }
                month = month.plusMonths(1)
            }
            return found.firstOrNull { weekday == null || it.dayOfWeek.value == weekday } ?: found.firstOrNull()
        }

        /** Start and end of a range like "von 14 bis 16 Uhr", picking the shortest sensible reading. */
        fun span(g: List<String>): Pair<Int, Int>? {
            fun hours(word: String): List<Int>? {
                val h = num(word) ?: return null
                return when {
                    h == 0 || h in 13..23 -> listOf(h)
                    h in 1..12 -> listOf(h % 12, h % 12 + 12)
                    else -> null
                }
            }
            val from = hours(g[1]) ?: return null
            val to = hours(g[4]) ?: return null
            val m1 = g[2].toIntOrNull() ?: 0
            val m2 = g[5].toIntOrNull() ?: 0
            if (m1 > 59 || m2 > 59) return null
            val prefer = num(g[1])?.let { colloquial(it) }
            var best: Pair<Int, Int>? = null
            var bestScore = Int.MAX_VALUE
            for (a in from) for (b in to) {
                val s = a * 60 + m1
                val e = b * 60 + m2
                val length = e - s
                if (length <= 0 || length > 12 * 60) continue
                val score = length * 2 + if (from.size == 1 || a == prefer) 0 else 1
                if (score < bestScore) {
                    bestScore = score
                    best = s to e
                }
            }
            return best
        }
        fun setSpan(g: List<String>) {
            val (s, e) = span(g) ?: return
            // A range is an appointment; only "heute von 8 bis 10" in the evening moves it to 20 Uhr.
            val sure = s / 60 >= 13 || s / 60 == 0 || num(g[1]).let { it != null && it >= 13 }
            setTime(s / 60, s % 60, if (sure || morning || evening) Loose.NONE else Loose.CLOCK)
            endTime = LocalTime.of(e / 60, e % 60)
        }

        // "am ersten Montag im Monat", before "jeden zweiten Montag" is taken for a series.
        w.take(dNthWeekday)?.let { m ->
            val n = nthWords[m.groupValues[1].lowercase()] ?: return@let
            val target = weekday(m.groupValues[2]) ?: return@let
            var first = today.withDayOfMonth(1).let { if (m.groupValues[3].isNotEmpty()) it.plusMonths(1) else it }
            for (i in 0 until 13) {
                val d = nthInMonth(first, n, target)
                if (!d.isBefore(today)) {
                    date = d
                    break
                }
                first = first.plusMonths(1)
            }
        }

        // Series first, so "jeden Morgen" is not read as "tomorrow".
        var repeat: Repeat? = null
        var seriesTime: LocalTime? = null
        w.take(rEveryNDay)?.let { m ->
            val n = everyWords[m.groupValues[1].lowercase()] ?: return@let
            val target = weekday(m.groupValues[2]) ?: return@let
            repeat = Repeat(RepeatUnit.WEEK, n)
            if (date == null) date = nextWeekday(target)
        }
        w.take(rEveryN)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val unit = when (m.groupValues[2].lowercase()) {
                "tage" -> RepeatUnit.DAY
                "wochen" -> RepeatUnit.WEEK
                "monate" -> RepeatUnit.MONTH
                else -> RepeatUnit.YEAR
            }
            if (n >= 1 && repeat == null) repeat = Repeat(unit, n)
        }
        w.take(rEveryNth)?.let { m ->
            val n = everyWords[m.groupValues[1].lowercase()] ?: return@let
            val unit = when (m.groupValues[2].lowercase()) {
                "tag" -> RepeatUnit.DAY
                "woche" -> RepeatUnit.WEEK
                "monat" -> RepeatUnit.MONTH
                else -> RepeatUnit.YEAR
            }
            if (repeat == null) repeat = Repeat(unit, n)
        }
        if (w.take(rWorkdays) != null && repeat == null) repeat = Repeat(RepeatUnit.WORKDAY, 1)
        w.take(rDaily)?.let { m ->
            if (repeat == null) repeat = Repeat(RepeatUnit.DAY, 1)
            when (m.groupValues[1].lowercase()) {
                "morgen" -> seriesTime = LocalTime.of(8, 0)
                "abend" -> seriesTime = LocalTime.of(19, 0)
            }
        }
        if (w.take(rWeekly) != null && repeat == null) repeat = Repeat(RepeatUnit.WEEK, 1)
        if (w.take(rMonthly) != null && repeat == null) repeat = Repeat(RepeatUnit.MONTH, 1)
        if (w.take(rYearly) != null && repeat == null) repeat = Repeat(RepeatUnit.YEAR, 1)

        fun dayMonth(d: Int, mo: Int, y: Int?): LocalDate? = try {
            if (y != null) LocalDate.of(if (y < 100) 2000 + y else y, mo, d)
            else LocalDate.of(today.year, mo, d).let { if (it.isBefore(today)) it.plusYears(1) else it }
        } catch (e: Exception) {
            null
        }
        /** Several days, e.g. a holiday: the end is the next such date, the start the one before it. */
        fun setDays(d1: Int, m1: Int, d2: Int, m2: Int, y: Int?) {
            val last = dayMonth(d2, m2, y) ?: return
            val first = try {
                LocalDate.of(last.year, m1, d1).let { if (it.isAfter(last)) it.minusYears(1) else it }
            } catch (e: Exception) {
                return
            }
            if (first.isBefore(last) && first.plusDays(366).isAfter(last)) {
                date = first
                endDate = last
            }
        }
        fun monthOf(s: String) = months.indexOf(s.lowercase()) + 1
        // "vom 20. bis 23. Dezember", "vom 28. Dezember bis 3. Januar"
        w.take(dMonthRange)?.let { m ->
            val g = m.groupValues
            val m2 = monthOf(g[4])
            setDays(g[1].toInt(), g[2].takeIf { it.isNotEmpty() }?.let { monthOf(it) } ?: m2, g[3].toInt(), m2, g[5].toIntOrNull())
        }
        // "vom 20.12. bis 3.1."
        if (date == null) w.take(dNumericRange)?.let { m ->
            val g = m.groupValues
            setDays(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toIntOrNull())
        }
        // "von Montag bis Freitag"
        if (date == null) w.take(dDayRange)?.let { m ->
            val first = nextWeekday(weekday(m.groupValues[1]) ?: return@let)
            val to = weekday(m.groupValues[2]) ?: return@let
            date = first
            endDate = first.plusDays(((to - first.dayOfWeek.value + 7) % 7).toLong()).takeIf { it.isAfter(first) }
        }

        // Ranges before single times, so "von 10 bis 12 Uhr" does not leave half of it behind.
        w.take(tSpan) { span(it.groupValues) != null }?.let { setSpan(it.groupValues) }
        // Without "von", only with "Uhr" or minutes ("10 bis 12 Uhr", "14:30-16:00").
        w.take(tSpanBare) { m ->
            val g = m.groupValues
            (g[2].isNotEmpty() || g[3].isNotEmpty() || g[5].isNotEmpty() || g[6].isNotEmpty()) && span(g) != null
        }?.let { setSpan(it.groupValues) }
        w.take(tClock)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toInt()
            if (evening && h in 1..11) setTime(h + 12, min) else setTime(h, min, clockLoose)
        }
        w.take(tViertel)?.let { m ->
            val h = num(m.groupValues[2]) ?: return@let
            if (m.groupValues[1].lowercase() == "nach") setTime(colloquial(h), 15, spokenLoose)
            else setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 45, spokenLoose)
        }
        w.take(tDreiviertel)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 45, spokenLoose)
        }
        w.take(tHalb)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 30, spokenLoose)
        }
        w.take(tUhr)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (evening && h in 1..11) setTime(h + 12, min) else setTime(h, min, clockLoose)
        }
        // A minute before, so it stays on the day that was meant.
        if (w.take(tMidnight) != null) setTime(23, 59)

        // "Freitag in einer Woche" before "Freitag" and "in einer Woche".
        w.take(dWeekFrom)?.let { m ->
            val n = num(m.groupValues[3]) ?: return@let
            // "in acht Tagen" and "in vierzehn Tagen" mean one and two weeks.
            val days = if (m.groupValues[4].lowercase().startsWith("woche")) n * 7L else if (n == 8) 7L else n.toLong()
            val base = when (m.groupValues[1].lowercase()) {
                "heute" -> today
                "morgen" -> today.plusDays(1)
                else -> nextWeekday(weekday(m.groupValues[2]) ?: return@let)
            }
            if (date == null) date = base.plusDays(days)
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
        // "in 20 Minuten", "in 2 Stunden": something to be reminded of, not a calendar appointment.
        val soon = exact != null

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
        // "nächste Woche Mittwoch": the Wednesday in the week after this one.
        if (date == null) w.take(dWeekdayNextWeek)?.let { m ->
            val target = weekday(m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: return@let
            date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays((target - 1).toLong())
        }
        // "Freitag, den 20."
        if (date == null) w.take(dWeekdayDom)?.let { m ->
            val target = weekday(m.groupValues[1]) ?: return@let
            date = dayOfMonth(m.groupValues[2].toInt(), target)
        }
        if (date == null) w.take(dWeekday)?.let { m ->
            val target = weekday(m.groupValues[2]) ?: return@let
            var diff = (target - today.dayOfWeek.value + 7) % 7
            if (diff == 0) diff = 7
            val lead = m.groupValues[1].lowercase()
            if (lead.startsWith("übernächst")) diff += 7
            date = today.plusDays(diff.toLong())
            // "jeden Montag", "immer montags", "montags" all mean every week.
            if (repeat == null && (lead == "jeden" || lead == "immer" || m.groupValues[3].isNotEmpty())) {
                repeat = Repeat(RepeatUnit.WEEK, 1)
            }
        }
        // "am Wochenende": the coming Saturday; on a weekend itself today, or the following one with "nächstes".
        if (date == null) w.take(dWeekend)?.let { m ->
            val next = m.groupValues[1].lowercase().startsWith("nächst")
            date = if (today.dayOfWeek.value >= 6 && !next) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        }
        // "am 15.", "jeden Monat am 1.", "am 1. des nächsten Monats". A number with a dot alone is no day.
        if (date == null) w.take(dDayOfMonth) { m ->
            m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || repeat?.unit == RepeatUnit.MONTH
        }?.let { m ->
            val from = today.withDayOfMonth(1).let { if (m.groupValues[3].lowercase().let { it == "nächsten" || it == "kommenden" }) it.plusMonths(1) else it }
            date = dayOfMonth(m.groupValues[2].toInt(), null, from)
        }
        if (date == null && w.take(dNextWeek) != null) date = today.plusWeeks(1)
        if (date == null && w.take(dNextMonth) != null) date = today.plusMonths(1)
        // A weekday said along with a date ("morgen, Freitag") is not part of the title.
        date?.let { d -> w.take(dWeekday) { it.groupValues[1].isEmpty() && weekday(it.groupValues[2]) == d.dayOfWeek.value } }

        // "um 3" without "Uhr": only after dates are out, so "um 10" is not confused with a day.
        if (time == null) w.take(tUm)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 0, spokenLoose)
        }

        // Time-of-day words only count when there is a date or a reminder to attach them to.
        if (date != null || time != null || isReminder || repeat != null) {
            val m = w.take(todWord)
            if (time == null && exact == null) {
                val word = m?.groupValues?.get(1)?.lowercase()
                time = when {
                    word == null -> seriesTime ?: if (morningWord) LocalTime.of(9, 0) else null
                    word == "früh" -> LocalTime.of(8, 0)
                    word == "morgens" || word.startsWith("vormittag") -> LocalTime.of(9, 0)
                    word.startsWith("mittag") -> LocalTime.of(12, 0)
                    word.startsWith("nachmittag") -> LocalTime.of(15, 0)
                    word.startsWith("abend") -> LocalTime.of(19, 0)
                    else -> LocalTime.of(22, 0)
                }
            }
        }

        // A series without any date or time starts today as an all-day entry.
        if (repeat != null && date == null && time == null && exact == null) date = today

        var allDay = false
        var start: LocalDateTime? = exact
        if (start == null && (date != null || time != null)) {
            var t = time
            var d = date
            val clock = now.toLocalTime()
            // "heute um 8" in the evening means 20 Uhr. So does a bare "um 8" once 8 in the morning is over,
            // but not "um 10" or "um 11", which are more often tomorrow morning.
            if (t != null && loose != Loose.NONE && t.hour in 1..11 && (d == null || d == today) && !t.isAfter(clock)) {
                val later = t.plusHours(12)
                val endFits = endTime.let { it == null || it.hour < 12 }
                val meant = d == today || (repeat == null && loose == Loose.SPOKEN && later.hour <= 21)
                if (later.isAfter(clock) && endFits && meant) {
                    t = later
                    d = today
                    endTime = endTime?.plusHours(12)
                }
            }
            if (d == null) d = if (t != null && t.isAfter(clock)) today else today.plusDays(1)
            // A Monday-to-Friday series starts on a working day.
            if (repeat?.unit == RepeatUnit.WORKDAY) while (d!!.dayOfWeek.value >= 6) d = d.plusDays(1)
            if (t == null) {
                allDay = true
                start = d!!.atStartOfDay()
            } else {
                start = d!!.atTime(t)
            }
        }

        val kind = when {
            start == null -> Kind.NOTE
            isReminder || soon -> Kind.REMINDER
            else -> Kind.APPOINTMENT
        }
        if (kind == Kind.REMINDER && allDay) {
            allDay = false
            start = start!!.toLocalDate().atTime(9, 0)
        }
        if (kind == Kind.NOTE) {
            return Parsed(Kind.NOTE, tidy(text, removeFillers = false).ifEmpty { text.trim() }, null, null, false)
        }
        val end = when {
            kind != Kind.APPOINTMENT -> null
            // Several days: the end is the start of the last day.
            allDay -> endDate?.atStartOfDay()?.takeIf { it.isAfter(start) }
            else -> endTime?.let { (endDate ?: start!!.toLocalDate()).atTime(it) }?.takeIf { it.isAfter(start) }
        }

        for (r in reminderPhrases) w.take(r)
        val fallback = if (kind == Kind.REMINDER) "Erinnerung" else "Termin"
        val title = tidy(w.s, removeFillers = true).ifEmpty { fallback }
        return Parsed(kind, title, start, end, allDay, repeat)
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
