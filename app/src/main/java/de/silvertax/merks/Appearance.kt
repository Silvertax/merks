package de.silvertax.merks

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

object Appearance {
    /** OLED mode keeps the app dark at all times; otherwise it follows the phone. */
    fun applyNightMode(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs.oled(ctx)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        )
    }
}

class MerksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.applyNightMode(this)
    }
}

/** Base of every screen: pure black in OLED mode, and picks up a change made on another screen. */
abstract class MerksActivity : AppCompatActivity() {
    private var oledShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        oledShown = Prefs.oled(this)
        if (oledShown) theme.applyStyle(R.style.ThemeOverlay_Merks_Oled, true)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.oled(this) != oledShown) recreate()
    }
}
