package de.silvertax.merks.parse

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Reads the appointments in a calendar file (.ics), as booking confirmations and invitations bring
 * them: title, place, start and end, exactly as written, so nothing has to be guessed. What is over
 * stays out. A series without an end becomes a series; one with an end becomes its single dates.
 * Pure Kotlin, for tests on the JVM.
 */
object IcsReader {
    /** A calendar file starts like this; a text that does is read here, not searched for dates. */
    fun isCalendar(text: String): Boolean = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("BEGIN:VCALENDAR", ignoreCase = true)

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private class Event(
        val title: String,
        val place: String,
        val details: String,
        val start: LocalDateTime,
        val end: LocalDateTime?,
        val allDay: Boolean,
        val rule: Map<String, String>?,
    )

    fun read(text: String, now: LocalDateTime, zone: ZoneId): PhotoFind {
        val events = events(text, zone)
        val titles = events.map { it.title }.filter { it.isNotEmpty() }.distinct()
        val many = titles.size > 1
        val dates = events.flatMap { e -> dates(e, now).map { if (many) it.copy(title = e.title.ifEmpty { null }) else it } }
            .sortedWith(compareBy({ it.day }, { it.time }))
            .take(PhotoReader.MAX_LISTED)
        val first = events.firstOrNull { e -> dates.any { it.title == null || it.title == e.title } }
        val note = first?.let { e ->
            listOf(e.place, e.details.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString("\n").take(160).ifEmpty { null }
        }
        // What the screen shows under "show the text": the events as written, readable.
        val shown = events.joinToString("\n\n") { e ->
            listOf(e.title, e.place, e.details.take(300)).filter { it.isNotBlank() }.joinToString("\n")
        }
        return PhotoFind(if (many) titles.first() else titles.firstOrNull().orEmpty(), note, dates, shown)
    }

    private fun events(text: String, zone: ZoneId): List<Event> {
        val out = mutableListOf<Event>()
        var props: MutableList<Prop>? = null
        var depth = 0
        for (line in unfold(text)) {
            val p = prop(line) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VEVENT", true) -> {
                    props = mutableListOf()
                    depth = 0
                }
                props == null -> {}
                // Reminders inside an event (VALARM) have properties of their own.
                p.name == "BEGIN" -> depth++
                p.name == "END" && depth > 0 -> depth--
                p.name == "END" && p.value.equals("VEVENT", true) -> {
                    event(props, zone)?.let { out += it }
                    props = null
                }
                depth == 0 -> props += p
            }
        }
        return out
    }

    private fun event(props: List<Prop>, zone: ZoneId): Event? {
        fun get(name: String) = props.firstOrNull { it.name == name }
        if (get("STATUS")?.value.equals("CANCELLED", true)) return null
        val startProp = get("DTSTART") ?: return null
        val allDay = startProp.params["VALUE"].equals("DATE", true) || startProp.value.length == 8
        val start = time(startProp, zone) ?: return null
        val end = get("DTEND")?.let { time(it, zone) } ?: get("DURATION")?.let { d -> duration(d.value)?.let { start.plus(it) } }
        val rule = get("RRULE")?.value?.split(';')?.mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] }
        }?.toMap()
        return Event(
            title = get("SUMMARY")?.value.orEmpty().trim().take(80),
            place = get("LOCATION")?.value.orEmpty().trim(),
            details = get("DESCRIPTION")?.value.orEmpty().trim(),
            start = start,
            end = end?.takeIf { it.isAfter(start) },
            allDay = allDay,
            rule = rule,
        )
    }

    /** One logical line per property: continuation lines start with a space or a tab. */
    private fun unfold(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n[ \t]"), "").split('\n')

    private fun prop(line: String): Prop? {
        // The value starts after the first colon that is not inside quotes.
        var quoted = false
        var colon = -1
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') quoted = !quoted
            if (ch == ':' && !quoted) {
                colon = i
                break
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p ->
            p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') }
        }.toMap()
        return Prop(head[0].trim().uppercase(), params, unescape(line.substring(colon + 1)))
    }

    private fun unescape(v: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            val ch = v[i]
            if (ch == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(v[i + 1])
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** A start or end in the phone's time zone; all-day values at midnight. */
    private fun time(p: Prop, zone: ZoneId): LocalDateTime? = try {
        val v = p.value.trim()
        when {
            v.length == 8 -> LocalDate.parse(v, dayFormat).atStartOfDay()
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1).take(15), timeFormat).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()
            else -> {
                val local = LocalDateTime.parse(v.take(15), timeFormat)
                val from = p.params["TZID"]?.let { zoneOf(it) }
                if (from == null) local else local.atZone(from).withZoneSameInstant(zone).toLocalDateTime()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Outlook writes Windows names for the zones of German-speaking countries; the rest knows them by their IANA name. */
    private fun zoneOf(id: String): ZoneId? = try {
        ZoneId.of(id)
    } catch (e: Exception) {
        when {
            id.contains("W. Europe", true) || id.contains("Central Europe", true) || id.contains("Romance", true) -> ZoneId.of("Europe/Berlin")
            id.contains("GMT Standard", true) -> ZoneId.of("Europe/London")
            id.contains("UTC", true) -> ZoneOffset.UTC
            else -> null
        }
    }

    /** "PT1H30M", "P1D", "P1W"; null for what is not understood. */
    private fun duration(v: String): Duration? = try {
        val s = v.trim().uppercase()
        when {
            s.contains('T') -> Duration.parse(s)
            s.endsWith("W") -> Duration.ofDays(7L * s.removePrefix("P").removeSuffix("W").toLong())
            else -> Duration.ofDays(Period.parse(s).days.toLong())
        }
    } catch (e: Exception) {
        null
    }

    // ---- From an event to the dates on the screen ----

    private fun dates(e: Event, now: LocalDateTime): List<PhotoDate> {
        val rule = e.rule
        val first = date(e, e.start)
        if (rule == null) return listOfNotNull(first.takeUnless { over(e, e.start, now) })
        val unit = when (rule["FREQ"]?.uppercase()) {
            "DAILY" -> RepeatUnit.DAY
            "WEEKLY" -> RepeatUnit.WEEK
            "MONTHLY" -> RepeatUnit.MONTH
            "YEARLY" -> RepeatUnit.YEAR
            else -> return listOfNotNull(first.takeUnless { over(e, e.start, now) })
        }
        val every = rule["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val days = rule["BYDAY"]?.split(',')?.mapNotNull { weekday(it) }?.distinct()?.sorted().orEmpty()
        val finite = rule.containsKey("COUNT") || rule.containsKey("UNTIL")
        if (!finite) {
            // As a series in Merks, when Merks can repeat it that way: from the next date on.
            val repeat = when {
                unit == RepeatUnit.WEEK && every == 1 && days == WORKDAYS -> Repeat(RepeatUnit.WORKDAY, 1)
                unit == RepeatUnit.WEEK && (days.isEmpty() || days == listOf(e.start.dayOfWeek)) -> Repeat(RepeatUnit.WEEK, every)
                unit != RepeatUnit.WEEK && days.isEmpty() -> Repeat(unit, every)
                else -> null
            }
            if (repeat != null) {
                val next = occurrences(e, unit, every, days, null, null).firstOrNull { !over(e, it, now) } ?: return emptyList()
                return listOf(date(e, next).copy(repeat = repeat))
            }
        }
        val count = rule["COUNT"]?.toIntOrNull()
        val until = rule["UNTIL"]?.let { untilOf(it) }
        return occurrences(e, unit, every, days, count, until)
            .filterNot { over(e, it, now) }
            .take(PhotoReader.MAX_DATES)
            .map { date(e, it) }
            .toList()
    }

    private val WORKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun weekday(v: String): DayOfWeek? = when (v.trim().uppercase().takeLast(2)) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun untilOf(v: String): LocalDateTime? = try {
        if (v.length == 8) LocalDate.parse(v, dayFormat).atTime(LocalTime.MAX) else LocalDateTime.parse(v.trim().removeSuffix("Z").take(15), timeFormat)
    } catch (e: Exception) {
        null
    }

    /** The starts of a series, in order, stopping at [count] (counted from the first) or [until]. */
    private fun occurrences(e: Event, unit: RepeatUnit, every: Int, days: List<DayOfWeek>, count: Int?, until: LocalDateTime?): Sequence<LocalDateTime> = sequence {
        var made = 0
        var step = 0L
        // Enough steps for years of a weekly series, never endless.
        while (step < 800) {
            val base = when (unit) {
                RepeatUnit.DAY -> e.start.plusDays(step * every)
                RepeatUnit.WEEK -> e.start.plusWeeks(step * every)
                RepeatUnit.MONTH -> e.start.plusMonths(step * every)
                else -> e.start.plusYears(step * every)
            }
            val starts = if (unit == RepeatUnit.WEEK && days.isNotEmpty()) {
                val monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                days.map { monday.plusDays((it.value - 1).toLong()) }.filter { !it.isBefore(e.start) }
            } else {
                listOf(base)
            }
            for (s in starts) {
                if (until != null && s.isAfter(until)) return@sequence
                if (count != null && made >= count) return@sequence
                made++
                yield(s)
            }
            step++
        }
    }

    private fun over(e: Event, start: LocalDateTime, now: LocalDateTime): Boolean {
        val length = e.end?.let { Duration.between(e.start, it) } ?: Duration.ZERO
        return if (e.allDay) {
            // All-day ends are the day after; a stay is over when its last day is.
            val last = start.toLocalDate().plusDays((length.toDays() - 1).coerceAtLeast(0))
            last.isBefore(now.toLocalDate())
        } else {
            start.plus(length).isBefore(now) || (length.isZero && start.isBefore(now))
        }
    }

    private fun date(e: Event, start: LocalDateTime): PhotoDate {
        val length = e.end?.let { Duration.between(e.start, it) }
        val source = listOf(e.title, e.place).filter { it.isNotEmpty() }.joinToString(", ").take(100)
        if (e.allDay) {
            val days = length?.toDays() ?: 1
            val last = start.toLocalDate().plusDays(days - 1).takeIf { days > 1 }
            return PhotoDate(start.toLocalDate(), lastDay = last, source = source)
        }
        val end = length?.let { start.plus(it) }?.takeIf { it.toLocalDate() == start.toLocalDate() }?.toLocalTime()
        return PhotoDate(start.toLocalDate(), start.toLocalTime(), end, source = source)
    }
}
