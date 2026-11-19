package de.silvertax.merks

import android.content.pm.PackageManager
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The version for Google Play: Play brings the updates, the app neither looks for nor installs any. */
@RunWith(RobolectricTestRunner::class)
class PlayBuildTest {
    @Test fun noUpdatesOfItsOwn() {
        assertTrue(BuildConfig.STORE)
        assertFalse(Updater.available)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(View.GONE, settings.findViewById<View>(R.id.card_app).visibility)
        // Opening the app asks nobody anything.
        Prefs.setAskedPermissions(app)
        var asked = 0
        Updater.source = { asked++; null }
        try {
            val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(0, asked)
            assertEquals(View.GONE, main.findViewById<View>(R.id.update_banner).visibility)
        } finally {
            Updater.source = null
        }
        val asks = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in asks)
    }
}
