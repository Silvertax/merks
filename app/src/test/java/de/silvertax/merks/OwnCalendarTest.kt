package de.silvertax.merks

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.ContentUris
import android.content.DialogInterface
import android.os.Looper
import android.provider.CalendarContract
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Parsed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** "Merk's als Kalender" and the chosen reminder times, in a simulated Android. */
@RunWith(RobolectricTestRunner::class)
class OwnCalendarTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private fun waitFor(condition: () -> Boolean) {
        // By the clock: a busy build server can take seconds for what takes milliseconds here.
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out")
    }

    @After fun forgetFolders() {
        // The photo screen asks FileProvider for its folder; it remembers that for good, and the next
        // test has a cache folder of its own.
        org.robolectric.util.ReflectionHelpers
            .getStaticField<MutableMap<String, Any>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
    }

    private fun fakeCalendar(): AppSmokeTest.FakeCalendar {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        return Robolectric.setupContentProvider(AppSmokeTest.FakeCalendar::class.java, CalendarContract.AUTHORITY)
    }

    private val start = LocalDate.now().plusDays(3).atTime(14, 30)
    private fun appointment(title: String, at: LocalDateTime = start) = Store.save(app, Parsed(Kind.APPOINTMENT, title, at, null, false), title)

    private fun settings() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun message(d: AlertDialog) = d.findViewById<TextView>(android.R.id.message)!!.text.toString()

    @Test fun appointmentsStayInTheApp() {
        val calendar = fakeCalendar()
        Prefs.setOwnCalendar(app, true)
        val e = appointment("Zahnarzt")
        assertTrue(calendar.inserted.isEmpty())
        assertNull(e.calendarEventId)
        // Not in the calendar on purpose, so nothing that sounds like a problem.
        assertEquals("Termin gemerkt", app.getString(Format.savedLabel(app, e)))
        // Merk's reminds by itself: the evening before and an hour ahead, as before.
        assertEquals(millis(start.toLocalDate().minusDays(1).atTime(18, 0)), alarms.peekNextScheduledAlarm()!!.triggerAtMs)

        // A change does not reach the calendar either.
        Store.edit(app, e, "Zahnarzt", start.toLocalDate().plusDays(1), LocalTime.of(9, 0))
        assertTrue(calendar.inserted.isEmpty())
    }

    @Test fun withTheCalendarNothingChanges() {
        val calendar = fakeCalendar()
        val e = appointment("Zahnarzt")
        assertEquals(1, calendar.inserted.size)
        assertEquals("Termin im Kalender eingetragen", app.getString(Format.savedLabel(app, e)))
    }

    @Test fun switchingOnTakesTheAppointmentsOutOfTheCalendar() {
        val calendar = fakeCalendar()
        val a = appointment("Zahnarzt")
        val b = appointment("Friseur", start.plusDays(1))
        Store.save(app, Parsed(Kind.REMINDER, "Wäsche", start, null, false), "Wäsche")
        assertEquals(2, calendar.inserted.size)

        val screen = settings()
        assertFalse(screen.findViewById<android.widget.CompoundButton>(R.id.own_calendar).isChecked)
        screen.findViewById<View>(R.id.row_own_calendar).performClick()
        assertTrue(Prefs.ownCalendar(app))
        val dialog = latestDialog()
        assertEquals(
            "Merk's hat 2 Termine in den Kalender des Handys eingetragen. Sollen sie dort raus? " +
                "Sonst kann der Kalender noch zusätzlich erinnern. In Merk's bleiben sie.",
            message(dialog),
        )
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { ShadowToast.getTextOfLatestToast() == "2 Termine aus dem Kalender genommen" }
        assertEquals(setOf(a.calendarEventId, b.calendarEventId), calendar.deleted.map { ContentUris.parseId(it) }.toSet())
        // In the app everything stays, without a calendar entry.
        assertEquals(3, Db(app).all().size)
        assertTrue(Db(app).all().all { it.calendarEventId == null })
    }

    @Test fun theyMayStayInTheCalendar() {
        val calendar = fakeCalendar()
        val a = appointment("Zahnarzt")
        settings().findViewById<View>(R.id.row_own_calendar).performClick()
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        assertTrue(Prefs.ownCalendar(app))
        assertTrue(calendar.deleted.isEmpty())
        assertEquals(a.calendarEventId, Db(app).get(a.id)!!.calendarEventId)
    }

    @Test fun switchingOffPutsThemIntoTheCalendar() {
        val calendar = fakeCalendar()
        Prefs.setOwnCalendar(app, true)
        val a = appointment("Zahnarzt")
        appointment("Friseur", start.plusDays(1))
        Store.save(app, Parsed(Kind.NOTE, "Milch", null, null, false), "Milch")
        assertTrue(calendar.inserted.isEmpty())

        val screen = settings()
        assertTrue(screen.findViewById<android.widget.CompoundButton>(R.id.own_calendar).isChecked)
        screen.findViewById<View>(R.id.row_own_calendar).performClick()
        assertFalse(Prefs.ownCalendar(app))
        val dialog = latestDialog()
        assertEquals("2 Termine stehen nur in Merk's. Sollen sie jetzt auch in den Kalender des Handys?", message(dialog))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { ShadowToast.getTextOfLatestToast() == "2 Termine in den Kalender eingetragen" }
        assertEquals(setOf("Zahnarzt", "Friseur"), calendar.inserted.map { it.getAsString(CalendarContract.Events.TITLE) }.toSet())
        assertTrue(Db(app).get(a.id)!!.calendarEventId in listOf(101L, 102L))
        // The note has no date and stays as it is.
        assertTrue(Db(app).all().filter { it.kind == Kind.NOTE }.all { it.calendarEventId == null })
    }

    @Test fun switchingOffAsksForTheCalendarFirst() {
        Prefs.setOwnCalendar(app, true)
        appointment("Zahnarzt")
        val screen = settings()
        screen.findViewById<View>(R.id.row_own_calendar).performClick()
        val asked = shadowOf(screen).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), asked.requestedPermissions.toList())

        // Allowed: then the offer to add what is only in Merk's.
        val calendar = fakeCalendar()
        screen.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(0, 0))
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor { calendar.inserted.size == 1 }
    }

    @Test fun withoutTheCalendarItSaysSo() {
        Prefs.setOwnCalendar(app, true)
        val screen = settings()
        screen.findViewById<View>(R.id.row_own_calendar).performClick()
        val asked = shadowOf(screen).lastRequestedPermission
        screen.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(-1, -1))
        assertEquals("Ohne Zugriff auf den Kalender bleiben die Termine nur in Merk's.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun reminderTimesCanBeChosen() {
        fakeCalendar()
        appointment("Zahnarzt")
        val screen = settings()
        val value = screen.findViewById<TextView>(R.id.leads_value)
        // What the former "evening before" switch did, until something else is chosen.
        assertEquals("Am Vorabend um 18 Uhr, 1 Stunde vorher", value.text.toString())

        screen.findViewById<View>(R.id.row_leads).performClick()
        val dialog = latestDialog()
        val list = dialog.listView
        assertEquals(
            listOf(
                "1 Woche vorher", "1 Tag vorher", "Am Vorabend um 18 Uhr", "2 Stunden vorher",
                "1 Stunde vorher", "30 Minuten vorher", "15 Minuten vorher", "Zu Beginn",
            ),
            (0 until list.adapter.count).map { list.adapter.getItem(it).toString() },
        )
        // The ticks are set when the rows are drawn.
        for (i in 0 until list.adapter.count) list.adapter.getView(i, null, list)
        assertEquals(listOf(2, 4), (0 until list.adapter.count).filter { list.isItemChecked(it) })
        // A day ahead instead of the evening before.
        for (i in listOf(1, 2)) list.performItemClick(list.adapter.getView(i, null, list), i, i.toLong())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        // The dialog hands the button on through the main loop.
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf(Lead.DAY, Lead.HOUR), Prefs.leads(app))
        assertEquals("1 Tag vorher, 1 Stunde vorher", value.text.toString())
        // The appointment already saved follows right away.
        waitFor { alarms.peekNextScheduledAlarm()?.triggerAtMs == millis(start.minusDays(1)) }
    }

    @Test fun noReminderAtAll() {
        Prefs.setLeads(app, emptySet())
        val screen = settings()
        assertEquals("Keine Erinnerung", screen.findViewById<TextView>(R.id.leads_value).text.toString())
        appointment("Zahnarzt")
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test fun thePhotoScreenSavesInsteadOfAddingToTheCalendar() {
        Prefs.setOwnCalendar(app, true)
        val photo = Robolectric.buildActivity(PhotoActivity::class.java).setup().get()
        assertEquals("Eintragen", photo.findViewById<Button>(R.id.save).text.toString())
    }

    /** "OK" on the reminder a day ahead puts it away; the one an hour ahead still comes. */
    @Test fun anEarlyReminderDoesNotSettleTheAppointment() {
        Prefs.setLeads(app, setOf(Lead.DAY, Lead.HOUR))
        val at = LocalDateTime.now().plusHours(23)
        val e = appointment("Zahnarzt", at)
        assertEquals(millis(at.minusHours(1)), alarms.peekNextScheduledAlarm()!!.triggerAtMs)
        // As when the alarm a day ahead goes off (a little late).
        AlarmReceiver().onReceive(app, android.content.Intent(app, AlarmReceiver::class.java).putExtra("id", e.id))
        val notifications = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        val n = notifications.allNotifications.single()
        assertEquals(listOf("OK", "In 15 Min.", "In 1 Std."), n.actions.map { it.title.toString() })

        ActionReceiver().onReceive(app, shadowOf(n.actions[0].actionIntent).savedIntent)
        assertFalse(Db(app).get(e.id)!!.done)
        assertTrue(notifications.allNotifications.isEmpty())
        assertEquals(millis(at.minusHours(1)), alarms.peekNextScheduledAlarm()!!.triggerAtMs)
    }
}
