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

    // Accent colours to choose in the settings; teal is the app's own.
    const val TEAL = "teal"
    const val ROSE = "rose"
    const val RED = "red"
    const val LILAC = "lilac"
    const val BLUE = "blue"
    const val GREEN = "green"
    val ACCENTS = listOf(TEAL, RED, ROSE, LILAC, BLUE, GREEN)

    /** Several colours mixed: chosen in the settings, see [mix]. */
    const val MIXED = "mixed"

    /**
     * The colours in use: the one chosen, or in the mixed mode those picked for it, in their order.
     * The first is the app's own colour; the others take turns, e.g. one per day on the date tiles.
     */
    fun mix(ctx: Context): List<String> =
        if (Prefs.accent(ctx) == MIXED) Prefs.mixedAccents(ctx) else listOf(Prefs.accent(ctx))

    /** The colour of everything that is not mixed: the app's buttons, switches and headings. */
    fun base(ctx: Context): String = mix(ctx).first()

    /** Everything that decides the colours, to tell when they changed. */
    fun colourKey(ctx: Context): String = Prefs.accent(ctx) + ":" + mix(ctx).joinToString(",")

    /** Which of the mixed colours a day gets: the same date always the same, the next day the next colour. */
    fun slotOfDay(start: Long): Int =
        java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt()

    /** Main colours of one accent, as they look on [ctx] (light or dark). */
    class Tone(val primary: Int, val onPrimary: Int, val container: Int, val onContainer: Int)

    /** The tones of [mix] for the screen [ctx]; a single one when nothing is mixed. */
    fun tones(ctx: Context): List<Tone> = mix(ctx).map { accent ->
        val t = android.view.ContextThemeWrapper(ctx, R.style.Theme_Merks).theme
        accentOverlay(accent).takeIf { it != 0 }?.let { t.applyStyle(it, true) }
        fun color(attr: Int): Int {
            val v = android.util.TypedValue()
            t.resolveAttribute(attr, v, true)
            return v.data
        }
        Tone(
            color(com.google.android.material.R.attr.colorPrimary),
            color(com.google.android.material.R.attr.colorOnPrimary),
            color(com.google.android.material.R.attr.colorPrimaryContainer),
            color(com.google.android.material.R.attr.colorOnPrimaryContainer),
        )
    }

    /** The theme overlay of an accent colour, or 0 for teal, which is the theme itself. */
    fun accentOverlay(accent: String): Int = when (accent) {
        RED -> R.style.ThemeOverlay_Merks_Accent_Red
        ROSE -> R.style.ThemeOverlay_Merks_Accent_Rose
        LILAC -> R.style.ThemeOverlay_Merks_Accent_Lilac
        BLUE -> R.style.ThemeOverlay_Merks_Accent_Blue
        GREEN -> R.style.ThemeOverlay_Merks_Accent_Green
        else -> 0
    }

    /** The chosen accent colour and, when [dark], OLED black, on top of the app's theme: in this order. */
    fun style(theme: Resources.Theme, ctx: Context, dark: Boolean) {
        val overlay = accentOverlay(base(ctx))
        if (overlay != 0) theme.applyStyle(overlay, true)
        if (dark && Prefs.oled(ctx)) theme.applyStyle(R.style.ThemeOverlay_Merks_Oled, true)
    }

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

open class MerksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.applyNightMode(this)
        Lang.sync(this)
    }
}

/**
 * Base of every screen: the chosen accent colour, pure black in OLED mode when dark, and picks up
 * a change made on another screen.
 */
abstract class MerksActivity : AppCompatActivity() {
    private var oledShown = false
    private var colourShown = ""

    /** Rebuild when OLED or the colour was changed on another screen meanwhile. Never for a running recording. */
    protected open val followsOledChanges = true

    private fun wantsOled() = Prefs.oled(this) && Appearance.isDark(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The language may have been changed in Android's settings meanwhile (Android 13+).
        if (Build.VERSION.SDK_INT >= 33) Lang.sync(this)
        oledShown = wantsOled()
        colourShown = Appearance.colourKey(this)
        Appearance.style(theme, this, Appearance.isDark(this))
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (followsOledChanges && (wantsOled() != oledShown || Appearance.colourKey(this) != colourShown)) recreate()
    }
}
