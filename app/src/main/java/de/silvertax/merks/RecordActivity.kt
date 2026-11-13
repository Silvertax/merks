package de.silvertax.merks

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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

/**
 * Opened by the widget: listens right away, sorts what was said and confirms it.
 * With [changeIntent] it listens for a new date or time for an existing entry instead.
 */
class RecordActivity : MerksActivity() {
    // A rebuild would stop the microphone; the recording is over in a few seconds anyway.
    override val followsOledChanges = false
    private var recognizer: SpeechRecognizer? = null
    private var saved: Entry? = null

    /** The entry that gets a new date or time, or null when something new is spoken. */
    private var changing: Entry? = null

    /** The last result was a change, so "Undo" puts the old version back instead of deleting. */
    private var savedWasChange = false
    private var compact = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var spoken: TextView
    private lateinit var detail: TextView
    private lateinit var stop: Button
    private lateinit var retry: Button
    private lateinit var done: Button
    private lateinit var undo: Button
    private lateinit var change: Button
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

        change = findViewById(R.id.change)
        change.setOnClickListener {
            saved?.let { changing = it }
            saved = null
            listen()
        }
        stop.setOnClickListener { stopSpeaking() }
        retry.setOnClickListener { retriedBusy = false; listen() }
        done.setOnClickListener { finish() }
        undo.setOnClickListener {
            val e = saved
            val before = if (savedWasChange && e != null) Prefs.takeBefore(this, e.id) else null
            when {
                e != null && before != null -> {
                    Store.restore(this, e, before)
                    Toast.makeText(this, R.string.undone_change, Toast.LENGTH_SHORT).show()
                }
                e != null -> {
                    Store.remove(this, e)
                    Toast.makeText(this, R.string.undone, Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }

        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent) {
        saved = null
        val id = intent.getLongExtra(EXTRA_CHANGE, -1L)
        changing = if (id > 0) Db(this).get(id) else null
        if (id > 0 && changing == null) {
            fail(getString(R.string.err_gone))
            show(done)
            return
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
        for (b in listOf(stop, retry, done, undo, change)) b.visibility = if (b in visible) View.VISIBLE else View.GONE
    }

    /** Only language and region, e.g. "de-DE": recognisers stumble over extras such as "-u-fw-mon". */
    private fun speechTag(): String {
        val l = Lang.locale(this)
        return if (l.country.isEmpty()) l.language else l.language + "-" + l.country
    }

    // Set after the recogniser refused the language once; then it listens in its own default.
    private var plainLanguage = false

    // Whether any sound arrived in this attempt. Without it, "nothing understood" would be the wrong
    // message: the microphone was busy, muted or blocked, and speaking louder does not help.
    private var heard = false
    private var micWasBusy = false
    private var retriedBusy = false

    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Another app is recording (a call, a voice message, a screen recording). Hotword listeners don't count. */
    private fun otherAppRecords(): Boolean = try {
        audio.activeRecordingConfigurations.any { it.clientAudioSource != HOTWORD_SOURCE }
    } catch (e: Exception) {
        false
    }

    /** Why no sound arrived, as far as the phone tells. */
    private fun silenceHint(): String = getString(
        when {
            audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION -> R.string.hint_call
            audio.isMicrophoneMute -> R.string.hint_muted
            micWasBusy -> R.string.hint_other_app
            else -> R.string.hint_silence
        },
    )

    /*
     * Many recognisers end after half a second of silence, whatever they are asked for. So a pause to
     * think does not end the recording: what was said so far is kept in [pieces] and the app listens
     * on. Only when nothing more comes for [PAUSE_MS], or "Done speaking" is tapped, it is saved.
     */
    private val pieces = mutableListOf<String>()

    // The current attempt listens on after a pause, and whether speaking has begun again in it.
    private var goingOn = false
    private var resumed = false

    // "Done speaking" was tapped while words were still coming: save with the next result.
    private var stopAsked = false

    private val pauseOver = Runnable {
        if (goingOn && !resumed) {
            recognizer?.cancel()
            finishSpeaking()
        }
    }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Listen in the app's language, which is the one people speak to it.
        .apply { if (!plainLanguage) putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag()) }
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Asked for, though not every recogniser honours it; listening on after a pause does the rest.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)

    private fun listen() {
        handler.removeCallbacksAndMessages(null)
        heard = false
        pieces.clear()
        goingOn = false
        stopAsked = false
        micWasBusy = otherAppRecords()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail(getString(R.string.err_no_recognizer))
            return
        }
        status.setText(R.string.listening)
        spoken.text = ""
        // When changing, say what for and what to say.
        detail.text = changing?.let { getString(R.string.edit_hint, it.title) } ?: ""
        show(stop)

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        recognizer?.startListening(speechIntent())
    }

    /** After a pause: keeps what was said and listens for more. */
    private fun listenOn() {
        goingOn = true
        resumed = false
        status.setText(R.string.listening_on)
        spoken.text = pieces.joinToString(" ")
        detail.setText(R.string.listening_on_hint)
        show(stop)
        ring(0f)
        handler.removeCallbacks(pauseOver)
        // Counted from when the microphone is open again; this one covers a recogniser that never says so.
        handler.postDelayed(pauseOver, PAUSE_MS + 1500)
        val r = recognizer
        if (r == null) {
            finishSpeaking()
            return
        }
        r.startListening(speechIntent())
    }

    /** "Done speaking": right away after a pause, otherwise with the words still being recognised. */
    private fun stopSpeaking() {
        if (goingOn && !resumed) {
            handler.removeCallbacks(pauseOver)
            recognizer?.cancel()
            finishSpeaking()
        } else {
            stopAsked = true
            recognizer?.stopListening()
        }
    }

    /** Everything said in this recording, sorted and saved, or the change made. */
    private fun finishSpeaking() {
        handler.removeCallbacks(pauseOver)
        goingOn = false
        val text = pieces.joinToString(" ").trim()
        val old = changing
        when {
            text.isEmpty() -> fail(getString(R.string.err_nothing))
            old != null -> sortAndChange(old, text)
            else -> sortAndSave(text)
        }
    }

    /** Speaking has begun again after a pause: the wait is over. */
    private fun speakingAgain() {
        if (goingOn && !resumed) {
            resumed = true
            handler.removeCallbacks(pauseOver)
            status.setText(R.string.listening)
        }
    }

    private val listener = object : RecognitionListener {
        // Only the spoken words put together, nothing to translate.
        @android.annotation.SuppressLint("SetTextI18n")
        override fun onPartialResults(partial: Bundle?) {
            best(partial)?.let {
                heard = true
                speakingAgain()
                spoken.text = (pieces + it).joinToString(" ")
            }
        }

        override fun onResults(results: Bundle?) {
            best(results)?.let { pieces += it.trim() }
            when {
                // Nothing at all, "Done speaking" tapped, or enough for one entry: save what there is.
                pieces.isEmpty() || stopAsked || pieces.size >= MAX_PIECES -> finishSpeaking()
                else -> listenOn()
            }
        }

        override fun onError(error: Int) {
            // Listening on after a pause: silence or any trouble now just means it was all.
            if (goingOn) {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retriedBusy) {
                    retriedBusy = true
                    handler.postDelayed({ if (goingOn) listenOn() }, 300)
                    return
                }
                finishSpeaking()
                return
            }
            // 12/13: language not supported or not installed. Try once more in the recogniser's own language.
            if ((error == 12 || error == 13) && !plainLanguage) {
                plainLanguage = true
                listen()
                return
            }
            // The recogniser was still busy or lost its service: one fresh attempt, shortly after.
            if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) && !retriedBusy) {
                retriedBusy = true
                handler.postDelayed({ listen() }, 400)
                return
            }
            val silent = !heard && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            if (silent) {
                fail(getString(R.string.err_silence), silenceHint())
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

        override fun onReadyForSpeech(params: Bundle?) {
            if (goingOn && !resumed) {
                handler.removeCallbacks(pauseOver)
                handler.postDelayed(pauseOver, PAUSE_MS)
            }
        }
        override fun onBeginningOfSpeech() {
            heard = true
            speakingAgain()
        }
        // Roughly -2 dB in silence and up to 10 dB when speaking up.
        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > 1f) heard = true
            ring((rmsdB + 2f) / 12f)
        }
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

    private fun fail(message: String, hint: String = "") {
        ring(0f)
        status.text = message
        detail.text = hint
        show(retry, done)
    }

    /** A new date, time or repetition for [old]; the title stays. */
    private fun sortAndChange(old: Entry, text: String) {
        ring(0f)
        status.setText(R.string.working)
        spoken.text = text
        show()
        Thread {
            val result = try {
                val p = Store.sort(this, text)
                if (p.start == null) null else Store.reschedule(this, old, p, text)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    result == null -> fail(getString(R.string.err_no_date), getString(R.string.edit_hint, old.title))
                    compact && Scheduler.canConfirmByNotification(this) -> {
                        Scheduler.confirm(this, result, changed = true)
                        finish()
                    }
                    else -> confirm(result, changed = true)
                }
            }
        }.start()
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

    private fun confirm(e: Entry, changed: Boolean = false) {
        saved = e
        savedWasChange = changed
        changing = null
        status.setText(if (changed) R.string.changed_label else Format.savedLabel(e))
        spoken.text = e.title
        detail.text = Format.whenText(this, e)
        show(done, undo, change)
        handler.postDelayed({ finish() }, 8000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        super.onDestroy()
    }

    companion object {
        // MediaRecorder.AudioSource.HOTWORD, not public: "Hey Google" waiting in the background.
        private const val HOTWORD_SOURCE = 1999
        private const val EXTRA_CHANGE = "change_id"

        /** How long a pause to think may be before what was said is saved. */
        const val PAUSE_MS = 3000L

        /** Pauses in one recording; more than that is not one entry any more. */
        private const val MAX_PIECES = 8

        /** Opens the recording to speak a new date or time for entry [id]. */
        fun changeIntent(ctx: Context, id: Long): Intent = Intent(ctx, RecordActivity::class.java).putExtra(EXTRA_CHANGE, id)
    }
}
