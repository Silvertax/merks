package de.silvertax.merks

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.core.content.IntentCompat
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
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
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
 *
 * The same for what comes from elsewhere: a picture, a PDF, a calendar file or a text passed on from
 * another app ("Share" → Merk's), or picked with "From a picture or file". Copies are read and
 * deleted; the original stays as it is.
 */
class PhotoActivity : MerksActivity() {
    // The screen is rebuilt only by Android itself; the model carries the photo being read over that.
    override val followsOledChanges = false

    private lateinit var model: PhotoModel
    private val handler = Handler(Looper.getMainLooper())
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken -> photoTaken(taken) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picked(uri) }

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

        retake.setOnClickListener { again() }
        findViewById<View>(R.id.review_retake).setOnClickListener { again() }
        byHand.setOnClickListener {
            model.editing = -1
            pickDay(LocalDate.now())
        }
        done.setOnClickListener { finish() }
        showText.setOnClickListener { showRead() }
        findViewById<View>(R.id.review_show_text).setOnClickListener { showRead() }
        undo.setOnClickListener { undoAll() }
        findViewById<Button>(R.id.save).apply {
            // With Merk's as the calendar, nothing goes into the phone's calendar.
            if (Prefs.ownCalendar(this@PhotoActivity)) setText(R.string.photo_save_own)
            setOnClickListener { saveAll() }
        }

        // After a rebuild an open picker has lost who listens to it.
        (supportFragmentManager.findFragmentByTag(DAY) as? MaterialDatePicker<*>)?.let { listenTo(it) }
        (supportFragmentManager.findFragmentByTag(TIME) as? MaterialTimePicker)?.let { listenTo(it) }

        model.onDone = { if (!isFinishing && !isDestroyed) show() }
        when (model.stage) {
            PhotoModel.Stage.START -> begin()
            // The app was ended while reading or saving: read again if the photo is still there.
            PhotoModel.Stage.READING -> if (!model.busy) {
                when {
                    model.source != PhotoModel.Source.CAMERA -> model.readShared(this, model.uris, model.shared)
                    shot.length() > 0 -> model.read(this, shot)
                    else -> openCamera()
                }
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

    /** What this screen was opened for: something passed on from another app, picking a file, or the camera. */
    private fun begin() {
        val (uris, text) = passedOn(intent)
        val shared = intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)
        when {
            uris.isNotEmpty() || text != null -> {
                model.source = if (uris.isEmpty()) PhotoModel.Source.TEXT else PhotoModel.Source.FILE
                model.uris = uris
                model.shared = text
                model.readShared(this, uris, text)
            }
            shared -> {
                model.source = PhotoModel.Source.FILE
                model.fail(R.string.photo_file_unreadable)
            }
            intent.getBooleanExtra(EXTRA_PICK, false) -> {
                model.source = PhotoModel.Source.FILE
                openPicker()
            }
            else -> openCamera()
        }
    }

    /** The files and the text another app passed on, as "Share" and "Open with" do. */
    private fun passedOn(intent: Intent): Pair<List<Uri>, String?> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
            else -> return emptyList<Uri>() to null
        }
        // Some apps put what they pass on only here.
        if (uris.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
        // A mail's subject often says what it is about; the text then says when.
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").ifEmpty { null }
            ?.takeIf { intent.action == Intent.ACTION_SEND }
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct() to text
    }

    /** "Try again": a new photo, or another file. */
    private fun again() = if (model.source == PhotoModel.Source.CAMERA) openCamera() else openPicker()

    private fun openPicker() {
        model.stage = PhotoModel.Stage.PICKING
        try {
            picker.launch(arrayOf("image/*", "application/pdf", "text/calendar", "text/plain"))
        } catch (e: ActivityNotFoundException) {
            model.fail(R.string.photo_no_picker)
        }
        show()
    }

    private fun picked(uri: Uri?) {
        if (uri == null) {
            // Nothing picked: back to what was there before, or out.
            if (model.find == null) {
                finish()
                return
            }
            model.stage = if (model.dates.isEmpty()) PhotoModel.Stage.NOTHING else PhotoModel.Stage.REVIEW
        } else {
            model.source = PhotoModel.Source.FILE
            model.uris = listOf(uri)
            model.shared = null
            model.readShared(this, listOf(uri), null)
        }
        show()
    }

    private fun openCamera() {
        val dir = File(cacheDir, PHOTO_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        model.source = PhotoModel.Source.CAMERA
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
        val camera = model.source == PhotoModel.Source.CAMERA
        icon.setImageResource(if (stage == PhotoModel.Stage.SAVED) R.drawable.ic_check else if (camera) R.drawable.ic_camera else R.drawable.ic_attach)
        findViewById<MaterialToolbar>(R.id.toolbar).setTitle(
            when (model.source) {
                PhotoModel.Source.CAMERA -> R.string.photo_title
                PhotoModel.Source.FILE -> R.string.photo_title_file
                PhotoModel.Source.TEXT -> R.string.photo_title_text
            },
        )
        retake.setText(if (camera) R.string.photo_retake else R.string.photo_pick_other)
        findViewById<Button>(R.id.review_retake).apply {
            setText(if (camera) R.string.photo_retake else R.string.photo_pick_other)
            visibility = if (model.source == PhotoModel.Source.TEXT) View.GONE else View.VISIBLE
        }
        handler.removeCallbacks(close)
        when (stage) {
            PhotoModel.Stage.START, PhotoModel.Stage.CAMERA, PhotoModel.Stage.PICKING -> {
                status.text = ""
                detail.text = ""
                only()
            }
            PhotoModel.Stage.READING -> {
                status.setText(
                    when (model.source) {
                        PhotoModel.Source.CAMERA -> R.string.photo_reading
                        PhotoModel.Source.FILE -> R.string.photo_reading_file
                        PhotoModel.Source.TEXT -> R.string.photo_reading_text
                    },
                )
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
                detail.text = when (model.source) {
                    PhotoModel.Source.CAMERA -> listOfNotNull(
                        getString(R.string.photo_tip),
                        getString(R.string.photo_tip_handwriting).takeIf { Prefs.apiKey(this).isEmpty() },
                    ).joinToString("\n\n")
                    PhotoModel.Source.FILE -> getString(R.string.photo_tip_file)
                    PhotoModel.Source.TEXT -> ""
                }
                val tryAgain = if (model.source == PhotoModel.Source.TEXT) null else retake
                if (find != null && find.text.isNotBlank()) only(*listOfNotNull(tryAgain, byHand, showText).toTypedArray()) else only(*listOfNotNull(tryAgain, byHand).toTypedArray())
            }
            PhotoModel.Stage.SAVED -> {
                val entries = model.saved.mapNotNull { Db(this).get(it) }
                status.text = when {
                    entries.size == 1 -> getString(Format.savedLabel(this, entries[0]))
                    else -> resources.getQuantityString(R.plurals.photo_saved, entries.size, entries.size)
                }
                detail.text = (listOfNotNull(entries.firstOrNull()?.title) + entries.map { Format.whenText(this, it) }).joinToString("\n")
                only(done, undo)
                handler.postDelayed(close, CLOSE_AFTER_MS)
            }
            PhotoModel.Stage.FAILED -> {
                status.setText(model.failure.takeIf { it != 0 } ?: R.string.photo_unreadable)
                detail.text = ""
                when {
                    model.failure == R.string.photo_no_camera || model.failure == R.string.photo_no_picker -> only(done)
                    model.source == PhotoModel.Source.TEXT -> only(done)
                    else -> only(retake, done)
                }
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
        // A plan whose own appointments are marked: say why the others are not ticked.
        val marked = f.dates.size > 1 && f.dates.all { it.title != null } && f.dates.any { it.chosen } && f.dates.any { !it.chosen }
        label.text = resources.getQuantityString(if (marked) R.plurals.photo_found_marked else R.plurals.photo_found, model.dates.size, model.dates.size)
        // When every date has its own title, a common one is not needed.
        titleBox.visibility = if (model.dates.isNotEmpty() && model.dates.all { it.title != null }) View.GONE else View.VISIBLE
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
        // From a plan or a calendar file a date can bring its own title and room, and be a series.
        v.findViewById<TextView>(R.id.date_time).text = listOfNotNull(Format.photoTime(this, d), d.repeat?.let { Format.repeatText(this, it) }).joinToString(" · ")
        v.findViewById<TextView>(R.id.date_title).apply {
            text = d.title.orEmpty()
            visibility = if (d.title == null) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.date_detail).apply {
            text = d.detail.orEmpty()
            visibility = if (d.detail == null) View.GONE else View.VISIBLE
        }
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
        if (title.isEmpty() && model.dates.any { it.chosen && it.title == null }) {
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
            .setTitle(if (model.source == PhotoModel.Source.CAMERA) R.string.photo_text_title else R.string.photo_text_title_file)
            .setMessage(model.find?.text.orEmpty().trim())
            .setPositiveButton(R.string.close, null)
            .show()
    }

    companion object {
        const val PHOTO_DIR = "photo"

        /** Where copies of what came from elsewhere are read; emptied before and after. */
        const val IMPORT_DIR = "import"

        /** Opens the screen for picking a picture or a file instead of the camera. */
        const val EXTRA_PICK = "pick"
        private const val DAY = "photo_day"
        private const val TIME = "photo_time"
        private const val CLOSE_AFTER_MS = 10_000L
    }
}

/** What the photo screen is doing. Outlives turning the phone, and is saved for a restart. */
class PhotoModel : ViewModel() {
    enum class Stage { START, CAMERA, PICKING, READING, REVIEW, NOTHING, SAVING, SAVED, FAILED }

    /** Where it came from: the camera, a file, or a text another app passed on. */
    enum class Source { CAMERA, FILE, TEXT }

    var stage = Stage.START
    var source = Source.CAMERA

    /** What came from elsewhere, kept for reading it once more after Android ended the app. */
    var uris: List<Uri> = emptyList()
    var shared: String? = null
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
    fun read(ctx: Context, file: File) = work(R.string.photo_unreadable, { file.delete() }) { PhotoScan.read(ctx.applicationContext, file) }

    /**
     * Reads what another app passed on or what was picked: [uris] are copied into the cache first,
     * since the right to read them ends with this screen, and the copies are deleted afterwards.
     * Only what other apps hand over is read: not the app's own files, not plain paths.
     */
    fun readShared(ctx: Context, uris: List<Uri>, text: String?) {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, PhotoActivity.IMPORT_DIR)
        work(R.string.photo_file_unreadable, { dir.deleteRecursively() }) {
            dir.deleteRecursively()
            dir.mkdirs()
            val files = uris.take(MAX_FILES).mapIndexedNotNull { i, uri -> copy(app, uri, File(dir, "in$i")) }
            if (files.isEmpty() && text.isNullOrBlank()) throw PhotoScan.Unreadable(R.string.photo_file_unreadable)
            PhotoScan.readAll(app, files, text?.take(PhotoScan.MAX_TEXT))
        }
    }

    private fun copy(ctx: Context, uri: Uri, to: File): File? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority == "${ctx.packageName}.files") return null
        return try {
            val complete = ctx.contentResolver.openInputStream(uri)?.use { input ->
                to.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        // A film or a whole archive is no letter: stop rather than fill the phone.
                        if (total > MAX_FILE_BYTES) return@use false
                        out.write(buffer, 0, n)
                    }
                    true
                }
            } ?: false
            to.takeIf { complete && it.length() > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** Runs [reading] in the background, then [cleanUp] whatever happens, and shows what was found. */
    private fun work(unreadable: Int, cleanUp: () -> Unit, reading: () -> PhotoFind) {
        stage = Stage.READING
        busy = true
        Thread {
            var found: PhotoFind? = null
            var why = unreadable
            try {
                found = reading()
            } catch (e: PhotoScan.Unreadable) {
                why = e.why
            } catch (e: Throwable) {
                why = unreadable
            } finally {
                runCatching(cleanUp)
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
        val mark = if (source == Source.CAMERA) Store.PHOTO_MARK else Store.FILE_MARK
        stage = Stage.SAVING
        busy = true
        Thread {
            val ids = try {
                picked.map { d ->
                    // The room or remark of this date first, then what goes for all of them.
                    val own = listOfNotNull(d.detail, note.trim().ifEmpty { null }).joinToString("\n")
                    Store.save(app, d.toParsed(title), mark + d.source.ifEmpty { d.title ?: title }, own).id
                }
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
            .put("source", source.name)
            .put("uris", JSONArray(uris.map { it.toString() }))
            .put("shared", shared ?: JSONObject.NULL)
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
        source = runCatching { Source.valueOf(o.getString("source")) }.getOrDefault(Source.CAMERA)
        uris = o.optJSONArray("uris")?.let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } }.orEmpty()
        shared = if (o.isNull("shared")) null else o.optString("shared")
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
        .put("title", d.title ?: JSONObject.NULL)
        .put("detail", d.detail ?: JSONObject.NULL)
        .put("repeat", d.repeat?.let { "${it.unit.name}:${it.every}" } ?: JSONObject.NULL)
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
        if (o.isNull("title")) null else o.optString("title").ifEmpty { null },
        if (o.isNull("repeat")) null else runCatching { o.optString("repeat").split(':').let { (unit, every) -> Repeat(RepeatUnit.valueOf(unit), every.toInt()) } }.getOrNull(),
        if (o.isNull("detail")) null else o.optString("detail").ifEmpty { null },
    )

    private companion object {
        const val STATE = "photo_state"

        /** A letter, a few photos or the pages of a booking: more at once is something else. */
        const val MAX_FILES = 5
        const val MAX_FILE_BYTES = 30L * 1024 * 1024
    }
}
