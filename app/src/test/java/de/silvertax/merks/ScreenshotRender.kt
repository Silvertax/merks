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
import de.silvertax.merks.parse.PhotoDate
import de.silvertax.merks.parse.PhotoFind
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
import java.time.LocalTime

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

    /** [hour]:[minute] today, or tomorrow when that is less than half an hour away: times a person would pick. */
    private fun next(hour: Int, minute: Int = 0): LocalDateTime =
        LocalDate.now().atTime(hour, minute).let { if (it.isAfter(LocalDateTime.now().plusMinutes(30))) it else it.plusDays(1) }

    private fun sampleEntries() {
        val today = LocalDate.now()
        val dentist = Store.save(app, Parsed(Kind.APPOINTMENT, t("Zahnarzt bei Neupert", "Dentist, Dr. Miller"), today.plusDays(1).atTime(10, 0), null, false), "x")
        Store.addNote(app, dentist, t("Versichertenkarte mitnehmen", "Bring the insurance card"))
        Store.save(app, Parsed(Kind.REMINDER, t("Wäsche aufhängen", "Hang up the laundry"), next(18), null, false), "x")
        val series = t("Jeden Montag Mülltonne rausstellen", "Every Monday take out the trash")
        Store.save(app, Store.sort(app, series), series)
        Store.save(app, Parsed(Kind.APPOINTMENT, t("Geburtstag von Oma", "Grandma's birthday"), today.plusDays(9).atStartOfDay(), null, true), "x")
        Store.save(app, Parsed(Kind.NOTE, t("Geschenkidee: Fotobuch vom Urlaub", "Gift idea: photo book of the holiday"), null, null, false), "x")
        val done = Store.save(app, Parsed(Kind.REMINDER, t("Rezept in der Apotheke abholen", "Pick up the prescription"), next(16, 30), null, false), "x")
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
        shotActivity(listeningNow(), name)
    }

    /** Merk's listening, with the first words heard. */
    private fun listeningNow(): RecordActivity {
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
        return activity
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
        for ((widget, size, name) in listOf(
            Triple(SpeakWidget(), 150 to 150, "8_widget_2x2"),
            Triple(SpeakWidget(), 150 to 64, "8_widget_2x1"),
            Triple(SpeakWidget(), 64 to 64, "8_widget_1x1"),
        )) {
            val v = widget.views(app, ButtonWidget.shapeFor(size.first.toFloat(), size.second.toFloat())).apply(app, null)
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

    /** The check before saving, with what a photo of a card with three treatments gave. */
    private fun photoReview(nightMode: Boolean, name: String) {
        night(nightMode)
        val today = LocalDate.now()
        val model = PhotoModel().apply {
            stage = PhotoModel.Stage.REVIEW
            find = PhotoFind(t("Physiotherapie Lößnitz", "Riverside Physiotherapy"), t("Handtuch mitbringen", "Bring a towel"), emptyList(), text = "…")
            dates += listOf(
                PhotoDate(today.plusDays(2), LocalTime.of(8, 15)),
                PhotoDate(today.plusDays(4), LocalTime.of(8, 15)),
                PhotoDate(today.plusDays(9), LocalTime.of(8, 30), chosen = false),
            )
        }
        val state = Bundle().also { model.save(it) }
        shotActivity(Robolectric.buildActivity(PhotoActivity::class.java).setup(state).get(), name)
    }

    @Test fun photoReviewLight() = photoReview(false, "14_photo_review_light")
    @Test fun photoReviewDark() = photoReview(true, "14_photo_review_dark")

    @Test fun photoWidget() {
        val density = app.resources.displayMetrics.density
        val v = LayoutInflater.from(app).inflate(R.layout.widget_photo, null)
        val w = (150 * density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, w)
        shot(v, "8_photo_widget_2x2")
    }

    /**
     * The home screen for the store and the README: "All in one" on top, below it "Speak", "Photo"
     * and "Up next" on their own, and Merk's listening at the bottom after a tap on "Speak".
     */
    private fun homeScreen(name: String) {
        night(true)
        sampleEntries()
        val density = app.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val bmp = Bitmap.createBitmap(px(411), px(891), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // A plain dark wallpaper.
        val wallpaper = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, bmp.height.toFloat(), Color.rgb(16, 24, 32), Color.rgb(43, 61, 71), android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), wallpaper)
        fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
            v.measure(View.MeasureSpec.makeMeasureSpec(px(w), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px(h), View.MeasureSpec.EXACTLY))
            v.layout(0, 0, px(w), px(h))
            canvas.save()
            canvas.translate(px(x).toFloat(), px(y).toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        val next = Glance.nextUp(app)
        val now = System.currentTimeMillis()
        val fs = app.resources.configuration.fontScale
        place(AllInOneWidget.views(app, next, now, AllInOneWidget.lookFor(379f, 216f, fs)).apply(app, null), 16, 112, 379, 216)
        place(SpeakWidget().views(app, ButtonWidget.shapeFor(88f, 106f)).apply(app, null), 16, 344, 88, 106)
        place(PhotoWidget().views(app, ButtonWidget.shapeFor(88f, 106f)).apply(app, null), 113, 344, 88, 106)
        place(NextWidget.views(app, next, now, NextWidget.lookFor(185f, 106f, fs)).apply(app, null), 210, 344, 185, 106)
        val card = listeningNow().also { idle(400) }.window.decorView
        canvas.save()
        canvas.translate(0f, (bmp.height - card.height).toFloat())
        card.draw(canvas)
        canvas.restore()
        save(bmp, name)
    }

    @Test fun home() = homeScreen("15_home")

    /** A widget drawn at a size in dp, as the home screen would, in light or dark mode. */
    private class WidgetShot(val label: String, val w: Int, val h: Int, val make: (android.content.Context) -> android.widget.RemoteViews)

    /**
     * All widgets at the sizes home screens give them, on one sheet, for design reviews: light and
     * dark mode, the phone's font as on a real home screen.
     */
    private fun widgetSheet(dark: Boolean, name: String) {
        sampleEntries()
        val config = android.content.res.Configuration(app.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        val c = app.createConfigurationContext(config)
        val d = c.resources.displayMetrics.density
        val next = Glance.nextUp(app)
        val now = System.currentTimeMillis()
        fun button(w: ButtonWidget, label: String, x: Int, y: Int) = WidgetShot("$label ${x}x$y", x, y) { w.views(it, ButtonWidget.shapeFor(x.toFloat(), y.toFloat())) }
        fun upNext(x: Int, y: Int) = WidgetShot("Up next ${x}x$y", x, y) { NextWidget.views(it, next, now, NextWidget.lookFor(x.toFloat(), y.toFloat())) }
        fun all(x: Int, y: Int) = WidgetShot("All in one ${x}x$y", x, y) { AllInOneWidget.views(it, next, now, AllInOneWidget.lookFor(x.toFloat(), y.toFloat())) }
        val rows = listOf(
            listOf(button(SpeakWidget(), "Speak", 64, 64), button(SpeakWidget(), "Speak", 78, 107), button(PhotoWidget(), "Photo", 78, 107), button(SpeakWidget(), "Speak", 150, 70), button(SpeakWidget(), "Speak", 150, 150), button(PhotoWidget(), "Photo", 150, 150)),
            listOf(upNext(140, 50), upNext(140, 70), upNext(140, 100), upNext(150, 210), upNext(236, 107), upNext(315, 150), upNext(315, 220)),
            listOf(all(150, 80), all(236, 107), all(315, 90), all(315, 107), all(394, 107)),
            listOf(all(160, 220), all(236, 220), all(315, 214), all(394, 214), all(315, 330)),
            listOf(
                WidgetShot("Up next, nothing planned", 236, 107) { NextWidget.views(it, emptyList(), now, NextWidget.lookFor(236f, 107f)) },
                WidgetShot("All in one, nothing planned", 315, 107) { AllInOneWidget.views(it, emptyList(), now, AllInOneWidget.lookFor(315f, 107f)) },
                WidgetShot("Up next in the widget list", 236, 107) { android.widget.RemoteViews(app.packageName, R.layout.widget_next_preview) },
                WidgetShot("All in one in the widget list", 315, 214) { android.widget.RemoteViews(app.packageName, R.layout.widget_all_preview) },
            ),
        )
        val gap = (18 * d).toInt()
        val labelH = (16 * d).toInt()
        val drawn = rows.map { row ->
            row.map { shot ->
                val v = shot.make(c).apply(c, null)
                val w = (shot.w * d).toInt()
                val h = (shot.h * d).toInt()
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                shot.label to bmp
            }
        }
        val width = drawn.maxOf { r -> r.sumOf { it.second.width } + gap * (r.size + 1) }
        val height = drawn.sumOf { r -> r.maxOf { it.second.height } + labelH + gap } + gap
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val wallpaper = android.graphics.Paint().apply {
            shader = if (dark) {
                android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(12, 18, 24), Color.rgb(40, 56, 66), android.graphics.Shader.TileMode.CLAMP)
            } else {
                android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(196, 214, 228), Color.rgb(232, 214, 196), android.graphics.Shader.TileMode.CLAMP)
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wallpaper)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.BLACK; textSize = 11 * d }
        var y = gap
        for (row in drawn) {
            var x = gap
            for ((label, bmp) in row) {
                canvas.drawText(label, x.toFloat(), y + 11 * d, text)
                canvas.drawBitmap(bmp, x.toFloat(), (y + labelH).toFloat(), null)
                x += bmp.width + gap
            }
            y += row.maxOf { it.second.height } + labelH + gap
        }
        save(sheet, name)
    }

    @Test fun widgetsDark() = widgetSheet(true, "17_widgets_dark")
    @Test fun widgetsLight() = widgetSheet(false, "17_widgets_light")

    /** What makes Merk's a calendar of its own: the switch, and when appointments remind. */
    @Test fun calendarSettings() {
        night(true)
        Prefs.setOwnCalendar(app, true)
        Prefs.setLeads(app, setOf(Lead.DAY, Lead.HOUR))
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle(400)
        val card = activity.findViewById<View>(R.id.row_own_calendar).parent as View
        val scroll = activity.findViewById<View>(R.id.settings_root).parent as android.widget.ScrollView
        scroll.scrollTo(0, card.top - (52 * app.resources.displayMetrics.density).toInt())
        shotActivity(activity, "16_calendar_dark")
    }

    @Test fun icon() {
        val icon = app.getDrawable(app.applicationInfo.icon) as AdaptiveIconDrawable
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bmp))
        save(bmp, "0_icon")
    }
}
