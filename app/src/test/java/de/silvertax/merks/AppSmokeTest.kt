package de.silvertax.merks

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Parsed
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import de.silvertax.merks.parse.RuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs the app's screens, receivers, database and alarms in a simulated Android (Robolectric),
 * the way they are used on the phone. Nothing here goes to the network.
 */
@RunWith(RobolectricTestRunner::class)
class AppSmokeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Stands in for the phone's calendar and records what the app writes into it. */
    class FakeCalendar : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()

        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(CalendarContract.Calendars._ID)).apply { addRow(arrayOf<Any>(7L)) }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted += ContentValues(values ?: ContentValues())
            return ContentUris.withAppendedId(uri, 100L + inserted.size)
        }
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
            deleted += uri
            return 1
        }
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private fun fakeCalendar(): FakeCalendar {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        return Robolectric.setupContentProvider(FakeCalendar::class.java, CalendarContract.AUTHORITY)
    }

    // ---------- Update from an older version ----------

    /** Entries saved with 0.1 / 0.2 (database version 1) must survive the update to 0.3. */
    @Test fun oldDatabaseIsUpgradedWithoutLosingEntries() {
        val old = object : SQLiteOpenHelper(app, "merks.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT, raw TEXT, " +
                        "start INTEGER, end_ INTEGER, all_day INTEGER, cal_id INTEGER, created INTEGER)",
                )
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        old.writableDatabase.execSQL(
            "INSERT INTO entries(kind,title,raw,start,end_,all_day,cal_id,created) " +
                "VALUES ('APPOINTMENT','Zahnarzt','Morgen um 10 Zahnarzt',1763024400000,NULL,0,55,1762970000000)",
        )
        old.writableDatabase.execSQL(
            "INSERT INTO entries(kind,title,raw,start,end_,all_day,cal_id,created) " +
                "VALUES ('NOTE','Milch kaufen','Milch kaufen',NULL,NULL,0,NULL,1762970001000)",
        )
        old.close()

        val all = Db(app).all()
        assertEquals(2, all.size)
        val dentist = all.first { it.title == "Zahnarzt" }
        assertEquals(1763024400000L, dentist.start)
        assertEquals(55L, dentist.calendarEventId)
        assertFalse(dentist.done)
        assertNull(dentist.snooze)
        assertNull(dentist.repeat)
        // The new columns work on the upgraded database.
        Db(app).setDone(dentist.id, true)
        assertTrue(Db(app).get(dentist.id)!!.done)
    }

    // ---------- Speaking: widget tap to confirmation ----------

    private fun speechAvailable() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        val pm = shadowOf(app.packageManager)
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
    }

    private fun say(text: String) {
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val results = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
        shadowOf(recognizer).triggerOnResults(results)
    }

    /** Saving runs on a background thread and reports back to the screen; wait for both. */
    private fun waitFor(condition: () -> Boolean) {
        repeat(400) {
            idleMain()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out")
    }

    private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMAN)

    @Test fun speakingSavesAndConfirmsWithANotification() {
        speechAvailable()
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        assertEquals("Ich höre zu …", activity.findViewById<TextView>(R.id.status).text.toString())

        say("Morgen um 10 Zahnarzt")
        waitFor { activity.isFinishing }

        val tomorrow = LocalDate.now().plusDays(1)
        val entry = Db(app).all().single()
        assertEquals(Kind.APPOINTMENT, entry.kind)
        assertEquals("Zahnarzt", entry.title)
        assertEquals(millis(tomorrow.atTime(10, 0)), entry.start)

        // Small mode: the info is gone and a notification says what was saved.
        val n = notifications.allNotifications.single()
        assertEquals("Termin gemerkt (nicht im Kalender)", n.title())
        assertEquals("${shortDate.format(tomorrow)}, 10:00 Uhr Zahnarzt", n.text())
        assertEquals("Rückgängig", n.actions.single().title.toString())

        // "Rückgängig" in the notification removes it again.
        UndoReceiver().onReceive(app, shadowOf(n.actions.single().actionIntent).savedIntent)
        assertTrue(Db(app).all().isEmpty())
        assertTrue(notifications.allNotifications.isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun speakingInFullScreenModeConfirmsOnScreen() {
        speechAvailable()
        val calendar = fakeCalendar()
        Prefs.setCompact(app, false)
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()

        say("Jeden Montag Mülltonne rausstellen")
        waitFor { Db(app).all().isNotEmpty() && activity.findViewById<View>(R.id.undo).visibility == View.VISIBLE }

        assertEquals("Termin im Kalender eingetragen", activity.findViewById<TextView>(R.id.status).text.toString())
        assertEquals("Mülltonne rausstellen", activity.findViewById<TextView>(R.id.spoken).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.detail).text.toString().endsWith("· wöchentlich"))
        assertEquals("FREQ=WEEKLY", calendar.inserted.single().getAsString(CalendarContract.Events.RRULE))
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test fun nothingUnderstoodOffersToTryAgain() {
        speechAvailable()
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        recognizer.triggerOnRmsChanged(6f) // a voice was there
        recognizer.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idleMain()
        assertEquals("Ich habe nichts verstanden.", activity.findViewById<TextView>(R.id.status).text.toString())
        assertEquals("", activity.findViewById<TextView>(R.id.detail).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.retry).visibility)
        assertTrue(Db(app).all().isEmpty())
    }

    /** A home screen that adds the button: nothing to explain. */
    @Test fun widgetButtonStaysQuietWhenThePhoneAddsIt() {
        Prefs.setAskedPermissions(app)
        Prefs.prefs(app).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(true)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        main.findViewById<View>(R.id.add_widget).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(ShadowDialog.getLatestDialog() == null)
    }

    /** Xiaomi's home screen takes the request and drops it silently: then the app explains the way by hand. */
    @org.robolectric.annotation.Config(shadows = [SilentHomeScreen::class])
    @Test fun widgetButtonExplainsWhenThePhoneDoesNothing() {
        Prefs.setAskedPermissions(app)
        Prefs.prefs(app).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(true)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "Xiaomi")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        main.findViewById<View>(R.id.add_widget).performClick()
        assertTrue(ShadowDialog.getLatestDialog() == null)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.startsWith("Das Handy hat den Knopf nicht von selbst hinzugefügt. Bei Xiaomi"))
        assertTrue(text, text.endsWith("„Widgets“ wählen und „Merk's“ hinzufügen."))
        assertEquals("App-Einstellungen", dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text.toString())
    }

    @Test fun widgetButtonWithoutSupportExplainsRightAway() {
        Prefs.setAskedPermissions(app)
        Prefs.prefs(app).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(false)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        main.findViewById<View>(R.id.add_widget).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.startsWith("Lange auf eine freie Stelle"))
    }

    /** No sound at all: the microphone was busy or blocked. Say that, not "nothing understood". */
    @Test fun silenceIsNamedAsSilence() {
        speechAvailable()
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        recognizer.triggerOnRmsChanged(-2f) // silence only
        recognizer.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        idleMain()
        assertEquals("Ich habe nichts gehört.", activity.findViewById<TextView>(R.id.status).text.toString())
        assertEquals(
            "Vom Mikrofon kam kein Ton. Ist es gesperrt, oder nutzt es gerade eine andere App?",
            activity.findViewById<TextView>(R.id.detail).text.toString(),
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.retry).visibility)
    }

    @Test fun silenceDuringACallNamesTheCall() {
        speechAvailable()
        (app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).mode =
            android.media.AudioManager.MODE_IN_COMMUNICATION
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idleMain()
        assertEquals("Ich habe nichts gehört.", activity.findViewById<TextView>(R.id.status).text.toString())
        assertEquals(
            "Gerade läuft ein Anruf. Solange ist das Mikrofon besetzt.",
            activity.findViewById<TextView>(R.id.detail).text.toString(),
        )
    }

    /** A recogniser that is still busy gets one fresh attempt by itself, then the error is shown. */
    @Test fun busyRecogniserIsTriedOnceMore() {
        speechAvailable()
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals("Ich höre zu …", activity.findViewById<TextView>(R.id.status).text.toString())
        val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertTrue(second !== first)
        shadowOf(second).triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.retry).visibility)
    }

    // ---------- Calendar ----------

    @Test fun singleAppointmentGoesIntoCalendarForOneHour() {
        val calendar = fakeCalendar()
        val start = LocalDate.now().plusDays(3).atTime(14, 30)
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt bei Neupert", start, null, false), "x")

        assertEquals(101L, e.calendarEventId)
        val v = calendar.inserted.single()
        assertEquals(7L, v.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals("Zahnarzt bei Neupert", v.getAsString(CalendarContract.Events.TITLE))
        assertEquals(millis(start), v.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(millis(start.plusHours(1)), v.getAsLong(CalendarContract.Events.DTEND))
        assertNull(v.getAsString(CalendarContract.Events.RRULE))

        // Deleting the entry in the app deletes the calendar event too.
        Store.remove(app, e)
        assertEquals("101", calendar.deleted.single().lastPathSegment)
    }

    @Test fun seriesGoesIntoCalendarAsRepeatingEvent() {
        val calendar = fakeCalendar()
        val text = "Alle zwei Wochen freitags um 15 Uhr Fußpflege"
        Store.save(app, RuleParser.parse(text, LocalDateTime.now()), text)

        val v = calendar.inserted.single()
        assertEquals("Fußpflege", v.getAsString(CalendarContract.Events.TITLE))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", v.getAsString(CalendarContract.Events.RRULE))
        assertEquals("PT3600S", v.getAsString(CalendarContract.Events.DURATION))
        assertNull(v.getAsLong(CalendarContract.Events.DTEND))
    }

    // ---------- Reminders and their buttons ----------

    @Test fun appointmentAlarmComesTheEveningBeforeOrAnHourAhead() {
        val start = LocalDate.now().plusDays(3).atTime(14, 30)
        Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt", start, null, false), "x")
        assertEquals(millis(start.toLocalDate().minusDays(1).atTime(18, 0)), alarms.peekNextScheduledAlarm()!!.triggerAtMs)

        // Switching the evening reminder off moves the alarm; after a reboot it is set again the same way.
        Prefs.setEveBefore(app, false)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(millis(start.minusHours(1)), alarms.peekNextScheduledAlarm()!!.triggerAtMs)
    }

    @Test fun reminderNotificationButtonsWork() {
        // A reminder that is due right now, as when its alarm has just gone off.
        val e = Store.save(app, Parsed(Kind.REMINDER, "Wäsche", LocalDateTime.now().minusMinutes(1), null, false), "x")
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).putExtra("id", e.id))

        val n = notifications.allNotifications.single()
        assertEquals("Wäsche", n.title())
        assertEquals(listOf("Erledigt", "In 15 Min.", "In 1 Std."), n.actions.map { it.title.toString() })

        // "In 15 Min.": remembered, alarm set, notification gone.
        val before = System.currentTimeMillis()
        ActionReceiver().onReceive(app, shadowOf(n.actions[1].actionIntent).savedIntent)
        val snooze = Db(app).get(e.id)!!.snooze!!
        assertTrue(snooze in before + 15 * 60_000L..System.currentTimeMillis() + 15 * 60_000L)
        assertEquals(snooze, alarms.peekNextScheduledAlarm()!!.triggerAtMs)
        assertTrue(notifications.allNotifications.isEmpty())

        // "Erledigt": ticked off, nothing more to come.
        ActionReceiver().onReceive(app, shadowOf(n.actions[0].actionIntent).savedIntent)
        assertTrue(Db(app).get(e.id)!!.done)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun doneOnASeriesKeepsTheSeriesGoing() {
        val first = LocalDate.now().plusDays(1).atTime(8, 0)
        val e = Store.save(
            app, Parsed(Kind.REMINDER, "Tabletten", first, null, false, Repeat(RepeatUnit.DAY, 1)), "x",
        )
        ActionReceiver().onReceive(app, Intent(app, ActionReceiver::class.java).putExtra("id", e.id))
        assertFalse(Db(app).get(e.id)!!.done)
        assertEquals(millis(first), alarms.peekNextScheduledAlarm()!!.triggerAtMs)
    }

    // ---------- Settings ----------

    private fun tapOn(activity: android.app.Activity, view: View) {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        val x = at[0] + view.width / 2f
        val y = at[1] + view.height / 2f
        val t = SystemClock.uptimeMillis()
        activity.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        activity.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        idleMain()
    }

    @Test fun settingsCheckTheModelWhenTheFieldIsLeft() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        idleMain()
        val model = activity.findViewById<EditText>(R.id.model)
        val status = activity.findViewById<TextView>(R.id.ai_status)
        val elsewhere = activity.findViewById<View>(R.id.ai_status)
        assertEquals("claude-haiku-5-5", model.text.toString())

        // A spelling is tidied as soon as one taps elsewhere (no key yet, so nothing is sent).
        model.requestFocus()
        model.setText("Sonnet 5.5")
        tapOn(activity, elsewhere)
        assertFalse(model.hasFocus())
        assertEquals("claude-sonnet-5-5", model.text.toString())
        assertTrue(status.text.toString().startsWith("„Sonnet 5.5“ als „claude-sonnet-5-5“ übernommen."))

        // A name that cannot exist goes back to Haiku 5.5, also via "Fertig" on the keyboard.
        model.requestFocus()
        model.setText("ä")
        model.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertFalse(model.hasFocus())
        assertEquals("claude-haiku-5-5", model.text.toString())
        assertEquals("Eingabe ungültig („ä“) – zurückgesetzt auf Haiku 5.5.", status.text.toString())

        // A tap into the other field simply moves the focus there.
        val key = activity.findViewById<EditText>(R.id.api_key)
        model.requestFocus()
        tapOn(activity, key)
        assertTrue(key.hasFocus())
        assertFalse(model.hasFocus())

        // Whatever is typed is kept when the screen is left, without pressing a button.
        model.setText("Claude Opus 5.5")
        controller.pause()
        assertEquals("claude-opus-5-5", Prefs.model(app))
    }

    @Test fun defaultModelIsStoredAsEmptySoLaterDefaultsApply() {
        Prefs.setModel(app, "claude-haiku-5-5")
        assertEquals("", Prefs.prefs(app).getString("model", null))
        assertEquals("claude-haiku-5-5", Prefs.model(app))
    }

    // ---------- Main list and update notice ----------

    @Test fun mainListShowsEntriesAndTheUpdateNotice() {
        Prefs.setAskedPermissions(app)
        // A newer version is known, and no check is due, so nothing goes to the network.
        Prefs.prefs(app).edit()
            .putLong("update_checked_at", System.currentTimeMillis())
            .putString("update_tag", "v9.0.0")
            .putString("update_url", "https://github.com/Silvertax/merks/releases/tag/v9.0.0")
            .apply()
        Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        val text = "Jeden Montag Mülltonne rausstellen"
        Store.save(app, RuleParser.parse(text, LocalDateTime.now()), text)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = activity.findViewById<ListView>(R.id.list)
        assertEquals(2, list.adapter.count)
        assertEquals("Mülltonne rausstellen", (list.adapter.getItem(0) as Entry).title)
        assertEquals("Milch kaufen", (list.adapter.getItem(1) as Entry).title)

        val banner = activity.findViewById<View>(R.id.update_banner)
        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals("Version 9.0.0 ist verfügbar.", activity.findViewById<TextView>(R.id.update_text).text.toString())
        activity.findViewById<View>(R.id.update_later).performClick()
        assertEquals(View.GONE, banner.visibility)
    }

    // ---------- OLED mode ----------

    @After fun backToFollowingThePhone() {
        TestScreens.forgetLeftovers()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    /** The screen then rebuilds itself, which the simulation cannot follow, so this stops at the switch. */
    @Test fun oledSwitchInTheDarkSettings() {
        RuntimeEnvironment.setQualifiers("+night")
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(settings.findViewById<View>(R.id.row_oled).isEnabled)
        settings.findViewById<View>(R.id.row_oled).performClick()
        assertTrue(Prefs.oled(app))
        // Black no longer makes the app dark by itself; that is what the design choice is for.
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }

    /** Only the F-Droid build (gradle -Pfdroid) leaves the update check out; F-Droid brings its updates. */
    @Test fun updateCheckInTheGithubBuild() {
        assertFalse(BuildConfig.FDROID)
        assertTrue(Updater.available)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, settings.findViewById<View>(R.id.card_app).visibility)
    }

    @Test fun oledSwitchRestsInTheLightDesign() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertFalse(settings.findViewById<View>(R.id.row_oled).isEnabled)
        assertFalse(settings.findViewById<View>(R.id.oled).isEnabled)
    }

    /** The screen then redraws itself in the new design, which the simulation cannot follow. */
    @Test fun designCanBeChosenInTheSettings() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals("Automatisch (wie das Handy)", settings.findViewById<TextView>(R.id.theme_value).text.toString())
        settings.findViewById<View>(R.id.row_theme).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("Dunkel", dialog.listView.adapter.getItem(2).toString())
        dialog.listView.performItemClick(dialog.listView.getChildAt(2), 2, 2)
        assertEquals(Appearance.DARK, Prefs.theme(app))
        assertEquals("Dunkel", settings.findViewById<TextView>(R.id.theme_value).text.toString())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
    }

    @Test fun designLightAndAutomatic() {
        Prefs.setTheme(app, Appearance.LIGHT)
        Appearance.applyNightMode(app)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
        Prefs.setTheme(app, Appearance.AUTO)
        Appearance.applyNightMode(app)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }

    /** Until 0.5.0 OLED made the app dark; whoever had it on keeps a dark app after the update. */
    @Test fun oledFromBeforeKeepsTheAppDark() {
        assertEquals(Appearance.AUTO, Prefs.theme(app))
        Prefs.setOled(app, true)
        assertEquals(Appearance.DARK, Prefs.theme(app))
        Appearance.applyNightMode(app)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
        // A choice made later wins.
        Prefs.setTheme(app, Appearance.AUTO)
        assertEquals(Appearance.AUTO, Prefs.theme(app))
    }

    @Test fun oledStaysOutOfTheLightDesign() {
        Prefs.setAskedPermissions(app)
        Prefs.prefs(app).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
        Prefs.setOled(app, true)
        Prefs.setTheme(app, Appearance.LIGHT)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = main.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue((root.background as ColorDrawable).color != Color.BLACK)
    }

    @Test fun oledModeMakesTheAppDarkAndBlack() {
        Prefs.setAskedPermissions(app)
        Prefs.prefs(app).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
        Prefs.setOled(app, true)
        // Making the app dark is AppCompat's part (see designCanBeChosenInTheSettings); here the phone is dark already.
        RuntimeEnvironment.setQualifiers("+night")

        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = main.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertEquals(Color.BLACK, (root.background as ColorDrawable).color)
        val uiMode = main.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertEquals(Configuration.UI_MODE_NIGHT_YES, uiMode)
    }

    @Test fun withoutOledTheBackgroundIsNotBlack() {
        Prefs.setAskedPermissions(app)
        Prefs.prefs(app).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = main.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue((root.background as ColorDrawable).color != Color.BLACK)
    }

    // ---------- Widget ----------

    @Test fun widgetCanBePlacedAndStartsTheRecording() {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(SpeakWidget::class.java, R.layout.widget_speak)
        val view = manager.getViewFor(id)
        assertNotNull(view)
        view.findViewById<View>(R.id.widget_root).performClick()
        idleMain()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(RecordActivity::class.java.name, started?.component?.className)
    }
}

/** A home screen that says yes to "add this widget" and then does nothing, as Xiaomi's does without its extra permission. */
@org.robolectric.annotation.Implements(android.appwidget.AppWidgetManager::class)
class SilentHomeScreen : org.robolectric.shadows.ShadowAppWidgetManager() {
    @org.robolectric.annotation.Implementation
    override fun requestPinAppWidget(provider: android.content.ComponentName, extras: android.os.Bundle?, callback: android.app.PendingIntent?): Boolean = true
}
