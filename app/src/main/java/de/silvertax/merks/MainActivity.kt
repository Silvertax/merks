package de.silvertax.merks

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.silvertax.merks.parse.Kind
import java.time.ZoneId

/** The list of everything that was dictated, plus a big button to speak. */
class MainActivity : MerksActivity() {
    private var entries: List<Entry> = emptyList()
    private var upcoming: List<Entry> = emptyList()
    private var starts: Map<Long, Long?> = emptyMap()
    private var heads: Map<Int, String> = emptyMap()

    // The colours in use; several in the mixed mode (see Appearance.mix).
    private lateinit var tones: List<Appearance.Tone>
    private lateinit var list: ListView
    private lateinit var empty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter

        findViewById<View>(R.id.speak).setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }
        findViewById<View>(R.id.photo).setOnClickListener {
            startActivity(Intent(this, PhotoActivity::class.java))
        }
        // A picture someone sent, a PDF, a calendar file: read like a photo.
        findViewById<View>(R.id.from_file).setOnClickListener {
            startActivity(PhotoActivity.pickIntent(this))
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.add_widget).setOnClickListener { addWidget() }
        tones = Appearance.tones(this)
        if (tones.size > 1) mixButtons()
        // Everything that is coming up as a text, e.g. for the family chat.
        findViewById<View>(R.id.share).setOnClickListener {
            Share.send(this, Share.list(this, upcoming.map { it to starts[it.id] }))
        }

        if (!Prefs.askedPermissions(this)) {
            Prefs.setAskedPermissions(this)
            val wanted = arrayListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(wanted.toTypedArray(), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app looks for a new version; the notice shows what was found, even offline.
        showUpdate()
        Updater.checkNow(this) { runOnUiThread { if (!isDestroyed) showUpdate() } }
    }

    /** "Version … is out": "Update now" goes on in the settings, where the download is shown; "Later" waits a day. */
    private fun showUpdate() {
        val banner = findViewById<View>(R.id.update_banner)
        val info = Updater.pending(this)
        banner.visibility = if (info == null) View.GONE else View.VISIBLE
        if (info == null) return
        findViewById<TextView>(R.id.update_banner_text).text = getString(R.string.update_banner, info.tag.removePrefix("v"))
        findViewById<View>(R.id.update_now).setOnClickListener { startActivity(SettingsActivity.updateIntent(this, info)) }
        findViewById<View>(R.id.update_later).setOnClickListener {
            Prefs.setUpdateLater(this, info.tag)
            banner.visibility = View.GONE
        }
    }

    // Set when another screen comes to the front, e.g. the home screen's "add widget?" question.
    private var paused = false

    override fun onPause() {
        super.onPause()
        paused = true
    }

    /**
     * What there is for the home screen: everything on one card, or each on its own: the buttons to
     * speak, to photograph and to read a file, and what comes next.
     */
    private fun addWidget() {
        val choices = arrayOf(
            getString(R.string.widget_pick_all),
            getString(R.string.widget_pick_speak),
            getString(R.string.widget_pick_photo),
            getString(R.string.widget_pick_file),
            getString(R.string.widget_pick_next),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_widget)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> pinWidget(AllInOneWidget::class.java, getString(R.string.widget_all))
                    1 -> pinWidget(SpeakWidget::class.java, getString(R.string.speak))
                    2 -> pinWidget(PhotoWidget::class.java, getString(R.string.photo))
                    3 -> pinWidget(FileWidget::class.java, getString(R.string.file_widget))
                    else -> pinWidget(NextWidget::class.java, getString(R.string.next_up_label))
                }
            }
            .show()
    }

    /**
     * Asks the home screen to add the widget. Some phones (Xiaomi above all) accept the request and
     * then drop it without a word, so if nothing visible happens the way by hand is explained.
     */
    private fun pinWidget(provider: Class<*>, name: String) {
        val awm = getSystemService(AppWidgetManager::class.java)
        val widget = ComponentName(this, provider)
        val before = awm.getAppWidgetIds(widget).size
        val asked = awm.isRequestPinAppWidgetSupported &&
            runCatching { awm.requestPinAppWidget(widget, null, null) }.getOrDefault(false)
        if (!asked) {
            explainWidget(name, refused = false)
            return
        }
        paused = false
        window.decorView.postDelayed({
            val nothingHappened = !paused && awm.getAppWidgetIds(widget).size == before
            if (nothingHappened && !isFinishing && !isDestroyed) explainWidget(name, refused = true)
        }, 1500)
    }

    private fun explainWidget(name: String, refused: Boolean) {
        val xiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
        val text = buildString {
            if (refused) append(getString(R.string.widget_refused)).append(' ')
            if (refused && xiaomi) append(getString(R.string.widget_xiaomi)).append(' ')
            if (refused) append("\n\n")
            append(getString(R.string.widget_manual, name))
        }
        val dialog = MaterialAlertDialogBuilder(this).setMessage(text).setPositiveButton(android.R.string.ok, null)
        if (refused && xiaomi) {
            dialog.setNeutralButton(R.string.widget_app_settings) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)),
                    )
                }
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        reload()
        Glance.refresh(this)
    }

    private fun reload() {
        val now = System.currentTimeMillis()
        // What is over leaves first; the calendar keeps the appointments.
        Store.clearPast(this, now)
        val all = Db(this).all()
        val zone = ZoneId.systemDefault()
        // The date that counts is the one still going on or coming next: for a series whose time today
        // is over, its next one; for a reminder put off, its new time. Ticked off: the date it had.
        starts = all.associate { it.id to (Plan.upcoming(it, now, zone)?.start ?: Plan.currentStart(it, now, zone)) }
        // What is still to come first (soonest on top, so what is now or next is always at the top),
        // then notes, then what is ticked off but not over yet.
        upcoming = all.filter { e -> !e.done && e.start != null }.sortedWith(compareBy({ starts[it.id] }, { it.title.lowercase() }))
        entries = upcoming + (all - upcoming.toSet()).sortedBy { it.done }
        // A heading where a new part of the list begins: today, tomorrow, this week, next week, later,
        // notes, ticked off. So a date further away is seen to be further away. Only with two parts or more.
        val firstDay = java.time.temporal.WeekFields.of(Lang.locale(this)).firstDayOfWeek
        val sections = entries.map { Plan.section(it, starts[it.id], now, zone, firstDay) }
        heads = if (sections.distinct().size < 2) emptyMap() else
            sections.withIndex().filter { (i, s) -> i == 0 || sections[i - 1] != s }.associate { (i, s) -> i to sectionName(s) }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.share).visibility = if (upcoming.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Mixed colours: "Speak" keeps the first, the buttons below it take the next ones. */
    private fun mixButtons() {
        fun tone(i: Int) = tones[i % tones.size]
        findViewById<com.google.android.material.button.MaterialButton>(R.id.photo).apply {
            backgroundTintList = ColorStateList.valueOf(tone(1).container)
            setTextColor(tone(1).onContainer)
            iconTint = ColorStateList.valueOf(tone(1).onContainer)
        }
        for ((id, i) in listOf(R.id.from_file to 2, R.id.add_widget to 3)) {
            findViewById<com.google.android.material.button.MaterialButton>(id).apply {
                setTextColor(tone(i).primary)
                iconTint = ColorStateList.valueOf(tone(i).primary)
            }
        }
    }

    private fun sectionName(s: Plan.Section): String = getString(
        when (s) {
            Plan.Section.TODAY -> R.string.section_today
            Plan.Section.TOMORROW -> R.string.section_tomorrow
            Plan.Section.THIS_WEEK -> R.string.section_this_week
            Plan.Section.NEXT_WEEK -> R.string.section_next_week
            Plan.Section.LATER -> R.string.section_later
            Plan.Section.NOTES -> R.string.section_notes
            Plan.Section.DONE -> R.string.section_done
        },
    )

    private fun label(e: Entry): String = getString(
        when (e.kind) {
            Kind.APPOINTMENT -> R.string.kind_appointment
            Kind.REMINDER -> R.string.kind_reminder
            Kind.NOTE -> R.string.kind_note
        },
    )

    private fun details(e: Entry) {
        val message = buildString {
            append(label(e))
            if (e.start != null) append(" · ").append(Format.whenText(this@MainActivity, e, starts[e.id]))
            if (e.done) append("\n").append(getString(R.string.is_done))
            e.note?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(getString(R.string.note_label, it)) }
            // Where it came from: what was said, the words on the photo, or in a file or message.
            val from = when {
                e.raw.startsWith(Store.PHOTO_MARK) -> getString(R.string.read_from_photo, e.raw.removePrefix(Store.PHOTO_MARK))
                e.raw.startsWith(Store.FILE_MARK) -> getString(R.string.read_from_file, e.raw.removePrefix(Store.FILE_MARK))
                else -> getString(R.string.said, e.raw)
            }
            append("\n\n").append(from)
        }
        // A big button for the most common fix: the date or time was missing or wrong.
        val changeButton = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            setText(R.string.edit_voice)
            setIconResource(R.drawable.ic_mic)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            minHeight = (56 * resources.displayMetrics.density).toInt()
        }
        // The same by typing, and the text as well: for a word that was understood wrongly.
        val editButton = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            id = R.id.edit_entry
            setText(R.string.edit_write)
            setIconResource(R.drawable.ic_edit)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            minHeight = (56 * resources.displayMetrics.density).toInt()
        }
        // What goes with an appointment, e.g. what to bring; a note on its own can join one instead.
        val attachTo = upcoming.filter { it.kind != Kind.NOTE && it.id != e.id }
        val noteButton = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            id = R.id.note_entry
            setText(if (e.kind == Kind.NOTE) R.string.note_attach else R.string.note_add)
            setIconResource(if (e.kind == Kind.NOTE) R.drawable.ic_event else R.drawable.ic_note)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            minHeight = (56 * resources.displayMetrics.density).toInt()
            visibility = if (e.kind == Kind.NOTE && attachTo.isEmpty()) View.GONE else View.VISIBLE
        }
        // And one to pass this entry on.
        val shareButton = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            id = R.id.share_entry
            setText(R.string.share)
            setIconResource(R.drawable.ic_share)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            minHeight = (56 * resources.displayMetrics.density).toInt()
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(changeButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(noteButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(editButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(shareButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // On a small screen with large print the buttons can scroll instead of being cut off.
        val scroll = androidx.core.widget.NestedScrollView(this).apply { addView(box) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(e.title)
            .setMessage(message)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.delete) { _, _ ->
                Store.remove(this, e)
                reload()
            }
        // Single appointments and reminders can be ticked off; a series simply carries on.
        if (e.start != null && e.repeat == null) {
            dialog.setNeutralButton(if (e.done) R.string.mark_open else R.string.action_done) { _, _ ->
                val db = Db(this)
                db.setDone(e.id, !e.done)
                db.setSnooze(e.id, null)
                Scheduler.dismiss(this, e.id)
                db.get(e.id)?.let { Scheduler.schedule(this, it) }
                Glance.refresh(this)
                reload()
            }
        }
        val shown = dialog.show()
        changeButton.setOnClickListener {
            shown.dismiss()
            startActivity(RecordActivity.changeIntent(this, e.id))
        }
        editButton.setOnClickListener {
            shown.dismiss()
            startActivity(EditActivity.intent(this, e.id))
        }
        noteButton.setOnClickListener {
            shown.dismiss()
            if (e.kind == Kind.NOTE) attach(e, attachTo) else startActivity(RecordActivity.noteIntent(this, e.id))
        }
        shareButton.setOnClickListener {
            shown.dismiss()
            Share.send(this, Share.line(this, e, starts[e.id]))
        }
    }

    /** A note spoken on its own goes with one of the coming appointments, e.g. what to bring there. */
    private fun attach(note: Entry, choices: List<Entry>) {
        val names = choices.map { it.title + " · " + Format.whenText(this, it, starts[it.id]) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_attach_title)
            .setItems(names) { _, which ->
                val target = Db(this).get(choices[which].id)
                val current = Db(this).get(note.id)
                if (target != null && current != null) {
                    Store.attachNote(this, current, target)
                    android.widget.Toast.makeText(this, getString(R.string.note_attached, target.title), android.widget.Toast.LENGTH_SHORT).show()
                }
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
            val e = entries[position]
            v.findViewById<TextView>(R.id.section).apply {
                val head = heads[position]
                text = head
                visibility = if (head == null) View.GONE else View.VISIBLE
                // The very first heading sits close under the top.
                setPadding(paddingLeft, if (position == 0) 0 else (12 * resources.displayMetrics.density).toInt(), paddingRight, paddingBottom)
            }
            v.findViewById<View>(R.id.card).setOnClickListener { details(e) }
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.sub).text =
                if (e.start != null) "${label(e)} · ${Format.whenText(this@MainActivity, e, starts[e.id])}" else label(e)

            // Each kind has its own symbol and colour; done entries get a tick and step back.
            val (icon, background, foreground) = when {
                e.done -> Triple(R.drawable.ic_check, M.attr.colorSurfaceContainerHigh, M.attr.colorOnSurfaceVariant)
                e.kind == Kind.APPOINTMENT ->
                    Triple(R.drawable.ic_event, M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer)
                e.kind == Kind.REMINDER ->
                    Triple(R.drawable.ic_bell, M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer)
                else -> Triple(R.drawable.ic_note, M.attr.colorSurfaceContainerHigh, M.attr.colorOnSurfaceVariant)
            }
            val badge = v.findViewById<View>(R.id.badge)
            // What has a date shows it in place of the symbol, so the day is found without reading the lines.
            val day = starts[e.id].takeIf { !e.done }
            // Mixed colours: an appointment's tile in the colour of its day, the same as in the widgets.
            val mixed = tones.takeIf { it.size > 1 && e.kind == Kind.APPOINTMENT && day != null }
                ?.let { it[Math.floorMod(Appearance.slotOfDay(day!!), it.size)] }
            val ink = mixed?.onContainer ?: MaterialColors.getColor(badge, foreground)
            badge.backgroundTintList = ColorStateList.valueOf(mixed?.container ?: MaterialColors.getColor(badge, background))
            v.findViewById<View>(R.id.badge_date).visibility = if (day != null) View.VISIBLE else View.GONE
            v.findViewById<ImageView>(R.id.badge_icon).apply {
                visibility = if (day != null) View.GONE else View.VISIBLE
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ink)
            }
            if (day != null) {
                v.findViewById<TextView>(R.id.badge_weekday).apply {
                    text = Format.tileWeekday(this@MainActivity, day)
                    setTextColor(ink)
                }
                v.findViewById<TextView>(R.id.badge_day).apply {
                    text = Format.tileDay(day)
                    setTextColor(ink)
                }
                v.findViewById<TextView>(R.id.badge_month).apply {
                    text = Format.tileMonth(this@MainActivity, day)
                    setTextColor(ink)
                }
            }
            v.findViewById<TextView>(R.id.entry_note).apply {
                text = e.note
                visibility = if (e.note.isNullOrBlank()) View.GONE else View.VISIBLE
                // A small note symbol in front, in the colour of the text.
                val icon = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_note)?.mutate()?.apply {
                    val size = (18 * resources.displayMetrics.density).toInt()
                    setBounds(0, 0, size, size)
                    setTint(currentTextColor)
                }
                setCompoundDrawablesRelative(icon, null, null, null)
            }
            v.findViewById<View>(R.id.card).alpha = if (e.done) 0.6f else 1f
            return v
        }
    }
}
