package de.silvertax.merks

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import de.silvertax.merks.parse.PhotoDate
import de.silvertax.merks.parse.PhotoFind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * "Termin fotografieren": opens the camera, reads the photo and shows what it found, to check and
 * change before it goes into the calendar. The photo is deleted as soon as it has been read; only the
 * words a date was read from stay with the entry.
 */
class PhotoActivity : MerksActivity() {
    // The screen is rebuilt only by Android itself; the model carries the photo being read over that.
    override val followsOledChanges = false

    private lateinit var model: PhotoModel
    private val handler = Handler(Looper.getMainLooper())
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken -> photoTaken(taken) }

    private lateinit var message: View
    private lateinit var icon: ImageView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: View
    private lateinit var retake: Button
    private lateinit var byHand: Button
    private lateinit var done: Button
    private lateinit var showText: Button
    private lateinit var undo: Button

    private lateinit var review: View
    private lateinit var label: TextView
    private lateinit var titleBox: TextInputLayout
    private lateinit var titleText: EditText
    private lateinit var noteText: EditText
    private lateinit var rows: LinearLayout
    private lateinit var sourceHint: TextView

    /** Where the camera puts the photo: a folder of its own in the cache, emptied before each photo. */
    private val shot get() = File(File(cacheDir, PHOTO_DIR), "shot.jpg")

    private val close = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[PhotoModel::class.java]
        // Android ended the app while the camera was open: the model is new, the saved state is not.
        if (model.stage == PhotoModel.Stage.START && savedInstanceState != null) model.restore(savedInstanceState)
        setContentView(R.layout.activity_photo)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        message = findViewById(R.id.message)
        icon = findViewById(R.id.message_icon)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        progress = findViewById(R.id.progress)
        retake = findViewById(R.id.retake)
        byHand = findViewById(R.id.by_hand)
        done = findViewById(R.id.done)
        showText = findViewById(R.id.show_text)
        undo = findViewById(R.id.undo)
        review = findViewById(R.id.review)
        label = findViewById(R.id.review_label)
        titleBox = findViewById(R.id.photo_title_box)
        titleText = findViewById(R.id.photo_title)
        noteText = findViewById(R.id.photo_note)
        rows = findViewById(R.id.dates)
        sourceHint = findViewById(R.id.source_hint)

        retake.setOnClickListener { openCamera() }
        findViewById<View>(R.id.review_retake).setOnClickListener { openCamera() }
        byHand.setOnClickListener {
            model.editing = -1
            pickDay(LocalDate.now())
        }
        done.setOnClickListener { finish() }
        showText.setOnClickListener { showRead() }
        findViewById<View>(R.id.review_show_text).setOnClickListener { showRead() }
        undo.setOnClickListener { undoAll() }
        findViewById<View>(R.id.save).setOnClickListener { saveAll() }

        // After a rebuild an open picker has lost who listens to it.
        (supportFragmentManager.findFragmentByTag(DAY) as? MaterialDatePicker<*>)?.let { listenTo(it) }
        (supportFragmentManager.findFragmentByTag(TIME) as? MaterialTimePicker)?.let { listenTo(it) }

        model.onDone = { if (!isFinishing && !isDestroyed) show() }
        when (model.stage) {
            PhotoModel.Stage.START -> openCamera()
            // The app was ended while reading or saving: read again if the photo is still there.
            PhotoModel.Stage.READING -> if (!model.busy) {
                if (shot.length() > 0) model.read(this, shot) else openCamera()
            }
            PhotoModel.Stage.SAVING -> if (!model.busy) model.stage = PhotoModel.Stage.REVIEW
            else -> {}
        }
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        model.save(outState)
    }

    override fun onDestroy() {
        model.onDone = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openCamera() {
        val dir = File(cacheDir, PHOTO_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        model.stage = PhotoModel.Stage.CAMERA
        try {
            camera.launch(FileProvider.getUriForFile(this, "$packageName.files", shot))
        } catch (e: ActivityNotFoundException) {
            model.fail(R.string.photo_no_camera)
        } catch (e: SecurityException) {
            model.fail(R.string.photo_no_camera)
        }
        show()
    }

    private fun photoTaken(taken: Boolean) {
        when {
            taken && shot.length() > 0 -> model.read(this, shot)
            taken -> model.fail(R.string.photo_unreadable)
            // Camera closed without a photo: back to what was there before, or out.
            model.find == null -> {
                finish()
                return
            }
            else -> model.stage = if (model.dates.isEmpty()) PhotoModel.Stage.NOTHING else PhotoModel.Stage.REVIEW
        }
        show()
    }

    private fun only(vararg visible: View) {
        for (b in listOf(retake, byHand, done, showText, undo)) b.visibility = if (b in visible) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetTextI18n") // only the title and the dates of what was saved, one per line
    private fun show() {
        val stage = model.stage
        val find = model.find
        review.visibility = if (stage == PhotoModel.Stage.REVIEW) View.VISIBLE else View.GONE
        message.visibility = if (stage == PhotoModel.Stage.REVIEW) View.GONE else View.VISIBLE
        progress.visibility = if (stage == PhotoModel.Stage.READING || stage == PhotoModel.Stage.SAVING) View.VISIBLE else View.GONE
        icon.setImageResource(if (stage == PhotoModel.Stage.SAVED) R.drawable.ic_check else R.drawable.ic_camera)
        handler.removeCallbacks(close)
        when (stage) {
            PhotoModel.Stage.START, PhotoModel.Stage.CAMERA -> {
                status.text = ""
                detail.text = ""
                only()
            }
            PhotoModel.Stage.READING -> {
                status.setText(R.string.photo_reading)
                detail.text = if (Prefs.apiKey(this).isNotEmpty()) getString(R.string.photo_reading_ai) else ""
                only()
            }
            PhotoModel.Stage.SAVING -> {
                status.setText(R.string.working)
                detail.text = ""
                only()
            }
            PhotoModel.Stage.NOTHING -> {
                val noText = find == null || (!find.byAi && find.text.isBlank())
                status.setText(if (noText) R.string.photo_no_text else R.string.photo_no_date)
                detail.text = listOfNotNull(
                    getString(R.string.photo_tip),
                    getString(R.string.photo_tip_handwriting).takeIf { Prefs.apiKey(this).isEmpty() },
                ).joinToString("\n\n")
                if (find != null && find.text.isNotBlank()) only(retake, byHand, showText) else only(retake, byHand)
            }
            PhotoModel.Stage.SAVED -> {
                val entries = model.saved.mapNotNull { Db(this).get(it) }
                status.text = when {
                    entries.size == 1 -> getString(Format.savedLabel(entries[0]))
                    else -> resources.getQuantityString(R.plurals.photo_saved, entries.size, entries.size)
                }
                detail.text = (listOfNotNull(entries.firstOrNull()?.title) + entries.map { Format.whenText(this, it) }).joinToString("\n")
                only(done, undo)
                handler.postDelayed(close, CLOSE_AFTER_MS)
            }
            PhotoModel.Stage.FAILED -> {
                status.setText(model.failure.takeIf { it != 0 } ?: R.string.photo_unreadable)
                detail.text = ""
                if (model.failure == R.string.photo_no_camera) only(done) else only(retake, done)
            }
            PhotoModel.Stage.REVIEW -> fillReview()
        }
    }

    private fun fillReview() {
        val f = model.find ?: return
        // Only the first time: afterwards the fields hold what was typed, also after turning the phone.
        if (!model.filled) {
            titleText.setText(f.title.ifEmpty { getString(R.string.photo_default_title) })
            noteText.setText(f.note.orEmpty())
            model.filled = true
        }
        label.text = resources.getQuantityString(R.plurals.photo_found, model.dates.size, model.dates.size)
        sourceHint.text = when {
            f.byAi -> getString(R.string.photo_by_ai)
            // The AI was asked for this photo and its answer did not come.
            Prefs.apiKey(this).isNotEmpty() && !Prefs.aiOk(this) -> getString(R.string.photo_ai_failed)
            else -> ""
        }
        sourceHint.visibility = if (sourceHint.text.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.review_show_text).visibility = if (f.text.isBlank()) View.GONE else View.VISIBLE
        rows.removeAllViews()
        for ((i, d) in model.dates.withIndex()) rows.addView(row(i, d))
    }

    private fun row(i: Int, d: PhotoDate): View {
        val v = layoutInflater.inflate(R.layout.item_photo_date, rows, false)
        val several = model.dates.size > 1
        val pick = v.findViewById<MaterialCheckBox>(R.id.date_pick)
        // The rows share their ids; what is ticked lives in the model, not in the saved view state.
        pick.isSaveEnabled = false
        pick.visibility = if (several) View.VISIBLE else View.GONE
        pick.isChecked = d.chosen
        pick.setOnCheckedChangeListener { _, on -> model.dates[i] = model.dates[i].copy(chosen = on) }
        v.findViewById<TextView>(R.id.date_day).text = Format.dayText(this, d.day)
        v.findViewById<TextView>(R.id.date_time).text = Format.photoTime(this, d)
        v.findViewById<View>(R.id.date_row).setOnClickListener { if (several) pick.toggle() else change(i) }
        v.findViewById<View>(R.id.date_change).setOnClickListener { change(i) }
        return v
    }

    /** Day first, then the time; leaving the time picker keeps the time as it was. */
    private fun change(i: Int) {
        model.editing = i
        pickDay(model.dates.getOrNull(i)?.day ?: LocalDate.now())
    }

    private fun pickDay(shown: LocalDate) {
        if (supportFragmentManager.findFragmentByTag(DAY) != null) return
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        listenTo(picker)
        picker.show(supportFragmentManager, DAY)
    }

    private fun listenTo(picker: MaterialDatePicker<*>) {
        picker.addOnPositiveButtonClickListener { picked ->
            // The picker counts in days of UTC.
            (picked as? Long)?.let {
                model.pickedDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                pickTime()
            }
        }
    }

    private fun pickTime() {
        if (supportFragmentManager.findFragmentByTag(TIME) != null) return
        val shown = model.dates.getOrNull(model.editing)?.time ?: LocalTime.of(9, 0)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(shown.hour)
            .setMinute(shown.minute)
            .build()
        listenTo(picker)
        picker.show(supportFragmentManager, TIME)
    }

    private fun listenTo(picker: MaterialTimePicker) {
        picker.addOnPositiveButtonClickListener { setWhen(LocalTime.of(picker.hour, picker.minute)) }
        picker.addOnNegativeButtonClickListener { setWhen(null) }
        picker.addOnCancelListener { setWhen(null) }
    }

    /** What the two pickers end with for [row] (-1: a new date); also the way in for tests. */
    internal fun picked(row: Int, day: LocalDate, time: LocalTime?) {
        model.editing = row
        model.pickedDay = day
        setWhen(time)
    }

    /** The day from the date picker with [time]; null keeps the time a date had, or makes a new one whole-day. */
    private fun setWhen(time: LocalTime?) {
        val day = model.pickedDay ?: return
        model.pickedDay = null
        val old = model.dates.getOrNull(model.editing)
        if (old == null) {
            model.dates += PhotoDate(day, time, chosen = true)
            if (model.find == null) model.find = PhotoFind("", null, emptyList())
            model.stage = PhotoModel.Stage.REVIEW
        } else {
            val t = time ?: old.time
            // Moved to another day, a stay keeps its length; a new start keeps the appointment's length.
            val last = if (t == null) old.lastDay?.plusDays(ChronoUnit.DAYS.between(old.day, day)) else null
            val end = if (t != null && old.time != null && old.end != null) {
                t.plus(Duration.between(old.time, old.end)).takeIf { it.isAfter(t) }
            } else {
                null
            }
            model.dates[model.editing] = old.copy(day = day, time = t, end = end, lastDay = last, chosen = true)
        }
        show()
    }

    private fun saveAll() {
        val title = titleText.text.toString().trim()
        if (title.isEmpty()) {
            titleBox.error = getString(R.string.edit_empty)
            return
        }
        titleBox.error = null
        if (model.dates.none { it.chosen }) {
            Toast.makeText(this, R.string.photo_pick_one, Toast.LENGTH_LONG).show()
            return
        }
        model.store(this, title, noteText.text.toString())
        show()
    }

    private fun undoAll() {
        val db = Db(this)
        model.saved.mapNotNull { db.get(it) }.forEach { Store.remove(this, it) }
        Toast.makeText(this, R.string.undone, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showRead() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_text_title)
            .setMessage(model.find?.text.orEmpty().trim())
            .setPositiveButton(R.string.close, null)
            .show()
    }

    companion object {
        const val PHOTO_DIR = "photo"
        private const val DAY = "photo_day"
        private const val TIME = "photo_time"
        private const val CLOSE_AFTER_MS = 10_000L
    }
}

/** What the photo screen is doing. Outlives turning the phone, and is saved for a restart. */
class PhotoModel : ViewModel() {
    enum class Stage { START, CAMERA, READING, REVIEW, NOTHING, SAVING, SAVED, FAILED }

    var stage = Stage.START
    var find: PhotoFind? = null

    /** The dates as shown, with what was ticked and changed. */
    val dates = mutableListOf<PhotoDate>()

    /** Why it failed, a string resource. */
    var failure = 0
    var saved: List<Long> = emptyList()

    /** The row whose day and time are being changed; -1 for a date added by hand. */
    var editing = -1

    /** The day picked for [editing], waiting for its time. */
    var pickedDay: LocalDate? = null

    /** Title and note were put into the fields once. */
    var filled = false

    /** Reading or saving runs in the background. */
    @Volatile
    var busy = false
        private set

    /** Told when background work is over; null while the screen is being rebuilt. */
    var onDone: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun fail(why: Int) {
        failure = why
        stage = Stage.FAILED
    }

    /** Reads the photo in [file] in the background and deletes it afterwards, whatever happens. */
    fun read(ctx: Context, file: File) {
        val app = ctx.applicationContext
        stage = Stage.READING
        busy = true
        Thread {
            var found: PhotoFind? = null
            var why = R.string.photo_unreadable
            try {
                found = PhotoScan.read(app, file)
            } catch (e: PhotoScan.Unreadable) {
                why = e.why
            } catch (e: Throwable) {
                why = R.string.photo_unreadable
            } finally {
                file.delete()
            }
            main.post {
                busy = false
                val f = found
                if (f == null) {
                    fail(why)
                } else {
                    find = f
                    dates.clear()
                    dates += f.dates
                    filled = false
                    stage = if (f.dates.isEmpty()) Stage.NOTHING else Stage.REVIEW
                }
                onDone?.invoke()
            }
        }.start()
    }

    /** Saves the ticked dates under [title], each as an appointment with [note]. */
    fun store(ctx: Context, title: String, note: String) {
        val app = ctx.applicationContext
        val picked = dates.filter { it.chosen }
        stage = Stage.SAVING
        busy = true
        Thread {
            val ids = try {
                picked.map { d -> Store.save(app, d.toParsed(title), Store.PHOTO_MARK + d.source.ifEmpty { title }, note).id }
            } catch (e: Exception) {
                null
            }
            main.post {
                busy = false
                if (ids == null) {
                    fail(R.string.err_save)
                } else {
                    saved = ids
                    stage = Stage.SAVED
                }
                onDone?.invoke()
            }
        }.start()
    }

    override fun onCleared() {
        onDone = null
    }

    fun save(out: Bundle) {
        val f = find
        val o = JSONObject()
            .put("stage", stage.name)
            .put("failure", failure)
            .put("editing", editing)
            .put("filled", filled)
            .put("picked", pickedDay?.toEpochDay() ?: JSONObject.NULL)
            .put("saved", JSONArray(saved))
            .put("dates", JSONArray(dates.map { dateJson(it) }))
        if (f != null) {
            o.put("title", f.title).put("note", f.note ?: JSONObject.NULL).put("text", f.text).put("ai", f.byAi)
        }
        out.putString(STATE, o.toString())
    }

    fun restore(from: Bundle) {
        val o = from.getString(STATE)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        stage = runCatching { Stage.valueOf(o.getString("stage")) }.getOrDefault(Stage.START)
        failure = o.optInt("failure")
        editing = o.optInt("editing", -1)
        pickedDay = if (o.isNull("picked")) null else LocalDate.ofEpochDay(o.getLong("picked"))
        saved = o.optJSONArray("saved")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
        dates.clear()
        o.optJSONArray("dates")?.let { a -> for (i in 0 until a.length()) dates += dateFrom(a.getJSONObject(i)) }
        if (o.has("title")) {
            find = PhotoFind(
                o.getString("title"),
                if (o.isNull("note")) null else o.getString("note"),
                dates.toList(),
                o.optString("text"),
                o.optBoolean("ai"),
            )
        }
        // Once filled, the fields keep what was typed: Android puts it back by itself.
        filled = o.optBoolean("filled")
    }

    private fun dateJson(d: PhotoDate) = JSONObject()
        .put("day", d.day.toEpochDay())
        .put("time", d.time?.toSecondOfDay() ?: -1)
        .put("end", d.end?.toSecondOfDay() ?: -1)
        .put("last", d.lastDay?.toEpochDay() ?: JSONObject.NULL)
        .put("source", d.source)
        .put("chosen", d.chosen)

    private fun dateFrom(o: JSONObject) = PhotoDate(
        LocalDate.ofEpochDay(o.getLong("day")),
        o.optInt("time", -1).takeIf { it >= 0 }?.let { LocalTime.ofSecondOfDay(it.toLong()) },
        o.optInt("end", -1).takeIf { it >= 0 }?.let { LocalTime.ofSecondOfDay(it.toLong()) },
        if (o.isNull("last")) null else LocalDate.ofEpochDay(o.getLong("last")),
        o.optString("source"),
        o.optBoolean("chosen", true),
    )

    private companion object {
        const val STATE = "photo_state"
    }
}
