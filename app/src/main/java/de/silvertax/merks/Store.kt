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
import de.silvertax.merks.parse.Repeat
import de.silvertax.merks.parse.RepeatUnit
import de.silvertax.merks.parse.RuleParser
import de.silvertax.merks.parse.RuleParserEn
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "merks.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT, raw TEXT, " +
                "start INTEGER, end_ INTEGER, all_day INTEGER, cal_id INTEGER, created INTEGER)",
        )
        addVersion2Columns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addVersion2Columns(db)
    }

    private fun addVersion2Columns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN done INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN snooze INTEGER")
        db.execSQL("ALTER TABLE entries ADD COLUMN repeat_unit TEXT")
        db.execSQL("ALTER TABLE entries ADD COLUMN repeat_every INTEGER")
    }

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
            put("done", if (e.done) 1 else 0)
            put("snooze", e.snooze)
            put("repeat_unit", e.repeat?.unit?.name)
            put("repeat_every", e.repeat?.every)
        },
    )

    fun delete(id: Long) {
        writableDatabase.delete("entries", "id=?", arrayOf(id.toString()))
    }

    fun setDone(id: Long, done: Boolean) = update(id, ContentValues().apply { put("done", if (done) 1 else 0) })

    fun setSnooze(id: Long, until: Long?) = update(id, ContentValues().apply { put("snooze", until) })

    private fun update(id: Long, values: ContentValues) {
        writableDatabase.update("entries", values, "id=?", arrayOf(id.toString()))
    }

    fun get(id: Long): Entry? = query("id=?", arrayOf(id.toString())).firstOrNull()

    fun all(): List<Entry> = query(null, null)

    private fun query(where: String?, args: Array<String>?): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.query("entries", null, where, args, null, null, "created DESC").use { c ->
            fun col(name: String) = c.getColumnIndexOrThrow(name)
            fun long(name: String): Long? = col(name).let { if (c.isNull(it)) null else c.getLong(it) }
            while (c.moveToNext()) {
                val unit = c.getString(col("repeat_unit"))
                val every = long("repeat_every")?.toInt()
                out += Entry(
                    id = c.getLong(col("id")),
                    kind = Kind.valueOf(c.getString(col("kind"))),
                    title = c.getString(col("title")),
                    raw = c.getString(col("raw")),
                    start = long("start"),
                    end = long("end_"),
                    allDay = c.getInt(col("all_day")) == 1,
                    calendarEventId = long("cal_id"),
                    created = c.getLong(col("created")),
                    done = c.getInt(col("done")) == 1,
                    snooze = long("snooze"),
                    repeat = if (unit != null && every != null && every >= 1) Repeat(RepeatUnit.valueOf(unit), every) else null,
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
    /** Pure black background when the app is dark. */
    fun oled(ctx: Context): Boolean = p(ctx).getBoolean("oled", false)

    /**
     * Light, dark or like the phone. Until 0.5.0 the OLED mode made the app dark by itself;
     * whoever had it on keeps a dark app.
     */
    fun theme(ctx: Context): String =
        p(ctx).getString("theme", null) ?: if (oled(ctx)) Appearance.DARK else Appearance.AUTO
    fun setTheme(ctx: Context, theme: String) = p(ctx).edit().putString("theme", theme).apply()
    fun setOled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("oled", on).apply()
    fun updates(ctx: Context): Boolean = p(ctx).getBoolean("updates", true)
    fun setUpdates(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("updates", on).apply()

    /** Extra notification at 18:00 on the day before an appointment. */
    fun eveBefore(ctx: Context): Boolean = p(ctx).getBoolean("eve_before", true)
    fun setEveBefore(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("eve_before", on).apply()

    /** "" follows the phone, otherwise "de" or "en". */
    fun lang(ctx: Context): String = p(ctx).getString("lang", "")!!
    fun setLang(ctx: Context, lang: String) = p(ctx).edit().putString("lang", lang).apply()

    fun apiKey(ctx: Context): String = p(ctx).getString("api_key", "")!!.trim()
    fun setApiKey(ctx: Context, key: String) = p(ctx).edit().putString("api_key", key.trim()).apply()

    /**
     * Stored tidied ("Sonnet 5.5" becomes "claude-sonnet-5-5"). The default is stored as empty,
     * so a new default in a later version applies automatically.
     */
    fun model(ctx: Context): String = AiParser.normalizeModel(p(ctx).getString("model", "")!!)
    fun setModel(ctx: Context, model: String) {
        val m = AiParser.normalizeModel(model)
        p(ctx).edit().putString("model", if (m == AiParser.DEFAULT_MODEL) "" else m).apply()
    }

    fun setAiStatus(ctx: Context, ok: Boolean, error: String?) = p(ctx).edit()
        .putLong("ai_at", System.currentTimeMillis()).putBoolean("ai_ok", ok).putString("ai_error", error).apply()
    fun aiAt(ctx: Context): Long = p(ctx).getLong("ai_at", 0)
    fun aiOk(ctx: Context): Boolean = p(ctx).getBoolean("ai_ok", false)
    fun aiError(ctx: Context): String? = p(ctx).getString("ai_error", null)

    fun askedPermissions(ctx: Context): Boolean = p(ctx).getBoolean("asked", false)
    fun setAskedPermissions(ctx: Context) = p(ctx).edit().putBoolean("asked", true).apply()
}

object Store {
    /** Sorts the spoken text: AI when a key is set and the request works, fixed rules otherwise. */
    fun sort(ctx: Context, text: String): Parsed {
        val now = LocalDateTime.now()
        val key = Prefs.apiKey(ctx)
        if (key.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            val model = Prefs.model(ctx)
            var ai = AiParser.parse(key, model, text, now, zone)
            var note = ai.error?.text(ctx)
            if (ai.modelUnknown && model != AiParser.DEFAULT_MODEL) {
                // A mistyped model must not switch the AI off for good: back to the default, try again.
                Prefs.setModel(ctx, "")
                ai = AiParser.parse(key, AiParser.DEFAULT_MODEL, text, now, zone)
                val reset = Lang.context(ctx).getString(R.string.ai_model_reset, model)
                note = listOfNotNull(reset, ai.error?.text(ctx)).joinToString("\n")
            }
            // Remembered so the settings can show whether AI sorting really works.
            Prefs.setAiStatus(ctx, ai.parsed != null, note)
            ai.parsed?.let { return it }
        }
        return if (Lang.isGerman(ctx)) RuleParser.parse(text, now) else RuleParserEn.parse(text, now)
    }

    /** Saves the entry, puts appointments into the phone calendar and schedules the reminder. */
    fun save(ctx: Context, p: Parsed, raw: String): Entry {
        val zone = ZoneId.systemDefault()
        val start = p.start?.atZone(zone)?.toInstant()?.toEpochMilli()
        val end = p.end?.atZone(zone)?.toInstant()?.toEpochMilli()
        val calId = if (p.kind == Kind.APPOINTMENT && p.start != null) insertCalendarEvent(ctx, p, raw) else null
        val draft = Entry(
            0, p.kind, p.title, raw, start, end, p.allDay, calId, System.currentTimeMillis(),
            repeat = if (start != null) p.repeat else null,
        )
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

    fun rrule(r: Repeat): String {
        val freq = when (r.unit) {
            RepeatUnit.DAY -> "DAILY"
            RepeatUnit.WEEK -> "WEEKLY"
            RepeatUnit.MONTH -> "MONTHLY"
            RepeatUnit.YEAR -> "YEARLY"
        }
        return if (r.every > 1) "FREQ=$freq;INTERVAL=${r.every}" else "FREQ=$freq"
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
                val startMillis: Long
                val length: Duration
                if (p.allDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    startMillis = start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    length = Duration.ofDays(1)
                } else {
                    val end = p.end?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    startMillis = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    length = Duration.between(start, end)
                }
                put(CalendarContract.Events.DTSTART, startMillis)
                if (p.repeat != null) {
                    // The calendar wants a duration instead of an end for a series.
                    put(CalendarContract.Events.RRULE, rrule(p.repeat))
                    put(CalendarContract.Events.DURATION, if (p.allDay) "P1D" else "PT${length.seconds}S")
                } else {
                    put(CalendarContract.Events.DTEND, startMillis + length.toMillis())
                }
            }
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
