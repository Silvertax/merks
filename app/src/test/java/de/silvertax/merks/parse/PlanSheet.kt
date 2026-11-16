package de.silvertax.merks.parse

/**
 * A printed weekly plan as the recognition reads it, word by word: a column for each weekday, [gap]
 * pixels apart, with its name on top; under it centred lines of words 20 pixels high and 32 pixels
 * apart. An empty line is the border to the next cell. A "~" before a word puts it right against the
 * one before, as when the recognition splits one word in two.
 */
internal class PlanSheet(private val left: Int = 500, private val gap: Int = 340) {
    val words = mutableListOf<OcrWord>()
    private val placed = mutableMapOf<Pair<Int, String>, List<OcrWord>>()

    fun centre(day: Int) = left + day * gap

    fun line(text: String, centre: Int, y: Int, height: Int = 20, confidence: Float = 92f): List<OcrWord> {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val widths = tokens.map { it.removePrefix("~").length * 11 }
        val spaces = tokens.drop(1).map { if (it.startsWith("~")) 2 else 7 }
        var x = centre - (widths.sum() + spaces.sum()) / 2
        return tokens.mapIndexed { i, t ->
            if (i > 0) x += spaces[i - 1]
            val w = OcrWord(t.removePrefix("~"), x, y, x + widths[i], y + height, confidence)
            x += widths[i]
            words += w
            w
        }
    }

    /** A day's column: its [name] (null when the recognition did not read it) and its lines. */
    fun column(day: Int, name: String?, vararg lines: String): PlanSheet {
        if (name != null) line(name, centre(day), 240)
        var y = 300
        for (l in lines) {
            if (l.isEmpty()) {
                y += 24
                continue
            }
            placed[day to l] = line(l, centre(day), y)
            y += 32
        }
        return this
    }

    fun above(text: String, centre: Int): PlanSheet {
        line(text, centre, 110)
        return this
    }

    fun below(text: String, centre: Int): PlanSheet {
        line(text, centre, 800)
        return this
    }

    /** The words of a line put into a day's column. */
    fun at(day: Int, line: String): List<OcrWord> = placed.getValue(day to line)

    companion object {
        /** A course plan of a neighbourhood centre for calendar week [week]; null leaves the week out. */
        fun courses(week: String? = "KW 47", monday: String? = "Montag"): PlanSheet = PlanSheet()
            .above("Kursplan Familienzentrum Lindenhof", 1100)
            .apply { if (week != null) above(week, 1700) }
            .column(0, monday, "07:45 Frühsport", "Turnhalle", "", "Töpfern", "09:00 – 10:30 Uhr", "Werkraum", "", "Chor", "(Noten mitbringen)", "14:00 – 15:00")
            .column(1, "Dienstag", "Offene Werkstatt", "08:30 – 10:00", "", "Kochgruppe (max. 8 P)", "11:00 – 12:30", "Raum 2")
            .column(2, "Mittwoch", "07:45 Frühsport", "Turnhalle", "", "Malkurs", "10:00 – 11:30", "Raum 12", "", "Schwimmen, 16:45 –", "17:30 (Hallenbad)")
            .column(3, "Donnerstag", "Offene Werkstatt", "08:30 – 10:00", "", "Gedächtnistraining", "Bücherei, 1. Stock", "10:15 – 11:00", "", "Spieleabend", "18:00 Uhr")
            .column(4, "Freitag", "07:45 Frühsport", "Turnhalle", "", "Offene Werkstatt", "13:00 – 14:30", "", "Lesecafé", "Raum 5", "15.30 bis 16.30 Uhr")
            .below("Jeden ersten Dienstag im Monat um 18:00 Uhr Elternabend in der Aula", 1180)
    }
}
