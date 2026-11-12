package de.silvertax.merks

import android.app.Activity
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 0.5.1/0.5.2: with OLED on and a theme other than the phone's, a screen decided "black or not"
 * before AppCompat had applied the theme, saw the other answer in onResume and rebuilt itself,
 * again and again. The recording never got to listen. Every combination must be stable.
 */
@RunWith(RobolectricTestRunner::class)
class AppearanceLoopTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun reset() = AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun shownBlack(a: Activity): Boolean =
        MerksActivity::class.java.getDeclaredField("oledShown").apply { isAccessible = true }.getBoolean(a)

    private fun check(theme: String, oled: Boolean, phoneDark: Boolean, compact: Boolean) {
        Prefs.prefs(app).edit().clear().putBoolean("compact", compact)
            .putLong("update_checked_at", System.currentTimeMillis()).apply()
        Prefs.setAskedPermissions(app)
        Prefs.setTheme(app, theme)
        Prefs.setOled(app, oled)
        RuntimeEnvironment.setQualifiers(if (phoneDark) "+night" else "+notnight")
        Appearance.applyNightMode(app)
        val label = "theme='$theme' oled=$oled phoneDark=$phoneDark compact=$compact"
        for (cls in listOf(RecordActivity::class.java, MainActivity::class.java, SettingsActivity::class.java)) {
            val c = Robolectric.buildActivity(cls).create()
            val a = c.get()
            val dark = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // What the screen shows must be what it will want in onResume, or it rebuilds itself.
            assertEquals("${cls.simpleName} $label: dark", dark, Appearance.isDark(a))
            assertEquals("${cls.simpleName} $label: black", oled && dark, shownBlack(a))
            c.destroy()
        }
    }

    @Test fun everyCombinationIsStable() {
        for (theme in listOf(Appearance.AUTO, Appearance.LIGHT, Appearance.DARK))
            for (oled in listOf(false, true))
                for (phoneDark in listOf(false, true))
                    check(theme, oled, phoneDark, compact = true)
    }

    @Test fun fullScreenRecordingToo() {
        check(Appearance.DARK, oled = true, phoneDark = false, compact = false)
        check(Appearance.LIGHT, oled = true, phoneDark = true, compact = false)
    }
}
