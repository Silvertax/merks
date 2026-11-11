package de.silvertax.merks

import android.Manifest
import android.app.Activity
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
import java.util.Locale

/** Opened by the widget: listens right away, sorts what was said and confirms it. */
class RecordActivity : Activity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme is the small floating info; the full screen is switched on here.
        compact = Prefs.compact(this)
        if (!compact) setTheme(R.style.AppTheme)
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
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else fail(getString(R.string.err_mic))
    }

    private fun show(vararg visible: View) {
        for (b in listOf(stop, retry, done, undo)) b.visibility = if (b in visible) View.VISIBLE else View.GONE
    }

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
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
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
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun fail(message: String) {
        status.text = message
        detail.text = ""
        show(retry, done)
    }

    private fun sortAndSave(text: String) {
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
        detail.text = Format.whenText(e)
        show(done, undo)
        handler.postDelayed({ finish() }, 8000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        super.onDestroy()
    }
}
