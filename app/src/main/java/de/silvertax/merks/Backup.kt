package de.silvertax.merks

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Everything in Merks in one file, e.g. for a new phone or before trying another version of the app:
 * all entries and the settings. The AI key stays out: a backup file is easily passed on.
 */
object Backup {
    private const val FORMAT = 1

    /** The settings that go along: what was chosen on the settings screen, nothing the app keeps for itself. */
    private val SETTINGS = listOf(
        "theme", "oled", "accent", "mixed_accents", "compact", "pause", "leads", "eve_before",
        "own_calendar", "lang", "model", "next_up", "auto_updates", "mascot", "mascot_widget",
    )

    class Content(val entries: List<Entry>, val settings: Map<String, Any?>, val saved: Long)

    /** Writes all entries and settings to [out]. Returns how many entries were written. */
    fun write(ctx: Context, out: OutputStream, now: Long = System.currentTimeMillis()): Int {
        val entries = Db(ctx).all()
        val prefs = Prefs.prefs(ctx).all
        val json = JSONObject().apply {
            put("app", "merks")
            put("format", FORMAT)
            put("version", Updater.currentVersion(ctx))
            put("saved", now)
            put("entries", JSONArray().apply { for (e in entries) put(entry(e)) })
            put("settings", JSONObject().apply { for (k in SETTINGS) prefs[k]?.let { put(k, it) } })
        }
        out.writer(Charsets.UTF_8).use { it.write(json.toString(1)) }
        return entries.size
    }

    private fun entry(e: Entry) = JSONObject(EntryJson.write(e)).apply {
        remove("id")
        // Only meaningful on the phone it came from; checked again when read back (see restore).
        e.calendarEventId?.let { put("calendarEventId", it) }
    }

    /** Reads a backup; null when it is not one (another file, or damaged). */
    fun read(input: InputStream): Content? = try {
        val o = JSONObject(input.reader(Charsets.UTF_8).use { it.readText() })
        if (o.optString("app") != "merks" || o.optInt("format") !in 1..FORMAT) {
            null
        } else {
            val list = o.getJSONArray("entries")
            val entries = (0 until list.length()).mapNotNull { i ->
                val e = list.getJSONObject(i)
                val calendar = if (e.has("calendarEventId")) e.getLong("calendarEventId") else null
                EntryJson.read(e.apply { put("id", 0L) }.toString())?.copy(calendarEventId = calendar)
            }
            val s = o.optJSONObject("settings") ?: JSONObject()
            Content(entries, SETTINGS.filter { s.has(it) }.associateWith { s.get(it) }, o.optLong("saved"))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Puts [content] in place of what is in Merks now: entries, settings, reminders and widgets.
     * A link to an appointment in the phone's calendar is kept only when that appointment is there
     * with the same title, so a backup from another phone never changes or deletes a stranger's event.
     */
    fun restore(ctx: Context, content: Content) {
        val db = Db(ctx)
        for (old in db.all()) {
            Scheduler.cancel(ctx, old.id)
            db.delete(old.id)
        }
        for (e in content.entries.sortedBy { it.created }) {
            val linked = e.calendarEventId?.takeIf { calendarHas(ctx, it, e.title) }
            db.insert(e.copy(id = 0, calendarEventId = linked))
        }
        Prefs.prefs(ctx).edit().apply {
            for ((k, v) in content.settings) {
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                }
            }
        }.commit()
        Appearance.applyNightMode(ctx)
        Scheduler.rescheduleAll(ctx)
        ButtonWidget.updateAll(ctx)
    }

    private fun calendarHas(ctx: Context, id: Long, title: String): Boolean {
        if (!Store.hasCalendarPermission(ctx)) return false
        return try {
            ctx.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DELETED),
                null, null, null,
            )?.use { c -> c.moveToFirst() && c.getString(0) == title && c.getInt(1) == 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** "Merks-Sicherung-2026-11-19.json": the name offered for a new backup. */
    fun fileName(ctx: Context, today: java.time.LocalDate = java.time.LocalDate.now()): String =
        Lang.context(ctx).getString(R.string.backup_file_name, today.toString())
}
