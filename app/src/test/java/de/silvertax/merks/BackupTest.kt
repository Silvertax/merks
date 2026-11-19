package de.silvertax.merks

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Parsed
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun backup(): ByteArray = ByteArrayOutputStream().also { Backup.write(app, it) }.toByteArray()

    /** Everything goes into the file and comes back: entries with all they carry, and the settings. */
    @Test fun entriesAndSettingsComeBack() {
        val day = LocalDate.now().plusDays(3)
        val yoga = Store.save(app, Parsed(Kind.APPOINTMENT, "Yoga", day.atTime(10, 0), day.atTime(11, 0), false), "Yoga morgen")
        Store.addNote(app, yoga, "Matte mitbringen")
        val bin = Store.save(app, Parsed(Kind.APPOINTMENT, "Mülltonne", day.atStartOfDay(), null, true, Repeat(RepeatUnit.WEEK, 2)), "Mülltonne")
        Store.save(app, Parsed(Kind.NOTE, "Milch kaufen", null, null, false), "Milch kaufen")
        Db(app).setDone(bin.id, true)
        Prefs.setAccent(app, Appearance.RED)
        Prefs.setTheme(app, Appearance.DARK)
        Prefs.setSpeechPause(app, Prefs.PAUSE_MANUAL)
        Prefs.setApiKey(app, "sk-geheim")
        val before = Db(app).all()

        val file = backup()
        // The AI key stays out of the file.
        assertFalse(String(file).contains("sk-geheim"))

        // A fresh start: other entries, other settings.
        for (e in Db(app).all()) Db(app).delete(e.id)
        Store.save(app, Parsed(Kind.NOTE, "Etwas anderes", null, null, false), "Etwas anderes")
        Prefs.setAccent(app, Appearance.TEAL)
        Prefs.setTheme(app, Appearance.LIGHT)
        Prefs.setApiKey(app, "sk-neu")

        val content = Backup.read(ByteArrayInputStream(file))!!
        assertEquals(3, content.entries.size)
        Backup.restore(app, content)
        val after = Db(app).all()
        fun strip(list: List<Entry>) = list.map { it.copy(id = 0, calendarEventId = null) }.sortedBy { it.title }
        assertEquals(strip(before), strip(after))
        assertEquals("Matte mitbringen", after.single { it.title == "Yoga" }.note)
        assertTrue(after.single { it.title == "Mülltonne" }.done)
        assertEquals(Appearance.RED, Prefs.accent(app))
        assertEquals(Appearance.DARK, Prefs.theme(app))
        assertTrue(Prefs.waitsForTap(app))
        // The key on this phone stays as it is.
        assertEquals("sk-neu", Prefs.apiKey(app))
    }

    /** A link to an event in the phone's calendar only comes back when that event is there: never to a stranger's. */
    @Test fun calendarLinksFromElsewhereAreDropped() {
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, "Yoga", LocalDate.now().plusDays(1).atTime(9, 0), null, false), "Yoga")
        Db(app).replace(e.copy(calendarEventId = 4711))
        val content = Backup.read(ByteArrayInputStream(backup()))!!
        assertEquals(4711L, content.entries.single().calendarEventId)
        Backup.restore(app, content)
        assertNull(Db(app).all().single().calendarEventId)
    }

    @Test fun otherFilesAreNoBackup() {
        assertNull(Backup.read(ByteArrayInputStream("{\"app\":\"other\"}".toByteArray())))
        assertNull(Backup.read(ByteArrayInputStream("not json".toByteArray())))
        assertNull(Backup.read(ByteArrayInputStream(ByteArray(0))))
    }

    /** The buttons in the settings ask the phone where to save, and which file to read. */
    @Test fun settingsOfferBackupAndRestore() {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        settings.findViewById<View>(R.id.backup_save).performClick()
        val save = shadowOf(settings).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.action)
        assertEquals("Merks-Sicherung-${LocalDate.now()}.json", save.getStringExtra(Intent.EXTRA_TITLE))
        settings.findViewById<View>(R.id.backup_restore).performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(settings).nextStartedActivityForResult.intent.action)
        assertNotNull(Uri.EMPTY)
    }
}
