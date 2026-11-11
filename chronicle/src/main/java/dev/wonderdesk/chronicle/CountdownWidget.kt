package dev.wonderdesk.chronicle

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

/** "Next up" countdown: days for far events, a live ticking countdown in the last 24 hours. */
class CountdownWidget : AppWidgetProvider() {
    companion object {
        fun refresh(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            mgr.getAppWidgetIds(ComponentName(c, CountdownWidget::class.java)).forEach { mgr.updateAppWidget(it, build(c)) }
        }

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_countdown)
            val now = System.currentTimeMillis()
            val next = if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                CalendarRepo(c).occurrences(now, now + 365L * 86_400_000L).filter { it.begin > now || it.end > now }.minByOrNull { if (it.begin > now) it.begin else now }
            else null
            if (next == null) {
                v.setTextViewText(R.id.w_title, "Nothing coming up"); v.setTextViewText(R.id.w_big, "—"); v.setTextViewText(R.id.w_when, "Add an event in Chronicle")
                v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE)
            } else {
                v.setTextViewText(R.id.w_title, next.title.ifBlank { "Event" })
                val left = next.begin - now
                v.setTextViewText(R.id.w_when, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(next.begin)) + if (next.location.isNotBlank()) " · ${next.location}" else "")
                when {
                    left <= 0 -> { v.setTextViewText(R.id.w_label, "HAPPENING NOW"); v.setTextViewText(R.id.w_big, "Now"); v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE) }
                    left < 86_400_000L -> {
                        // Ticks by itself every second without waking the app.
                        v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + left, null, true)
                        v.setChronometerCountDown(R.id.w_chrono, true)
                        v.setViewVisibility(R.id.w_chrono, View.VISIBLE); v.setViewVisibility(R.id.w_big, View.GONE)
                    }
                    else -> {
                        val days = (left / 86_400_000L).toInt(); val hours = ((left % 86_400_000L) / 3_600_000L).toInt()
                        v.setTextViewText(R.id.w_big, if (days >= 7) "$days days" else "$days d $hours h")
                        v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE)
                    }
                }
            }
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 21, Intent(c, MainActivity::class.java).apply { next?.let { putExtra("event", it.eventId) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { val v = build(c); ids.forEach { mgr.updateAppWidget(it, v) } }
    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action == Intent.ACTION_PROVIDER_CHANGED || intent.action == Intent.ACTION_DATE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) refresh(c)
    }
}
