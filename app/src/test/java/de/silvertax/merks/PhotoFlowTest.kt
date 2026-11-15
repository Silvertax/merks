package de.silvertax.merks

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import de.silvertax.merks.parse.AiError
import de.silvertax.merks.parse.AiPhotoResult
import de.silvertax.merks.parse.AiProblem
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.PhotoDate
import de.silvertax.merks.parse.PhotoFind
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
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The way from the photo button to the calendar, in a simulated Android. The camera, the text
 * recognition and the AI are stand-ins: the camera writes a few bytes, the recognition "reads" a
 * given text, the AI answers what the test says.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoFlowTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val original = PhotoScan.parts
    private val zone = ZoneId.systemDefault()

    private fun waitFor(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out")
    }

    @After fun realParts() {
        PhotoScan.parts = original
        // FileProvider remembers its folders for good; here every test has a cache folder of its own.
        org.robolectric.util.ReflectionHelpers
            .getStaticField<MutableMap<String, Any>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
    }

    /** Stand-ins for the picture, the text recognition and the AI. */
    private class Parts(
        var first: String = "",
        var second: String = "",
        var ai: AiPhotoResult? = null,
        var opens: Boolean = true,
        var reads: Boolean = true,
    ) : PhotoScan.Parts {
        val passes = mutableListOf<Int>()
        var asked = 0

        override fun load(file: File, maxSide: Int, minSide: Int): Bitmap? =
            if (opens) Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888) else null

        override fun text(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): String {
            if (!reads) throw UnsatisfiedLinkError("no tesseract here")
            passes += pass
            return if (pass == 1) first else second
        }

        override fun ai(key: String, model: String, jpeg: ByteArray, now: LocalDateTime, zone: ZoneId): AiPhotoResult {
            asked++
            return ai ?: AiPhotoResult(null, AiError(AiProblem.NO_CONNECTION, detail = "offline"))
        }
    }

    private val day = LocalDate.now().plusDays(5)
    private val dotted = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val card get() =
        """
        Zahnarztpraxis Dr. med. dent. Anna Neupert
        Meißner Str. 12 - 01445 Radebeul . Tel. 0351 830 12 34
        \hr nächster Termin:
        Mo, ${day.format(dotted)} um 10:30 Uhr
        Bitte bringen Sie Ihre Versichertenkarte mit.
        Bei Verhinderung bitte 24 Stunden vorher absagen.
        """.trimIndent()

    private val shot get() = File(app.cacheDir, "photo/shot.jpg")
    private fun model(a: PhotoActivity) = ViewModelProvider(a)[PhotoModel::class.java]
    private fun status(a: Activity) = a.findViewById<TextView>(R.id.status).text.toString()
    private fun visible(a: Activity, id: Int) = a.findViewById<View>(id).visibility == View.VISIBLE

    private fun fakeCalendar(): AppSmokeTest.FakeCalendar {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        return Robolectric.setupContentProvider(AppSmokeTest.FakeCalendar::class.java, CalendarContract.AUTHORITY)
    }

    /** Opens the photo screen, lets the camera take a photo and waits until it has been read. */
    private fun photograph(parts: Parts): PhotoActivity {
        PhotoScan.parts = parts
        val activity = Robolectric.buildActivity(PhotoActivity::class.java).setup().get()
        val asked = shadowOf(activity).peekNextStartedActivityForResult()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, asked.intent.action)
        @Suppress("DEPRECATION")
        val out = asked.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        assertEquals("content://de.silvertax.merks.files/photo/shot.jpg", out.toString())
        shot.writeBytes(byteArrayOf(1, 2, 3))
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, null)
        waitFor { !model(activity).busy && model(activity).stage != PhotoModel.Stage.READING }
        return activity
    }

    private fun rows(a: Activity) = a.findViewById<LinearLayout>(R.id.dates)
    private fun row(a: Activity, i: Int) = rows(a).getChildAt(i)

    @Test fun aPhotoOfACardBecomesAnAppointment() {
        val calendar = fakeCalendar()
        val parts = Parts(first = card)
        val activity = photograph(parts)

        // The photo is gone as soon as it was read.
        assertFalse(shot.exists())
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertTrue(visible(activity, R.id.review))
        assertEquals(listOf(1), parts.passes)
        assertEquals(0, parts.asked)
        assertEquals("Das habe ich gefunden. Bitte kurz prüfen:", activity.findViewById<TextView>(R.id.review_label).text.toString())
        assertEquals("Zahnarztpraxis Dr. Anna Neupert", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        assertEquals("Bitte bringen Sie Ihre Versichertenkarte mit.", activity.findViewById<EditText>(R.id.photo_note).text.toString())
        assertEquals(1, rows(activity).childCount)
        assertEquals(Format.dayText(activity, day), row(activity, 0).findViewById<TextView>(R.id.date_day).text.toString())
        assertEquals("10:30 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
        // One date: nothing to tick.
        assertEquals(View.GONE, row(activity, 0).findViewById<View>(R.id.date_pick).visibility)
        assertFalse(visible(activity, R.id.source_hint))

        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }

        val e = Db(app).all().single()
        assertEquals(Kind.APPOINTMENT, e.kind)
        assertEquals("Zahnarztpraxis Dr. Anna Neupert", e.title)
        assertEquals(day.atTime(10, 30).atZone(zone).toInstant().toEpochMilli(), e.start)
        assertFalse(e.allDay)
        assertEquals("Bitte bringen Sie Ihre Versichertenkarte mit.", e.note)
        // Only the line with the date stays, marked as read from a photo; not the whole card.
        assertEquals("📷 Mo, ${day.format(dotted)} um 10:30 Uhr", e.raw)
        val event = calendar.inserted.single()
        assertEquals("Zahnarztpraxis Dr. Anna Neupert", event.getAsString(CalendarContract.Events.TITLE))
        assertEquals(
            "Bitte bringen Sie Ihre Versichertenkarte mit.\n\n📷 Mo, ${day.format(dotted)} um 10:30 Uhr",
            event.getAsString(CalendarContract.Events.DESCRIPTION),
        )

        assertEquals("Termin im Kalender eingetragen", status(activity))
        assertTrue(visible(activity, R.id.done))
        assertTrue(visible(activity, R.id.undo))
        activity.findViewById<View>(R.id.done).performClick()
        assertTrue(activity.isFinishing)
    }

    @Test fun titleAndNoteCanBeChangedBeforeSaving() {
        fakeCalendar()
        val activity = photograph(Parts(first = card))
        activity.findViewById<EditText>(R.id.photo_title).setText("Zahnarzt")
        activity.findViewById<EditText>(R.id.photo_note).setText("")
        // Another day and time, as with the pickers.
        activity.picked(0, day.plusDays(1), LocalTime.of(8, 0))
        assertEquals("08:00 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val e = Db(app).all().single()
        assertEquals("Zahnarzt", e.title)
        assertNull(e.note)
        assertEquals(day.plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), e.start)
    }

    @Test fun anEmptyTitleIsNotSaved() {
        val activity = photograph(Parts(first = card))
        activity.findViewById<EditText>(R.id.photo_title).setText(" ")
        activity.findViewById<View>(R.id.save).performClick()
        assertEquals("Bitte einen Text eingeben.", activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.photo_title_box).error?.toString())
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertTrue(Db(app).all().isEmpty())
    }

    @Test fun severalDatesAreTickedOneByOne() {
        fakeCalendar()
        val (a, b, c) = listOf(3L, 5L, 8L).map { LocalDate.now().plusDays(it) }
        val treatments = "Physiotherapie Lößnitz\nIhre Termine\n" +
            listOf(a, b, c).joinToString("\n") { it.format(dotted) + " 08:15 Uhr" }
        val activity = photograph(Parts(first = treatments))
        assertEquals("3 Termine gefunden. Was angehakt ist, kommt in den Kalender:", activity.findViewById<TextView>(R.id.review_label).text.toString())
        assertEquals(3, rows(activity).childCount)
        val second = row(activity, 1).findViewById<CheckBox>(R.id.date_pick)
        assertEquals(View.VISIBLE, second.visibility)
        assertTrue(second.isChecked)
        second.performClick()
        assertFalse(model(activity).dates[1].chosen)

        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val saved = Db(app).all().sortedBy { it.start }
        assertEquals(listOf(a, c), saved.map { java.time.Instant.ofEpochMilli(it.start!!).atZone(zone).toLocalDate() })
        assertTrue(saved.all { it.title == "Physiotherapie Lößnitz" })
        assertEquals("2 Termine eingetragen", status(activity))

        // "Undo" takes both out again, from the app and from the calendar.
        activity.findViewById<View>(R.id.undo).performClick()
        assertTrue(Db(app).all().isEmpty())
        assertTrue(activity.isFinishing)
    }

    @Test fun nothingTickedIsNotSaved() {
        val (a, b) = listOf(3L, 5L).map { LocalDate.now().plusDays(it) }
        val activity = photograph(Parts(first = "Termine:\n${a.format(dotted)} 9:00 Uhr\n${b.format(dotted)} 9:00 Uhr"))
        row(activity, 0).findViewById<CheckBox>(R.id.date_pick).performClick()
        row(activity, 1).findViewById<CheckBox>(R.id.date_pick).performClick()
        activity.findViewById<View>(R.id.save).performClick()
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertTrue(Db(app).all().isEmpty())
    }

    @Test fun withoutADateTheDateCanBeChosenByHand() {
        fakeCalendar()
        val parts = Parts(first = "Friseur Haarscharf\nWaschen, Schneiden", second = "Friseur Haarscharf")
        val activity = photograph(parts)
        // Read twice, the second time looking for columns, before giving up.
        assertEquals(listOf(1, 2), parts.passes)
        assertEquals(PhotoModel.Stage.NOTHING, model(activity).stage)
        assertEquals("Kein Datum gefunden", status(activity))
        assertTrue(activity.findViewById<TextView>(R.id.detail).text.toString().contains("Handschrift kann das Handy ohne KI kaum lesen."))
        assertTrue(visible(activity, R.id.retake))
        assertTrue(visible(activity, R.id.by_hand))
        assertTrue(visible(activity, R.id.show_text))

        activity.findViewById<View>(R.id.show_text).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("Friseur Haarscharf\nWaschen, Schneiden", dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
        dialog.dismiss()

        activity.picked(-1, day, LocalTime.of(14, 0))
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals("Friseur Haarscharf", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val e = Db(app).all().single()
        assertEquals(day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(), e.start)
        assertEquals("📷 Friseur Haarscharf", e.raw)
    }

    @Test fun noTextAtAll() {
        val activity = photograph(Parts(first = "", second = " "))
        assertEquals("Kein Text zu erkennen", status(activity))
        assertFalse(visible(activity, R.id.show_text))
    }

    @Test fun theAiReadsThePhotoWhenAKeyIsSet() {
        fakeCalendar()
        Prefs.setApiKey(app, "sk-ant-test")
        val parts = Parts(
            first = card,
            ai = AiPhotoResult(
                PhotoFind("Zahnarzt Dr. Neupert", "Versichertenkarte mitbringen", listOf(PhotoDate(day, LocalTime.of(10, 30), source = "23.11. 10:30")), byAi = true),
                null,
            ),
        )
        val activity = photograph(parts)
        assertEquals(1, parts.asked)
        // No need for the phone to read as well.
        assertTrue(parts.passes.isEmpty())
        assertTrue(Prefs.aiOk(app))
        assertEquals("Zahnarzt Dr. Neupert", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        assertEquals("Von der KI gelesen.", activity.findViewById<TextView>(R.id.source_hint).text.toString())
        assertFalse(visible(activity, R.id.review_show_text))
        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        assertEquals("📷 23.11. 10:30", Db(app).all().single().raw)
    }

    @Test fun withoutConnectionThePhoneReadsItself() {
        Prefs.setApiKey(app, "sk-ant-test")
        val parts = Parts(first = card)
        val activity = photograph(parts)
        assertEquals(1, parts.asked)
        assertEquals(listOf(1), parts.passes)
        assertFalse(Prefs.aiOk(app))
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals("Die KI war nicht erreichbar, das Handy hat selbst gelesen.", activity.findViewById<TextView>(R.id.source_hint).text.toString())
    }

    @Test fun whenTheAiSeesNoDateThePhoneHasALook() {
        Prefs.setApiKey(app, "sk-ant-test")
        val parts = Parts(first = card, ai = AiPhotoResult(PhotoFind("Zahnarzt", null, emptyList(), byAi = true), null))
        val activity = photograph(parts)
        assertEquals(listOf(1), parts.passes)
        assertEquals(1, model(activity).dates.size)
        assertFalse(visible(activity, R.id.source_hint))
    }

    @Test fun cameraClosedWithoutAPhoto() {
        PhotoScan.parts = Parts(first = card)
        val activity = Robolectric.buildActivity(PhotoActivity::class.java).setup().get()
        val asked = shadowOf(activity).peekNextStartedActivityForResult()
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        assertTrue(activity.isFinishing)
        assertTrue(Db(app).all().isEmpty())
    }

    @Test fun aPhotoThatCannotBeOpenedIsDeletedAnyway() {
        val activity = photograph(Parts(opens = false))
        assertFalse(shot.exists())
        assertEquals(PhotoModel.Stage.FAILED, model(activity).stage)
        assertEquals("Das Foto ließ sich nicht öffnen.", status(activity))
        assertTrue(visible(activity, R.id.retake))
    }

    @Test fun aPhoneWithoutTextRecognitionSaysSo() {
        val activity = photograph(Parts(first = card, reads = false))
        assertFalse(shot.exists())
        assertEquals("Das Lesen von Text klappt auf diesem Handy leider nicht.", status(activity))
    }

    @Test fun noCameraApp() {
        shadowOf(app).checkActivities(true)
        PhotoScan.parts = Parts(first = card)
        val activity = Robolectric.buildActivity(PhotoActivity::class.java).setup().get()
        assertEquals("Keine Kamera-App gefunden.", status(activity))
        assertTrue(visible(activity, R.id.done))
        assertFalse(visible(activity, R.id.retake))
    }

    /** Low on memory, Android ends the app while the camera is open; the photo still arrives. */
    @Test fun aRestartWhileTheCameraIsOpenLosesNothing() {
        fakeCalendar()
        PhotoScan.parts = Parts(first = card)
        val first = Robolectric.buildActivity(PhotoActivity::class.java).setup()
        val asked = shadowOf(first.get()).nextStartedActivityForResult
        val state = Bundle()
        first.pause().stop().saveInstanceState(state).destroy()
        shot.writeBytes(byteArrayOf(1, 2, 3))

        val second = Robolectric.buildActivity(PhotoActivity::class.java).setup(state)
        val activity = second.get()
        // The camera is not opened a second time: its photo is on the way.
        assertNull(shadowOf(activity).peekNextStartedActivityForResult())
        shadowOf(activity).callOnActivityResult(asked.requestCode, Activity.RESULT_OK, null)
        waitFor { model(activity).stage == PhotoModel.Stage.REVIEW }
        assertEquals("Zahnarztpraxis Dr. Anna Neupert", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        assertFalse(shot.exists())
    }

    /** What was typed and ticked survives a restart while checking, e.g. after a look at the calendar. */
    @Test fun aRestartWhileCheckingKeepsTheChanges() {
        val (a, b) = listOf(3L, 5L).map { LocalDate.now().plusDays(it) }
        val first = Robolectric.buildActivity(PhotoActivity::class.java)
        PhotoScan.parts = Parts(first = "Physiotherapie\n${a.format(dotted)} 9:00 Uhr\n${b.format(dotted)} 9:00 Uhr")
        first.setup()
        val asked = shadowOf(first.get()).nextStartedActivityForResult
        shot.writeBytes(byteArrayOf(1))
        shadowOf(first.get()).receiveResult(asked.intent, Activity.RESULT_OK, null)
        waitFor { model(first.get()).stage == PhotoModel.Stage.REVIEW }
        first.get().findViewById<EditText>(R.id.photo_title).setText("Krankengymnastik")
        row(first.get(), 1).findViewById<CheckBox>(R.id.date_pick).performClick()
        val state = Bundle()
        first.pause().stop().saveInstanceState(state).destroy()

        val again = Robolectric.buildActivity(PhotoActivity::class.java).setup(state).get()
        assertEquals(PhotoModel.Stage.REVIEW, model(again).stage)
        assertNull(shadowOf(again).peekNextStartedActivityForResult())
        assertEquals("Krankengymnastik", again.findViewById<EditText>(R.id.photo_title).text.toString())
        assertTrue(row(again, 0).findViewById<CheckBox>(R.id.date_pick).isChecked)
        assertFalse(row(again, 1).findViewById<CheckBox>(R.id.date_pick).isChecked)
    }

    @Test fun mainScreenHasThePhotoButton() {
        Prefs.setAskedPermissions(app)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val button = main.findViewById<TextView>(R.id.photo)
        assertEquals("Termin fotografieren", button.text.toString())
        button.performClick()
        assertEquals(PhotoActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)
    }

    @Test fun theListSaysWhereAnEntryCameFrom() {
        Prefs.setAskedPermissions(app)
        val photo = Store.save(app, PhotoDate(day, LocalTime.of(9, 0)).toParsed("Zahnarzt"), Store.PHOTO_MARK + "Di 24.11. 09:00 Uhr")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = main.findViewById<android.widget.ListView>(R.id.list)
        list.adapter.getView(0, null, list).findViewById<View>(R.id.card).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.endsWith("Vom Foto gelesen: „Di 24.11. 09:00 Uhr“"))
        assertEquals("Zahnarzt", photo.title)
    }

    @Test fun thePhotoWidgetOpensTheCamera() {
        val widgets = shadowOf(AppWidgetManager.getInstance(app))
        val id = widgets.createWidget(PhotoWidget::class.java, R.layout.widget_photo)
        val view = widgets.getViewFor(id)
        // The simulation draws the smallest shape, the camera without a label; the description names it.
        assertEquals("Foto", view.findViewById<View>(R.id.widget_root).contentDescription?.toString())
        val icon = view.findViewById<android.widget.ImageView>(R.id.widget_icon)
        assertEquals(R.drawable.ic_camera, shadowOf(icon.drawable).createdFromResId)
        view.findViewById<View>(R.id.widget_root).performClick()
        assertEquals(PhotoActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
    }
}
