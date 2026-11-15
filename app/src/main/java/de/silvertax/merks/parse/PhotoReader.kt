package de.silvertax.merks.parse

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * One date found on a photo. [time] null means the whole day; [lastDay] is set when it lasts several
 * days, [end] when an end time is given.
 */
data class PhotoDate(
    val day: LocalDate,
    val time: LocalTime? = null,
    val end: LocalTime? = null,
    val lastDay: LocalDate? = null,
    /** The words it was read from. They are kept with the entry instead of the whole text. */
    val source: String = "",
    /** Ticked on the screen from the start: what is plainly an appointment, not a deadline. */
    val chosen: Boolean = true,
) {
    /** As an appointment for the calendar, under [title]. */
    fun toParsed(title: String): Parsed = Parsed(
        Kind.APPOINTMENT,
        title,
        day.atTime(time ?: LocalTime.MIDNIGHT),
        when {
            time == null -> lastDay?.atStartOfDay()
            end != null -> day.atTime(end)
            else -> null
        },
        allDay = time == null,
    )
}

/** What a photo says: what it is about, what goes with it, and the dates on it. */
data class PhotoFind(
    /** Empty when nothing fitting was found; the screen then offers a plain word. */
    val title: String,
    val note: String?,
    val dates: List<PhotoDate>,
    /** The text read on the phone; empty when the AI read the photo. */
    val text: String = "",
    val byAi: Boolean = false,
)

/**
 * Finds appointments in the text read from a photo: an appointment card, a letter, a poster.
 * Text recognition makes mistakes and mixes columns, so dates and times are looked for line by line
 * and put together, and what is plainly not the appointment stays out: the letter's own date,
 * birthdays, validity dates, phone numbers and whatever is over. Pure Kotlin, for tests on the JVM.
 */
object PhotoReader {
    /** More than this on one photo is a list of something else, not appointments. */
    const val MAX_DATES = 12

    private val monthNames: Map<String, Int> = buildMap {
        listOf(
            "januar", "februar", "märz", "april", "mai", "juni", "juli",
            "august", "september", "oktober", "november", "dezember",
        ).forEachIndexed { i, m -> put(m, i + 1) }
        listOf(
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
        ).forEachIndexed { i, m -> put(m, i + 1) }
        put("jänner", 1)
        put("maerz", 3)
        // Short forms, with or without a dot: "Nov.", "Dez", "Sept."
        listOf(
            "jan" to 1, "feb" to 2, "mär" to 3, "mrz" to 3, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7,
            "aug" to 8, "sep" to 9, "sept" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "dez" to 12, "dec" to 12,
        ).forEach { (k, v) -> put(k, v) }
    }

    private val weekdayNames: Map<String, Int> = mapOf(
        "montag" to 1, "dienstag" to 2, "mittwoch" to 3, "donnerstag" to 4, "freitag" to 5,
        "samstag" to 6, "sonnabend" to 6, "sonntag" to 7,
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7,
        "mo" to 1, "di" to 2, "mi" to 3, "do" to 4, "fr" to 5, "sa" to 6, "so" to 7,
        "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4,
        "fri" to 5, "sat" to 6, "sun" to 7,
    )

    private fun alternatives(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|")
    private val MONTH = alternatives(monthNames.keys)
    private val WEEKDAY = alternatives(weekdayNames.keys)

    // Word boundaries that also work with umlauts, and a space within one line: a date or a time
    // never runs over a line break.
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private const val S = "[ \\t]"
    private const val ORD = "(?:st|nd|rd|th)?"
    // "am" only as a time when no word follows: in German, "Raum 1 am Ende" is no 1 o'clock.
    private const val AM_PM = "(?:a\\.m\\.|p\\.m\\.|pm(?!\\p{L})|am(?![ \\t]*\\p{L}))"
    private const val UNIT = "(uhr|h(?!\\p{L})|$AM_PM)"

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // Dates, the most specific forms first; a later form never takes text an earlier one took.
    private val numericRange = re("$L(\\d{1,2})\\.$S?(?:(\\d{1,2})\\.)?$S*(?:-|bis)$S*(\\d{1,2})\\.$S?(\\d{1,2})\\.(\\d{4}|\\d{2})?(?![\\d:])")
    private val nameRange = re("$L(\\d{1,2})\\.?$S*(?:-|bis)$S*(\\d{1,2})\\.?$S*($MONTH)\\.?(?:$S*,?$S*(\\d{4}))?$R")
    private val nameRangeEn = re("$L($MONTH)\\.?$S+(\\d{1,2})$ORD$S*(?:-|to|until|through)$S*(\\d{1,2})$ORD(?:$S*,?$S*(\\d{4}))?$R")
    private val numeric = re("$L(\\d{1,2})\\.$S?(\\d{1,2})\\.(?:(\\d{4}|\\d{2})|$S(\\d{4}))?(?![\\d:])")
    private val iso = re("$L(\\d{4})-(\\d{1,2})-(\\d{1,2})$R")
    private val slashed = re("$L(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})(?![\\d/])")
    private val dayName = re("$L(\\d{1,2})(?:\\.|$ORD)$S*(?:of$S+)?($MONTH)\\.?(?:$S*,?$S*(\\d{4}))?$R")
    private val nameDay = re("$L($MONTH)\\.?$S+(\\d{1,2})$ORD$R(?:$S*,?$S*(\\d{4}))?")

    /** "3.12" without the last dot: only right after a weekday or "am", or it could be a room number. */
    private val bareNumeric = re("$L(\\d{1,2})\\.(\\d{1,2})(?![\\d.:,])")
    private val bareContext = re("(?:$L(?:$WEEKDAY|am|den|ab|vom|zum|on)\\.?,?)$S{1,3}$")
    private val relative = re("$L(heute|übermorgen|morgen|today|tomorrow)$R")

    // Times. A dot or a comma only counts with "Uhr" or after "um": "Zimmer 2.14" is no time.
    private val timeRange = re(
        "$L(?:von$S+|from$S+)?(\\d{1,2})(?:($S?:$S?|[.,])(\\d{2}))?$S*(uhr$S*)?(?:-|bis|to|until|till)$S*" +
            "(\\d{1,2})(?:($S?:$S?|[.,])(\\d{2}))?(?!\\d)$S*$UNIT?",
    )
    private val colonTime = re("$L(\\d{1,2})$S?:$S?(\\d{2})(?!\\d)(?:$S*$UNIT)?")
    private val dotTime = re("$L(\\d{1,2})[.,](\\d{2})$S*(uhr|h(?!\\p{L}))")
    private val hourTime = re("$L(\\d{1,2})$S*(uhr|$AM_PM)")
    private val bareDotTime = re("$L(\\d{1,2})\\.(\\d{2})(?!\\d)(?!\\.\\d)")
    private val timeContext = re("$L(?:um|gegen|at)$S{1,3}$")

    // What stands before a date that is not an appointment.
    private val notAnAppointment = re(
        "(?<!\\p{L})(?:geb\\.?|geboren|geburtsdatum|stand|druckdatum|gedruckt|erstellt|rechnungsdatum|rechnung vom|" +
            "schreiben vom|brief vom|nachricht vom|ausgestellt|ausstellungsdatum|gültig\\p{L}*|ablauf\\p{L}*|haltbar\\p{L}*|" +
            "mhd|date of birth|birth\\p{L}*|born|dob|printed|issued|valid\\p{L}*|expir\\p{L}*)\\W*(?:am|den|on|bis|ab)?\\W*$",
    )
    private val deadline = re(
        "(?<!\\p{L})(?:bis|spätestens|anmeldung|anmeldeschluss|rückmeldung|abgabe|einsendeschluss|zahlbar|fällig|" +
            "by|deadline|until|due|before)\\W*(?:zum|am|den|on|the)?\\W*$",
    )

    // A line that is only "Radebeul, 10.11.2026" or "Radebeul, den 10. November": the letter's own date.
    private val placeAndDate = re("^\\W*(\\p{L}[\\p{L} .'-]{1,40}?),$S*(?:den$S+)?$")

    private val weekdayEnd = re("($WEEKDAY)\\.?,?$S*(?:den$S+|the$S+)?$")

    // What a time on the next line may start with to belong to a date at the end of this one.
    private val timeLead = re("^\\W*(?:um|ab|von|gegen|beginn|uhrzeit|zeit|time|at|from)?\\W*$")

    private class DateHit(
        val line: Int,
        val from: Int,
        val to: Int,
        val day: LocalDate,
        val lastDay: LocalDate? = null,
        val deadline: Boolean = false,
        /** "heute" and "morgen" only count with a time, or "Guten Morgen" would be an appointment. */
        val needsTime: Boolean = false,
    ) {
        var time: TimeHit? = null
        var timeLine: Int = -1
    }

    private class TimeHit(val line: Int, val from: Int, val to: Int, val start: LocalTime, val end: LocalTime?) {
        var used = false
    }

    fun read(text: String, now: LocalDateTime, monthFirst: Boolean = RuleParserEn.monthFirstHere()): PhotoFind {
        val lines = tidy(text)
        val today = now.toLocalDate()
        val times = lines.flatMapIndexed { i, l -> timesIn(i, l) }
        val dates = lines.flatMapIndexed { i, l -> datesIn(i, l, today, monthFirst, times.filter { it.line == i }) }
        pair(dates, times, lines)

        val found = dates.mapNotNull { d ->
            val t = d.time
            if (d.needsTime && t == null) return@mapNotNull null
            val start = t?.start
            // What is over stays out, and so does a date too far ahead to be meant (often misread).
            if (start == null && d.day.isBefore(today)) return@mapNotNull null
            if (start != null && d.day.atTime(start).isBefore(now)) return@mapNotNull null
            if (d.day.isAfter(today.plusYears(3))) return@mapNotNull null
            PhotoDate(d.day, start, t?.end, d.lastDay, source(lines, d), chosen = !d.deadline)
        }
        val dated = tidyUp(found)
        return PhotoFind(title(lines), note(lines, dates), dated, text.trim())
    }

    /** Lines without stray marks, with the most common misreadings inside numbers put right. */
    private fun tidy(text: String): List<String> = text.lines().map { line ->
        line.replace('–', '-').replace('—', '-').replace('‚', ',')
            .replace(Regex("(?<=\\d)[Oo](?![\\p{L}])|(?<=[\\d.:])[Oo](?=\\d)"), "0")
            .replace(Regex("(?<=\\d)[lI|](?=[\\d.:])|(?<=[\\d.:])[lI|](?=\\d)"), "1")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun timeOf(hour: Int, minute: Int, unit: String?): LocalTime? {
        if (minute !in 0..59) return null
        val u = unit?.lowercase()?.replace(".", "")
        val h = when (u) {
            "pm" -> if (hour in 1..11) hour + 12 else if (hour == 12) 12 else return null
            "am" -> if (hour == 12) 0 else if (hour in 1..11) hour else return null
            else -> hour
        }
        return if (h in 0..23) LocalTime.of(h, minute) else null
    }

    private fun timesIn(line: Int, text: String): List<TimeHit> {
        val hits = mutableListOf<TimeHit>()
        fun free(r: IntRange) = hits.none { it.from <= r.last && r.first < it.to }

        for (m in timeRange.findAll(text)) {
            val (h1, sep1, m1, uhr1, h2, sep2, m2) = m.destructured
            val unit = m.groupValues[8]
            // "1-3" alone is no time, and "1.12. - 3.12." are days: a colon on both sides or a "Uhr" make it one.
            val sure = (':' in sep1 && ':' in sep2) || unit.isNotEmpty() || uhr1.isNotEmpty()
            if (!sure) continue
            val endUnit = unit.ifEmpty { null }
            // "9 - 11 am": the unit at the end counts for both.
            val start = timeOf(h1.toInt(), m1.toIntOrNull() ?: 0, endUnit?.takeIf { it.lowercase() != "uhr" && it.lowercase() != "h" })
            val end = timeOf(h2.toInt(), m2.toIntOrNull() ?: 0, endUnit)
            if (start == null || end == null || !end.isAfter(start)) continue
            hits += TimeHit(line, m.range.first, m.range.last + 1, start, end)
        }
        for (m in colonTime.findAll(text)) {
            if (!free(m.range)) continue
            val t = timeOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null }) ?: continue
            hits += TimeHit(line, m.range.first, m.range.last + 1, t, null)
        }
        for (m in dotTime.findAll(text)) {
            if (!free(m.range)) continue
            val t = timeOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null) ?: continue
            hits += TimeHit(line, m.range.first, m.range.last + 1, t, null)
        }
        for (m in hourTime.findAll(text)) {
            if (!free(m.range)) continue
            val t = timeOf(m.groupValues[1].toInt(), 0, m.groupValues[2]) ?: continue
            hits += TimeHit(line, m.range.first, m.range.last + 1, t, null)
        }
        for (m in bareDotTime.findAll(text)) {
            if (!free(m.range) || !timeContext.containsMatchIn(text.substring(0, m.range.first))) continue
            val t = timeOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null) ?: continue
            hits += TimeHit(line, m.range.first, m.range.last + 1, t, null)
        }
        return hits.sortedBy { it.from }
    }

    private fun datesIn(line: Int, text: String, today: LocalDate, monthFirst: Boolean, times: List<TimeHit>): List<DateHit> {
        val taken = mutableListOf<IntRange>()
        val hits = mutableListOf<DateHit>()
        fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last } &&
            times.none { it.from <= r.last && r.first < it.to }

        fun add(m: MatchResult, day: LocalDate?, lastDay: LocalDate? = null, needsTime: Boolean = false) {
            taken += m.range
            if (day == null) return
            val before = text.substring(0, m.range.first)
            if (notAnAppointment.containsMatchIn(before.takeLast(40))) return
            if (letterDate(text, m.range) && times.isEmpty()) return
            val isDeadline = deadline.containsMatchIn(before.takeLast(30))
            hits += DateHit(line, m.range.first, m.range.last + 1, day, lastDay?.takeIf { it.isAfter(day) }, isDeadline, needsTime)
        }
        fun weekdayBefore(r: IntRange): Int? {
            val before = text.substring(0, r.first)
            val w = weekdayEnd.find(before) ?: return null
            val start = w.range.first
            if (start > 0 && text[start - 1].isLetterOrDigit()) return null
            return weekdayNames[w.groupValues[1].lowercase()]
        }

        for (m in numericRange.findAll(text)) {
            if (!free(m.range)) continue
            val (d1, m1, d2, m2, y) = m.destructured
            val last = resolve(d2.toInt(), m2.toInt(), y.toIntOrNull(), null, today)
            val first = last?.let { l -> date(l.year, m1.toIntOrNull() ?: m2.toInt(), d1.toInt())?.let { if (it.isAfter(l)) it.minusYears(1) else it } }
            add(m, first, last)
        }
        for (m in nameRange.findAll(text)) {
            if (!free(m.range)) continue
            val (d1, d2, name, y) = m.destructured
            val month = monthNames[name.lowercase()] ?: continue
            val first = resolve(d1.toInt(), month, y.toIntOrNull(), null, today)
            add(m, first, first?.let { date(it.year, month, d2.toInt()) })
        }
        for (m in nameRangeEn.findAll(text)) {
            if (!free(m.range)) continue
            val (name, d1, d2, y) = m.destructured
            val month = monthNames[name.lowercase()] ?: continue
            val first = resolve(d1.toInt(), month, y.toIntOrNull(), null, today)
            add(m, first, first?.let { date(it.year, month, d2.toInt()) })
        }
        for (m in numeric.findAll(text)) {
            if (!free(m.range)) continue
            val y = m.groupValues[3].ifEmpty { m.groupValues[4] }.toIntOrNull()
            add(m, resolve(m.groupValues[1].toInt(), m.groupValues[2].toInt(), y, weekdayBefore(m.range), today))
        }
        for (m in iso.findAll(text)) {
            if (!free(m.range)) continue
            add(m, date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
        }
        for (m in slashed.findAll(text)) {
            if (!free(m.range)) continue
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val (d, mo) = if (monthFirst) b to a else a to b
            add(m, resolve(d, mo, m.groupValues[3].toInt(), weekdayBefore(m.range), today))
        }
        for (m in dayName.findAll(text)) {
            if (!free(m.range)) continue
            val month = monthNames[m.groupValues[2].lowercase()] ?: continue
            add(m, resolve(m.groupValues[1].toInt(), month, m.groupValues[3].toIntOrNull(), weekdayBefore(m.range), today))
        }
        for (m in nameDay.findAll(text)) {
            if (!free(m.range)) continue
            val month = monthNames[m.groupValues[1].lowercase()] ?: continue
            add(m, resolve(m.groupValues[2].toInt(), month, m.groupValues[3].toIntOrNull(), weekdayBefore(m.range), today))
        }
        for (m in bareNumeric.findAll(text)) {
            if (!free(m.range) || !bareContext.containsMatchIn(text.substring(0, m.range.first))) continue
            add(m, resolve(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, weekdayBefore(m.range), today))
        }
        for (m in relative.findAll(text)) {
            if (!free(m.range)) continue
            val before = text.substring(0, m.range.first).trimEnd().lowercase()
            // "Guten Morgen", "am Morgen", "jeden Morgen" are times of day, not tomorrow.
            if (before.endsWith("guten") || before.endsWith("am") || before.endsWith("jeden") || before.endsWith("good")) continue
            val day = when (m.groupValues[1].lowercase()) {
                "heute", "today" -> today
                "übermorgen" -> today.plusDays(2)
                else -> today.plusDays(1)
            }
            add(m, day, needsTime = true)
        }
        return hits.sortedBy { it.from }
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: Exception) {
        null
    }

    /**
     * The date as meant: a two-digit year in this century, a missing year the next time that day
     * comes, unless it was only a few weeks ago (an old card) or a weekday beside it says otherwise.
     */
    private fun resolve(d: Int, m: Int, y: Int?, weekday: Int?, today: LocalDate): LocalDate? {
        if (m !in 1..12 || d !in 1..31) return null
        if (y != null) {
            val year = if (y < 100) 2000 + y else y
            if (year !in today.year - 1..today.year + 3) return null
            return date(year, m, d)
        }
        val options = listOfNotNull(date(today.year, m, d), date(today.year + 1, m, d))
        weekday?.let { w -> options.firstOrNull { it.dayOfWeek.value == w && !it.isBefore(today) }?.let { return it } }
        val thisYear = options.firstOrNull { it.year == today.year } ?: return options.firstOrNull()
        return when {
            !thisYear.isBefore(today) -> thisYear
            ChronoUnit.DAYS.between(thisYear, today) <= 60 -> thisYear
            else -> options.firstOrNull { it.year == today.year + 1 }
        }
    }

    /** "Radebeul, 10.11.2026" alone on its line. A weekday in front ("Montag, 30.11.") is no place. */
    private fun letterDate(text: String, r: IntRange): Boolean {
        if (text.substring(r.last + 1).any { it.isLetterOrDigit() }) return false
        val place = placeAndDate.find(text.substring(0, r.first)) ?: return false
        val word = place.groupValues[1].trim().lowercase().trimEnd('.')
        return word !in weekdayNames && !word.endsWith("tag") && !word.endsWith("day")
    }

    /**
     * Gives each date its time: the next one on the same line before another date, one in front of a
     * single date, or one at the start of the next line, as when a letter breaks the line after the date.
     */
    private fun pair(dates: List<DateHit>, times: List<TimeHit>, lines: List<String>) {
        for ((i, d) in dates.withIndex()) {
            val next = dates.getOrNull(i + 1)?.takeIf { it.line == d.line }
            val after = times.firstOrNull { !it.used && it.line == d.line && it.from >= d.to && (next == null || it.from < next.from) }
            if (after != null) {
                d.time = after.also { it.used = true }
                d.timeLine = d.line
            }
        }
        for (d in dates) {
            if (d.time != null) continue
            val onLine = dates.filter { it.line == d.line }
            // Before the date: "10:30 Uhr, Mo 23.11." works for a single date on the line.
            if (onLine.size == 1) {
                times.lastOrNull { !it.used && it.line == d.line && it.to <= d.from }?.let {
                    d.time = it.also { t -> t.used = true }
                    d.timeLine = d.line
                }
            }
            if (d.time != null || onLine.last() !== d) continue
            // At the start of the next line that has text and no date of its own.
            val nextLine = (d.line + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: continue
            if (dates.any { it.line == nextLine }) continue
            val t = times.firstOrNull { !it.used && it.line == nextLine } ?: continue
            if (!timeLead.matches(lines[nextLine].substring(0, t.from))) continue
            d.time = t.also { it.used = true }
            d.timeLine = nextLine
        }
    }

    /** The words a date was read from, at most a line or two. */
    private fun source(lines: List<String>, d: DateHit): String {
        val text = if (d.timeLine >= 0 && d.timeLine != d.line) lines[d.line] + " " + lines[d.timeLine] else lines[d.line]
        return text.trim().let { if (it.length <= 100) it else it.take(100).substringBeforeLast(' ') + " …" }
    }

    /** Duplicates go (the same day once with and once without time), and what is left is sorted. */
    private fun tidyUp(found: List<PhotoDate>): List<PhotoDate> {
        val anyTime = found.any { it.time != null && it.chosen }
        val kept = found
            .distinctBy { Triple(it.day, it.time, it.lastDay) }
            .filter { d -> d.time != null || found.none { it.day == d.day && it.time != null } || d.lastDay != null }
            // With times found, a bare date beside them is a deadline or something else: shown, not ticked.
            .map { if (anyTime && it.time == null && it.lastDay == null) it.copy(chosen = false) else it }
            .sortedWith(compareBy({ it.day }, { it.time }))
        return kept.take(MAX_DATES)
    }

    // ---- What it is about ----

    private val skipLine = re(
        "(?:\\b(?:tel|telefon|fax|mobil|handy|phone|e-?mail|www|http|iban|bic|ust|steuer|seite|page)\\b|@|\\b\\d{5}\\b|" +
            "(?:str\\.|straße|strasse|weg|platz|allee|gasse|ring|damm|ufer|street|road|avenue)$S*\\d)",
    )
    private val greeting = re("^(?:sehr geehrte|liebe|lieber|hallo|guten tag|dear|hello|mit freundlichen|freundliche grüße|viele grüße|beste grüße|kind regards|best regards|sincerely|yours)")
    private val instruction = re("^(?:bitte|please|bei |wir |sie |ihr |ihre |falls|sollten|if |we |you )")
    // Words of a heading that says nothing about the appointment: "Ihr nächster Termin", "Terminkarte".
    private val genericWord = re(
        "(?:ihr|ihre|ihren|your|nächste[rnms]?|next|termin\\p{L}*|appointments?|erinnerung|reminder|" +
            "bestätigung|confirmation|einladung|invitation|karte|card|bitte|please|notieren|note)",
    )
    private val recipient = re("^(?:frau|herrn?|familie|eheleute|mr|mrs|ms|miss)\\.?(?:$S|$)")
    private val academic = re("\\b(Dr|Prof)\\.$S*(?:(?:med|dent|rer|nat|phil|jur|univ|habil|vet|oec|pol|h\\.$S?c)\\.$S*)+")
    private val subject = re(
        "(?:ihr|ihren|ihre|your)$S+(?:nächste[rn]?$S+)?(?:termin|appointment)$S+" +
            "(?:zur|zum|in der|in unserer|in unserem|im|bei|für|for|at|with)$S+(.{3,40}?)\\W*$",
    )
    private val invitation = re("(?:einladung|invitation)$S+(?:zur|zum|to)$S+(?:the$S+)?(.{3,40}?)\\W*$")

    /**
     * A title from the head of the photo: usually the practice or the office, as on its letterhead,
     * with "what" in front when the text says it ("Ihr Termin zur Sehstärkenbestimmung").
     */
    private fun title(lines: List<String>): String {
        val head = header(lines)
        val what = lines.firstNotNullOfOrNull { l ->
            (subject.find(l) ?: invitation.find(l))?.groupValues?.get(1)?.trim()
                ?.takeIf { it.count(Char::isLetter) >= 3 && !hasDateOrTime(it) }
                ?.let { academic.replace(it) { m -> m.groupValues[1] + ". " }.replace(Regex("$S+"), " ").trim() }
        }
        return when {
            what == null -> head.orEmpty()
            head == null || head.equals(what, ignoreCase = true) -> what
            what.length + head.length + 3 <= 50 -> "$what – $head"
            else -> what
        }
    }

    private fun header(lines: List<String>): String? {
        var skipNext = false
        for (line in lines) {
            if (skipNext) {
                skipNext = false
                continue
            }
            // The address of the person the letter is for: the name, and on the next line the street.
            if (recipient.containsMatchIn(line.trim { !it.isLetter() })) {
                skipNext = line.trim { !it.isLetter() }.split(' ').size == 1
                continue
            }
            for (raw in line.split(Regex("$S[-·•|+:]$S|,"))) {
                // A letter cut off at the end ("Zahnarztpraxis D") is a column the recognition split.
                val part = raw.trim().trim { !it.isLetterOrDigit() && it != '.' && it != ')' }.trim()
                    .replace(Regex("$S\\p{L}$"), "")
                if (goodHeader(part)) return academic.replace(part) { it.groupValues[1] + ". " }.replace(Regex("$S+"), " ").trim()
            }
        }
        return null
    }

    private fun goodHeader(part: String): Boolean {
        // Names on letterheads start with a capital; a small letter first is a piece of a word.
        if (part.firstOrNull()?.isLowerCase() == true) return false
        val letters = part.count(Char::isLetter)
        val visible = part.count { !it.isWhitespace() }
        if (letters < 4 || letters < visible * 0.7 || part.length > 50) return false
        if (part.split(' ').none { w -> w.count(Char::isLetter) >= 3 }) return false
        val telling = part.split(' ').filter { w -> w.count(Char::isLetter) > 2 && !genericWord.matches(w.trim { !it.isLetter() }) }
        return telling.isNotEmpty() && !skipLine.containsMatchIn(part) && !greeting.containsMatchIn(part) &&
            !instruction.containsMatchIn(part) && !hasDateOrTime(part)
    }

    private fun hasDateOrTime(s: String): Boolean =
        numeric.containsMatchIn(s) || dayName.containsMatchIn(s) || nameDay.containsMatchIn(s) || colonTime.containsMatchIn(s) ||
            hourTime.containsMatchIn(s) || iso.containsMatchIn(s) || slashed.containsMatchIn(s)

    // ---- What goes with it ----

    private val worthNoting = re(
        "(?:mitbringen|mitzubringen|bringen$S+sie|bring|nüchtern|fasting|zimmer|raum$S|etage|stockwerk|ebene|treffpunkt|" +
            "eingang|ort:|früher|room|floor|building|entrance|meeting point|arrive|anmeldung)",
    )
    private val boilerplate = re("(?:absagen|verhinderung|nicht wahrnehmen|abzusagen|cancel|reschedule|unable to attend)")
    private val place = re("^\\W*((?:im|in der|in|bei|at|in the)$S+\\S.*)$")
    private val sentenceEnd = Regex("[.;](?:\\s|$)")

    /**
     * Short lines worth having in the calendar: what to bring, to come fasting, the room. Cancellation
     * rules and the rest of the letter stay out. A sentence that holds the appointment itself is cut
     * down to the part after its time ("in Zimmer 2.14 zur Abholung Ihres Ausweises").
     */
    private fun note(lines: List<String>, dates: List<DateHit>): String? {
        val notes = mutableListOf<String>()
        fun add(s: String) {
            val n = s.trim().trim(',', ';', '-', ' ').replaceFirstChar { it.uppercase() }
            if (n.length >= 6 && notes.none { it.contains(n, ignoreCase = true) || n.contains(it, ignoreCase = true) }) notes += n
        }
        // The place right after the time: "14:30 - 17:00 Uhr im Gemeindesaal".
        for (d in dates) {
            val t = d.time ?: continue
            if (d.deadline) continue
            val rest = lines[t.line].substring(t.to).substringBefore(',').split(sentenceEnd).first().trim()
            place.find(rest)?.let { add(it.groupValues[1]) }
        }
        for ((i, line) in lines.withIndex()) {
            if (!worthNoting.containsMatchIn(line) || boilerplate.containsMatchIn(line) || skipLine.containsMatchIn(line)) continue
            var sentence = line
            // A sentence that goes on in the next line.
            val next = lines.getOrNull(i + 1)
            if (!line.trimEnd().endsWith('.') && next != null && next.firstOrNull()?.isLowerCase() == true) sentence += " " + next
            val ownDate = dates.filter { (it.line == i || it.timeLine == i) && !it.deadline }
            if (ownDate.isEmpty()) {
                if (sentence.length <= 120) add(sentence)
            } else {
                // The appointment itself is in this line: only what comes after it.
                val end = ownDate.maxOf { d -> maxOf(if (d.line == i) d.to else 0, d.time?.takeIf { it.line == i }?.to ?: 0) }
                val rest = line.substring(end).trim().trimStart(',', ';', '.').trim()
                if (worthNoting.containsMatchIn(rest) && rest.length <= 120) add(rest)
            }
        }
        return notes.take(3).joinToString("\n").ifEmpty { null }
    }
}
