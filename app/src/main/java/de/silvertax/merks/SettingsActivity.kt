package de.silvertax.merks

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import de.silvertax.merks.parse.AiParser
import de.silvertax.merks.parse.ModelCheck
import de.silvertax.merks.parse.Parsed
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Settings: recording style, reminders, update check, and the optional AI sorting with its status. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var status: TextView

    /** Key and model of the last check, so leaving a field without changes costs nothing. */
    private var lastChecked: Pair<String, String>? = null

    /** Each check or test gets a number; only the newest one may write the status line. */
    private var seq = 0

    private var downX = 0f
    private var downY = 0f

    /** What is in the model field: as typed, as tidied, and a note when tidying changed it. */
    private data class ModelInput(val shown: String, val name: String, val note: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        root = findViewById(R.id.settings_root)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        switchRow(R.id.row_compact, R.id.compact, Prefs.compact(this)) { on -> Prefs.setCompact(this, on) }
        switchRow(R.id.row_eve, R.id.eve_before, Prefs.eveBefore(this)) { on ->
            Prefs.setEveBefore(this, on)
            // Existing entries follow the switch right away.
            Thread { Scheduler.rescheduleAll(applicationContext) }.start()
        }
        switchRow(R.id.row_updates, R.id.updates, Prefs.updates(this)) { on -> Prefs.setUpdates(this, on) }

        key = findViewById(R.id.api_key)
        model = findViewById(R.id.model)
        status = findViewById(R.id.ai_status)
        key.setText(Prefs.apiKey(this))
        model.setText(Prefs.model(this))
        lastChecked = Prefs.apiKey(this) to Prefs.model(this)
        showLastStatus()

        // Leaving a field (tap elsewhere or "Fertig" on the keyboard) checks key and model right away.
        for (field in listOf(key, model)) {
            field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) checkSetup() }
            field.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    leaveField(field)
                    true
                } else {
                    false
                }
            }
        }

        findViewById<View>(R.id.test).setOnClickListener { test(it) }
        findViewById<View>(R.id.save).setOnClickListener {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** A setting with a switch; the whole row can be tapped, which is easier than hitting the switch. */
    private fun switchRow(row: Int, switch: Int, value: Boolean, changed: (Boolean) -> Unit) {
        val s = findViewById<CompoundButton>(switch)
        s.isChecked = value
        s.setOnCheckedChangeListener { _, on -> changed(on) }
        findViewById<View>(row).setOnClickListener { s.toggle() }
    }

    /** Key and model are kept whenever the screen is left, not only via the button. */
    override fun onPause() {
        Prefs.setApiKey(this, key.text.toString())
        Prefs.setModel(this, model.text.toString())
        super.onPause()
    }

    /** A tap (not a scroll) outside the text fields ends the input, as people expect. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                // Asked of the two fields directly; the window's own focus answer is not always there.
                val fields = listOf(key, model)
                val focused = fields.firstOrNull { it.isFocused }
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                val tap = abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                // A tap into the other field just moves the focus there; that also starts the check.
                if (tap && focused != null && fields.none { hits(box(it), ev) }) leaveField(focused)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The outlined box around a field, including its label, counts as part of the field. */
    private fun box(field: EditText): View =
        generateSequence(field.parent) { it.parent }.filterIsInstance<TextInputLayout>().firstOrNull() ?: field

    private fun hits(v: View, ev: MotionEvent): Boolean {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return ev.rawX >= at[0] && ev.rawX < at[0] + v.width && ev.rawY >= at[1] && ev.rawY < at[1] + v.height
    }

    private fun leaveField(field: EditText) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        if (!root.requestFocus()) field.clearFocus()
    }

    /** Tidies the model field ("Sonnet 5.5" becomes "claude-sonnet-5-5"). */
    private fun takeModelInput(): ModelInput {
        val raw = model.text.toString().trim()
        val name = AiParser.normalizeModel(raw)
        if (model.text.toString() != name) model.setText(name)
        val changed = raw.isNotEmpty() && raw.lowercase(Locale.ROOT) != name
        return ModelInput(
            shown = raw.ifEmpty { name },
            name = name,
            note = if (changed) getString(R.string.ai_model_adjusted, raw, name) else null,
        )
    }

    private fun show(vararg lines: String?) {
        status.text = lines.filterNotNull().joinToString("\n")
    }

    /** An unknown model goes back to the default, so the AI keeps working. */
    private fun resetModel(shown: String) {
        seq++ // whatever was still running is outdated now
        model.setText(AiParser.DEFAULT_MODEL)
        Prefs.setModel(this, "")
        show(
            if (shown == AiParser.DEFAULT_MODEL) getString(R.string.ai_default_gone)
            else getString(R.string.ai_model_reset, shown),
        )
    }

    /** Asks Anthropic whether the key works and the model exists. */
    private fun checkSetup() {
        val typedKey = key.text.toString().trim()
        val input = takeModelInput()
        if (!AiParser.isPlausibleModelName(input.name)) {
            resetModel(input.shown)
            return
        }
        if (typedKey.isEmpty()) {
            show(input.note, getString(R.string.ai_status_off))
            return
        }
        if (lastChecked == typedKey to input.name) {
            if (input.note != null) show(input.note)
            return
        }
        lastChecked = typedKey to input.name
        val token = ++seq
        show(input.note, getString(R.string.ai_checking))
        Thread {
            val result = AiParser.checkModel(typedKey, input.name)
            runOnUiThread {
                if (isDestroyed || token != seq) return@runOnUiThread
                when (result) {
                    ModelCheck.VALID -> {
                        Prefs.setModel(this, input.name)
                        show(input.note, getString(R.string.ai_model_valid, input.name))
                    }
                    ModelCheck.INVALID -> resetModel(input.shown)
                    ModelCheck.KEY_INVALID -> show(input.note, getString(R.string.ai_key_invalid))
                    ModelCheck.UNKNOWN -> {
                        lastChecked = null // try again next time
                        show(input.note, getString(R.string.ai_model_unchecked, input.name))
                    }
                }
            }
        }.start()
    }

    /** Whether the last real AI request worked, so a wrong key or model does not go unnoticed. */
    private fun showLastStatus() {
        val at = Prefs.aiAt(this)
        val note = Prefs.aiError(this)
        when {
            Prefs.apiKey(this).isEmpty() -> show(getString(R.string.ai_status_off))
            at == 0L -> show(getString(R.string.ai_status_unused))
            Prefs.aiOk(this) -> show(getString(R.string.ai_status_ok, Format.stamp(at)), note)
            else -> show(getString(R.string.ai_status_failed, Format.stamp(at)), note)
        }
    }

    /** Sends a sample sentence with the key and model as typed. */
    private fun test(button: View) {
        val typedKey = key.text.toString().trim()
        val input = takeModelInput()
        if (typedKey.isEmpty()) {
            show(getString(R.string.ai_test_no_key))
            return
        }
        if (!AiParser.isPlausibleModelName(input.name)) {
            resetModel(input.shown)
            return
        }
        val token = ++seq
        button.isEnabled = false
        show(getString(R.string.ai_test_running))
        Thread {
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            var used = input.name
            var result = AiParser.parse(typedKey, used, SAMPLE, now, zone)
            val reset = result.modelUnknown && used != AiParser.DEFAULT_MODEL
            if (reset) {
                // Unknown model: back to the default, and test that instead.
                used = AiParser.DEFAULT_MODEL
                result = AiParser.parse(typedKey, used, SAMPLE, now, zone)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true
                if (token != seq) return@runOnUiThread
                if (reset) {
                    resetModel(input.shown)
                } else if (result.parsed != null) {
                    Prefs.setModel(this, used)
                    lastChecked = typedKey to used
                }
                val p = result.parsed
                val outcome = if (p != null) {
                    getString(R.string.ai_test_ok, used, p.title, startText(p))
                } else {
                    getString(R.string.ai_test_failed, result.error.orEmpty())
                }
                val first = if (reset) getString(R.string.ai_model_reset, input.shown) else input.note
                show(first, if (first != null) "\n" + outcome else outcome)
            }
        }.start()
    }

    private fun startText(p: Parsed): String = p.start?.let { DATE.format(it) }.orEmpty()

    private companion object {
        const val SAMPLE = "Morgen um 10 Zahnarzt"
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm 'Uhr'", Locale.GERMAN)
    }
}
