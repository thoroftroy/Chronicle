package dev.wonderdesk.shared

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.DatePicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keep the selected day in the displayed month, clamping at shorter month ends. */
fun shiftCalendarSelection(date: LocalDate, months: Long): LocalDate = date.plusMonths(months)

/** Fixed 9am start; durations are the only choices offered, so no other validation is needed. */
fun chronicleEventEnd(startMillis: Long, durationMinutes: Int): Long {
    require(durationMinutes in listOf(30, 60, 120)) { "Unsupported duration" }
    return startMillis + durationMinutes * 60_000L
}

/** How long until the event starts, like "in 3d 4h" or "started 10m ago". Pure for testing. */
fun formatCountdown(nowMillis: Long, eventStartMillis: Long): String {
    val diff = eventStartMillis - nowMillis
    val minutes = diff / 60_000L
    if (minutes >= 0) {
        val days = minutes / 1_440L
        val hours = (minutes % 1_440L) / 60L
        val mins = minutes % 60L
        return when {
            days > 0 -> "in ${days}d ${hours}h"
            hours > 0 -> "in ${hours}h ${mins}m"
            else -> "in ${mins}m"
        }
    }
    val ago = -minutes
    val days = ago / 1_440L
    val hours = (ago % 1_440L) / 60L
    val mins = ago % 60L
    return when {
        days > 0 -> "started ${days}d ago"
        hours > 0 -> "started ${hours}h ago"
        else -> "started ${mins}m ago"
    }
}

data class DayEvent(val title: String, val startMillis: Long, val endMillis: Long)

fun eventsOn(context: Context, date: LocalDate): List<DayEvent> {
    if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val out = mutableListOf<DayEvent>()
    runCatching {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
            "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?",
            arrayOf(end.toString(), start.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { c ->
            val t = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val s = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val e = c.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            while (c.moveToNext()) {
                out += DayEvent(c.getString(t) ?: "Event", c.getLong(s), c.getLong(e))
            }
        }
    }
    return out
}

@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val op = rememberOperation()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var title by rememberSaveable { mutableStateOf("") }
    var durationMinutes by rememberSaveable { mutableIntStateOf(60) }
    var pickMonth by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf(listOf<DayEvent>()) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    fun loadEvents() { op.run("Reading calendar", { eventsOn(context, selected) }) { events = it } }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { loadEvents() }
    LaunchedEffect(selectedText) { loadEvents() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                val s = shiftCalendarSelection(selected, -1)
                selectedText = s.toString(); monthText = YearMonth.from(s).toString()
            }) { Text("Previous") }
            TextButton(onClick = { pickMonth = true }) { Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy")), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = {
                val s = shiftCalendarSelection(selected, 1)
                selectedText = s.toString(); monthText = YearMonth.from(s).toString()
            }) { Text("Next") }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { monthText = YearMonth.now().toString(); selectedText = LocalDate.now().toString() }) { Text("Today") }
            TextButton(onClick = {
                DatePickerDialog(context, { _: DatePicker, year: Int, zeroBasedMonth: Int, day: Int ->
                    val date = LocalDate.of(year, zeroBasedMonth + 1, day)
                    selectedText = date.toString(); monthText = YearMonth.from(date).toString()
                }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
            }) { Text("Jump to date…") }
        }
        Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.labelSmall) } }
        val leading = month.atDay(1).dayOfWeek.value - 1
        val cells = (List(leading) { 0 } + (1..month.lengthOfMonth()).toList()).chunked(7)
        cells.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (week + List(7 - week.size) { 0 }).forEach { day ->
                    if (day == 0) Spacer(Modifier.weight(1f))
                    else {
                        val isSelected = selectedText == month.atDay(day).toString()
                        val isToday = month.atDay(day) == LocalDate.now()
                        TextButton(onClick = { selectedText = month.atDay(day).toString() }, Modifier.weight(1f).heightIn(min = 44.dp), contentPadding = PaddingValues(0.dp)) {
                            Text(if (isSelected) "[$day]" else "$day", style = if (isToday) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Text("Selected: $selectedText · ${ZoneId.systemDefault().id}")
        // Events on the selected day with countdowns.
        if (events.isEmpty()) {
            Text("No calendar events on this day that this app can read.", style = MaterialTheme.typography.bodySmall)
            if (!permissionAsked && context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                TextButton(onClick = { permissionAsked = true; permissionLauncher.launch(android.Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar access to show events") }
            }
        } else {
            events.forEach { e ->
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                val startLabel = Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.systemDefault()).format(formatter)
                val endLabel = Instant.ofEpochMilli(e.endMillis).atZone(ZoneId.systemDefault()).format(formatter)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(e.title)
                        Text("$startLabel–$endLabel · ${formatCountdown(System.currentTimeMillis(), e.startMillis)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Event title") }, singleLine = true)
        Row(Modifier.fillMaxWidth()) {
            listOf(30, 60, 120).forEach { minutes ->
                TextButton(onClick = { durationMinutes = minutes }, modifier = Modifier.weight(1f)) { Text(if (durationMinutes == minutes) "• $minutes min" else "$minutes min") }
            }
        }
        AquaButton(onClick = {
            val start = selected.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            context.launchSafely(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, chronicleEventEnd(start, durationMinutes)), op::notice)
        }, enabled = title.isNotBlank()) { Text("Create ${durationMinutes}-min event in calendar app…") }
        OperationStatus(op)
        Text("Events come from your installed calendar accounts. Creating sends the details to your calendar app to review and save. Countdowns are from this phone's clock.", style = MaterialTheme.typography.bodySmall)
    }
    if (pickMonth) AlertDialog(onDismissRequest = { pickMonth = false }, title = { Text("Pick a month") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var year by remember { mutableStateOf(month.year) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { year-- }) { Text("◀") }
                Text(year.toString(), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { year++ }) { Text("▶") }
            }
            listOf(1..6, 7..12).forEach { rowRange ->
                Row(Modifier.fillMaxWidth()) {
                    rowRange.forEach { m ->
                        TextButton(onClick = { monthText = YearMonth.of(year, m).toString(); selectedText = LocalDate.of(year, m, 1).toString(); pickMonth = false }, Modifier.weight(1f)) {
                            Text(YearMonth.of(year, m).format(DateTimeFormatter.ofPattern("MMM")))
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { pickMonth = false }) { Text("Close") } })
}
