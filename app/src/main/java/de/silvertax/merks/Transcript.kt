package de.silvertax.merks

import de.silvertax.merks.parse.PlanReader

/**
 * Everything said in one recording, put together from what the recognisers deliver.
 *
 * Every attempt of a recogniser ends after a pause and is followed by a fresh one, so a recording
 * is made of [pieces]. Within one attempt, some recognisers start their partial results over after
 * a short pause, and some end with only the last stretch: the words before it would vanish from the
 * screen and sometimes from the entry. Those words are [held] here and put back, so the text on the
 * screen only ever grows, apart from the recogniser correcting its last words.
 */
class Transcript {
    private val pieces = mutableListOf<String>()
    private val held = mutableListOf<String>()
    private var partial = ""

    /** Nothing at all was understood so far. */
    val isEmpty get() = pieces.isEmpty() && held.isEmpty() && partial.isEmpty()

    /** Attempts that ended with words. */
    val finished get() = pieces.size

    /** Words of the current attempt that are not final yet. */
    val pending get() = held.isNotEmpty() || partial.isNotEmpty()

    /** All that was said so far, as shown on the screen. */
    val text: String get() = (pieces + held + partial).filter { it.isNotEmpty() }.joinToString(" ")

    fun clear() {
        pieces.clear()
        held.clear()
        partial = ""
    }

    /**
     * A partial result of the current attempt: [stable] is what the recogniser is sure of,
     * [unstable] what may still change (sent separately by some recognisers).
     * Returns whether anything was heard.
     */
    fun partial(stable: String?, unstable: String? = null): Boolean {
        val now = combined(stable.orEmpty(), unstable.orEmpty())
        if (now.none { it.isLetterOrDigit() }) return false
        val before = partial
        if (before.isNotEmpty()) {
            val kept = dropped(before, now)
            if (kept.isNotEmpty()) held += kept
        }
        partial = now
        return true
    }

    /** The current attempt is over with [result]; without one, the words shown so far count. */
    fun result(result: String?) {
        val final = result?.trim().orEmpty()
        val shown = (held + partial).filter { it.isNotEmpty() }.joinToString(" ")
        val all = if (final.none { it.isLetterOrDigit() }) shown else join(shown, final)
        if (all.isNotEmpty()) pieces += all
        held.clear()
        partial = ""
    }

    /** The current attempt ended without a result: what was shown of it counts. */
    fun keepPartial() = result(null)

    companion object {
        /** Stable and unstable words as one text, without saying the end twice. */
        fun combined(stable: String, unstable: String): String {
            val s = stable.trim()
            val u = unstable.trim()
            return when {
                u.isEmpty() -> s
                s.isEmpty() -> u
                s.endsWith(u, ignoreCase = true) -> s
                else -> "$s $u"
            }
        }

        /**
         * What of [before] the recogniser dropped when its result turned into [now]:
         * nothing when it only corrected or went on, all of it when it started over,
         * and the first words when [now] takes up the rest of them and goes on.
         */
        internal fun dropped(before: String, now: String): String {
            val old = before.split(' ').map(::key).filter { it.isNotEmpty() }
            val new = now.split(' ').map(::key).filter { it.isNotEmpty() }
            if (old.isEmpty()) return ""
            if (new.isEmpty()) return before.trim()
            val k = old.indices.firstOrNull { k ->
                val tail = old.size - k
                // Going on from the middle only counts when the rest of the words is taken up.
                if (k > 0 && new.size < tail) return@firstOrNull false
                val n = minOf(new.size, tail)
                val m = (0 until n).count { same(old[k + it], new[it]) }
                m >= 1 && m * 2 >= n
            }
            val words = before.trim().split(' ').filter { key(it).isNotEmpty() }
            return when (k) {
                null -> before.trim()
                else -> words.take(k).joinToString(" ")
            }
        }

        /** [before] and [after] as one text, where [after] may start with words already in [before]. */
        internal fun join(before: String, after: String): String {
            val gone = dropped(before, after)
            return listOf(gone, after.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun key(word: String) = word.lowercase().filter { it.isLetterOrDigit() }

        /** The same word, or a correction of it ("Zahn" to "Zahnarzt", "Frisör" to "Friseur"). */
        private fun same(a: String, b: String): Boolean = when {
            a.isEmpty() || b.isEmpty() -> a == b
            a.startsWith(b) || b.startsWith(a) -> true
            minOf(a.length, b.length) >= 4 -> PlanReader.distance(a, b) <= 2
            else -> false
        }
    }
}
