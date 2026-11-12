package de.silvertax.merks.parse

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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

    private const val L = "(?<![\\p{L}\\d'])"
    private const val R = "(?![\\p{L}\\d])"
    private const val PRE = "(?:(?:on|at|around|by|for|from|about|this)\\s+)*"
    private const val HOUR = "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private const val COUNT =
        "(\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five)"
    private const val AMPM = "(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)"
    private const val ORD = "(\\d{1,2})(?:st|nd|rd|th)?"
    private val MONTHS = (months + monthShort.keys).joinToString("|")
    private val DAYS = weekdays.keys.joinToString("|")

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun num(s: String): Int? = s.toIntOrNull() ?: numWords[s.lowercase()]
    private fun month(s: String): Int? = months.indexOf(s.lowercase()).takeIf { it >= 0 }?.plus(1) ?: monthShort[s.lowercase()]
    private fun pm(s: String) = s.lowercase().startsWith("p")

    private class Work(var s: String) {
        fun take(r: Regex): MatchResult? {
            val m = r.find(s) ?: return null
            s = s.replaceRange(m.range, " ")
            return m
        }
    }

    // Times
    private val tClock = re("$L$PRE(\\d{1,2})[:.](\\d{2})(?:\\s*$AMPM)?$R")
    private val tAmPm = re("$L$PRE$HOUR\\s*$AMPM(?![\\p{L}])")
    private val tOclock = re("$L$PRE${HOUR}\\s*o'?\\s?clock$R")
    private val tHalfPast = re("$L${PRE}half\\s+past\\s+$HOUR$R")
    private val tQuarter = re("$L$PRE(?:a\\s+)?quarter\\s+(past|after|to|before)\\s+$HOUR$R")
    private val tNoon = re("$L$PRE(noon|midday|lunchtime)$R")
    private val tAt = re("${L}(?:at|around)\\s+$HOUR$R")

    // Dates
    private val dHalfHour = re("${L}in\\s+(?:a\\s+)?half\\s+(?:an\\s+)?hour$R")
    private val dQuarterHour = re("${L}in\\s+a\\s+quarter\\s+of\\s+an\\s+hour$R")
    private val dRelative = re("${L}in\\s+$COUNT\\s+(minutes?|mins?|hours?|days?|weeks?|months?)$R")
    private val dDayMonth = re("$L$PRE(?:the\\s+)?$ORD\\s+(?:of\\s+)?($MONTHS)\\.?(?:,?\\s+(\\d{4}))?$R")
    private val dMonthDay = re("$L$PRE($MONTHS)\\.?\\s+(?:the\\s+)?$ORD(?:,?\\s+(\\d{4}))?$R")
    private val dDayAfter = re("$L$PRE(?:the\\s+)?day\\s+after\\s+tomorrow$R")
    private val dTonight = re("${L}tonight$R")
    private val dToday = re("$L${PRE}today$R")
    private val dTomorrow = re("$L${PRE}tomorrow$R")
    private val dWeekday = re("$L$PRE(?:(next|this|coming|every|the)\\s+)?($DAYS)(s?)$R")
    private val dNextWeek = re("${L}next\\s+week$R")
    private val dNextMonth = re("${L}next\\s+month$R")
    private val dDayOfMonth = re("$L$PRE(?:the\\s+)?$ORD$R")

    // Repeats
    private val rDaily = re("$L(?:every\\s+day|each\\s+day|daily|every\\s+(morning|evening|night))$R")
    private val rEveryN = re("${L}every\\s+(other|$COUNT)\\s+(days?|weeks?|months?|years?)$R")
    private val rWeekly = re("$L(?:every\\s+week|each\\s+week|weekly)$R")
    private val rMonthly = re("$L(?:every\\s+month|each\\s+month|monthly)$R")
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
    private val tailFiller = re("\\s(?:at|on|in|and|for|by)$")

    fun parse(text: String, now: LocalDateTime): Parsed {
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
        var date: LocalDate? = null
        var exact: LocalDateTime? = null
        val today = now.toLocalDate()

        fun setTime(h: Int, m: Int) {
            if (time == null && h in 0..23 && m in 0..59) time = LocalTime.of(h, m)
        }

        // Repeats first, so "every evening" is not read as just "evening".
        var repeat: Repeat? = null
        var seriesTime: LocalTime? = null
        w.take(rEveryN)?.let { m ->
            val n = if (m.groupValues[1].lowercase() == "other") 2 else num(m.groupValues[2]) ?: return@let
            val unit = when (m.groupValues[3].lowercase().removeSuffix("s")) {
                "day" -> RepeatUnit.DAY
                "week" -> RepeatUnit.WEEK
                "month" -> RepeatUnit.MONTH
                else -> RepeatUnit.YEAR
            }
            if (n >= 1) repeat = Repeat(unit, n)
        }
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

        w.take(tClock)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[3]
            setTime(if (ampm.isNotEmpty()) withAmPm(h, ampm) else if (evening && h in 1..11) h + 12 else h, min)
        }
        w.take(tAmPm)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(withAmPm(h, m.groupValues[2]), 0)
        }
        w.take(tQuarter)?.let { m ->
            val h = num(m.groupValues[2]) ?: return@let
            when (m.groupValues[1].lowercase()) {
                "past", "after" -> setTime(colloquial(h), 15)
                else -> setTime(colloquial(if (h - 1 == 0) 12 else h - 1), 45)
            }
        }
        w.take(tHalfPast)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 30)
        }
        w.take(tOclock)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 0)
        }
        if (w.take(tNoon) != null) setTime(12, 0)

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

        fun dayMonth(d: Int, mo: Int, y: Int?): LocalDate? = try {
            if (y != null) LocalDate.of(y, mo, d)
            else LocalDate.of(today.year, mo, d).let { if (it.isBefore(today)) it.plusYears(1) else it }
        } catch (e: Exception) {
            null
        }

        if (date == null) w.take(dDayMonth)?.let { m ->
            val mo = month(m.groupValues[2]) ?: return@let
            date = dayMonth(m.groupValues[1].toInt(), mo, m.groupValues[3].toIntOrNull())
        }
        if (date == null) w.take(dMonthDay)?.let { m ->
            val mo = month(m.groupValues[1]) ?: return@let
            date = dayMonth(m.groupValues[2].toInt(), mo, m.groupValues[3].toIntOrNull())
        }
        if (date == null && w.take(dDayAfter) != null) date = today.plusDays(2)
        var tonight = false
        if (date == null && w.take(dTonight) != null) {
            date = today
            tonight = true
        }
        if (date == null && w.take(dToday) != null) date = today
        if (date == null && w.take(dTomorrow) != null) date = today.plusDays(1)
        if (date == null) w.take(dWeekday)?.let { m ->
            val target = weekdays[m.groupValues[2].lowercase()] ?: return@let
            var diff = (target - today.dayOfWeek.value + 7) % 7
            if (diff == 0) diff = 7
            date = today.plusDays(diff.toLong())
            // "every Monday", "on Mondays" mean every week.
            val lead = m.groupValues[1].lowercase()
            if (repeat == null && (lead == "every" || m.groupValues[3].isNotEmpty())) repeat = Repeat(RepeatUnit.WEEK, 1)
        }
        if (date == null && w.take(dNextWeek) != null) date = today.plusWeeks(1)
        if (date == null && w.take(dNextMonth) != null) date = today.plusMonths(1)
        // "every month on the 1st"
        if (date == null && repeat?.unit == RepeatUnit.MONTH) w.take(dDayOfMonth)?.let { m ->
            val day = m.groupValues[1].toInt()
            if (day in 1..28) {
                val thisMonth = today.withDayOfMonth(day)
                date = if (thisMonth.isBefore(today)) thisMonth.plusMonths(1) else thisMonth
            }
        }

        // "at 3" without am/pm: only after the dates are out, so a day of the month is not taken for an hour.
        if (time == null) w.take(tAt)?.let { m ->
            val h = num(m.groupValues[1]) ?: return@let
            setTime(colloquial(h), 0)
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
            val t = time
            val d = date ?: if (t != null && t.isAfter(now.toLocalTime())) today else today.plusDays(1)
            if (t == null) {
                allDay = true
                start = d.atStartOfDay()
            } else {
                start = d.atTime(t)
            }
        }

        val kind = when {
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
        val fallback = if (kind == Kind.REMINDER) "Reminder" else "Appointment"
        val title = tidy(w.s, removeFillers = true).ifEmpty { fallback }
        return Parsed(kind, title, start, null, allDay, repeat)
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
