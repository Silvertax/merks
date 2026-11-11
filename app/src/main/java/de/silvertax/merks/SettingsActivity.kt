package de.silvertax.merks

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast

/** Settings: small info with notification, update check, and the optional API key for AI sorting. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val compact = findViewById<Switch>(R.id.compact)
        compact.isChecked = Prefs.compact(this)
        compact.setOnCheckedChangeListener { _, on -> Prefs.setCompact(this, on) }

        val updates = findViewById<Switch>(R.id.updates)
        updates.isChecked = Prefs.updates(this)
        updates.setOnCheckedChangeListener { _, on -> Prefs.setUpdates(this, on) }

        val key = findViewById<EditText>(R.id.api_key)
        key.setText(Prefs.apiKey(this))
        findViewById<View>(R.id.save).setOnClickListener {
            Prefs.setApiKey(this, key.text.toString())
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
