package de.silvertax.merks

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import de.silvertax.merks.parse.Kind

/** The list of everything that was dictated, plus a big button to speak. */
class MainActivity : Activity() {
    private var entries: List<Entry> = emptyList()
    private lateinit var list: ListView
    private lateinit var empty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> details(entries[position]) }

        findViewById<View>(R.id.speak).setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.add_widget).setOnClickListener {
            val awm = getSystemService(AppWidgetManager::class.java)
            if (awm.isRequestPinAppWidgetSupported) {
                awm.requestPinAppWidget(ComponentName(this, SpeakWidget::class.java), null, null)
            } else {
                AlertDialog.Builder(this).setMessage(R.string.widget_manual)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
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

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val now = System.currentTimeMillis()
        val all = Db(this).all()
        // Upcoming things first (soonest on top), then notes and everything that has passed.
        val upcoming = all.filter { it.start != null && it.start + DAY > now }.sortedBy { it.start }
        entries = upcoming + (all - upcoming.toSet())
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
            if (e.start != null) append(" · ").append(Format.whenText(e))
            append("\n\n").append(getString(R.string.said, e.raw))
        }
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.delete) { _, _ ->
                Store.remove(this, e)
                reload()
            }
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
            val e = entries[position]
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.sub).text =
                if (e.start != null) "${label(e)} · ${Format.whenText(e)}" else label(e)
            return v
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
