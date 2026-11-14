package de.silvertax.merks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import de.silvertax.merks.parse.Kind

/**
 * Opened by the widget: listens right away, sorts what was said and confirms it.
 * With [changeIntent] it listens for a new date or time for an existing entry instead,
 * with [noteIntent] for a note that goes with an existing entry.
 */
class RecordActivity : MerksActivity() {
    // A rebuild would stop the microphone; the recording is over in a few seconds anyway.
    override val followsOledChanges = false
    private var recognizer: SpeechRecognizer? = null
    private var saved: Entry? = null

    /** The entry that gets a new date or time, or null when something new is spoken. */
    private var changing: Entry? = null

    /** The entry that gets a note, or null. */
    private var noteFor: Entry? = null

    /** The last result was a change (or a note), so "Undo" puts the old version back instead of deleting. */
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
    private lateinit var note: Button
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
            changing = saved
            noteFor = null
            saved = null
            listen()
        }
        // Right after saving: say what goes with it, e.g. what to bring.
        note = findViewById(R.id.note)
        note.setOnClickListener {
            noteFor = saved
            changing = null
            saved = null
            listen()
        }
        stop.setOnClickListener { stopSpeaking() }
        retry.setOnClickListener { listen() }
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
        val db = Db(this)
        val changeId = intent.getLongExtra(EXTRA_CHANGE, -1L)
        val noteId = intent.getLongExtra(EXTRA_NOTE, -1L)
        changing = if (changeId > 0) db.get(changeId) else null
        noteFor = if (noteId > 0) db.get(noteId) else null
        if ((changeId > 0 && changing == null) || (noteId > 0 && noteFor == null)) {
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
        for (b in listOf(stop, retry, done, undo, change, note)) b.visibility = if (b in visible) View.VISIBLE else View.GONE
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

    // A first attempt that failed for a reason of the recogniser itself is tried once more by itself.
    private var retriedOnce = false

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
     * Many recognisers end after half a second of silence, whatever they are asked for, and some
     * stumble when they are started again right away. So a pause to think does not end the recording:
     * what was said so far is kept in [pieces], and a fresh recogniser listens on. Only when nothing
     * more comes for the pause chosen in the settings, or "Done speaking" is tapped, it is saved.
     */
    private val pieces = mutableListOf<String>()

    /** The words of the current attempt so far. They count even when the recogniser cannot finish. */
    private var partial = ""

    // Listening on after a pause, and whether speaking has begun again since.
    private var goingOn = false
    private var resumed = false
    private var micOpenInPause = false

    // When the current pause is over (uptime), fresh attempts in it, and pauses in this recording.
    private var pauseEnds = 0L
    private var restarts = 0
    private var pauses = 0
    private var startedAt = 0L

    // "Done speaking" was tapped while words were still coming: save with the next result.
    private var stopAsked = false

    // The recording is over (being saved, or a message is shown): late calls of a recogniser change nothing.
    private var ended = false

    private val pauseOver = Runnable { if (goingOn && !resumed && !ended) finishSpeaking() }

    // Some recognisers never answer once stopped; then what was understood so far is used.
    private val stopWaited = Runnable {
        if (stopAsked && !ended) {
            keepPartial()
            if (pieces.isEmpty()) fail(getString(R.string.err_nothing)) else finishSpeaking()
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

    /** A new recording: everything from the start. */
    private fun listen() {
        handler.removeCallbacksAndMessages(null)
        heard = false
        pieces.clear()
        partial = ""
        goingOn = false
        resumed = false
        stopAsked = false
        ended = false
        retriedOnce = false
        pauses = 0
        startedAt = SystemClock.uptimeMillis()
        micWasBusy = otherAppRecords()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail(getString(R.string.err_no_recognizer))
            return
        }
        status.setText(R.string.listening)
        spoken.text = ""
        // When changing or adding a note, say what for and what to say.
        detail.text = noteFor?.let { getString(R.string.note_hint, it.title) }
            ?: changing?.let { getString(R.string.edit_hint, it.title) }
            ?: ""
        show(stop)
        startRecognizer()
    }

    /** A fresh recogniser for every attempt: some phones refuse to start the same one twice in a row. */
    private fun startRecognizer() {
        recognizer?.destroy()
        partial = ""
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(Listener(r))
        r.startListening(speechIntent())
    }

    private fun keepPartial() {
        if (partial.isNotEmpty()) pieces += partial
        partial = ""
    }

    /** One stretch of speech is over: listen on after a pause, or save. */
    private fun afterSpeech() {
        val tooLong = SystemClock.uptimeMillis() - startedAt > MAX_RECORDING_MS
        when {
            pieces.isEmpty() -> fail(getString(R.string.err_nothing))
            stopAsked || pieces.size >= MAX_PIECES || pauses >= MAX_PAUSES || tooLong -> finishSpeaking()
            else -> beginPause()
        }
    }

    /** After a pause: keeps what was said and listens for more, with a fresh recogniser. */
    private fun beginPause() {
        goingOn = true
        resumed = false
        micOpenInPause = false
        restarts = 0
        pauses++
        status.setText(R.string.listening_on)
        spoken.text = pieces.joinToString(" ")
        detail.setText(R.string.listening_on_hint)
        show(stop)
        ring(0f)
        // Counted again from when the microphone is open; this covers a recogniser that never says so.
        endPauseIn(Prefs.speechPause(this) + STARTUP_MS)
        startRecognizer()
    }

    private fun endPauseIn(ms: Long) {
        pauseEnds = SystemClock.uptimeMillis() + ms
        handler.removeCallbacks(pauseOver)
        handler.postDelayed(pauseOver, ms)
    }

    /** Speaking has begun again after a pause: the wait is over. */
    private fun speakingAgain() {
        if (goingOn && !resumed) {
            resumed = true
            handler.removeCallbacks(pauseOver)
            status.setText(R.string.listening)
        }
    }

    /** "Done speaking": right away after a pause, otherwise with the words still being recognised. */
    private fun stopSpeaking() {
        if (goingOn && !resumed) {
            finishSpeaking()
            return
        }
        stopAsked = true
        recognizer?.stopListening()
        handler.removeCallbacks(stopWaited)
        handler.postDelayed(stopWaited, STOP_WAIT_MS)
    }

    /** The recogniser could not finish this attempt. */
    private fun failed(error: Int) {
        // What was understood so far counts, even without a final result.
        if (partial.isNotEmpty()) {
            keepPartial()
            afterSpeech()
            return
        }
        if (goingOn) {
            val soft = error in SOFT_ERRORS
            when {
                stopAsked -> finishSpeaking()
                // Speaking began again but nothing came of it (a cough, a noise): another pause.
                resumed && soft && pauses < MAX_PAUSES -> beginPause()
                // Silence came too early, or the recogniser was not ready yet: try again while the pause lasts.
                !resumed && soft && restarts < MAX_RESTARTS && SystemClock.uptimeMillis() + RESTART_MS < pauseEnds -> {
                    restarts++
                    handler.postDelayed({ if (goingOn && !resumed && !ended) startRecognizer() }, RESTART_MS)
                }
                else -> finishSpeaking()
            }
            return
        }
        // Stopped by hand: no second attempt that nobody asked for, just say what happened.
        // 12/13: language not supported or not installed. Try once more in the recogniser's own language.
        if ((error == 12 || error == 13) && !plainLanguage && !stopAsked) {
            plainLanguage = true
            listen()
            return
        }
        // Still busy, lost its service, or not ready yet: one fresh attempt, shortly after.
        if (error in RETRY_ERRORS && !retriedOnce && !stopAsked) {
            retriedOnce = true
            handler.postDelayed({ if (!ended) startRecognizer() }, 400)
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
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SERVER_DISCONNECTED ->
                    getString(R.string.err_not_ready)
                else -> getString(R.string.err_generic, error)
            },
        )
    }

    /** Everything said in this recording: sorted and saved, the change made, or the note added. */
    private fun finishSpeaking() {
        if (ended) return
        ended = true
        goingOn = false
        handler.removeCallbacks(pauseOver)
        handler.removeCallbacks(stopWaited)
        // The microphone is free at once, and late calls of the recogniser go nowhere.
        recognizer?.destroy()
        recognizer = null
        val text = pieces.joinToString(" ").trim()
        val target = noteFor
        val old = changing
        when {
            text.isEmpty() -> fail(getString(R.string.err_nothing))
            target != null -> addNote(target, text)
            old != null -> sortAndChange(old, text)
            else -> sortAndSave(text)
        }
    }

    /** Calls of an earlier recogniser, or after the recording ended, are ignored. */
    private inner class Listener(private val owner: SpeechRecognizer) : RecognitionListener {
        private val stale get() = owner !== recognizer || ended

        @SuppressLint("SetTextI18n") // only the spoken words put together, nothing to translate
        override fun onPartialResults(b: Bundle?) {
            if (stale) return
            best(b)?.let {
                partial = it.trim()
                heard = true
                speakingAgain()
                spoken.text = (pieces + partial).joinToString(" ")
            }
        }

        override fun onResults(b: Bundle?) {
            if (stale) return
            // Some recognisers end with nothing although words were shown while speaking.
            val text = best(b)?.trim() ?: partial
            partial = ""
            if (text.isNotEmpty()) pieces += text
            afterSpeech()
        }

        override fun onError(error: Int) {
            if (!stale) failed(error)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (stale) return
            // The microphone is open again: from now on the full pause counts, however long the start took.
            if (goingOn && !resumed && !micOpenInPause) {
                micOpenInPause = true
                endPauseIn(Prefs.speechPause(this@RecordActivity))
            }
        }

        override fun onBeginningOfSpeech() {
            if (stale) return
            heard = true
            speakingAgain()
        }

        // Roughly -2 dB in silence and up to 10 dB when speaking up.
        override fun onRmsChanged(rmsdB: Float) {
            if (stale) return
            if (rmsdB > 1f) heard = true
            ring((rmsdB + 2f) / 12f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (!stale) ring(0f)
        }
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
        ended = true
        goingOn = false
        handler.removeCallbacks(pauseOver)
        handler.removeCallbacks(stopWaited)
        recognizer?.destroy()
        recognizer = null
        ring(0f)
        status.text = message
        detail.text = hint
        show(retry, done)
    }

    /** Shows "One moment …" while [work] runs in the background, then hands its result to [then]. */
    private fun <T> working(text: String, work: () -> T?, then: (T?) -> Unit) {
        ring(0f)
        status.setText(R.string.working)
        spoken.text = text
        show()
        Thread {
            val result = try {
                work()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) then(result)
            }
        }.start()
    }

    /** A new date, time or repetition for [old]; the title stays. */
    private fun sortAndChange(old: Entry, text: String) = working(text, {
        val p = Store.sort(this, text)
        if (p.start == null) null else Store.reschedule(this, old, p, text)
    }) { result ->
        when {
            result == null -> fail(getString(R.string.err_no_date), getString(R.string.edit_hint, old.title))
            compact && Scheduler.canConfirmByNotification(this) -> {
                Scheduler.confirm(this, result, Scheduler.Confirm.CHANGED)
                finish()
            }
            else -> confirm(result, Scheduler.Confirm.CHANGED)
        }
    }

    /** What was said goes with [target] as a note; nothing is sorted. */
    private fun addNote(target: Entry, text: String) = working(text, {
        // Asked again: the entry may have changed meanwhile.
        Db(this).get(target.id)?.let {
            Prefs.rememberBefore(this, it)
            Store.addNote(this, it, text)
        }
    }) { result ->
        when {
            result == null -> fail(getString(R.string.err_gone))
            compact && Scheduler.canConfirmByNotification(this) -> {
                Scheduler.confirm(this, result, Scheduler.Confirm.NOTED, added = text)
                finish()
            }
            else -> confirm(result, Scheduler.Confirm.NOTED, added = text)
        }
    }

    private fun sortAndSave(text: String) = working(text, { Store.save(this, Store.sort(this, text), text) }) { entry ->
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

    private fun confirm(e: Entry, how: Scheduler.Confirm = Scheduler.Confirm.NEW, added: String? = null) {
        saved = e
        savedWasChange = how != Scheduler.Confirm.NEW
        changing = null
        noteFor = null
        status.setText(
            when (how) {
                Scheduler.Confirm.NEW -> Format.savedLabel(e)
                Scheduler.Confirm.CHANGED -> R.string.changed_label
                Scheduler.Confirm.NOTED -> R.string.note_added
            },
        )
        spoken.text = e.title
        detail.text = if (how == Scheduler.Confirm.NOTED) Store.tidyNote(added.orEmpty()) else Format.whenText(this, e)
        // A note goes with an appointment or a reminder, not with a note.
        if (e.kind == Kind.NOTE) show(done, undo, change) else show(done, undo, change, note)
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
        private const val EXTRA_NOTE = "note_id"

        /** How long to think a "normal" pause allows before what was said is saved. */
        const val PAUSE_MS = 3000L

        /** What a fresh recogniser may take until the microphone is open. */
        const val STARTUP_MS = 1000L

        /** How long "Done speaking" waits for the last words before it saves what it has. */
        const val STOP_WAIT_MS = 2500L

        // SpeechRecognizer.ERROR_SERVER_DISCONNECTED, named only from Android 12 on; older phones never send it.
        private const val SERVER_DISCONNECTED = 11

        private const val RESTART_MS = 300L
        private const val MAX_RESTARTS = 5

        /** Parts and pauses in one recording; more than that is not one entry any more. */
        private const val MAX_PIECES = 8
        private const val MAX_PAUSES = 12
        private const val MAX_RECORDING_MS = 120_000L

        /** Errors after a pause that only mean "nothing (yet)": silence, or a recogniser not ready. */
        private val SOFT_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SERVER_DISCONNECTED,
        )

        /** Errors of the recogniser itself, for which a first attempt is tried once more. */
        private val RETRY_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SERVER_DISCONNECTED,
        )

        /** Opens the recording to speak a new date or time for entry [id]. */
        fun changeIntent(ctx: Context, id: Long): Intent = Intent(ctx, RecordActivity::class.java).putExtra(EXTRA_CHANGE, id)

        /** Opens the recording to speak a note for entry [id], e.g. what to bring. */
        fun noteIntent(ctx: Context, id: Long): Intent = Intent(ctx, RecordActivity::class.java).putExtra(EXTRA_NOTE, id)
    }
}
