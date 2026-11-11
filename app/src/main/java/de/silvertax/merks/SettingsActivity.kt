package de.silvertax.merks

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast

/** One optional setting: the API key that switches on AI sorting. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val key = findViewById<EditText>(R.id.api_key)
        key.setText(Prefs.apiKey(this))
        findViewById<View>(R.id.save).setOnClickListener {
            Prefs.setApiKey(this, key.text.toString())
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
