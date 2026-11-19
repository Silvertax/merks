package de.silvertax.merks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private fun t() = Transcript()

    @Test fun partialsThatGoOnReplaceEachOther() {
        val t = t()
        t.partial("Morgen")
        t.partial("Morgen um")
        t.partial("Morgen um 10 Zahnarzt")
        assertEquals("Morgen um 10 Zahnarzt", t.text)
        t.result("Morgen um 10 Zahnarzt")
        assertEquals("Morgen um 10 Zahnarzt", t.text)
        assertEquals(1, t.finished)
        assertFalse(t.pending)
    }

    @Test fun correctionsOfTheFirstWordAreNoNewStart() {
        val t = t()
        t.partial("Zahn")
        t.partial("Zahnarzt morgen")
        assertEquals("Zahnarzt morgen", t.text)
        t.partial("Wir müssen")
        t.partial("Bier müssen")
        assertEquals("Zahnarzt morgen Bier müssen", t.text)
    }

    @Test fun aNewStartKeepsTheWordsBefore() {
        val t = t()
        t.partial("Morgen um 10 Zahnarzt")
        t.partial("um")
        t.partial("um 11 Friseur")
        assertEquals("Morgen um 10 Zahnarzt um 11 Friseur", t.text)
        // The final result with only the last words: the first ones stay.
        t.result("um 11 Friseur")
        assertEquals("Morgen um 10 Zahnarzt um 11 Friseur", t.text)
    }

    @Test fun aFinalResultWithEverythingIsNotDoubled() {
        val t = t()
        t.partial("Zahnarzt morgen")
        t.partial("um 10")
        t.result("Zahnarzt morgen um 10 Uhr")
        assertEquals("Zahnarzt morgen um 10 Uhr", t.text)
    }

    @Test fun aFinalResultFromTheMiddleTakesUpTheRest() {
        val t = t()
        t.partial("Zahnarzt morgen um 10")
        t.result("um 10 Uhr")
        assertEquals("Zahnarzt morgen um 10 Uhr", t.text)
    }

    @Test fun withoutAFinalResultTheShownWordsCount() {
        val t = t()
        t.partial("Milch kaufen")
        t.result(null)
        t.partial("Brot")
        t.keepPartial()
        assertEquals("Milch kaufen Brot", t.text)
        assertEquals(2, t.finished)
    }

    @Test fun unstableWordsAreShownOnce() {
        assertEquals("Zahnarzt morgen um", Transcript.combined("Zahnarzt", " morgen um"))
        assertEquals("Zahnarzt morgen", Transcript.combined("Zahnarzt morgen", "morgen"))
        assertEquals("morgen", Transcript.combined("", "morgen"))
        val t = t()
        t.partial("Zahnarzt morgen")
        // The recogniser is less sure again: the words move to the unsure part but stay on the screen.
        t.partial("Zahnarzt", "morgen um")
        assertEquals("Zahnarzt morgen um", t.text)
    }

    @Test fun nothingHeardIsNoChange() {
        val t = t()
        assertFalse(t.partial("", ""))
        assertFalse(t.partial(" . "))
        assertTrue(t.isEmpty)
        t.partial("Arzt")
        t.result(".")
        assertEquals("Arzt", t.text)
    }
}
