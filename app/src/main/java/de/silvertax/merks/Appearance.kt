package de.silvertax.merks

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

object Appearance {
    const val AUTO = ""
    const val LIGHT = "light"
    const val DARK = "dark"

    /** Light, dark, or like the phone. */
    fun applyNightMode(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.theme(ctx)) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /**
     * Whether the app is dark: decided by the choice in the settings, or by the phone itself.
     * Deliberately not read from a screen's own resources: during onCreate those can still show
     * the phone's mode, which made screens rebuild themselves over and over (the recording too).
     */
    fun isDark(ctx: Context): Boolean = when (Prefs.theme(ctx)) {
        LIGHT -> false
        DARK -> true
        else -> (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}

class MerksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.applyNightMode(this)
        Lang.sync(this)
    }
}

/** Base of every screen: pure black in OLED mode when dark, and picks up a change made on another screen. */
abstract class MerksActivity : AppCompatActivity() {
    private var oledShown = false

    /** Rebuild when OLED was switched on another screen meanwhile. Never for a running recording. */
    protected open val followsOledChanges = true

    private fun wantsOled() = Prefs.oled(this) && Appearance.isDark(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The language may have been changed in Android's settings meanwhile (Android 13+).
        if (Build.VERSION.SDK_INT >= 33) Lang.sync(this)
        oledShown = wantsOled()
        if (oledShown) theme.applyStyle(R.style.ThemeOverlay_Merks_Oled, true)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (followsOledChanges && wantsOled() != oledShown) recreate()
    }
}
