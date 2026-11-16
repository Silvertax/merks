package de.silvertax.merks

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
import de.silvertax.merks.parse.OcrWord
import de.silvertax.merks.parse.PhotoDate
import de.silvertax.merks.parse.PhotoFind
import de.silvertax.merks.parse.PlanSheet
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

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
        // By the clock: a busy build server can take seconds for what takes milliseconds here.
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
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

    /** Stand-ins for the picture, the text recognition, the AI and the pages of a PDF. */
    private class Parts(
        var first: String = "",
        var second: String = "",
        var ai: AiPhotoResult? = null,
        var opens: Boolean = true,
        var reads: Boolean = true,
        /** What each page of a PDF "says"; the recognition reads a page's own words. */
        var pdfPages: List<String> = emptyList(),
        /** The words with their places, as read word by word. */
        var words: List<OcrWord> = emptyList(),
    ) : PhotoScan.Parts {
        val passes = mutableListOf<Int>()
        var wordReads = 0
        var asked = 0

        /** How many pictures the AI got, and the text it was asked about. */
        var pictures = 0
        var askedText: String? = null
        private val opened = java.util.IdentityHashMap<Bitmap, File>()

        override fun load(file: File, maxSide: Int, minSide: Int): Bitmap? =
            if (opens) Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).also { opened[it] = file } else null

        override fun text(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): String {
            if (!reads) throw UnsatisfiedLinkError("no tesseract here")
            passes += pass
            val page = opened[picture]?.name?.let { Regex("page(\\d+)\\.png").matchEntire(it) }?.groupValues?.get(1)?.toInt()
            if (page != null) return pdfPages[page - 1]
            return if (pass == 1) first else second
        }

        override fun words(ctx: Context, picture: Bitmap, german: Boolean, pass: Int): List<OcrWord> {
            if (!reads) throw UnsatisfiedLinkError("no tesseract here")
            wordReads++
            return if (pass == 1) words else emptyList()
        }

        override fun ai(key: String, model: String, jpegs: List<ByteArray>, now: LocalDateTime, zone: ZoneId): AiPhotoResult {
            asked++
            pictures = jpegs.size
            return ai ?: AiPhotoResult(null, AiError(AiProblem.NO_CONNECTION, detail = "offline"))
        }

        override fun aiText(key: String, model: String, text: String, now: LocalDateTime, zone: ZoneId): AiPhotoResult {
            asked++
            askedText = text
            return ai ?: AiPhotoResult(null, AiError(AiProblem.NO_CONNECTION, detail = "offline"))
        }

        override fun pages(file: File, dir: File, maxSide: Int, max: Int): List<File> =
            pdfPages.take(max).indices.map { i -> File(dir, "page${i + 1}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
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
        assertEquals("3 Termine gefunden. Was angehakt ist, wird eingetragen:", activity.findViewById<TextView>(R.id.review_label).text.toString())
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
        assertEquals("Foto", view.findViewById<View>(android.R.id.background).contentDescription?.toString())
        val icon = view.findViewById<android.widget.ImageView>(R.id.widget_icon)
        assertEquals(R.drawable.w_photo_button, shadowOf(icon.drawable).createdFromResId)
        view.findViewById<View>(android.R.id.background).performClick()
        assertEquals(PhotoActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
    }

    /** A weekly plan read by the AI: each entry with its own title and room, the marked ones ticked. */
    @Test fun aWeeklyPlanBecomesAppointmentsWithTheirOwnTitles() {
        fakeCalendar()
        Prefs.setApiKey(app, "sk-ant-test")
        val plan = PhotoFind(
            "Kursplan Familienzentrum",
            null,
            listOf(
                PhotoDate(day, LocalTime.of(7, 30), title = "Frühsport", detail = "Saal 1"),
                PhotoDate(day, LocalTime.of(9, 0), LocalTime.of(10, 0), title = "Töpfern", detail = "Raum 12", chosen = false),
                PhotoDate(day.plusDays(1), LocalTime.of(13, 30), title = "Chor"),
            ),
            byAi = true,
        )
        val activity = photograph(Parts(first = card, ai = AiPhotoResult(plan, null)))
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        // Every entry has its own title, so there is no common one to fill in.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.photo_title_box).visibility)
        assertEquals("Frühsport", row(activity, 0).findViewById<TextView>(R.id.date_title).text.toString())
        assertEquals("07:30 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
        assertEquals("Saal 1", row(activity, 0).findViewById<TextView>(R.id.date_detail).text.toString())
        // Only what is marked on the plan is listed; a button brings the rest.
        assertEquals(2, rows(activity).childCount)
        assertEquals("Chor", row(activity, 1).findViewById<TextView>(R.id.date_title).text.toString())
        assertEquals("2 markierte Termine auf dem Plan gefunden. Was angehakt ist, wird eingetragen:", activity.findViewById<TextView>(R.id.review_label).text.toString())
        val more = activity.findViewById<TextView>(R.id.more_dates)
        assertEquals(View.VISIBLE, more.visibility)
        assertEquals("1 weiteren Termin zeigen", more.text.toString())
        more.performClick()
        assertEquals(View.GONE, more.visibility)
        assertEquals(3, rows(activity).childCount)
        assertFalse(row(activity, 1).findViewById<CheckBox>(R.id.date_pick).isChecked)
        assertEquals(View.GONE, row(activity, 2).findViewById<View>(R.id.date_detail).visibility)
        // Why one is not ticked: it was not marked on the plan.
        assertEquals(
            "3 Termine gefunden. Angehakt ist, was auf dem Plan markiert ist. Eingetragen wird, was angehakt ist:",
            activity.findViewById<TextView>(R.id.review_label).text.toString(),
        )

        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val saved = Db(app).all().sortedBy { it.start }
        assertEquals(listOf("Frühsport", "Chor"), saved.map { it.title })
        // The room goes into the entry's note.
        assertEquals("Saal 1", saved[0].note)
        assertNull(saved[1].note)
    }

    /** A long plan the AI read with everything ticked: the phone looks for the highlighter itself. */
    @Test fun aPlanTheAiTickedCompletelyIsCheckedForMarksByThePhone() {
        Prefs.setApiKey(app, "sk-ant-test")
        val dates = (0 until 8).map { PhotoDate(day.plusDays(it.toLong()), LocalTime.of(9, 0), title = "Kurs $it") }
        val parts = Parts(first = card, ai = AiPhotoResult(PhotoFind("Kursplan", null, dates, byAi = true), null), words = PlanSheet.courses().words)
        val activity = photograph(parts)
        assertEquals(2, parts.wordReads)
        assertEquals(8, model(activity).dates.size)
        // A short list, or one with ticks of the AI's own, is left alone.
        val few = Parts(first = card, ai = AiPhotoResult(PhotoFind("Kursplan", null, dates.take(3), byAi = true), null))
        photograph(few)
        assertEquals(0, few.wordReads)
        val own = Parts(first = card, ai = AiPhotoResult(PhotoFind("Kursplan", null, dates.mapIndexed { i, d -> d.copy(chosen = i % 2 == 0) }, byAi = true), null))
        photograph(own)
        assertEquals(0, own.wordReads)
    }

    @Test fun aWeeklyPlanIsReadColumnByColumnWithoutAi() {
        fakeCalendar()
        val next = LocalDate.now().plusWeeks(1)
        val week = next.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val sheet = PlanSheet.courses(week = "KW $week")
        // Read as one text, the columns run into each other; the days show it is a plan.
        val parts = Parts(first = "Kursplan Familienzentrum Lindenhof KW $week\nMontag Dienstag Mittwoch Donnerstag Freitag\n07:45 Frühsport 08:30 Offene Werkstatt 07:45 Frühsport", words = sheet.words)
        val activity = photograph(parts)
        // Read as a page once, then word by word twice.
        assertEquals(2, parts.wordReads)
        assertEquals(listOf(1), parts.passes)
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        val dates = model(activity).dates
        assertEquals(14, dates.size)
        val monday = next.with(DayOfWeek.MONDAY)
        assertEquals(PhotoDate(monday, LocalTime.of(7, 45), source = "07:45 Frühsport Turnhalle", title = "Frühsport", detail = "Turnhalle"), dates[0])
        assertEquals(View.GONE, activity.findViewById<View>(R.id.photo_title_box).visibility)
        // Nothing is marked, so nothing is folded away.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.more_dates).visibility)
        assertEquals("Töpfern", row(activity, 1).findViewById<TextView>(R.id.date_title).text.toString())
        assertEquals("Werkraum", row(activity, 1).findViewById<TextView>(R.id.date_detail).text.toString())
        assertEquals("14 Termine gefunden. Was angehakt ist, wird eingetragen:", activity.findViewById<TextView>(R.id.review_label).text.toString())

        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val saved = Db(app).all().sortedBy { it.start }
        assertEquals(14, saved.size)
        assertEquals("Frühsport", saved[0].title)
        assertEquals("Turnhalle", saved[0].note)
    }

    @Test fun whatLooksLikeAPlanAndIsNoneIsReadAsUsual() {
        val parts = Parts(first = "Montag Dienstag Mittwoch geschlossen\n$card")
        val activity = photograph(parts)
        assertEquals(2, parts.wordReads)
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals(listOf(day), model(activity).dates.map { it.day })
    }

    // ---------- From elsewhere: passed on from another app, or picked ----------

    /** A file another app hands over, as WhatsApp's "Share" does, served by its content provider. */
    private fun offer(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://com.example.chat.files/$name")
        shadowOf(app.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(bytes))
        return uri
    }

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 0x4A, 0x46)

    /** Opens the photo screen as another app's "Share" or "Open with" does, and waits until it has read. */
    private fun passOn(intent: Intent, parts: Parts): PhotoActivity {
        PhotoScan.parts = parts
        val activity = Robolectric.buildActivity(PhotoActivity::class.java, intent).setup().get()
        waitFor { !model(activity).busy && model(activity).stage != PhotoModel.Stage.READING }
        return activity
    }

    private fun toolbarTitle(a: Activity) = a.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title.toString()
    private fun imports() = File(app.cacheDir, PhotoActivity.IMPORT_DIR).list().orEmpty().toList()

    @Test fun aPictureSharedFromAnotherAppIsReadLikeAPhoto() {
        fakeCalendar()
        val parts = Parts(first = card)
        val uri = offer("IMG-20261116-WA0006.jpg", jpegBytes)
        val activity = passOn(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri), parts)
        // No camera: what was passed on is read, and the copy of it is gone afterwards.
        assertNull(shadowOf(activity).peekNextStartedActivityForResult())
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals(emptyList<String>(), imports())
        assertEquals("Termin aus Datei", toolbarTitle(activity))
        assertEquals("Zahnarztpraxis Dr. Anna Neupert", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        assertEquals("10:30 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
        // Trying again picks another file rather than opening the camera.
        assertEquals("Andere Datei wählen", activity.findViewById<TextView>(R.id.review_retake).text.toString())

        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val e = Db(app).all().single()
        assertEquals(day.atTime(10, 30).atZone(zone).toInstant().toEpochMilli(), e.start)
        assertEquals("📄 Mo, ${day.format(dotted)} um 10:30 Uhr", e.raw)
    }

    /** A letter as a PDF: its pages are read together, the title on the first, the dates on the second. */
    @Test fun thePagesOfAPdfAreReadTogether() {
        val later = day.plusDays(7)
        val parts = Parts(pdfPages = listOf("Praxis für Physiotherapie Lößnitz\nIhre Behandlungstermine", "${day.format(dotted)} 9:00 Uhr\n${later.format(dotted)} 9:00 Uhr"))
        val pdf = offer("Termine.pdf", "%PDF-1.7\n1 0 obj".toByteArray())
        val activity = passOn(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, pdf), parts)
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals(listOf(day, later), model(activity).dates.map { it.day })
        assertEquals(emptyList<String>(), imports())

        // With the AI, both pages go in one request.
        Prefs.setApiKey(app, "sk-ant-test")
        val ai = Parts(pdfPages = parts.pdfPages, ai = AiPhotoResult(PhotoFind("Physiotherapie", null, listOf(PhotoDate(day, LocalTime.of(9, 0))), byAi = true), null))
        passOn(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, offer("Termine2.pdf", "%PDF-1.7\n".toByteArray())), ai)
        assertEquals(1, ai.asked)
        assertEquals(2, ai.pictures)
    }

    /** A booking confirmation's calendar file: taken over exactly, nothing is guessed. */
    @Test fun aCalendarFileIsTakenOverExactly() {
        fakeCalendar()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:Zahnarzt Dr. Neupert\r\n" +
            "DTSTART:${day.atTime(10, 30).format(stamp)}\r\nDTEND:${day.atTime(11, 0).format(stamp)}\r\nLOCATION:Meißner Str. 12\\, Radebeul\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val parts = Parts()
        val activity = passOn(Intent(Intent.ACTION_VIEW).setDataAndType(offer("termin.ics", ics.toByteArray()), "text/calendar"), parts)
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertTrue(parts.passes.isEmpty())
        assertEquals(0, parts.asked)
        assertEquals("Zahnarzt Dr. Neupert", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        assertEquals("Meißner Str. 12, Radebeul", activity.findViewById<EditText>(R.id.photo_note).text.toString())
        assertEquals("10:30–11:00 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())

        activity.findViewById<View>(R.id.save).performClick()
        waitFor { model(activity).stage == PhotoModel.Stage.SAVED }
        val e = Db(app).all().single()
        assertEquals(day.atTime(10, 30).atZone(zone).toInstant().toEpochMilli(), e.start)
        assertEquals(day.atTime(11, 0).atZone(zone).toInstant().toEpochMilli(), e.end)
        assertEquals("📄 Zahnarzt Dr. Neupert, Meißner Str. 12, Radebeul", e.raw)
    }

    @Test fun aMessagePassedOnAsTextIsRead() {
        val text = "Hallo Frau Lukomski, Ihr nächster Termin ist am ${day.format(dotted)} um 10:30 Uhr. Praxis Dr. Neupert"
        val activity = passOn(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), Parts())
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals("Termin aus Text", toolbarTitle(activity))
        assertEquals(listOf(day), model(activity).dates.map { it.day })
        assertEquals("10:30 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
        // From a text there is no other file to choose.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.review_retake).visibility)
    }

    /** A short message reads like something said, and is understood the same way. */
    @Test fun aShortMessageIsUnderstoodLikeSpeaking() {
        val activity = passOn(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Morgen um 10 Zahnarzt"), Parts())
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals("Zahnarzt", activity.findViewById<EditText>(R.id.photo_title).text.toString())
        assertEquals(Format.dayText(activity, LocalDate.now().plusDays(1)), row(activity, 0).findViewById<TextView>(R.id.date_day).text.toString())
        assertEquals("10:00 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
    }

    @Test fun aPictureOrFileCanBePickedFromTheMainScreen() {
        Prefs.setAskedPermissions(app)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val button = main.findViewById<TextView>(R.id.from_file)
        assertEquals("Aus Bild oder Datei", button.text.toString())
        button.performClick()
        val started = shadowOf(main).nextStartedActivity
        assertEquals(PhotoActivity::class.java.name, started.component?.className)

        PhotoScan.parts = Parts(first = card)
        val activity = Robolectric.buildActivity(PhotoActivity::class.java, started).setup().get()
        val asked = shadowOf(activity).peekNextStartedActivityForResult()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.intent.action)
        assertEquals(listOf("image/*", "application/pdf", "text/calendar", "text/plain"), asked.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(offer("Brief.jpg", jpegBytes)))
        waitFor { !model(activity).busy && model(activity).stage != PhotoModel.Stage.READING }
        assertEquals(PhotoModel.Stage.REVIEW, model(activity).stage)
        assertEquals("10:30 Uhr", row(activity, 0).findViewById<TextView>(R.id.date_time).text.toString())
    }

    @Test fun pickingNothingCloses() {
        PhotoScan.parts = Parts(first = card)
        val activity = Robolectric.buildActivity(PhotoActivity::class.java, Intent(app, PhotoActivity::class.java).putExtra(PhotoActivity.EXTRA_PICK, true)).setup().get()
        val asked = shadowOf(activity).peekNextStartedActivityForResult()
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        assertTrue(activity.isFinishing)
    }

    /** Another app cannot make Merk's read its own files, nor plain paths on the phone. */
    @Test fun theAppsOwnFilesAreNotRead() {
        for (uri in listOf(Uri.parse("content://de.silvertax.merks.files/photo/shot.jpg"), Uri.parse("file:///sdcard/Download/brief.jpg"))) {
            val activity = passOn(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri), Parts(first = card))
            assertEquals(PhotoModel.Stage.FAILED, model(activity).stage)
            assertEquals("Die Datei ließ sich nicht öffnen.", status(activity))
        }
    }

    @Test fun theListSaysWhenAnEntryCameFromAFile() {
        Prefs.setAskedPermissions(app)
        Store.save(app, PhotoDate(day, LocalTime.of(9, 0)).toParsed("Zahnarzt"), Store.FILE_MARK + "Zahnarzt Dr. Neupert")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = main.findViewById<android.widget.ListView>(R.id.list)
        list.adapter.getView(0, null, list).findViewById<View>(R.id.card).performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.endsWith("Aus Datei oder Nachricht übernommen: „Zahnarzt Dr. Neupert“"))
    }
}
