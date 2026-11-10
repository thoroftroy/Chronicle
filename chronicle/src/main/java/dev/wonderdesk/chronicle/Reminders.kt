package dev.wonderdesk.chronicle

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

object Notify {
    const val EVENTS = "chronicle-events"
    const val TASKS = "chronicle-tasks"

    fun channels(c: Context) {
        val m = c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(EVENTS, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders for calendar events" })
        m.createNotificationChannel(NotificationChannel(TASKS, "Task due dates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a task is due" })
    }

    fun enabled(c: Context) = c.getSharedPreferences("chronicle", Context.MODE_PRIVATE).getBoolean("reminders", true)

    fun openApp(c: Context, code: Int, eventId: Long = -1) = PendingIntent.getActivity(c, code,
        Intent(c, MainActivity::class.java).putExtra("event", eventId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Schedules a due-time notification for every open task with a time. */
    fun scheduleTasks(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        TaskStore(c).all().forEach { t ->
            val pi = PendingIntent.getBroadcast(c, t.id.hashCode(), Intent(c, TaskDueReceiver::class.java).putExtra("id", t.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
            val at = if (t.dueHasTime) t.due else if (t.due > 0) LocalDate.ofEpochDay(t.due / 86_400_000L).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else 0L
            if (!t.done && at > System.currentTimeMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** The calendar provider broadcasts this when an event reminder is due. */
class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (!Notify.enabled(c)) return
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        val alarmTime = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        Notify.channels(c)
        val m = c.getSystemService(NotificationManager::class.java)
        runCatching {
            c.contentResolver.query(CalendarContract.CalendarAlerts.CONTENT_URI_BY_INSTANCE,
                arrayOf(CalendarContract.CalendarAlerts.EVENT_ID, CalendarContract.CalendarAlerts.TITLE, CalendarContract.CalendarAlerts.BEGIN,
                    CalendarContract.CalendarAlerts.EVENT_LOCATION, CalendarContract.CalendarAlerts.ALL_DAY),
                "${CalendarContract.CalendarAlerts.ALARM_TIME} = ?", arrayOf(alarmTime.toString()), null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0); val begin = cur.getLong(2)
                    val title = cur.getString(1)?.ifBlank { null } ?: "Event"
                    val whenText = if (cur.getInt(4) == 1) "Today" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(begin)) + " · " + countdown(System.currentTimeMillis(), begin, begin + 1)
                    val where = cur.getString(3).orEmpty()
                    m.notify((id * 31 + begin).toInt(), Notification.Builder(c, Notify.EVENTS).setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                        .setContentTitle(title).setContentText(listOf(whenText, where).filter { it.isNotBlank() }.joinToString(" · "))
                        .setCategory(Notification.CATEGORY_EVENT).setWhen(begin).setShowWhen(true)
                        .setContentIntent(Notify.openApp(c, id.toInt(), id)).setAutoCancel(true).build())
                }
            }
        }
        ChronicleWidget.refresh(c)
    }
}

class TaskDueReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val t = TaskStore(c).all().find { it.id == id && !it.done } ?: return
        Notify.channels(c)
        c.getSystemService(NotificationManager::class.java).notify(id.hashCode(), Notification.Builder(c, Notify.TASKS)
            .setSmallIcon(android.R.drawable.checkbox_on_background).setContentTitle(t.title).setContentText("Due now")
            .setContentIntent(Notify.openApp(c, id.hashCode())).setAutoCancel(true).build())
        ChronicleWidget.refresh(c)
    }
}

class ChronicleBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) { Notify.scheduleTasks(c); ChronicleWidget.refresh(c) }
}

/** Home-screen widget: today's date, then the next events and tasks with countdowns. */
class ChronicleWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { mgr.updateAppWidget(it, build(c)) } }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action == Intent.ACTION_PROVIDER_CHANGED || intent.action == Intent.ACTION_DATE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) refresh(c)
    }

    companion object {
        private val ROWS = listOf(R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4, R.id.w_row5)
        private val TITLES = listOf(R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4, R.id.w_t5)
        private val TIMES = listOf(R.id.w_s1, R.id.w_s2, R.id.w_s3, R.id.w_s4, R.id.w_s5)
        private val DOTS = listOf(R.id.w_d1, R.id.w_d2, R.id.w_d3, R.id.w_d4, R.id.w_d5)

        fun refresh(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, ChronicleWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, build(c)) }
        }

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_upcoming)
            val today = LocalDate.now()
            v.setTextViewText(R.id.w_day, today.dayOfMonth.toString())
            v.setTextViewText(R.id.w_month, today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM")))
            val now = System.currentTimeMillis()
            data class Item(val title: String, val sub: String, val color: Int, val at: Long)
            val items = mutableListOf<Item>()
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                CalendarRepo(c).occurrences(now, now + 14 * 86_400_000L).filter { it.end > now }.take(5).forEach { o ->
                    val time = if (o.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(o.begin))
                    val d = o.startsOn(ZoneId.systemDefault())
                    val dayText = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) }
                    items += Item(o.title, "$dayText $time · ${countdown(now, o.begin, o.end)}", o.color, o.begin)
                }
            }
            TaskStore(c).all().filter { !it.done && it.due > 0 }.sortedBy { it.due }.take(3).forEach { t ->
                items += Item("☐ " + t.title, "Task due " + LocalDate.ofEpochDay(t.due / 86_400_000L).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), 0xFF2E9E44.toInt(), t.due)
            }
            val shown = items.sortedBy { it.at }.take(5)
            ROWS.forEachIndexed { i, row ->
                val it = shown.getOrNull(i)
                v.setViewVisibility(row, if (it == null) android.view.View.GONE else android.view.View.VISIBLE)
                if (it != null) { v.setTextViewText(TITLES[i], it.title); v.setTextViewText(TIMES[i], it.sub); v.setInt(DOTS[i], "setColorFilter", it.color) }
            }
            v.setViewVisibility(R.id.w_empty, if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
            v.setOnClickPendingIntent(R.id.w_root, Notify.openApp(c, 9))
            v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(c, 10, Intent(c, MainActivity::class.java).putExtra("new", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}
