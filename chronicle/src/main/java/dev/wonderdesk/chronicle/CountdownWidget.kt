package dev.wonderdesk.chronicle

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** What one countdown widget shows. [eventId] null = whatever is next. */
data class CountdownConfig(val eventId: Long? = null, val label: String = "", val style: String = "auto", val color: Int = 0xFFE5483C.toInt(), val showDate: Boolean = true) {
    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("countdown-widgets", Context.MODE_PRIVATE)
        fun load(c: Context, id: Int): CountdownConfig = prefs(c).let { p ->
            CountdownConfig(p.getLong("$id.event", -1L).takeIf { it > 0 }, p.getString("$id.label", "") ?: "", p.getString("$id.style", "auto") ?: "auto",
                p.getInt("$id.color", 0xFFE5483C.toInt()), p.getBoolean("$id.date", true))
        }
        fun save(c: Context, id: Int, cfg: CountdownConfig) = prefs(c).edit().apply {
            if (cfg.eventId != null) putLong("$id.event", cfg.eventId) else remove("$id.event")
            putString("$id.label", cfg.label); putString("$id.style", cfg.style); putInt("$id.color", cfg.color); putBoolean("$id.date", cfg.showDate)
        }.apply()
        fun delete(c: Context, id: Int) = prefs(c).edit().apply { listOf("event", "label", "style", "color", "date").forEach { remove("$id.$it") } }.apply()
    }
}

val COUNTDOWN_COLORS = listOf(0xFFE5483C, 0xFF1E7FE8, 0xFF2E9E44, 0xFF8E44AD, 0xFFE67E22, 0xFF1B1D21).map { it.toInt() }

/**
 * Countdown to one event you choose (a wedding, a trip, a birthday) or simply the next thing in your calendar.
 * Shows days for far-off events and ticks live in the last 24 hours; after the event it counts the days since.
 */
class CountdownWidget : AppWidgetProvider() {
    companion object {
        const val PINNED = "dev.wonderdesk.chronicle.COUNTDOWN_PINNED"

        fun refresh(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            mgr.getAppWidgetIds(ComponentName(c, CountdownWidget::class.java)).forEach { id -> runCatching { mgr.updateAppWidget(id, build(c, id)) } }
        }

        /** Asks the home screen to add a countdown for [eventId] (Android shows its own "Add to home screen?" prompt). */
        fun pin(c: Context, eventId: Long, title: String): Boolean {
            val mgr = AppWidgetManager.getInstance(c)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val cb = PendingIntent.getBroadcast(c, eventId.toInt(), Intent(c, CountdownWidget::class.java).setAction(PINNED).putExtra("event", eventId).putExtra("label", ""),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            return runCatching { mgr.requestPinAppWidget(ComponentName(c, CountdownWidget::class.java), null, cb) }.getOrDefault(false)
        }

        private fun target(c: Context, cfg: CountdownConfig): Occurrence? {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
            val now = System.currentTimeMillis(); val repo = CalendarRepo(c)
            if (cfg.eventId == null) return repo.occurrences(now, now + 365L * 86_400_000L).filter { it.end > now }.minByOrNull { if (it.begin > now) it.begin else now }
            // A chosen event: its next occurrence (for repeating ones), or the last one if it's over.
            val ahead = repo.occurrences(now - 86_400_000L, now + 5 * 365L * 86_400_000L).filter { it.eventId == cfg.eventId && it.end > now }.minByOrNull { it.begin }
            return ahead ?: repo.occurrences(now - 10 * 365L * 86_400_000L, now).filter { it.eventId == cfg.eventId }.maxByOrNull { it.begin }
        }

        fun amount(ms: Long, style: String): String {
            val days = ms / 86_400_000L; val hours = (ms % 86_400_000L) / 3_600_000L; val mins = (ms % 3_600_000L) / 60_000L
            return when (style) {
                "days" -> if (days == 1L) "1 day" else "$days days"
                "weeks" -> if (days >= 14) "${days / 7} wk ${days % 7} d" else "$days d $hours h"
                "dhm" -> if (days > 0) "$days d $hours h" else "$hours h $mins m"
                else -> when { days >= 7 -> "$days days"; days >= 1 -> "$days d $hours h"; else -> "$hours h $mins m" }
            }
        }

        fun build(c: Context, id: Int): RemoteViews {
            val cfg = CountdownConfig.load(c, id)
            val v = RemoteViews(c.packageName, R.layout.widget_countdown)
            val now = System.currentTimeMillis()
            val next = runCatching { target(c, cfg) }.getOrNull()
            v.setTextColor(R.id.w_big, cfg.color); v.setTextColor(R.id.w_chrono, cfg.color)
            if (next == null) {
                v.setTextViewText(R.id.w_label, cfg.label.ifBlank { "COUNTDOWN" })
                v.setTextViewText(R.id.w_title, if (cfg.eventId != null) "Event not found" else "Nothing coming up"); v.setTextViewText(R.id.w_big, "—")
                v.setTextViewText(R.id.w_when, if (cfg.eventId != null) "Hold the widget to choose another event" else "Add an event in Chronicle")
                v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE)
            } else {
                v.setTextViewText(R.id.w_title, next.title.ifBlank { "Event" })
                val left = next.begin - now
                v.setTextViewText(R.id.w_when, if (!cfg.showDate) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(next.begin)).let { if (next.allDay) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(next.begin + java.util.TimeZone.getDefault().getOffset(next.begin).let { -it.toLong() })) else it } + if (next.location.isNotBlank()) " · ${next.location}" else "")
                v.setViewVisibility(R.id.w_when, if (cfg.showDate) View.VISIBLE else View.GONE)
                val label = cfg.label.ifBlank { if (cfg.eventId == null) "NEXT UP" else "COUNTDOWN" }
                when {
                    next.end <= now -> { v.setTextViewText(R.id.w_label, label); v.setTextViewText(R.id.w_big, amount(now - next.end, "days") + " ago"); v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE) }
                    left <= 0 -> { v.setTextViewText(R.id.w_label, label); v.setTextViewText(R.id.w_big, "Today!"); v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE) }
                    left < 86_400_000L && cfg.style != "days" -> {
                        v.setTextViewText(R.id.w_label, label)
                        // Ticks by itself every second without waking the app.
                        v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + left, null, true)
                        v.setChronometerCountDown(R.id.w_chrono, true)
                        v.setViewVisibility(R.id.w_chrono, View.VISIBLE); v.setViewVisibility(R.id.w_big, View.GONE)
                    }
                    else -> { v.setTextViewText(R.id.w_label, label); v.setTextViewText(R.id.w_big, amount(left, cfg.style)); v.setViewVisibility(R.id.w_chrono, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE) }
                }
            }
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 2100 + id, Intent(c, MainActivity::class.java).apply { next?.let { putExtra("event", it.eventId) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { id -> runCatching { mgr.updateAppWidget(id, build(c, id)) } } }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { CountdownConfig.delete(c, it) } }
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == PINNED) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val ev = intent.getLongExtra("event", -1L)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && ev > 0) { CountdownConfig.save(c, id, CountdownConfig(eventId = ev, label = intent.getStringExtra("label").orEmpty())); refresh(c) }
            return
        }
        super.onReceive(c, intent)
        if (intent.action == Intent.ACTION_PROVIDER_CHANGED || intent.action == Intent.ACTION_DATE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) refresh(c)
    }
}

/** Chooses what a countdown widget counts down to and how it looks. Opens when the widget is added, and from the launcher's Edit. */
class CountdownConfigActivity : WonderActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(savedInstanceState: Bundle?) {
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        super.onCreate(savedInstanceState)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) finish()
    }

    @Composable override fun Content() = CountdownSetup(widgetId) { cfg ->
        CountdownConfig.save(this, widgetId, cfg)
        runCatching { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, CountdownWidget.build(this, widgetId)) }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
    }
}

@Composable
private fun CountdownSetup(widgetId: Int, onDone: (CountdownConfig) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var cfg by remember { mutableStateOf(CountdownConfig.load(context, widgetId)) }
    var q by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<Occurrence>?>(null) }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        events = if (!granted) emptyList() else withContext(Dispatchers.IO) {
            runCatching { CalendarRepo(context).occurrences(now - 86_400_000L, now + 3 * 365L * 86_400_000L).filter { it.end > now }.distinctBy { it.eventId }.sortedBy { it.begin }.take(400) }.getOrDefault(emptyList())
        }
    }
    WonderApp(appName = "Chronicle", about = "Countdown widget", title = "Countdown widget", onBack = { (context as? Activity)?.finish() },
        actions = { TextButton(onClick = { onDone(cfg) }) { Text("Done", color = p.toolbarText, fontWeight = FontWeight.SemiBold) } }) {
        val shown = events.orEmpty().filter { q.isBlank() || it.title.contains(q, true) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Panel(title = "Look", footer = "Counts down to the day and time, ticks live in the last 24 hours and counts the days since once it's over.") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(cfg.label, { cfg = cfg.copy(label = it.take(24)) }, "Label (optional)", supporting = "Shown above the event name, like OUR WEDDING")
                        Text("Show time left as", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("auto" to "Automatic", "days" to "Days", "weeks" to "Weeks", "dhm" to "Days + hours").forEach { (k, n) -> Chip(n, cfg.style == k, { cfg = cfg.copy(style = k) }) }
                        }
                        Text("Colour", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            COUNTDOWN_COLORS.forEach { col ->
                                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(col)).then(if (cfg.color == col) Modifier.border(3.dp, p.accent, CircleShape) else Modifier).clickable { cfg = cfg.copy(color = col) })
                            }
                        }
                    }
                    PanelDivider()
                    SwitchRow("Show date and place", cfg.showDate, { cfg = cfg.copy(showDate = it) })
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel("Count down to", Modifier.padding(start = 6.dp, bottom = 6.dp))
                Panel {
                    ListRow("Whatever's next", subtitle = "Always the next event in your calendars", icon = WonderIcons.Schedule, tint = Color(0xFF1E7FE8),
                        trailing = { RadioButton(cfg.eventId == null, { cfg = cfg.copy(eventId = null) }) }, onClick = { cfg = cfg.copy(eventId = null) })
                }
                Spacer(Modifier.height(12.dp))
                SearchField(q, { q = it }, "Find an event")
                Spacer(Modifier.height(8.dp))
                if (!granted) Text("Allow calendar access in Chronicle to pick an event.", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
                else if (events == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (shown.isEmpty()) Text(if (q.isBlank()) "No upcoming events. Add one in Chronicle first." else "No events match “$q”.", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(shown, key = { i, o -> "${o.eventId}-$i" }) { i, o ->
                ListRow(o.title.ifBlank { "Event" }, Modifier.panelItem(i, shown.size),
                    subtitle = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(o.begin)) + " · " + CountdownWidget.amount((o.begin - System.currentTimeMillis()).coerceAtLeast(0), "auto"),
                    leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(o.color))) },
                    trailing = { RadioButton(cfg.eventId == o.eventId, { cfg = cfg.copy(eventId = o.eventId) }) }, onClick = { cfg = cfg.copy(eventId = o.eventId) })
            }
            item { Spacer(Modifier.height(16.dp)); WButton("Save widget", { onDone(cfg) }, Modifier.fillMaxWidth(), icon = WonderIcons.Check, kind = ButtonKind.Primary) }
        }
    }
}
