package dev.wonderdesk.chronicle

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

class MainActivity : WonderActivity() {
    @Composable override fun Content() = ChronicleApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent }
    companion object { val incoming = mutableStateOf<Intent?>(null) }
}

private const val ABOUT = "Chronicle is your calendar. It shows and edits the calendars already on your phone, including Google and other synced accounts, " +
    "so changes sync back to them. It also keeps tasks with due dates, sends reminders, imports and exports .ics files, and has a home-screen widget."

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun LocalDate.startMs() = atStartOfDay(zone).toInstant().toEpochMilli()
private fun timeText(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Bumped whenever the calendar changes so every view re-reads. */
private val revision = mutableIntStateOf(0)

@Composable
fun ChronicleApp() {
    val context = LocalContext.current
    val repo = remember { CalendarRepo(context) }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCalendar(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasCalendar(context); revision.intValue++
    }
    var tab by rememberSaveable { mutableStateOf(context.prefs("chronicle").getString("tab", "month") ?: "month") }
    var selected by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val day = LocalDate.ofEpochDay(selected)
    var editing by remember { mutableStateOf<EventDraft?>(null) }
    var jump by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf<List<EventDraft>?>(null) }
    val tasks = remember { mutableStateListOf<Task>().apply { addAll(TaskStore(context).all()) } }
    fun saveTasks() { TaskStore(context).save(tasks.toList()); Notify.scheduleTasks(context); ChronicleWidget.refresh(context) }
    val toastHolder = remember { mutableStateOf<(String) -> Unit>({}) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { Ics.parse(context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()) }.getOrDefault(emptyList()) }
            if (list.isEmpty()) toastHolder.value("No events found in that file") else importing = list
        }
    }
    // Widget "+", reminder taps and .ics files opened from other apps.
    val incoming by MainActivity.incoming
    LaunchedEffect(incoming) {
        val intent = incoming ?: (context as ComponentActivity).intent
        MainActivity.incoming.value = null
        (context as ComponentActivity).intent = Intent()
        if (intent?.getBooleanExtra("new", false) == true) editing = newDraft(LocalDate.now(), repo)
        intent?.getLongExtra("event", -1L)?.takeIf { it > 0 }?.let { id -> repo.load(id)?.let { editing = it } }
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val list = withContext(Dispatchers.IO) { runCatching { Ics.parse(context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().readText()) }.getOrDefault(emptyList()) }
            if (list.isNotEmpty()) importing = list
        }
    }
    LaunchedEffect(granted) { if (granted) { Notify.channels(context); ChronicleWidget.refresh(context) } }

    val edit = editing
    if (edit != null) {
        EventEditor(edit, repo, onClose = { editing = null }, onSaved = { revision.intValue++; ChronicleWidget.refresh(context); editing = null })
        return
    }
    WonderApp(
        appName = "Chronicle", about = ABOUT,
        tabs = listOf(
            WonderTab("month", "Month", WonderIcons.CalendarMonth),
            WonderTab("day", "Day", WonderIcons.Today),
            WonderTab("agenda", "Agenda", WonderIcons.ViewList),
            WonderTab("tasks", "Tasks", WonderIcons.CheckCircle),
        ),
        selectedTab = tab, onTab = { tab = it; context.prefs("chronicle").edit().putString("tab", it).apply() },
        title = when (tab) {
            "day" -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
            "agenda" -> "Agenda"; "tasks" -> "Tasks"
            else -> day.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        },
        actions = {
            if (tab != "tasks") {
                TextButton(onClick = { selected = LocalDate.now().toEpochDay() }) { Text("Today", color = wonderPalette().toolbarText, style = MaterialTheme.typography.titleSmall) }
                ToolbarButton(WonderIcons.Event, "Go to date", { jump = true })
            }
            if (tab == "agenda") ToolbarButton(WonderIcons.Search, "Search events", { searching = !searching })
            ToolbarButton(WonderIcons.Add, if (tab == "tasks") "New task" else "New event", {
                if (tab == "tasks") { tasks.add(0, Task(title = "")); } else editing = newDraft(day, repo)
            })
        },
        menu = listOf(
            WonderMenuItem("Import .ics file", WonderIcons.UploadFile) { importer.launch(arrayOf("text/calendar", "text/*", "application/octet-stream")) },
            WonderMenuItem("Export this month", WonderIcons.Download) {
                scope.launch {
                    val ym = YearMonth.from(day)
                    val drafts = withContext(Dispatchers.IO) {
                        repo.occurrences(ym.atDay(1).startMs(), ym.plusMonths(1).atDay(1).startMs()).mapNotNull { repo.load(it.eventId) }.distinctBy { it.id }
                    }
                    val f = java.io.File(context.cacheDir, "share").apply { mkdirs() }.let { java.io.File(it, "Chronicle-${ym}.ics") }
                    f.writeText(Ics.export(drafts))
                    context.share(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f), "text/calendar") {}
                }
            },
        ),
        settings = { CalendarSettings(repo, granted) },
    ) {
        toastHolder.value = LocalToast.current
        if (!granted && tab != "tasks") {
            EmptyState("Connect your calendars", "Chronicle shows and edits the calendars on this phone, including Google Calendar, so everything stays in sync.",
                action = { WButton("Allow calendar access", { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) },
                    icon = WonderIcons.CalendarMonth, kind = ButtonKind.Primary) })
            return@WonderApp
        }
        val rev = revision.intValue
        when (tab) {
            "day" -> DayView(repo, day, rev, onDay = { selected = it.toEpochDay() }, onOpen = { o -> repo.load(o.eventId)?.let { editing = it } },
                onNewAt = { t -> editing = newDraft(day, repo, t) })
            "agenda" -> AgendaView(repo, day, rev, searching, tasks, onOpen = { o -> repo.load(o.eventId)?.let { editing = it } })
            "tasks" -> TasksView(tasks, ::saveTasks)
            else -> MonthView(repo, day, rev, tasks, onSelect = { selected = it.toEpochDay() }, onOpenDay = { selected = it.toEpochDay(); tab = "day" },
                onOpen = { o -> repo.load(o.eventId)?.let { editing = it } }, onNew = { editing = newDraft(day, repo) })
        }
    }
    if (jump) JumpDialog(day, onDismiss = { jump = false }) { selected = it.toEpochDay(); jump = false }
    importing?.let { list ->
        ConfirmDialog("Import ${list.size} event${if (list.size == 1) "" else "s"}?", list.take(5).joinToString("\n") { "• " + it.title } + if (list.size > 5) "\n…" else "",
            "Import", onConfirm = {
                scope.launch(Dispatchers.IO) {
                    val cal = repo.calendars().firstOrNull { it.writable && it.visible }?.id ?: -1
                    list.forEach { runCatching { repo.save(it.copy(calendarId = cal)) } }
                    withContext(Dispatchers.Main) { revision.intValue++; toastHolder.value("Imported ${list.size} events") }
                }
            }, onDismiss = { importing = null })
    }
}

private fun hasCalendar(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun newDraft(day: LocalDate, repo: CalendarRepo, time: LocalTime? = null): EventDraft {
    val now = LocalTime.now()
    val t = time ?: LocalTime.of((now.hour + 1).coerceAtMost(23), 0)
    val start = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
    val defaultCal = repo.calendars().filter { it.writable && it.visible }.let { cals -> cals.firstOrNull { it.account.contains("@") } ?: cals.firstOrNull() }
    return EventDraft(calendarId = defaultCal?.id ?: -1, start = start, end = start + 3_600_000L)
}

@Composable
private fun rememberOccurrences(repo: CalendarRepo, from: Long, to: Long, rev: Int, query: String? = null): List<Occurrence> {
    var list by remember { mutableStateOf<List<Occurrence>>(emptyList()) }
    LaunchedEffect(from, to, rev, query) { list = withContext(Dispatchers.IO) { repo.occurrences(from, to, query) } }
    return list
}

/* ------------------------------------------------ month ------------------------------------------- */

@Composable
private fun MonthView(repo: CalendarRepo, selected: LocalDate, rev: Int, tasks: List<Task>, onSelect: (LocalDate) -> Unit, onOpenDay: (LocalDate) -> Unit, onOpen: (Occurrence) -> Unit, onNew: () -> Unit) {
    val p = wonderPalette()
    val ym = YearMonth.from(selected)
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val gridStart = ym.atDay(1).let { d -> d.minusDays(((d.dayOfWeek.value - firstDow.value + 7) % 7).toLong()) }
    val cells = (0 until 42).map { gridStart.plusDays(it.toLong()) }
    val occ = rememberOccurrences(repo, cells.first().startMs(), cells.last().plusDays(1).startMs(), rev)
    val today = LocalDate.now()
    val now = rememberNow(60_000)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 8.dp).pointerInput(ym) {
            var dx = 0f
            detectHorizontalDragGestures(onDragEnd = { if (dx > 120) onSelect(selected.minusMonths(1)) else if (dx < -120) onSelect(selected.plusMonths(1)); dx = 0f }) { _, d -> dx += d }
        }) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSelect(selected.minusMonths(1)) }) { Icon(WonderIcons.NavigateBefore, "Previous month", tint = p.accent) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onSelect(selected.plusMonths(1)) }) { Icon(WonderIcons.NavigateNext, "Next month", tint = p.accent) }
            }
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).map { firstDow.plus(it.toLong()) }.forEach { d ->
                    Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) p.secondaryText.copy(alpha = .7f) else p.secondaryText)
                }
            }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { d ->
                        val inMonth = d.month == ym.month
                        val isSel = d == selected
                        val isToday = d == today
                        val dayOcc = occ.filter { it.covers(d, zone) }
                        val hasTask = tasks.any { !it.done && it.due > 0 && LocalDate.ofEpochDay(it.due / 86_400_000L) == d }
                        Column(Modifier.weight(1f).height(54.dp).padding(2.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) p.accent.copy(alpha = .14f) else Color.Transparent)
                            .clickableWithDouble(onClick = { onSelect(d) }, onDouble = { onOpenDay(d) }),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.padding(top = 4.dp).size(26.dp).clip(CircleShape).background(if (isToday) Color(0xFFE5483C) else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal),
                                    color = when { isToday -> Color.White; !inMonth -> p.secondaryText.copy(alpha = .45f); else -> p.text })
                            }
                            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                dayOcc.take(3).forEach { o -> Box(Modifier.size(6.dp).clip(CircleShape).background(Color(o.color))) }
                                if (hasTask) Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp)).background(p.success))
                            }
                        }
                    }
                }
            }
        }
        val dayOcc = occ.filter { it.covers(selected, zone) }
        val dayTasks = tasks.filter { !it.done && it.due > 0 && LocalDate.ofEpochDay(it.due / 86_400_000L) == selected }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)) {
                    SectionLabel(when (selected) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"; else -> selected.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) }, Modifier.weight(1f))
                    Text(daysAwayText(selected, today), style = MaterialTheme.typography.labelMedium, color = p.secondaryText)
                }
            }
            if (dayOcc.isEmpty() && dayTasks.isEmpty()) item {
                Panel { ListRow("Nothing planned", subtitle = "Tap + to add an event. Double-tap a date to see its timeline.", icon = WonderIcons.EventNote, tint = Color(0xFF7A7F87), onClick = onNew) }
            }
            itemsIndexed(dayOcc) { i, o -> OccurrenceRow(o, now, Modifier.panelItem(i, dayOcc.size + dayTasks.size)) { onOpen(o) } }
            itemsIndexed(dayTasks) { i, t -> ListRow(t.title.ifBlank { "Untitled task" }, Modifier.panelItem(dayOcc.size + i, dayOcc.size + dayTasks.size), subtitle = "Task due",
                icon = WonderIcons.CheckCircle, tint = p.success) }
        }
    }
}

private fun daysAwayText(d: LocalDate, today: LocalDate): String {
    val n = java.time.temporal.ChronoUnit.DAYS.between(today, d)
    return when { n == 0L -> ""; n == 1L -> "in 1 day"; n == -1L -> "1 day ago"; n > 0 -> "in $n days"; else -> "${-n} days ago" }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.clickableWithDouble(onClick: () -> Unit, onDouble: () -> Unit) = this.then(
    Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onDoubleTap = { onDouble() }) })

@Composable
private fun OccurrenceRow(o: Occurrence, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val p = wonderPalette()
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Color(o.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (o.end < now) p.secondaryText else p.text)
            Text(listOf(if (o.allDay) "All day" else "${timeText(o.begin)} – ${timeText(o.end)}", o.location).filter { it.isNotBlank() }.joinToString(" · ") + if (o.recurring) "  ↻" else "",
                style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(countdown(now, o.begin, o.end), style = MaterialTheme.typography.labelMedium, color = if (now in o.begin until o.end) p.success else p.accent)
    }
}

/* -------------------------------------------------- day ------------------------------------------- */

@Composable
private fun DayView(repo: CalendarRepo, day: LocalDate, rev: Int, onDay: (LocalDate) -> Unit, onOpen: (Occurrence) -> Unit, onNewAt: (LocalTime) -> Unit) {
    val p = wonderPalette()
    val occ = rememberOccurrences(repo, day.startMs(), day.plusDays(1).startMs(), rev)
    val allDay = occ.filter { it.allDay || (it.begin < day.startMs() && it.end > day.plusDays(1).startMs()) }
    val timed = occ - allDay.toSet()
    val hour = 56.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(day) { scroll.scrollTo(with(density) { (hour * (if (day == LocalDate.now()) (LocalTime.now().hour - 1).coerceAtLeast(0) else 7)).roundToPx() }) }
    Column(Modifier.fillMaxSize()) {
        // week strip
        val weekStart = day.minusDays(((day.dayOfWeek.value - WeekFields.of(Locale.getDefault()).firstDayOfWeek.value + 7) % 7).toLong())
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDay(day.minusWeeks(1)) }) { Icon(WonderIcons.NavigateBefore, "Previous week", tint = p.accent) }
            (0 until 7).map { weekStart.plusDays(it.toLong()) }.forEach { d ->
                val on = d == day; val today = d == LocalDate.now()
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) p.accent else Color.Transparent).clickable { onDay(d) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = if (on) p.onAccent else p.secondaryText)
                    Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (today) FontWeight.Bold else FontWeight.Normal),
                        color = if (on) p.onAccent else if (today) Color(0xFFE5483C) else p.text)
                }
            }
            IconButton(onClick = { onDay(day.plusWeeks(1)) }) { Icon(WonderIcons.NavigateNext, "Next week", tint = p.accent) }
        }
        if (allDay.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            allDay.forEach { o -> Text(o.title, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(o.color).copy(alpha = .85f)).clickable { onOpen(o) }.padding(8.dp, 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
        Box(Modifier.fillMaxSize().verticalScroll(scroll).pointerInput(day) {
            var dx = 0f
            detectHorizontalDragGestures(onDragEnd = { if (dx > 120) onDay(day.minusDays(1)) else if (dx < -120) onDay(day.plusDays(1)); dx = 0f }) { _, d -> dx += d }
        }) {
            Column {
                (0 until 24).forEach { h ->
                    Row(Modifier.fillMaxWidth().height(hour).clickable { onNewAt(LocalTime.of(h, 0)) }) {
                        Text(LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h a")),
                            Modifier.width(56.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
                        HorizontalDivider(Modifier.padding(top = 7.dp), color = p.separator)
                    }
                }
            }
            // events laid out in columns when they overlap
            val sorted = timed.sortedBy { it.begin }
            val columns = mutableListOf<MutableList<Occurrence>>()
            sorted.forEach { o -> (columns.firstOrNull { it.last().end <= o.begin } ?: mutableListOf<Occurrence>().also { columns += it }).add(o) }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 60.dp, end = 8.dp)) {
                val colW = maxWidth / columns.size.coerceAtLeast(1)
                columns.forEachIndexed { ci, col ->
                    col.forEach { o ->
                        val startMin = ((o.begin - day.startMs()) / 60_000f).coerceAtLeast(0f)
                        val endMin = ((o.end - day.startMs()) / 60_000f).coerceAtMost(1440f)
                        Column(Modifier.offset(x = colW * ci, y = hour * (startMin / 60f) + 8.dp).width(colW - 2.dp).height((hour * ((endMin - startMin) / 60f)).coerceAtLeast(24.dp))
                            .clip(RoundedCornerShape(6.dp)).background(Color(o.color).copy(alpha = .88f)).clickable { onOpen(o) }.padding(6.dp, 3.dp)) {
                            Text(o.title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${timeText(o.begin)} – ${timeText(o.end)}", color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                if (day == LocalDate.now()) {
                    val m = LocalTime.now().let { it.hour * 60 + it.minute }
                    Box(Modifier.offset(y = hour * (m / 60f) + 8.dp).fillMaxWidth().height(2.dp).background(Color(0xFFE5483C)))
                }
            }
        }
    }
}

/* ------------------------------------------------ agenda ------------------------------------------ */

@Composable
private fun AgendaView(repo: CalendarRepo, from: LocalDate, rev: Int, searching: Boolean, tasks: List<Task>, onOpen: (Occurrence) -> Unit) {
    val p = wonderPalette()
    var query by rememberSaveable { mutableStateOf("") }
    var past by rememberSaveable { mutableStateOf(false) }
    val start = if (past) from.minusDays(90) else from
    val end = if (past) from.plusDays(1) else from.plusDays(120)
    val occ = rememberOccurrences(repo, start.startMs(), end.startMs(), rev, query.takeIf { searching && it.isNotBlank() })
    val now = System.currentTimeMillis()
    val byDay = occ.groupBy { it.startsOn(zone) }.toSortedMap(if (past) compareByDescending { it } else compareBy { it })
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (searching) SearchField(query, { query = it }, "Search titles, places and notes")
            Segmented(listOf("Upcoming", "Past"), if (past) 1 else 0) { past = it == 1 }
        }
        if (byDay.isEmpty()) EmptyState(if (query.isNotBlank()) "No matches" else "Nothing here", if (past) "No events in the last 90 days." else "No events in the next four months.", icon = WonderIcons.EventNote)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            byDay.forEach { (d, list) ->
                item(key = "h$d") {
                    Row(Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = if (d == LocalDate.now()) Color(0xFFE5483C) else p.text)
                        Spacer(Modifier.width(8.dp))
                        Text(d.format(DateTimeFormatter.ofPattern("EEEE, MMMM yyyy")), style = MaterialTheme.typography.labelLarge, color = p.secondaryText, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                itemsIndexed(list, key = { _, o -> "${o.eventId}-${o.begin}" }) { i, o -> OccurrenceRow(o, now, Modifier.panelItem(i, list.size)) { onOpen(o) } }
            }
        }
    }
}

/* ------------------------------------------------ tasks ------------------------------------------- */

@Composable
private fun TasksView(tasks: MutableList<Task>, save: () -> Unit) {
    val p = wonderPalette()
    var showDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(tasks.firstOrNull()?.id) { tasks.firstOrNull()?.let { if (it.title.isEmpty()) editing = it } }
    val today = LocalDate.now()
    fun dueDay(t: Task) = if (t.due > 0) LocalDate.ofEpochDay(t.due / 86_400_000L) else null
    val open = tasks.filter { !it.done && it.title.isNotEmpty() }
    val groups = listOf(
        "Overdue" to open.filter { dueDay(it)?.isBefore(today) == true },
        "Today" to open.filter { dueDay(it) == today },
        "Upcoming" to open.filter { dueDay(it)?.isAfter(today) == true }.sortedBy { it.due },
        "Someday" to open.filter { it.due == 0L },
    ).filter { it.second.isNotEmpty() }
    val done = tasks.filter { it.done }.sortedByDescending { it.completedAt }
    if (open.isEmpty() && done.isEmpty() && editing == null) {
        EmptyState("No tasks", "Keep a to-do list with due dates. Tasks also show up in your month view and widget.", icon = WonderIcons.CheckCircle,
            action = { WButton("New task", { tasks.add(0, Task()); editing = tasks[0] }, icon = WonderIcons.Add, kind = ButtonKind.Primary) })
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        groups.forEach { (name, list) ->
            item { SectionLabel(name, Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp)) }
            itemsIndexed(list.sortedByDescending { it.priority }, key = { _, t -> t.id }) { i, t ->
                TaskRow(t, Modifier.panelItem(i, list.size), overdue = name == "Overdue", onToggle = {
                    val idx = tasks.indexOfFirst { it.id == t.id }; tasks[idx] = t.copy(done = true, completedAt = System.currentTimeMillis()); save()
                }, onClick = { editing = t })
            }
        }
        if (done.isNotEmpty()) {
            item { Row(Modifier.padding(top = 14.dp)) { WButton(if (showDone) "Hide completed" else "Show completed (${done.size})", { showDone = !showDone }, kind = ButtonKind.Plain) } }
            if (showDone) itemsIndexed(done, key = { _, t -> "d" + t.id }) { i, t ->
                TaskRow(t, Modifier.panelItem(i, done.size), overdue = false, onToggle = {
                    val idx = tasks.indexOfFirst { it.id == t.id }; tasks[idx] = t.copy(done = false); save()
                }, onClick = { editing = t })
            }
        }
    }
    editing?.let { t -> TaskEditor(t, onDismiss = {
        if (t.title.isEmpty()) tasks.removeAll { it.id == t.id && it.title.isEmpty() }
        editing = null
    }, onSave = { n -> val i = tasks.indexOfFirst { it.id == n.id }; if (i >= 0) tasks[i] = n else tasks.add(0, n); save(); editing = null },
        onDelete = { tasks.removeAll { it.id == t.id }; save(); editing = null }) }
}

@Composable
private fun TaskRow(t: Task, modifier: Modifier, overdue: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val p = wonderPalette()
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (t.done) p.success else if (t.priority > 0) p.danger else p.panelBorder, CircleShape)
            .background(if (t.done) p.success else Color.Transparent).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            if (t.done) Icon(WonderIcons.Check, "Done", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (t.done) p.secondaryText else p.text,
                textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            val due = if (t.due > 0) {
                val d = LocalDate.ofEpochDay(t.due / 86_400_000L)
                d.format(DateTimeFormatter.ofPattern("EEE d MMM")) + if (t.dueHasTime) " " + timeText(t.due) else ""
            } else ""
            if (due.isNotEmpty() || t.notes.isNotEmpty()) Text(listOf(due, t.notes.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (overdue) p.danger else p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.priority > 0) Icon(WonderIcons.Flag, "Important", tint = p.danger, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TaskEditor(t: Task, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(t.title) }
    var notes by remember { mutableStateOf(t.notes) }
    var due by remember { mutableLongStateOf(t.due) }
    var hasTime by remember { mutableStateOf(t.dueHasTime) }
    var important by remember { mutableStateOf(t.priority > 0) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    fun setDay(d: LocalDate?) { due = if (d == null) 0L else if (hasTime) d.atTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(due), zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli() else d.toEpochDay() * 86_400_000L }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (t.title.isEmpty()) "New task" else "Edit task") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(title, { title = it }, "Task")
            Field(notes, { notes = it }, "Notes", singleLine = false, minLines = 2)
            Text("Due", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dd = if (due > 0) LocalDate.ofEpochDay(due / 86_400_000L) else null
                Chip("None", due == 0L, { setDay(null); hasTime = false })
                Chip("Today", dd == today, { setDay(today) }); Chip("Tomorrow", dd == today.plusDays(1), { setDay(today.plusDays(1)) })
                Chip("Next week", dd == today.plusWeeks(1), { setDay(today.plusWeeks(1)) })
                Chip(if (dd != null && dd !in listOf(today, today.plusDays(1), today.plusWeeks(1))) dd.format(DateTimeFormatter.ofPattern("d MMM")) else "Pick…", false, { pickDate = true }, WonderIcons.Event)
            }
            if (due > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(hasTime, { on ->
                    hasTime = on
                    val d = LocalDate.ofEpochDay(if (t.dueHasTime && !on) LocalDateTime.ofInstant(Instant.ofEpochMilli(due), zone).toLocalDate().toEpochDay() else due / 86_400_000L)
                    due = if (on) d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() else d.toEpochDay() * 86_400_000L
                })
                Text("At a time" + if (hasTime) " · " + timeText(due) else "", Modifier.clickable(enabled = hasTime) { pickTime = true })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(important, { important = it }); Text("Important") }
            if (t.title.isNotEmpty()) WButton("Delete task", onDelete, icon = WonderIcons.Delete, kind = ButtonKind.Plain)
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank(), onClick = {
            // Keep the day-only convention: due holds epoch-day * 1 day in UTC millis when no time is set.
            val normalized = if (due > 0 && hasTime && due < 86_400_000L * 100_000) due else due
            onSave(t.copy(title = title.trim(), notes = notes.trim(), due = normalized, dueHasTime = hasTime && due > 0, priority = if (important) 1 else 0))
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    @OptIn(ExperimentalMaterial3Api::class)
    if (pickDate) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = if (due > 0) (if (hasTime) LocalDateTime.ofInstant(Instant.ofEpochMilli(due), zone).toLocalDate() else LocalDate.ofEpochDay(due / 86_400_000L)).toEpochDay() * 86_400_000L else null)
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton(onClick = { ds.selectedDateMillis?.let { setDay(LocalDate.ofEpochDay(it / 86_400_000L)) }; pickDate = false }) { Text("OK") } }) { DatePicker(ds) }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    if (pickTime) {
        val cur = LocalDateTime.ofInstant(Instant.ofEpochMilli(due), zone)
        val ts = rememberTimePickerState(cur.hour, cur.minute)
        WonderDialog(onDismissRequest = { pickTime = false }, title = { Text("Due time") }, text = { TimePicker(ts) },
            confirmButton = { TextButton(onClick = { due = cur.toLocalDate().atTime(ts.hour, ts.minute).atZone(zone).toInstant().toEpochMilli(); pickTime = false }) { Text("OK") } })
    }
}

/* ------------------------------------------------ editor ------------------------------------------ */

@Composable
private fun EventEditor(initial: EventDraft, repo: CalendarRepo, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var d by remember { mutableStateOf(initial) }
    val cals = remember { repo.calendars().filter { it.writable } }
    var pick by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val startDt = if (d.allDay) Instant.ofEpochMilli(d.start).atZone(ZoneOffset.UTC).toLocalDateTime() else LocalDateTime.ofInstant(Instant.ofEpochMilli(d.start), zone)
    val endDt = if (d.allDay) Instant.ofEpochMilli(d.end - 1).atZone(ZoneOffset.UTC).toLocalDateTime() else LocalDateTime.ofInstant(Instant.ofEpochMilli(d.end), zone)
    fun ms(dt: LocalDateTime, allDay: Boolean) = if (allDay) dt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else dt.atZone(zone).toInstant().toEpochMilli()
    WonderApp(appName = "Chronicle", about = ABOUT, title = if (initial.id == null) "New event" else "Edit event", onBack = onClose,
        actions = {
            TextButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { repo.save(d) } }
                    if (r.isSuccess) onSaved()
                }
            }) { Text("Save", color = p.toolbarText, style = MaterialTheme.typography.titleMedium) }
        },
        menu = if (initial.id != null) listOf(
            WonderMenuItem("Share as .ics", WonderIcons.Share) {
                val f = java.io.File(context.cacheDir, "share").apply { mkdirs() }.let { java.io.File(it, "${d.title.ifBlank { "Event" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.ics") }
                f.writeText(Ics.export(listOf(d)))
                context.share(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f), "text/calendar") {}
            },
            WonderMenuItem("Delete event", WonderIcons.Delete, danger = true) { confirmDelete = true },
        ) else emptyList()
    ) {
        Page {
            Panel {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(d.title, { d = d.copy(title = it) }, "Title")
                    Field(d.location, { d = d.copy(location = it) }, "Location")
                }
            }
            Panel {
                SwitchRow("All day", d.allDay, { on ->
                    val s = startDt; val e = endDt
                    d = if (on) d.copy(allDay = true, start = ms(s.toLocalDate().atStartOfDay(), true), end = ms(e.toLocalDate().plusDays(1).atStartOfDay(), true))
                        else d.copy(allDay = false, start = ms(s.toLocalDate().atTime(9, 0), false), end = ms(s.toLocalDate().atTime(10, 0), false))
                }, icon = WonderIcons.WbSunny, tint = Color(0xFFF2A516))
                PanelDivider()
                ListRow("Starts", icon = WonderIcons.Schedule, tint = Color(0xFF2E9E44), trailing = {
                    Row { TextButton(onClick = { pick = "startDate" }) { Text(startDt.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) }
                        if (!d.allDay) TextButton(onClick = { pick = "startTime" }) { Text(timeText(d.start)) } }
                })
                PanelDivider()
                ListRow("Ends", icon = WonderIcons.HourglassBottom, tint = Color(0xFFE5483C), trailing = {
                    Row { TextButton(onClick = { pick = "endDate" }) { Text(endDt.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) }
                        if (!d.allDay) TextButton(onClick = { pick = "endTime" }) { Text(timeText(d.end)) } }
                })
                PanelDivider()
                var repeatMenu by remember { mutableStateOf(false) }
                Box {
                    ListRow("Repeat", icon = WonderIcons.Repeat, tint = Color(0xFF8E44AD), trailing = { Text(rruleLabel(d.rrule), color = p.secondaryText) }, chevron = true, onClick = { repeatMenu = true })
                    DropdownMenu(repeatMenu, { repeatMenu = false }, Modifier.background(p.panel)) {
                        listOf("" to "Does not repeat", "FREQ=DAILY" to "Every day", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" to "Every weekday", "FREQ=WEEKLY" to "Every week",
                            "FREQ=WEEKLY;INTERVAL=2" to "Every two weeks", "FREQ=MONTHLY" to "Every month", "FREQ=YEARLY" to "Every year").forEach { (r, l) ->
                            DropdownMenuItem({ Text(l) }, { d = d.copy(rrule = r); repeatMenu = false })
                        }
                    }
                }
            }
            Panel(title = "Reminders") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080).forEach { m ->
                        Chip(reminderLabel(m).replace(" before", ""), m in d.reminders, { d = d.copy(reminders = if (m in d.reminders) d.reminders - m else (d.reminders + m).sorted()) })
                    }
                }
            }
            if (cals.isNotEmpty()) Panel(title = "Calendar") {
                cals.forEachIndexed { i, c ->
                    if (i > 0) PanelDivider()
                    ListRow(c.name, subtitle = c.account.takeIf { it != CalendarRepo.LOCAL_ACCOUNT }, leading = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(c.color))) },
                        selected = c.id == d.calendarId, trailing = { if (c.id == d.calendarId) Icon(WonderIcons.Check, null, tint = p.accent) }, onClick = { d = d.copy(calendarId = c.id) })
                }
            } else Panel { ListRow("Saved on this phone", subtitle = "Add a Google account in Android settings to sync events", icon = WonderIcons.PhoneAndroid, tint = Color(0xFF7A7F87)) }
            Panel { Column(Modifier.padding(12.dp)) { Field(d.notes, { d = d.copy(notes = it) }, "Notes", singleLine = false, minLines = 3) } }
            if (d.start > System.currentTimeMillis()) Text("Starts ${countdown(System.currentTimeMillis(), d.start, d.end)}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    when (pick) {
        "startDate", "endDate" -> {
            val base = if (pick == "startDate") startDt else endDt
            val ds = rememberDatePickerState(initialSelectedDateMillis = base.toLocalDate().toEpochDay() * 86_400_000L)
            DatePickerDialog(onDismissRequest = { pick = null }, confirmButton = {
                TextButton(onClick = {
                    ds.selectedDateMillis?.let { sel ->
                        val nd = LocalDate.ofEpochDay(sel / 86_400_000L)
                        d = if (pick == "startDate") {
                            val len = d.end - d.start
                            val ns = ms(nd.atTime(base.toLocalTime()), d.allDay); d.copy(start = ns, end = ns + len)
                        } else d.copy(end = if (d.allDay) ms(nd.plusDays(1).atStartOfDay(), true) else ms(nd.atTime(base.toLocalTime()), false).coerceAtLeast(d.start))
                    }
                    pick = null
                }) { Text("OK") }
            }) { DatePicker(ds) }
        }
        "startTime", "endTime" -> {
            val base = if (pick == "startTime") startDt else endDt
            val ts = rememberTimePickerState(base.hour, base.minute)
            WonderDialog(onDismissRequest = { pick = null }, title = { Text(if (pick == "startTime") "Start time" else "End time") }, text = { TimePicker(ts) }, confirmButton = {
                TextButton(onClick = {
                    val nt = base.toLocalDate().atTime(ts.hour, ts.minute)
                    d = if (pick == "startTime") { val len = d.end - d.start; val ns = ms(nt, false); d.copy(start = ns, end = ns + len) }
                        else d.copy(end = ms(nt, false).let { if (it <= d.start) it + 86_400_000L else it })
                    pick = null
                }) { Text("OK") }
            })
        }
    }
    if (confirmDelete) ConfirmDialog("Delete event?", if (d.rrule.isNotBlank()) "This deletes every occurrence of this repeating event." else "This removes it from ${cals.find { it.id == d.calendarId }?.name ?: "the calendar"}.",
        "Delete", onConfirm = { scope.launch { withContext(Dispatchers.IO) { runCatching { repo.delete(d.id!!) } }; onSaved() } }, onDismiss = { confirmDelete = false }, danger = true)
}

/* ----------------------------------------------- jump to date -------------------------------------- */

@Composable
private fun JumpDialog(current: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val p = wonderPalette()
    var year by remember { mutableIntStateOf(current.year) }
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { parseDateText(text) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Go to date") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(text, { text = it }, "Type a date", supporting = parsed?.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")) ?: "e.g. 3/14/2021, 2019-07-04, next friday, in 3 weeks", onDone = { parsed?.let(onPick) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Icon(WonderIcons.NavigateBefore, "Previous year", tint = p.accent) }
                Text("$year", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { year++ }) { Icon(WonderIcons.NavigateNext, "Next year", tint = p.accent) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-10, -5, -1, 1, 5).forEach { n -> Chip(if (n > 0) "+$n y" else "$n y", false, { year += n }) }
            }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((1..12).toList()) { m ->
                    val on = year == current.year && m == current.monthValue
                    Box(Modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(if (on) p.accent else p.window).border(1.dp, p.panelBorder, RoundedCornerShape(8.dp))
                        .clickable { onPick(LocalDate.of(year, m, 1.coerceAtLeast(if (year == current.year && m == current.monthValue) current.dayOfMonth else 1))) }, contentAlignment = Alignment.Center) {
                        Text(java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = if (on) p.onAccent else p.text)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onPick) }) { Text("Go") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Understands common written dates and simple phrases. Pure for testing. */
fun parseDateText(s: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val t = s.trim().lowercase()
    if (t.isEmpty()) return null
    when (t) { "today" -> return today; "tomorrow" -> return today.plusDays(1); "yesterday" -> return today.minusDays(1) }
    Regex("^in (\\d+) (day|days|week|weeks|month|months|year|years)$").find(t)?.let { m ->
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].trimEnd('s')) { "day" -> today.plusDays(n); "week" -> today.plusWeeks(n); "month" -> today.plusMonths(n); else -> today.plusYears(n) }
    }
    Regex("^(\\d+) (day|days|week|weeks|month|months|year|years) ago$").find(t)?.let { m ->
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].trimEnd('s')) { "day" -> today.minusDays(n); "week" -> today.minusWeeks(n); "month" -> today.minusMonths(n); else -> today.minusYears(n) }
    }
    Regex("^(next|last) (monday|tuesday|wednesday|thursday|friday|saturday|sunday)$").find(t)?.let { m ->
        val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
        return if (m.groupValues[1] == "next") today.with(java.time.temporal.TemporalAdjusters.next(dow)) else today.with(java.time.temporal.TemporalAdjusters.previous(dow))
    }
    val patterns = listOf("yyyy-M-d", "M/d/yyyy", "M/d/yy", "d.M.yyyy", "MMMM d yyyy", "MMM d yyyy", "d MMMM yyyy", "d MMM yyyy", "MMMM d", "MMM d", "d MMMM", "MMMM yyyy", "yyyy")
    val cleaned = t.replace(",", "").replace(Regex("(\\d)(st|nd|rd|th)"), "$1")
    for (pat in patterns) {
        runCatching {
            val f = java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pat)
                .parseDefaulting(java.time.temporal.ChronoField.YEAR_OF_ERA, today.year.toLong())
                .parseDefaulting(java.time.temporal.ChronoField.DAY_OF_MONTH, 1).parseDefaulting(java.time.temporal.ChronoField.MONTH_OF_YEAR, 1)
                .toFormatter(Locale.ENGLISH)
            return LocalDate.parse(cleaned, f)
        }
    }
    return null
}

/* ------------------------------------------------ settings ----------------------------------------- */

@Composable
private fun CalendarSettings(repo: CalendarRepo, granted: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.prefs("chronicle") }
    var reminders by remember { mutableStateOf(prefs.getBoolean("reminders", true)) }
    var cals by remember { mutableStateOf(if (granted) repo.calendars() else emptyList()) }
    if (granted) Panel(title = "Calendars", footer = "Calendars come from the accounts on this phone. To add Google Calendar, add your Google account in Android settings; Chronicle syncs through it.") {
        if (cals.isEmpty()) ListRow("No calendars yet", subtitle = "Events you create are saved on this phone")
        cals.forEachIndexed { i, c ->
            if (i > 0) PanelDivider()
            SwitchRow(c.name, c.visible, { on -> repo.setVisible(c.id, on); cals = repo.calendars(); revision.intValue++ }, subtitle = c.account)
        }
        PanelDivider()
        ListRow("Add an account", icon = WonderIcons.Person, tint = Color(0xFF1E7FE8), chevron = true, onClick = {
            context.launchSafely(Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))) {}
        })
    }
    Panel(title = "Notifications") {
        SwitchRow("Event reminders", reminders, { reminders = it; prefs.edit().putBoolean("reminders", it).apply() },
            subtitle = "Chronicle notifies you at each event's reminder time", icon = WonderIcons.Notifications, tint = Color(0xFFE5483C))
    }
}
