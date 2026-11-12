package de.silvertax.merks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Opened by the widget: listens right away, sorts what was said and confirms it. */
class RecordActivity : MerksActivity() {
    // A rebuild would stop the microphone; the recording is over in a few seconds anyway.
    override val followsOledChanges = false
    private var recognizer: SpeechRecognizer? = null
    private var saved: Entry? = null
    private var compact = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var spoken: TextView
    private lateinit var detail: TextView
    private lateinit var stop: Button
    private lateinit var retry: Button
    private lateinit var done: Button
    private lateinit var undo: Button
    private lateinit var pulse: View

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme is the small floating info; the full screen is switched on here.
        compact = Prefs.compact(this)
        if (!compact) setTheme(R.style.Theme_Merks)
        super.onCreate(savedInstanceState)
        if (compact) {
            setContentView(R.layout.activity_record_compact)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
        } else {
            setContentView(R.layout.activity_record)
        }
        status = findViewById(R.id.status)
        spoken = findViewById(R.id.spoken)
        detail = findViewById(R.id.detail)
        stop = findViewById(R.id.stop)
        retry = findViewById(R.id.retry)
        done = findViewById(R.id.done)
        undo = findViewById(R.id.undo)
        pulse = findViewById(R.id.pulse)

        stop.setOnClickListener { recognizer?.stopListening() }
        retry.setOnClickListener { listen() }
        done.setOnClickListener { finish() }
        undo.setOnClickListener {
            saved?.let { Store.remove(this, it) }
            Toast.makeText(this, R.string.undone, Toast.LENGTH_SHORT).show()
            finish()
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else fail(getString(R.string.err_mic))
    }

    private fun show(vararg visible: View) {
        for (b in listOf(stop, retry, done, undo)) b.visibility = if (b in visible) View.VISIBLE else View.GONE
    }

    /** Only language and region, e.g. "de-DE": recognisers stumble over extras such as "-u-fw-mon". */
    private fun speechTag(): String {
        val l = Lang.locale(this)
        return if (l.country.isEmpty()) l.language else l.language + "-" + l.country
    }

    // Set after the recogniser refused the language once; then it listens in its own default.
    private var plainLanguage = false

    private fun listen() {
        handler.removeCallbacksAndMessages(null)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail(getString(R.string.err_no_recognizer))
            return
        }
        status.setText(R.string.listening)
        spoken.text = ""
        detail.text = ""
        show(stop)

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Listen in the app's language, which is the one people speak to it.
                .apply { if (!plainLanguage) putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag()) }
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Give people time to think; not every recogniser honours these.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L),
        )
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partial: Bundle?) {
            best(partial)?.let { spoken.text = it }
        }

        override fun onResults(results: Bundle?) {
            val text = best(results)
            if (text.isNullOrBlank()) fail(getString(R.string.err_nothing)) else sortAndSave(text)
        }

        override fun onError(error: Int) {
            // 12/13: language not supported or not installed. Try once more in the recogniser's own language.
            if ((error == 12 || error == 13) && !plainLanguage) {
                plainLanguage = true
                listen()
                return
            }
            fail(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.err_nothing)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.err_network)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.err_mic)
                    else -> getString(R.string.err_generic, error)
                },
            )
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        // Roughly -2 dB in silence and up to 10 dB when speaking up.
        override fun onRmsChanged(rmsdB: Float) = ring((rmsdB + 2f) / 12f)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = ring(0f)
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** The ring around the microphone follows the voice: bigger when louder, resting when quiet. */
    private fun ring(level: Float) {
        val scale = 1f + 0.45f * level.coerceIn(0f, 1f)
        pulse.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    }

    private fun fail(message: String) {
        ring(0f)
        status.text = message
        detail.text = ""
        show(retry, done)
    }

    private fun sortAndSave(text: String) {
        ring(0f)
        status.setText(R.string.working)
        spoken.text = text
        show()
        Thread {
            val entry = try {
                Store.save(this, Store.sort(this, text), text)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    entry == null -> fail(getString(R.string.err_save))
                    // Small mode: the info disappears and a notification says what was saved.
                    compact && Scheduler.canConfirmByNotification(this) -> {
                        Scheduler.confirm(this, entry)
                        finish()
                    }
                    else -> confirm(entry)
                }
            }
        }.start()
    }

    private fun confirm(e: Entry) {
        saved = e
        status.setText(Format.savedLabel(e))
        spoken.text = e.title
        detail.text = Format.whenText(this, e)
        show(done, undo)
        handler.postDelayed({ finish() }, 8000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        super.onDestroy()
    }
}
