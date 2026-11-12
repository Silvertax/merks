package de.silvertax.merks

import android.app.Application
import android.content.Context
import android.content.res.Configuration
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

    /** Whether this screen is shown in the dark design right now. */
    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
        if (wantsOled() != oledShown) recreate()
    }
}
