package de.silvertax.merks.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A word read on a picture: its box in pixels, and how sure the reading was, from 0 to 100. */
data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float = 90f,
) {
    val height: Int get() = bottom - top
    val xMid: Float get() = (left + right) / 2f
    val yMid: Float get() = (top + bottom) / 2f
}

/** A picture's pixels as ARGB, [scale] times smaller than the picture the words were read on. */
class Pixels(val width: Int, val height: Int, val argb: IntArray, val scale: Float)

/**
 * Reads a weekly plan: a table with a column for each weekday and, in its cells, what is on when -
 * courses, shifts, lessons. Read as one text, such a table mixes its columns, so here the words come
 * with their places on the picture and are put back into columns and lines. Under each weekday, a
 * line with a time is an appointment; its title is the line above it (or the rest of its own line),
 * and a room or a remark around it ("Raum 12", "(bring shoes)") goes with it. Recognition mistakes
 * are evened out where the plan helps: a course read as "Werkstatl" once and "Werkstatt" four times
 * is "Werkstatt". What is marked with a highlighter is ticked; when nothing is, all is.
 * Pure Kotlin, for tests on the JVM.
 */
object PlanReader {
    /** A highlighter on at least this share of a title's background marks it. */
    private const val MARKED = 0.35f

    /** How far a pixel's colour may be from the paper's and still be paper. */
    private const val PAPER = 0.08f

    private val fullDays = listOf(
        listOf("montag", "monday"),
        listOf("dienstag", "tuesday"),
        listOf("mittwoch", "wednesday"),
        listOf("donnerstag", "thursday"),
        listOf("freitag", "friday"),
        listOf("samstag", "sonnabend", "saturday"),
        listOf("sonntag", "sunday"),
    )
    private val shortDays = listOf(
        listOf("mo", "mon"),
        listOf("di", "tue", "tues"),
        listOf("mi", "wed"),
        listOf("do", "thu", "thur", "thurs"),
        listOf("fr", "fri"),
        listOf("sa", "sat"),
        listOf("so", "sun"),
    )

    private val WEEK = Regex("(?<!\\p{L})(?:KW|Kw|Kalenderwoche|Woche|[Ww]eek|[Ww]k)\\.?\\s*(\\d{1,2})(?!\\d)")
    private val SHORT = Regex("(?<!\\p{L})(Mo|Di|Mi|Do|Fr|Sa|So|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\.?(?!\\p{L})")
    private val DATE = Regex("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})?(?![\\d])")

    /**
     * Whether the text read on a picture looks like a weekly plan: three weekdays or more, four short
     * ones ("Mo Di Mi Do"), or two and a calendar week.
     */
    fun looksLikePlan(text: String): Boolean {
        val full = text.split(Regex("[^\\p{L}]+")).filter { it.length >= 5 }.mapNotNull { dayOf(it, short = false) }.toSet()
        val short = SHORT.findAll(text).mapNotNull { dayOf(it.value, short = true) }.toSet()
        val week = WEEK.containsMatchIn(text)
        return full.size >= 3 || short.size >= 4 || (week && (full.size >= 2 || short.size >= 2))
    }

    /** The weekday a word names, 0 for Monday. A letter misread is all right in a full name. */
    private fun dayOf(word: String, short: Boolean): Int? {
        val n = word.lowercase().filter { it.isLetter() }
        if (n.length >= 5) {
            fullDays.forEachIndexed { i, names -> if (names.any { distance(n, it) <= max(1, it.length / 5) }) return i }
        }
        if (short && word.trimEnd('.', ',', ':').length <= 5) shortDays.forEachIndexed { i, names -> if (n in names) return i }
        return null
    }

    private class Head(val day: Int, val word: OcrWord, val date: String)

    /** One line of words in a column, and what is in it: times, a title, a room. */
    private class Row(val words: List<OcrWord>, h: Float) {
        val y: Float = median(words.map { it.yMid })
        val raw: String = buildString {
            words.forEachIndexed { i, w ->
                // "0 8:30" and "-1 1:30": recognition split one time in two, tightly.
                val tight = i > 0 && words[i - 1].text.last().isDigit() && SPLIT.containsMatchIn(w.text) && w.left - words[i - 1].right <= 0.2f * h
                if (i > 0 && !tight) append(' ')
                append(w.text)
            }
        }
        val clocks: List<Clock> = clocksIn(raw)
        val before: String = clocks.firstOrNull()?.let { raw.substring(0, it.start) } ?: ""
        private val after: String = clocks.lastOrNull()?.let { raw.substring(it.end) } ?: ""
        val rest: String = clean(
            if (clocks.isEmpty()) {
                raw
            } else {
                (listOf(before) + clocks.zipWithNext { a, b -> raw.substring(a.end, b.start) } + after)
                    .filterNot { RANGE.matches(it.replace(UHR, " ")) }
                    .joinToString(" ")
                    .replace(UHR, " ")
                    .replace(LONE_DASH, " ")
            },
        )
        val outside: String
        val inside: String
        init {
            val (o, i) = parens(rest)
            outside = o
            inside = i
        }

        /** Only a room, a code or a remark in brackets: it goes with an appointment, it is none. */
        val place: Boolean = (outside.isEmpty() && inside.isNotEmpty()) || (outside.isNotEmpty() && isPlace(outside))

        /** Words that can be a title. */
        val titled: Boolean = outside.count { it.isLetter() } >= 3 && !place

        /** "Sport, 12:45 -": the end follows on the next line. */
        val open: Boolean = clocks.size == 1 && after.replace(UHR, " ").isNotBlank() && RANGE.matches(after.replace(UHR, " "))
    }

    private class Entry(val column: Int, val start: LocalTime, var end: LocalTime?, var title: String, val titleWords: List<OcrWord>, var open: Boolean) {
        val detail = mutableListOf<String>()
        val rows = mutableListOf<Row>()

        /** Rooms and remarks on the lines after its time; more may follow. */
        var trailing = 0
        var sureness = 0f
        var mark = 0f
    }

    private class Clock(val time: LocalTime, val start: Int, val end: Int)

    private const val UNIT = "(?:\\s?(uhr|h|a\\.m\\.|p\\.m\\.|pm|am(?![ \\t]*\\p{L})))?"
    private val COLON = Regex("(?<![\\d.,:\\p{L}])(\\d{1,3})\\s?:\\s?(\\d{1,2})(?!\\d)$UNIT(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val DOTTED = Regex("(?<![\\d.,:\\p{L}])(\\d{1,2})\\.(\\d{2})(?![\\d.])$UNIT(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val HOUR = Regex("(?<![\\d.,:\\p{L}])(\\d{1,2})\\s?(uhr|h|a\\.m\\.|p\\.m\\.|pm|am(?![ \\t]*\\p{L}))(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("^\\d[:.]\\d")
    private val RANGE = Regex("\\s*(?:-|–|—|=|bis|to|until)?\\s*", RegexOption.IGNORE_CASE)
    private val UHR = Regex("(?i)(?<!\\p{L})uhr(?!\\p{L})")
    private val LONE_DASH = Regex("(?<=^|\\s)[-–—=_]+(?=\\s|$)")
    private val PARENS = Regex("\\([^()]*\\)")

    private fun clocksIn(s: String): List<Clock> {
        val found = (COLON.findAll(s) + DOTTED.findAll(s))
            .mapNotNull { m -> clock(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { Clock(it, m.range.first, m.range.last + 1) } }
            .sortedBy { it.start }
            .fold(mutableListOf<Clock>()) { kept, c -> kept.apply { if (none { it.end > c.start }) add(c) } }
        if (found.isNotEmpty()) return found
        return HOUR.findAll(s).mapNotNull { m -> clock(m.groupValues[1], "00", m.groupValues[2])?.let { Clock(it, m.range.first, m.range.last + 1) } }.toList()
    }

    private fun clock(hour: String, minute: String, unit: String): LocalTime? {
        var h = hour.toInt()
        // A digit too many where a split time was joined, and an 0 read as 8: "88:30".
        if (hour.length == 3) h = hour.takeLast(2).toInt()
        if (h in 24..99 && hour.length == 2 && hour[0] == '8') h = hour[1].digitToInt()
        val m = when {
            minute.length == 2 -> minute.toInt()
            // "9:0": the last 0 was lost.
            minute == "0" -> 0
            else -> return null
        }
        when (unit.lowercase().filter { it.isLetter() }) {
            "pm" -> if (h in 1..11) h += 12
            "am" -> if (h == 12) h = 0
        }
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private val ROOM = Regex("(raum|zimmer|saal|halle|aula|küche|etage|stock|keller|gebäude|room|floor|hall|building|kitchen)$")
    private val ROOM_WORDS = setOf("zi", "og", "eg", "ug", "rm", "station")
    private val SMALL_WORDS = setOf("auf", "im", "in", "bei", "an", "am", "on", "at")

    /** A room or a remark: "Raum 12", "Werkraum, 1. Stock", "Halle B", "EG-12", "max. 8 Personen". */
    private fun isPlace(s: String): Boolean {
        if (Regex("(?i)(?<!\\p{L})max\\.").containsMatchIn(s)) return true
        val tokens = s.split(Regex("[\\s,;:]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val parts = tokens.flatMap { it.split('-', ':', '/') }.map { p -> p.filter { it.isLetter() }.lowercase() }.filter { it.isNotEmpty() }
        if (parts.any { ROOM.containsMatchIn(it) || it in ROOM_WORDS }) return true
        return tokens.all { t -> t.any { it.isDigit() } || t.lowercase() in SMALL_WORDS } && tokens.any { t -> t.any { it.isDigit() } }
    }

    /** What is outside brackets and what is inside; a lone bracket is a misread edge of a highlighter. */
    private fun parens(s: String): Pair<String, String> {
        val inside = PARENS.findAll(s).joinToString(" ") { it.value }
        val outside = PARENS.replace(s, " ").replace('(', ' ').replace(')', ' ')
        return clean(outside) to clean(inside)
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^[^\\p{L}\\d(]+"), "")
        .replace(Regex("[^\\p{L}\\d!)]+$"), "")

    private fun unbracketed(s: String): String = s.trim().let { if (it.startsWith("(") && it.endsWith(")")) it.substring(1, it.length - 1).trim() else it }

    private fun garbage(w: OcrWord, h: Float): Boolean {
        val t = w.text.trim()
        if (t.all { it in "-–—=" }) return false
        if (t.none { it.isLetterOrDigit() } && t != "(" && t != ")") return true
        if (w.height < 0.35f * h) return true
        // Small and unsure, or far too big and unsure: a table line, a stroke of the pen.
        if (w.height < 0.6f * h && w.confidence < 50) return true
        if (w.height > 2.2f * h && w.confidence < 50) return true
        return t.length == 1 && !t[0].isDigit() && w.confidence < 50
    }

    /** Without the words that mostly lie on a word read more surely: one place read twice. */
    private fun sure(words: List<OcrWord>): List<OcrWord> = words.filter { w ->
        val area = (w.right - w.left) * w.height
        words.none { o ->
            o !== w && o.confidence > w.confidence && overlap(w, o) > 0.5f * min(area, (o.right - o.left) * o.height)
        }
    }

    private fun overlap(a: OcrWord, b: OcrWord): Int =
        max(0, min(a.right, b.right) - max(a.left, b.left)) * max(0, min(a.bottom, b.bottom) - max(a.top, b.top))

    /** Words into lines, from left to right: a word goes on the line whose last word ends just before it. */
    private fun chain(words: List<OcrWord>, h: Float, may: (OcrWord, OcrWord, Float) -> Boolean = { _, _, _ -> true }): List<List<OcrWord>> {
        val lines = mutableListOf<MutableList<OcrWord>>()
        for (w in words.sortedBy { it.left }) {
            var best: MutableList<OcrWord>? = null
            for (line in lines) {
                val last = line.last()
                val gap = (w.left - last.right).toFloat()
                val size = max(h, min(w.height, last.height).toFloat())
                if (gap < -0.3f * h || gap > 1.6f * h || abs(w.yMid - last.yMid) > 0.55f * size) continue
                if (!may(last, w, gap)) continue
                if (best == null || abs(w.yMid - last.yMid) < abs(w.yMid - best.last().yMid)) best = line
            }
            if (best == null) lines += mutableListOf(w) else best += w
        }
        return lines
    }

    private fun text(line: List<OcrWord>) = line.joinToString(" ") { it.text }

    /**
     * The appointments in a weekly plan, from the words read on its picture with their places. [pixels],
     * the same picture made smaller, shows what is marked with a highlighter. Null when the words are no
     * plan: no row of weekdays over columns, or no appointment under them.
     */
    fun read(words: List<OcrWord>, now: LocalDateTime, pixels: Pixels? = null): PhotoFind? {
        val all = words.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        val sizes = all.filter { w -> w.text.count { it.isLetterOrDigit() } >= 2 }.map { it.height.toFloat() }
        if (sizes.size < 6) return null
        val h = median(sizes)
        val clean = sure(all.filterNot { garbage(it, h) })
        val lines = chain(clean, h)

        // The weekdays over the columns: a name alone on its line, perhaps with a date ("Mo 16.11.").
        val found = mutableMapOf<Int, Head>()
        for (line in lines.sortedBy { l -> l.minOf { it.top } }) {
            val tokens = line.filter { w -> w.text.any { it.isLetterOrDigit() } }
            if (tokens.isEmpty() || tokens.size > 3) continue
            val first = tokens[0].text.trim()
            val day = dayOf(first, short = first.first().isUpperCase()) ?: continue
            val rest = tokens.drop(1).joinToString(" ") { it.text }.trim()
            if (rest.isNotEmpty() && !Regex("^,?\\s*\\d{1,2}\\.\\s?\\d{1,2}\\.?(\\d{2,4})?$").matches(rest)) continue
            if (day !in found) found[day] = Head(day, tokens[0], rest)
        }
        if (found.size < 2) return null
        val row = median(found.values.map { it.word.yMid })
        val heads = found.filterValues { abs(it.word.yMid - row) <= 4 * h }.toSortedMap()
        if (heads.size < 2) return null
        val days = heads.keys.toList()
        val xs = days.map { heads.getValue(it).word.xMid }
        if (xs.zipWithNext().any { (a, b) -> b <= a }) return null
        // x = a + pitch * day: a column whose name was not read lies where the others say.
        val (a, pitch) = fit(days.map { it.toFloat() }, xs)
        if (pitch < 2.5f * h) return null
        val (c, slope) = fit(xs, days.map { heads.getValue(it).word.bottom.toFloat() })
        val lo = if (days.first() <= 2) 0 else days.first()
        val hi = if (days.last() < 4) 4 else days.last()
        val columns = (lo..hi).toList()
        val centre = columns.associateWith { d -> heads[d]?.word?.xMid ?: (a + pitch * d) }
        val bounds = columns.zipWithNext { p, q -> (centre.getValue(p) + centre.getValue(q)) / 2 }
        fun columnOf(x: Float): Int = columns[bounds.indexOfFirst { x < it }.let { if (it < 0) columns.lastIndex else it }]
        fun headBottom(col: Int): Float = heads[col]?.word?.bottom?.toFloat() ?: (c + slope * centre.getValue(col))
        val left = centre.getValue(columns.first()) - 0.75f * pitch
        val right = centre.getValue(columns.last()) + 0.75f * pitch
        val headWords = heads.values.map { it.word }.toSet()
        val body = clean.filter { w -> w !in headWords && w.xMid in left..right && w.yMid > headBottom(columnOf(w.xMid)) - 0.2f * h }
        val above = clean.filter { w -> w !in headWords && w.bottom < headBottom(columnOf(w.xMid)) - h }

        // Lines again, now kept within their column unless the words stand as close as in a sentence.
        // A sentence across several columns is a note under the plan, no appointment.
        val bodyLines = chain(body, h) { p, q, gap -> columnOf(p.xMid) == columnOf(q.xMid) || gap < 0.8f * h }
            .filter { l -> l.maxOf { it.right } - l.minOf { it.left } <= 1.5f * pitch }
        val byColumn = bodyLines.groupBy { l -> columnOf((l.minOf { it.left } + l.maxOf { it.right }) / 2f) }
        val rowsOf = byColumn.mapValues { (_, ls) -> ls.map { Row(it, h) }.sortedBy { it.y } }
        val gaps = rowsOf.values.flatMap { rs -> rs.zipWithNext { p, q -> q.y - p.y }.filter { it > 0.5f * h } }
        val step = if (gaps.isEmpty()) 1.6f * h else median(gaps)

        val entries = columns.flatMap { col -> entries(col, rowsOf[col].orEmpty(), step) }
        if (entries.isEmpty()) return null

        // A title or a room read slightly otherwise in one place is the spelling the plan has more often.
        val texts = lines.map { key(text(it)) }
        val titles = fixed(entries.map { it.title }, entries.map { it.sureness }, texts)
        entries.forEachIndexed { i, e -> e.title = titles[i] }
        val details = entries.flatMap { e -> e.detail.map { unbracketed(it) }.filter { d -> d.count { it.isLetterOrDigit() } >= 2 } }
        val betterDetails = fixed(details, details.map { 0f }, texts).iterator()
        entries.forEach { e ->
            val kept = e.detail.map { unbracketed(it) }.filter { d -> d.count { it.isLetterOrDigit() } >= 2 }
            e.detail.clear()
            kept.forEach { _ -> e.detail += betterDetails.next() }
        }
        if (pixels != null) marks(entries, clean, pixels, centre, pitch)

        val today = now.toLocalDate()
        val byTop = lines.sortedBy { l -> l.minOf { it.top } }.map { text(it) }
        val text = byTop.joinToString("\n")
        val monday = week(byTop, heads.values, above, today)
        val dates = entries.mapNotNull { e ->
            // Without a week on the plan, each weekday is the next one to come.
            val day = monday?.plusDays(e.column.toLong()) ?: today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(e.column + 1))).let {
                if (it == today && e.start <= now.toLocalTime()) it.plusWeeks(1) else it
            }
            if (day.atTime(e.start).isBefore(now)) return@mapNotNull null
            PhotoDate(
                day,
                e.start,
                e.end?.takeIf { it.isAfter(e.start) },
                source = e.rows.joinToString(" ") { it.raw }.take(100),
                chosen = e.mark >= MARKED,
                title = e.title.take(80),
                detail = e.detail.distinct().joinToString(", ").take(100).ifEmpty { null },
            )
        }
            .distinctBy { Triple(it.day, it.time, key(it.title.orEmpty())) }
            .sortedWith(compareBy({ it.day }, { it.time }))
            .take(PhotoReader.MAX_LISTED)
        if (dates.isEmpty()) return null
        val heading = above.let { chain(it, h) }.map { clean(text(it)) }
            .filter { t -> t.count { it.isLetter() } >= 5 && !WEEK.containsMatchIn(t) }
            .maxByOrNull { t -> t.count { it.isLetter() } }
        // What is marked is ticked; when nothing is, everything is.
        return PhotoFind(heading.orEmpty(), null, if (dates.any { it.chosen }) dates else dates.map { it.copy(chosen = true) }, text)
    }

    /** The appointments in one column, from its lines top to bottom. */
    private fun entries(column: Int, rows: List<Row>, step: Float): List<Entry> {
        val out = mutableListOf<Entry>()
        val pending = mutableListOf<Row>()
        var last: Entry? = null
        var lastRow: Row? = null
        fun takeTrailing() {
            val e = last ?: return
            val r = lastRow ?: return
            while (pending.isNotEmpty() && pending[0].place && pending[0].y - r.y <= 1.6f * step * (1 + e.trailing)) {
                val p = pending.removeAt(0)
                e.detail += p.rest
                e.rows += p
                e.trailing++
            }
        }
        for (row in rows) {
            if (row.clocks.isEmpty()) {
                if (row.titled || row.place) pending += row
                continue
            }
            val prev = last
            val prevRow = lastRow
            val close = prevRow != null && row.y - prevRow.y <= 1.6f * step
            // "Sport, 12:45 -" and below it "13:45 (Halle)": the end of the one before.
            if (prev != null && prev.open && close && pending.isEmpty() && row.before.none { it.isLetterOrDigit() }) {
                prev.end = row.clocks[0].time
                prev.open = false
                listOf(row.outside, row.inside).filter { it.isNotEmpty() }.forEach { prev.detail += it }
                prev.rows += row
                lastRow = row
                continue
            }
            // The same time once more right below, and nothing else: one line read twice.
            if (prev != null && close && pending.isEmpty() && !row.titled && row.clocks[0].time == prev.start) continue
            takeTrailing()
            val detail = mutableListOf<String>()
            val own = row.outside.takeIf { row.outside.count { it.isLetter() } >= 3 && !isPlace(it) }
            if (row.outside.isNotEmpty() && own == null) detail += row.outside
            if (row.inside.isNotEmpty()) detail += row.inside
            val titleRows = mutableListOf<Row>()
            val title = mutableListOf<String>()
            if (own != null) {
                title += own
            } else {
                // The title is right above: one or two lines, perhaps with a room or a remark between.
                val candidates = pending.toMutableList()
                while (candidates.isNotEmpty()) {
                    val p = candidates.last()
                    if (p.place && titleRows.isEmpty()) {
                        candidates.removeAt(candidates.lastIndex)
                        continue
                    }
                    if (p.place || titleRows.size == 2) break
                    val from = titleRows.firstOrNull()?.y ?: row.y
                    if (from - p.y > (if (titleRows.isEmpty()) 2.6f else 1.6f) * step) break
                    titleRows.add(0, p)
                    candidates.removeAt(candidates.lastIndex)
                }
                titleRows.forEach { p ->
                    if (p.outside.isNotEmpty()) title += p.outside
                    if (p.inside.isNotEmpty()) detail += p.inside
                }
                val between = pending.filter { p -> titleRows.isNotEmpty() && p.y > titleRows.last().y && p.place }
                detail.addAll(0, between.map { it.rest })
            }
            pending.clear()
            val name = clean(title.joinToString(" "))
            if (name.count { it.isLetter() } < 3) {
                last = null
                lastRow = null
                continue
            }
            val words = if (titleRows.isEmpty()) row.words else titleRows.flatMap { it.words }
            val e = Entry(column, row.clocks[0].time, row.clocks.getOrNull(1)?.time, name, words, row.open)
            e.detail += detail
            e.rows += titleRows
            e.rows += row
            e.sureness = words.map { it.confidence }.average().toFloat()
            out += e
            last = e
            lastRow = row
        }
        takeTrailing()
        return out
    }

    /** The Monday of the plan's week: from its calendar week, else a date over a column or above. */
    private fun week(lines: List<String>, heads: Collection<Head>, above: List<OcrWord>, today: LocalDate): LocalDate? {
        lines.firstNotNullOfOrNull { WEEK.find(it)?.groupValues?.get(1)?.toInt() }?.let { kw -> weekStart(kw, today)?.let { return it } }
        heads.forEach { head -> date(head.date, today)?.let { return it.minusDays(head.day.toLong()).with(DayOfWeek.MONDAY) } }
        return date(above.sortedBy { it.top }.joinToString(" ") { it.text }, today)?.with(DayOfWeek.MONDAY)
    }

    /** The Monday of calendar week [kw] in the year that puts it nearest to [today]. */
    private fun weekStart(kw: Int, today: LocalDate): LocalDate? = (today.year - 1..today.year + 1).mapNotNull { y ->
        val mid = LocalDate.of(y, 6, 1)
        if (kw < 1 || kw > mid.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).maximum) null else mid.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, kw.toLong()).with(DayOfWeek.MONDAY)
    }.minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }

    private fun date(text: String, today: LocalDate): LocalDate? {
        val m = DATE.find(text) ?: return null
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        return runCatching {
            if (y != null) LocalDate.of(y, mo, d) else (today.year - 1..today.year + 1).map { LocalDate.of(it, mo, d) }.minBy { abs(ChronoUnit.DAYS.between(today, it)) }
        }.getOrNull()
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The numbers in a text: words mostly of digits, with letters that are often misread for digits
     * read as those ("O7" is "07"). A word with a digit misread in it ("0ffene") is no number.
     */
    private fun numbers(s: String): String = s.split(Regex("[^\\p{L}\\d]+")).filter { t -> 2 * t.count { it.isDigit() } >= t.length && t.isNotEmpty() }.joinToString(" ") { t ->
        t.map { ch ->
            when (ch.lowercaseChar()) {
                's' -> '5'
                'o' -> '0'
                'i', 'l' -> '1'
                'b' -> '8'
                else -> ch
            }
        }.filter { it.isDigit() }.joinToString("")
    }

    /**
     * Each of [values] replaced by a slightly different spelling of it that the plan holds more often, as
     * a value or in its [lines], or that was read more surely. Numbers have to agree: "Raum 1" stays.
     */
    private fun fixed(values: List<String>, sureness: List<Float>, lines: List<String>): List<String> {
        val groups = values.indices.groupBy { key(values[it]) }.filterKeys { it.isNotEmpty() }
        // Written alike but for capitals or a comma: the way it is written most often.
        val spelling = groups.mapValues { (_, idx) ->
            idx.groupBy { values[it] }.entries.maxWith(compareBy({ it.value.size }, { e -> e.value.maxOf { sureness[it] } })).key
        }
        val order = compareBy<Triple<Int, Int, Float>>({ it.first }, { it.second }, { it.third })
        val score = groups.mapValues { (k, idx) -> Triple(idx.size, lines.count { k in it }, idx.maxOf { sureness[it] }) }
        val numbers = spelling.mapValues { numbers(it.value) }
        val target = groups.keys.associateWith { k ->
            groups.keys
                .filter { o ->
                    o != k && min(o.length, k.length) >= 5 && numbers[o] == numbers[k] && order.compare(score.getValue(o), score.getValue(k)) > 0 &&
                        distance(k, o) <= max(1, (0.2f * max(k.length, o.length)).roundToInt())
                }
                .maxWithOrNull { p, q -> order.compare(score.getValue(p), score.getValue(q)) } ?: k
        }
        return values.map { v -> target[key(v)]?.let { spelling.getValue(it) } ?: v }
    }

    /**
     * How much of each entry's title is on a highlighter: the share of its background that is clearly
     * not the colour of the paper around it. The paper is taken near the entry, between the words,
     * because light falls unevenly on a photo.
     */
    private fun marks(entries: List<Entry>, words: List<OcrWord>, pixels: Pixels, centre: Map<Int, Float>, pitch: Float) {
        val w = pixels.width
        val ht = pixels.height
        if (w < 4 || ht < 4 || pixels.argb.size < w * ht) return
        val s = pixels.scale
        val nearWord = BooleanArray(w * ht)
        for (word in words) {
            val pad = word.height
            val l = ((word.left - 0.4f * pad) / s).toInt().coerceIn(0, w - 1)
            val r = ((word.right + 0.4f * pad) / s).toInt().coerceIn(0, w - 1)
            val t = ((word.top - 0.6f * pad) / s).toInt().coerceIn(0, ht - 1)
            val b = ((word.bottom + 0.6f * pad) / s).toInt().coerceIn(0, ht - 1)
            for (y in t..b) java.util.Arrays.fill(nearWord, y * w + l, y * w + r + 1, true)
        }
        for (e in entries) {
            if (e.titleWords.isEmpty()) continue
            val l = (e.titleWords.minOf { it.left } / s).toInt().coerceIn(0, w - 1)
            val r = (e.titleWords.maxOf { it.right } / s).toInt().coerceIn(0, w - 1)
            val t = (e.titleWords.minOf { it.top } / s).toInt().coerceIn(0, ht - 1)
            val b = (e.titleWords.maxOf { it.bottom } / s).toInt().coerceIn(0, ht - 1)
            if (r <= l || b <= t) continue
            val size = max(1, b - t)
            val x0 = ((centre.getValue(e.column) - pitch / 2) / s).toInt().coerceIn(0, w - 1)
            val x1 = ((centre.getValue(e.column) + pitch / 2) / s).toInt().coerceIn(0, w - 1)
            val y0 = max(0, t - 4 * size)
            val y1 = min(ht - 1, b + 4 * size)
            val room = (y1 - y0 + 1) * (x1 - x0 + 1)
            val values = IntArray(room)
            val reds = FloatArray(room)
            val yellows = FloatArray(room)
            var n = 0
            for (y in y0..y1) {
                for (x in x0..x1) {
                    if (nearWord[y * w + x]) continue
                    val p = pixels.argb[y * w + x]
                    values[n] = value(p)
                    reds[n] = redness(p)
                    yellows[n] = yellowness(p)
                    n++
                }
            }
            if (n < 30) continue
            val sorted = values.copyOf(n).apply { sort() }
            val cut = sorted[n / 2]
            val paperValue = sorted[n * 3 / 4]
            if (paperValue < 60) continue
            // The paper is the brighter half of what lies between the words.
            var m = 0
            for (i in 0 until n) {
                if (values[i] < cut) continue
                reds[m] = reds[i]
                yellows[m] = yellows[i]
                m++
            }
            val paperRed = middle(reds, m)
            val paperYellow = middle(yellows, m)
            var background = 0
            var far = 0
            for (y in t..b) {
                for (x in l..r) {
                    val p = pixels.argb[y * w + x]
                    if (value(p) < 0.55f * paperValue) continue
                    background++
                    val dr = redness(p) - paperRed
                    val dy = yellowness(p) - paperYellow
                    if (sqrt(dr * dr + dy * dy) > PAPER) far++
                }
            }
            if (background > 0) e.mark = far.toFloat() / background
        }
    }

    private fun middle(a: FloatArray, n: Int): Float = a.copyOf(n).apply { sort() }[n / 2]

    private fun value(p: Int) = max(p shr 16 and 0xff, max(p shr 8 and 0xff, p and 0xff))
    private fun brightness(p: Int) = ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)) / 3f + 1

    /** Red against green, and red and green against blue, both against the brightness: colour without light. */
    private fun redness(p: Int) = ((p shr 16 and 0xff) - (p shr 8 and 0xff)) / brightness(p)
    private fun yellowness(p: Int) = (((p shr 16 and 0xff) + (p shr 8 and 0xff)) / 2f - (p and 0xff)) / brightness(p)

    /** Least squares: y = a + b * x. */
    private fun fit(xs: List<Float>, ys: List<Float>): Pair<Float, Float> {
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        val sxx = xs.sumOf { ((it - mx) * (it - mx)).toDouble() }.toFloat()
        val sxy = xs.indices.sumOf { ((xs[it] - mx) * (ys[it] - my)).toDouble() }.toFloat()
        val b = if (sxx == 0f) 0f else sxy / sxx
        return (my - b * mx) to b
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.isEmpty()) 0f else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** How many letters to change, add or drop to make [a] into [b]. */
    internal fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
