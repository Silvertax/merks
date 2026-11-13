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
import java.time.temporal.ChronoUnit
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

    /** Writes all fields of [e] into its existing row. */
    fun replace(e: Entry) {
        writableDatabase.update(
            "entries",
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
            "id=?", arrayOf(e.id.toString()),
        )
    }

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

    /** A quiet notification that always shows what comes next, also on the lock screen. */
    fun nextUpNotification(ctx: Context): Boolean = p(ctx).getBoolean("next_up", false)
    fun setNextUpNotification(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("next_up", on).apply()

    /**
     * The version whose downloaded update waits for the permission to install, e.g. "v0.7.0". Kept here
     * because some phones restart the app when that permission is given.
     */
    fun pendingUpdate(ctx: Context): String? {
        val raw = p(ctx).getString("pending_update", null) ?: return null
        val since = System.currentTimeMillis() - (raw.substringAfter('|', "").toLongOrNull() ?: 0L)
        // Only for the few minutes that step takes; later the installer should not pop up out of nowhere.
        return raw.substringBefore('|').takeIf { since in 0..10 * 60_000L }
    }

    fun setPendingUpdate(ctx: Context, tag: String?) = p(ctx).edit()
        .apply { if (tag == null) remove("pending_update") else putString("pending_update", tag + "|" + System.currentTimeMillis()) }
        .apply()

    /** How an entry looked before its last change by voice, so "Undo" can bring it back. One at a time. */
    fun rememberBefore(ctx: Context, e: Entry) = p(ctx).edit().putString("before", EntryJson.write(e)).apply()

    /** The remembered version of entry [id], if the last change was to that entry; it is forgotten either way. */
    fun takeBefore(ctx: Context, id: Long): Entry? {
        val json = p(ctx).getString("before", null) ?: return null
        val e = EntryJson.read(json) ?: return null
        if (e.id != id) return null
        p(ctx).edit().remove("before").apply()
        return e
    }

    fun forgetBefore(ctx: Context) = p(ctx).edit().remove("before").apply()

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
        Glance.refresh(ctx)
        return entry
    }

    fun remove(ctx: Context, e: Entry) {
        Scheduler.cancel(ctx, e.id)
        Db(ctx).delete(e.id)
        deleteCalendarEvent(ctx, e.calendarEventId)
        Glance.refresh(ctx)
    }

    /**
     * Gives an entry a new date, time or repetition, spoken afterwards. The title stays. A note with a
     * date becomes an appointment; "remind me ..." makes it a reminder. The calendar entry and the
     * reminders move along; the version before is remembered for "Undo".
     */
    fun reschedule(ctx: Context, old: Entry, p: Parsed, said: String): Entry {
        val zone = ZoneId.systemDefault()
        val kind = when {
            p.kind == Kind.REMINDER -> Kind.REMINDER
            old.kind == Kind.NOTE -> Kind.APPOINTMENT
            else -> old.kind
        }
        val start = p.start?.atZone(zone)?.toInstant()?.toEpochMilli()
        val target = old.copy(
            kind = kind,
            raw = old.raw + " → " + said,
            start = start,
            end = p.end?.atZone(zone)?.toInstant()?.toEpochMilli(),
            allDay = p.allDay,
            repeat = if (start != null) p.repeat else null,
            created = System.currentTimeMillis(),
            done = false,
            snooze = null,
        )
        Prefs.rememberBefore(ctx, old)
        return rewrite(ctx, old, target)
    }

    /** Puts [current] back the way [before] was (the "Undo" of a change). */
    fun restore(ctx: Context, current: Entry, before: Entry): Entry = rewrite(ctx, current, before.copy(id = current.id))

    /** Replaces [current] with [target] under the same id, with a fresh calendar entry and alarms. */
    private fun rewrite(ctx: Context, current: Entry, target: Entry): Entry {
        Scheduler.cancel(ctx, current.id)
        deleteCalendarEvent(ctx, current.calendarEventId)
        val zone = ZoneId.systemDefault()
        fun local(ms: Long?) = ms?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zone) }
        val calId = if (target.kind == Kind.APPOINTMENT && target.start != null) {
            insertCalendarEvent(
                ctx, Parsed(target.kind, target.title, local(target.start), local(target.end), target.allDay, target.repeat), target.raw,
            )
        } else {
            null
        }
        val saved = target.copy(id = current.id, calendarEventId = calId)
        Db(ctx).replace(saved)
        Scheduler.schedule(ctx, saved)
        Glance.refresh(ctx)
        return saved
    }

    private fun deleteCalendarEvent(ctx: Context, id: Long?) {
        if (id == null || !hasCalendarPermission(ctx)) return
        try {
            ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        } catch (ex: Exception) {
            // Calendar entry may already be gone.
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
        if (r.unit == RepeatUnit.WORKDAY) return "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        val freq = when (r.unit) {
            RepeatUnit.DAY -> "DAILY"
            RepeatUnit.WEEK -> "WEEKLY"
            RepeatUnit.MONTH -> "MONTHLY"
            RepeatUnit.YEAR -> "YEARLY"
            RepeatUnit.WORKDAY -> "WEEKLY"
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
                    // Several days ("vom 20. bis 23. Dezember"): the end is the last day.
                    val days = p.end?.let { ChronoUnit.DAYS.between(start.toLocalDate(), it.toLocalDate()) + 1 } ?: 1
                    length = Duration.ofDays(days.coerceIn(1, 366))
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
                    put(CalendarContract.Events.DURATION, if (p.allDay) "P${length.toDays()}D" else "PT${length.seconds}S")
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

/** An entry as text, for the one remembered version before a change. */
object EntryJson {
    fun write(e: Entry): String = org.json.JSONObject().apply {
        put("id", e.id)
        put("kind", e.kind.name)
        put("title", e.title)
        put("raw", e.raw)
        e.start?.let { put("start", it) }
        e.end?.let { put("end", it) }
        put("allDay", e.allDay)
        put("created", e.created)
        put("done", e.done)
        e.snooze?.let { put("snooze", it) }
        e.repeat?.let { put("repeatUnit", it.unit.name); put("repeatEvery", it.every) }
    }.toString()

    fun read(json: String): Entry? = try {
        val o = org.json.JSONObject(json)
        fun long(k: String): Long? = if (o.has(k)) o.getLong(k) else null
        Entry(
            id = o.getLong("id"),
            kind = Kind.valueOf(o.getString("kind")),
            title = o.getString("title"),
            raw = o.getString("raw"),
            start = long("start"),
            end = long("end"),
            allDay = o.getBoolean("allDay"),
            calendarEventId = null,
            created = o.getLong("created"),
            done = o.optBoolean("done"),
            snooze = long("snooze"),
            repeat = if (o.has("repeatUnit")) Repeat(RepeatUnit.valueOf(o.getString("repeatUnit")), o.getInt("repeatEvery")) else null,
        )
    } catch (e: Exception) {
        null
    }
}
