package de.silvertax.merks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import de.silvertax.merks.parse.Kind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Changes an entry by typing instead of speaking: its text, its day and its time. For a word the
 * speech recognition got wrong, or when speaking is not possible right now.
 */
class EditActivity : MerksActivity() {
    private lateinit var entry: Entry
    private lateinit var text: EditText

    /** Day and time as they stand on the screen; no day for a note, no time for a whole day. */
    internal var day: LocalDate? = null
        private set
    internal var time: LocalTime? = null
        private set

    /** Only a day or time that was touched is written back, so a series keeps its first date otherwise. */
    private var whenChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = Db(this).get(intent.getLongExtra(ID, -1))
        if (found == null) {
            finish()
            return
        }
        entry = found
        setContentView(R.layout.activity_edit)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        text = findViewById(R.id.edit_text)

        if (savedInstanceState == null) {
            text.setText(entry.title)
            // For a series, the date that counts is its next one.
            val zone = ZoneId.systemDefault()
            val shown = Plan.currentStart(entry, System.currentTimeMillis(), zone)?.let { Instant.ofEpochMilli(it).atZone(zone) }
            day = shown?.toLocalDate()
            time = shown?.toLocalTime()?.takeIf { !entry.allDay }
        } else {
            day = savedInstanceState.getLong(DAY, NONE).takeIf { it != NONE }?.let { LocalDate.ofEpochDay(it) }
            time = savedInstanceState.getInt(TIME, -1).takeIf { it >= 0 }?.let { LocalTime.ofSecondOfDay(it.toLong()) }
            whenChanged = savedInstanceState.getBoolean(CHANGED)
        }

        findViewById<View>(R.id.row_date).setOnClickListener { pickDay() }
        findViewById<View>(R.id.row_time).setOnClickListener { pickTime() }
        findViewById<View>(R.id.remove_time).setOnClickListener { setTime(null) }
        findViewById<View>(R.id.save).setOnClickListener { save() }
        // After the screen was rebuilt (turned, dark mode), an open picker has lost who listens to it.
        (supportFragmentManager.findFragmentByTag(DAY) as? MaterialDatePicker<*>)?.let { listenTo(it) }
        (supportFragmentManager.findFragmentByTag(TIME) as? MaterialTimePicker)?.let { listenTo(it) }
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(DAY, day?.toEpochDay() ?: NONE)
        outState.putInt(TIME, time?.toSecondOfDay() ?: -1)
        outState.putBoolean(CHANGED, whenChanged)
    }

    /** A reminder needs a time to ring at; an appointment may be for the whole day. */
    private val needsTime get() = entry.kind == Kind.REMINDER

    private fun show() {
        findViewById<TextView>(R.id.date_value).text = day?.let { Format.fullDate(this, it) } ?: getString(R.string.edit_no_date)
        findViewById<TextView>(R.id.time_value).text = time?.let { Format.clockTime(this, it) } ?: getString(R.string.edit_no_time)
        // Without a day there is nothing a time could belong to.
        findViewById<View>(R.id.row_time).apply {
            isEnabled = day != null
            alpha = if (day != null) 1f else 0.5f
        }
        findViewById<View>(R.id.remove_time).visibility = if (time != null && !needsTime) View.VISIBLE else View.GONE
        val note = when {
            entry.repeat != null -> getString(R.string.edit_repeats, Format.repeatText(this, entry.repeat!!))
            entry.kind == Kind.NOTE -> getString(R.string.edit_note_hint)
            else -> null
        }
        findViewById<TextView>(R.id.edit_note).apply {
            this.text = note
            visibility = if (note == null) View.GONE else View.VISIBLE
        }
    }

    internal fun setDay(picked: LocalDate) {
        day = picked
        if (time == null && needsTime) time = LocalTime.of(9, 0)
        whenChanged = true
        show()
    }

    internal fun setTime(picked: LocalTime?) {
        if (day == null || (picked == null && needsTime)) return
        time = picked
        whenChanged = true
        show()
    }

    private fun pickDay() {
        if (supportFragmentManager.findFragmentByTag(DAY) != null) return
        val shown = (day ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker().setSelection(shown).build()
        listenTo(picker)
        picker.show(supportFragmentManager, DAY)
    }

    private fun listenTo(picker: MaterialDatePicker<*>) {
        picker.addOnPositiveButtonClickListener { picked ->
            // The picker counts in days of UTC.
            (picked as? Long)?.let { setDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
        }
    }

    private fun pickTime() {
        if (day == null || supportFragmentManager.findFragmentByTag(TIME) != null) return
        val shown = time ?: LocalTime.of(9, 0)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(shown.hour)
            .setMinute(shown.minute)
            .build()
        listenTo(picker)
        picker.show(supportFragmentManager, TIME)
    }

    private fun listenTo(picker: MaterialTimePicker) {
        picker.addOnPositiveButtonClickListener { setTime(LocalTime.of(picker.hour, picker.minute)) }
    }

    private fun save() {
        val title = text.text.toString().trim()
        val box = findViewById<TextInputLayout>(R.id.edit_text_box)
        if (title.isEmpty()) {
            box.error = getString(R.string.edit_empty)
            return
        }
        box.error = null
        // Asked again: the entry may have been ticked off or moved by a notification meanwhile.
        val current = Db(this).get(entry.id)
        if (current != null) {
            Store.edit(this, current, title, day.takeIf { whenChanged }, time)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val ID = "id"
        private const val DAY = "day"
        private const val TIME = "time"
        private const val CHANGED = "changed"
        private const val NONE = Long.MIN_VALUE

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, EditActivity::class.java).putExtra(ID, id)
    }
}
