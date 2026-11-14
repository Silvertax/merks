package de.silvertax.merks

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Parsed
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Renders the screens to PNG files for design reviews and the store listing. Skipped unless
 * MERKS_SCREENSHOTS names an output folder, so normal builds never write images.
 * MERKS_SCREENSHOTS_LANG=en renders the English app, otherwise German.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotRender {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var out: File
    private var english = false

    private fun t(de: String, en: String) = if (english) en else de

    @Before fun onlyWhenAsked() {
        val dir = System.getenv("MERKS_SCREENSHOTS")
        assumeTrue("set MERKS_SCREENSHOTS to a folder to render", !dir.isNullOrBlank())
        out = File(dir!!).apply { mkdirs() }
        english = System.getenv("MERKS_SCREENSHOTS_LANG") == "en"
        RuntimeEnvironment.setQualifiers(if (english) "+en-rUS" else "+de-rDE")
        Prefs.setAskedPermissions(app)
    }

    @After fun reset() = androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    )

    private fun idle(ms: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (ms > 0) looper.idleFor(Duration.ofMillis(ms)) else looper.idle()
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(view: View, name: String, background: Int? = null) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (background != null) canvas.drawColor(background)
        view.draw(canvas)
        save(bmp, name)
    }

    private fun shotActivity(activity: Activity, name: String) {
        idle(400)
        shot(activity.window.decorView, name)
    }

    private fun night(on: Boolean) {
        if (on) RuntimeEnvironment.setQualifiers("+night")
    }

    @Test fun settingsTopDark() {
        night(true)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shotActivity(activity, "7b_settings_top_dark")
    }

    private fun sampleEntries() {
        val today = LocalDate.now()
        val dentist = Store.save(app, Parsed(Kind.APPOINTMENT, t("Zahnarzt bei Neupert", "Dentist, Dr. Miller"), today.plusDays(1).atTime(10, 0), null, false), "x")
        Store.addNote(app, dentist, t("Versichertenkarte mitnehmen", "Bring the insurance card"))
        Store.save(app, Parsed(Kind.REMINDER, t("Wäsche aufhängen", "Hang up the laundry"), LocalDateTime.now().plusHours(2).withMinute(0), null, false), "x")
        val series = t("Jeden Montag Mülltonne rausstellen", "Every Monday take out the trash")
        Store.save(app, Store.sort(app, series), series)
        Store.save(app, Parsed(Kind.APPOINTMENT, t("Geburtstag von Oma", "Grandma's birthday"), today.plusDays(9).atStartOfDay(), null, true), "x")
        Store.save(app, Parsed(Kind.NOTE, t("Geschenkidee: Fotobuch vom Urlaub", "Gift idea: photo book of the holiday"), null, null, false), "x")
        val done = Store.save(app, Parsed(Kind.REMINDER, t("Rezept in der Apotheke abholen", "Pick up the prescription"), LocalDateTime.now().plusHours(3).withMinute(30), null, false), "x")
        Db(app).setDone(done.id, true)
    }

    private fun main(nightMode: Boolean, withEntries: Boolean, name: String) {
        night(nightMode)
        if (withEntries) sampleEntries()
        shotActivity(Robolectric.buildActivity(MainActivity::class.java).setup().get(), name)
    }

    @Test fun mainLight() = main(false, true, "1_main_light")
    @Test fun mainDark() = main(true, true, "2_main_dark")
    @Test fun mainEmpty() = main(false, false, "3_main_empty")

    @Test fun mainOled() {
        Prefs.setOled(app, true)
        main(true, true, "2b_main_oled")
    }

    private fun listening(nightMode: Boolean, name: String) {
        night(nightMode)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        shadowOf(app.packageManager).addServiceIfNotPresent(service)
        shadowOf(app.packageManager).addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        recognizer.triggerOnPartialResults(
            Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(t("Morgen um 10 Zahnarzt bei Neupert", "Tomorrow at 10 dentist, Dr. Miller")),
                )
            },
        )
        recognizer.triggerOnRmsChanged(7f)
        shotActivity(activity, name)
    }

    /** After a pause: it keeps listening, with the words so far. */
    @Test fun listeningOn() {
        night(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        shadowOf(app.packageManager).addServiceIfNotPresent(service)
        shadowOf(app.packageManager).addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(
            Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(t("Morgen um 10", "Tomorrow at 10"))) },
        )
        shotActivity(activity, "5b_listening_on_dark")
    }

    @Test fun listeningLight() = listening(false, "4_listening_light")
    @Test fun listeningDark() = listening(true, "5_listening_dark")

    private fun settings(nightMode: Boolean, name: String) {
        night(nightMode)
        Prefs.setApiKey(app, "sk-ant-api03-••••••••")
        Prefs.setAiStatus(app, true, null)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle(400)
        // The whole page, not only what fits on the screen.
        val toolbar = activity.findViewById<View>(R.id.toolbar)
        val content = activity.findViewById<View>(R.id.settings_root)
        val bmp = Bitmap.createBitmap(content.width, toolbar.height + content.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val typed = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        canvas.drawColor(typed.getColor(0, Color.WHITE))
        typed.recycle()
        toolbar.draw(canvas)
        canvas.translate(0f, toolbar.height.toFloat())
        content.draw(canvas)
        save(bmp, name)
    }

    @Test fun settingsLight() = settings(false, "6_settings_light")
    @Test fun settingsDark() = settings(true, "7_settings_dark")

    @Test fun widgets() {
        val density = app.resources.displayMetrics.density
        for ((layout, size, name) in listOf(
            Triple(R.layout.widget_speak, 150 to 150, "8_widget_2x2"),
            Triple(R.layout.widget_speak_wide, 150 to 64, "8_widget_2x1"),
            Triple(R.layout.widget_speak_small, 64 to 64, "8_widget_1x1"),
        )) {
            val v = LayoutInflater.from(app).inflate(layout, null)
            val w = (size.first * density).toInt()
            val h = (size.second * density).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            shot(v, name)
        }
    }

    /** The "Up next" widget in its three shapes, drawn from the real RemoteViews. */
    @Test fun nextWidget() {
        sampleEntries()
        val density = app.resources.displayMetrics.density
        val next = Glance.nextUp(app)
        val now = System.currentTimeMillis()
        for ((size, name) in listOf(
            (250 to 70) to "9_next_line",
            (250 to 110) to "9_next_block",
            (250 to 170) to "9_next_list",
            (345 to 160) to "9_next_list_wide",
            // Two cells wide, as it sits next to the "Speak" button.
            (161 to 60) to "9_next_2x1_low",
            (161 to 81) to "9_next_2x1",
            (161 to 110) to "9_next_2x1_tall",
            (161 to 180) to "9_next_2x2",
        )) {
            val look = NextWidget.lookFor(size.first.toFloat(), size.second.toFloat(), app.resources.configuration.fontScale)
            val v = NextWidget.views(app, next, now, look).apply(app, null)
            val w = (size.first * density).toInt()
            val h = (size.second * density).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            shot(v, name)
        }
    }

    /** Confirmation after a change by voice, full-screen mode. */
    @Test fun changedConfirmation() {
        night(true)
        Prefs.setCompact(app, false)
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val note = Store.save(app, Parsed(Kind.NOTE, t("Zahnarzt bei Neupert", "Dentist, Dr. Miller"), null, null, false), "x")
        val moved = Store.reschedule(app, note, Parsed(Kind.APPOINTMENT, "", LocalDate.now().plusDays(1).atTime(10, 0), null, false), "x")
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        confirmOn(activity, moved, Scheduler.Confirm.CHANGED)
        shotActivity(activity, "10_changed_dark")
    }

    private fun confirmOn(activity: RecordActivity, e: Entry, how: Scheduler.Confirm, added: String? = null) {
        val confirm = RecordActivity::class.java.getDeclaredMethod("confirm", Entry::class.java, Scheduler.Confirm::class.java, String::class.java)
        confirm.isAccessible = true
        confirm.invoke(activity, e, how, added)
    }

    /** The small card after saving, when no notification can be shown: undo, change, add a note. */
    @Test fun compactConfirmations() {
        night(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, t("Zahnarzt bei Neupert", "Dentist, Dr. Miller"), LocalDate.now().plusDays(1).atTime(10, 0), null, false), "x")
        val activity = Robolectric.buildActivity(RecordActivity::class.java).setup().get()
        confirmOn(activity, e, Scheduler.Confirm.NEW)
        shotActivity(activity, "13_saved_compact_dark")
        val noted = Store.addNote(app, e, t("Versichertenkarte mitnehmen", "Bring the insurance card"))
        confirmOn(activity, noted, Scheduler.Confirm.NOTED, t("Versichertenkarte mitnehmen", "Bring the insurance card"))
        shotActivity(activity, "13b_noted_compact_dark")
    }

    /** The entry dialog with its "change date or time" button. */
    private fun editScreen(nightMode: Boolean, name: String) {
        night(nightMode)
        val start = LocalDate.now().plusDays(1).atTime(10, 0)
        val text = t("Zahnarzt bei Neupert", "Dentist, Dr. Miller")
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, text, start, null, false), text)
        val activity = Robolectric.buildActivity(EditActivity::class.java, EditActivity.intent(app, e.id)).setup().get()
        shotActivity(activity, name)
    }

    @Test fun editLight() = editScreen(false, "12_edit_light")
    @Test fun editDark() = editScreen(true, "12_edit_dark")

    @Test fun entryDialog() {
        night(true)
        val text = t("Zahnarzt bei Neupert", "Dentist, Dr. Miller")
        val e = Store.save(app, Parsed(Kind.APPOINTMENT, text, LocalDate.now().plusDays(1).atTime(10, 0), null, false), t("Morgen um 10 Zahnarzt bei Neupert", "Tomorrow at 10 dentist, Dr. Miller"))
        Store.addNote(app, e, t("Versichertenkarte mitnehmen", "Bring the insurance card"))
        Store.save(app, Parsed(Kind.NOTE, t("Brot und Milch besorgen", "Get bread and milk"), null, null, false), "x")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val list = main.findViewById<android.widget.ListView>(R.id.list)
        for ((position, name) in listOf(0 to "11_entry_dialog_dark", 1 to "11b_note_dialog_dark")) {
            list.adapter.getView(position, null, list).findViewById<View>(R.id.card).performClick()
            idle(400)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            shot(dialog.window!!.decorView, name, android.graphics.Color.parseColor("#101418"))
            dialog.dismiss()
        }
    }

    @Test fun icon() {
        val icon = app.getDrawable(app.applicationInfo.icon) as AdaptiveIconDrawable
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bmp))
        save(bmp, "0_icon")
    }
}
