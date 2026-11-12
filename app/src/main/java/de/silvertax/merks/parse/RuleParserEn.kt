package de.silvertax.merks.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Offline fallback for English: recognises common date, time and repeat phrases with fixed rules,
 * like [RuleParser] does for German. Pure Kotlin, so it can be unit-tested on the JVM.
 */
object RuleParserEn {
    private val months = listOf(
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )
    private val monthShort = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7,
        "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val weekdays = mapOf(
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4,
        "friday" to 5, "saturday" to 6, "sunday" to 7,
    )
    private val numWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45,
    )
    private val everyWords = mapOf(
        "other" to 2, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4,
    )
    private val nthWords = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3,
        "fourth" to 4, "4th" to 4, "last" to -1,
    )

    /** Regions that write the month first, as in 12/3 for December 3. */
    private val monthFirstRegions = setOf("US", "PH", "PR", "GU", "VI", "AS", "MP", "UM", "FM", "MH", "PW")

    private const val L = "(?<![\\p{L}\\d'])"
    private const val R = "(?![\\p{L}\\d])"
    private const val PRE = "(?:(?:on|at|around|by|for|from|about|this)\\s+)*"
    private const val HOUR = "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private const val NUM =
        "\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five"
    private const val COUNT = "($NUM)"
    private const val EVERY_N = "(other|second|2nd|third|3rd|fourth|4th|$NUM)"
    private const val AMPM = "(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)"
    private const val ORD = "(\\d{1,2})(?:st|nd|rd|th)?"

    /** One end of a time range: hour, minutes, am/pm. */
    private const val SPAN =
        "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|noon|midday)" +
            "(?:[:.](\\d{2}))?(?:\\s*$AMPM)?(?:\\s*o'?\\s?clock)?"
    private val MONTHS = (months + monthShort.keys).joinToString("|")
    private val DAYS = weekdays.keys.joinToString("|")

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun num(s: String): Int? = s.toIntOrNull() ?: numWords[s.lowercase()]
    private fun month(s: String): Int? = months.indexOf(s.lowercase()).takeIf { it >= 0 }?.plus(1) ?: monthShort[s.lowercase()]
    private fun pm(s: String) = s.lowercase().startsWith("p")
    private fun weekday(s: String): Int? = weekdays[s.lowercase()]

    private class Work(var s: String) {
        /** Finds the first match that [ok] accepts and cuts it out of the text. */
        fun take(r: Regex, ok: (MatchResult) -> Boolean = { true }): MatchResult? {
            val m = r.findAll(s).firstOrNull(ok) ?: return null
            s = s.replaceRange(m.range, " ")
            return m
        }
    }

    /** How sure an hour without am/pm is. A spoken "at 8" may well mean 8 pm once 8 am is over. */
    private enum class Loose { NONE, CLOCK, SPOKEN }

    // Times
    private val tSpan = re("$L(?:from|between)\\s+$SPAN\\s*(?:-|–|to|till|til|until|and)\\s*$SPAN$R")
    private val tSpanBare = re("$L$SPAN\\s*(?:-|–|to|till|til|until)\\s*$SPAN$R")
    private val tClock = re("$L$PRE(\\d{1,2})[:.](\\d{2})(?:\\s*$AMPM)?$R")
    private val tAmPm = re("$L$PRE$HOUR\\s*$AMPM(?![\\p{L}])")
    private val tOclock = re("$L$PRE${HOUR}\\s*o'?\\s?clock$R")
    private val tHalfPast = re("$L${PRE}half\\s+past\\s+$HOUR$R")
    private val tQuarter = re("$L$PRE(?:a\\s+)?quarter\\s+(past|after|to|before)\\s+$HOUR$R")
    private val tNoon = re("$L$PRE(noon|midday|lunchtime)$R")
    private val tMidnight = re("$L$PRE(?:12\\s+)?midnight$R")
    private val tAt = re("${L}(?:at|around)\\s+$HOUR$R")

    // Dates
    private val dHalfHour = re("${L}in\\s+(?:a\\s+)?half\\s+(?:an\\s+)?hour$R")
    private val dQuarterHour = re("${L}in\\s+a\\s+quarter\\s+of\\s+an\\s+hour$R")
    private val dRelative = re("${L}in\\s+$COUNT\\s+(minutes?|mins?|hours?|days?|weeks?|months?)$R")
    private val dWeekFrom =
        re("$L(?:in\\s+)?(a|one|two|three|four|\\d)\\s+weeks?\\s+from\\s+(today|tomorrow|now|(?:next\\s+|this\\s+)?($DAYS))$R")
    private val dDayMonth = re("$L$PRE(?:the\\s+)?$ORD\\s+(?:of\\s+)?($MONTHS)\\.?(?:,?\\s+(\\d{4}))?$R")
    private val dMonthDay = re("$L$PRE($MONTHS)\\.?\\s+(?:the\\s+)?$ORD(?:,?\\s+(\\d{4}))?$R")
    private val dSlash = re("$L$PRE(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}|\\d{2}))?$R")
    private val dDayRange = re("$L(?:from\\s+)?($DAYS)\\s+(?:to|through|thru|till|until|-|–)\\s+($DAYS)$R")
    private val dMonthDayRange = re(
        "$L(?:from\\s+)?($MONTHS)\\.?\\s+$ORD\\s*(?:to|through|thru|till|until|-|–)\\s*(?:($MONTHS)\\.?\\s+)?$ORD(?:,?\\s+(\\d{4}))?$R",
    )
    private val dDayMonthRange = re(
        "$L(?:from\\s+)?(?:the\\s+)?$ORD(?:\\s+(?:of\\s+)?($MONTHS)\\.?)?\\s*(?:to|through|thru|till|until|-|–)\\s*" +
            "(?:the\\s+)?$ORD\\s+(?:of\\s+)?($MONTHS)\\.?(?:,?\\s+(\\d{4}))?$R",
    )
    private val dDayAfter = re("$L$PRE(?:the\\s+)?day\\s+after\\s+tomorrow$R")
    private val dTonight = re("${L}tonight$R")
    private val dToday = re("$L${PRE}today$R")
    private val dTomorrow = re("$L${PRE}tomorrow$R")
    private val dWeekdayNextWeek = re("$L$PRE(?:($DAYS)\\s+(?:of\\s+)?next\\s+week|next\\s+week(?:\\s+on)?\\s+($DAYS))$R")
    private val dWeekdayDom = re("$L$PRE($DAYS),?\\s+(?:the\\s+(\\d{1,2})(?:st|nd|rd|th)?|(\\d{1,2})(?:st|nd|rd|th))$R")
    private val dWeekday = re("$L$PRE(?:(next|this|coming|every|the)\\s+)?($DAYS)(s?)$R")
    private val dWeekend = re("$L(?:(this|next|on\\s+the|over\\s+the|at\\s+the|the)\\s+)weekend$R")
    private val dNextWeek = re("${L}next\\s+week$R")
    private val dNextMonth = re("${L}next\\s+month$R")
    private val dDayOfMonth = re(
        "$L(on\\s+)?(the\\s+)?(\\d{1,2})(st|nd|rd|th)?(?:\\s+of\\s+(the|this|next)\\s+month)?$R" +
            "(?!\\s+(?:times?|floor|dose|round|place|grade|try|attempt|chance|birthday|anniversary|row|lesson|" +
            "session|visit|street|avenue|day|week|month|year|century|edition)$R)",
    )
    private val dNthWeekday = re(
        "$L$PRE(?:the\\s+)?(first|1st|second|2nd|third|3rd|fourth|4th|last)\\s+($DAYS)\\s+(?:of|in)\\s+" +
            "(?:(the|every|each|this|next)\\s+)?month$R",
    )
    private val thisPartOfDay = re("${L}this\\s+(?:morning|afternoon|evening)$R")

    // Repeats
    private val rEveryNDay = re("$L(?:every|each)\\s+$EVERY_N\\s+($DAYS)s?$R")
    private val rDaily = re("$L(?:every\\s+day|each\\s+day|daily|every\\s+(morning|evening|night))$R")
    private val rEveryN = re("${L}every\\s+$EVERY_N\\s+(days?|weeks?|months?|years?)$R")
    private val rWorkdays = re(
        "$L(?:(?:every|each)\\s+(?:week\\s?day|work\\s?day|working\\s+day|business\\s+day)|" +
            "(?:on\\s+)?(?:week\\s?days|work\\s?days|working\\s+days|business\\s+days)|" +
            "(?:(?:every|each)\\s+(?:from\\s+)?monday|(?:on\\s+)?mondays)\\s+(?:to|through|thru|till|until|-|–)\\s+fridays?)$R",
    )
    private val rWeekly = re("$L(?:every\\s+week|each\\s+week|weekly)$R")
    private val rMonthly = re("$L(?:(?:of\\s+)?(?:every|each)\\s+month|monthly)$R")
    private val rYearly = re("$L(?:every\\s+year|each\\s+year|yearly|annually)$R")

    private val todWord = re("$L(?:in\\s+the\\s+|this\\s+|at\\s+)?(early|morning|afternoon|evening|night)$R")
    private val morningHint = re("$L(?:early|morning|a\\.?\\s?m\\.?)$R")
    private val eveningHint = re("$L(?:afternoon|evening|tonight|night|p\\.?\\s?m\\.?)$R")

    private val reminderHint = re("${L}remind(?:er)?$R|${L}don'?t\\s+forget$R|${L}do\\s+not\\s+forget$R|${L}remember\\s+to$R")
    private val reminderPhrases = listOf(
        re("$L(?:please\\s+)?remind\\s+me(?:\\s+(?:to|about|of|that))?$R"),
        re("$L(?:please\\s+)?(?:don'?t|do\\s+not)\\s+forget(?:\\s+(?:to|about))?$R"),
        re("${L}remember\\s+to$R"),
        re("${L}reminder(?:\\s+(?:to|about|for))?$R"),
    )
    private val leadFiller = re(
        "^(?:so|okay|ok|um|uh|and|please|note|make\\s+a\\s+note|write\\s+down|" +
            "i\\s+(?:have|need|must|want|got)(?:\\s+to)?|i've\\s+got|i'd\\s+like\\s+to|we\\s+have|there\\s+is|there's)$R[\\s,:]*",
    )
    private val leadArticle = re("^(?:a|an|to|that|about)$R\\s+")
    private val tailFiller = re("\\s(?:at|on|in|and|for|by|from|between|of)$")

    private fun monthFirstHere() = Locale.getDefault().country.uppercase() in monthFirstRegions

    /** The [n]th (or with -1 the last) [weekday] of the month that [first] starts. */
    private fun nthInMonth(first: LocalDate, n: Int, weekday: Int): LocalDate =
        if (n < 0) first.with(TemporalAdjusters.lastInMonth(DayOfWeek.of(weekday)))
        else first.with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.of(weekday)))

    fun parse(text: String, now: LocalDateTime, monthFirst: Boolean = monthFirstHere()): Parsed {
        val w = Work(text.trim())
        val morning = morningHint.containsMatchIn(text)
        val evening = eveningHint.containsMatchIn(text)
        val isReminder = reminderHint.containsMatchIn(text)

        // Spoken hours like "at 3" usually mean the afternoon.
        fun colloquial(h: Int): Int = when {
            h in 1..7 && !morning -> h + 12
            h in 8..11 && evening -> h + 12
            else -> h
        }
        fun withAmPm(h: Int, ampm: String): Int = when {
            pm(ampm) && h in 1..11 -> h + 12
            !pm(ampm) && h == 12 -> 0
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

        fun setTime(h: Int, m: Int, how: Loose = Loose.NONE) {
            if (time == null && h in 0..23 && m in 0..59) {
                time = LocalTime.of(h, m)
                loose = how
            }
        }

        /** The coming [target] weekday, a week ahead when it is today. */
        fun nextWeekday(target: Int, from: LocalDate = today): LocalDate {
            var diff = (target - from.dayOfWeek.value + 7) % 7
            if (diff == 0) diff = 7
            return from.plusDays(diff.toLong())
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

        /** Start and end of a range like "from 2 to 4 pm", picking the shortest sensible reading. */
        fun span(g: List<String>, at: Int): Pair<Int, Int>? {
            fun hours(word: String, ampm: String): List<Int>? {
                val lower = word.lowercase()
                if (lower == "noon" || lower == "midday") return listOf(12)
                val h = num(lower) ?: return null
                return when {
                    ampm.isNotEmpty() -> if (h in 1..12) listOf(withAmPm(h, ampm)) else null
                    h == 0 || h in 13..23 -> listOf(h)
                    h in 1..12 -> listOf(h % 12, h % 12 + 12)
                    else -> null
                }
            }
            val from = hours(g[at], g[at + 2]) ?: return null
            val to = hours(g[at + 3], g[at + 5]) ?: return null
            val m1 = g[at + 1].toIntOrNull() ?: 0
            val m2 = g[at + 4].toIntOrNull() ?: 0
            if (m1 > 59 || m2 > 59) return null
            val prefer = num(g[at])?.let { colloquial(it) }
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
        fun setSpan(g: List<String>, at: Int) {
            val (s, e) = span(g, at) ?: return
            // A range is an appointment; only "today from 8 to 10" in the evening moves it to 8 pm.
            val sure = g[at + 2].isNotEmpty() || g[at + 5].isNotEmpty() || s / 60 >= 13 || s / 60 == 0
            setTime(s / 60, s % 60, if (sure || morning || evening) Loose.NONE else Loose.CLOCK)
            endTime = LocalTime.of(e / 60, e % 60)
        }

        // "the first Monday of the month", before the repeats take "of every month" for a plain monthly series.
        w.take(dNthWeekday)?.let { m ->
            val n = nthWords[m.groupValues[1].lowercase()] ?: return@let
            val target = weekday(m.groupValues[2]) ?: return@let
            var first = today.withDayOfMonth(1).let { if (m.groupValues[3].lowercase() == "next") it.plusMonths(1) else it }
            for (i in 0 until 13) {
                val d = nthInMonth(first, n, target)
                if (!d.isBefore(today)) {
                    date = d
                    break
                }
                first = first.plusMonths(1)
            }
        }

        // Repeats first, so "every evening" is not read as just "evening".
        var repeat: Repeat? = null
        var seriesTime: LocalTime? = null
        w.take(rEveryNDay)?.let { m ->
            val n = everyWords[m.groupValues[1].lowercase()] ?: num(m.groupValues[1]) ?: return@let
            val target = weekday(m.groupValues[2]) ?: return@let
            if (n >= 1) {
                repeat = Repeat(RepeatUnit.WEEK, n)
                if (date == null) date = nextWeekday(target)
            }
        }
        w.take(rEveryN)?.let { m ->
            val n = everyWords[m.groupValues[1].lowercase()] ?: num(m.groupValues[1]) ?: return@let
            val unit = when (m.groupValues[2].lowercase().removeSuffix("s")) {
                "day" -> RepeatUnit.DAY
                "week" -> RepeatUnit.WEEK
                "month" -> RepeatUnit.MONTH
                else -> RepeatUnit.YEAR
            }
            if (n >= 1 && repeat == null) repeat = Repeat(unit, n)
        }
        if (w.take(rWorkdays) != null && repeat == null) repeat = Repeat(RepeatUnit.WORKDAY, 1)
        w.take(rDaily)?.let { m ->
            if (repeat == null) repeat = Repeat(RepeatUnit.DAY, 1)
            when (m.groupValues[1].lowercase()) {
                "morning" -> seriesTime = LocalTime.of(8, 0)
                "evening" -> seriesTime = LocalTime.of(19, 0)
                "night" -> seriesTime = LocalTime.of(22, 0)
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
        // "December 20 to 23", "Dec 28 - Jan 3", "the 20th to the 23rd of December"
        w.take(dMonthDayRange)?.let { m ->
            val g = m.groupValues
            val m1 = month(g[1]) ?: return@let
            setDays(g[2].toInt(), m1, g[4].toInt(), g[3].takeIf { it.isNotEmpty() }?.let { month(it) } ?: m1, g[5].toIntOrNull())
        }
        if (date == null) w.take(dDayMonthRange)?.let { m ->
            val g = m.groupValues
            val m2 = month(g[4]) ?: return@let
            setDays(g[1].toInt(), g[2].takeIf { it.isNotEmpty() }?.let { month(it) } ?: m2, g[3].toInt(), m2, g[5].toIntOrNull())
        }
        // "Monday to Friday"
        if (date == null) w.take(dDayRange)?.let { m ->
            val first = nextWeekday(weekday(m.groupValues[1]) ?: return@let)
            val to = weekday(m.groupValues[2]) ?: return@let
            date = first
            endDate = first.plusDays(((to - first.dayOfWeek.value + 7) % 7).toLong()).takeIf { it.isAfter(first) }
        }

        // Ranges before single times, so "from 10 to 12" does not leave half of it behind.
        w.take(tSpan) { span(it.groupValues, 1) != null }?.let { setSpan(it.groupValues, 1) }
        // Without "from", only when am/pm or minutes make it clear ("ten to twelve" is 11:50).
        w.take(tSpanBare) { m ->
            val g = m.groupValues
            (g[2].isNotEmpty() || g[3].isNotEmpty() || g[5].isNotEmpty() || g[6].isNotEmpty()) && span(g, 1) != null
        }?.let { setSpan(it.groupValues, 1) }
        w.take(tClock)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[3]
            when {
                ampm.isNotEmpty() -> setTime(withAmPm(h, ampm), min)
                evening && h in 1..11 -> setTime(h + 12, min)
                // "at 3:15" is in the afternoon; nobody means 3:15 at night without saying so.
                h in 1..5 && !morning -> setTime(h + 12, min)
                else -> setTime(h, min, if (morning) Loose.NONE else Loose.CLOCK)
            }
        }
        w.take(tAmPm)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(withAmPm(h, m.groupValues[2]), 0)
        }
        w.take(tQuarter)?.let { m ->
            val h = num(m.groupValues[2]) ?: return@let
            when (m.groupValues[1].lowercase()) {
                "past", "after" -> setTime(colloquial(h), 15, spokenLoose)
                else -> setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 45, spokenLoose)
            }
        }
        w.take(tHalfPast)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 30, spokenLoose)
        }
        w.take(tOclock)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 0, spokenLoose)
        }
        if (w.take(tNoon) != null) setTime(12, 0)
        // A minute before, so it stays on the day that was meant.
        if (w.take(tMidnight) != null) setTime(23, 59)

        // "a week from Friday" before "Friday" and "in a week".
        w.take(dWeekFrom)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val base = when (m.groupValues[2].lowercase()) {
                "today", "now" -> today
                "tomorrow" -> today.plusDays(1)
                else -> nextWeekday(weekday(m.groupValues[3]) ?: return@let)
            }
            if (date == null) date = base.plusWeeks(n.toLong())
        }

        if (w.take(dHalfHour) != null) exact = now.plusMinutes(30)
        if (exact == null && w.take(dQuarterHour) != null) exact = now.plusMinutes(15)
        if (exact == null) w.take(dRelative)?.let { m ->
            val n = (num(m.groupValues[1]) ?: return@let).toLong()
            val unit = m.groupValues[2].lowercase()
            when {
                unit.startsWith("min") -> exact = now.plusMinutes(n)
                unit.startsWith("hour") -> exact = now.plusHours(n)
                unit.startsWith("day") -> date = today.plusDays(n)
                unit.startsWith("week") -> date = today.plusWeeks(n)
                else -> date = today.plusMonths(n)
            }
        }
        // "in 20 minutes", "in 2 hours": something to be reminded of, not a calendar appointment.
        val soon = exact != null

        if (date == null) w.take(dDayMonth)?.let { m ->
            val mo = month(m.groupValues[2]) ?: return@let
            date = dayMonth(m.groupValues[1].toInt(), mo, m.groupValues[3].toIntOrNull())
        }
        if (date == null) w.take(dMonthDay)?.let { m ->
            val mo = month(m.groupValues[1]) ?: return@let
            date = dayMonth(m.groupValues[2].toInt(), mo, m.groupValues[3].toIntOrNull())
        }
        if (date == null) w.take(dSlash)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val (mo, d) = when {
                a > 12 -> b to a
                b > 12 -> a to b
                monthFirst -> a to b
                else -> b to a
            }
            date = dayMonth(d, mo, m.groupValues[3].toIntOrNull())
        }
        if (date == null && w.take(dDayAfter) != null) date = today.plusDays(2)
        var tonight = false
        if (date == null && w.take(dTonight) != null) {
            date = today
            tonight = true
        }
        if (date == null && w.take(dToday) != null) date = today
        if (date == null && w.take(dTomorrow) != null) date = today.plusDays(1)
        // "Wednesday next week": the Wednesday in the week after this one.
        if (date == null) w.take(dWeekdayNextWeek)?.let { m ->
            val target = weekday(m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: return@let
            date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays((target - 1).toLong())
        }
        // "Friday the 20th"
        if (date == null) w.take(dWeekdayDom)?.let { m ->
            val target = weekday(m.groupValues[1]) ?: return@let
            date = dayOfMonth(m.groupValues[2].ifEmpty { m.groupValues[3] }.toInt(), target)
        }
        if (date == null) w.take(dWeekday)?.let { m ->
            val target = weekday(m.groupValues[2]) ?: return@let
            date = nextWeekday(target)
            // "every Monday", "on Mondays" mean every week.
            val lead = m.groupValues[1].lowercase()
            if (repeat == null && (lead == "every" || m.groupValues[3].isNotEmpty())) repeat = Repeat(RepeatUnit.WEEK, 1)
        }
        if (date == null) w.take(dWeekend)?.let { m ->
            val next = m.groupValues[1].lowercase() == "next"
            val dow = today.dayOfWeek.value
            // Like "next Friday": the coming one, so nothing is missed; on a weekend "next" is the one after.
            date = if (dow >= 6 && !next) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        }
        // "on the 15th", "the 1st of next month", "every month on the 1st". A bare number is no day.
        if (date == null) w.take(dDayOfMonth) { m ->
            val g = m.groupValues
            g[4].isNotEmpty() || (g[2].isNotEmpty() && (g[1].isNotEmpty() || g[5].isNotEmpty() || repeat?.unit == RepeatUnit.MONTH))
        }?.let { m ->
            val from = today.withDayOfMonth(1).let { if (m.groupValues[5].lowercase() == "next") it.plusMonths(1) else it }
            date = dayOfMonth(m.groupValues[3].toInt(), null, from)
        }
        if (date == null && w.take(dNextWeek) != null) date = today.plusWeeks(1)
        if (date == null && w.take(dNextMonth) != null) date = today.plusMonths(1)
        // "this afternoon" on its own means today.
        if (date == null && thisPartOfDay.containsMatchIn(w.s)) date = today
        // A weekday said along with a date ("Friday, November 20th", "tomorrow Friday") is not part of the title.
        date?.let { d -> w.take(dWeekday) { it.groupValues[1].isEmpty() && weekday(it.groupValues[2]) == d.dayOfWeek.value } }

        // "at 3" without am/pm: only after the dates are out, so a day of the month is not taken for an hour.
        if (time == null) w.take(tAt)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 0, spokenLoose)
        }

        // Time-of-day words only count when there is a date, a time, a reminder or a series to attach them to.
        if (date != null || time != null || isReminder || repeat != null) {
            val m = w.take(todWord)
            if (time == null && exact == null) {
                val word = m?.groupValues?.get(1)?.lowercase()
                time = when (word) {
                    null -> seriesTime ?: if (tonight) LocalTime.of(19, 0) else null
                    "early" -> LocalTime.of(8, 0)
                    "morning" -> LocalTime.of(9, 0)
                    "afternoon" -> LocalTime.of(15, 0)
                    "evening" -> LocalTime.of(19, 0)
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
            // "today at 8" in the evening means 8 pm. So does a bare "at 8" once 8 am is over,
            // but not "at 10" or "at 11", which are more often tomorrow morning.
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
        val fallback = if (kind == Kind.REMINDER) "Reminder" else "Appointment"
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
