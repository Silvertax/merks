package de.silvertax.merks

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's language: German or English. Chosen in the settings, or taken from the phone
 * (German phones get German, every other language English).
 */
object Lang {
    const val AUTO = ""
    const val GERMAN = "de"
    const val ENGLISH = "en"

    private fun phone(): Locale = Resources.getSystem().configuration.locales[0]

    fun locale(ctx: Context): Locale {
        val phone = phone()
        return when (Prefs.lang(ctx).ifEmpty { if (phone.language == GERMAN) GERMAN else ENGLISH }) {
            // Keep the phone's region where it fits, e.g. en-GB dates for British phones.
            GERMAN -> if (phone.language == GERMAN) phone else Locale.GERMANY
            else -> if (phone.language == ENGLISH) phone else Locale.US
        }
    }

    fun isGerman(ctx: Context): Boolean = locale(ctx).language == GERMAN

    /**
     * A context whose texts are in the app's language. Screens get this from AppCompat; alarms,
     * notifications and the widget can run without any screen, so they ask here.
     */
    fun context(ctx: Context): Context {
        val wanted = locale(ctx)
        if (ctx.resources.configuration.locales[0] == wanted) return ctx
        val config = Configuration(ctx.resources.configuration).apply { setLocale(wanted) }
        return ctx.createConfigurationContext(config)
    }

    /**
     * Brings the setting and the shown language together. From Android 13 the language can also be
     * changed in the system settings, which then counts; before that the app's own setting counts.
     */
    fun sync(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val shown = AppCompatDelegate.getApplicationLocales()
            val lang = when {
                shown.isEmpty -> AUTO
                shown[0]?.language == GERMAN -> GERMAN
                else -> ENGLISH
            }
            if (lang != Prefs.lang(ctx)) Prefs.setLang(ctx, lang)
        } else {
            apply(Prefs.lang(ctx))
        }
    }

    /** Applies the choice to the screens; Android 13+ also shows it in the system's app language list. */
    fun apply(choice: String) {
        AppCompatDelegate.setApplicationLocales(
            if (choice == AUTO) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(choice),
        )
    }
}
