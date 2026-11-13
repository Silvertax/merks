package de.silvertax.merks

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.silvertax.merks.parse.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The app on an English phone, and the language setting. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class AppSmokeTestEn {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @After fun followThePhoneAgain() {
        TestScreens.forgetLeftovers()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test fun speakingInEnglish() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        shadowOf(app.packageManager).addServiceIfNotPresent(service)
        shadowOf(app.packageManager).addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))

        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        assertEquals("Listening …", activity.findViewById<TextView>(R.id.status).text.toString())
        // The recogniser is asked for the app's language.
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("en-US", recognizer.lastRecognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))

        recognizer.triggerOnResults(
            Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Tomorrow at 10 dentist")) },
        )
        // It listens on for a while in case more comes; then it saves.
        assertEquals("Still listening …", activity.findViewById<TextView>(R.id.status).text.toString())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.isFinishing) return@repeat
            Thread.sleep(25)
        }
        assertTrue(activity.isFinishing)

        val tomorrow = LocalDate.now().plusDays(1)
        val entry = Db(app).all().single()
        assertEquals("Dentist", entry.title)
        val n = notifications.allNotifications.single()
        assertEquals("Appointment noted (not in calendar)", n.title())
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.US).format(tomorrow)
        assertEquals("$date, 10:00 AM Dentist", n.text())
        assertEquals(listOf("Undo", "Change"), n.actions.map { it.title.toString() })
    }

    @Test fun mainScreenInEnglish() {
        Prefs.setAskedPermissions(app)
        val text = "Every Monday take out the trash"
        Store.save(app, Store.sort(app, text), text)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals("Speak", (activity.findViewById<View>(R.id.speak) as TextView).text.toString())
        val list = activity.findViewById<android.widget.ListView>(R.id.list)
        val row = list.adapter.getView(0, null, list)
        assertEquals("Take out the trash", row.findViewById<TextView>(R.id.title).text.toString())
        assertTrue(row.findViewById<TextView>(R.id.sub).text.toString().matches(Regex("Appointment · Monday, \\w+ \\d+ · weekly")))
    }

    /** An English phone with the app set to German: rules, texts and notifications follow the app. */
    @Test fun appInGermanOnAnEnglishPhone() {
        Prefs.setLang(app, Lang.GERMAN)
        assertEquals(Locale.GERMANY, Lang.locale(app))
        val parsed = Store.sort(app, "Morgen um 10 Zahnarzt")
        assertEquals(Kind.APPOINTMENT, parsed.kind)
        assertEquals("Zahnarzt", parsed.title)
        assertEquals(LocalDate.now().plusDays(1).atTime(10, 0), parsed.start)

        val e = Store.save(app, parsed, "Morgen um 10 Zahnarzt")
        Scheduler.confirm(app, e)
        val n = notifications.allNotifications.single()
        assertEquals("Termin gemerkt (nicht im Kalender)", n.title())
        assertTrue(n.text()!!.endsWith(", 10:00 Uhr Zahnarzt"))
        assertEquals(listOf("Rückgängig", "Ändern"), n.actions.map { it.title.toString() })
    }

    @Test fun englishRulesOnAnEnglishPhone() {
        val parsed = Store.sort(app, "Remind me in 20 minutes to take the laundry out")
        assertEquals(Kind.REMINDER, parsed.kind)
        assertEquals("Take the laundry out", parsed.title)
        assertTrue(Duration20.contains(parsed.start!!))
    }

    private object Duration20 {
        fun contains(t: LocalDateTime): Boolean {
            val expected = LocalDateTime.now().plusMinutes(20)
            return !t.isBefore(expected.minusMinutes(1)) && !t.isAfter(expected.plusMinutes(1))
        }
    }

    /** The screen then redraws itself in the new language, which the simulation cannot follow. */
    @Test fun languageCanBeChosenInTheSettings() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals("Automatic (phone language)", settings.findViewById<TextView>(R.id.language_value).text.toString())
        settings.findViewById<View>(R.id.row_language).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
        assertEquals(Lang.GERMAN, Prefs.lang(app))
        assertEquals("de", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test fun widgetLabelFollowsTheAppLanguage() {
        Prefs.setLang(app, Lang.GERMAN)
        val manager = shadowOf(android.appwidget.AppWidgetManager.getInstance(app))
        val id = manager.createWidget(SpeakWidget::class.java, R.layout.widget_speak)
        val label = manager.getViewFor(id).findViewById<TextView>(R.id.widget_label)
        // The simulation draws the smallest layout without a label; then the description tells it.
        val described = manager.getViewFor(id).findViewById<View>(R.id.widget_root).contentDescription?.toString()
        assertEquals("Sprechen", label?.text?.toString() ?: described)
    }
}
