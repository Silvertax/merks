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
    private var starts: Map<Long, Long?> = emptyMap()
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
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.add_widget).setOnClickListener { addWidget() }

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

    // Set when another screen comes to the front, e.g. the home screen's "add widget?" question.
    private var paused = false

    override fun onPause() {
        super.onPause()
        paused = true
    }

    /** There are two things for the home screen: the button to speak, and what comes next. */
    private fun addWidget() {
        val choices = arrayOf(getString(R.string.widget_pick_speak), getString(R.string.widget_pick_next))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_widget)
            .setItems(choices) { _, which ->
                if (which == 0) pinWidget(SpeakWidget::class.java, getString(R.string.speak))
                else pinWidget(NextWidget::class.java, getString(R.string.next_up_label))
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
        val all = Db(this).all()
        val zone = ZoneId.systemDefault()
        // For a series the date that counts is its next one.
        starts = all.associate { it.id to Plan.currentStart(it, now, zone) }
        // Upcoming things first (soonest on top), then notes, then everything done or passed.
        val upcoming = all.filter { e -> !e.done && (starts[e.id] ?: 0) + DAY > now }.sortedBy { starts[it.id] }
        entries = upcoming + (all - upcoming.toSet()).sortedBy { it.done }
        adapter.notifyDataSetChanged()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

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
            append("\n\n").append(getString(R.string.said, e.raw))
        }
        // A big button for the most common fix: the date or time was missing or wrong.
        val changeButton = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            setText(R.string.edit_voice)
            setIconResource(R.drawable.ic_mic)
            minHeight = (56 * resources.displayMetrics.density).toInt()
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(changeButton)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(e.title)
            .setMessage(message)
            .setView(box)
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
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
            val e = entries[position]
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
            val ink = MaterialColors.getColor(badge, foreground)
            badge.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(badge, background))
            // What has a date shows it in place of the symbol, so the day is found without reading the lines.
            val day = starts[e.id].takeIf { !e.done }
            v.findViewById<View>(R.id.badge_date).visibility = if (day != null) View.VISIBLE else View.GONE
            v.findViewById<ImageView>(R.id.badge_icon).apply {
                visibility = if (day != null) View.GONE else View.VISIBLE
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ink)
            }
            if (day != null) {
                v.findViewById<TextView>(R.id.badge_day).apply {
                    text = Format.tileDay(day)
                    setTextColor(ink)
                }
                v.findViewById<TextView>(R.id.badge_month).apply {
                    text = Format.tileMonth(this@MainActivity, day)
                    setTextColor(ink)
                }
            }
            v.findViewById<View>(R.id.card).alpha = if (e.done) 0.6f else 1f
            return v
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
