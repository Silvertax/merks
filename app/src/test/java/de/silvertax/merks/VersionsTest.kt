package de.silvertax.merks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun higherIsNewer() {
        assertTrue(Versions.newer("v0.2.0", "0.1.0"))
        assertTrue(Versions.newer("v0.10.0", "0.9.3"))
        assertTrue(Versions.newer("1.0", "0.9.9"))
        assertTrue(Versions.newer("v0.2.1", "0.2"))
    }

    @Test fun sameOrLowerIsNot() {
        assertFalse(Versions.newer("v0.2.0", "0.2.0"))
        assertFalse(Versions.newer("v0.1.9", "0.2.0"))
        assertFalse(Versions.newer("", "0.2.0"))
        assertFalse(Versions.newer("v0.2.0-beta", "0.2.0"))
    }
}
