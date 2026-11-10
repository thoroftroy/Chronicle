package dev.wonderdesk.chronicle

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID

data class Cal(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean, val visible: Boolean)

data class Occurrence(
    val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
    val color: Int, val location: String, val calendarId: Long, val recurring: Boolean,
) {
    fun startsOn(zone: ZoneId): LocalDate = if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate() else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
    fun lastDay(zone: ZoneId): LocalDate = if (allDay) Instant.ofEpochMilli(end - 1).atZone(ZoneOffset.UTC).toLocalDate() else Instant.ofEpochMilli((end - 1).coerceAtLeast(begin)).atZone(zone).toLocalDate()
    fun covers(day: LocalDate, zone: ZoneId) = !day.isBefore(startsOn(zone)) && !day.isAfter(lastDay(zone))
}

data class EventDraft(
    val id: Long? = null, val title: String = "", val calendarId: Long = -1, val allDay: Boolean = false,
    val start: Long = System.currentTimeMillis(), val end: Long = System.currentTimeMillis() + 3_600_000L,
    val location: String = "", val notes: String = "", val rrule: String = "", val reminders: List<Int> = listOf(15),
)

data class Task(
    val id: String = UUID.randomUUID().toString(), val title: String = "", val notes: String = "",
    val due: Long = 0L, val dueHasTime: Boolean = false, val done: Boolean = false, val priority: Int = 0,
    val list: String = "", val created: Long = System.currentTimeMillis(), val completedAt: Long = 0L,
) {
    fun toJson() = JSONObject().put("id", id).put("title", title).put("notes", notes).put("due", due).put("dueHasTime", dueHasTime)
        .put("done", done).put("priority", priority).put("list", list).put("created", created).put("completedAt", completedAt)
    companion object {
        fun from(o: JSONObject) = Task(o.getString("id"), o.optString("title"), o.optString("notes"), o.optLong("due"), o.optBoolean("dueHasTime"),
            o.optBoolean("done"), o.optInt("priority"), o.optString("list"), o.optLong("created"), o.optLong("completedAt"))
    }
}

class CalendarRepo(private val context: Context) {
    private val cr get() = context.contentResolver

    fun calendars(): List<Cal> = runCatching {
        cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR,
            Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE), null, null, "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}")?.use { c ->
            buildList { while (c.moveToNext()) add(Cal(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "", c.getInt(3),
                c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR, c.getInt(5) == 1)) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** A calendar that lives only on this phone, made the first time there's nowhere else to save. */
    fun ensureLocalCalendar(): Long {
        calendars().firstOrNull { it.account == LOCAL_ACCOUNT }?.let { return it.id }
        val uri = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT).appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT); put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "Chronicle"); put(Calendars.CALENDAR_DISPLAY_NAME, "On this phone"); put(Calendars.CALENDAR_COLOR, 0xFF1E7FE8.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER); put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1); put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return cr.insert(uri, v)?.let { ContentUris.parseId(it) } ?: -1
    }

    fun setVisible(id: Long, visible: Boolean) {
        runCatching { cr.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), ContentValues().apply { put(Calendars.VISIBLE, if (visible) 1 else 0) }, null, null) }
    }

    fun occurrences(from: Long, to: Long, query: String? = null): List<Occurrence> = runCatching {
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val sel = buildString {
            append("${Instances.VISIBLE} = 1")
            if (!query.isNullOrBlank()) append(" AND (${Instances.TITLE} LIKE ? OR ${Instances.EVENT_LOCATION} LIKE ? OR ${Instances.DESCRIPTION} LIKE ?)")
        }
        val args = if (query.isNullOrBlank()) null else Array(3) { "%$query%" }
        cr.query(uri, arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.DISPLAY_COLOR,
            Instances.EVENT_LOCATION, Instances.CALENDAR_ID, Instances.RRULE), sel, args, "${Instances.BEGIN} ASC")?.use { c ->
            buildList { while (c.moveToNext()) add(Occurrence(c.getLong(0), c.getString(1)?.ifBlank { null } ?: "(No title)", c.getLong(2), c.getLong(3), c.getInt(4) == 1,
                c.getInt(5), c.getString(6) ?: "", c.getLong(7), !c.getString(8).isNullOrBlank())) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun load(eventId: Long): EventDraft? = runCatching {
        cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events.TITLE, Events.CALENDAR_ID, Events.ALL_DAY, Events.DTSTART, Events.DTEND,
            Events.DURATION, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.RRULE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val start = c.getLong(3)
            val end = if (!c.isNull(4)) c.getLong(4) else start + parseDuration(c.getString(5))
            val reminders = cr.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { r ->
                buildList { while (r.moveToNext()) add(r.getInt(0)) }
            }.orEmpty()
            EventDraft(eventId, c.getString(0) ?: "", c.getLong(1), c.getInt(2) == 1, start, end, c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "", reminders)
        }
    }.getOrNull()

    fun save(d: EventDraft): Long {
        val calId = if (d.calendarId >= 0) d.calendarId else ensureLocalCalendar()
        val v = ContentValues().apply {
            put(Events.TITLE, d.title.ifBlank { "New event" }); put(Events.CALENDAR_ID, calId)
            put(Events.EVENT_LOCATION, d.location); put(Events.DESCRIPTION, d.notes)
            put(Events.ALL_DAY, if (d.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (d.allDay) "UTC" else TimeZone.getDefault().id)
            put(Events.DTSTART, d.start)
            if (d.rrule.isNotBlank()) {
                put(Events.RRULE, d.rrule); putNull(Events.DTEND)
                put(Events.DURATION, if (d.allDay) "P${((d.end - d.start) / 86_400_000L).coerceAtLeast(1)}D" else "P${((d.end - d.start) / 1000).coerceAtLeast(60)}S")
            } else { putNull(Events.RRULE); putNull(Events.DURATION); put(Events.DTEND, d.end) }
            put(Events.HAS_ALARM, if (d.reminders.isNotEmpty()) 1 else 0)
        }
        val id = if (d.id != null) { cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, d.id), v, null, null); d.id }
                 else cr.insert(Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) } ?: throw IllegalStateException("The calendar refused the event")
        cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
        d.reminders.distinct().forEach { m -> cr.insert(Reminders.CONTENT_URI, ContentValues().apply { put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, m); put(Reminders.METHOD, Reminders.METHOD_ALERT) }) }
        return id
    }

    fun delete(eventId: Long) { cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) }

    /** Remove just one occurrence of a repeating event. */
    fun deleteOccurrence(eventId: Long, begin: Long) {
        val uri = Events.CONTENT_EXCEPTION_URI.buildUpon().appendPath(eventId.toString()).build()
        cr.insert(uri, ContentValues().apply { put(Events.ORIGINAL_INSTANCE_TIME, begin); put(Events.STATUS, Events.STATUS_CANCELED) })
    }

    companion object {
        const val LOCAL_ACCOUNT = "Chronicle"
        fun parseDuration(s: String?): Long {
            if (s.isNullOrBlank()) return 3_600_000L
            val m = Regex("P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").find(s) ?: return 3_600_000L
            val (w, d, h, mi, se) = m.destructured
            return ((w.toLongOrNull() ?: 0) * 604800 + (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (se.toLongOrNull() ?: 0)) * 1000
        }
    }
}

class TaskStore(context: Context) {
    private val file = File(context.filesDir, "tasks.json")
    fun all(): List<Task> = runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).map { Task.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun save(list: List<Task>) { val tmp = File(file.path + ".tmp"); tmp.writeText(JSONArray(list.map { it.toJson() }).toString()); tmp.renameTo(file) }
}

/* -------------------------------------------- iCalendar ------------------------------------------- */

object Ics {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    fun export(events: List<EventDraft>): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Wonderdesk//Chronicle//EN\r\n")
        events.forEach { e ->
            append("BEGIN:VEVENT\r\nUID:${e.id ?: UUID.randomUUID()}@chronicle.wonderdesk\r\nDTSTAMP:${stamp.format(Instant.now())}\r\n")
            if (e.allDay) append("DTSTART;VALUE=DATE:${day.format(Instant.ofEpochMilli(e.start))}\r\nDTEND;VALUE=DATE:${day.format(Instant.ofEpochMilli(e.end))}\r\n")
            else append("DTSTART:${stamp.format(Instant.ofEpochMilli(e.start))}\r\nDTEND:${stamp.format(Instant.ofEpochMilli(e.end))}\r\n")
            append("SUMMARY:${esc(e.title)}\r\n")
            if (e.location.isNotBlank()) append("LOCATION:${esc(e.location)}\r\n")
            if (e.notes.isNotBlank()) append("DESCRIPTION:${esc(e.notes)}\r\n")
            if (e.rrule.isNotBlank()) append("RRULE:${e.rrule}\r\n")
            e.reminders.forEach { append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-PT${it}M\r\nEND:VALARM\r\n") }
            append("END:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    /** Events from an .ics file. Times without a zone are read as local time. */
    fun parse(text: String): List<EventDraft> {
        val lines = text.replace("\r\n ", "").replace("\n ", "").replace("\r\n\t", "").lines()
        val out = mutableListOf<EventDraft>()
        var cur: MutableMap<String, String>? = null
        val alarms = mutableListOf<Int>()
        var inAlarm = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            when {
                line == "BEGIN:VEVENT" -> { cur = mutableMapOf(); alarms.clear() }
                line == "BEGIN:VALARM" -> inAlarm = true
                line == "END:VALARM" -> inAlarm = false
                line == "END:VEVENT" -> {
                    val m = cur ?: continue
                    val (start, allDay) = parseTime(m["DTSTART"] ?: continue, m["DTSTART;"] ?: "")
                    val end = m["DTEND"]?.let { parseTime(it, m["DTEND;"] ?: "").first } ?: (start + if (allDay) 86_400_000L else 3_600_000L)
                    out += EventDraft(title = unesc(m["SUMMARY"] ?: "Event"), allDay = allDay, start = start, end = end,
                        location = unesc(m["LOCATION"] ?: ""), notes = unesc(m["DESCRIPTION"] ?: ""), rrule = m["RRULE"] ?: "", reminders = alarms.toList().ifEmpty { listOf(15) })
                    cur = null
                }
                cur != null && inAlarm && line.startsWith("TRIGGER") -> {
                    val v = line.substringAfter(':')
                    Regex("-?P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?").find(v)?.destructured?.let { (d, h, mi) ->
                        alarms += (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (mi.toIntOrNull() ?: 0)
                    }
                }
                cur != null && !inAlarm && line.contains(':') -> {
                    val keyPart = line.substringBefore(':'); val value = line.substringAfter(':')
                    val key = keyPart.substringBefore(';')
                    cur[key] = value
                    if (keyPart.contains(';')) cur["$key;"] = keyPart.substringAfter(';')
                }
            }
        }
        return out
    }

    private fun parseTime(v: String, params: String): Pair<Long, Boolean> {
        if (params.contains("VALUE=DATE") || v.length == 8) {
            val d = LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE)
            return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        }
        val local = LocalDateTime.parse(v.removeSuffix("Z").take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val zone = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            params.contains("TZID=") -> runCatching { ZoneId.of(params.substringAfter("TZID=").substringBefore(';')) }.getOrDefault(ZoneId.systemDefault())
            else -> ZoneId.systemDefault()
        }
        return local.atZone(zone).toInstant().toEpochMilli() to false
    }
}

fun countdown(now: Long, begin: Long, end: Long): String {
    if (now in begin until end) return "Happening now"
    val diff = begin - now
    val past = diff < 0
    val m = kotlin.math.abs(diff) / 60_000
    val text = when {
        m < 1 -> return if (past) "Just ended" else "Starting now"
        m < 60 -> "$m min"
        m < 60 * 24 -> "${m / 60} h ${m % 60} min".replace(" 0 min", "")
        m < 60 * 24 * 60 -> "${m / 1440} day${if (m / 1440 == 1L) "" else "s"}"
        else -> "${m / 43200} months"
    }
    return if (past) "$text ago" else "in $text"
}

fun rruleLabel(r: String): String = when {
    r.isBlank() -> "Does not repeat"
    r.startsWith("FREQ=DAILY") -> "Every day"
    r.startsWith("FREQ=WEEKLY") && r.contains("BYDAY=MO,TU,WE,TH,FR") -> "Every weekday"
    r.startsWith("FREQ=WEEKLY;INTERVAL=2") -> "Every two weeks"
    r.startsWith("FREQ=WEEKLY") -> "Every week"
    r.startsWith("FREQ=MONTHLY") -> "Every month"
    r.startsWith("FREQ=YEARLY") -> "Every year"
    else -> "Custom repeat"
}

fun reminderLabel(m: Int): String = when {
    m == 0 -> "At start"
    m < 60 -> "$m min before"
    m < 1440 -> "${m / 60} h before"
    m % 10080 == 0 -> "${m / 10080} week${if (m / 10080 > 1) "s" else ""} before"
    else -> "${m / 1440} day${if (m / 1440 > 1) "s" else ""} before"
}

fun Context.eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
