package de.silvertax.merks

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The version on GitHub: it looks for its updates itself. */
@RunWith(RobolectricTestRunner::class)
class GithubBuildTest {
    @Test fun updateCheckInTheGithubBuild() {
        assertFalse(BuildConfig.STORE)
        assertTrue(Updater.available)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, settings.findViewById<View>(R.id.card_app).visibility)
    }
}
