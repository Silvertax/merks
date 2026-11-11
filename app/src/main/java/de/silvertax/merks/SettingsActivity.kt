package de.silvertax.merks

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.silvertax.merks.parse.AiParser
import java.time.LocalDateTime
import java.time.ZoneId

/** Settings: recording style, reminders, update check, and the optional AI sorting with its status. */
class SettingsActivity : Activity() {
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val compact = findViewById<Switch>(R.id.compact)
        compact.isChecked = Prefs.compact(this)
        compact.setOnCheckedChangeListener { _, on -> Prefs.setCompact(this, on) }

        val eve = findViewById<Switch>(R.id.eve_before)
        eve.isChecked = Prefs.eveBefore(this)
        eve.setOnCheckedChangeListener { _, on ->
            Prefs.setEveBefore(this, on)
            // Existing entries follow the switch right away.
            Thread { Scheduler.rescheduleAll(applicationContext) }.start()
        }

        val updates = findViewById<Switch>(R.id.updates)
        updates.isChecked = Prefs.updates(this)
        updates.setOnCheckedChangeListener { _, on -> Prefs.setUpdates(this, on) }

        key = findViewById(R.id.api_key)
        model = findViewById(R.id.model)
        status = findViewById(R.id.ai_status)
        key.setText(Prefs.apiKey(this))
        model.setText(Prefs.model(this))
        showLastStatus()

        findViewById<View>(R.id.test).setOnClickListener { test(it) }
        findViewById<View>(R.id.save).setOnClickListener {
            Prefs.setApiKey(this, key.text.toString())
            Prefs.setModel(this, model.text.toString())
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Whether the last real AI request worked, so a wrong key or model does not go unnoticed. */
    private fun showLastStatus() {
        val at = Prefs.aiAt(this)
        status.text = when {
            Prefs.apiKey(this).isEmpty() -> getString(R.string.ai_status_off)
            at == 0L -> getString(R.string.ai_status_unused)
            Prefs.aiOk(this) -> getString(R.string.ai_status_ok, Format.stamp(at))
            else -> getString(R.string.ai_status_failed, Format.stamp(at), Prefs.aiError(this).orEmpty())
        }
    }

    /** Sends a sample sentence with the key and model as typed, without saving them. */
    private fun test(button: View) {
        val typedKey = key.text.toString().trim()
        val typedModel = model.text.toString().trim().ifEmpty { AiParser.DEFAULT_MODEL }
        if (typedKey.isEmpty()) {
            status.setText(R.string.ai_test_no_key)
            return
        }
        button.isEnabled = false
        status.setText(R.string.ai_test_running)
        Thread {
            val result = AiParser.parse(typedKey, typedModel, SAMPLE, LocalDateTime.now(), ZoneId.systemDefault())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true
                val p = result.parsed
                status.text = if (p != null) {
                    getString(R.string.ai_test_ok, typedModel, p.title, p.start?.toString().orEmpty().replace('T', ' '))
                } else {
                    getString(R.string.ai_test_failed, result.error.orEmpty())
                }
            }
        }.start()
    }

    private companion object {
        const val SAMPLE = "Morgen um 10 Zahnarzt"
    }
}
