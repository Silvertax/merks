package de.silvertax.merks

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.CalendarContract
import de.silvertax.merks.parse.AiParser
import de.silvertax.merks.parse.Kind
import de.silvertax.merks.parse.Parsed
import de.silvertax.merks.parse.RuleParser
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class Entry(
    val id: Long,
    val kind: Kind,
    val title: String,
    val raw: String,
    val start: Long?,
    val end: Long?,
    val allDay: Boolean,
    val calendarEventId: Long?,
    val created: Long,
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "merks.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT, raw TEXT, " +
                "start INTEGER, end_ INTEGER, all_day INTEGER, cal_id INTEGER, created INTEGER)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(e: Entry): Long = writableDatabase.insert(
        "entries", null,
        ContentValues().apply {
            put("kind", e.kind.name)
            put("title", e.title)
            put("raw", e.raw)
            put("start", e.start)
            put("end_", e.end)
            put("all_day", if (e.allDay) 1 else 0)
            put("cal_id", e.calendarEventId)
            put("created", e.created)
        },
    )

    fun delete(id: Long) {
        writableDatabase.delete("entries", "id=?", arrayOf(id.toString()))
    }

    fun get(id: Long): Entry? = query("id=?", arrayOf(id.toString())).firstOrNull()

    fun all(): List<Entry> = query(null, null)

    private fun query(where: String?, args: Array<String>?): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.query("entries", null, where, args, null, null, "created DESC").use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    id = c.getLong(0),
                    kind = Kind.valueOf(c.getString(1)),
                    title = c.getString(2),
                    raw = c.getString(3),
                    start = if (c.isNull(4)) null else c.getLong(4),
                    end = if (c.isNull(5)) null else c.getLong(5),
                    allDay = c.getInt(6) == 1,
                    calendarEventId = if (c.isNull(7)) null else c.getLong(7),
                    created = c.getLong(8),
                )
            }
        }
        return out
    }
}

object Prefs {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("merks", Context.MODE_PRIVATE)
    private fun p(ctx: Context) = prefs(ctx)

    /** Small info while listening, confirmation as a notification. */
    fun compact(ctx: Context): Boolean = p(ctx).getBoolean("compact", true)
    fun setCompact(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("compact", on).apply()
    fun updates(ctx: Context): Boolean = p(ctx).getBoolean("updates", true)
    fun setUpdates(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("updates", on).apply()
    fun apiKey(ctx: Context): String = p(ctx).getString("api_key", "")!!.trim()
    fun setApiKey(ctx: Context, key: String) = p(ctx).edit().putString("api_key", key.trim()).apply()
    fun askedPermissions(ctx: Context): Boolean = p(ctx).getBoolean("asked", false)
    fun setAskedPermissions(ctx: Context) = p(ctx).edit().putBoolean("asked", true).apply()
}

object Store {
    /** Sorts the spoken text: AI when a key is set and the network answers, fixed rules otherwise. */
    fun sort(ctx: Context, text: String): Parsed {
        val now = LocalDateTime.now()
        val key = Prefs.apiKey(ctx)
        val ai = if (key.isNotEmpty()) AiParser.parse(key, text, now, ZoneId.systemDefault()) else null
        return ai ?: RuleParser.parse(text, now)
    }

    /** Saves the entry, puts appointments into the phone calendar and schedules the reminder. */
    fun save(ctx: Context, p: Parsed, raw: String): Entry {
        val zone = ZoneId.systemDefault()
        val start = p.start?.atZone(zone)?.toInstant()?.toEpochMilli()
        val end = p.end?.atZone(zone)?.toInstant()?.toEpochMilli()
        val calId = if (p.kind == Kind.APPOINTMENT && p.start != null) insertCalendarEvent(ctx, p, raw) else null
        val draft = Entry(0, p.kind, p.title, raw, start, end, p.allDay, calId, System.currentTimeMillis())
        val entry = draft.copy(id = Db(ctx).insert(draft))
        Scheduler.schedule(ctx, entry)
        return entry
    }

    fun remove(ctx: Context, e: Entry) {
        Scheduler.cancel(ctx, e.id)
        Db(ctx).delete(e.id)
        if (e.calendarEventId != null && hasCalendarPermission(ctx)) {
            try {
                ctx.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.calendarEventId), null, null,
                )
            } catch (ex: Exception) {
                // Calendar entry may already be gone.
            }
        }
    }

    fun hasCalendarPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun findCalendar(ctx: Context): Long? {
        val cols = arrayOf(CalendarContract.Calendars._ID)
        val where = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${CalendarContract.Calendars.VISIBLE}=1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, where, args, order)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun insertCalendarEvent(ctx: Context, p: Parsed, raw: String): Long? {
        if (!hasCalendarPermission(ctx)) return null
        return try {
            val calendar = findCalendar(ctx) ?: return null
            val start = p.start!!
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar)
                put(CalendarContract.Events.TITLE, p.title)
                put(CalendarContract.Events.DESCRIPTION, raw)
                if (p.allDay) {
                    val day = start.toLocalDate()
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DTSTART, day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                    put(CalendarContract.Events.DTEND, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                } else {
                    val zone = ZoneId.systemDefault()
                    val end = p.end?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                }
            }
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
