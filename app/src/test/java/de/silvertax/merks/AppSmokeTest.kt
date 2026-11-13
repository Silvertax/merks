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
import java.time.LocalTime
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
        assertEquals(listOf("Rückgängig", "Ändern"), n.actions.map { it.title.toString() })
        // "Ändern" opens the recording for this entry.
        val change = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(entry.id, change.getLongExtra("change_id", -1))

        // "Rückgängig" in the notification removes it again.
        UndoReceiver().onReceive(app, shadowOf(n.actions[0].actionIntent).savedIntent)
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

    /** Taps "Put on the home screen" and picks the button to speak (0) or "Up next" (1). */
    private fun pickWidget(main: MainActivity, which: Int): android.app.Dialog {
        main.findViewById<View>(R.id.add_widget).performClick()
        val chooser = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = chooser.listView
        assertEquals(2, list.adapter.count)
        list.performItemClick(list.adapter.getView(which, null, list), which, list.adapter.getItemId(which))
        return chooser
    }

    /** A home screen that adds the button: nothing to explain. */
    @Test fun widgetButtonStaysQuietWhenThePhoneAddsIt() {
        Prefs.setAskedPermissions(app)
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(true)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val chooser = pickWidget(main, 0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(ShadowDialog.getLatestDialog() === chooser)
        assertFalse(chooser.isShowing)
    }

    /** Both widgets are on offer, and the way by hand names the one that was picked. */
    @Test fun widgetButtonOffersUpNextToo() {
        Prefs.setAskedPermissions(app)
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(false)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val chooser = pickWidget(main, 1)
        val shown = (chooser as androidx.appcompat.app.AlertDialog).listView.adapter
        assertTrue(shown.getItem(0).toString().startsWith("Knopf „Sprechen“"))
        assertTrue(shown.getItem(1).toString().startsWith("„Als Nächstes“"))
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.endsWith("„Merk's“ suchen und „Als Nächstes“ hinzufügen."))
    }

    /** Xiaomi's home screen takes the request and drops it silently: then the app explains the way by hand. */
    @org.robolectric.annotation.Config(shadows = [SilentHomeScreen::class])
    @Test fun widgetButtonExplainsWhenThePhoneDoesNothing() {
        Prefs.setAskedPermissions(app)
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(true)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "Xiaomi")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val chooser = pickWidget(main, 0)
        assertTrue(ShadowDialog.getLatestDialog() === chooser)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.startsWith("Das Handy hat es nicht von selbst hinzugefügt. Bei Xiaomi"))
        assertTrue(text, text.endsWith("„Widgets“ wählen, „Merk's“ suchen und „Sprechen“ hinzufügen."))
        assertEquals("App-Einstellungen", dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text.toString())
    }

    @Test fun widgetButtonWithoutSupportExplainsRightAway() {
        Prefs.setAskedPermissions(app)
        shadowOf(app.getSystemService(android.appwidget.AppWidgetManager::class.java)).setRequestPinAppWidgetSupported(false)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        pickWidget(main, 0)
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

    @Test fun rangeGoesIntoCalendarWithItsEnd() {
        val calendar = fakeCalendar()
        val start = LocalDate.now().plusDays(3).atTime(10, 0)
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, "Handwerker", start, start.withHour(12), false), "x")

        val v = calendar.inserted.single()
        assertEquals(millis(start), v.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(millis(start.withHour(12)), v.getAsLong(CalendarContract.Events.DTEND))
        assertTrue(Format.whenText(app, e), Format.whenText(app, e).endsWith("10:00–12:00 Uhr"))
    }

    @Test fun severalDaysGoIntoCalendarAsOneLongEvent() {
        val calendar = fakeCalendar()
        val first = LocalDate.now().plusDays(3)
        val text = "Urlaub"
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, text, first.atStartOfDay(), first.plusDays(4).atStartOfDay(), true), text)

        val v = calendar.inserted.single()
        assertEquals(1, v.getAsInteger(CalendarContract.Events.ALL_DAY))
        val days = (v.getAsLong(CalendarContract.Events.DTEND) - v.getAsLong(CalendarContract.Events.DTSTART)) / (24 * 3600_000L)
        assertEquals(5L, days)
        assertTrue(Format.whenText(app, e), Format.whenText(app, e).contains(" – "))

        // Once it has begun, "Up next" shows until when it lasts.
        val ongoing = e.copy(start = millis(LocalDate.now().minusDays(1).atStartOfDay()), end = millis(LocalDate.now().plusDays(2).atStartOfDay()))
        val shown = Format.compactWhen(app, ongoing, ongoing.start!!, System.currentTimeMillis())
        assertTrue(shown, shown.startsWith("bis "))
    }

    @Test fun workdaySeriesGoesIntoCalendarMondayToFriday() {
        val calendar = fakeCalendar()
        val text = "Werktags um 7 Uhr Hund ausführen"
        val e = Store.save(app, RuleParser.parse(text, LocalDateTime.now()), text)

        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", calendar.inserted.single().getAsString(CalendarContract.Events.RRULE))
        assertTrue(Format.whenText(app, e), Format.whenText(app, e).endsWith("werktags (Mo–Fr)"))
        assertTrue(java.time.Instant.ofEpochMilli(e.start!!).atZone(zone).dayOfWeek.value <= 5)
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

    // ---------- Changing the date or time by voice ----------

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun status(a: android.app.Activity) = a.findViewById<TextView>(R.id.status).text.toString()

    @Test fun aNoteGetsADateByVoiceAndBecomesAnAppointment() {
        val calendar = fakeCalendar()
        speechAvailable()
        Prefs.setCompact(app, false)
        val note = Store.save(app, Parsed(Kind.NOTE, "Zahnarzt bei Neupert", null, null, false), "Zahnarzt bei Neupert")

        val activity = Robolectric.buildActivity(RecordActivity::class.java, RecordActivity.changeIntent(app, note.id)).setup().get()
        assertEquals("Ich höre zu …", status(activity))
        assertEquals(
            "Neue Zeit für „Zahnarzt bei Neupert“, z. B. „Freitag um 10“",
            activity.findViewById<TextView>(R.id.detail).text.toString(),
        )
        say("morgen um 10")
        waitFor { status(activity) == "Geändert" }

        val changed = Db(app).get(note.id)!!
        assertEquals(1, Db(app).all().size)
        assertEquals(Kind.APPOINTMENT, changed.kind)
        assertEquals("Zahnarzt bei Neupert", changed.title)
        assertEquals(ms(LocalDate.now().plusDays(1).atTime(10, 0)), changed.start)
        assertEquals("Zahnarzt bei Neupert → morgen um 10", changed.raw)
        assertEquals(1, calendar.inserted.size)
        assertEquals("Zahnarzt bei Neupert", calendar.inserted.single().getAsString(CalendarContract.Events.TITLE))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.change).visibility)

        // "Undo" brings the note back, and the calendar entry goes again.
        activity.findViewById<View>(R.id.undo).performClick()
        val back = Db(app).get(note.id)!!
        assertEquals(Kind.NOTE, back.kind)
        assertNull(back.start)
        assertNull(back.calendarEventId)
        assertEquals(1, calendar.deleted.size)
    }

    @Test fun anAppointmentMovesWithItsCalendarEntry() {
        val calendar = fakeCalendar()
        speechAvailable()
        val now = LocalDateTime.now()
        val text = "Freitag um 15 Uhr Friseur"
        val first = Store.save(app, RuleParser.parse(text, now), text)
        assertEquals(1, calendar.inserted.size)
        val firstCalendarId = first.calendarEventId!!

        // Small mode: the info closes and a notification says what changed.
        val activity = Robolectric.buildActivity(RecordActivity::class.java, RecordActivity.changeIntent(app, first.id)).setup().get()
        say("Samstag um 11")
        waitFor { activity.isFinishing }

        val moved = Db(app).get(first.id)!!
        assertEquals("Friseur", moved.title)
        assertEquals(ms(RuleParser.parse("Samstag um 11", now).start!!), moved.start)
        assertEquals(2, calendar.inserted.size)
        assertTrue(calendar.deleted.single().toString().endsWith("/$firstCalendarId"))
        val n = notifications.allNotifications.single { it.title() == "Geändert" }
        assertTrue(n.text()!!.endsWith("Friseur"))
        assertEquals(listOf("Rückgängig", "Ändern"), n.actions.map { it.title.toString() })

        // "Undo" in the notification puts the old time back.
        UndoReceiver().onReceive(app, Intent(app, UndoReceiver::class.java).putExtra("id", first.id).putExtra("changed", true))
        val back = Db(app).get(first.id)!!
        assertEquals(first.start, back.start)
        assertEquals(3, calendar.inserted.size)
        assertEquals(1, Db(app).all().size)
    }

    @Test fun withoutADateNothingIsChanged() {
        speechAvailable()
        Prefs.setCompact(app, false)
        val note = Store.save(app, Parsed(Kind.NOTE, "Oma anrufen", null, null, false), "Oma anrufen")
        val activity = Robolectric.buildActivity(RecordActivity::class.java, RecordActivity.changeIntent(app, note.id)).setup().get()
        say("ach egal")
        waitFor { status(activity) == "Ich habe kein Datum und keine Uhrzeit gehört." }
        assertEquals(note, Db(app).get(note.id))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.retry).visibility)
    }

    @Test fun anEntryThatIsGoneSaysSo() {
        speechAvailable()
        val activity = Robolectric.buildActivity(RecordActivity::class.java, RecordActivity.changeIntent(app, 4711)).setup().get()
        assertEquals("Diesen Eintrag gibt es nicht mehr.", status(activity))
    }

    @Test fun theEntryDialogOffersToChangeByVoice() {
        Prefs.setAskedPermissions(app)
        val note = Store.save(app, Parsed(Kind.NOTE, "Zahnarzt", null, null, false), "Zahnarzt")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = main.findViewById<ListView>(R.id.list)
        list.adapter.getView(0, null, list).findViewById<View>(R.id.card).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        fun find(v: View): TextView? = when {
            v is TextView && v.text.toString() == "Datum oder Uhrzeit ändern" -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val button = find(dialog.window!!.decorView)!!
        button.performClick()
        val started = shadowOf(main).nextStartedActivity
        assertEquals(RecordActivity::class.java.name, started.component!!.className)
        assertEquals(note.id, started.getLongExtra("change_id", -1))
    }

    // ---------- What comes next, at a glance ----------

    @Test fun theQuietNotificationShowsWhatComesNext() {
        val tomorrow = LocalDate.now().plusDays(1).atTime(10, 0)
        // Off unless switched on.
        val first = Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt", tomorrow, null, false), "Morgen um 10 Zahnarzt")
        assertNull(notifications.getNotification(Glance.NOTIFICATION_ID))

        Prefs.setNextUpNotification(app, true)
        Glance.refresh(app)
        val n = notifications.getNotification(Glance.NOTIFICATION_ID)!!
        assertEquals("Zahnarzt", n.title())
        assertEquals("Morgen, 10:00 Uhr", n.text())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)

        // Something sooner comes first.
        val today = LocalDateTime.now().plusMinutes(90)
        val sooner = Store.save(app, Parsed(Kind.REMINDER, "Tabletten nehmen", today, null, false), "Tabletten")
        assertEquals("Tabletten nehmen", notifications.getNotification(Glance.NOTIFICATION_ID)!!.title())

        Store.remove(app, sooner)
        Store.remove(app, first)
        assertNull(notifications.getNotification(Glance.NOTIFICATION_ID))
    }

    @Test fun theUpNextWidgetShowsWhatComesNext() {
        val widgets = shadowOf(AppWidgetManager.getInstance(app))
        val id = widgets.createWidget(NextWidget::class.java, R.layout.widget_next)
        assertEquals("Nichts geplant", widgets.getViewFor(id).findViewById<TextView>(R.id.next_title).text.toString())

        Store.save(app, Parsed(Kind.APPOINTMENT, "Fußpflege", LocalDate.now().plusDays(1).atTime(9, 0), null, false), "Fußpflege")
        val view = widgets.getViewFor(id)
        assertEquals("Fußpflege", view.findViewById<TextView>(R.id.next_title).text.toString())
        assertEquals("Morgen, 09:00 Uhr", view.findViewById<TextView>(R.id.next_when).text.toString())
    }

    /** Two cells wide, next to the "Speak" button: the day on top, the time below, so nothing is cut off. */
    @Test fun theUpNextWidgetFitsTwoCells() {
        fun look(shape: NextWidget.Companion.Shape, narrow: Boolean = false, low: Boolean = false) =
            NextWidget.Companion.Look(shape, narrow, low)
        val line = NextWidget.Companion.Shape.LINE
        val block = NextWidget.Companion.Shape.BLOCK
        val list = NextWidget.Companion.Shape.LIST
        assertEquals(look(line), NextWidget.lookFor(250f, 80f))
        assertEquals(look(line, narrow = true), NextWidget.lookFor(161f, 80f))
        assertEquals(look(line, narrow = true, low = true), NextWidget.lookFor(161f, 60f))
        // Larger print needs more room before three lines fit.
        assertEquals(look(line, narrow = true, low = true), NextWidget.lookFor(161f, 80f, fontScale = 1.3f))
        assertEquals(look(block, narrow = true), NextWidget.lookFor(161f, 110f))
        assertEquals(look(list, narrow = true), NextWidget.lookFor(161f, 180f))
        assertEquals(look(block), NextWidget.lookFor(0f, 0f))

        val day = LocalDate.now().plusDays(5)
        Store.save(app, Parsed(Kind.APPOINTMENT, "Fußpflege", day.atTime(9, 0), null, false), "Fußpflege")
        Store.save(app, Parsed(Kind.APPOINTMENT, "Oma zu Besuch", day.plusDays(1).atStartOfDay(), null, true), "Oma")
        val next = Glance.nextUp(app)
        val now = System.currentTimeMillis()
        fun visible(v: View): Boolean = v.visibility == View.VISIBLE && ((v.parent as? View)?.let { visible(it) } ?: true)
        fun texts(l: NextWidget.Companion.Look): List<String> {
            val v = NextWidget.views(app, next, now, l).apply(app, null)
            return listOf(R.id.next_label, R.id.next_title, R.id.next_when, R.id.next_line2).map { id ->
                v.findViewById<TextView>(id).let { if (visible(it)) it.text.toString() else "" }
            }
        }
        val date = java.time.format.DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMANY).format(day)
        val short = java.time.format.DateTimeFormatter.ofPattern("d.M.", Locale.GERMANY)
        assertEquals(listOf(date, "Fußpflege", "09:00 Uhr", ""), texts(look(line, narrow = true)))
        assertEquals(listOf("", "Fußpflege", short.format(day) + ", 09:00 Uhr", ""), texts(look(line, narrow = true, low = true)))
        assertEquals(
            listOf(date, "Fußpflege", "09:00 Uhr", short.format(day.plusDays(1)) + " · Oma zu Besuch"),
            texts(look(list, narrow = true)),
        )
        // Wide stays as it was.
        assertEquals(listOf("", "Fußpflege", "$date, 09:00 Uhr", ""), texts(look(line)))
    }

    // ---------- Main list ----------

    /** What has a date shows day and month in place of the symbol; notes and done things keep their symbol. */
    @Test fun mainListShowsTheDateAtAGlance() {
        Prefs.setAskedPermissions(app)
        val day = LocalDate.now().plusDays(5)
        Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt", day.atTime(10, 0), null, false), "Zahnarzt")
        val done = Store.save(app, Parsed(Kind.REMINDER, "Rezept abholen", day.plusDays(1).atTime(9, 0), null, false), "Rezept")
        Db(app).setDone(done.id, true)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = activity.findViewById<ListView>(R.id.list)
        fun row(title: String): View = (0 until list.adapter.count).map { list.adapter.getView(it, null, list) }
            .first { it.findViewById<TextView>(R.id.title).text.toString() == title }

        val dated = row("Zahnarzt")
        assertEquals(View.VISIBLE, dated.findViewById<View>(R.id.badge_date).visibility)
        assertEquals(View.GONE, dated.findViewById<View>(R.id.badge_icon).visibility)
        assertEquals(day.dayOfMonth.toString(), dated.findViewById<TextView>(R.id.badge_day).text.toString())
        val month = java.time.format.DateTimeFormatter.ofPattern("MMM", Locale.GERMANY).format(day).trimEnd('.')
        assertEquals(month, dated.findViewById<TextView>(R.id.badge_month).text.toString())

        for (title in listOf("Milch kaufen", "Rezept abholen")) {
            assertEquals(title, View.GONE, row(title).findViewById<View>(R.id.badge_date).visibility)
            assertEquals(title, View.VISIBLE, row(title).findViewById<View>(R.id.badge_icon).visibility)
        }
    }

    @Test fun mainListShowsEntries() {
        Prefs.setAskedPermissions(app)
        Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        val text = "Jeden Montag Mülltonne rausstellen"
        Store.save(app, RuleParser.parse(text, LocalDateTime.now()), text)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = activity.findViewById<ListView>(R.id.list)
        assertEquals(2, list.adapter.count)
        assertEquals("Mülltonne rausstellen", (list.adapter.getItem(0) as Entry).title)
        assertEquals("Milch kaufen", (list.adapter.getItem(1) as Entry).title)
    }

    // ---------- Changing by typing ----------

    private fun editScreen(e: Entry) = Robolectric.buildActivity(EditActivity::class.java, EditActivity.intent(app, e.id)).setup()

    /** A word the recognition got wrong is fixed by typing; date and calendar entry follow along. */
    @Test fun entryTextCanBeFixedByTyping() {
        val calendar = fakeCalendar()
        val start = LocalDate.now().plusDays(3).atTime(14, 30)
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt bei Neubert", start, start.plusHours(2), false), "Zahnarzt bei Neubert")
        val screen = editScreen(e).get()
        val text = screen.findViewById<TextView>(R.id.edit_text)
        assertEquals("Zahnarzt bei Neubert", text.text.toString())
        assertEquals(Format.fullDate(app, start.toLocalDate()), screen.findViewById<TextView>(R.id.date_value).text.toString())
        assertEquals("14:30 Uhr", screen.findViewById<TextView>(R.id.time_value).text.toString())

        text.text = "Zahnarzt bei Neupert"
        screen.findViewById<View>(R.id.save).performClick()
        assertTrue(screen.isFinishing)

        val saved = Db(app).get(e.id)!!
        assertEquals("Zahnarzt bei Neupert", saved.title)
        assertEquals(millis(start), saved.start)
        assertEquals(millis(start.plusHours(2)), saved.end)
        assertEquals(e.created, saved.created)
        // The calendar shows the new text: old event out, new one in.
        assertEquals(1, calendar.deleted.size)
        assertEquals("Zahnarzt bei Neupert", calendar.inserted.last().getAsString(CalendarContract.Events.TITLE))
    }

    /** Day and time are picked instead of spoken; a time span keeps its length. */
    @Test fun entryDayAndTimeCanBeChangedByTyping() {
        fakeCalendar()
        val start = LocalDate.now().plusDays(3).atTime(14, 30)
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, "Handwerker", start, start.plusHours(2), false), "Handwerker")
        val screen = editScreen(e).get()

        // The rows open the pickers.
        screen.findViewById<View>(R.id.row_date).performClick()
        idleMain()
        assertNotNull(screen.supportFragmentManager.findFragmentByTag("day"))
        screen.findViewById<View>(R.id.row_time).performClick()
        idleMain()
        assertNotNull(screen.supportFragmentManager.findFragmentByTag("time"))

        val day = LocalDate.now().plusDays(10)
        screen.setDay(day)
        screen.setTime(LocalTime.of(9, 15))
        assertEquals(Format.fullDate(app, day), screen.findViewById<TextView>(R.id.date_value).text.toString())
        assertEquals("09:15 Uhr", screen.findViewById<TextView>(R.id.time_value).text.toString())
        screen.findViewById<View>(R.id.save).performClick()

        val saved = Db(app).get(e.id)!!
        assertEquals("Handwerker", saved.title)
        assertEquals(millis(day.atTime(9, 15)), saved.start)
        assertEquals(millis(day.atTime(11, 15)), saved.end)
        assertFalse(saved.allDay)

        // "Remove the time" makes it a whole day.
        val again = editScreen(saved).get()
        assertEquals(View.VISIBLE, again.findViewById<View>(R.id.remove_time).visibility)
        again.findViewById<View>(R.id.remove_time).performClick()
        assertEquals("Ohne Uhrzeit (ganztägig)", again.findViewById<TextView>(R.id.time_value).text.toString())
        again.findViewById<View>(R.id.save).performClick()
        val wholeDay = Db(app).get(e.id)!!
        assertTrue(wholeDay.allDay)
        assertEquals(millis(day.atStartOfDay()), wholeDay.start)
        assertNull(wholeDay.end)
    }

    /** A note has no day; given one, it becomes an appointment. Without one it stays a note with a new text. */
    @Test fun noteGetsADayByTyping() {
        fakeCalendar()
        val note = Store.save(app, Parsed(Kind.NOTE, "Friseur", null, null, false), "Friseur")
        val screen = editScreen(note).get()
        assertEquals("Kein Tag", screen.findViewById<TextView>(R.id.date_value).text.toString())
        assertFalse(screen.findViewById<View>(R.id.row_time).isEnabled)
        assertEquals("Mit einem Tag wird aus der Notiz ein Termin.", screen.findViewById<TextView>(R.id.edit_note).text.toString())
        screen.findViewById<TextView>(R.id.edit_text).text = "Friseur Schmidt"
        screen.findViewById<View>(R.id.save).performClick()
        assertEquals(Kind.NOTE, Db(app).get(note.id)!!.kind)
        assertEquals("Friseur Schmidt", Db(app).get(note.id)!!.title)

        val day = LocalDate.now().plusDays(4)
        val again = editScreen(note).get()
        again.setDay(day)
        assertTrue(again.findViewById<View>(R.id.row_time).isEnabled)
        again.setTime(LocalTime.of(16, 0))
        again.findViewById<View>(R.id.save).performClick()
        val saved = Db(app).get(note.id)!!
        assertEquals(Kind.APPOINTMENT, saved.kind)
        assertEquals(millis(day.atTime(16, 0)), saved.start)
        assertNotNull(saved.calendarEventId)
    }

    /** A reminder always keeps a time, and a series its rhythm; an empty text is not saved. */
    @Test fun editingKeepsWhatMustStay() {
        val at = LocalDate.now().plusDays(2).atTime(8, 0)
        val reminder = Store.save(app, Parsed(Kind.REMINDER, "Tabletten", at, null, false, Repeat(RepeatUnit.DAY, 1)), "Tabletten")
        val screen = editScreen(reminder).get()
        assertEquals(View.GONE, screen.findViewById<View>(R.id.remove_time).visibility)
        assertTrue(screen.findViewById<TextView>(R.id.edit_note).text.toString().startsWith("Wiederholt sich: täglich."))
        screen.setTime(null)
        assertEquals("08:00 Uhr", screen.findViewById<TextView>(R.id.time_value).text.toString())

        screen.findViewById<TextView>(R.id.edit_text).text = "   "
        screen.findViewById<View>(R.id.save).performClick()
        assertFalse(screen.isFinishing)
        assertEquals("Tabletten", Db(app).get(reminder.id)!!.title)

        screen.findViewById<TextView>(R.id.edit_text).text = "Tabletten nehmen"
        screen.setTime(LocalTime.of(7, 30))
        screen.findViewById<View>(R.id.save).performClick()
        val saved = Db(app).get(reminder.id)!!
        assertEquals("Tabletten nehmen", saved.title)
        assertEquals(Kind.REMINDER, saved.kind)
        assertEquals(Repeat(RepeatUnit.DAY, 1), saved.repeat)
        assertEquals(millis(at.toLocalDate().atTime(7, 30)), saved.start)
    }

    /** The way in: tap the entry, then "Schriftlich ändern". */
    @Test fun entryDialogOpensTheEditScreen() {
        Prefs.setAskedPermissions(app)
        val note = Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = main.findViewById<ListView>(R.id.list)
        list.adapter.getView(0, null, list).findViewById<View>(R.id.card).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val button = dialog.findViewById<TextView>(R.id.edit_entry)!!
        assertEquals("Schriftlich ändern", button.text.toString())
        button.performClick()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(EditActivity::class.java.name, opened.component!!.className)
        assertEquals(note.id, opened.getLongExtra("id", -1))
    }

    // ---------- Sharing ----------

    /** The text the share sheet was handed, or null if nothing was shared. */
    private fun sharedText(): String? {
        val chooser = shadowOf(app).nextStartedActivity ?: return null
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        return send.getStringExtra(Intent.EXTRA_TEXT)
    }

    /** One tap passes on everything that is coming up, soonest first; notes and done things stay out. */
    @Test fun upcomingEntriesCanBeShared() {
        Prefs.setAskedPermissions(app)
        Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        val onlyNotes = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.GONE, onlyNotes.findViewById<View>(R.id.share).visibility)

        val day = LocalDate.now().plusDays(5)
        Store.save(app, Parsed(Kind.APPOINTMENT, "Oma zu Besuch", day.plusDays(2).atStartOfDay(), null, true), "Oma")
        Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt", day.atTime(10, 0), day.atTime(11, 0), false), "Zahnarzt")
        val done = Store.save(app, Parsed(Kind.REMINDER, "Rezept abholen", day.plusDays(1).atTime(9, 0), null, false), "Rezept")
        Db(app).setDone(done.id, true)

        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val share = main.findViewById<View>(R.id.share)
        assertEquals(View.VISIBLE, share.visibility)
        assertEquals("Termine teilen", share.contentDescription.toString())
        share.performClick()

        val short = java.time.format.DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMANY)
        val long = java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)
        assertEquals(
            "Das steht an:\n\n" +
                "• ${short.format(day)}, 10:00–11:00 Uhr: Zahnarzt\n" +
                "• ${long.format(day.plusDays(2))}: Oma zu Besuch",
            sharedText(),
        )
    }

    /** A single entry is passed on from its dialog; a note is just its text. */
    @Test fun singleEntryCanBeShared() {
        Prefs.setAskedPermissions(app)
        val day = LocalDate.now().plusDays(5)
        Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        Store.save(app, Parsed(Kind.APPOINTMENT, "Zahnarzt", day.atTime(10, 0), null, false), "Zahnarzt")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = main.findViewById<ListView>(R.id.list)

        fun shareRow(position: Int): String? {
            list.adapter.getView(position, null, list).findViewById<View>(R.id.card).performClick()
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val button = dialog.findViewById<TextView>(R.id.share_entry)!!
            assertEquals("Teilen", button.text.toString())
            button.performClick()
            assertFalse(dialog.isShowing)
            return sharedText()
        }
        val date = java.time.format.DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMANY).format(day)
        assertEquals("$date, 10:00 Uhr: Zahnarzt", shareRow(0))
        assertEquals("Milch kaufen", shareRow(1))
    }

    // ---------- Updates: only on request ----------

    @After fun realUpdateSource() { Updater.source = null }

    private fun checkForUpdates(answer: Updater.Info?): android.app.Activity {
        var asked = 0
        Updater.source = { asked++; answer }
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val state = settings.findViewById<TextView>(R.id.update_state)
        // Nothing is asked until the button is tapped.
        idleMain()
        assertEquals(0, asked)
        assertTrue(state.text.toString().startsWith("Installiert: Version "))
        val button = settings.findViewById<View>(R.id.check_updates)
        assertEquals("Jetzt nach Updates suchen", (button as TextView).text.toString())
        button.performClick()
        waitFor { button.isEnabled }
        assertEquals(1, asked)
        return settings
    }

    @After fun realDownload() {
        Updater.download = null
        Updater.cleanUp(app)
        // The simulation keeps this permission from one test to the next.
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        // FileProvider remembers its folders for good; here every test has a cache folder of its own.
        org.robolectric.util.ReflectionHelpers
            .getStaticField<MutableMap<String, Any>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
    }

    private val newer = Updater.Info("v9.0.0", "https://github.com/Silvertax/merks/releases/tag/v9.0.0")
    private val apkAddress = "https://github.com/Silvertax/merks/releases/download/v9.0.0/merks.apk"
    private val updateFile get() = java.io.File(app.cacheDir, "update/merks-9.0.0.apk")

    /** Stands in for the download: writes a file that the phone takes for the given app. */
    private fun downloadGives(packageName: String = app.packageName, works: Boolean = true): MutableList<String> {
        val asked = mutableListOf<String>()
        val here = app.packageManager.getPackageInfo(app.packageName, 0)
        shadowOf(app.packageManager).setPackageArchiveInfo(
            updateFile.path,
            android.content.pm.PackageInfo().apply {
                this.packageName = packageName
                longVersionCode = here.longVersionCode + 1
            },
        )
        Updater.download = { address, file, progress ->
            asked += address
            if (works) {
                file.writeText("apk")
                progress(100)
            }
            works
        }
        return asked
    }

    private fun installerOpened(intent: Intent?) {
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content://de.silvertax.merks.files/update/merks-9.0.0.apk", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /** "Update now" loads the new version in the app and opens the phone's installer; no web page to find a file on. */
    @Test fun updateButtonDownloadsAndOpensTheInstaller() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val asked = downloadGives()
        val settings = checkForUpdates(newer)
        assertEquals("Version 9.0.0 ist verfügbar.", settings.findViewById<TextView>(R.id.update_state).text.toString())
        val get = settings.findViewById<TextView>(R.id.update_get)
        assertEquals(View.VISIBLE, get.visibility)
        assertEquals("Jetzt aktualisieren", get.text.toString())
        get.performClick()
        assertFalse(get.isEnabled)
        waitFor { get.isEnabled }

        assertEquals(listOf(apkAddress), asked)
        installerOpened(shadowOf(app).nextStartedActivity)
        assertTrue(settings.findViewById<TextView>(R.id.update_state).text.toString().startsWith("Das Handy fragt jetzt"))

        // A second tap, e.g. after backing out of the installer, does not download again.
        get.performClick()
        waitFor { get.isEnabled }
        assertEquals(1, asked.size)
        installerOpened(shadowOf(app).nextStartedActivity)
    }

    /** The first time, the phone must allow it: said in plain words, then straight on once the switch is on. */
    @Test fun updateAsksOnceForThePermissionToInstall() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        downloadGives()
        Updater.source = { newer }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settings = controller.get()
        settings.findViewById<View>(R.id.check_updates).performClick()
        val get = settings.findViewById<TextView>(R.id.update_get)
        waitFor { get.visibility == View.VISIBLE }
        get.performClick()
        waitFor { get.isEnabled }

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.toString().startsWith("Damit Merk's das Update selbst installieren kann"))
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idleMain()
        val page = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, page.action)
        assertEquals("package:de.silvertax.merks", page.dataString)
        assertNull(shadowOf(app).nextStartedActivity)

        // Back with the switch on: the installer opens by itself.
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        controller.pause().resume()
        installerOpened(shadowOf(app).nextStartedActivity)
        assertNull(Prefs.pendingUpdate(app))

        // And not again on the next visit.
        controller.pause().resume()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    /** Some phones restart the app when the permission is given; the installation carries on all the same. */
    @Test fun updateCarriesOnAfterTheAppWasRestarted() {
        downloadGives()
        updateFile.parentFile!!.mkdirs()
        updateFile.writeText("apk")
        Prefs.setPendingUpdate(app, "v9.0.0")
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        Robolectric.buildActivity(SettingsActivity::class.java).setup()
        installerOpened(shadowOf(app).nextStartedActivity)
    }

    /** Back without the switch on: nothing opens, and the button is there for another try. */
    @Test fun updateWithoutThePermissionStaysPut() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        downloadGives()
        updateFile.parentFile!!.mkdirs()
        updateFile.writeText("apk")
        Prefs.setPendingUpdate(app, "v9.0.0")
        Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(Prefs.pendingUpdate(app))
    }

    /** No download, or a file that is not this app: the browser is the way left, straight to the file. */
    @Test fun updateFallsBackToTheBrowser() {
        for (wrongApp in listOf(false, true)) {
            val asked = if (wrongApp) downloadGives(packageName = "com.example.other") else downloadGives(works = false)
            shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
            val settings = checkForUpdates(newer)
            val get = settings.findViewById<TextView>(R.id.update_get)
            get.performClick()
            waitFor { get.isEnabled }
            assertEquals(1, asked.size)
            assertTrue(settings.findViewById<TextView>(R.id.update_state).text.toString().startsWith("Das Herunterladen hat nicht geklappt."))
            assertEquals("Mit dem Browser herunterladen", get.text.toString())
            assertNull(shadowOf(app).nextStartedActivity)
            assertFalse(updateFile.exists())
            get.performClick()
            val opened = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals(apkAddress, opened.dataString)
        }
    }

    /** Once the new version runs, the downloaded file is thrown away. */
    @Test fun downloadedUpdateIsRemovedAfterUpdating() {
        updateFile.parentFile!!.mkdirs()
        updateFile.writeText("apk")
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(updateFile.exists())
    }

    @Test fun updateButtonSaysWhenNothingIsNewer() {
        val settings = checkForUpdates(Updater.Info("v0.0.1", "https://github.com/Silvertax/merks/releases/tag/v0.0.1"))
        assertTrue(settings.findViewById<TextView>(R.id.update_state).text.toString().startsWith("Du hast die neueste Version ("))
        assertEquals(View.GONE, settings.findViewById<View>(R.id.update_get).visibility)
    }

    @Test fun updateButtonSaysWhenTheCheckFails() {
        val settings = checkForUpdates(null)
        assertEquals(
            "Die Suche hat nicht geklappt. Prüfe die Internetverbindung.",
            settings.findViewById<TextView>(R.id.update_state).text.toString(),
        )
        assertEquals(View.GONE, settings.findViewById<View>(R.id.update_get).visibility)
    }

    /** Opening the app asks nobody anything. */
    @Test fun openingTheAppDoesNotCheckForUpdates() {
        Prefs.setAskedPermissions(app)
        var asked = 0
        Updater.source = { asked++; null }
        Robolectric.buildActivity(MainActivity::class.java).setup()
        idleMain()
        Thread.sleep(200)
        idleMain()
        assertEquals(0, asked)
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
        Prefs.setOled(app, true)
        Prefs.setTheme(app, Appearance.LIGHT)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = main.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue((root.background as ColorDrawable).color != Color.BLACK)
    }

    @Test fun oledModeMakesTheAppDarkAndBlack() {
        Prefs.setAskedPermissions(app)
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
